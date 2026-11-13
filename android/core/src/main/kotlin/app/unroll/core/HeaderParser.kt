package app.unroll.core

class HeaderInfo {
    var format: ShotFormat = ShotFormat.UNKNOWN
    var meta: ShotMeta = ShotMeta()
    /** Largest embedded JPEG preview we found (RAF/ARW/NEF/DNG/CR2/CR3 preview, JPEG MPF preview). */
    var preview: ByteRange? = null
    /** Preview announced by a maker note (Olympus/OM System CameraSettings) — a candidate for TIFF RAWs. */
    var makerNotePreview: ByteRange? = null
}

/**
 * Parses the *first bytes* of a camera file. This is the heart of the "instant" experience:
 * 192 KB over USB gives us the full EXIF, the film recipe, the star rating and the location
 * of a 1–8 MP preview inside the file, so we never pull a 60 MB RAW just to look at it.
 */
object HeaderParser {
    /**
     * How many leading bytes to read. Covers EXIF (≤64 KB APP1) + MPF index in JPEGs and the
     * RAF header + embedded JPEG's EXIF. TIFF RAWs keep their IFDs near the start too.
     */
    const val HEADER_LENGTH = 192 * 1024

    private val SOI = byteArrayOf(0xFF.toByte(), 0xD8.toByte())

    fun parse(data: ByteArray): HeaderInfo {
        val v = ByteView(data)
        val info = HeaderInfo()
        when {
            v.matches(0, SOI) -> {
                info.format = ShotFormat.JPEG
                parseJPEG(v, 0, info, previewFromMPF = true)
            }
            v.matches(0, "FUJIFILMCCD-RAW") -> {
                info.format = ShotFormat.RAF
                parseRAF(v, info)
            }
            v.matches(4, "ftyp") -> {
                val brand = v.ascii(8, 4) ?: ""
                if (brand.startsWith("crx")) {
                    info.format = ShotFormat.CR3
                    parseCR3(v, info)
                } else {
                    info.format = ShotFormat.HEIF
                    parseHEIF(v, info)
                }
            }
            else -> TiffStructure.at(v, 0)?.let {
                info.format = ShotFormat.TIFF_RAW
                parseTIFFRaw(it, info)
            }
        }
        return info
    }

    // JPEG

    fun parseJPEG(v: ByteView, start: Int, info: HeaderInfo, previewFromMPF: Boolean) {
        var p = start + 2
        var exifBase: Int? = null
        var mpfBase: Int? = null
        while (p + 4 <= v.count) {
            if (v.u8(p) != 0xFF) break
            val marker = v.u8(p + 1) ?: break
            if (marker == 0xFF) { p += 1; continue }                                    // fill byte
            if (marker == 0xD8 || marker in 0xD0..0xD7 || marker == 0x01) { p += 2; continue }
            if (marker == 0xDA || marker == 0xD9) break                                  // start of scan / end
            val len = v.u16(p + 2, false) ?: break
            if (len < 2) break
            val body = p + 4
            when {
                marker == 0xE1 && v.matches(body, "Exif\u0000\u0000") -> if (exifBase == null) exifBase = body + 6
                marker == 0xE1 && v.matches(body, "http://ns.adobe.com/xap/1.0/\u0000") ->
                    if (info.meta.rating == null) info.meta.rating = xmpRating(v, body, len - 2)
                marker == 0xE2 && v.matches(body, "MPF\u0000") -> mpfBase = body + 4
                marker == 0xC0 || marker == 0xC1 || marker == 0xC2 -> if (info.meta.height == null) {   // SOF: pixel size
                    info.meta.height = v.u16(body + 1, false)
                    info.meta.width = v.u16(body + 3, false)
                }
            }
            p += 2 + len
        }
        exifBase?.let { TiffStructure.at(v, it)?.let { t -> parseExifTIFF(t, info) } }
        if (previewFromMPF) {
            mpfBase?.let { TiffStructure.at(v, it)?.let { t -> mpfLargestPreview(t)?.let { r -> info.preview = r } } }
        }
    }

    /** Multi-Picture Format index: Fujifilm/Sony/Canon/Nikon JPEGs carry a ~2–8 MP preview here. */
    fun mpfLargestPreview(t: TiffStructure): ByteRange? {
        val first = t.firstIFDOffset ?: return null
        val mp = t.entries(first).tag(0xB002) ?: return null
        val n = mp.count / 16
        var best: ByteRange? = null
        for (i in 1 until maxOf(n, 1)) {
            val p = mp.valuePos + i * 16
            val size = t.v.u32(p + 4, t.little) ?: continue
            val off = t.v.u32(p + 8, t.little) ?: continue
            if (off <= 0 || size <= 0) continue
            val r = ByteRange(t.base + off, size)
            if (r.length > (best?.length ?: 0)) best = r
        }
        return best
    }

    fun xmpRating(v: ByteView, from: Int, length: Int): Int? {
        val end = minOf(v.count, from + maxOf(0, length))
        if (end <= from || from < 0) return null
        val s = String(v.b, from, end - from, Charsets.UTF_8)
        for (pattern in listOf("xmp:Rating=\"", "<xmp:Rating>")) {
            val i = s.indexOf(pattern)
            if (i >= 0) {
                val digits = s.substring(i + pattern.length).takeWhile { it == '-' || it.isDigit() }
                digits.toIntOrNull()?.let { return it.coerceIn(0, 5) }
            }
        }
        return null
    }

    // EXIF

    fun parseExifTIFF(t: TiffStructure, info: HeaderInfo) {
        val ifd0Off = t.firstIFDOffset ?: return
        val ifd0 = t.entries(ifd0Off)
        val m = info.meta
        m.make = m.make ?: t.string(ifd0.tag(0x010F))
        m.model = m.model ?: t.string(ifd0.tag(0x0110))
        m.orientation = m.orientation ?: t.int(ifd0.tag(0x0112))?.toInt()
        t.int(ifd0.tag(0x4746))?.let { if (it > 0) m.rating = minOf(5L, it).toInt() }      // Microsoft/EXIF Rating
        // XMP packet in IFD0 (tag 0x02BC, used by TIFF RAWs incl. Panasonic RW2). Canon/Nikon/
        // Olympus/Panasonic/Pentax have no maker-note rating tag, so this is where stars live.
        if (m.rating == null) {
            ifd0.tag(0x02BC)?.let { x -> xmpRating(t.v, x.valuePos, x.count)?.let { if (it > 0) m.rating = it } }
        }
        t.int(ifd0.tag(0x8769))?.let { exifOff ->
            val ex = t.entries(exifOff)
            parseExifIFD(t, ex, m)
            ex.tag(0x927C)?.let { mn ->
                parseMakerNote(t, mn.valuePos, mn.count, m.make ?: "", m)?.let { info.makerNotePreview = it }
            }
        }
        t.int(ifd0.tag(0x8825))?.let { parseGPS(t, it, m) }
    }

    fun parseGPS(t: TiffStructure, ifdAt: Long, m: ShotMeta) {
        if (m.latitude != null) return
        val g = t.entries(ifdAt)
        val lat = dms(t, g.tag(0x0002)) ?: return
        val lon = dms(t, g.tag(0x0004)) ?: return
        if (lat == 0.0 && lon == 0.0) return
        m.latitude = if (t.string(g.tag(0x0001)) == "S") -lat else lat
        m.longitude = if (t.string(g.tag(0x0003)) == "W") -lon else lon
    }

    fun parseExifIFD(t: TiffStructure, ex: List<TiffEntry>, m: ShotMeta) {
        m.exposureTime = m.exposureTime ?: t.rational(ex.tag(0x829A))
        m.fNumber = m.fNumber ?: t.rational(ex.tag(0x829D))
        m.iso = m.iso ?: t.int(ex.tag(0x8827))?.toInt()
        m.dateTimeOriginal = m.dateTimeOriginal ?: t.string(ex.tag(0x9003))
        m.offsetTime = m.offsetTime ?: t.string(ex.tag(0x9011))
        m.exposureBias = m.exposureBias ?: t.rational(ex.tag(0x9204))
        m.focalLength = m.focalLength ?: t.rational(ex.tag(0x920A))
        m.focalLength35 = m.focalLength35 ?: t.int(ex.tag(0xA405))?.toInt()
        m.lens = m.lens ?: t.string(ex.tag(0xA434))
        if (m.width == null) m.width = t.int(ex.tag(0xA002))?.toInt()
        if (m.height == null) m.height = t.int(ex.tag(0xA003))?.toInt()
    }

    private fun dms(t: TiffStructure, e: TiffEntry?): Double? {
        val d = t.rational(e, 0) ?: return null
        return d + (t.rational(e, 1) ?: 0.0) / 60 + (t.rational(e, 2) ?: 0.0) / 3600
    }

    /**
     * Decodes the bits of each maker note we use. Returns a preview range when the note carries
     * one (Olympus / OM System keep the full-size preview there, not in the TIFF IFDs).
     */
    fun parseMakerNote(exif: TiffStructure, p: Int, length: Int, make: String, m: ShotMeta): ByteRange? {
        val v = exif.v
        val mk = make.lowercase()
        // "FUJIFILM" headers also appear in some Leica/Minolta/Sharp notes (ExifTool), so check the make too.
        if (v.matches(p, "FUJIFILM") && (mk.isEmpty() || mk.startsWith("fujifilm"))) {
            val res = FujiMakerNote.parse(v, p)
            res.recipe?.let { m.recipe = it }
            res.rating?.let { if (it > 0) m.rating = minOf(5, it) }
        } else if (mk.startsWith("nikon") && v.matches(p, "Nikon\u0000")) {
            // Type-3 Nikon maker note: embedded TIFF header 10 bytes in.
            val t = TiffStructure.at(v, p + 10) ?: return null
            val first = t.firstIFDOffset ?: return null
            t.entries(first).tag(0x0023)?.let { pc ->
                // PictureControlData: 4-char version, then the name. ExifTool's PictureControl3
                // (version "03xx", Z bodies) has 4 more bytes before the name; v1/v2 do not.
                val version = v.ascii(pc.valuePos, 4) ?: ""
                val nameAt = pc.valuePos + if (version.startsWith("03")) 8 else 4
                v.ascii(nameAt, 20)?.let { m.look = "Picture Control: " + capitalizeWords(it) }
            }
        } else if (mk.startsWith("sony")) {
            // "SONY DSC \0\0\0" / "SONY CAM": IFD 12 bytes in. ExifTool also knows a header-less
            // variant (Sony5, seen in ARW/SR2) where the IFD starts at the note itself.
            // Offsets are relative to the EXIF TIFF header in both cases.
            val ifdAt = if (v.matches(p, "SONY ")) p + 12 else p
            val e = exif.entries((ifdAt - exif.base).toLong())
            exif.int(e.tag(0x2002))?.let { if (it in 1..5) m.rating = it.toInt() }
            exif.string(e.tag(0xB020))?.let { m.look = "Creative Style: $it" }
        } else if (v.matches(p, "OLYMPUS\u0000") || v.matches(p, "OM SYSTEM\u0000")) {
            return olympusPreview(v, p)
        }
        return null
    }

    /**
     * Olympus "OLYMPUS\0II" (IFD at +12) and OM System "OM SYSTEM\0\0\0II" (IFD at +16) maker notes.
     * Offsets are relative to the note start. The full-size preview is PreviewImageStart/Length
     * (0x0101/0x0102) inside the CameraSettings sub-IFD 0x2020 (ExifTool Olympus tables).
     */
    fun olympusPreview(v: ByteView, p: Int): ByteRange? {
        val isOM = v.matches(p, "OM SYSTEM")
        val order = p + if (isOM) 12 else 8
        val a = v.u8(order) ?: return null
        if (a != v.u8(order + 1) || (a != 0x49 && a != 0x4D)) return null
        val t = TiffStructure(v, p, a == 0x49)
        val cs = t.entries(if (isOM) 16 else 12).tag(0x2020) ?: return null
        // Newer notes store 0x2020 as an IFD/LONG pointer; older ones embed the IFD as UNDEFINED data.
        val sub: Long = if (cs.type == 7) (cs.valuePos - p).toLong() else t.int(cs) ?: return null
        val c = t.entries(sub)
        val start = t.int(c.tag(0x0101)) ?: return null
        val len = t.int(c.tag(0x0102)) ?: return null
        if (start <= 0 || len <= 1024) return null
        return ByteRange(p + start, len)
    }

    // RAF

    fun parseRAF(v: ByteView, info: HeaderInfo) {
        val jOff = v.u32(84, false) ?: return
        val jLen = v.u32(88, false) ?: return
        if (jOff <= 0) return
        info.preview = ByteRange(jOff, jLen)
        if (v.matches(pos(jOff), SOI)) {
            parseJPEG(v, pos(jOff), info, previewFromMPF = false)
            // Pixel size reported by the embedded preview is not the RAW size.
            info.meta.width = null; info.meta.height = null
        }
    }

    // HEIF (Sony/Canon/Nikon/Fujifilm HIF)

    /**
     * HEIF keeps EXIF in an item: `meta/iinf` names it ("Exif"), `meta/iloc` says where its bytes
     * are (ISO/IEC 23008-12 Annex A: a u32 offset to the TIFF header, then the EXIF payload).
     * Works on the truncated header as long as the meta box and the EXIF item fit in it.
     */
    fun parseHEIF(v: ByteView, info: HeaderInfo) {
        val meta = Heif.findTopLevel(v, "meta") ?: return
        val box = Heif.parseMeta(v, meta)
        box.exif?.let { (off, len) ->
            val p = pos(off)
            val tiffOff = v.u32(p, false)
            var tiffAt = if (tiffOff != null) p + 4 + pos(tiffOff) else -1
            if (TiffStructure.at(v, tiffAt) == null) {
                // Some writers leave the offset at 0 and still prefix "Exif\0\0".
                tiffAt = v.firstIndexOf("Exif\u0000\u0000", p, minOf(v.count, p + 32))?.plus(6) ?: -1
            }
            if (len > 8) TiffStructure.at(v, tiffAt)?.let { parseExifTIFF(it, info) }
        }
        box.size?.let { (w, h) -> info.meta.width = w; info.meta.height = h }
    }

    // TIFF-based RAW (ARW, NEF, DNG, CR2, ORF, RW2, PEF…)

    fun parseTIFFRaw(t: TiffStructure, info: HeaderInfo) {
        parseExifTIFF(t, info)
        val candidates = mutableListOf<ByteRange>()
        info.makerNotePreview?.let { candidates += it }
        val ifd0 = t.firstIFDOffset

        // Panasonic RW2/RWL: the preview is a whole JPEG stored as tag 0x002E (JpgFromRaw) in IFD0,
        // and the EXIF lives inside that JPEG (IFD0 has no ExifIFD pointer). ExifTool PanasonicRaw.
        if (t.magic == 0x55 && ifd0 != null) {
            val j = t.entries(ifd0).tag(0x002E)
            if (j != null && j.count > 1024) {
                candidates += ByteRange(j.valuePos.toLong(), j.count.toLong())
                if (t.v.matches(j.valuePos, SOI)) {
                    val w = info.meta.width; val h = info.meta.height
                    parseJPEG(t.v, j.valuePos, info, previewFromMPF = false)
                    info.meta.width = w; info.meta.height = h
                }
            }
        }
        val visited = HashSet<Long>()
        val queue = ArrayList<Long>()
        var off = t.firstIFDOffset
        while (off != null && queue.size < 8) { queue += off; off = t.nextIFD(off) }

        while (queue.isNotEmpty()) {
            val o = queue.removeAt(queue.size - 1)
            if (!visited.add(o) || visited.size >= 32) continue
            val e = t.entries(o)
            for (sub in t.ints(e.tag(0x014A))) queue += sub                               // SubIFDs
            val jo = t.int(e.tag(0x0201))
            val jl = t.int(e.tag(0x0202))
            if (jo != null && jl != null && jl > 1024) candidates += ByteRange(t.base + jo, jl)
            val compression = t.int(e.tag(0x0103)) ?: 1L
            val photometric = t.int(e.tag(0x0106)) ?: 0L
            val bps = t.int(e.tag(0x0102)) ?: 8L
            val isJPEGStrip = compression == 6L || (compression == 7L && bps == 8L && (photometric == 2L || photometric == 6L))
            val offs = t.ints(e.tag(0x0111))
            val lens = t.ints(e.tag(0x0117))
            // Old-style JPEG strips (compression 6) are a preview only in IFD0 (CR2). Canon's IFD3
            // holds the *raw sensor data* as lossless JPEG, also tagged 6 — never take it.
            val stripOK = if (compression == 6L) o == ifd0 else true
            if (isJPEGStrip && stripOK && offs.size == 1 && lens.size == 1 && lens[0] > 1024) {
                candidates += ByteRange(t.base + offs[0], lens[0])
            }
        }
        info.preview = candidates.maxByOrNull { it.length }
    }

    // CR3 (ISO-BMFF)

    private val CR3_XMP_UUID = bytes(
        0xBE, 0x7A, 0xCF, 0xCB, 0x97, 0xA9, 0x42, 0xE8, 0x9C, 0x71, 0x99, 0x94, 0x91, 0xE3, 0xAF, 0xAC,
    )

    fun parseCR3(v: ByteView, info: HeaderInfo) {
        // CMT1 = IFD0 (make/model), CMT2 = EXIF IFD — each is a self-contained TIFF.
        v.firstIndexOf("CMT1")?.let { p -> TiffStructure.at(v, p + 4)?.let { parseExifTIFF(it, info) } }
        v.firstIndexOf("CMT2")?.let { p ->
            TiffStructure.at(v, p + 4)?.let { t -> t.firstIFDOffset?.let { parseExifIFD(t, t.entries(it), info.meta) } }
        }
        // CMT4 = GPS IFD (lclevy/canon_cr3).
        v.firstIndexOf("CMT4")?.let { p ->
            TiffStructure.at(v, p + 4)?.let { t -> t.firstIFDOffset?.let { parseGPS(t, it, info.meta) } }
        }
        // XMP lives in uuid box be7acfcb-97a9-42e8-9c71-999491e3afac, right after moov.
        if (info.meta.rating == null) {
            val u = v.firstIndexOf(CR3_XMP_UUID)
            val boxSize = u?.let { v.u32(it - 8, false) }
            if (u != null && boxSize != null && boxSize > 24) {
                xmpRating(v, u + 16, (boxSize - 24).toInt())?.let { if (it > 0) info.meta.rating = it }
            }
        }
        // PRVW box: a ~1620×1080 JPEG, size stored as big-endian u32 right before SOI.
        val p = v.firstIndexOf("PRVW") ?: return
        val soi = v.firstIndexOf(bytes(0xFF, 0xD8, 0xFF), p, p + 64) ?: return
        val size = v.u32(soi - 4, false) ?: return
        if (size > 1024) info.preview = ByteRange(soi.toLong(), size)
    }
}

fun bytes(vararg ints: Int): ByteArray = ByteArray(ints.size) { ints[it].toByte() }
