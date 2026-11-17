package app.unroll.source

import android.net.Network
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import app.unroll.UnrollApplication
import app.unroll.core.ByteRange
import app.unroll.core.RgbPreview
import app.unroll.core.ShotFormat
import app.unroll.core.ShotMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.time.Instant

/** Where a file's bytes come from. */
sealed interface FileLocation {
    /** A PTP/MTP object on a USB camera. */
    data class Mtp(val camera: MtpCamera, val handle: Int) : FileLocation
    /** A Storage Access Framework document (SD card reader, Files). */
    data class Document(val uri: Uri) : FileLocation
    /** A plain file (the demo roll). */
    data class Local(val file: File) : FileLocation
    /** Ricoh GR style: GET <url>, ?size=thumb|view. */
    data class Http(val url: String, val network: Network?) : FileLocation
}

/** One physical file on the camera / card. */
class ShotFile(val name: String, val size: Long, val location: FileLocation) {
    val format: ShotFormat = ShotFormat.fromFilename(name)
    val knownSize: Long get() = if (size == UNKNOWN_SIZE) 0 else size
    /** True when bytes come over USB/Wi-Fi from a camera (vs. a card / local file). */
    val isRemote: Boolean get() = location is FileLocation.Mtp || location is FileLocation.Http

    companion object {
        /** Ricoh listings carry no size: a safe upper bound for reads, excluded from totals. */
        const val UNKNOWN_SIZE: Long = 1L shl 36
        private const val MTP_READ_CHUNK = 1 shl 20
    }

    /** Size for display / verification: 0 when the source didn't tell us (Ricoh) or the camera reported 0xFFFFFFFF. */
    val verifiableSize: Long? get() = size.takeIf { it > 0 && it != UNKNOWN_SIZE && it != MtpCamera.SIZE_UNKNOWN_32 }

    /** Ranged read: [length] bytes at [offset], fewer at end of file. Never pulls the whole file. */
    suspend fun read(offset: Long, length: Int): ByteArray = withContext(Dispatchers.IO) {
        val len = minOf(length.toLong(), size - offset).toInt()
        if (len <= 0) return@withContext ByteArray(0)
        when (val l = location) {
            is FileLocation.Mtp -> {
                // In 1 MB requests: other PTP work (thumbnails, header reads) slips in between, and a
                // cancelled caller (cell scrolled away) stops after the current chunk.
                if (len <= MTP_READ_CHUNK) return@withContext l.camera.readPartial(l.handle, offset, len)
                val out = java.io.ByteArrayOutputStream(len)
                var off = offset
                while (out.size() < len) {
                    coroutineContext.ensureActive()
                    val n = minOf(MTP_READ_CHUNK, len - out.size())
                    val b = l.camera.readPartial(l.handle, off, n)
                    out.write(b)
                    off += b.size
                    if (b.size < n) break
                }
                out.toByteArray()
            }
            is FileLocation.Local -> RandomAccessFile(l.file, "r").use { f ->
                f.seek(offset)
                val b = ByteArray(len)
                val n = f.read(b)
                if (n == len) b else b.copyOf(maxOf(0, n))
            }
            is FileLocation.Document -> {
                val cr = UnrollApplication.context.contentResolver
                val pfd = cr.openFileDescriptor(l.uri, "r") ?: throw IOException("Card file unavailable")
                pfd.use {
                    FileInputStream(it.fileDescriptor).channel.use { ch ->
                        val buf = ByteBuffer.allocate(len)
                        var p = offset
                        while (buf.hasRemaining()) { val n = ch.read(buf, p); if (n <= 0) break; p += n }
                        buf.array().copyOf(buf.position())
                    }
                }
            }
            is FileLocation.Http -> Http.get(l, "bytes=$offset-${offset + len - 1}", maxBytes = if (offset == 0L) len else null).let { (code, data) ->
                // Servers that ignore Range send the whole file; slice it ourselves.
                if (code == 200 && data.size > len) data.copyOfRange(minOf(offset.toInt(), data.size), minOf(data.size, offset.toInt() + len)) else data
            }
        }
    }

    /** Streams the whole original into [out], byte for byte. Cancellable between chunks. */
    suspend fun copyTo(out: OutputStream, progress: (Double) -> Unit) = withContext(Dispatchers.IO) {
        val ctx = coroutineContext
        val check = { ctx.ensureActive() }
        when (val l = location) {
            is FileLocation.Mtp -> l.camera.copyObject(l.handle, size, out, progress, check)
            is FileLocation.Local -> l.file.inputStream().use { pump(it, out, l.file.length(), progress, check) }
            is FileLocation.Document -> UnrollApplication.context.contentResolver.openInputStream(l.uri)?.use { pump(it, out, size, progress, check) }
                ?: throw IOException("Card file unavailable")
            is FileLocation.Http -> Http.stream(l, out, progress, check)
        }
    }

    suspend fun readAll(): ByteArray = withContext(Dispatchers.IO) {
        val o = java.io.ByteArrayOutputStream(if (knownSize in 1..Int.MAX_VALUE.toLong()) knownSize.toInt() else 1 shl 20)
        copyTo(o) {}
        o.toByteArray()
    }
}

internal fun pump(input: java.io.InputStream, out: OutputStream, total: Long, progress: (Double) -> Unit, check: () -> Unit = {}): Long {
    val buf = ByteArray(1 shl 20)
    var done = 0L
    while (true) {
        check()
        val n = input.read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
        done += n
        if (total > 0) progress((done.toDouble() / total).coerceAtMost(1.0))
    }
    return done
}

/**
 * Camera Wi-Fi has no internet: requests are bound to the Wi-Fi network so Android doesn't route
 * them to mobile data. Camera Wi-Fi drops out a lot (the GR sleeps, the phone roams), so every
 * request retries with backoff and re-resolves the Wi-Fi network; downloads resume with Range.
 */
object Http {
    /** Looks up the current camera Wi-Fi network again (it changes after a reconnect). */
    @Volatile var networkProvider: (() -> Network?)? = null
    private val backoffMs = longArrayOf(500, 1500, 4000)

    fun open(l: FileLocation.Http, url: String = l.url, network: Network? = l.network): HttpURLConnection {
        val u = URL(url)
        val c = (network?.openConnection(u) ?: u.openConnection()) as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 20000
        return c
    }

    /** HTTP status errors are final; I/O errors (timeouts, resets, network gone) are worth retrying. */
    class StatusException(val code: Int) : IOException("Camera answered HTTP $code")

    private fun <T> retrying(l: FileLocation.Http, block: (Network?) -> T): T {
        var last: IOException? = null
        for (attempt in 0..backoffMs.size) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
            val net = if (attempt == 0) l.network else (networkProvider?.invoke() ?: l.network)
            try {
                return block(net)
            } catch (e: StatusException) {
                if (e.code in 400..499) throw e
                last = e
            } catch (e: IOException) {
                last = e
            }
            if (attempt < backoffMs.size) Thread.sleep(backoffMs[attempt])
        }
        throw last ?: IOException("Camera unreachable")
    }

    fun get(l: FileLocation.Http, range: String?, maxBytes: Int? = null): Pair<Int, ByteArray> = retrying(l) { net ->
        val c = open(l, network = net)
        try {
            range?.let { c.setRequestProperty("Range", it) }
            val code = c.responseCode
            if (code != 200 && code != 206) throw StatusException(code)
            // A server that ignores Range on a header read: stop after what we asked for.
            code to c.inputStream.use { if (code == 200 && maxBytes != null) readUpTo(it, maxBytes) else it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    private fun readUpTo(input: java.io.InputStream, max: Int): ByteArray {
        val b = ByteArray(max)
        var n = 0
        while (n < max) { val r = input.read(b, n, max - n); if (r < 0) break; n += r }
        return if (n == max) b else b.copyOf(n)
    }

    fun stream(l: FileLocation.Http, out: OutputStream, progress: (Double) -> Unit, check: () -> Unit = {}) {
        var written = 0L
        var total = -1L
        retrying(l) { net ->
            val c = open(l, network = net)
            try {
                if (written > 0) c.setRequestProperty("Range", "bytes=$written-")
                val code = c.responseCode
                if (code != 200 && code != 206) throw StatusException(code)
                if (written > 0 && code != 206) throw IOException("The camera can't resume this download")
                if (total < 0) total = c.contentLengthLong.takeIf { it > 0 }?.let { it + written } ?: -1
                c.inputStream.use { input ->
                    val buf = ByteArray(1 shl 20)
                    while (true) {
                        check()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        if (total > 0) progress((written.toDouble() / total).coerceAtMost(1.0))
                    }
                }
                if (total > 0 && written < total) throw IOException("Download stopped at $written of $total bytes")
            } finally {
                c.disconnect()
            }
        }
    }
}

sealed interface ImportState {
    data object None : ImportState
    data object Queued : ImportState
    data class Importing(val fraction: Double) : ImportState
    data object Done : ImportState
    data class Failed(val message: String) : ImportState
}

/** A capture as the user thinks of it: a JPEG/HEIF and (optionally) its RAW twin. */
class Shot(
    val primary: ShotFile,
    raw: ShotFile?,
    /** File date from the card — available instantly, used for newest-first ordering. */
    val fileDate: Long,
    isLocked: Boolean,
) {
    val id: String = key(primary.name, primary.size, fileDate)

    /** Set later when the RAW half of a pair shows up separately (shooting while plugged in). */
    var raw by mutableStateOf(raw)
    var isLocked by mutableStateOf(isLocked)
    var meta by mutableStateOf<ShotMeta?>(null)
    var preview: ByteRange? = null
    /** Uncompressed RGB thumbnail inside the RAW, for files without any embedded JPEG. */
    var rgbPreview: RgbPreview? = null
    /** The header couldn't be read after several tries (meta stays null; shown by file name). */
    @Volatile var headerFailed = false
    /** Grid thumbnail. Owned by [app.unroll.media.Previews]' LRU, which clears it on eviction. */
    var thumbnail by mutableStateOf<ImageBitmap?>(null)
    var importState by mutableStateOf<ImportState>(ImportState.None)
    var wasImported by mutableStateOf(false)

    companion object {
        fun key(name: String, size: Long, dateMillis: Long) = "$name|$size|${dateMillis / 1000}"
    }

    val rating: Int get() = meta?.rating ?: 0
    val isRated: Boolean get() = rating > 0
    val isKeeper: Boolean get() = isRated || isLocked
    val captureDate: Instant get() = meta?.captureDate() ?: Instant.ofEpochMilli(fileDate)
    val totalBytes: Long get() = primary.knownSize + (raw?.knownSize ?: 0)
    val isRAWOnly: Boolean get() = primary.format.isRAW
    val isVideo: Boolean get() = primary.format == ShotFormat.VIDEO
    val hasRAW: Boolean get() = raw != null || primary.format.isRAW

    val formatBadge: String
        get() = when {
            isVideo -> "VIDEO"
            raw != null -> if (primary.format == ShotFormat.HEIF) "HEIF+RAW" else "JPG+RAW"
            primary.format.isRAW -> "RAW"
            primary.format == ShotFormat.HEIF -> "HEIF"
            else -> "JPG"
        }

    override fun equals(other: Any?) = other is Shot && other.id == id
    override fun hashCode() = id.hashCode()
}
