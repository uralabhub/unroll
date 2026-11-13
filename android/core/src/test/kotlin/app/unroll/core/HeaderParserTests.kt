package app.unroll.core

import app.unroll.core.Fixtures.baseJPEG
import app.unroll.core.Fixtures.fujiJPEG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Port of UnrollTests/HeaderParserTests.swift — same synthetic files, same expectations. */
class HeaderParserTests {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun fujiJPEGRecipeAndExif() {
        val info = HeaderParser.parse(fujiJPEG())
        assertEquals(ShotFormat.JPEG, info.format)
        val m = info.meta
        assertEquals("FUJIFILM", m.make)
        assertEquals("X100VI", m.model)
        assertEquals("Fujifilm X100VI", m.cameraName)
        assertEquals("1/250", m.shutterText)
        assertEquals("ƒ/2", m.apertureText)
        assertEquals(160, m.iso)
        assertEquals("XF23mmF2", m.lens)
        assertEquals(4, m.rating)
        assertEquals(64, m.width)
        assertEquals(10.7767, m.latitude ?: 0.0, 0.001)
        assertEquals(106.7, m.longitude ?: 0.0, 0.001)

        val r = m.recipe!!
        assertEquals("Classic Chrome", r.filmSimulation)
        assertEquals("Weak, Small", r.grainEffect)
        assertEquals("Strong", r.colorChrome)
        assertEquals("Weak", r.colorChromeBlue)
        assertEquals("Cloudy", r.whiteBalance)
        assertEquals(3, r.wbShiftRed)
        assertEquals(-5, r.wbShiftBlue)
        assertEquals("DR200", r.dynamicRange)
        assertEquals(-0.5, r.highlight!!, 0.0)
        assertEquals(1.0, r.shadow!!, 0.0)
        assertEquals("-2", r.color)
        assertEquals("-1", r.sharpness)
        assertEquals("-4", r.noiseReduction)
        assertEquals(-2, r.clarity)
        assertEquals("Cloudy, +3 Red & -5 Blue", r.wbText)
    }

    @Test fun captureDateUsesOffsetTime() {
        val m = HeaderParser.parse(fujiJPEG()).meta
        val d = m.captureDate(ZoneOffset.ofHours(-5))!!
        // 08:15:30 at +07:00 == 01:15:30Z regardless of the phone's zone.
        assertEquals("2026-11-13T01:15:30Z", DateTimeFormatter.ISO_INSTANT.format(d))
    }

    @Test fun monochromeFromSaturation() {
        val mn = TiffBuilder.fujiMakerNote(listOf(E.short(0x1003, 0x501), E.short(0x1402, 0x100), E.short(0x1403, 100)))
        val tiff = TiffBuilder.exifTIFF(listOf(E.ascii(0x010F, "FUJIFILM"), E.ascii(0x0110, "X-T5")), listOf(E.undefined(0x927C, mn)))
        val r = HeaderParser.parse(TiffBuilder.jpeg(baseJPEG(), tiff)).meta.recipe!!
        assertEquals("Acros + R", r.filmSimulation)
        assertTrue(r.isMonochrome)
        assertNull(r.color)
    }

    @Test fun rafWrapperFindsPreviewAndMeta() {
        val jpeg = fujiJPEG(rating = 5)
        val raf = TiffBuilder.raf(jpeg)
        val info = HeaderParser.parse(raf)
        assertEquals(ShotFormat.RAF, info.format)
        assertEquals(ByteRange(160, jpeg.size.toLong()), info.preview)
        assertEquals(5, info.meta.rating)
        assertEquals("Classic Chrome", info.meta.recipe?.filmSimulation)
        // The preview range must point at a decodable JPEG.
        assertTrue(Fixtures.decodes(raf.copyOfRange(160, 160 + jpeg.size)))
    }

    @Test fun tiffRawPicksLargestJPEGPreview() {
        // IFD0 with a small thumbnail, next IFD with a bigger one (ARW/NEF style).
        val small = ByteArray(2000) { 0xAA.toByte() }
        val big = ByteArray(9000) { 0xBB.toByte() }
        val header = bytes(0x49, 0x49, 0x2A, 0x00) + TiffBuilder.le32(8)
        val ifd0Entries = listOf(E.ascii(0x010F, "SONY"), E.ascii(0x0110, "ILCE-7M4"), E.long(0x0201, 0), E.long(0x0202, small.size))
        val ifd1Off = 8 + TiffBuilder.size(ifd0Entries)
        val ifd1Entries = listOf(E.long(0x0201, 0), E.long(0x0202, big.size))
        val smallOff = ifd1Off + TiffBuilder.size(ifd1Entries)
        val bigOff = smallOff + small.size
        val ifd0 = TiffBuilder.ifd(listOf(E.ascii(0x010F, "SONY"), E.ascii(0x0110, "ILCE-7M4"), E.long(0x0201, smallOff), E.long(0x0202, small.size)), 8, ifd1Off)
        val ifd1 = TiffBuilder.ifd(listOf(E.long(0x0201, bigOff), E.long(0x0202, big.size)), ifd1Off)
        val info = HeaderParser.parse(header + ifd0 + ifd1 + small + big)
        assertEquals(ShotFormat.TIFF_RAW, info.format)
        assertEquals("ILCE-7M4", info.meta.model)
        assertEquals(ByteRange(bigOff.toLong(), big.size.toLong()), info.preview)
    }

    @Test fun xmpRating() {
        val xmp = "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta><rdf:Description xmp:Rating=\"3\"/></x:xmpmeta>".toByteArray()
        val len = xmp.size + 2
        val seg = bytes(0xFF, 0xE1, len shr 8, len and 0xFF) + xmp
        val tiff = TiffBuilder.exifTIFF(listOf(E.ascii(0x010F, "NIKON CORPORATION"), E.ascii(0x0110, "NIKON Z f")), emptyList())
        val m = HeaderParser.parse(TiffBuilder.jpeg(baseJPEG(), tiff, listOf(seg))).meta
        assertEquals(3, m.rating)
        assertEquals("NIKON Z f", m.cameraName)
    }

    @Test fun truncatedHeaderDoesNotCrash() {
        val d = fujiJPEG()
        for (n in 0 until minOf(d.size, 900) step 7) HeaderParser.parse(d.copyOfRange(0, n))
        // And every prefix of every container format, byte by byte near the start.
        for (file in listOf(TiffBuilder.raf(d), d)) for (n in 0 until minOf(file.size, 400)) HeaderParser.parse(file.copyOfRange(0, n))
    }

    @Test fun folderScanPairsRAWAndLock() {
        val dir = File(tmp.root, "DCIM/100TEST").apply { mkdirs() }
        val jpeg = fujiJPEG()
        File(dir, "DSCF0001.JPG").writeBytes(jpeg)
        File(dir, "DSCF0001.RAF").writeBytes(TiffBuilder.raf(jpeg))
        File(dir, "DSCF0002.JPG").apply { writeBytes(jpeg); setWritable(false) }
        val shots = FolderScanner.scan(tmp.root)
        assertEquals(2, shots.size)
        val one = shots.first { it.primary.name == "DSCF0001.JPG" }
        assertEquals("DSCF0001.RAF", one.raw?.name)
        val two = shots.first { it.primary.name == "DSCF0002.JPG" }
        assertTrue(two.locked)
        assertFalse(one.locked)
    }

    @Test fun frameGeometryProducesLargerCanvas() {
        val meta = HeaderParser.parse(fujiJPEG()).meta
        for (style in FrameStyle.entries) {
            val g = FrameGeometry.of(300, 200, FrameOptions(style = style), meta.recipe?.rows?.size)
            assertTrue("$style", g.canvasW * g.canvasH > 300.0 * 200)
            // The photo sits fully inside the canvas.
            assertTrue("$style", g.photoX >= 0 && g.photoY >= 0 && g.photoX + 300 <= g.canvasW + 0.001 && g.photoY + 200 <= g.canvasH + 0.001)
        }
        val story = FrameGeometry.of(300, 200, FrameOptions(aspect = FrameAspect.STORY), meta.recipe?.rows?.size)
        assertEquals(9.0 / 16.0, story.canvasW / story.canvasH, 0.01)
    }

    @Test fun clockSyncRoundTrip() {
        val now = 1_800_000_000L
        val qr = ClockSync.matrix(now, 600)
        // Put the code on a bigger canvas, like a photo of the screen.
        val w = 1200; val h = 900
        val px = IntArray(w * h) { 0xFF404040.toInt() }
        for (y in 130 until 770) for (x in 280 until 920) px[y * w + x] = -1
        val scale = 600.0 / qr.width
        for (y in 0 until 600) for (x in 0 until 600) {
            if (qr.get((x / scale).toInt(), (y / scale).toInt())) px[(150 + y) * w + 300 + x] = 0xFF000000.toInt()
        }
        assertEquals(now, ClockSync.readTimestamp(px, w, h))
    }

    @Test fun clockSyncIgnoresForeignCodes() {
        assertNull(ClockSync.readTimestamp(IntArray(100 * 100) { -1 }, 100, 100))
        assertEquals("in sync", ClockSync.describe(1.2))
        assertEquals("1m 30s fast", ClockSync.describe(90.0))
        assertEquals("12s slow", ClockSync.describe(-12.0))
        assertNotNull(ClockSync.payload(1))
    }
}
