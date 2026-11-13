package app.unroll.core

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/** Absolute byte range inside the original file. */
@Serializable
data class ByteRange(val offset: Long, val length: Long)

/** Everything we can learn about a shot from its first ~192 KB, without downloading it. */
@Serializable
data class ShotMeta(
    var make: String? = null,
    var model: String? = null,
    var lens: String? = null,
    /** Wall-clock capture time exactly as the camera wrote it ("yyyy:MM:dd HH:mm:ss"). */
    var dateTimeOriginal: String? = null,
    /** "+07:00" when the camera records its time zone (OffsetTimeOriginal). */
    var offsetTime: String? = null,
    var fNumber: Double? = null,
    var exposureTime: Double? = null,
    var iso: Int? = null,
    var focalLength: Double? = null,
    var focalLength35: Int? = null,
    var exposureBias: Double? = null,
    var orientation: Int? = null,
    var width: Int? = null,
    var height: Int? = null,
    /** 0–5 stars as set on the camera body (EXIF Rating, XMP, or maker note). */
    var rating: Int? = null,
    var latitude: Double? = null,
    var longitude: Double? = null,
    var recipe: FilmRecipe? = null,
    /** Non-Fujifilm "look" (Nikon Picture Control, Sony Creative Style) when we can read it. */
    var look: String? = null,
) {
    val hasGPS: Boolean get() = latitude != null && longitude != null

    val cameraName: String
        get() {
            val model = model ?: return make ?: "Camera"
            val make = make
            if (make != null) {
                val first = make.split(' ').firstOrNull { it.isNotEmpty() }
                if (!model.lowercase().startsWith(first?.lowercase() ?: "~")) {
                    val brand = first?.let(::capitalizeWord) ?: make
                    return "$brand $model"
                }
            }
            return model
        }

    /**
     * Capture time resolved to an absolute instant. Without OffsetTimeOriginal we assume the
     * camera clock is set to the phone's current time zone (true for most people).
     */
    fun captureDate(fallbackZone: ZoneId = ZoneId.systemDefault()): Instant? {
        val s = dateTimeOriginal ?: return null
        if (s.length < 19) return null
        val local = try {
            LocalDateTime.parse(s.substring(0, 19), EXIF_DATE)
        } catch (_: Exception) {
            return null
        }
        val zone: ZoneId = offsetTime?.let(::zone) ?: fallbackZone
        return local.atZone(zone).toInstant()
    }

    // Display

    val apertureText: String? get() = fNumber?.let { "ƒ/" + trim(it) }
    val isoText: String? get() = iso?.let { "ISO $it" }
    val focalText: String? get() = focalLength?.let { trim(it) + "mm" }

    val shutterText: String?
        get() {
            val t = exposureTime ?: return null
            if (t <= 0) return null
            if (t >= 1) return trim(t) + "s"
            return "1/${roundHalfAway(1 / t).toLong()}"
        }

    val biasText: String?
        get() {
            val b = exposureBias ?: return null
            if (abs(b) <= 0.01) return null
            return (if (b > 0) "+" else "") + trim(roundHalfAway(b * 10) / 10) + " EV"
        }

    /** "23mm   ƒ/2   1/250   ISO 160" */
    val exposureLine: String
        get() = listOfNotNull(focalText, apertureText, shutterText, isoText).joinToString("   ")

    companion object {
        val EXIF_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.US)

        fun zone(offset: String): ZoneOffset? {
            val t = offset.trim()
            if (t.length < 6) return null
            val sign = t[0]
            if (sign != '+' && sign != '-') return null
            val parts = t.substring(1).split(':').filter { it.isNotEmpty() }
            if (parts.size != 2) return null
            val h = parts[0].toIntOrNull() ?: return null
            val m = parts[1].toIntOrNull() ?: return null
            return try {
                ZoneOffset.ofTotalSeconds((if (sign == '-') -1 else 1) * (h * 3600 + m * 60))
            } catch (_: Exception) {
                null
            }
        }

        fun trim(d: Double): String =
            if (d % 1.0 == 0.0) d.toLong().toString() else String.format(Locale.US, "%.1f", d)
    }
}

/** Swift's `rounded()`: half away from zero (Kotlin's `round` is half-even). */
internal fun roundHalfAway(x: Double): Double = if (x < 0) -floor(-x + 0.5) else floor(x + 0.5)

internal fun capitalizeWord(w: String): String =
    w.lowercase().replaceFirstChar { it.titlecase(Locale.US) }

/** "STANDARD" → "Standard", "FLAT MONOCHROME" → "Flat Monochrome". */
internal fun capitalizeWords(s: String): String = s.split(' ').joinToString(" ") { capitalizeWord(it) }

/** A Fujifilm in-camera recipe as recorded in the maker note. */
@Serializable
data class FilmRecipe(
    var filmSimulation: String,
    var grainEffect: String? = null,          // "Weak, Small"
    var colorChrome: String? = null,          // Off / Weak / Strong
    var colorChromeBlue: String? = null,
    var whiteBalance: String? = null,
    var kelvin: Int? = null,
    var wbShiftRed: Int? = null,
    var wbShiftBlue: Int? = null,
    var dynamicRange: String? = null,         // DR100 / DR200 / DR400 / DR-Auto
    /** D-Range Priority (0x1443–0x1445): "Auto", "Weak", "Strong"; null when off. */
    var dRangePriority: String? = null,
    var highlight: Double? = null,
    var shadow: Double? = null,
    var color: String? = null,
    var sharpness: String? = null,
    var noiseReduction: String? = null,
    var clarity: Int? = null,
    var monoWarmCool: Int? = null,
    var monoMagentaGreen: Int? = null,
) {
    val isMonochrome: Boolean
        get() {
            val s = filmSimulation.lowercase()
            return "acros" in s || "monochrome" in s || "sepia" in s
        }

    val wbText: String?
        get() {
            var wb = whiteBalance ?: return null
            val k = kelvin
            if (k != null && wb == "Kelvin") wb = "${k}K"
            val r = wbShiftRed ?: 0
            val b = wbShiftBlue ?: 0
            if (r != 0 || b != 0) wb += ", ${signed(r)} Red & ${signed(b)} Blue"
            return wb
        }

    /**
     * Ordered (label, value) rows — the format Fuji X Weekly–style recipes are shared in.
     * With D-Range Priority on, the camera ignores DR / Highlight / Shadow, so one
     * "D-Range Priority" row takes their place.
     */
    val rows: List<Pair<String, String>>
        get() {
            val r = mutableListOf("Film Simulation" to filmSimulation)
            grainEffect?.let { r += "Grain Effect" to it }
            colorChrome?.let { r += "Color Chrome Effect" to it }
            colorChromeBlue?.let { r += "Color Chrome FX Blue" to it }
            wbText?.let { r += "White Balance" to it }
            val drp = dRangePriority
            if (drp != null) {
                r += "D-Range Priority" to drp
            } else {
                dynamicRange?.let { r += "Dynamic Range" to it }
                highlight?.let { r += "Highlight" to signed(it) }
                shadow?.let { r += "Shadow" to signed(it) }
            }
            color?.let { if (!isMonochrome) r += "Color" to it }
            val wc = monoWarmCool
            if (isMonochrome && wc != null) r += "Monochromatic Color" to "WC ${signed(wc)} MG ${signed(monoMagentaGreen ?: 0)}"
            sharpness?.let { r += "Sharpness" to it }
            noiseReduction?.let { r += "High ISO NR" to it }
            clarity?.let { r += "Clarity" to signed(it) }
            return r
        }

    /** Stable signature used to group shots that share the same recipe. */
    val signature: String get() = rows.joinToString("|") { "${it.first}=${it.second}" }

    /** One-line summary for badges: "Classic Chrome · DR200 · H-1 S+1", or "Classic Chrome · DRP Strong". */
    val shortLine: String
        get() {
            val parts = mutableListOf(filmSimulation)
            val drp = dRangePriority
            if (drp != null) {
                parts += "DRP $drp"
                return parts.joinToString(" · ")
            }
            dynamicRange?.let { parts += it }
            var tone = ""
            highlight?.let { tone += "H${signed(it)}" }
            shadow?.let { tone += (if (tone.isEmpty()) "" else " ") + "S${signed(it)}" }
            if (tone.isNotEmpty()) parts += tone
            return parts.joinToString(" · ")
        }

    companion object {
        fun signed(v: Int): String = if (v > 0) "+$v" else "$v"
        fun signed(v: Double): String {
            val s = if (v % 1.0 == 0.0) v.toLong().toString() else String.format(Locale.US, "%.1f", v)
            return if (v > 0) "+$s" else s
        }
    }
}

enum class ShotFormat {
    JPEG, HEIF, RAF, CR3, TIFF_RAW, VIDEO, UNKNOWN;

    val isRAW: Boolean get() = this == RAF || this == CR3 || this == TIFF_RAW

    companion object {
        fun fromFilename(name: String): ShotFormat {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "jpg", "jpeg" -> JPEG
                "heic", "heif", "hif" -> HEIF
                "raf" -> RAF
                "cr3" -> CR3
                "arw", "nef", "nrw", "dng", "cr2", "orf", "rw2", "pef", "srw", "3fr", "iiq", "rwl", "srf", "sr2", "erf", "x3f" -> TIFF_RAW
                "mov", "mp4", "mts", "m4v", "avi" -> VIDEO
                else -> UNKNOWN
            }
        }
    }
}
