package app.unroll.core

import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.floor

/**
 * Adds a GPS block to a camera JPEG **without moving a single existing byte inside its EXIF**.
 *
 * Android gallery apps only read location from the file itself (MediaStore's latitude/longitude
 * columns are read-only since Android 10), so a location has to be written into the JPEG. A normal
 * EXIF rewrite re-lays out every IFD, which breaks maker notes whose offsets are absolute — Sony's
 * are relative to the TIFF header, for example. Instead we *append*:
 *
 *  1. a copy of IFD0 with one extra entry (0x8825 → GPS IFD), whose other 12-byte records are
 *     copied verbatim, so their value offsets still point at the original, untouched data;
 *  2. the GPS IFD and its rationals;
 *
 * all at the end of the APP1 segment, then point the TIFF header at the new IFD0 and grow the
 * APP1 length. The old IFD0 stays in place, unreferenced. The image data, maker note (film recipe,
 * rating) and every other tag keep their exact bytes and positions. The MPF index (APP2) stores
 * offsets relative to itself, so the embedded previews still resolve; only the first image's size
 * in the MP entry is bumped by the bytes we added.
 *
 * Returns null — leave the file alone — when there is no EXIF, it already has GPS, or the
 * segment would outgrow 64 KB.
 */
object GpsInjector {
    fun inject(jpeg: ByteArray, lat: Double, lon: Double, altitude: Double? = null): ByteArray? {
        val v = ByteView(jpeg)
        if (!v.matches(0, byteArrayOf(0xFF.toByte(), 0xD8.toByte()))) return null
        var p = 2
        var app1: Int = -1
        var app1Len = 0
        var mpfBase = -1
        while (p + 4 <= v.count) {
            if (v.u8(p) != 0xFF) break
            val marker = v.u8(p + 1) ?: break
            if (marker == 0xFF) { p += 1; continue }
            if (marker == 0xD8 || marker in 0xD0..0xD7 || marker == 0x01) { p += 2; continue }
            if (marker == 0xDA || marker == 0xD9) break
            val len = v.u16(p + 2, false) ?: break
            if (marker == 0xE1 && app1 < 0 && v.matches(p + 4, "Exif\u0000\u0000")) { app1 = p; app1Len = len }
            if (marker == 0xE2 && mpfBase < 0 && v.matches(p + 4, "MPF\u0000")) mpfBase = p + 8
            p += 2 + len
        }
        if (app1 < 0) return null
        val tiffBase = app1 + 10
        val segEnd = app1 + 2 + app1Len
        if (segEnd > jpeg.size) return null
        val t = TiffStructure.at(v, tiffBase) ?: return null
        val le = t.little
        val ifd0Off = t.firstIFDOffset ?: return null
        val ifd0At = tiffBase + pos(ifd0Off)
        val n = v.u16(ifd0At, le) ?: return null
        if (n <= 0 || ifd0At + 2 + n * 12 + 4 > segEnd) return null
        val records = (0 until n).map { i -> jpeg.copyOfRange(ifd0At + 2 + i * 12, ifd0At + 14 + i * 12) }
        if (records.any { ByteView(it).u16(0, le) == 0x8825 }) return null
        val next = jpeg.copyOfRange(ifd0At + 2 + n * 12, ifd0At + 6 + n * 12)

        val w = Writer(le)
        val pad = (segEnd - tiffBase) % 2                      // keep IFDs word-aligned
        val newIfd0 = (segEnd - tiffBase) + pad
        val ifd0Size = 2 + (n + 1) * 12 + 4
        val gpsIfd = newIfd0 + ifd0Size
        val gpsCount = if (altitude != null) 7 else 5
        val gpsHeader = 2 + gpsCount * 12 + 4
        var data = gpsIfd + gpsHeader

        // New IFD0: original records, plus 0x8825, in tag order.
        val gpsRecord = Writer(le).apply { u16(0x8825); u16(4); u32(1); u32(gpsIfd.toLong()) }.bytes()
        val tagOf = { r: ByteArray -> ByteView(r).u16(0, le) ?: 0 }
        val merged = (records + listOf(gpsRecord)).sortedBy(tagOf)
        repeat(pad) { w.u8(0) }
        w.u16(n + 1)
        merged.forEach { w.raw(it) }
        w.raw(next)

        // GPS IFD.
        val latR = dmsRationals(abs(lat))
        val lonR = dmsRationals(abs(lon))
        val g = Writer(le)
        g.u16(gpsCount)
        g.u16(0x0000); g.u16(1); g.u32(4); g.raw(byteArrayOf(2, 3, 0, 0))                       // GPSVersionID 2.3
        g.u16(0x0001); g.u16(2); g.u32(2); g.raw(byteArrayOf(if (lat < 0) 'S'.code.toByte() else 'N'.code.toByte(), 0, 0, 0))
        g.u16(0x0002); g.u16(5); g.u32(3); g.u32(data.toLong()); data += 24
        g.u16(0x0003); g.u16(2); g.u32(2); g.raw(byteArrayOf(if (lon < 0) 'W'.code.toByte() else 'E'.code.toByte(), 0, 0, 0))
        g.u16(0x0004); g.u16(5); g.u32(3); g.u32(data.toLong()); data += 24
        if (altitude != null) {
            g.u16(0x0005); g.u16(1); g.u32(1); g.raw(byteArrayOf(if (altitude < 0) 1 else 0, 0, 0, 0))
            g.u16(0x0006); g.u16(5); g.u32(1); g.u32(data.toLong()); data += 8
        }
        g.u32(0)
        latR.forEach { g.u32(it.first); g.u32(it.second) }
        lonR.forEach { g.u32(it.first); g.u32(it.second) }
        if (altitude != null) { g.u32(Math.round(abs(altitude) * 100)); g.u32(100) }

        val added = w.bytes() + g.bytes()
        val newLen = app1Len + added.size
        if (newLen > 0xFFFF) return null

        val out = ByteArrayOutputStream(jpeg.size + added.size)
        val head = jpeg.copyOfRange(0, segEnd)
        head[app1 + 2] = (newLen shr 8).toByte()
        head[app1 + 3] = (newLen and 0xFF).toByte()
        Writer(le).apply { u32(newIfd0.toLong()) }.bytes().copyInto(head, tiffBase + 4)
        out.write(head)
        out.write(added)
        out.write(jpeg, segEnd, jpeg.size - segEnd)
        val result = out.toByteArray()

        // MPF: the first image grew by `added.size` bytes; its MP entry records that size.
        if (mpfBase > segEnd) bumpFirstMPSize(result, mpfBase + added.size, added.size)
        return result
    }

    private fun bumpFirstMPSize(b: ByteArray, mpHeader: Int, delta: Int) {
        val v = ByteView(b)
        val t = TiffStructure.at(v, mpHeader) ?: return
        val first = t.firstIFDOffset ?: return
        val mp = t.entries(first).tag(0xB002) ?: return
        if (mp.count < 16) return
        val sizeAt = mp.valuePos + 4
        val size = v.u32(sizeAt, t.little) ?: return
        Writer(t.little).apply { u32(size + delta) }.bytes().copyInto(b, sizeAt)
    }

    /** Degrees, minutes, seconds×1000 — the precision cameras use (~3 cm). */
    private fun dmsRationals(deg: Double): List<Pair<Long, Long>> {
        val d = floor(deg)
        val mFull = (deg - d) * 60
        val m = floor(mFull)
        val s = (mFull - m) * 60
        return listOf(d.toLong() to 1L, m.toLong() to 1L, Math.round(s * 1000) to 1000L)
    }

    private class Writer(val little: Boolean) {
        private val o = ByteArrayOutputStream()
        fun u8(x: Int) = o.write(x and 0xFF)
        fun u16(x: Int) { if (little) { u8(x); u8(x shr 8) } else { u8(x shr 8); u8(x) } }
        fun u32(x: Long) {
            if (little) for (i in 0..3) u8((x shr (8 * i)).toInt()) else for (i in 3 downTo 0) u8((x shr (8 * i)).toInt())
        }
        fun raw(b: ByteArray) = o.write(b)
        fun bytes(): ByteArray = o.toByteArray()
    }
}
