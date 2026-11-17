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
import android.os.SystemClock
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
import app.unroll.data.DebouncedWriter
import app.unroll.data.Disk
import app.unroll.source.CameraSession
import app.unroll.source.FileLocation
import app.unroll.source.Http
import app.unroll.source.ImportState
import app.unroll.source.Shot
import app.unroll.source.SourceKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

class RunSummary(val count: Int, val seconds: Double, val geotagged: Int, val failed: Int = 0,
                 val endedAt: Long = System.currentTimeMillis())

/** Shot ids still to import, persisted so a run survives the process being killed or the cable pulled. */
private class ImportQueueStore(private val disk: Disk, scope: CoroutineScope) {
    @Serializable
    data class Saved(val savedAt: Long, val ids: List<String>)

    private val writer = DebouncedWriter(disk, scope, Saved.serializer(), "import-queue.json", delayMs = 300)

    fun load(): List<String> {
        val s = disk.load(Saved.serializer(), "import-queue.json") ?: return emptyList()
        // A queue nobody came back for in 3 days is stale.
        return if (System.currentTimeMillis() - s.savedAt < 3L * 86_400_000) s.ids else emptyList()
    }

    fun save(ids: List<String>) {
        val snapshot = Saved(System.currentTimeMillis(), ids)
        writer.schedule { snapshot }
    }
}

/**
 * The transfer pipeline: copy → (RAW twin) → locate → save to MediaStore → remember.
 *
 * Unplugging the camera (or the camera hanging, or Wi-Fi dropping) *pauses* the run instead of
 * failing it: the remaining shots wait, by id, for the next session that has them and continue
 * when it is ready. Other errors are retried a couple of times; what still fails is counted apart
 * from what was saved.
 */
class ImportEngine(private val app: AppState, disk: Disk) {
    var queue by mutableStateOf<List<Shot>>(emptyList())
        private set
    var current by mutableStateOf<Shot?>(null)
        private set
    /** Saved successfully in this run. */
    var completedInRun by mutableIntStateOf(0)
        private set
    var failedInRun by mutableIntStateOf(0)
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
    /** Why the run is paused (camera gone), or null. Shots then wait in [waitingCount]. */
    var paused by mutableStateOf<String?>(null)
        private set
    var waitingCount by mutableIntStateOf(0)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var worker: Job? = null
    private val store = ImportQueueStore(disk, app.scope)
    /** Shots waiting for a session that has them (Shot is null when restored from disk). */
    private val waiting = LinkedHashMap<String, Shot?>()
    private val lastUiTick = AtomicLong(0)

    init {
        store.load().forEach { waiting[it] = null }
        waitingCount = waiting.size
    }

    val isRunning: Boolean get() = current != null || queue.isNotEmpty()

    /** Done (saved or failed) out of the run's total. */
    private val finishedInRun: Int get() = completedInRun + failedInRun

    val progress: Double get() = if (totalInRun == 0) 0.0 else (finishedInRun + currentFraction) / totalInRun

    val throughputMBps: Double?
        get() {
            val t = (System.currentTimeMillis() - runStarted) / 1000.0
            return if (bytesInRun > 0 && t > 0.5) bytesInRun / 1_048_576.0 / t else null
        }

    private fun resetRun() {
        completedInRun = 0; failedInRun = 0; totalInRun = 0; bytesInRun = 0; geotaggedInRun = 0; runStarted = System.currentTimeMillis()
    }

    private fun persist() {
        val ids = LinkedHashSet<String>()
        current?.let { ids += it.id }
        queue.forEach { ids += it.id }
        ids += waiting.keys
        waitingCount = waiting.size
        store.save(ids.toList())
    }

    /** Queues shots. Returns how many were refused by the free daily quota. */
    fun enqueue(shots: List<Shot>): Int {
        val fresh = shots.filter { it.importState != ImportState.Queued && current?.id != it.id && it.importState != ImportState.Done && !it.wasImported }
            .distinctBy { it.id }
        var accepted = fresh
        var refused = 0
        if (!app.pro.isPro) {
            val room = maxOf(0, app.quota.remaining - queue.size - waiting.size - if (current == null) 0 else 1)
            if (accepted.size > room) { refused = accepted.size - room; accepted = accepted.take(room) }
        }
        if (accepted.isEmpty()) return refused
        if (!isRunning) resetRun()
        accepted.forEach { it.importState = ImportState.Queued; waiting.remove(it.id) }
        queue = queue + accepted
        totalInRun += accepted.size
        persist()
        startIfNeeded()
        return refused
    }

    fun cancelAll() {
        queue.forEach { it.importState = ImportState.None }
        waiting.values.forEach { it?.importState = ImportState.None }
        totalInRun -= queue.size
        queue = emptyList()
        waiting.clear()
        paused = null
        persist()
    }

    /**
     * A session became ready: shots that were waiting for it (cable pulled, camera hung, app
     * killed) continue — matched by shot id, since the reconnected camera gives new objects.
     */
    fun resume(session: CameraSession) {
        if (waiting.isEmpty()) return
        val back = waiting.keys.mapNotNull { session.shot(it) }.filter { !it.wasImported && !app.ledger.contains(it.id) }
        if (back.isEmpty()) return
        back.forEach { waiting.remove(it.id); it.importState = ImportState.Queued }
        if (!isRunning) resetRun()
        totalInRun += back.size
        if (waiting.isEmpty()) paused = null
        queue = queue + back
        persist()
        startIfNeeded()
    }

    /** The foreground service hit Android's time limit (dataSync, Android 15+). */
    fun onServiceTimeout() {
        lastError = "Android paused background importing. Open Unroll to let it finish."
    }

    private fun startIfNeeded() {
        if (worker != null) return
        ImportService.start(UnrollApplication.context)?.let { lastError = it }
        lastFinishedRun = null
        worker = scope.launch {
            var endedPaused = false
            while (queue.isNotEmpty()) {
                val shot = queue.first()
                queue = queue.drop(1)
                current = shot
                currentFraction = 0.0
                val r = importWithRetry(shot)
                currentFraction = 0.0
                when (r) {
                    Outcome.SAVED -> completedInRun += 1
                    Outcome.FAILED -> failedInRun += 1
                    Outcome.PAUSED -> { pauseRun(shot); endedPaused = true; break }
                }
                persist()
            }
            current = null
            worker = null
            persist()
            if (!endedPaused) {
                lastFinishedRun = RunSummary(completedInRun, (System.currentTimeMillis() - runStarted) / 1000.0, geotaggedInRun, failedInRun)
                if (app.settings.autoEject && app.session?.kind == SourceKind.USB) app.usb.eject()
            }
        }
    }

    /** Moves [shot] and everything after it to the waiting list until the camera is back. */
    private fun pauseRun(shot: Shot) {
        val rest = listOf(shot) + queue
        rest.forEach { it.importState = ImportState.Queued; waiting[it.id] = it }
        // They leave this run; resume() counts them into the next one.
        totalInRun -= rest.size
        queue = emptyList()
        paused = "Camera disconnected"
        lastError = "Camera disconnected. Import paused: plug it back in and it continues (${rest.size} left)."
    }

    private enum class Outcome { SAVED, FAILED, PAUSED }

    /** The source is gone (cable pulled, camera hung and was closed, Wi-Fi lost), not this file. */
    private fun sourceGone(shot: Shot, e: Throwable): Boolean = when (val l = shot.primary.location) {
        is FileLocation.Mtp -> l.camera.closed
        is FileLocation.Http -> e is IOException && e !is Http.StatusException
        else -> false
    }

    private suspend fun importWithRetry(shot: Shot): Outcome {
        var attempt = 0
        while (true) {
            try {
                importOne(shot)
                return Outcome.SAVED
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (sourceGone(shot, e)) return Outcome.PAUSED
                val transient = e is IOException
                if (!transient || attempt >= RETRIES) {
                    val msg = e.message ?: e.javaClass.simpleName
                    shot.importState = ImportState.Failed(msg)
                    lastError = "${shot.primary.name}: $msg"
                    return Outcome.FAILED
                }
                delay(1000L shl attempt)                  // 1 s, 2 s
                if (sourceGone(shot, e)) return Outcome.PAUSED
                attempt += 1
            }
        }
    }

    private fun progress(shot: Shot, f: Double) {
        currentFraction = f
        shot.importState = ImportState.Importing(f)
    }

    /** Progress from the copy loop (an I/O thread), forwarded to the UI at most ~10× a second. */
    private fun reporter(shot: Shot): (Double) -> Unit = { f ->
        val now = SystemClock.uptimeMillis()
        val last = lastUiTick.get()
        if ((now - last >= 100 || f >= 1.0) && lastUiTick.compareAndSet(last, now)) scope.launch { if (current === shot && shot.importState is ImportState.Importing) progress(shot, f) }
    }

    /** Drops the first [skip] bytes written through it (the original head replaced by a patched one). */
    private class SkipFirst(out: OutputStream, private var skip: Long) : FilterOutputStream(out) {
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (skip >= len) { skip -= len; return }
            val from = skip.toInt()
            skip = 0
            out.write(b, off + from, len - from)
        }
        override fun write(b: Int) { if (skip > 0) skip -= 1 else out.write(b) }
    }

    private suspend fun importOne(shot: Shot) {
        progress(shot, 0.0)
        val ctx = UnrollApplication.context
        val raw = shot.raw
        val wantsRAW = app.settings.includeRAW && raw != null
        val mainShare = if (wantsRAW) 0.5 else 1.0

        if (shot.meta == null) {
            runCatching { withContext(Dispatchers.IO) { CameraSession.readHeader(shot.primary) } }.getOrNull()?.let {
                shot.meta = it.info.meta; shot.preview = it.info.preview; shot.rgbPreview = it.info.rgbPreview
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
        val report = reporter(shot)
        var geotagged = false
        var bytes = 0L

        // Both halves are written as pending items and published together; any failure deletes both.
        val pendings = ArrayList<MediaStoreSaver.Pending>()
        try {
            withContext(Dispatchers.IO) {
                val main = MediaStoreSaver.create(ctx, shot.primary.name, shot.isVideo, folder, date).also { pendings += it }
                var expected = shot.primary.verifiableSize
                // A JPEG that needs a location gets the GPS block appended to its EXIF. Only the
                // head is patched in memory; the rest streams through, so file size doesn't matter.
                val loc = location
                val patch = if (loc != null && shot.primary.format == ShotFormat.JPEG) {
                    runCatching { shot.primary.read(0, HeaderParser.HEADER_LENGTH) }.getOrNull()
                        ?.let { GpsInjector.injectHead(it, loc.lat, loc.lon) }
                } else null
                val written = MediaStoreSaver.write(ctx, main) { out ->
                    if (patch != null) {
                        out.write(patch.prefix)
                        shot.primary.copyTo(SkipFirst(out, patch.replaces.toLong())) { f -> report(f * mainShare) }
                    } else {
                        shot.primary.copyTo(out) { f -> report(f * mainShare) }
                    }
                }
                if (patch != null) {
                    geotagged = true
                    expected = expected?.let { it - patch.replaces + patch.prefix.size }
                }
                if (expected != null && written != expected) throw IOException("Copied $written of $expected bytes")
                bytes += written

                // RAW twin: always byte for byte, named after the JPEG as saved (MediaStore may have
                // renamed it to avoid a clash), so the pair stays linked by name.
                if (wantsRAW && raw != null) {
                    val stem = main.displayName.substringBeforeLast('.')
                    val rawName = stem + "." + raw.name.substringAfterLast('.', "RAW")
                    val rp = MediaStoreSaver.create(ctx, rawName, false, folder, date).also { pendings += it }
                    val n = MediaStoreSaver.write(ctx, rp) { out -> raw.copyTo(out) { f -> report(mainShare + f * (1 - mainShare)) } }
                    raw.verifiableSize?.let { if (n != it) throw IOException("Copied $n of $it bytes of ${raw.name}") }
                    bytes += n
                }
                MediaStoreSaver.publish(ctx, pendings, date)
            }
        } catch (e: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { MediaStoreSaver.discard(ctx, pendings) }
            throw e
        }
        bytesInRun += bytes
        if (geotagged) geotaggedInRun += 1
        app.ledger.mark(shot.id)
        if (!app.pro.isPro) app.quota.consume()
        meta.recipe?.let { app.recipes.record(it, meta.cameraName, date) }
        shot.wasImported = true
        shot.importState = ImportState.Done
    }

    fun close() = scope.cancel()

    companion object {
        private const val RETRIES = 2
    }
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

        /** Starts the foreground service; returns a user-facing notice when Android refuses. */
        fun start(context: Context): String? = try {
            context.startForegroundService(Intent(context, ImportService::class.java))
            null
        } catch (e: Exception) {
            // Android 12+: ForegroundServiceStartNotAllowedException when started from the background.
            "Android didn't allow importing in the background. Keep Unroll open until the import finishes."
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Imports", NotificationManager.IMPORTANCE_LOW))
        val engine = (application as UnrollApplication).state.importer
        try {
            ServiceCompat.startForeground(this, ID, build(0, 0, 0.0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException / SecurityException: say so instead of failing silently.
            engine.lastError = "Android didn't allow importing in the background. Keep Unroll open until the import finishes."
            stopSelf()
            return START_NOT_STICKY
        }
        scope.coroutineContext[Job]?.children?.forEach { it.cancel() }
        scope.launch {
            snapshotFlow { Triple(engine.isRunning, engine.completedInRun + engine.failedInRun, (engine.progress * 100).toInt() / 100.0) }.collect { (running, done, p) ->
                if (!running) {
                    ServiceCompat.stopForeground(this@ImportService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    nm.notify(ID, build(done, engine.totalInRun, p))
                }
            }
        }
        // Killed under memory pressure mid-run: Android restarts the service; the queue itself is
        // persisted by the engine and resumes when the camera's session is ready again.
        return START_REDELIVER_INTENT
    }

    /** Android 15+: dataSync services get ~6 h a day. Stop cleanly and tell the user. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        (application as UnrollApplication).state.importer.onServiceTimeout()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
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
