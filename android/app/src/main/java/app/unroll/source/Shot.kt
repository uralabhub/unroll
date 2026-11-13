package app.unroll.source

import android.net.Network
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import app.unroll.UnrollApplication
import app.unroll.core.ByteRange
import app.unroll.core.ShotFormat
import app.unroll.core.ShotMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
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
    }

    /** Ranged read: [length] bytes at [offset], fewer at end of file. Never pulls the whole file. */
    suspend fun read(offset: Long, length: Int): ByteArray = withContext(Dispatchers.IO) {
        val len = minOf(length.toLong(), size - offset).toInt()
        if (len <= 0) return@withContext ByteArray(0)
        when (val l = location) {
            is FileLocation.Mtp -> l.camera.readPartial(l.handle, offset, len)
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
            is FileLocation.Http -> Http.get(l, "bytes=$offset-${offset + len - 1}").let { (code, data) ->
                // Servers that ignore Range send the whole file; slice it ourselves.
                if (code == 200 && data.size > len) data.copyOfRange(minOf(offset.toInt(), data.size), minOf(data.size, offset.toInt() + len)) else data
            }
        }
    }

    /** Streams the whole original into [out], byte for byte. */
    suspend fun copyTo(out: OutputStream, progress: (Double) -> Unit) = withContext(Dispatchers.IO) {
        when (val l = location) {
            is FileLocation.Mtp -> l.camera.copyObject(l.handle, size, out, progress)
            is FileLocation.Local -> l.file.inputStream().use { pump(it, out, l.file.length(), progress) }
            is FileLocation.Document -> UnrollApplication.context.contentResolver.openInputStream(l.uri)?.use { pump(it, out, size, progress) }
                ?: throw IOException("Card file unavailable")
            is FileLocation.Http -> Http.stream(l, out, progress)
        }
    }

    suspend fun readAll(): ByteArray = withContext(Dispatchers.IO) {
        val o = java.io.ByteArrayOutputStream(if (knownSize in 1..Int.MAX_VALUE.toLong()) knownSize.toInt() else 1 shl 20)
        copyTo(o) {}
        o.toByteArray()
    }
}

internal fun pump(input: java.io.InputStream, out: OutputStream, total: Long, progress: (Double) -> Unit) {
    val buf = ByteArray(1 shl 20)
    var done = 0L
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
        done += n
        if (total > 0) progress((done.toDouble() / total).coerceAtMost(1.0))
    }
}

/** Camera Wi-Fi has no internet: requests are bound to the Wi-Fi network so Android doesn't route them to mobile data. */
object Http {
    fun open(l: FileLocation.Http, url: String = l.url): HttpURLConnection {
        val u = URL(url)
        val c = (l.network?.openConnection(u) ?: u.openConnection()) as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 20000
        return c
    }

    fun get(l: FileLocation.Http, range: String?): Pair<Int, ByteArray> {
        val c = open(l)
        try {
            range?.let { c.setRequestProperty("Range", it) }
            val code = c.responseCode
            if (code != 200 && code != 206) throw IOException("Camera answered HTTP $code")
            return code to c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    fun stream(l: FileLocation.Http, out: OutputStream, progress: (Double) -> Unit) {
        val c = open(l)
        try {
            if (c.responseCode != 200) throw IOException("Camera answered HTTP ${c.responseCode}")
            c.inputStream.use { pump(it, out, c.contentLengthLong, progress) }
        } finally {
            c.disconnect()
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
