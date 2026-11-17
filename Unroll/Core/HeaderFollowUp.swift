import Foundation

/// Adaptive header reading.
///
/// The first `HeaderParser.headerLength` bytes are enough for most cameras, but a real-file test
/// over 924 camera models found bodies that keep their TIFF structure further in:
/// - Nikon Z / D850 / D780 / D750 / D500 / D7500 / D5600 / Nikon 1, Samsung NX, Pentax K-3 III:
///   SubIFDs (where the full-size preview is described) sit at 200 KB – 1 MB.
/// - Sony NEX-3 ARW, Ricoh GR II DNG: IFD0 itself is near the *end* of the file.
///
/// Instead of reading more of every file, we parse what we have, ask which referenced IFDs are
/// outside it, read just those (a few KB each) and parse again.
extension HeaderParser {
    /// Never chase offsets beyond this; nothing sane puts its IFDs past it.
    static let followUpFileLimit = 256 << 20
    /// The merged buffer may not exceed this (gaps are zero-filled).
    static let followUpBufferLimit = 64 << 20
    /// How much to read at an out-of-range IFD: the entries plus the small values they point at.
    static let followUpChunk = 64 * 1024

    /// Whether a parse result is worth a follow-up read.
    static func wantsFollowUp(_ info: HeaderInfo) -> Bool {
        info.format == .tiffRaw && (info.preview == nil || info.meta.model == nil)
    }

    /// TIFF IFDs (IFD0 chain, SubIFDs, EXIF IFD) referenced from `data` but lying outside it.
    static func missingRanges(_ data: Data, fileSize: Int?) -> [ByteRange] {
        let v = ByteView(data)
        guard let t = TIFFStructure(v, headerAt: 0), let first = t.firstIFDOffset else { return [] }
        let limit = min(fileSize.map { $0 > 0 ? $0 : followUpFileLimit } ?? followUpFileLimit, followUpFileLimit)
        var out: [ByteRange] = []
        func need(_ off: Int, _ len: Int) {
            guard off > 0, off < limit, len > 0 else { return }
            out.append(ByteRange(offset: off, length: min(len, limit - off)))
        }
        var seen = Set<Int>()
        var queue = [first]
        while let o = queue.popLast() {
            guard o > 0, seen.count < 32, seen.insert(o).inserted else { continue }
            guard let n16 = v.u16(o, little: t.little) else { need(o, followUpChunk); continue }
            let n = Int(n16)
            guard n > 0, n < 1000 else { continue }
            if o + 2 + n * 12 + 4 > v.count { need(o, followUpChunk); continue }
            let e = t.entries(ifdAt: o)
            if let sub = e[tag: 0x014A] {
                let bytes = sub.count * 4
                if sub.count > 1, sub.valuePos + bytes > v.count { need(sub.valuePos, bytes) } else { queue += t.ints(sub) }
            }
            if let exif = t.int(e[tag: 0x8769]) { queue.append(exif) }
            if let next = t.nextIFD(after: o) { queue.append(next) }
        }
        return coalesce(out)
    }

    /// Merges ranges that overlap or sit within 16 KB of each other into single reads.
    static func coalesce(_ ranges: [ByteRange]) -> [ByteRange] {
        var out: [ByteRange] = []
        for r in ranges.sorted(by: { $0.offset < $1.offset }) {
            if let last = out.last, r.offset <= last.offset + last.length + 16 * 1024 {
                let end = max(last.offset + last.length, r.offset + r.length)
                out[out.count - 1] = ByteRange(offset: last.offset, length: end - last.offset)
            } else {
                out.append(r)
            }
        }
        return out
    }

    /// Lays `chunks` (absolute offset, bytes) over `data` in one buffer; gaps are zero-filled.
    /// Chunks that would push the buffer past `followUpBufferLimit` are dropped.
    static func merge(_ data: Data, _ chunks: [(offset: Int, data: Data)]) -> Data {
        let usable = chunks.filter { $0.offset >= 0 && $0.offset + $0.data.count <= followUpBufferLimit && !$0.data.isEmpty }
        let end = max(data.count, usable.map { $0.offset + $0.data.count }.max() ?? 0)
        guard end > data.count || !usable.isEmpty else { return data }
        var out = Data(count: end)
        out.replaceSubrange(0..<data.count, with: data)
        for c in usable { out.replaceSubrange(c.offset..<(c.offset + c.data.count), with: c.data) }
        return out
    }

    /// Parse with up to two follow-up rounds. `read(offset, length)` fetches bytes from the file.
    static func parseAdaptive(_ initial: Data, fileSize: Int?,
                              read: (Int, Int) async throws -> Data) async -> HeaderInfo {
        var data = initial
        var info = parse(data)
        for _ in 0..<2 where wantsFollowUp(info) {
            let missing = missingRanges(data, fileSize: fileSize)
            guard !missing.isEmpty else { break }
            var chunks: [(offset: Int, data: Data)] = []
            for r in missing {
                guard let d = try? await read(r.offset, r.length), !d.isEmpty else { continue }
                chunks.append((r.offset, d))
            }
            guard !chunks.isEmpty else { break }
            data = merge(data, chunks)
            info = parse(data)
        }
        return info
    }
}
