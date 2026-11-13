package app.unroll.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.util.LruCache
import androidx.compose.ui.graphics.asImageBitmap
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
    private val cache = object : LruCache<String, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun gate(f: ShotFile) = if (f.isRemote) cameraGate else diskGate

    /** Grid thumbnail (~360 px). */
    suspend fun thumbnail(shot: Shot, session: CameraSession?) {
        if (shot.thumbnail != null || shot.isVideo) return
        val img = gate(shot.primary).withPermit {
            if (shot.thumbnail != null) return
            withContext(Dispatchers.IO) { loadThumb(shot, session) }
        }
        img?.let { shot.thumbnail = it.asImageBitmap() }
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
        val r = shot.preview
        if (r != null && f.format.isRAW && r.length in 1..16_000_000) {
            runCatching { f.read(r.offset, r.length.toInt()) }.getOrNull()?.let { ImageDecode.decode(it, 360, shot.meta?.orientation)?.let { b -> return b } }
        }
        if (f.format == ShotFormat.JPEG || f.format == ShotFormat.HEIF) {
            if (f.size < 60_000_000) runCatching { f.readAll() }.getOrNull()?.let { return ImageDecode.decode(it, 360) }
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
        val r = shot.preview
        if (r != null && r.length in 1..16_000_000) {
            runCatching { f.read(r.offset, r.length.toInt()) }.getOrNull()
                ?.let { ImageDecode.decode(it, 2400, shot.meta?.orientation) }?.let { return it }
        }
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
