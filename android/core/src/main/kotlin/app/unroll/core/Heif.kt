package app.unroll.core

/**
 * Just enough ISO-BMFF / HEIF (ISO/IEC 23008-12) to find the EXIF item and the primary image
 * size inside a header that may be cut short. Every read is bounds-checked.
 */
object Heif {
    /** A box: [start] is the first header byte, [body] the first payload byte, [end] one past the box. */
    class Box(val type: String, val start: Int, val body: Int, val end: Int)

    class Meta(val exif: Pair<Long, Long>?, val size: Pair<Int, Int>?)

    /** Children of the byte range [from, to). Stops at the first malformed box. */
    fun children(v: ByteView, from: Int, to: Int): List<Box> {
        val out = mutableListOf<Box>()
        var p = from
        while (p + 8 <= to) {
            val size32 = v.u32(p, false) ?: break
            val type = v.ascii(p + 4, 4) ?: break
            var body = p + 8
            val size: Long = when (size32) {
                1L -> { body += 8; (v.u32(p + 8, false) ?: break) shl 32 or (v.u32(p + 12, false) ?: break) }
                0L -> (to - p).toLong()                              // box runs to the end
                else -> size32
            }
            if (size < 8) break
            val end = if (p + size > Int.MAX_VALUE) Int.MAX_VALUE else (p + size).toInt()
            out += Box(type, p, body, end)
            if (end <= p) break
            p = end
        }
        return out
    }

    fun findTopLevel(v: ByteView, type: String): Box? = children(v, 0, v.count).firstOrNull { it.type == type }

    fun parseMeta(v: ByteView, meta: Box): Meta {
        // meta is a FullBox: 4 bytes of version/flags before its children.
        val kids = children(v, meta.body + 4, minOf(meta.end, v.count))
        val exifIDs = kids.firstOrNull { it.type == "iinf" }?.let { exifItemIDs(v, it) } ?: emptySet()
        val idat = kids.firstOrNull { it.type == "idat" }
        val locations = kids.firstOrNull { it.type == "iloc" }?.let { iloc(v, it) } ?: emptyMap()
        val exif = exifIDs.firstNotNullOfOrNull { id ->
            locations[id]?.let { loc ->
                if (loc.constructionMethod == 1) {
                    idat?.let { Pair(it.body + loc.offset, loc.length) }
                } else if (loc.constructionMethod == 0) {
                    Pair(loc.offset, loc.length)
                } else null
            }
        }
        val primary = kids.firstOrNull { it.type == "pitm" }?.let { pitm ->
            val ver = v.u8(pitm.body) ?: 0
            if (ver == 0) v.u16(pitm.body + 4, false)?.toLong() else v.u32(pitm.body + 4, false)
        }
        val size = kids.firstOrNull { it.type == "iprp" }?.let { primarySize(v, it, primary) }
        return Meta(exif, size)
    }

    private fun exifItemIDs(v: ByteView, iinf: Box): Set<Long> {
        val ver = v.u8(iinf.body) ?: return emptySet()
        val first = iinf.body + 4 + if (ver == 0) 2 else 4
        val ids = mutableSetOf<Long>()
        for (infe in children(v, first, iinf.end)) {
            if (infe.type != "infe") continue
            val iv = v.u8(infe.body) ?: continue
            if (iv < 2) continue                                 // v0/v1 carry no item_type
            val idAt = infe.body + 4
            val id = if (iv == 2) v.u16(idAt, false)?.toLong() else v.u32(idAt, false)
            val typeAt = idAt + (if (iv == 2) 2 else 4) + 2         // + item_protection_index
            if (id != null && v.ascii(typeAt, 4) == "Exif") ids += id
        }
        return ids
    }

    class Location(val constructionMethod: Int, val offset: Long, val length: Long)

    /** item ID → first extent (base_offset + extent_offset, extent_length). */
    fun iloc(v: ByteView, box: Box): Map<Long, Location> {
        val ver = v.u8(box.body) ?: return emptyMap()
        var p = box.body + 4
        val a = v.u8(p) ?: return emptyMap()
        val b = v.u8(p + 1) ?: return emptyMap()
        val offsetSize = a shr 4
        val lengthSize = a and 0xF
        val baseOffsetSize = b shr 4
        val indexSize = if (ver == 1 || ver == 2) b and 0xF else 0
        p += 2
        val itemCount = (if (ver < 2) v.u16(p, false)?.toLong() else v.u32(p, false)) ?: return emptyMap()
        p += if (ver < 2) 2 else 4
        fun sized(at: Int, n: Int): Long? = when (n) {
            0 -> 0L
            4 -> v.u32(at, false)
            8 -> { val hi = v.u32(at, false); val lo = v.u32(at + 4, false); if (hi != null && lo != null) (hi shl 32) or lo else null }
            else -> null
        }
        val out = mutableMapOf<Long, Location>()
        for (i in 0 until minOf(itemCount, 4096L).toInt()) {
            val id = (if (ver < 2) v.u16(p, false)?.toLong() else v.u32(p, false)) ?: break
            p += if (ver < 2) 2 else 4
            var method = 0
            if (ver == 1 || ver == 2) { method = (v.u16(p, false) ?: break) and 0xF; p += 2 }
            p += 2                                                     // data_reference_index
            val base = sized(p, baseOffsetSize) ?: break
            p += baseOffsetSize
            val extents = v.u16(p, false) ?: break
            p += 2
            var first: Location? = null
            for (x in 0 until extents) {
                p += indexSize
                val off = sized(p, offsetSize) ?: return out
                p += offsetSize
                val len = sized(p, lengthSize) ?: return out
                p += lengthSize
                if (first == null) first = Location(method, base + off, len)
            }
            first?.let { out[id] = it }
        }
        return out
    }

    /** `ispe` (image spatial extents) of the primary item; the largest one when that can't be resolved. */
    private fun primarySize(v: ByteView, iprp: Box, primary: Long?): Pair<Int, Int>? {
        val kids = children(v, iprp.body, iprp.end)
        val ipco = kids.firstOrNull { it.type == "ipco" } ?: return null
        val props = children(v, ipco.body, ipco.end)
        fun ispe(b: Box): Pair<Int, Int>? {
            if (b.type != "ispe") return null
            val w = v.u32(b.body + 4, false) ?: return null
            val h = v.u32(b.body + 8, false) ?: return null
            return if (w in 1..65535 && h in 1..65535) w.toInt() to h.toInt() else null
        }
        val ipma = kids.firstOrNull { it.type == "ipma" }
        if (primary != null && ipma != null) {
            val ver = v.u8(ipma.body) ?: 0
            val flags = v.u8(ipma.body + 3) ?: 0
            var p = ipma.body + 4
            val n = v.u32(p, false) ?: 0
            p += 4
            for (i in 0 until minOf(n, 4096L).toInt()) {
                val id = (if (ver < 1) v.u16(p, false)?.toLong() else v.u32(p, false)) ?: break
                p += if (ver < 1) 2 else 4
                val count = v.u8(p) ?: break
                p += 1
                for (j in 0 until count) {
                    val index = if (flags and 1 == 1) (v.u16(p, false) ?: break) and 0x7FFF else (v.u8(p) ?: break) and 0x7F
                    p += if (flags and 1 == 1) 2 else 1
                    if (id == primary && index >= 1) props.getOrNull(index - 1)?.let(::ispe)?.let { return it }
                }
            }
        }
        return props.mapNotNull(::ispe).maxByOrNull { it.first.toLong() * it.second }
    }
}
