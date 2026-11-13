import Foundation

/// Bounds-checked random access over a byte buffer. Every read returns nil instead of trapping,
/// because we routinely parse *truncated* headers (the first 128 KB of a 60 MB RAW).
struct ByteView {
    let b: [UInt8]

    init(_ data: Data) { b = [UInt8](data) }
    init(_ bytes: [UInt8]) { b = bytes }

    var count: Int { b.count }

    func u8(_ o: Int) -> UInt8? { (o >= 0 && o < b.count) ? b[o] : nil }

    func u16(_ o: Int, little: Bool) -> UInt16? {
        guard o >= 0, o + 2 <= b.count else { return nil }
        return little ? UInt16(b[o]) | UInt16(b[o + 1]) << 8
                      : UInt16(b[o]) << 8 | UInt16(b[o + 1])
    }

    func u32(_ o: Int, little: Bool) -> UInt32? {
        guard o >= 0, o + 4 <= b.count else { return nil }
        let (a, c, d, e) = (UInt32(b[o]), UInt32(b[o + 1]), UInt32(b[o + 2]), UInt32(b[o + 3]))
        return little ? a | c << 8 | d << 16 | e << 24 : a << 24 | c << 16 | d << 8 | e
    }

    func matches(_ o: Int, _ s: String) -> Bool { matches(o, Array(s.utf8)) }

    func matches(_ o: Int, _ bytes: [UInt8]) -> Bool {
        guard o >= 0, o + bytes.count <= b.count else { return false }
        for i in 0..<bytes.count where b[o + i] != bytes[i] { return false }
        return true
    }

    /// Printable ASCII, trimmed at the first NUL.
    func ascii(_ o: Int, _ n: Int) -> String? {
        guard o >= 0, n > 0, o + n <= b.count else { return nil }
        var out = [UInt8]()
        for i in 0..<n { let c = b[o + i]; if c == 0 { break }; out.append(c) }
        let s = String(decoding: out.filter { $0 >= 0x20 && $0 < 0x7F }, as: UTF8.self)
            .trimmingCharacters(in: .whitespaces)
        return s.isEmpty ? nil : s
    }

    func firstIndex(of pattern: [UInt8], from: Int = 0, to end: Int? = nil) -> Int? {
        let last = min(end ?? b.count, b.count) - pattern.count
        guard !pattern.isEmpty, from <= last else { return nil }
        var i = max(0, from)
        while i <= last {
            if b[i] == pattern[0] && matches(i, pattern) { return i }
            i += 1
        }
        return nil
    }
}

struct TIFFEntry {
    let tag: UInt16
    let type: UInt16
    let count: Int
    /// Absolute position (in the ByteView) of the first value byte.
    let valuePos: Int
}

/// A TIFF / EXIF IFD walker. `base` is the absolute position offsets are relative to
/// (the TIFF header for EXIF, the MakerNote start for Fujifilm, etc).
struct TIFFStructure {
    let v: ByteView
    let base: Int
    let little: Bool

    init?(_ v: ByteView, headerAt base: Int) {
        guard let a = v.u8(base), let c = v.u8(base + 1), a == c else { return nil }
        switch a {
        case 0x49: little = true
        case 0x4D: little = false
        default: return nil
        }
        self.v = v
        self.base = base
    }

    init(_ v: ByteView, base: Int, little: Bool) {
        self.v = v; self.base = base; self.little = little
    }

    /// TIFF magic: 42 normally; ORF uses "RO"/"RS", RW2 uses 0x55.
    var magic: UInt16? { v.u16(base + 2, little: little) }
    var firstIFDOffset: Int? { v.u32(base + 4, little: little).map(Int.init) }

    static func typeSize(_ t: UInt16) -> Int {
        switch t {
        case 1, 2, 6, 7: return 1
        case 3, 8: return 2
        case 4, 9, 11, 13: return 4
        case 5, 10, 12: return 8
        default: return 0
        }
    }

    /// Entries of the IFD at `off` (relative to base). Empty when out of range.
    func entries(ifdAt off: Int) -> [TIFFEntry] {
        let p = base + off
        guard off > 0, let n16 = v.u16(p, little: little) else { return [] }
        let n = Int(n16)
        guard n > 0, n < 1024 else { return [] }
        var out: [TIFFEntry] = []
        out.reserveCapacity(n)
        for i in 0..<n {
            let e = p + 2 + i * 12
            guard let tag = v.u16(e, little: little),
                  let type = v.u16(e + 2, little: little),
                  let cnt = v.u32(e + 4, little: little) else { break }
            let size = TIFFStructure.typeSize(type) * Int(cnt)
            guard size > 0, cnt < 1 << 26 else { continue }
            let pos: Int
            if size <= 4 {
                pos = e + 8
            } else {
                guard let o = v.u32(e + 8, little: little) else { continue }
                pos = base + Int(o)
            }
            out.append(TIFFEntry(tag: tag, type: type, count: Int(cnt), valuePos: pos))
        }
        return out
    }

    func nextIFD(after off: Int) -> Int? {
        guard let n = v.u16(base + off, little: little) else { return nil }
        guard let next = v.u32(base + off + 2 + Int(n) * 12, little: little), next > 0 else { return nil }
        return Int(next)
    }

    func int(_ e: TIFFEntry?, index: Int = 0) -> Int? {
        guard let e, index < e.count else { return nil }
        let p = e.valuePos + index * TIFFStructure.typeSize(e.type)
        switch e.type {
        case 1, 7: return v.u8(p).map(Int.init)
        case 6: return v.u8(p).map { Int(Int8(bitPattern: $0)) }
        case 3: return v.u16(p, little: little).map(Int.init)
        case 8: return v.u16(p, little: little).map { Int(Int16(bitPattern: $0)) }
        case 4, 13: return v.u32(p, little: little).map(Int.init)
        case 9: return v.u32(p, little: little).map { Int(Int32(bitPattern: $0)) }
        default: return nil
        }
    }

    func ints(_ e: TIFFEntry?) -> [Int] {
        guard let e else { return [] }
        return (0..<min(e.count, 4096)).compactMap { int(e, index: $0) }
    }

    func rational(_ e: TIFFEntry?, index: Int = 0) -> Double? {
        guard let e, e.type == 5 || e.type == 10, index < e.count else { return nil }
        let p = e.valuePos + index * 8
        guard let n = v.u32(p, little: little), let d = v.u32(p + 4, little: little), d != 0 else { return nil }
        if e.type == 10 { return Double(Int32(bitPattern: n)) / Double(Int32(bitPattern: d)) }
        return Double(n) / Double(d)
    }

    func string(_ e: TIFFEntry?) -> String? {
        guard let e else { return nil }
        return v.ascii(e.valuePos, e.count)
    }
}

extension Array where Element == TIFFEntry {
    subscript(tag tag: UInt16) -> TIFFEntry? { first { $0.tag == tag } }
}
