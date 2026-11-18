package app.unroll.media

import android.app.ActivityManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.unroll.UnrollApplication
import app.unroll.core.RgbPreview
import app.unroll.core.ShotFormat
import app.unroll.source.CameraSession
import app.unroll.source.FileLocation
import app.unroll.source.Shot
import app.unroll.source.ShotFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

object ImageDecode {
    /** Decodes JPEG/HEIF bytes no larger than [maxPixel] on the long edge, upright per [orientation]. */
    fun decode(data: ByteArray, maxPixel: Int, orientation: Int? = null): Bitmap? {
        if (data.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        val bmp: Bitmap? = if (bounds.outWidth > 0) {
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPixel) sample *= 2
            BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } else {
            // HEIF and anything BitmapFactory can't size: ImageDecoder (applies EXIF rotation itself).
            return runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(data))) { d, info, _ ->
                    val s = info.size
                    val scale = maxPixel.toFloat() / maxOf(s.width, s.height)
                    if (scale < 1) d.setTargetSize((s.width * scale).toInt().coerceAtLeast(1), (s.height * scale).toInt().coerceAtLeast(1))
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }.getOrNull()
        }
        val b = bmp ?: return null
        val scaled = if (maxOf(b.width, b.height) > maxPixel) {
            val k = maxPixel.toFloat() / maxOf(b.width, b.height)
            Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true)
        } else b
        return orient(scaled, orientation ?: exifOrientation(data))
    }

    /** Uncompressed 8-bit RGB (a RAW's thumbnail strip) → bitmap, scaled to [maxPixel]. */
    fun decodeRGB(data: ByteArray, p: RgbPreview, maxPixel: Int, orientation: Int?): Bitmap? {
        val w = p.width; val h = p.height
        if (data.size < w * h * 3) return null
        val px = IntArray(w * h)
        var i = 0
        for (k in px.indices) {
            px[k] = (0xFF shl 24) or ((data[i].toInt() and 0xFF) shl 16) or ((data[i + 1].toInt() and 0xFF) shl 8) or (data[i + 2].toInt() and 0xFF)
            i += 3
        }
        val b = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        val scaled = if (maxOf(w, h) > maxPixel) {
            val k = maxPixel.toFloat() / maxOf(w, h)
            Bitmap.createScaledBitmap(b, (w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1), true)
        } else b
        return orient(scaled, orientation)
    }

    private fun exifOrientation(data: ByteArray): Int? =
        runCatching { app.unroll.core.HeaderParser.parse(data.copyOf(minOf(data.size, 128 * 1024))).meta.orientation }.getOrNull()

    /** Bakes the EXIF orientation into pixels (BitmapFactory ignores it). */
    fun orient(b: Bitmap, o: Int?): Bitmap {
        if (o == null || o <= 1 || o > 8) return b
        val m = Matrix()
        when (o) {
            2 -> m.setScale(-1f, 1f)
            3 -> m.setRotate(180f)
            4 -> m.setScale(1f, -1f)
            5 -> { m.setRotate(90f); m.postScale(-1f, 1f) }
            6 -> m.setRotate(90f)
            7 -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
            8 -> m.setRotate(-90f)
        }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }
}

object Previews {
    /** PTP stacks serialise anyway; flooding them only adds latency. Disks take more. */
    private val cameraGate = Semaphore(2)
    private val diskGate = Semaphore(6)
    private const val MAX_PREVIEW_BYTES = 16_000_000L
    private val cache = object : LruCache<String, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /**
     * Grid thumbnails, bounded by memory (1/8 of the app's heap class). The LRU owns them: when one
     * is evicted the shot's reference is cleared too, so 2,000 shots don't pin 700 MB of bitmaps.
     * A visible cell whose thumbnail was evicted just loads it again.
     */
    private class ThumbEntry(val shot: Shot, val image: ImageBitmap, val bytes: Int)

    private val thumbs = object : LruCache<String, ThumbEntry>(thumbBudget()) {
        override fun sizeOf(key: String, value: ThumbEntry) = value.bytes
        override fun entryRemoved(evicted: Boolean, key: String, old: ThumbEntry, new: ThumbEntry?) {
            if (new?.image === old.image) return
            main.post { if (old.shot.thumbnail === old.image) old.shot.thumbnail = null }
        }
    }
    private val main = Handler(Looper.getMainLooper())

    private fun thumbBudget(): Int {
        val am = UnrollApplication.context.getSystemService(ActivityManager::class.java)
        val mb = am?.memoryClass ?: 256
        return (mb / 8).coerceAtLeast(16) * 1024 * 1024
    }

    /** Marks a shot's thumbnail as recently used (a cell showing it was composed). */
    fun touch(shot: Shot) { thumbs.get(shot.id) }

    private fun gate(f: ShotFile) = if (f.isRemote) cameraGate else diskGate

    /** Grid thumbnail (~360 px). Cancelling the caller (cell scrolled away) stops between chunks. */
    suspend fun thumbnail(shot: Shot, session: CameraSession?) {
        if (shot.thumbnail != null || shot.isVideo) { touch(shot); return }
        val img = gate(shot.primary).withPermit {
            if (shot.thumbnail != null) return
            withContext(Dispatchers.IO) { loadThumb(shot, session) }
        }
        img?.let {
            val ib = it.asImageBitmap()
            thumbs.put(shot.id, ThumbEntry(shot, ib, it.byteCount))
            shot.thumbnail = ib
        }
    }

    private suspend fun loadThumb(shot: Shot, session: CameraSession?): Bitmap? {
        val f = shot.primary
        val l = f.location
        if (l is FileLocation.Mtp) {
            // One small GetThumb request; orientation comes from the header when we have it.
            runCatching { l.camera.thumbnail(l.handle) }.getOrNull()?.let { t ->
                ImageDecode.decode(t, 360, shot.meta?.orientation)?.let { return it }
            }
        }
        if (l is FileLocation.Http) {
            return runCatching { app.unroll.source.Http.get(l.copy(url = l.url + "?size=thumb"), null).second }.getOrNull()
                ?.let { ImageDecode.decode(it, 360) }
        }
        // Embedded preview first (RAW-only shots, MPF): a ranged read, never the whole RAW.
        if (shot.meta == null && f.format.isRAW) session?.loadMeta(shot)
        embedded(shot, 360)?.let { return it }
        // Whole-file decode: from a card or local disk, or over USB for a small JPEG/HEIF the camera
        // has no thumbnail for (screenshots, edited/re-saved files). Never over Wi-Fi.
        if (f.format == ShotFormat.JPEG || f.format == ShotFormat.HEIF) {
            val limit = if (l is FileLocation.Mtp) 25_000_000L else if (!f.isRemote) 60_000_000L else 0L
            if (f.size in 1 until limit) runCatching { f.readAll() }.getOrNull()?.let { return ImageDecode.decode(it, 360) }
        }
        return null
    }

    /** The RAW's embedded JPEG (or, failing that, its uncompressed RGB thumbnail) via ranged reads. */
    private suspend fun embedded(shot: Shot, maxPixel: Int): Bitmap? {
        val f = shot.primary
        val r = shot.preview
        if (r != null && (f.format.isRAW || maxPixel > 360) && r.length in 1..MAX_PREVIEW_BYTES) {
            runCatching { f.read(r.offset, r.length.toInt()) }.getOrNull()
                ?.let { ImageDecode.decode(it, maxPixel, shot.meta?.orientation) }?.let { return it }
        }
        val rgb = shot.rgbPreview
        if (rgb != null && rgb.range.length in 1..MAX_PREVIEW_BYTES) {
            runCatching { f.read(rgb.range.offset, rgb.range.length.toInt()) }.getOrNull()
                ?.let { ImageDecode.decodeRGB(it, rgb, maxPixel, shot.meta?.orientation) }?.let { return it }
        }
        return null
    }

    /** Screen-size preview: the embedded RAW/MPF preview via a ranged read — never the whole file. */
    suspend fun large(shot: Shot, session: CameraSession?): Bitmap? {
        cache.get(shot.id)?.let { return it }
        val img = withContext(Dispatchers.IO) { loadLarge(shot, session) }
        img?.let { cache.put(shot.id, it) }
        return img
    }

    private suspend fun loadLarge(shot: Shot, session: CameraSession?): Bitmap? {
        val f = shot.primary
        if (f.location is FileLocation.Local || f.location is FileLocation.Document) {
            if (!f.format.isRAW && f.size < 60_000_000) runCatching { f.readAll() }.getOrNull()?.let { ImageDecode.decode(it, 2400)?.let { b -> return b } }
        }
        if (f.location is FileLocation.Http) {
            val l = f.location as FileLocation.Http
            return runCatching { app.unroll.source.Http.get(l.copy(url = l.url + "?size=view"), null).second }.getOrNull()?.let { ImageDecode.decode(it, 2000) }
        }
        if (shot.meta == null) session?.loadMeta(shot)
        embedded(shot, 2400)?.let { return it }
        if ((f.format == ShotFormat.JPEG || f.format == ShotFormat.HEIF) && f.size < 40_000_000) {
            runCatching { f.readAll() }.getOrNull()?.let { ImageDecode.decode(it, 2400) }?.let { return it }
        }
        val l = f.location
        if (l is FileLocation.Mtp) runCatching { l.camera.thumbnail(l.handle) }.getOrNull()?.let { return ImageDecode.decode(it, 1024, shot.meta?.orientation) }
        return null
    }

    /** Best full-resolution image for framing (original JPEG; embedded preview for RAW-only). Capped for memory. */
    suspend fun full(shot: Shot, session: CameraSession?): Bitmap? {
        val f = shot.primary
        if (!f.format.isRAW && f.format != ShotFormat.VIDEO) {
            withContext(Dispatchers.IO) { runCatching { f.readAll() }.getOrNull()?.let { ImageDecode.decode(it, 4096) } }?.let { return it }
        }
        return large(shot, session)
    }
}
