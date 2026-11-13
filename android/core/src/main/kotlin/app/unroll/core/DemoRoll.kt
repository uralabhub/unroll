package app.unroll.core

import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

/**
 * The demo roll: a realistic fake SD card (DCIM/100_FUJI) so the whole pipeline — header parsing,
 * recipes, ratings, protect flags, RAW pairing, geotagging, frames, recap — can be tried without
 * a camera. This part decides *what* each file says; the app paints the pixels.
 */
object DemoRoll {
    class Look(
        val filmMode: Int?,          // null → monochrome via saturation
        val saturation: Int,
        val highlight: Int, val shadow: Int,
        val grain: Int, val grainSize: Int, val cce: Int, val ccb: Int,
        val wb: Int, val red: Int, val blue: Int,
        val dr: Int,
        /** Multiply tint for the painted photo, RGB 0–1. */
        val tint: Triple<Float, Float, Float>,
        /** D-Range Priority (0x1443 mode, level) — one look uses it so the demo shows the row. */
        val drp: Pair<Int, Int>? = null,
    ) {
        val mono: Boolean get() = filmMode == null
    }

    val looks = listOf(
        Look(0x600, 0x400, 16, -16, 32, 16, 64, 32, 0x000, 40, -100, 200, Triple(0.85f, 0.78f, 0.62f)),
        Look(0x800, 0x100, -32, -32, 64, 16, 64, 0, 0x200, 60, -80, 400, Triple(0.95f, 0.72f, 0.55f)),
        Look(null, 0x501, -16, -48, 64, 32, 0, 0, 0x000, 0, 0, 200, Triple(1f, 1f, 1f)),
        Look(0xA00, 0x080, 0, -8, 32, 16, 32, 32, 0x100, 40, -60, 400, Triple(1.0f, 0.82f, 0.6f), drp = 1 to 2),
        Look(0xB00, 0x000, 0, 0, 0, 0, 32, 32, 0x000, 0, 0, 100, Triple(0.8f, 0.88f, 0.95f)),
    )

    /** A walk through District 1, Saigon — matches the demo timestamps. */
    val route = listOf(
        10.7769 to 106.7009, 10.7798 to 106.6990, 10.7820 to 106.6958, 10.7797 to 106.6922,
        10.7745 to 106.6980, 10.7725 to 106.6980, 10.7712 to 106.7040, 10.7740 to 106.7055,
        10.7769 to 106.7009,
    )

    const val COUNT = 42
    const val FOLDER = "DCIM/100_FUJI"

    fun startOfWalk(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Instant =
        LocalDateTime.of(now.atZone(zone).toLocalDate(), LocalTime.of(7, 30)).atZone(zone).toInstant()

    fun timestamp(i: Int, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Instant =
        startOfWalk(now, zone).plusSeconds(i * 9L * 60 + (i * 37L) % 120)

    fun coordinate(t: Instant, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Pair<Double, Double> {
        val total = COUNT * 9.0 * 60
        val f = ((t.epochSecond - startOfWalk(now, zone).epochSecond) / total).coerceIn(0.0, 1.0) * (route.size - 1)
        val i = minOf(route.size - 2, f.toInt())
        val r = f - i
        val a = route[i]; val b = route[i + 1]
        return (a.first + (b.first - a.first) * r) to (a.second + (b.second - a.second) * r)
    }

    /** The GPS log a phone would have recorded during the demo walk (a fix a minute). */
    fun walk(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): PhotoWalk {
        val start = startOfWalk(now, zone)
        val end = timestamp(COUNT, now, zone)
        val pts = mutableListOf<TrailPoint>()
        var t = start
        while (!t.isAfter(end)) {
            val c = coordinate(t, now, zone)
            pts += TrailPoint(c.first, c.second, t.toEpochMilli(), 8.0)
            t = t.plusSeconds(60)
        }
        return PhotoWalk("demo", start.toEpochMilli(), end.toEpochMilli(), pts, isDemo = true)
    }

    class Spec(
        val index: Int,
        val stem: String,
        val look: Look,
        val date: Instant,
        val isXT5: Boolean,
        val portrait: Boolean,
        val rating: Int,
        val withRAF: Boolean,
        /** "Protect" on camera = read-only file. */
        val locked: Boolean,
    ) {
        val width: Int get() = if (portrait) 800 else 1200
        val height: Int get() = if (portrait) 1200 else 800
    }

    fun spec(i: Int, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()) = Spec(
        index = i,
        stem = String.format(Locale.US, "DSCF%04d", 4120 + i),
        look = looks[(i / 3) % looks.size],
        date = timestamp(i, now, zone),
        isXT5 = i % 4 == 3,
        portrait = i % 5 == 2,
        rating = listOf(0, 0, 0, 4, 0, 0, 5, 0, 3, 0, 0, 0, 5)[i % 13],
        withRAF = i % 3 == 0,
        locked = i % 7 == 5,
    )

    /** EXIF + Fujifilm maker note for one demo shot, ready for [TiffBuilder.jpeg]. */
    fun exif(s: Spec, zone: ZoneId = ZoneId.systemDefault()): ByteArray {
        val look = s.look
        val i = s.index
        val local = s.date.atZone(zone)
        val dateText = ShotMeta.EXIF_DATE.format(local)
        val off = local.offset.totalSeconds
        val offText = String.format(Locale.US, "%s%02d:%02d", if (off >= 0) "+" else "-", abs(off) / 3600, abs(off) % 3600 / 60)
        val e = TiffBuilder.Entry
        val mn = mutableListOf(
            e.undefined(0x0000, "0130".toByteArray(Charsets.ISO_8859_1)),
            e.short(0x1001, 0x82),
            e.short(0x1002, look.wb),
            e.short(0x1003, look.saturation),
            e.slong(0x100A, look.red, look.blue),
            e.short(0x100E, 0x2E0),
            e.slong(0x100F, if (s.isXT5) -2000 else 0),
            e.slong(0x1040, look.shadow),
            e.slong(0x1041, look.highlight),
            e.long(0x1047, look.grain),
            e.long(0x1048, look.cce),
            e.long(0x104C, look.grainSize),
            e.long(0x104E, look.ccb),
            e.short(0x1402, 0x100),
            e.short(0x1403, look.dr),
            e.long(0x1431, s.rating),
        )
        look.filmMode?.let { mn += e.short(0x1401, it) }
        look.drp?.let { (mode, level) ->
            mn += e.short(0x1443, mode)
            mn += e.short(if (mode == 0) 0x1444 else 0x1445, level)
        }

        val shutter = listOf(125, 250, 500, 1000, 60, 2000)[i % 6]
        val fnum = listOf(20, 28, 40, 56, 80)[i % 5]
        val iso = listOf(125, 160, 200, 400, 800, 1600)[i % 6]
        val model = if (s.isXT5) "X-T5" else "X100VI"
        val ifd0 = listOf(
            e.ascii(0x010F, "FUJIFILM"), e.ascii(0x0110, model), e.short(0x0112, 1),
            e.ascii(0x0131, "Digital Camera $model Ver1.10"), e.ascii(0x0132, dateText),
        )
        val exif = mutableListOf(
            e.rational(0x829A, 1 to shutter),
            e.rational(0x829D, fnum to 10),
            e.short(0x8827, iso),
            e.ascii(0x9003, dateText),
            e.ascii(0x9011, offText),
            e.srational(0x9204, if (i % 3 == 0) -1 else 0, 3),
            e.rational(0x920A, (if (s.isXT5) 33 else 23) to 1),
            e.short(0xA405, if (s.isXT5) 50 else 35),
            e.long(0xA002, s.width), e.long(0xA003, s.height),
            e.undefined(0x927C, TiffBuilder.fujiMakerNote(mn)),
        )
        if (s.isXT5) exif += e.ascii(0xA434, "XF33mmF1.4 R LM WR")
        return TiffBuilder.exifTIFF(ifd0, exif)
    }
}

/** SplitMix64 — deterministic noise for the painted demo photos. */
class SplitMix(seed: Long) {
    private var state = seed
    fun next(): Double {
        state += -0x61c8864680b583ebL                          // 0x9E3779B97F4A7C15
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L          // 0xBF58476D1CE4E5B9
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L          // 0x94D049BB133111EB
        z = z xor (z ushr 31)
        return (z ushr 11).toDouble() / (1L shl 53).toDouble()
    }
}
