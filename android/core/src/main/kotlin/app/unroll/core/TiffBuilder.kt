package app.unroll.core

import java.io.ByteArrayOutputStream

/**
 * Minimal little-endian TIFF/EXIF writer. Used to synthesise realistic camera files for the
 * demo roll and for unit tests (platform encoders cannot write maker notes, so we write EXIF ourselves).
 */
object TiffBuilder {
    class Entry(val tag: Int, val type: Int, val count: Int, val payload: ByteArray) {
        companion object {
            fun ascii(tag: Int, s: String): Entry {
                val b = s.toByteArray(Charsets.UTF_8) + 0.toByte()
                return Entry(tag, 2, b.size, b)
            }
            fun byte(tag: Int, v: Int) = Entry(tag, 1, 1, byteArrayOf(v.toByte()))
            fun short(tag: Int, v: Int) = Entry(tag, 3, 1, le16(v))
            fun long(tag: Int, v: Long) = Entry(tag, 4, 1, le32(v))
            fun long(tag: Int, v: Int) = long(tag, v.toLong())
            fun slong(tag: Int, vararg vs: Int) = Entry(tag, 9, vs.size, cat(vs.map { le32(it.toLong()) }))
            fun rational(tag: Int, vararg vs: Pair<Int, Int>) =
                Entry(tag, 5, vs.size, cat(vs.map { le32(it.first.toLong()) + le32(it.second.toLong()) }))
            fun srational(tag: Int, n: Int, d: Int) = Entry(tag, 10, 1, le32(n.toLong()) + le32(d.toLong()))
            fun undefined(tag: Int, bytes: ByteArray) = Entry(tag, 7, bytes.size, bytes)
        }
    }

    fun le16(v: Int): ByteArray = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    fun le32(v: Long): ByteArray = ByteArray(4) { ((v shr (8 * it)) and 0xFF).toByte() }
    fun be32(v: Long): ByteArray = ByteArray(4) { ((v shr (8 * (3 - it))) and 0xFF).toByte() }
    fun cat(parts: List<ByteArray>): ByteArray {
        val o = ByteArrayOutputStream()
        parts.forEach { o.write(it) }
        return o.toByteArray()
    }

    /** Serialises one IFD placed at [offset] (relative to the TIFF base); out-of-line values follow it. */
    fun ifd(entries: List<Entry>, at: Int, next: Int = 0): ByteArray {
        val sorted = entries.sortedBy { it.tag }
        val headerSize = 2 + sorted.size * 12 + 4
        val head = ByteArrayOutputStream()
        val data = ByteArrayOutputStream()
        head.write(le16(sorted.size))
        for (e in sorted) {
            head.write(le16(e.tag)); head.write(le16(e.type)); head.write(le32(e.count.toLong()))
            if (e.payload.size <= 4) {
                head.write(e.payload); head.write(ByteArray(4 - e.payload.size))
            } else {
                head.write(le32((at + headerSize + data.size()).toLong()))
                data.write(e.payload)
                if (data.size() % 2 == 1) data.write(0)
            }
        }
        head.write(le32(next.toLong()))
        return head.toByteArray() + data.toByteArray()
    }

    fun size(entries: List<Entry>): Int = ifd(entries, 0).size

    /** Full EXIF TIFF: IFD0 → EXIF IFD (→ optional GPS IFD). */
    fun exifTIFF(ifd0: List<Entry>, exif: List<Entry>, gps: List<Entry> = emptyList()): ByteArray {
        val header = bytes(0x49, 0x49, 0x2A, 0x00) + le32(8)
        var i0 = ifd0 + Entry.long(0x8769, 0)
        if (gps.isNotEmpty()) i0 = i0 + Entry.long(0x8825, 0)
        val exifOff = 8 + size(i0)
        val gpsOff = exifOff + size(exif)
        i0 = i0.map {
            when (it.tag) {
                0x8769 -> Entry.long(0x8769, exifOff)
                0x8825 -> Entry.long(0x8825, gpsOff)
                else -> it
            }
        }
        var out = header + ifd(i0, 8) + ifd(exif, exifOff)
        if (gps.isNotEmpty()) out += ifd(gps, gpsOff)
        return out
    }

    /** Fujifilm maker note blob ("FUJIFILM" + offset 12 + IFD, offsets relative to blob start). */
    fun fujiMakerNote(entries: List<Entry>): ByteArray =
        "FUJIFILM".toByteArray(Charsets.ISO_8859_1) + le32(12) + ifd(entries, 12)

    /** Inserts an APP1/EXIF segment right after SOI, dropping existing APP0/APP1 segments. */
    fun jpeg(jpeg: ByteArray, withExif: ByteArray, extraSegments: List<ByteArray> = emptyList()): ByteArray {
        val src = jpeg
        if (src.size <= 4 || src[0] != 0xFF.toByte() || src[1] != 0xD8.toByte()) return jpeg
        var p = 2
        while (p + 4 <= src.size && src[p] == 0xFF.toByte() && (src[p + 1] == 0xE0.toByte() || src[p + 1] == 0xE1.toByte())) {
            p += 2 + (((src[p + 2].toInt() and 0xFF) shl 8) or (src[p + 3].toInt() and 0xFF))
        }
        val body = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + withExif
        val len = body.size + 2
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), (len and 0xFF).toByte()))
        out.write(body)
        extraSegments.forEach { out.write(it) }
        out.write(src, p, src.size - p)
        return out.toByteArray()
    }

    /** Wraps a JPEG in a minimal RAF container (header + embedded JPEG), for tests and the demo roll. */
    fun raf(embedding: ByteArray): ByteArray {
        val h = ByteArray(160)
        val magic = "FUJIFILMCCD-RAW 0201FF383501".toByteArray(Charsets.ISO_8859_1)
        magic.copyInto(h, 0)
        be32(160).copyInto(h, 84)
        be32(embedding.size.toLong()).copyInto(h, 88)
        return h + embedding
    }
}
