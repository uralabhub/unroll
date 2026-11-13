package app.unroll.importer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.exifinterface.media.ExifInterface
import app.unroll.core.Fix
import java.io.IOException
import java.io.OutputStream

/**
 * Writes into the shared photo library (MediaStore). Originals go in byte for byte; the only
 * exception is a JPEG that gets a location, where [app.unroll.core.GpsInjector] *appends* a GPS
 * block (see its doc) because Android galleries read location from the file only.
 */
object MediaStoreSaver {
    class Saved(val uri: Uri, val displayName: String)

    private fun mime(name: String, video: Boolean): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "heic", "heif", "hif" -> "image/heif"
            "dng" -> "image/x-adobe-dng"
            "raf" -> "image/x-fuji-raf"
            "arw" -> "image/x-sony-arw"
            "cr2" -> "image/x-canon-cr2"
            "cr3" -> "image/x-canon-cr3"
            "nef" -> "image/x-nikon-nef"
            "nrw" -> "image/x-nikon-nrw"
            "orf" -> "image/x-olympus-orf"
            "rw2" -> "image/x-panasonic-rw2"
            "pef" -> "image/x-pentax-pef"
            "srw" -> "image/x-samsung-srw"
            "mov" -> "video/quicktime"
            "mp4", "m4v" -> "video/mp4"
            else -> if (video) "video/*" else "image/*"
        }
    }

    /**
     * Inserts one file as a pending item, streams it in with [write], then publishes it.
     * [relativePath] is e.g. "DCIM/Unroll". RAW types MediaStore rejects as images fall back to
     * Download/Unroll under the same name, so the pair still matches by name.
     */
    suspend fun save(
        context: Context,
        name: String,
        video: Boolean,
        relativePath: String,
        takenMillis: Long?,
        write: suspend (OutputStream) -> Unit,
    ): Saved {
        val cr = context.contentResolver
        val collection = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime(name, video))
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            takenMillis?.let { put(MediaStore.MediaColumns.DATE_TAKEN, it) }
        }
        val uri = try {
            cr.insert(collection, values)
        } catch (e: IllegalArgumentException) {
            null
        } ?: run {
            // e.g. a RAW extension this Android version doesn't map to an image MIME type.
            val dl = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Unroll")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            cr.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), dl)
        } ?: throw IOException("Couldn't create $name in the photo library")

        try {
            (cr.openOutputStream(uri, "w") ?: throw IOException("Couldn't open $name for writing")).use { write(it) }
            cr.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                takenMillis?.let { put(MediaStore.MediaColumns.DATE_TAKEN, it) }
            }, null, null)
        } catch (e: Exception) {
            runCatching { cr.delete(uri, null, null) }
            throw e
        }
        val actual = cr.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: name
        return Saved(uri, actual)
    }

    /** Saves a rendered image (frame / recap / recipe card) as a new JPEG in Pictures/Unroll. */
    suspend fun saveJpeg(context: Context, bytes: ByteArray, name: String): Saved =
        save(context, name, false, "Pictures/Unroll", System.currentTimeMillis()) { it.write(bytes) }
}

/**
 * Fallback location: borrow the location of phone photos taken around the same time.
 * Most people snap at least one phone picture on a walk — zero setup, zero battery.
 * Needs READ_MEDIA_IMAGES + ACCESS_MEDIA_LOCATION (asked from the Library screen).
 */
object PhoneLocationLookup {
    fun location(context: Context, at: Long, windowMillis: Long = 20 * 60 * 1000L): Fix? {
        val cr = context.contentResolver
        val sel = "${MediaStore.Images.Media.DATE_TAKEN} >= ? AND ${MediaStore.Images.Media.DATE_TAKEN} <= ? AND " +
            "${MediaStore.MediaColumns.RELATIVE_PATH} NOT LIKE ?"
        val args = arrayOf((at - windowMillis).toString(), (at + windowMillis).toString(), "%Unroll%")
        val rows = runCatching {
            cr.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN),
                sel, args, "${MediaStore.Images.Media.DATE_TAKEN} DESC",
            )
        }.getOrNull() ?: return null
        var best: Pair<Fix, Long>? = null
        rows.use { c ->
            var n = 0
            while (c.moveToNext() && n++ < 60) {
                val uri = MediaStore.setRequireOriginal(Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0).toString()))
                val gap = kotlin.math.abs(c.getLong(1) - at)
                if (gap >= (best?.second ?: Long.MAX_VALUE)) continue
                val ll = runCatching { cr.openInputStream(uri)?.use { ExifInterface(it).latLong } }.getOrNull() ?: continue
                best = Fix(ll[0], ll[1], 50.0) to gap
            }
        }
        return best?.first
    }
}
