import XCTest
import UIKit
@testable import Unroll

final class HeaderParserTests: XCTestCase {

    /// A tiny real JPEG to wrap with synthetic EXIF.
    private func baseJPEG() -> Data {
        let f = UIGraphicsImageRendererFormat(); f.scale = 1
        let img = UIGraphicsImageRenderer(size: CGSize(width: 64, height: 48), format: f).image { c in
            UIColor.orange.setFill(); c.fill(CGRect(x: 0, y: 0, width: 64, height: 48))
        }
        return img.jpegData(compressionQuality: 0.8)!
    }

    private func fujiJPEG(rating: Int = 4) -> Data {
        let mn = TIFFBuilder.fujiMakerNote([
            .undefined(0x0000, Array("0130".utf8)),
            .short(0x1001, 0x82),          // sharpness -1
            .short(0x1002, 0x200),         // cloudy
            .short(0x1003, 0x400),         // color -2
            .slong(0x100A, [60, -100]),    // R+3 B-5
            .short(0x100E, 0x2E0),         // NR -4
            .slong(0x100F, [-2000]),       // clarity -2
            .slong(0x1040, [-16]),         // shadow +1
            .slong(0x1041, [8]),           // highlight -0.5
            .long(0x1047, 32), .long(0x104C, 16),   // grain weak small
            .long(0x1048, 64), .long(0x104E, 32),   // CCE strong, CCB weak
            .short(0x1401, 0x600),         // Classic Chrome
            .short(0x1402, 0x100), .short(0x1403, 200),
            .long(0x1431, rating),
        ])
        let tiff = TIFFBuilder.exifTIFF(
            ifd0: [.ascii(0x010F, "FUJIFILM"), .ascii(0x0110, "X100VI"), .short(0x0112, 1)],
            exif: [
                .rational(0x829A, [(1, 250)]), .rational(0x829D, [(20, 10)]), .short(0x8827, 160),
                .ascii(0x9003, "2026:11:13 08:15:30"), .ascii(0x9011, "+07:00"),
                .rational(0x920A, [(23, 1)]), .ascii(0xA434, "XF23mmF2"),
                .undefined(0x927C, mn),
            ],
            gps: [.ascii(0x0001, "N"), .rational(0x0002, [(10, 1), (46, 1), (3600, 100)]),
                  .ascii(0x0003, "E"), .rational(0x0004, [(106, 1), (42, 1), (0, 1)])]
        )
        return TIFFBuilder.jpeg(baseJPEG(), withExif: tiff)
    }

    func testFujiJPEGRecipeAndExif() throws {
        let info = HeaderParser.parse(fujiJPEG())
        XCTAssertEqual(info.format, .jpeg)
        let m = info.meta
        XCTAssertEqual(m.make, "FUJIFILM")
        XCTAssertEqual(m.model, "X100VI")
        XCTAssertEqual(m.cameraName, "Fujifilm X100VI")
        XCTAssertEqual(m.shutterText, "1/250")
        XCTAssertEqual(m.apertureText, "ƒ/2")
        XCTAssertEqual(m.iso, 160)
        XCTAssertEqual(m.lens, "XF23mmF2")
        XCTAssertEqual(m.rating, 4)
        XCTAssertEqual(m.width, 64)
        XCTAssertEqual(m.latitude ?? 0, 10.7767, accuracy: 0.001)
        XCTAssertEqual(m.longitude ?? 0, 106.7, accuracy: 0.001)

        let r = try XCTUnwrap(m.recipe)
        XCTAssertEqual(r.filmSimulation, "Classic Chrome")
        XCTAssertEqual(r.grainEffect, "Weak, Small")
        XCTAssertEqual(r.colorChrome, "Strong")
        XCTAssertEqual(r.colorChromeBlue, "Weak")
        XCTAssertEqual(r.whiteBalance, "Cloudy")
        XCTAssertEqual(r.wbShiftRed, 3)
        XCTAssertEqual(r.wbShiftBlue, -5)
        XCTAssertEqual(r.dynamicRange, "DR200")
        XCTAssertEqual(r.highlight, -0.5)
        XCTAssertEqual(r.shadow, 1)
        XCTAssertEqual(r.color, "-2")
        XCTAssertEqual(r.sharpness, "-1")
        XCTAssertEqual(r.noiseReduction, "-4")
        XCTAssertEqual(r.clarity, -2)
        XCTAssertEqual(r.wbText, "Cloudy, +3 Red & -5 Blue")
    }

    func testCaptureDateUsesOffsetTime() throws {
        let m = HeaderParser.parse(fujiJPEG()).meta
        let d = try XCTUnwrap(m.captureDate(fallbackZone: TimeZone(secondsFromGMT: -5 * 3600)!))
        // 08:15:30 at +07:00 == 01:15:30Z regardless of the phone's zone.
        let f = ISO8601DateFormatter()
        XCTAssertEqual(f.string(from: d), "2026-11-13T01:15:30Z")
    }

    func testMonochromeFromSaturation() throws {
        let mn = TIFFBuilder.fujiMakerNote([.short(0x1003, 0x501), .short(0x1402, 0x100), .short(0x1403, 100)])
        let tiff = TIFFBuilder.exifTIFF(ifd0: [.ascii(0x010F, "FUJIFILM"), .ascii(0x0110, "X-T5")], exif: [.undefined(0x927C, mn)])
        let r = try XCTUnwrap(HeaderParser.parse(TIFFBuilder.jpeg(baseJPEG(), withExif: tiff)).meta.recipe)
        XCTAssertEqual(r.filmSimulation, "Acros + R")
        XCTAssertTrue(r.isMonochrome)
        XCTAssertNil(r.color)
    }

    func testRAFWrapperFindsPreviewAndMeta() throws {
        let jpeg = fujiJPEG(rating: 5)
        let raf = TIFFBuilder.raf(embedding: jpeg)
        let info = HeaderParser.parse(raf)
        XCTAssertEqual(info.format, .raf)
        XCTAssertEqual(info.preview, ByteRange(offset: 160, length: jpeg.count))
        XCTAssertEqual(info.meta.rating, 5)
        XCTAssertEqual(info.meta.recipe?.filmSimulation, "Classic Chrome")
        // The preview range must point at a decodable JPEG.
        let slice = raf.subdata(in: 160..<(160 + jpeg.count))
        XCTAssertNotNil(UIImage(data: slice))
    }

    func testTIFFRawPicksLargestJPEGPreview() {
        // IFD0 with a small thumbnail, next IFD with a bigger one (ARW/NEF style).
        let small = [UInt8](repeating: 0xAA, count: 2000)
        let big = [UInt8](repeating: 0xBB, count: 9000)
        let header: [UInt8] = [0x49, 0x49, 0x2A, 0x00] + TIFFBuilder.le32(8)
        let ifd0Entries: [TIFFBuilder.Entry] = [.ascii(0x010F, "SONY"), .ascii(0x0110, "ILCE-7M4"), .long(0x0201, 0), .long(0x0202, small.count)]
        let ifd0Size = TIFFBuilder.size(of: ifd0Entries)
        let ifd1Off = 8 + ifd0Size
        let ifd1Entries: [TIFFBuilder.Entry] = [.long(0x0201, 0), .long(0x0202, big.count)]
        let ifd1Size = TIFFBuilder.size(of: ifd1Entries)
        let smallOff = ifd1Off + ifd1Size
        let bigOff = smallOff + small.count
        let ifd0 = TIFFBuilder.ifd([.ascii(0x010F, "SONY"), .ascii(0x0110, "ILCE-7M4"), .long(0x0201, smallOff), .long(0x0202, small.count)], at: 8, next: ifd1Off)
        let ifd1 = TIFFBuilder.ifd([.long(0x0201, bigOff), .long(0x0202, big.count)], at: ifd1Off)
        let file = Data(header + ifd0 + ifd1 + small + big)
        let info = HeaderParser.parse(file)
        XCTAssertEqual(info.format, .tiffRaw)
        XCTAssertEqual(info.meta.model, "ILCE-7M4")
        XCTAssertEqual(info.preview, ByteRange(offset: bigOff, length: big.count))
    }

    func testXMPRating() {
        let xmp = Array("http://ns.adobe.com/xap/1.0/\0<x:xmpmeta><rdf:Description xmp:Rating=\"3\"/></x:xmpmeta>".utf8)
        let len = UInt16(xmp.count + 2)
        let seg: [UInt8] = [0xFF, 0xE1, UInt8(len >> 8), UInt8(len & 0xFF)] + xmp
        let tiff = TIFFBuilder.exifTIFF(ifd0: [.ascii(0x010F, "NIKON CORPORATION"), .ascii(0x0110, "NIKON Z f")], exif: [])
        let jpeg = TIFFBuilder.jpeg(baseJPEG(), withExif: tiff, extraSegments: [seg])
        let m = HeaderParser.parse(jpeg).meta
        XCTAssertEqual(m.rating, 3)
        XCTAssertEqual(m.cameraName, "NIKON Z f")
    }

    func testTruncatedHeaderDoesNotCrash() {
        let d = fujiJPEG()
        for n in stride(from: 0, to: min(d.count, 900), by: 7) {
            _ = HeaderParser.parse(d.prefix(n))
        }
    }

    func testFolderScanPairsRAWAndLock() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("DCIM/100TEST")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let jpeg = fujiJPEG()
        try jpeg.write(to: dir.appendingPathComponent("DSCF0001.JPG"))
        try TIFFBuilder.raf(embedding: jpeg).write(to: dir.appendingPathComponent("DSCF0001.RAF"))
        try jpeg.write(to: dir.appendingPathComponent("DSCF0002.JPG"))
        try FileManager.default.setAttributes([.posixPermissions: 0o444], ofItemAtPath: dir.appendingPathComponent("DSCF0002.JPG").path)
        let shots = await FolderSource.scan(dir.deletingLastPathComponent().deletingLastPathComponent())
        XCTAssertEqual(shots.count, 2)
        let one = try XCTUnwrap(shots.first { $0.primary.name == "DSCF0001.JPG" })
        XCTAssertEqual(one.raw?.name, "DSCF0001.RAF")
        let two = try XCTUnwrap(shots.first { $0.primary.name == "DSCF0002.JPG" })
        XCTAssertTrue(two.isLocked)
        XCTAssertFalse(one.isLocked)
    }

    func testFrameRendererProducesLargerCanvas() {
        let img = UIGraphicsImageRenderer(size: CGSize(width: 300, height: 200), format: { let f = UIGraphicsImageRendererFormat(); f.scale = 1; return f }()).image { _ in }
        let meta = HeaderParser.parse(fujiJPEG()).meta
        for style in FrameStyle.allCases {
            var o = FrameOptions(); o.style = style
            let out = FrameRenderer.render(img, meta: meta, options: o)
            XCTAssertGreaterThan(out.size.width * out.size.height, 300 * 200, "\(style)")
        }
        var o = FrameOptions(); o.aspect = .story
        let story = FrameRenderer.render(img, meta: meta, options: o)
        XCTAssertEqual(story.size.width / story.size.height, 9.0 / 16.0, accuracy: 0.01)
    }

    func testClockSyncRoundTrip() async throws {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let qr = try XCTUnwrap(ClockSync.qr(for: now, size: 600))
        // Put the code on a bigger canvas, like a photo of the screen.
        let photo = UIGraphicsImageRenderer(size: CGSize(width: 1200, height: 900), format: { let f = UIGraphicsImageRendererFormat(); f.scale = 1; return f }()).image { c in
            UIColor.darkGray.setFill(); c.fill(CGRect(x: 0, y: 0, width: 1200, height: 900))
            UIColor.white.setFill(); c.fill(CGRect(x: 280, y: 130, width: 640, height: 640))
            qr.draw(in: CGRect(x: 300, y: 150, width: 600, height: 600))
        }
        let read = await ClockSync.readTimestamp(in: photo)
        XCTAssertEqual(read, now)
    }
}
