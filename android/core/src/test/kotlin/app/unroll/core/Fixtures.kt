package app.unroll.core

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

typealias E = TiffBuilder.Entry

/** Shared builders for the ported XCTest cases. */
object Fixtures {
    /** A tiny real JPEG to wrap with synthetic EXIF (UIGraphicsImageRenderer on iOS, ImageIO here). */
    fun baseJPEG(color: Color = Color.ORANGE, w: Int = 64, h: Int = 48, noise: Boolean = false): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = color
        g.fillRect(0, 0, w, h)
        g.dispose()
        // Noise makes the encoder emit a realistically sized file (a flat 64×48 frame is ~600 bytes).
        if (noise) { val r = java.util.Random(7); for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, r.nextInt()) }
        val out = ByteArrayOutputStream()
        check(ImageIO.write(img, "jpg", out))
        return out.toByteArray()
    }

    fun decodes(jpeg: ByteArray): Boolean = ImageIO.read(ByteArrayInputStream(jpeg)) != null

    fun fujiNote(rating: Int = 4): List<E> = listOf(
        E.undefined(0x0000, "0130".toByteArray()),
        E.short(0x1001, 0x82),          // sharpness -1
        E.short(0x1002, 0x200),         // cloudy
        E.short(0x1003, 0x400),         // color -2
        E.slong(0x100A, 60, -100),      // R+3 B-5
        E.short(0x100E, 0x2E0),         // NR -4
        E.slong(0x100F, -2000),         // clarity -2
        E.slong(0x1040, -16),           // shadow +1
        E.slong(0x1041, 8),             // highlight -0.5
        E.long(0x1047, 32), E.long(0x104C, 16),   // grain weak small
        E.long(0x1048, 64), E.long(0x104E, 32),   // CCE strong, CCB weak
        E.short(0x1401, 0x600),         // Classic Chrome
        E.short(0x1402, 0x100), E.short(0x1403, 200),
        E.long(0x1431, rating),
    )

    fun fujiJPEG(rating: Int = 4, gps: Boolean = true, extraNote: List<E> = emptyList()): ByteArray {
        val tiff = TiffBuilder.exifTIFF(
            ifd0 = listOf(E.ascii(0x010F, "FUJIFILM"), E.ascii(0x0110, "X100VI"), E.short(0x0112, 1)),
            exif = listOf(
                E.rational(0x829A, 1 to 250), E.rational(0x829D, 20 to 10), E.short(0x8827, 160),
                E.ascii(0x9003, "2026:11:13 08:15:30"), E.ascii(0x9011, "+07:00"),
                E.rational(0x920A, 23 to 1), E.ascii(0xA434, "XF23mmF2"),
                E.undefined(0x927C, TiffBuilder.fujiMakerNote(fujiNote(rating) + extraNote)),
            ),
            gps = if (gps) listOf(
                E.ascii(0x0001, "N"), E.rational(0x0002, 10 to 1, 46 to 1, 3600 to 100),
                E.ascii(0x0003, "E"), E.rational(0x0004, 106 to 1, 42 to 1, 0 to 1),
            ) else emptyList(),
        )
        return TiffBuilder.jpeg(baseJPEG(), tiff)
    }

    fun be32(v: Int): ByteArray = TiffBuilder.be32(v.toLong())
    fun le32(v: Int): ByteArray = TiffBuilder.le32(v.toLong())
    fun ascii(s: String): ByteArray = s.toByteArray(Charsets.ISO_8859_1)
    fun box(type: String, payload: ByteArray): ByteArray = be32(8 + payload.size) + ascii(type) + payload
    fun fullBox(type: String, version: Int, payload: ByteArray, flags: Int = 0): ByteArray =
        box(type, byteArrayOf(version.toByte(), (flags shr 16).toByte(), (flags shr 8).toByte(), flags.toByte()) + payload)
}
