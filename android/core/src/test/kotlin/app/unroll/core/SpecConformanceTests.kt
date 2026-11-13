package app.unroll.core

import app.unroll.core.Fixtures.ascii
import app.unroll.core.Fixtures.baseJPEG
import app.unroll.core.Fixtures.be32
import app.unroll.core.Fixtures.box
import app.unroll.core.Fixtures.fullBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Color
import java.io.File

/**
 * Port of UnrollTests/SpecConformanceTests.swift. Files built byte-for-byte from the published
 * layouts (ExifTool tag tables, lclevy/canon_cr3, ISO/IEC 23008-12). No real camera files
 * involved: each test pins one rule from a spec.
 */
class SpecConformanceTests {
    @get:Rule val tmp = TemporaryFolder()

    private fun byte(tag: Int, b: Int) = E(tag, 1, 1, byteArrayOf(b.toByte()))

    private fun gray() = baseJPEG(Color.GRAY)

    private fun fujiRecipe(make: String = "FUJIFILM", note: List<E>): FilmRecipe? {
        val tiff = TiffBuilder.exifTIFF(
            listOf(E.ascii(0x010F, make), E.ascii(0x0110, "X-T5")),
            listOf(E.undefined(0x927C, TiffBuilder.fujiMakerNote(note))),
        )
        return HeaderParser.parse(TiffBuilder.jpeg(gray(), tiff)).meta.recipe
    }

    // Fujifilm (ExifTool FujiFilm table)

    @Test fun fujiMonoWarmCoolIsSignedByte() {
        // 0x1049 / 0x104B are int8s: 0xFE = -2, 0x01 = +1.
        val r = fujiRecipe(note = listOf(E.short(0x1003, 0x300), E.short(0x1401, 0), byte(0x1049, 0xFE), byte(0x104B, 0x01)))!!
        assertEquals("Monochrome", r.filmSimulation)
        assertEquals(-2, r.monoWarmCool)
        assertEquals(1, r.monoMagentaGreen)
    }

    @Test fun fujiGrainCombinedValues() {
        // 0x1047: 16 = Weak/Small, 48 = Strong/Small; an explicit 0x104C size wins.
        assertEquals("Weak, Small", fujiRecipe(note = listOf(E.short(0x1401, 0x800), E.long(0x1047, 16)))?.grainEffect)
        assertEquals("Strong, Small", fujiRecipe(note = listOf(E.short(0x1401, 0x800), E.long(0x1047, 48)))?.grainEffect)
        assertEquals("Strong, Large", fujiRecipe(note = listOf(E.short(0x1401, 0x800), E.long(0x1047, 64), E.long(0x104C, 32)))?.grainEffect)
    }

    @Test fun fujiWBShiftScalesOnlyMultiplesOf20() {
        assertEquals(2, fujiRecipe(note = listOf(E.short(0x1401, 0), E.slong(0x100A, 40, -180)))?.wbShiftRed)
        assertEquals(-9, fujiRecipe(note = listOf(E.short(0x1401, 0), E.slong(0x100A, 40, -180)))?.wbShiftBlue)
        val odd = fujiRecipe(note = listOf(E.short(0x1401, 0), E.slong(0x100A, 3, -1)))!!
        assertEquals(3, odd.wbShiftRed)
        assertEquals(-1, odd.wbShiftBlue)
    }

    @Test fun fujiUnknownOrMissingFilmModeIsNotGuessed() {
        assertEquals("Film sim 0xC00", fujiRecipe(note = listOf(E.short(0x1401, 0xC00)))?.filmSimulation)
        assertNull("no FilmMode tag → no recipe, not Provia", fujiRecipe(note = listOf(E.short(0x1003, 0x000))))
    }

    @Test fun fujiHeaderOnOtherMakeIsIgnored() {
        // ExifTool: the "FUJIFILM" maker note header is also used by some Leica/Minolta/Sharp models.
        assertNull(fujiRecipe(make = "LEICA", note = listOf(E.short(0x1401, 0x600))))
    }

    // TIFF-based RAW

    @Test fun cr2RawDataStripIsNotThePreview() {
        // CR2: IFD0 StripOffsets = JPEG preview (compression 6). IFD3 = raw sensor data, *also*
        // compression 6 (lossless JPEG) and much bigger. Only IFD0's strip may be the preview.
        val header = bytes(0x49, 0x49, 0x2A, 0x00) + TiffBuilder.le32(16) + ascii("CR") + bytes(2, 0) + TiffBuilder.le32(0)
        val preview = bytes(0xFF, 0xD8) + ByteArray(3000) { 0x11 }
        val rawData = ByteArray(50_000) { 0x22 }
        fun ifd0(stripAt: Int, next: Int) = TiffBuilder.ifd(
            listOf(E.ascii(0x010F, "Canon"), E.ascii(0x0110, "Canon EOS R"), E.short(0x0103, 6), E.long(0x0111, stripAt), E.long(0x0117, preview.size)),
            16, next,
        )
        val ifd3At = 16 + ifd0(0, 0).size
        val ifd3Size = TiffBuilder.ifd(listOf(E.short(0x0103, 6), E.long(0x0111, 0), E.long(0x0117, 0)), 0).size
        val previewAt = ifd3At + ifd3Size
        val rawAt = previewAt + preview.size
        val ifd3 = TiffBuilder.ifd(listOf(E.short(0x0103, 6), E.long(0x0111, rawAt), E.long(0x0117, rawData.size)), ifd3At)
        val info = HeaderParser.parse(header + ifd0(previewAt, ifd3At) + ifd3 + preview + rawData)
        assertEquals("Canon EOS R", info.meta.model)
        assertEquals(ByteRange(previewAt.toLong(), preview.size.toLong()), info.preview)
    }

    @Test fun rw2PreviewAndExifFromJpgFromRaw() {
        // RW2: "IIU\0", IFD0 tag 0x002E JpgFromRaw holds a full JPEG; the EXIF is inside it.
        val exif = TiffBuilder.exifTIFF(
            listOf(E.ascii(0x010F, "Panasonic"), E.ascii(0x0110, "DC-S5M2")),
            listOf(E.ascii(0x9003, "2026:11:13 09:00:00"), E.short(0x8827, 400)),
        )
        val jpg = TiffBuilder.jpeg(baseJPEG(Color.GRAY, noise = true), exif)
        val header = bytes(0x49, 0x49, 0x55, 0x00) + TiffBuilder.le32(8)
        val ifd0 = TiffBuilder.ifd(listOf(E.ascii(0x010F, "Panasonic"), E.ascii(0x0110, "DC-S5M2"), E.undefined(0x002E, jpg)), 8)
        val file = header + ifd0
        val jpgAt = ByteView(file).firstIndexOf(bytes(0xFF, 0xD8))!!
        val info = HeaderParser.parse(file)
        assertEquals(ByteRange(jpgAt.toLong(), jpg.size.toLong()), info.preview)
        assertEquals("2026:11:13 09:00:00", info.meta.dateTimeOriginal)
        assertEquals(400, info.meta.iso)
        assertNull("preview pixel size is not the sensor size", info.meta.width)
    }

    @Test fun orfPreviewFromOlympusMakerNote() {
        // "OLYMPUS\0II\3\0", IFD at +12, offsets from note start. CameraSettings 0x2020 (IFD) →
        // PreviewImageStart 0x0101 / PreviewImageLength 0x0102.
        val jpeg = bytes(0xFF, 0xD8) + ByteArray(4000) { 0x33 }
        val mainIFDSize = 2 + 12 + 4
        val subAt = 12 + mainIFDSize
        val subSize = 2 + 2 * 12 + 4
        val previewAt = subAt + subSize
        var note = ascii("OLYMPUS\u0000") + bytes(0x49, 0x49, 0x03, 0x00)
        note += TiffBuilder.ifd(listOf(E(0x2020, 13, 1, TiffBuilder.le32(subAt.toLong()))), 12)
        note += TiffBuilder.ifd(listOf(E.long(0x0101, previewAt), E.long(0x0102, jpeg.size)), subAt)
        note += jpeg
        val tiff = TiffBuilder.exifTIFF(listOf(E.ascii(0x010F, "OM Digital Solutions"), E.ascii(0x0110, "OM-1")), listOf(E.undefined(0x927C, note)))
        tiff[2] = 0x52; tiff[3] = 0x4F                          // ORF magic "IIRO"
        val noteAt = ByteView(tiff).firstIndexOf(ascii("OLYMPUS\u0000"))!!
        val info = HeaderParser.parse(tiff)
        assertEquals("OM-1", info.meta.model)
        assertEquals(ByteRange((noteAt + previewAt).toLong(), jpeg.size.toLong()), info.preview)
    }

    @Test fun tiffRawXMPPacketRating() {
        val xmp = ascii("<x:xmpmeta><rdf:Description xmp:Rating=\"2\"/></x:xmpmeta>")
        val header = bytes(0x49, 0x49, 0x2A, 0x00) + TiffBuilder.le32(8)
        val file = header + TiffBuilder.ifd(listOf(E.ascii(0x010F, "NIKON CORPORATION"), E.undefined(0x02BC, xmp)), 8)
        assertEquals(2, HeaderParser.parse(file).meta.rating)
    }

    // Maker notes

    private fun nikonLook(version: String, nameOffset: Int): String? {
        var pc = ascii(version) + ByteArray(nameOffset - 4)
        pc += ascii("FLAT") + ByteArray(40)
        val inner = bytes(0x49, 0x49, 0x2A, 0x00) + TiffBuilder.le32(8) + TiffBuilder.ifd(listOf(E.undefined(0x0023, pc)), 8)
        val note = ascii("Nikon\u0000") + bytes(0x02, 0x10, 0x00, 0x00) + inner
        val tiff = TiffBuilder.exifTIFF(listOf(E.ascii(0x010F, "NIKON CORPORATION"), E.ascii(0x0110, "NIKON Z 8")), listOf(E.undefined(0x927C, note)))
        return HeaderParser.parse(TiffBuilder.jpeg(gray(), tiff)).meta.look
    }

    @Test fun nikonPictureControlNameOffsetByVersion() {
        assertEquals("Picture Control: Flat", nikonLook("0310", 8))   // PictureControl3 (Z)
        assertEquals("Picture Control: Flat", nikonLook("0200", 4))   // v1/v2
    }

    @Test fun sonyHeaderlessMakerNote() {
        // ExifTool Sony5: no "SONY DSC" header, IFD at the note start, offsets from the EXIF header.
        val note = TiffBuilder.ifd(listOf(E.long(0x2002, 3)), 0)
        val tiff = TiffBuilder.exifTIFF(listOf(E.ascii(0x010F, "SONY"), E.ascii(0x0110, "ILCE-7CM2")), listOf(E.undefined(0x927C, note)))
        assertEquals(3, HeaderParser.parse(TiffBuilder.jpeg(gray(), tiff)).meta.rating)
    }

    @Test fun sonyHeaderedMakerNoteRatingAndCreativeStyle() {
        // "SONY DSC \0\0\0" then the IFD; offsets relative to the EXIF TIFF header (ExifTool Sony).
        val body = TiffBuilder.exifTIFF(listOf(E.ascii(0x010F, "SONY"), E.ascii(0x0110, "ILCE-7M4")), listOf(E.undefined(0x927C, ByteArray(64))))
        // Find where the maker note payload landed, then write a real headered note there.
        val v = ByteView(body)
        val t = TiffStructure.at(v, 0)!!
        val ex = t.entries(t.int(t.entries(t.firstIFDOffset!!).tag(0x8769))!!)
        val mn = ex.tag(0x927C)!!
        val ifdAt = mn.valuePos + 12
        val style = ascii("Vivid\u0000")
        // IFD: 2 entries; the Creative Style string lives right after it.
        val strAt = ifdAt + 2 + 2 * 12 + 4
        val note = ascii("SONY DSC \u0000\u0000\u0000") +
            TiffBuilder.le16(2) +
            TiffBuilder.le16(0x2002) + TiffBuilder.le16(4) + TiffBuilder.le32(1) + TiffBuilder.le32(5) +
            TiffBuilder.le16(0xB020) + TiffBuilder.le16(2) + TiffBuilder.le32(style.size.toLong()) + TiffBuilder.le32(strAt.toLong()) +
            TiffBuilder.le32(0) + style
        note.copyInto(body, mn.valuePos)
        val m = HeaderParser.parse(TiffBuilder.jpeg(gray(), body)).meta
        assertEquals(5, m.rating)
        assertEquals("Creative Style: Vivid", m.look)
    }

    @Test fun exifRatingTag4746() {
        val tiff = TiffBuilder.exifTIFF(listOf(E.ascii(0x010F, "Canon"), E.ascii(0x0110, "Canon EOS R5"), E.short(0x4746, 4)), emptyList())
        assertEquals(4, HeaderParser.parse(TiffBuilder.jpeg(gray(), tiff)).meta.rating)
    }

    // CR3 (lclevy/canon_cr3)

    @Test fun cr3ReadsGPSFromCMT4AndRatingFromXMPBox() {
        val tiffHeader = bytes(0x49, 0x49, 0x2A, 0x00) + TiffBuilder.le32(8)
        val cmt1 = tiffHeader + TiffBuilder.ifd(listOf(E.ascii(0x010F, "Canon"), E.ascii(0x0110, "Canon EOS R6m2")), 8)
        val cmt4 = tiffHeader + TiffBuilder.ifd(
            listOf(E.ascii(0x0001, "S"), E.rational(0x0002, 33 to 1, 52 to 1, 0 to 1), E.ascii(0x0003, "E"), E.rational(0x0004, 151 to 1, 12 to 1, 0 to 1)), 8,
        )
        val uuid = bytes(0xBE, 0x7A, 0xCF, 0xCB, 0x97, 0xA9, 0x42, 0xE8, 0x9C, 0x71, 0x99, 0x94, 0x91, 0xE3, 0xAF, 0xAC)
        val xmp = ascii("<x:xmpmeta><rdf:Description xmp:Rating=\"4\"/></x:xmpmeta>")
        val file = box("ftyp", ascii("crx ") + be32(1) + ascii("crx isom")) +
            box("moov", box("CMT1", cmt1) + box("CMT4", cmt4)) +
            box("uuid", uuid + xmp)
        val info = HeaderParser.parse(file)
        assertEquals(ShotFormat.CR3, info.format)
        val m = info.meta
        assertEquals("Canon EOS R6m2", m.model)
        assertEquals(-33.8667, m.latitude ?: 0.0, 0.001)
        assertEquals(151.2, m.longitude ?: 0.0, 0.001)
        assertEquals(4, m.rating)
    }

    @Test fun cr3PreviewFromPRVW() {
        val jpeg = bytes(0xFF, 0xD8, 0xFF) + ByteArray(5000) { 0x44 }
        // PRVW payload (lclevy): 4 unknown, 2 unknown, 2 width, 2 height, 2 unknown, u32 size, JPEG.
        val prvw = box("PRVW", ByteArray(4) + bytes(0, 1, 0x06, 0x54, 0x04, 0x38, 0, 1) + be32(jpeg.size) + jpeg)
        val file = box("ftyp", ascii("crx ") + be32(1)) + box("uuid", ByteArray(16) + prvw)
        val soi = ByteView(file).firstIndexOf(bytes(0xFF, 0xD8, 0xFF))!!
        assertEquals(ByteRange(soi.toLong(), jpeg.size.toLong()), HeaderParser.parse(file).preview)
    }

    // HEIF (ISO/IEC 23008-12)

    /**
     * iOS uses ImageIO here; Android's core has no platform decoder, so the HEIF box walk is ours.
     * This builds the boxes a camera writes: ftyp, meta{hdlr, pitm, iinf{infe hvc1, infe Exif},
     * iloc, iprp{ipco{ispe}, ipma}}, mdat{…, Exif item = u32 offset + "Exif\0\0" + TIFF}.
     */
    private fun heif(ilocVersion: Int, exifInIdat: Boolean, model: String = "ILCE-7M4"): ByteArray {
        val tiff = TiffBuilder.exifTIFF(
            listOf(E.ascii(0x010F, "SONY"), E.ascii(0x0110, model)),
            listOf(E.ascii(0x9003, "2026:11:13 10:20:30"), E.short(0x8827, 800)),
        )
        val exifItem = be32(6) + ascii("Exif\u0000\u0000") + tiff
        val image = ByteArray(300) { 0x5A }
        val ftyp = box("ftyp", ascii("heic") + be32(0) + ascii("mif1heic"))
        val hdlr = fullBox("hdlr", 0, be32(0) + ascii("pict") + ByteArray(12) + bytes(0))
        val pitm = fullBox("pitm", 0, TiffBuilder.be32(1).copyOfRange(2, 4))
        fun infe(id: Int, type: String) = fullBox("infe", 2, byteArrayOf(0, id.toByte()) + byteArrayOf(0, 0) + ascii(type) + bytes(0))
        val iinf = fullBox("iinf", 0, byteArrayOf(0, 2) + infe(1, "hvc1") + infe(2, "Exif"))
        val ispe = fullBox("ispe", 0, be32(6240) + be32(4160))
        val ipma = fullBox("ipma", 0, be32(1) + byteArrayOf(0, 1) + bytes(1) + bytes(0x81))
        val iprp = box("iprp", box("ipco", ispe) + ipma)
        val idat = if (exifInIdat) box("idat", exifItem) else ByteArray(0)

        fun iloc(imageAt: Int, exifAt: Int): ByteArray {
            // offset_size 4, length_size 4, base_offset_size 0, index_size 0
            val count = byteArrayOf(0, 2)
            fun item(id: Int, method: Int, off: Int, len: Int): ByteArray {
                var b = byteArrayOf(0, id.toByte())
                if (ilocVersion >= 1) b += byteArrayOf(0, method.toByte())
                return b + byteArrayOf(0, 0) + byteArrayOf(0, 1) + be32(off) + be32(len)
            }
            return fullBox("iloc", ilocVersion, bytes(0x44, 0x00) + count +
                item(1, 0, imageAt, image.size) +
                item(2, if (exifInIdat) 1 else 0, exifAt, exifItem.size))
        }
        // Two passes: lay out once with dummy offsets to learn the sizes, then for real.
        fun build(imageAt: Int, exifAt: Int): ByteArray {
            val meta = fullBox("meta", 0, hdlr + pitm + iinf + iloc(imageAt, exifAt) + iprp + idat)
            val mdat = box("mdat", image + if (exifInIdat) ByteArray(0) else exifItem)
            return ftyp + meta + mdat
        }
        val probe = build(0, 0)
        val mdatBody = probe.size - (image.size + if (exifInIdat) 0 else exifItem.size)
        return build(mdatBody, if (exifInIdat) 0 else mdatBody + image.size)
    }

    @Test fun heifHeaderMetadataFromExifItem() {
        for ((ver, idat) in listOf(0 to false, 1 to false, 1 to true)) {
            val info = HeaderParser.parse(heif(ver, idat))
            assertEquals("iloc v$ver idat=$idat", ShotFormat.HEIF, info.format)
            assertEquals("iloc v$ver idat=$idat", "ILCE-7M4", info.meta.model)
            assertEquals("2026:11:13 10:20:30", info.meta.dateTimeOriginal)
            assertEquals(800, info.meta.iso)
            assertEquals(6240, info.meta.width)
            assertEquals(4160, info.meta.height)
        }
    }

    @Test fun heifTruncatedBeforeExifItemStillGivesSizeAndDoesNotCrash() {
        val full = heif(0, false)
        for (n in 0 until full.size) HeaderParser.parse(full.copyOfRange(0, n))
        val cut = HeaderParser.parse(full.copyOfRange(0, full.size - 40))
        assertEquals(ShotFormat.HEIF, cut.format)
        assertNull(cut.meta.dateTimeOriginal)
    }

    // Sources

    @Test fun ricohOrderKeyIsStableAndChronological() {
        assertEquals(1_000_001_234L, RicohOrder.orderKey("100RICOH", "R0001234.DNG"))
        val a = RicohOrder.orderKey("100RICOH", "R0009999.JPG")!!
        val b = RicohOrder.orderKey("101RICOH", "R0000001.JPG")!!
        assertTrue(a < b)
        assertNull(RicohOrder.orderKey("MISC", "notes.txt"))
    }

    @Test fun writeProtectedCardIsNotAllKeepers() {
        // Every file read-only = card switch / read-only mount, not per-file Protect.
        val dir = File(tmp.root, "DCIM/100TEST").apply { mkdirs() }
        for (n in listOf("A001.JPG", "A002.JPG", "A003.JPG")) File(dir, n).apply { writeBytes(gray()); setWritable(false) }
        val shots = FolderScanner.scan(tmp.root)
        assertEquals(3, shots.size)
        assertTrue(shots.all { !it.locked })
    }

    @Test fun usbCatalogPairsAcrossFoldersByStem() {
        // MTP listing: same stem in two folders must not pair; RAW-only stays a shot of its own.
        fun item(folder: String, name: String, locked: Boolean = false) = CardItem(name, name, folder, 10, 0, locked)
        val shots = Pairing.pair(listOf(
            item("100_FUJI", "DSCF0001.JPG"), item("100_FUJI", "DSCF0001.RAF", locked = true),
            item("101_FUJI", "DSCF0001.RAF"), item("100_FUJI", "DSCF0002.HIF"), item("100_FUJI", "DSCF0002.RAF"),
            item("100_FUJI", "NOTES.TXT"),
        ))
        assertEquals(3, shots.size)
        val first = shots.first { it.primary.folder == "100_FUJI" && it.primary.name == "DSCF0001.JPG" }
        assertEquals("DSCF0001.RAF", first.raw?.name)
        assertTrue("Protect on either half protects the shot", first.locked)
        assertEquals(null, shots.first { it.primary.folder == "101_FUJI" }.raw)
        assertEquals("DSCF0002.RAF", shots.first { it.primary.name == "DSCF0002.HIF" }.raw?.name)
    }
}
