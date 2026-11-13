package app.unroll.core

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Camera clocks drift (and forget DST / time zones), which silently breaks geotagging.
 * Fix: show a QR code containing the exact time; the user photographs it with the camera;
 * we read the QR back from that shot and compare it with the shot's EXIF time.
 */
object ClockSync {
    const val PREFIX = "UNROLL-CLOCK:"

    fun payload(epochSeconds: Long): String = PREFIX + epochSeconds

    /** QR modules for [epochSeconds], scaled to roughly [size] px. */
    fun matrix(epochSeconds: Long, size: Int): BitMatrix =
        QRCodeWriter().encode(
            payload(epochSeconds), BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1),
        )

    /** Reads the timestamp encoded in a photo of the sync screen (ARGB pixels). */
    fun readTimestamp(argb: IntArray, width: Int, height: Int): Long? {
        val src = RGBLuminanceSource(width, height, argb)
        val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
        val text = try {
            QRCodeReader().decode(BinaryBitmap(HybridBinarizer(src)), hints).text
        } catch (_: Exception) {
            return null
        }
        if (!text.startsWith(PREFIX)) return null
        return text.removePrefix(PREFIX).toLongOrNull()
    }

    /** Seconds the camera is ahead of real time (positive = camera fast). */
    fun offsetSeconds(exifEpochMillis: Long, truthEpochMillis: Long): Double =
        (exifEpochMillis - truthEpochMillis) / 1000.0

    fun describe(off: Double): String {
        val s = roundHalfAway(kotlin.math.abs(off)).toInt()
        if (s < 2) return "in sync"
        val txt = if (s >= 60) "${s / 60}m ${s % 60}s" else "${s}s"
        return if (off > 0) "$txt fast" else "$txt slow"
    }
}
