import XCTest
import UIKit
import ImageIO
import UniformTypeIdentifiers
@testable import Unroll

/// Files built byte-for-byte from the published layouts (ExifTool tag tables, lclevy/canon_cr3,
/// Apple ImageCaptureCore docs). No real camera files involved: each test pins one rule from a spec.
final class SpecConformanceTests: XCTestCase {
    typealias E = TIFFBuilder.Entry
    private func be32(_ v: Int) -> [UInt8] { [UInt8(v >> 24 & 0xFF), UInt8(v >> 16 & 0xFF), UInt8(v >> 8 & 0xFF), UInt8(v & 0xFF)] }
    private func byte(_ tag: UInt16, _ b: UInt8) -> E { E(tag: tag, type: 1, count: 1, payload: [b]) }

    private func baseJPEG() -> Data {
        let f = UIGraphicsImageRendererFormat(); f.scale = 1
        return UIGraphicsImageRenderer(size: CGSize(width: 64, height: 48), format: f).image { c in
            UIColor.gray.setFill(); c.fill(CGRect(x: 0, y: 0, width: 64, height: 48))
        }.jpegData(compressionQuality: 0.8)!
    }

    private func fujiRecipe(make: String = "FUJIFILM", _ note: [E]) -> FilmRecipe? {
        let tiff = TIFFBuilder.exifTIFF(ifd0: [.ascii(0x010F, make), .ascii(0x0110, "X-T5")],
                                        exif: [.undefined(0x927C, TIFFBuilder.fujiMakerNote(note))])
        return HeaderParser.parse(TIFFBuilder.jpeg(baseJPEG(), withExif: tiff)).meta.recipe
    }

    // MARK: Fujifilm (ExifTool FujiFilm table)

    func testFujiMonoWarmCoolIsSignedByte() throws {
        // 0x1049 / 0x104B are int8s: 0xFE = -2, 0x01 = +1.
        let r = try XCTUnwrap(fujiRecipe([.short(0x1003, 0x300), .short(0x1401, 0), byte(0x1049, 0xFE), byte(0x104B, 0x01)]))
        XCTAssertEqual(r.filmSimulation, "Monochrome")
        XCTAssertEqual(r.monoWarmCool, -2)
        XCTAssertEqual(r.monoMagentaGreen, 1)
    }

    func testFujiGrainCombinedValues() throws {
        // 0x1047: 16 = Weak/Small, 48 = Strong/Small; an explicit 0x104C size wins.
        XCTAssertEqual(fujiRecipe([.short(0x1401, 0x800), .long(0x1047, 16)])?.grainEffect, "Weak, Small")
        XCTAssertEqual(fujiRecipe([.short(0x1401, 0x800), .long(0x1047, 48)])?.grainEffect, "Strong, Small")
        XCTAssertEqual(fujiRecipe([.short(0x1401, 0x800), .long(0x1047, 64), .long(0x104C, 32)])?.grainEffect, "Strong, Large")
    }

    func testFujiWBShiftScalesOnlyMultiplesOf20() throws {
        XCTAssertEqual(fujiRecipe([.short(0x1401, 0), .slong(0x100A, [40, -180])])?.wbShiftRed, 2)
        XCTAssertEqual(fujiRecipe([.short(0x1401, 0), .slong(0x100A, [40, -180])])?.wbShiftBlue, -9)
        let odd = try XCTUnwrap(fujiRecipe([.short(0x1401, 0), .slong(0x100A, [3, -1])]))
        XCTAssertEqual(odd.wbShiftRed, 3)
        XCTAssertEqual(odd.wbShiftBlue, -1)
    }

    func testFujiUnknownOrMissingFilmModeIsNotGuessed() {
        XCTAssertEqual(fujiRecipe([.short(0x1401, 0xC00)])?.filmSimulation, "Film sim 0xC00")
        XCTAssertNil(fujiRecipe([.short(0x1003, 0x000)]), "no FilmMode tag → no recipe, not Provia")
    }

    func testFujiDRangePriority() throws {
        // ExifTool 0x1443 (0 Auto / 1 Fixed), 0x1444 (1 Weak / 2 Strong / 3 Plus), 0x1445 (1 Weak / 2 Strong).
        let base: [E] = [.short(0x1401, 0x600), .short(0x1402, 1), .short(0x1403, 200), .short(0x1041, 16)]
        let fixed = try XCTUnwrap(fujiRecipe(base + [.short(0x1443, 1), .short(0x1445, 2)]))
        XCTAssertEqual(fixed.dRangePriority, "Strong")
        XCTAssertFalse(fixed.rows.contains { $0.0 == "Dynamic Range" || $0.0 == "Highlight" }, "DRP replaces DR and tones")
        XCTAssertEqual(fujiRecipe(base + [.short(0x1443, 1), .short(0x1445, 1)])?.dRangePriority, "Weak")
        XCTAssertEqual(fujiRecipe(base + [.short(0x1443, 0), .short(0x1444, 3)])?.dRangePriority, "Auto")
        let off = try XCTUnwrap(fujiRecipe(base + [.short(0x1443, 0), .short(0x1444, 0)]))
        XCTAssertNil(off.dRangePriority, "undocumented level → off, not guessed")
        XCTAssertEqual(off.dynamicRange, "DR200")
        XCTAssertNil(fujiRecipe(base)?.dRangePriority)
    }

    func testFujiHeaderOnOtherMakeIsIgnored() {
        // ExifTool: the "FUJIFILM" maker note header is also used by some Leica/Minolta/Sharp models.
        XCTAssertNil(fujiRecipe(make: "LEICA", [.short(0x1401, 0x600)]))
    }

    // MARK: TIFF-based RAW

    func testCR2RawDataStripIsNotThePreview() {
        // CR2: IFD0 StripOffsets = JPEG preview (compression 6). IFD3 = raw sensor data, *also*
        // compression 6 (lossless JPEG) and much bigger. Only IFD0's strip may be the preview.
        let header: [UInt8] = [0x49, 0x49, 0x2A, 0x00] + TIFFBuilder.le32(16) + Array("CR".utf8) + [2, 0] + TIFFBuilder.le32(0)
        let preview = [0xFF, 0xD8] + [UInt8](repeating: 0x11, count: 3000)
        let rawData = [UInt8](repeating: 0x22, count: 50_000)
        func ifd0(_ stripAt: Int, next: Int) -> [UInt8] {
            TIFFBuilder.ifd([.ascii(0x010F, "Canon"), .ascii(0x0110, "Canon EOS R"), .short(0x0103, 6),
                             .long(0x0111, stripAt), .long(0x0117, preview.count)], at: 16, next: next)
        }
        let ifd0Size = ifd0(0, next: 0).count
        let ifd3At = 16 + ifd0Size
        let ifd3Size = TIFFBuilder.ifd([.short(0x0103, 6), .long(0x0111, 0), .long(0x0117, 0)], at: 0).count
        let previewAt = ifd3At + ifd3Size
        let rawAt = previewAt + preview.count
        let ifd3 = TIFFBuilder.ifd([.short(0x0103, 6), .long(0x0111, rawAt), .long(0x0117, rawData.count)], at: ifd3At)
        let file = Data(header + ifd0(previewAt, next: ifd3At) + ifd3 + preview + rawData)
        let info = HeaderParser.parse(file)
        XCTAssertEqual(info.meta.model, "Canon EOS R")
        XCTAssertEqual(info.preview, ByteRange(offset: previewAt, length: preview.count))
    }

    func testRW2PreviewAndExifFromJpgFromRaw() throws {
        // RW2: "IIU\0", IFD0 tag 0x002E JpgFromRaw holds a full JPEG; the EXIF is inside it.
        let exif = TIFFBuilder.exifTIFF(ifd0: [.ascii(0x010F, "Panasonic"), .ascii(0x0110, "DC-S5M2")],
                                        exif: [.ascii(0x9003, "2026:11:13 09:00:00"), .short(0x8827, 400)])
        let jpg = [UInt8](TIFFBuilder.jpeg(baseJPEG(), withExif: exif))
        let header: [UInt8] = [0x49, 0x49, 0x55, 0x00] + TIFFBuilder.le32(8)
        let ifd0 = TIFFBuilder.ifd([.ascii(0x010F, "Panasonic"), .ascii(0x0110, "DC-S5M2"), .undefined(0x002E, jpg)], at: 8)
        let file = Data(header + ifd0)
        let jpgAt = try XCTUnwrap(ByteView(file).firstIndex(of: [0xFF, 0xD8]))
        let info = HeaderParser.parse(file)
        XCTAssertEqual(info.preview, ByteRange(offset: jpgAt, length: jpg.count))
        XCTAssertEqual(info.meta.dateTimeOriginal, "2026:11:13 09:00:00")
        XCTAssertEqual(info.meta.iso, 400)
        XCTAssertNil(info.meta.width, "preview pixel size is not the sensor size")
    }

    func testORFPreviewFromOlympusMakerNote() throws {
        // "OLYMPUS\0II\3\0", IFD at +12, offsets from note start. CameraSettings 0x2020 (IFD) →
        // PreviewImageStart 0x0101 / PreviewImageLength 0x0102.
        let jpeg = [0xFF, 0xD8] + [UInt8](repeating: 0x33, count: 4000)
        let mainIFDSize = 2 + 12 + 4
        let subAt = 12 + mainIFDSize
        let subSize = 2 + 2 * 12 + 4
        let previewAt = subAt + subSize
        var note = Array("OLYMPUS\0".utf8) + [0x49, 0x49, 0x03, 0x00]
        note += TIFFBuilder.ifd([E(tag: 0x2020, type: 13, count: 1, payload: TIFFBuilder.le32(UInt32(subAt)))], at: 12)
        note += TIFFBuilder.ifd([.long(0x0101, previewAt), .long(0x0102, jpeg.count)], at: subAt)
        note += jpeg
        var tiff = TIFFBuilder.exifTIFF(ifd0: [.ascii(0x010F, "OM Digital Solutions"), .ascii(0x0110, "OM-1")],
                                        exif: [.undefined(0x927C, note)])
        tiff[2] = 0x52; tiff[3] = 0x4F                          // ORF magic "IIRO"
        let file = Data(tiff)
        let noteAt = try XCTUnwrap(ByteView(file).firstIndex(of: Array("OLYMPUS\0".utf8)))
        let info = HeaderParser.parse(file)
        XCTAssertEqual(info.meta.model, "OM-1")
        XCTAssertEqual(info.preview, ByteRange(offset: noteAt + previewAt, length: jpeg.count))
    }

    func testTIFFRawXMPPacketRating() {
        let xmp = Array("<x:xmpmeta><rdf:Description xmp:Rating=\"2\"/></x:xmpmeta>".utf8)
        let header: [UInt8] = [0x49, 0x49, 0x2A, 0x00] + TIFFBuilder.le32(8)
        let file = Data(header + TIFFBuilder.ifd([.ascii(0x010F, "NIKON CORPORATION"), .undefined(0x02BC, xmp)], at: 8))
        XCTAssertEqual(HeaderParser.parse(file).meta.rating, 2)
    }

    // MARK: Maker notes

    private func nikonLook(version: String, nameOffset: Int) -> String? {
        var pc = Array(version.utf8) + [UInt8](repeating: 0, count: nameOffset - 4)
        pc += Array("FLAT".utf8) + [UInt8](repeating: 0, count: 40)
        let inner: [UInt8] = [0x49, 0x49, 0x2A, 0x00] + TIFFBuilder.le32(8) + TIFFBuilder.ifd([.undefined(0x0023, pc)], at: 8)
        let note = Array("Nikon\0".utf8) + [0x02, 0x10, 0x00, 0x00] + inner
        let tiff = TIFFBuilder.exifTIFF(ifd0: [.ascii(0x010F, "NIKON CORPORATION"), .ascii(0x0110, "NIKON Z 8")],
                                        exif: [.undefined(0x927C, note)])
        return HeaderParser.parse(TIFFBuilder.jpeg(baseJPEG(), withExif: tiff)).meta.look
    }

    func testNikonPictureControlNameOffsetByVersion() {
        XCTAssertEqual(nikonLook(version: "0310", nameOffset: 8), "Picture Control: Flat")   // PictureControl3 (Z)
        XCTAssertEqual(nikonLook(version: "0200", nameOffset: 4), "Picture Control: Flat")   // v1/v2
    }

    func testSonyHeaderlessMakerNote() {
        // ExifTool Sony5: no "SONY DSC" header, IFD at the note start, offsets from the EXIF header.
        let note = TIFFBuilder.ifd([.long(0x2002, 3)], at: 0)
        let tiff = TIFFBuilder.exifTIFF(ifd0: [.ascii(0x010F, "SONY"), .ascii(0x0110, "ILCE-7CM2")], exif: [.undefined(0x927C, note)])
        XCTAssertEqual(HeaderParser.parse(TIFFBuilder.jpeg(baseJPEG(), withExif: tiff)).meta.rating, 3)
    }

    // MARK: CR3 (lclevy/canon_cr3)

    func testCR3ReadsGPSFromCMT4AndRatingFromXMPBox() {
        func box(_ type: String, _ payload: [UInt8]) -> [UInt8] { be32(8 + payload.count) + Array(type.utf8) + payload }
        let tiffHeader: [UInt8] = [0x49, 0x49, 0x2A, 0x00] + TIFFBuilder.le32(8)
        let cmt1 = tiffHeader + TIFFBuilder.ifd([.ascii(0x010F, "Canon"), .ascii(0x0110, "Canon EOS R6m2")], at: 8)
        let cmt4 = tiffHeader + TIFFBuilder.ifd([.ascii(0x0001, "S"), .rational(0x0002, [(33, 1), (52, 1), (0, 1)]),
                                                 .ascii(0x0003, "E"), .rational(0x0004, [(151, 1), (12, 1), (0, 1)])], at: 8)
        let uuid: [UInt8] = [0xBE, 0x7A, 0xCF, 0xCB, 0x97, 0xA9, 0x42, 0xE8, 0x9C, 0x71, 0x99, 0x94, 0x91, 0xE3, 0xAF, 0xAC]
        let xmp = Array("<x:xmpmeta><rdf:Description xmp:Rating=\"4\"/></x:xmpmeta>".utf8)
        let file = box("ftyp", Array("crx ".utf8) + be32(1) + Array("crx isom".utf8))
            + box("moov", box("CMT1", cmt1) + box("CMT4", cmt4))
            + box("uuid", uuid + xmp)
        let m = HeaderParser.parse(Data(file)).meta
        XCTAssertEqual(m.model, "Canon EOS R6m2")
        XCTAssertEqual(m.latitude ?? 0, -33.8667, accuracy: 0.001)
        XCTAssertEqual(m.longitude ?? 0, 151.2, accuracy: 0.001)
        XCTAssertEqual(m.rating, 4)
    }

    // MARK: HEIF

    func testHEIFHeaderMetadataViaImageIO() throws {
        let img = UIGraphicsImageRenderer(size: CGSize(width: 64, height: 48)).image { c in
            UIColor.gray.setFill(); c.fill(CGRect(x: 0, y: 0, width: 64, height: 48))
        }
        let out = NSMutableData()
        guard let cg = img.cgImage,
              let dest = CGImageDestinationCreateWithData(out, UTType.heic.identifier as CFString, 1, nil) else {
            throw XCTSkip("No HEIC encoder in this runtime")
        }
        let props: [CFString: Any] = [
            kCGImagePropertyTIFFDictionary: [kCGImagePropertyTIFFMake: "SONY", kCGImagePropertyTIFFModel: "ILCE-7M4"],
            kCGImagePropertyExifDictionary: [kCGImagePropertyExifDateTimeOriginal: "2026:11:13 10:20:30",
                                             kCGImagePropertyExifISOSpeedRatings: [800]],
        ]
        CGImageDestinationAddImage(dest, cg, props as CFDictionary)
        guard CGImageDestinationFinalize(dest) else { throw XCTSkip("HEIC encode failed in this runtime") }
        let info = HeaderParser.parse((out as Data).prefix(HeaderParser.headerLength))
        XCTAssertEqual(info.format, .heif)
        XCTAssertEqual(info.meta.model, "ILCE-7M4")
        XCTAssertEqual(info.meta.dateTimeOriginal, "2026:11:13 10:20:30")
        XCTAssertEqual(info.meta.iso, 800)
    }

    // MARK: Sources

    func testRicohOrderKeyIsStableAndChronological() {
        XCTAssertEqual(RicohGRSource.orderKey(dir: "100RICOH", file: "R0001234.DNG"), 1_000_001_234)
        let a = RicohGRSource.orderKey(dir: "100RICOH", file: "R0009999.JPG")!
        let b = RicohGRSource.orderKey(dir: "101RICOH", file: "R0000001.JPG")!
        XCTAssertLessThan(a, b)
        XCTAssertNil(RicohGRSource.orderKey(dir: "MISC", file: "notes.txt"))
    }

    func testWriteProtectedCardIsNotAllKeepers() async throws {
        // Every file read-only = card switch / read-only mount, not per-file Protect.
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("DCIM/100TEST")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        for n in ["A001.JPG", "A002.JPG", "A003.JPG"] {
            let u = dir.appendingPathComponent(n)
            try baseJPEG().write(to: u)
            try FileManager.default.setAttributes([.posixPermissions: 0o444], ofItemAtPath: u.path)
        }
        let shots = await FolderSource.scan(dir.deletingLastPathComponent().deletingLastPathComponent())
        XCTAssertEqual(shots.count, 3)
        XCTAssertTrue(shots.allSatisfy { !$0.isLocked })
    }
}
