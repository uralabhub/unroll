import XCTest
@testable import Unroll

/// Adaptive header reads: layouts found in real files from Nikon Z / D850 (SubIFDs past 192 KB)
/// and Sony NEX-3 / Ricoh GR Digital II (IFD0 near the end of the file).
final class HeaderFollowUpTests: XCTestCase {
    private typealias E = TIFFBuilder.Entry

    /// Little-endian TIFF with bytes placed at absolute offsets.
    private func file(size: Int, ifd0At: Int, parts: [(Int, [UInt8])]) -> Data {
        var b = [UInt8](repeating: 0, count: size)
        b.replaceSubrange(0..<8, with: [0x49, 0x49, 0x2A, 0x00] + TIFFBuilder.le32(UInt32(ifd0At)))
        for (o, bytes) in parts { b.replaceSubrange(o..<(o + bytes.count), with: bytes) }
        return Data(b)
    }

    private let jpeg: [UInt8] = [0xFF, 0xD8, 0xFF, 0xDB] + [UInt8](repeating: 0x11, count: 3000) + [0xFF, 0xD9]

    private func reader(_ d: Data) -> (Int, Int) async throws -> Data {
        { o, n in d.subdata(in: o..<min(d.count, o + n)) }
    }

    func testSubIFDBeyondHeaderIsFollowed() async {
        let sub = 300_000, jpg = 310_000
        let ifd0 = TIFFBuilder.ifd([E.ascii(0x010F, "NIKON CORPORATION"), E.ascii(0x0110, "NIKON Z 6_2"),
                                   E.long(0x014A, sub)], at: 8)
        let subIFD = TIFFBuilder.ifd([E.long(0x0201, jpg), E.long(0x0202, jpeg.count)], at: sub)
        let d = file(size: 320_000, ifd0At: 8, parts: [(8, ifd0), (sub, subIFD), (jpg, jpeg)])
        let head = d.prefix(HeaderParser.headerLength)

        XCTAssertNil(HeaderParser.parse(head).preview, "precondition: the first 192 KB alone has no preview")
        let missing = HeaderParser.missingRanges(head, fileSize: d.count)
        XCTAssertEqual(missing.first?.offset, sub)

        let info = await HeaderParser.parseAdaptive(head, fileSize: d.count, read: reader(d))
        XCTAssertEqual(info.meta.model, "NIKON Z 6_2")
        XCTAssertEqual(info.preview, ByteRange(offset: jpg, length: jpeg.count))
    }

    func testIFD0AtEndOfFileIsFollowed() async {
        let at = 4_000_000, jpg = 3_900_000
        let ifd0 = TIFFBuilder.ifd([E.ascii(0x010F, "SONY"), E.ascii(0x0110, "NEX-3"),
                                   E.long(0x0201, jpg), E.long(0x0202, jpeg.count)], at: at)
        let d = file(size: at + 4096, ifd0At: at, parts: [(at, ifd0), (jpg, jpeg)])
        let head = d.prefix(HeaderParser.headerLength)

        XCTAssertNil(HeaderParser.parse(head).meta.model)
        let info = await HeaderParser.parseAdaptive(head, fileSize: d.count, read: reader(d))
        XCTAssertEqual(info.meta.model, "NEX-3")
        XCTAssertEqual(info.preview?.offset, jpg)
    }

    func testCompleteHeaderNeedsNoFollowUp() async {
        let ifd0 = TIFFBuilder.ifd([E.ascii(0x0110, "X"), E.long(0x0201, 20_000), E.long(0x0202, jpeg.count)], at: 8)
        let d = file(size: 30_000, ifd0At: 8, parts: [(8, ifd0), (20_000, jpeg)])
        XCTAssertTrue(HeaderParser.missingRanges(d, fileSize: d.count).isEmpty)
        var reads = 0
        let info = await HeaderParser.parseAdaptive(d, fileSize: d.count) { o, n in reads += 1; return Data() }
        XCTAssertEqual(reads, 0)
        XCTAssertNotNil(info.preview)
    }

    func testOffsetsBeyondFileAreIgnored() {
        let ifd0 = TIFFBuilder.ifd([E.long(0x014A, 900_000_000)], at: 8)
        let d = file(size: 1000, ifd0At: 8, parts: [(8, ifd0)])
        XCTAssertTrue(HeaderParser.missingRanges(d, fileSize: 50_000_000).isEmpty)
    }

    func testMergeZeroFillsGapsAndRespectsLimit() {
        let merged = HeaderParser.merge(Data([1, 2, 3]), [(offset: 10, data: Data([9, 9]))])
        XCTAssertEqual([UInt8](merged), [1, 2, 3, 0, 0, 0, 0, 0, 0, 0, 9, 9])
        let huge = HeaderParser.merge(Data([1]), [(offset: HeaderParser.followUpBufferLimit, data: Data([1]))])
        XCTAssertEqual(huge.count, 1)
    }

    func testCoalesceJoinsNearbyRanges() {
        let r = HeaderParser.coalesce([ByteRange(offset: 100_000, length: 100), ByteRange(offset: 0, length: 10),
                                       ByteRange(offset: 100_200, length: 100)])
        XCTAssertEqual(r, [ByteRange(offset: 0, length: 10), ByteRange(offset: 100_000, length: 300)])
    }

    func testExtensionListCoversRealCameraFormats() {
        for ext in ["crw", "mrw", "kdc", "dcr", "raw", "gpr", "fff", "mos", "mef", "x3f", "iiq", "3fr", "srw", "nrw", "hif", "mxf"] {
            XCTAssertNotEqual(ShotFormat.from(filename: "A.\(ext.uppercased())"), .unknown, ext)
        }
        XCTAssertEqual(ShotFormat.from(filename: "VOICE.WAV"), .unknown)
        XCTAssertTrue(ShotFormat.from(filename: "CRW_0001.CRW").isRAW)
    }
}
