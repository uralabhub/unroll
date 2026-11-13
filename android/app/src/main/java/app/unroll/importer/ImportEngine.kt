package app.unroll.importer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.unroll.AppState
import app.unroll.MainActivity
import app.unroll.R
import app.unroll.UnrollApplication
import app.unroll.core.Fix
import app.unroll.core.GpsInjector
import app.unroll.core.HeaderParser
import app.unroll.core.ShotFormat
import app.unroll.core.ShotMeta
import app.unroll.data.AlbumMode
import app.unroll.source.ImportState
import app.unroll.source.Shot
import app.unroll.source.SourceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RunSummary(val count: Int, val seconds: Double, val geotagged: Int)

/** The transfer pipeline: copy → (RAW twin) → locate → save to MediaStore → remember. */
class ImportEngine(private val app: AppState) {
    var queue by mutableStateOf<List<Shot>>(emptyList())
        private set
    var current by mutableStateOf<Shot?>(null)
        private set
    var completedInRun by mutableIntStateOf(0)
        private set
    var totalInRun by mutableIntStateOf(0)
        private set
    var bytesInRun by mutableLongStateOf(0L)
        private set
    private var runStarted = 0L
    private var geotaggedInRun = 0
    var currentFraction by mutableDoubleStateOf(0.0)
        private set
    var lastError by mutableStateOf<String?>(null)
    var lastFinishedRun by mutableStateOf<RunSummary?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var worker: Job? = null

    val isRunning: Boolean get() = current != null || queue.isNotEmpty()

    val progress: Double get() = if (totalInRun == 0) 0.0 else (completedInRun + currentFraction) / totalInRun

    val throughputMBps: Double?
        get() {
            val t = (System.currentTimeMillis() - runStarted) / 1000.0
            return if (bytesInRun > 0 && t > 0.5) bytesInRun / 1_048_576.0 / t else null
        }

    /** Queues shots. Returns how many were refused by the free daily quota. */
    fun enqueue(shots: List<Shot>): Int {
        val fresh = shots.filter { it.importState != ImportState.Queued && current?.id != it.id && it.importState != ImportState.Done && !it.wasImported }
            .distinctBy { it.id }
        var accepted = fresh
        var refused = 0
        if (!app.pro.isPro) {
            val room = maxOf(0, app.quota.remaining - queue.size - if (current == null) 0 else 1)
            if (accepted.size > room) { refused = accepted.size - room; accepted = accepted.take(room) }
        }
        if (accepted.isEmpty()) return refused
        if (!isRunning) {
            completedInRun = 0; totalInRun = 0; bytesInRun = 0; geotaggedInRun = 0; runStarted = System.currentTimeMillis()
        }
        accepted.forEach { it.importState = ImportState.Queued }
        queue = queue + accepted
        totalInRun += accepted.size
        startIfNeeded()
        return refused
    }

    fun cancelAll() {
        queue.forEach { it.importState = ImportState.None }
        totalInRun -= queue.size
        queue = emptyList()
    }

    private fun startIfNeeded() {
        if (worker != null) return
        ImportService.start(UnrollApplication.context)
        worker = scope.launch {
            while (queue.isNotEmpty()) {
                val shot = queue.first()
                queue = queue.drop(1)
                current = shot
                currentFraction = 0.0
                importOne(shot)
                completedInRun += 1
                currentFraction = 0.0
            }
            current = null
            lastFinishedRun = RunSummary(completedInRun, (System.currentTimeMillis() - runStarted) / 1000.0, geotaggedInRun)
            worker = null
            if (app.settings.autoEject && app.session?.kind == SourceKind.USB) app.usb.eject()
        }
    }

    private fun progress(shot: Shot, f: Double) {
        currentFraction = f
        shot.importState = ImportState.Importing(f)
    }

    private suspend fun importOne(shot: Shot) {
        progress(shot, 0.0)
        val ctx = UnrollApplication.context
        try {
            val raw = shot.raw
            val wantsRAW = app.settings.includeRAW && raw != null
            val mainShare = if (wantsRAW) 0.5 else 1.0

            if (shot.meta == null) {
                runCatching { shot.primary.read(0, HeaderParser.HEADER_LENGTH) }.getOrNull()?.let {
                    val info = withContext(Dispatchers.Default) { HeaderParser.parse(it) }
                    shot.meta = info.meta; shot.preview = info.preview
                }
            }
            val meta = shot.meta ?: ShotMeta()
            val date = app.correctedDate(shot)
            var location: Fix? = null
            val inFile = meta.latitude != null && meta.longitude != null
            if (!inFile && app.settings.geotag) {
                location = app.walks.location(date)
                if (location == null && app.settings.borrowPhoneLocations) {
                    location = withContext(Dispatchers.IO) { PhoneLocationLookup.location(ctx, date) }
                }
            }
            val folder = when (app.settings.albumMode) {
                AlbumMode.SINGLE -> "DCIM/Unroll"
                AlbumMode.PER_CAMERA -> "DCIM/Unroll/" + meta.cameraName.replace(Regex("[^A-Za-z0-9 ._-]"), "").trim().ifEmpty { "Camera" }
                AlbumMode.NONE -> "DCIM/Camera"
            }
            val onMain: (Double) -> Unit = { f -> scope.launch { progress(shot, f) } }

            // Main file. A JPEG that needs a location gets the GPS block appended; anything else
            // (and a JPEG we can't safely extend) is streamed in untouched.
            var geotagged = false
            val saved = withContext(Dispatchers.IO) {
                val loc = location
                if (loc != null && shot.primary.format == ShotFormat.JPEG && shot.primary.knownSize in 1..80_000_000) {
                    val bytes = shot.primary.readAll().also { onMain(mainShare * 0.9) }
                    val withGPS = GpsInjector.inject(bytes, loc.lat, loc.lon)
                    geotagged = withGPS != null
                    bytesInRun += bytes.size
                    MediaStoreSaver.save(ctx, shot.primary.name, false, folder, date) { it.write(withGPS ?: bytes) }
                } else {
                    var n = 0L
                    MediaStoreSaver.save(ctx, shot.primary.name, shot.isVideo, folder, date) { out ->
                        val counting = object : java.io.FilterOutputStream(out) {
                            override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); n += len }
                        }
                        shot.primary.copyTo(counting) { f -> onMain(f * mainShare) }
                    }.also { bytesInRun += n }
                }
            }
            // RAW twin: always byte for byte, named after the JPEG as saved (MediaStore may have
            // renamed it to avoid a clash), so the pair stays linked by name.
            if (wantsRAW && raw != null) {
                val stem = saved.displayName.substringBeforeLast('.')
                val rawName = stem + "." + raw.name.substringAfterLast('.', "RAW")
                withContext(Dispatchers.IO) {
                    var n = 0L
                    MediaStoreSaver.save(ctx, rawName, false, folder, date) { out ->
                        val counting = object : java.io.FilterOutputStream(out) {
                            override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); n += len }
                        }
                        raw.copyTo(counting) { f -> onMain(0.5 + f * 0.5) }
                    }
                    bytesInRun += n
                }
            }
            if (geotagged) geotaggedInRun += 1
            app.ledger.mark(shot.id)
            if (!app.pro.isPro) app.quota.consume()
            meta.recipe?.let { app.recipes.record(it, meta.cameraName, date) }
            shot.wasImported = true
            shot.importState = ImportState.Done
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            shot.importState = ImportState.Failed(msg)
            lastError = "${shot.primary.name}: $msg"
        }
    }

    fun close() = scope.cancel()
}

/**
 * Keeps the process in the foreground while an import runs, so switching apps or locking the
 * phone doesn't kill a 300-shot transfer. The work itself runs in [ImportEngine]; this service
 * shows its progress and stops when the queue is empty.
 */
class ImportService : Service() {
    companion object {
        private const val CHANNEL = "import"
        private const val ID = 7

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, ImportService::class.java)) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Imports", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, ID, build(0, 0, 0.0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val engine = (application as UnrollApplication).state.importer
        scope.coroutineContext[Job]?.children?.forEach { it.cancel() }
        scope.launch {
            snapshotFlow { Triple(engine.isRunning, engine.completedInRun, engine.progress) }.collect { (running, done, p) ->
                if (!running) {
                    ServiceCompat.stopForeground(this@ImportService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    nm.notify(ID, build(done, engine.totalInRun, p))
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun build(done: Int, total: Int, p: Double): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_unroll)
            .setContentTitle(if (total == 0) "Importing" else "Importing ${minOf(done + 1, total)} of $total")
            .setContentText("Originals go to the photo library untouched")
            .setProgress(1000, (p * 1000).toInt(), total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
