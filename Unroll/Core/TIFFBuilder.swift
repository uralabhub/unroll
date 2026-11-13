import Foundation

/// Minimal little-endian TIFF/EXIF writer. Used to synthesise realistic camera files for the
/// demo roll and for unit tests (ImageIO cannot write maker notes, so we write EXIF ourselves).
struct TIFFBuilder {
    struct Entry {
        var tag: UInt16
        var type: UInt16
        var count: Int
        var payload: [UInt8]

        static func ascii(_ tag: UInt16, _ s: String) -> Entry {
            let b = Array(s.utf8) + [0]
            return Entry(tag: tag, type: 2, count: b.count, payload: b)
        }
        static func short(_ tag: UInt16, _ v: Int) -> Entry {
            Entry(tag: tag, type: 3, count: 1, payload: le16(UInt16(truncatingIfNeeded: v)))
        }
        static func long(_ tag: UInt16, _ v: Int) -> Entry {
            Entry(tag: tag, type: 4, count: 1, payload: le32(UInt32(truncatingIfNeeded: v)))
        }
        static func slong(_ tag: UInt16, _ vs: [Int]) -> Entry {
            Entry(tag: tag, type: 9, count: vs.count, payload: vs.flatMap { le32(UInt32(bitPattern: Int32($0))) })
        }
        static func rational(_ tag: UInt16, _ vs: [(Int, Int)]) -> Entry {
            Entry(tag: tag, type: 5, count: vs.count, payload: vs.flatMap { le32(UInt32($0.0)) + le32(UInt32($0.1)) })
        }
        static func srational(_ tag: UInt16, _ n: Int, _ d: Int) -> Entry {
            Entry(tag: tag, type: 10, count: 1, payload: le32(UInt32(bitPattern: Int32(n))) + le32(UInt32(bitPattern: Int32(d))))
        }
        static func undefined(_ tag: UInt16, _ bytes: [UInt8]) -> Entry {
            Entry(tag: tag, type: 7, count: bytes.count, payload: bytes)
        }
    }

    static func le16(_ v: UInt16) -> [UInt8] { [UInt8(v & 0xFF), UInt8(v >> 8)] }
    static func le32(_ v: UInt32) -> [UInt8] { (0..<4).map { UInt8((v >> (8 * $0)) & 0xFF) } }

    /// Serialises one IFD placed at `offset` (relative to the TIFF base); out-of-line values follow it.
    static func ifd(_ entries: [Entry], at offset: Int, next: Int = 0) -> [UInt8] {
        let sorted = entries.sorted { $0.tag < $1.tag }
        let headerSize = 2 + sorted.count * 12 + 4
        var head = le16(UInt16(sorted.count))
        var data: [UInt8] = []
        for e in sorted {
            head += le16(e.tag) + le16(e.type) + le32(UInt32(e.count))
            if e.payload.count <= 4 {
                head += e.payload + [UInt8](repeating: 0, count: 4 - e.payload.count)
            } else {
                head += le32(UInt32(offset + headerSize + data.count))
                data += e.payload
                if data.count % 2 == 1 { data.append(0) }
            }
        }
        head += le32(UInt32(next))
        return head + data
    }

    static func size(of entries: [Entry]) -> Int { ifd(entries, at: 0).count }

    /// Full EXIF TIFF: IFD0 → EXIF IFD (→ optional GPS IFD).
    static func exifTIFF(ifd0: [Entry], exif: [Entry], gps: [Entry] = []) -> [UInt8] {
        let header: [UInt8] = [0x49, 0x49, 0x2A, 0x00] + le32(8)
        var ifd0 = ifd0
        ifd0.append(.long(0x8769, 0))
        if !gps.isEmpty { ifd0.append(.long(0x8825, 0)) }
        let ifd0Size = size(of: ifd0)
        let exifOff = 8 + ifd0Size
        let exifSize = size(of: exif)
        let gpsOff = exifOff + exifSize
        ifd0 = ifd0.map { e in
            if e.tag == 0x8769 { return .long(0x8769, exifOff) }
            if e.tag == 0x8825 { return .long(0x8825, gpsOff) }
            return e
        }
        var out = header + ifd(ifd0, at: 8) + ifd(exif, at: exifOff)
        if !gps.isEmpty { out += ifd(gps, at: gpsOff) }
        return out
    }

    /// Fujifilm maker note blob ("FUJIFILM" + offset 12 + IFD, offsets relative to blob start).
    static func fujiMakerNote(_ entries: [Entry]) -> [UInt8] {
        Array("FUJIFILM".utf8) + le32(12) + ifd(entries, at: 12)
    }

    /// Inserts an APP1/EXIF segment right after SOI, dropping existing APP0/APP1 segments.
    static func jpeg(_ jpeg: Data, withExif tiff: [UInt8], extraSegments: [[UInt8]] = []) -> Data {
        let src = [UInt8](jpeg)
        guard src.count > 4, src[0] == 0xFF, src[1] == 0xD8 else { return jpeg }
        var pos = 2
        while pos + 4 <= src.count, src[pos] == 0xFF, src[pos + 1] == 0xE0 || src[pos + 1] == 0xE1 {
            pos += 2 + Int(UInt16(src[pos + 2]) << 8 | UInt16(src[pos + 3]))
        }
        let body = Array("Exif\0\0".utf8) + tiff
        let len = UInt16(body.count + 2)
        var out: [UInt8] = [0xFF, 0xD8, 0xFF, 0xE1, UInt8(len >> 8), UInt8(len & 0xFF)] + body
        for seg in extraSegments { out += seg }
        out += src[pos...]
        return Data(out)
    }

    /// Wraps a JPEG in a minimal RAF container (header + embedded JPEG), for tests.
    static func raf(embedding jpeg: Data) -> Data {
        var h = [UInt8](repeating: 0, count: 160)
        let magic = Array("FUJIFILMCCD-RAW 0201FF383501".utf8)
        h.replaceSubrange(0..<magic.count, with: magic)
        func be32(_ v: Int) -> [UInt8] { [UInt8(v >> 24 & 0xFF), UInt8(v >> 16 & 0xFF), UInt8(v >> 8 & 0xFF), UInt8(v & 0xFF)] }
        h.replaceSubrange(84..<88, with: be32(160))
        h.replaceSubrange(88..<92, with: be32(jpeg.count))
        return Data(h) + jpeg
    }
}
