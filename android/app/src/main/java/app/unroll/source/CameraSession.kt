package app.unroll.source

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.unroll.core.HeaderParser
import app.unroll.core.ShotFormat
import app.unroll.core.ShotMeta
import app.unroll.data.ImportLedger
import app.unroll.data.MetaCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

enum class SourceKind(val label: String) { USB("USB-C"), CARD("Card"), WIFI("Wi-Fi"), DEMO("Demo") }

sealed interface SessionStatus {
    data object Connecting : SessionStatus
    data class Cataloging(val percent: Int) : SessionStatus
    data object Ready : SessionStatus
    data class Failed(val message: String) : SessionStatus
}

/** A live connection to one camera / card. Owns the shot list and the background header scan. */
class CameraSession(
    val kind: SourceKind,
    name: String,
    private val metaCache: MetaCache,
    private val ledger: ImportLedger,
) {
    val id: String = UUID.randomUUID().toString()
    var name by mutableStateOf(name)
    var status by mutableStateOf<SessionStatus>(SessionStatus.Connecting)
    var shots by mutableStateOf<List<Shot>>(emptyList())
        private set
    /** How many shots have had their header parsed (drives the ★ filter progress). */
    var scanned by mutableIntStateOf(0)
        private set

    /** Parallel header reads. Camera PTP stacks choke on many concurrent requests; disks don't. */
    private val readConcurrency = if (kind == SourceKind.USB || kind == SourceKind.WIFI) 2 else 6
    var onClose: (() -> Unit)? = null
    var onNewShots: ((List<Shot>) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val byID = HashMap<String, Shot>()
    private var scanJob: Job? = null
    private val lock = Mutex()
    private val pending = ArrayDeque<Shot>()
    private val inFlight = HashSet<String>()
    private val readFailures = HashMap<String, Int>()

    val scanComplete: Boolean get() = scanned >= shots.count { !it.isVideo }

    fun shot(id: String): Shot? = byID[id]

    /** Replaces the catalog. Newest first; cached metadata applied instantly. Main thread. */
    fun replaceShots(list: List<Shot>) {
        // Keep existing Shot objects (and their thumbnails / in-flight loads) across catalog refreshes.
        val previous = HashMap(byID)
        byID.clear()
        val merged = list.map { s ->
            previous[s.id]?.also { old -> byID[s.id] = old; if (old.raw == null && s.raw != null) old.raw = s.raw } ?: adopt(s)
        }
        shots = merged.sortedByDescending { it.fileDate }
        scanned = shots.count { it.meta != null }
        restartScan()
    }

    /** Shots that appeared while connected (user kept shooting). Triggers auto-import. */
    fun add(list: List<Shot>) {
        val fresh = list.filter { it.id !in byID }.map(::adopt)
        if (fresh.isEmpty()) return
        shots = (fresh + shots).sortedByDescending { it.fileDate }
        restartScan()
        if (status == SessionStatus.Ready) onNewShots?.invoke(fresh)
    }

    fun remove(predicate: (Shot) -> Boolean) {
        shots = shots.filterNot(predicate)
        byID.clear()
        shots.forEach { byID[it.id] = it }
    }

    private fun adopt(s: Shot): Shot {
        metaCache[s.id]?.let { s.meta = it.meta; s.preview = it.preview }
        s.wasImported = ledger.contains(s.id)
        byID[s.id] = s
        return s
    }

    // Metadata scan (newest first)

    private fun restartScan() {
        scanJob?.cancel()
        // A camera accepts object reads only once its catalog is complete.
        if (kind == SourceKind.USB && status != SessionStatus.Ready) { pending.clear(); return }
        pending.clear()
        pending.addAll(shots.filter { it.meta == null && !it.isVideo })
        scanJob = scope.launch {
            repeat(readConcurrency) {
                launch {
                    while (isActive) {
                        val s = nextPending() ?: break
                        loadMeta(s)
                    }
                }
            }
        }
    }

    private fun nextPending(): Shot? {
        while (pending.isNotEmpty()) {
            val s = pending.removeFirst()
            if (s.meta == null && s.id !in inFlight) return s
        }
        return null
    }

    /** Bumps a shot to the front (it just scrolled into view). */
    fun prioritize(shot: Shot) {
        if (shot.meta != null || shot.isVideo || shot.id in inFlight) return
        pending.remove(shot)
        pending.addFirst(shot)
    }

    /** Reads and parses the header once. Always runs on the main thread (it updates UI state). */
    suspend fun loadMeta(shot: Shot) = withContext(Dispatchers.Main.immediate) { loadMetaOnMain(shot) }

    private suspend fun loadMetaOnMain(shot: Shot) {
        if (shot.meta != null) return
        lock.withLock { if (!inFlight.add(shot.id)) return }
        try {
            // RAW twin carries the same EXIF; the JPEG header is cheaper to parse.
            val data = try {
                shot.primary.read(0, HeaderParser.HEADER_LENGTH)
            } catch (e: Exception) {
                val n = (readFailures[shot.id] ?: 0) + 1
                readFailures[shot.id] = n
                // One retry on the next scan pass; after that, don't hammer a broken file.
                if (n >= 2) { shot.meta = ShotMeta(); scanned += 1 }
                return
            }
            val info = withContext(Dispatchers.Default) { HeaderParser.parse(data) }
            // RAW+HEIF: if the HEIF's EXIF didn't fit in the header, the RAW twin carries the same EXIF.
            // Its preview range points into the RAW file, so it's not used for the HEIF primary.
            val raw = shot.raw
            if (info.format == ShotFormat.HEIF && info.meta.dateTimeOriginal == null && raw != null) {
                runCatching { raw.read(0, HeaderParser.HEADER_LENGTH) }.getOrNull()?.let { rd ->
                    info.meta = withContext(Dispatchers.Default) { HeaderParser.parse(rd) }.meta
                }
            }
            shot.preview = if (info.format == ShotFormat.HEIF) null else info.preview
            shot.meta = info.meta
            scanned += 1
            metaCache[shot.id] = MetaCache.Entry(info.meta, info.preview)
        } finally {
            lock.withLock { inFlight.remove(shot.id) }
        }
    }

    fun close() {
        scanJob?.cancel()
        scope.cancel()
        onClose?.invoke()
    }
}
