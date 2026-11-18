package app.unroll

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.unroll.billing.ProStore
import app.unroll.core.Fix
import app.unroll.core.ShotMeta
import app.unroll.data.AutoImportRule
import app.unroll.data.Disk
import app.unroll.data.ImportLedger
import app.unroll.data.MetaCache
import app.unroll.data.Quota
import app.unroll.data.RecipeBook
import app.unroll.data.Settings
import app.unroll.data.WalkStore
import app.unroll.importer.ImportEngine
import app.unroll.location.WalkRecorder
import app.unroll.source.CameraSession
import app.unroll.source.DemoRollWriter
import app.unroll.source.LocalSource
import app.unroll.source.RicohSource
import app.unroll.source.SafSource
import app.unroll.source.SessionStatus
import app.unroll.source.Shot
import app.unroll.source.SourceKind
import app.unroll.source.UsbCameraService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Screens pushed over Home / Library. */
sealed interface Screen {
    data class Detail(val shots: List<Shot>, val startId: String) : Screen
    data class Frame(val image: Bitmap, val meta: ShotMeta?, val location: Fix?) : Screen
    data class Recap(val shots: List<Shot>) : Screen
    data object Recipes : Screen
    data class RecipeDetail(val id: String) : Screen
    data object ClockSync : Screen
    data object Settings : Screen
    data object Walks : Screen
}

class AppState(private val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val disk = Disk(context)
    val settings = Settings(context, disk)
    val ledger = ImportLedger(disk, scope)
    val recipes = RecipeBook(disk, scope)
    val quota = Quota(context)
    val pro = ProStore(context)
    val walks = WalkRecorder(context, WalkStore(disk))
    val usb = UsbCameraService(context)
    val metaCache = MetaCache(disk, scope)
    val importer = ImportEngine(this, disk)

    var session by mutableStateOf<CameraSession?>(null)
        private set
    var showPaywall by mutableStateOf(false)
    var toast by mutableStateOf<String?>(null)
    /** Shown as a toast once the paywall closes, so it never covers the plans. */
    var paywallNote: String? = null
    /** The first-import permission prompt runs once per app run, not again on every reconnect. */
    var askedImportPermissions = false
    /** True for a few seconds after an import run ends, while its summary is on screen. */
    var showingSummary by mutableStateOf(false)
    val stack = mutableStateListOf<Screen>()

    init {
        usb.makeSession = { name -> CameraSession(SourceKind.USB, name, metaCache, ledger) }
        usb.onSession = { s -> activate(s); watchReady(s) }
        usb.start()
        pro.start()
    }

    fun push(s: Screen) { stack.add(s) }
    fun pop(): Boolean = if (stack.isNotEmpty()) { stack.removeAt(stack.lastIndex); true } else false

    // Sessions

    private fun activate(s: CameraSession) {
        session?.close()
        stack.clear()
        s.onNewShots = { new -> autoImport(new) }
        val previousClose = s.onClose
        s.onClose = {
            previousClose?.invoke()
            if (session === s) session = null
        }
        session = s
    }

    /** USB sessions catalog asynchronously: auto-import once the camera says the catalog is complete. */
    private fun watchReady(s: CameraSession) {
        scope.launch {
            val st = androidx.compose.runtime.snapshotFlow { s.status }.first { it == SessionStatus.Ready || it is SessionStatus.Failed }
            if (st == SessionStatus.Ready && session === s) {
                importer.resume(s)                 // a paused / persisted run continues first
                autoImport(s.shots)
            }
        }
    }

    fun openFolder(tree: Uri) {
        val s = CameraSession(SourceKind.CARD, SafSource.displayName(tree), metaCache, ledger)
        s.status = SessionStatus.Cataloging(0)
        activate(s)
        scope.launch {
            val shots = runCatching { SafSource.scan(context, tree) }.getOrElse {
                s.status = SessionStatus.Failed("Couldn't read that folder: ${it.message}")
                return@launch
            }
            finishOpen(s, shots, nameFromCamera = true)
        }
    }

    fun openDemo() {
        val s = CameraSession(SourceKind.DEMO, "X100VI · Demo Roll", metaCache, ledger)
        s.status = SessionStatus.Cataloging(0)
        activate(s)
        scope.launch {
            val root = DemoRollWriter.prepare(context)
            walks.installDemoWalk()
            finishOpen(s, LocalSource.scan(root), nameFromCamera = false)
        }
    }

    fun connectRicoh() {
        val s = CameraSession(SourceKind.WIFI, "Ricoh GR", metaCache, ledger)
        activate(s)
        scope.launch {
            try {
                val (model, shots) = RicohSource.connect(context)
                s.name = model
                s.replaceShots(shots)
                s.status = SessionStatus.Ready
                importer.resume(s)
            } catch (e: Exception) {
                s.status = SessionStatus.Failed("Couldn't reach the camera. Join the GR's Wi-Fi in Settings, then try again.")
            }
        }
    }

    private suspend fun finishOpen(s: CameraSession, shots: List<Shot>, nameFromCamera: Boolean) {
        s.replaceShots(shots)
        s.status = SessionStatus.Ready
        importer.resume(s)
        // Name the session after the camera once the newest header is in.
        s.shots.firstOrNull()?.let { newest ->
            s.loadMeta(newest)
            if (nameFromCamera) newest.meta?.cameraName?.let { if (newest.meta?.model != null) s.name = it }
        }
        autoImport(s.shots)
    }

    fun disconnect() {
        val s = session ?: return
        if (s.kind == SourceKind.USB) usb.eject()
        s.close()
        session = null
        stack.clear()
    }

    // Import

    /**
     * Auto-import rule on connect, and live import of shots taken while plugged in.
     * For "picks only" the header has to be read first — that's where the ★ rating lives.
     */
    fun autoImport(shots: List<Shot>) {
        val rule = settings.autoImport
        if (rule == AutoImportRule.OFF) return
        if (!pro.isPro && rule == AutoImportRule.KEEPERS) return       // Pro feature
        val s = session
        scope.launch {
            val picked = mutableListOf<Shot>()
            var unread = mutableListOf<Shot>()
            for (shot in shots) {
                if (shot.wasImported || shot.isVideo) continue
                if (rule == AutoImportRule.KEEPERS) {
                    if (shot.meta == null && !shot.isKeeper) s?.loadMeta(shot)
                    when {
                        shot.isKeeper -> picked += shot
                        shot.meta == null -> unread += shot          // header read failed: decide later
                    }
                } else {
                    picked += shot
                }
            }
            if (picked.isNotEmpty()) importShots(picked)
            // A busy or sleeping camera can fail a header read; a ★ shot must not be dropped for
            // that. Try those again with backoff, then say how many are still unknown.
            var wait = 5_000L
            repeat(3) {
                if (unread.isEmpty() || session !== s) return@launch
                delay(wait); wait *= 3
                val again = unread.filter { it.meta == null && !it.wasImported }
                unread = mutableListOf()
                val late = mutableListOf<Shot>()
                for (shot in again) {
                    s?.loadMeta(shot)
                    when {
                        shot.isKeeper -> late += shot
                        shot.meta == null -> unread += shot
                    }
                }
                if (late.isNotEmpty()) importShots(late)
            }
            if (unread.isNotEmpty() && session === s) {
                toast = "Couldn't read the rating of ${unread.size} shot${if (unread.size == 1) "" else "s"}. Check them and import by hand."
            }
        }
    }

    fun importShots(shots: List<Shot>) {
        val refused = importer.enqueue(shots)
        if (refused > 0) {
            paywallNote = "Free plan: ${Quota.FREE_PER_DAY} imports a day. $refused waiting for Pro."
            showPaywall = true
        }
    }

    /** Capture time (epoch ms) with the per-camera clock correction applied. */
    fun correctedDate(shot: Shot): Long {
        val offset = settings.clockOffset(shot.meta?.model)
        return shot.captureDate.toEpochMilli() - (offset * 1000).toLong()
    }

    /** Where a shot will be placed on import: its own GPS, the walk, or (maybe) phone photos. */
    fun locationSource(shot: Shot): String = when {
        shot.meta?.hasGPS == true -> "GPS in file"
        walks.location(correctedDate(shot)) != null -> "Location from your walk"
        settings.borrowPhoneLocations -> "Location from phone photos"
        else -> "No location"
    }
}
