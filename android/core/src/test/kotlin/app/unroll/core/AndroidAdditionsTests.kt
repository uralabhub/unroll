package app.unroll.core

import app.unroll.core.Fixtures.baseJPEG
import app.unroll.core.Fixtures.fujiJPEG
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Rules added on the Android branch: D-Range Priority, GPS injection, trail lookup, demo roll. */
class AndroidAdditionsTests {

    private fun recipe(vararg extra: E): FilmRecipe = HeaderParser.parse(fujiJPEG(extraNote = extra.toList())).meta.recipe!!

    // Fuji D-Range Priority (ExifTool FujiFilm 0x1443–0x1445)

    @Test fun drpAutoShowsAutoWhateverTheLevel() {
        for (level in 1..3) {
            val r = recipe(E.short(0x1443, 0), E.short(0x1444, level))
            assertEquals("level $level", "Auto", r.dRangePriority)
        }
    }

    @Test fun drpFixedShowsItsLevel() {
        assertEquals("Weak", recipe(E.short(0x1443, 1), E.short(0x1445, 1)).dRangePriority)
        assertEquals("Strong", recipe(E.short(0x1443, 1), E.short(0x1445, 2)).dRangePriority)
    }

    @Test fun drpOtherValuesMeanOff() {
        assertNull(recipe().dRangePriority)                                           // tags absent
        assertNull(recipe(E.short(0x1443, 0), E.short(0x1444, 0)).dRangePriority)     // Auto, no level
        assertNull(recipe(E.short(0x1443, 0), E.short(0x1445, 2)).dRangePriority)     // Auto reads 0x1444 only
        assertNull(recipe(E.short(0x1443, 1), E.short(0x1445, 3)).dRangePriority)     // Fixed has no "Plus"
        assertNull(recipe(E.short(0x1443, 1), E.short(0x1444, 2)).dRangePriority)     // Fixed reads 0x1445 only
        assertNull(recipe(E.short(0x1443, 2), E.short(0x1444, 1), E.short(0x1445, 1)).dRangePriority)
        assertNull(recipe(E.short(0x1444, 2), E.short(0x1445, 2)).dRangePriority)     // no mode tag
    }

    @Test fun drpRowReplacesDynamicRangeHighlightAndShadow() {
        val off = recipe()
        val offLabels = off.rows.map { it.first }
        assertTrue(offLabels.containsAll(listOf("Dynamic Range", "Highlight", "Shadow")))
        assertEquals("Classic Chrome · DR200 · H-0.5 S+1", off.shortLine)

        val on = recipe(E.short(0x1443, 1), E.short(0x1445, 2))
        val labels = on.rows.map { it.first }
        assertFalse(labels.contains("Dynamic Range"))
        assertFalse(labels.contains("Highlight"))
        assertFalse(labels.contains("Shadow"))
        // Same place in the list the DR row had: right after White Balance.
        assertEquals(labels.indexOf("White Balance") + 1, labels.indexOf("D-Range Priority"))
        assertEquals("Strong", on.rows.first { it.first == "D-Range Priority" }.second)
        assertEquals(off.rows.size - 2, on.rows.size)
        assertEquals("Classic Chrome · DRP Strong", on.shortLine)
        assertEquals("Classic Chrome · DRP Auto", recipe(E.short(0x1443, 0), E.short(0x1444, 3)).shortLine)
        // Different signatures, so the Recipe Book keeps them apart.
        assertTrue(on.signature != off.signature)
    }

    // GPS injection

    @Test fun gpsInjectionKeepsEveryExistingByte() {
        val src = fujiJPEG(gps = false)
        assertNull(HeaderParser.parse(src).meta.latitude)
        val out = GpsInjector.inject(src, 10.7769, -106.7009, altitude = 12.0)!!
        val before = HeaderParser.parse(src).meta
        val after = HeaderParser.parse(out).meta
        assertEquals(10.7769, after.latitude!!, 1e-6)
        assertEquals(-106.7009, after.longitude!!, 1e-6)
        // Everything else reads back the same: recipe, rating, exposure.
        assertEquals(before.copy(latitude = after.latitude, longitude = after.longitude), after)
        assertTrue(Fixtures.decodes(out))

        // Byte-level: only the APP1 length (2 bytes) and the IFD0 pointer (4 bytes) changed before the
        // appended block, and everything after the APP1 segment is identical.
        val app1Len = ((src[4].toInt() and 0xFF) shl 8) or (src[5].toInt() and 0xFF)
        val segEnd = 4 + app1Len
        val changed = (0 until segEnd).filter { src[it] != out[it] }
        assertTrue("changed $changed", changed.all { it in 4..5 || it in 16..19 })
        val added = out.size - src.size
        assertArrayEquals(src.copyOfRange(segEnd, src.size), out.copyOfRange(segEnd + added, out.size))
    }

    @Test fun gpsInjectionLeavesFilesWithGPSOrWithoutExifAlone() {
        assertNull(GpsInjector.inject(fujiJPEG(gps = true), 1.0, 1.0))
        assertNull(GpsInjector.inject(baseJPEG(), 1.0, 1.0))
        assertNull(GpsInjector.inject(ByteArray(10), 1.0, 1.0))
    }

    @Test fun gpsInjectionWorksOnBigEndianExif() {
        // Hand-made Motorola-order EXIF: IFD0 {Make}, no GPS.
        val tiff = bytes(0x4D, 0x4D, 0x00, 0x2A, 0, 0, 0, 8, 0, 1, 0x01, 0x0F, 0, 2, 0, 0, 0, 4) + "SONY".toByteArray() + bytes(0, 0, 0, 0)
        val jpeg = TiffBuilder.jpeg(baseJPEG(), tiff)
        val out = GpsInjector.inject(jpeg, -33.8667, 151.2)!!
        val m = HeaderParser.parse(out).meta
        assertEquals("SONY", m.make)
        assertEquals(-33.8667, m.latitude!!, 1e-6)
        assertEquals(151.2, m.longitude!!, 1e-6)
    }

    @Test fun gpsInjectionBumpsTheMPFFirstImageSize() {
        // JPEG with an MPF APP2 index: entry 0 = this image, entry 1 = a preview after EOI.
        val base = fujiJPEG(gps = false)
        val preview = baseJPEG(w = 32, h = 24)
        fun mpf(firstSize: Int, previewOff: Int): ByteArray {
            val be = { v: Long -> TiffBuilder.be32(v) }
            val be16 = { v: Int -> byteArrayOf((v shr 8).toByte(), v.toByte()) }
            val hdr = "MM".toByteArray() + be16(42) + be(8)
            val ifd = be16(1) + be16(0xB002) + be16(7) + be(32) + be(26) + be(0)
            val entries = be(0x030000) + be(firstSize.toLong()) + be(0) + be16(0) + be16(0) +
                be(0x010001) + be(preview.size.toLong()) + be(previewOff.toLong()) + be16(0) + be16(0)
            val body = "MPF\u0000".toByteArray() + hdr + ifd + entries
            val len = body.size + 2
            return byteArrayOf(0xFF.toByte(), 0xE2.toByte(), (len shr 8).toByte(), len.toByte()) + body
        }
        // Insert APP2 right after APP1.
        val app1End = 4 + (((base[4].toInt() and 0xFF) shl 8) or (base[5].toInt() and 0xFF))
        val seg0 = mpf(0, 0)
        val firstSize = base.size + seg0.size
        val mpHeaderAt = app1End + 8
        val seg = mpf(firstSize, firstSize - mpHeaderAt)
        val file = base.copyOfRange(0, app1End) + seg + base.copyOfRange(app1End, base.size) + preview
        val before = HeaderParser.parse(file).preview!!
        assertTrue(Fixtures.decodes(file.copyOfRange(before.offset.toInt(), (before.offset + before.length).toInt())))

        val out = GpsInjector.inject(file, 1.0, 2.0)!!
        val after = HeaderParser.parse(out).preview!!
        val added = out.size - file.size
        assertEquals(before.offset + added, after.offset)
        assertTrue(Fixtures.decodes(out.copyOfRange(after.offset.toInt(), (after.offset + after.length).toInt())))
        val mpBase = mpHeaderAt + added
        val t = TiffStructure.at(ByteView(out), mpBase)!!
        val mp = t.entries(t.firstIFDOffset!!).tag(0xB002)!!
        assertEquals((firstSize + added).toLong(), ByteView(out).u32(mp.valuePos + 4, false))
    }

    // Trail lookup (Photo Walk)

    @Test fun trailInterpolatesAndRespectsGaps() {
        val w = PhotoWalk("w", 0, 600_000, listOf(TrailPoint(10.0, 100.0, 0, 5.0), TrailPoint(11.0, 101.0, 600_000, 10.0)))
        val mid = Geo.location(listOf(w), 300_000, now = 600_000)!!
        assertEquals(10.5, mid.lat, 1e-9)
        assertEquals(100.5, mid.lon, 1e-9)
        assertEquals(10.0, mid.acc, 0.0)
        assertNotNull(Geo.location(listOf(w), 600_000 + 14 * 60_000, now = 600_000))
        assertNull(Geo.location(listOf(w), 600_000 + 16 * 60_000, now = 600_000))
        assertEquals(w, Geo.walkCovering(listOf(w), listOf(100, 200), now = 600_000))
        assertEquals(111_000.0, Geo.distance(10.0, 100.0, 11.0, 100.0), 500.0)
    }

    // Demo roll

    @Test fun demoRollFilesParseLikeCameraFiles() {
        val zone = ZoneId.of("Asia/Ho_Chi_Minh")
        val now = Instant.parse("2026-11-13T05:00:00Z")
        val walk = DemoRoll.walk(now, zone)
        var keepers = 0
        var drp = 0
        for (i in 0 until DemoRoll.COUNT) {
            val s = DemoRoll.spec(i, now, zone)
            val m = HeaderParser.parse(TiffBuilder.jpeg(baseJPEG(), DemoRoll.exif(s, zone))).meta
            assertEquals(s.date, m.captureDate(ZoneOffset.UTC))
            assertNotNull("shot $i has a recipe", m.recipe)
            assertEquals(if (s.isXT5) "X-T5" else "X100VI", m.model)
            if ((m.rating ?: 0) > 0 || s.locked) keepers++
            if (m.recipe?.dRangePriority != null) drp++
            // Every demo shot falls inside the demo walk, so it gets a location.
            assertNotNull(Geo.location(listOf(walk), s.date.toEpochMilli(), now.toEpochMilli()))
        }
        assertTrue(keepers in 10..20)
        assertTrue("the demo shows D-Range Priority", drp > 0)
    }

    @Test fun metaRoundTripsThroughJson() {
        val m = HeaderParser.parse(fujiJPEG(extraNote = listOf(E.short(0x1443, 0), E.short(0x1444, 1)))).meta
        val json = Json.encodeToString(ShotMeta.serializer(), m)
        assertEquals(m, Json.decodeFromString(ShotMeta.serializer(), json))
    }
}
