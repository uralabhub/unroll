package app.unroll.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Adaptive header reads (QA audit §1): structures referenced past the first 192 KB. */
class AdaptiveHeaderTests {
    @get:Rule val tmp = TemporaryFolder()

    /** A sparse "file": only the parts we place exist, the rest reads as zeros. */
    private class VirtualFile(val size: Long) {
        val parts = sortedMapOf<Long, ByteArray>()
        val reads = mutableListOf<ByteRange>()
        fun put(at: Long, b: ByteArray) = apply { parts[at] = b }
        fun read(r: ByteRange): ByteArray {
            reads += r
            val n = minOf(r.length, size - r.offset).toInt().coerceAtLeast(0)
            val out = ByteArray(n)
            for ((o, b) in parts) {
                val from = maxOf(o, r.offset); val to = minOf(o + b.size, r.offset + n)
                for (p in from until to) out[(p - r.offset).toInt()] = b[(p - o).toInt()]
            }
            return out
        }
        fun head() = read(ByteRange(0, HeaderParser.HEADER_LENGTH.toLong())).also { reads.clear() }
    }

    private val jpegStub = bytes(0xFF, 0xD8, 0xFF, 0xDB) + ByteArray(5000)
    private fun tiffHeader(ifd0: Long) = bytes(0x49, 0x49, 0x2A, 0x00) + TiffBuilder.le32(ifd0)

    @Test fun nikonStyleSubIFDPastHeader() {
        // IFD0 (make/model + one SubIFD) at 8; the SubIFD and its JPEG at ~300 KB, like a Z6 NEF.
        val subAt = 300_000L
        val jpgAt = 320_000L
        val ifd0 = TiffBuilder.ifd(listOf(E.ascii(0x010F, "NIKON CORPORATION"), E.ascii(0x0110, "NIKON Z 6"), E.long(0x014A, subAt)), 8)
        val sub = TiffBuilder.ifd(listOf(E.short(0x0103, 6), E.long(0x0201, jpgAt), E.long(0x0202, jpegStub.size.toLong())), subAt.toInt())
        val f = VirtualFile(40_000_000).put(0, tiffHeader(8) + ifd0).put(subAt, sub).put(jpgAt, jpegStub)
        val head = f.head()

        val first = HeaderParser.parse(head)
        assertEquals("NIKON Z 6", first.meta.model)
        assertNull(first.preview)
        assertEquals(listOf(ByteRange(subAt, HeaderParser.IFD_READ)), HeaderParser.missingRanges(head, f.size))

        val res = HeaderParser.parseAdaptive(head, f.size) { f.read(it) }
        assertEquals(ByteRange(jpgAt, jpegStub.size.toLong()), res.info.preview)
        assertEquals(1, res.rounds)
        assertEquals(HeaderParser.IFD_READ, res.extraBytesRead)
    }

    @Test fun ifd0AtEndOfFile() {
        // Sony NEX-3 / Ricoh GR II style: the header points at an IFD0 ~14 MB in, near the end.
        val size = 14_712_832L + 3000
        val ifdAt = 14_712_832L
        val exifAt = ifdAt + 1024
        val ifd0 = TiffBuilder.ifd(listOf(
            E.ascii(0x010F, "SONY"), E.ascii(0x0110, "NEX-3"),
            E.long(0x0201, 163_891), E.long(0x0202, jpegStub.size.toLong()), E.long(0x8769, exifAt),
        ), ifdAt.toInt())
        val exif = TiffBuilder.ifd(listOf(E.short(0x8827, 400)), exifAt.toInt())
        val f = VirtualFile(size).put(0, tiffHeader(ifdAt)).put(163_891, jpegStub).put(ifdAt, ifd0).put(exifAt, exif)
        val head = f.head()

        assertNull(HeaderParser.parse(head).meta.model)
        // Clamped to the end of the file.
        assertEquals(listOf(ByteRange(ifdAt, size - ifdAt)), HeaderParser.missingRanges(head, size))
        val res = HeaderParser.parseAdaptive(head, size) { f.read(it) }
        assertEquals("NEX-3", res.info.meta.model)
        assertEquals(400, res.info.meta.iso)
        assertEquals(ByteRange(163_891, jpegStub.size.toLong()), res.info.preview)
    }

    @Test fun farIFD0BeyondMergeCapStaysSparse() {
        // Phase One IIQ: IFD0 at ~107 MB. No 107 MB buffer — the chunk is kept sparse.
        val ifdAt = 107_253_304L
        val size = ifdAt + 20_000
        val ifd0 = TiffBuilder.ifd(listOf(E.ascii(0x010F, "Phase One"), E.ascii(0x0110, "IQ180"), E.long(0x0201, 1000), E.long(0x0202, jpegStub.size.toLong())), ifdAt.toInt())
        val f = VirtualFile(size).put(0, tiffHeader(ifdAt)).put(1000, jpegStub).put(ifdAt, ifd0)
        val res = HeaderParser.parseAdaptive(f.head(), size) { f.read(it) }
        assertEquals("IQ180", res.info.meta.model)
        assertEquals(ByteRange(1000, jpegStub.size.toLong()), res.info.preview)
        assertTrue(res.view.b.size <= HeaderParser.HEADER_LENGTH)
    }

    @Test fun subIFDOffsetArrayPastHeaderTakesTwoRounds() {
        val arrayAt = 250_000L
        val subs = listOf(400_000L, 500_000L, 600_000L)
        val ifd0 = TiffBuilder.ifd(listOf(E.ascii(0x0110, "K-3 Mark III"), TiffBuilder.Entry(0x014A, 4, 3, ByteArray(12))), 8).also { b ->
            // Point the 3-element SubIFD array (normally placed after the IFD) at 250 KB instead.
            val i = (0 until 2).first { k -> ByteView(b).u16(2 + k * 12, true) == 0x014A }
            TiffBuilder.le32(arrayAt).copyInto(b, 2 + i * 12 + 8)
        }
        val f = VirtualFile(30_000_000).put(0, tiffHeader(8) + ifd0)
            .put(arrayAt, TiffBuilder.cat(subs.map { TiffBuilder.le32(it) }))
        subs.forEachIndexed { k, o ->
            val len = 2000L + k * 3000
            f.put(o, TiffBuilder.ifd(listOf(E.short(0x0103, 6), E.long(0x0201, o + 512), E.long(0x0202, len)), o.toInt()))
        }
        val head = f.head()
        assertEquals(listOf(ByteRange(arrayAt, 12)), HeaderParser.missingRanges(head, f.size))
        val res = HeaderParser.parseAdaptive(head, f.size) { f.read(it) }
        assertEquals(2, res.rounds)
        assertEquals(ByteRange(600_512, 8000), res.info.preview)
    }

    @Test fun missingRangesIgnoresGarbageOffsets() {
        val head = tiffHeader(300L shl 20) + ByteArray(100)
        assertTrue(HeaderParser.missingRanges(head, null).isEmpty())          // ≥ 256 MiB
        val head2 = tiffHeader(5_000_000)
        assertTrue(HeaderParser.missingRanges(head2, 4_000_000).isEmpty())    // ≥ file size
        assertEquals(1, HeaderParser.missingRanges(head2, 6_000_000).size)
        // Not TIFF: nothing to ask for.
        assertTrue(HeaderParser.missingRanges(Fixtures.fujiJPEG(), null).isEmpty())
    }

    @Test fun completeHeaderNeedsNoReads() {
        var calls = 0
        val res = HeaderParser.parseAdaptive(Fixtures.fujiJPEG(), null) { calls++; null }
        assertEquals(0, calls)
        assertEquals("X100VI", res.info.meta.model)
    }

    @Test fun unreadableRangesStopGracefully() {
        val f = VirtualFile(1_000_000).put(0, tiffHeader(500_000))
        var calls = 0
        val res = HeaderParser.parseAdaptive(f.head(), f.size) { calls++; null }      // GetPartialObject unsupported
        assertEquals(1, calls)
        assertNull(res.info.meta.model)
    }

    @Test fun mergeZeroFillsAndCaps() {
        val data = byteArrayOf(1, 2, 3)
        val out = HeaderParser.merge(data, listOf(6L to byteArrayOf(9, 8), (HeaderParser.MERGE_CAP.toLong() - 1) to byteArrayOf(7, 7)))
        assertArrayEquals(byteArrayOf(1, 2, 3, 0, 0, 0, 9, 8), out)
        // Overlapping chunk overwrites; empty chunk list returns the input.
        assertArrayEquals(byteArrayOf(1, 5, 3), HeaderParser.merge(data, listOf(1L to byteArrayOf(5))))
        assertTrue(HeaderParser.merge(data, emptyList()) === data)
    }

    @Test fun coalesceJoinsNeighbours() {
        val r = HeaderParser.coalesce(listOf(ByteRange(100_000, 10), ByteRange(0, 100), ByteRange(50, 100), ByteRange(2000, 10)))
        assertEquals(listOf(ByteRange(0, 2010), ByteRange(100_000, 10)), r)
    }

    @Test fun uncompressedRGBThumbnailIsAFallback() {
        // Hasselblad 3FR / DNG IFD0: 8-bit RGB strip, no JPEG anywhere.
        val w = 64; val h = 48
        val stripAt = 1024L
        val ifd0 = TiffBuilder.ifd(listOf(
            E.ascii(0x010F, "Hasselblad"), E.ascii(0x0110, "Hasselblad H3D"),
            E.long(0x0100, w), E.long(0x0101, h), TiffBuilder.Entry(0x0102, 3, 3, TiffBuilder.le16(8) + TiffBuilder.le16(8) + TiffBuilder.le16(8)),
            E.short(0x0103, 1), E.short(0x0106, 2), E.short(0x0115, 3), E.long(0x0111, stripAt), E.long(0x0117, (w * h * 3).toLong()),
        ), 8)
        val file = (tiffHeader(8) + ifd0).copyOf(stripAt.toInt() + w * h * 3)
        val info = HeaderParser.parse(file)
        assertNull(info.preview)
        assertEquals(RgbPreview(ByteRange(stripAt, (w * h * 3).toLong()), w, h), info.rgbPreview)
        assertFalse("no extra reads for a complete file", HeaderParser.missingRanges(file, file.size.toLong()).isNotEmpty())
    }

    @Test fun nonTIFFRawsAreImportOnly() {
        val x3f = "FOVb".toByteArray() + ByteArray(400)
        assertEquals(ShotFormat.OTHER_RAW, HeaderParser.parse(x3f).format)
        val crw = bytes(0x49, 0x49, 0x1A, 0, 0, 0) + "HEAPCCDR".toByteArray() + ByteArray(100)
        assertEquals(ShotFormat.OTHER_RAW, HeaderParser.parse(crw).format)
        for (n in 0 until 64) HeaderParser.parse(bytes(0, 'M'.code, 'R'.code, 'M'.code) + ByteArray(n))
    }

    @Test fun mrwReadsTTWBlock() {
        val tiff = TiffBuilder.exifTIFF(listOf(E.ascii(0x010F, "KONICA MINOLTA"), E.ascii(0x0110, "DYNAX 5D")), listOf(E.short(0x8827, 200)))
        val ttw = bytes(0, 'T'.code, 'T'.code, 'W'.code) + TiffBuilder.be32(tiff.size.toLong()) + tiff
        val prd = bytes(0, 'P'.code, 'R'.code, 'D'.code) + TiffBuilder.be32(24) + ByteArray(24)
        val body = prd + ttw
        val mrw = bytes(0, 'M'.code, 'R'.code, 'M'.code) + TiffBuilder.be32(body.size.toLong()) + body
        val info = HeaderParser.parse(mrw)
        assertEquals("DYNAX 5D", info.meta.model)
        assertEquals(200, info.meta.iso)
    }

    @Test fun extensionListIsOneSourceOfTruth() {
        for (ext in listOf("crw", "mrw", "kdc", "dcr", "raw", "rwl", "gpr", "fff", "mos", "mef", "mdc", "srf", "sr2", "erf", "x3f", "iiq", "3fr")) {
            assertTrue(ext, Pairing.isMedia("A.$ext") && ShotFormat.fromFilename("A.${ext.uppercase()}").isRAW)
        }
        for (ext in listOf("avi", "mxf", "mts", "mp4")) assertEquals(ShotFormat.VIDEO, ShotFormat.fromFilename("C0001.$ext"))
        assertEquals(ShotFormat.TIFF, ShotFormat.fromFilename("scan.TIF"))
        assertEquals(Pairing.mediaExtensions, ShotFormat.byExtension.keys)
        assertFalse(Pairing.isMedia("NOTES.TXT"))
    }

    @Test fun tiffPairsWithRawAndUnknownRawsAreListed() {
        val items = listOf(
            CardItem(1, "CF0001.TIF", "100", 10, 1, false), CardItem(2, "CF0001.IIQ", "100", 10, 1, false),
            CardItem(3, "SDIM0001.X3F", "100", 10, 2, false),
        )
        val shots = Pairing.pair(items)
        assertEquals(2, shots.size)
        assertEquals("CF0001.TIF", shots[0].primary.name)
        assertEquals("CF0001.IIQ", shots[0].raw?.name)
        assertEquals("SDIM0001.X3F", shots[1].primary.name)
    }

    @Test fun folderScanFindsSonyAndAVCHDClips() {
        val root = tmp.root
        File(root, "DCIM/100MSDCF").apply { mkdirs() }.let { File(it, "DSC00001.ARW").writeBytes(ByteArray(10)) }
        File(root, "PRIVATE/M4ROOT/CLIP").apply { mkdirs() }.let { File(it, "C0001.MP4").writeBytes(ByteArray(10)) }
        File(root, "PRIVATE/AVCHD/BDMV/STREAM").apply { mkdirs() }.let { File(it, "00000.MTS").writeBytes(ByteArray(10)) }
        File(root, "PRIVATE/M4ROOT/THMBNL").apply { mkdirs() }.let { File(it, "C0001T01.JPG").writeBytes(ByteArray(10)) }
        val names = FolderScanner.scan(root).map { it.primary.name }.toSet()
        assertEquals(setOf("DSC00001.ARW", "C0001.MP4", "00000.MTS"), names)
        assertNotNull(FolderScanner.roots(root).firstOrNull { it.name == "DCIM" })
    }
}
