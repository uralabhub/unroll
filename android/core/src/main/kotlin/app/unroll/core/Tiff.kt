package app.unroll.core

/**
 * Bounds-checked random access over a byte buffer. Every read returns null instead of throwing,
 * because we routinely parse *truncated* headers (the first 192 KB of a 60 MB RAW).
 */
class ByteView(val b: ByteArray) {
    val count: Int get() = b.size

    fun u8(o: Int): Int? = if (o >= 0 && o < b.size) b[o].toInt() and 0xFF else null

    fun u16(o: Int, little: Boolean): Int? {
        if (o < 0 || o + 2 > b.size) return null
        val a = b[o].toInt() and 0xFF
        val c = b[o + 1].toInt() and 0xFF
        return if (little) a or (c shl 8) else (a shl 8) or c
    }

    /** Unsigned 32-bit value, as Long so it never goes negative. */
    fun u32(o: Int, little: Boolean): Long? {
        if (o < 0 || o + 4 > b.size) return null
        val a = (b[o].toLong() and 0xFF)
        val c = (b[o + 1].toLong() and 0xFF)
        val d = (b[o + 2].toLong() and 0xFF)
        val e = (b[o + 3].toLong() and 0xFF)
        return if (little) a or (c shl 8) or (d shl 16) or (e shl 24) else (a shl 24) or (c shl 16) or (d shl 8) or e
    }

    fun matches(o: Int, s: String): Boolean = matches(o, s.toByteArray(Charsets.ISO_8859_1))

    fun matches(o: Int, bytes: ByteArray): Boolean {
        if (o < 0 || o + bytes.size > b.size) return false
        for (i in bytes.indices) if (b[o + i] != bytes[i]) return false
        return true
    }

    /** Printable ASCII, trimmed at the first NUL. */
    fun ascii(o: Int, n: Int): String? {
        if (o < 0 || n <= 0 || o + n > b.size) return null
        val sb = StringBuilder()
        for (i in 0 until n) {
            val c = b[o + i].toInt() and 0xFF
            if (c == 0) break
            if (c in 0x20..0x7E) sb.append(c.toChar())
        }
        val s = sb.toString().trim(' ')
        return s.ifEmpty { null }
    }

    fun firstIndexOf(pattern: ByteArray, from: Int = 0, to: Int? = null): Int? {
        val last = minOf(to ?: b.size, b.size) - pattern.size
        if (pattern.isEmpty() || from > last) return null
        var i = maxOf(0, from)
        while (i <= last) {
            if (b[i] == pattern[0] && matches(i, pattern)) return i
            i++
        }
        return null
    }

    fun firstIndexOf(s: String, from: Int = 0, to: Int? = null): Int? =
        firstIndexOf(s.toByteArray(Charsets.ISO_8859_1), from, to)
}

/** File offsets come from untrusted bytes: anything outside Int range becomes an unreadable position. */
internal fun pos(l: Long): Int = if (l in 0..Int.MAX_VALUE.toLong()) l.toInt() else Int.MAX_VALUE

class TiffEntry(
    val tag: Int,
    val type: Int,
    val count: Int,
    /** Absolute position (in the ByteView) of the first value byte. */
    val valuePos: Int,
)

fun List<TiffEntry>.tag(tag: Int): TiffEntry? = firstOrNull { it.tag == tag }

/**
 * A TIFF / EXIF IFD walker. [base] is the absolute position offsets are relative to
 * (the TIFF header for EXIF, the MakerNote start for Fujifilm, etc).
 */
class TiffStructure(val v: ByteView, val base: Int, val little: Boolean) {

    companion object {
        /** Reads the "II"/"MM" byte order at [base]; null when there is no TIFF header there. */
        fun at(v: ByteView, base: Int): TiffStructure? {
            val a = v.u8(base) ?: return null
            val c = v.u8(base + 1) ?: return null
            if (a != c) return null
            return when (a) {
                0x49 -> TiffStructure(v, base, true)
                0x4D -> TiffStructure(v, base, false)
                else -> null
            }
        }

        fun typeSize(t: Int): Int = when (t) {
            1, 2, 6, 7 -> 1
            3, 8 -> 2
            4, 9, 11, 13 -> 4
            5, 10, 12 -> 8
            else -> 0
        }
    }

    /** TIFF magic: 42 normally; ORF uses "RO"/"RS", RW2 uses 0x55. */
    val magic: Int? get() = v.u16(base + 2, little)
    val firstIFDOffset: Long? get() = v.u32(base + 4, little)

    /** Entries of the IFD at [off] (relative to base). Empty when out of range. */
    fun entries(ifdAt: Long): List<TiffEntry> {
        if (ifdAt <= 0 || ifdAt >= v.count) return emptyList()
        val p = base + ifdAt.toInt()
        val n = v.u16(p, little) ?: return emptyList()
        if (n <= 0 || n >= 1024) return emptyList()
        val out = ArrayList<TiffEntry>(n)
        for (i in 0 until n) {
            val e = p + 2 + i * 12
            val tag = v.u16(e, little) ?: break
            val type = v.u16(e + 2, little) ?: break
            val cnt = v.u32(e + 4, little) ?: break
            if (cnt >= (1L shl 26)) continue
            val size = typeSize(type).toLong() * cnt
            if (size <= 0) continue
            val vp = if (size <= 4) e + 8 else {
                val o = v.u32(e + 8, little) ?: continue
                pos(base.toLong() + o)
            }
            out.add(TiffEntry(tag, type, cnt.toInt(), vp))
        }
        return out
    }

    fun nextIFD(after: Long): Long? {
        if (after <= 0 || after >= v.count) return null
        val p = base + after.toInt()
        val n = v.u16(p, little) ?: return null
        val next = v.u32(p + 2 + n * 12, little) ?: return null
        return if (next > 0) next else null
    }

    fun int(e: TiffEntry?, index: Int = 0): Long? {
        if (e == null || index >= e.count) return null
        val p = e.valuePos + index * typeSize(e.type)
        return when (e.type) {
            1, 7 -> v.u8(p)?.toLong()
            6 -> v.u8(p)?.toByte()?.toLong()
            3 -> v.u16(p, little)?.toLong()
            8 -> v.u16(p, little)?.toShort()?.toLong()
            4, 13 -> v.u32(p, little)
            9 -> v.u32(p, little)?.toInt()?.toLong()
            else -> null
        }
    }

    fun ints(e: TiffEntry?): List<Long> {
        if (e == null) return emptyList()
        return (0 until minOf(e.count, 4096)).mapNotNull { int(e, it) }
    }

    fun rational(e: TiffEntry?, index: Int = 0): Double? {
        if (e == null || (e.type != 5 && e.type != 10) || index >= e.count) return null
        val p = e.valuePos + index * 8
        val n = v.u32(p, little) ?: return null
        val d = v.u32(p + 4, little) ?: return null
        if (d == 0L) return null
        if (e.type == 10) return n.toInt().toDouble() / d.toInt().toDouble()
        return n.toDouble() / d.toDouble()
    }

    fun string(e: TiffEntry?): String? = e?.let { v.ascii(it.valuePos, it.count) }
}
