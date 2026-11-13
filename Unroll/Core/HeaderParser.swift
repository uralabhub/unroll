import Foundation
import ImageIO

/// Absolute byte range inside the original file.
struct ByteRange: Codable, Hashable {
    var offset: Int
    var length: Int
}

struct HeaderInfo {
    var format: ShotFormat = .unknown
    var meta = ShotMeta()
    /// Largest embedded JPEG preview we found (RAF/ARW/NEF/DNG/CR2/CR3 preview, JPEG MPF preview).
    var preview: ByteRange?
    /// Preview announced by a maker note (Olympus/OM System CameraSettings) — a candidate for TIFF RAWs.
    var makerNotePreview: ByteRange?
}

/// Parses the *first bytes* of a camera file. This is the heart of the "instant" experience:
/// 128 KB over USB/Wi-Fi gives us the full EXIF, the film recipe, the star rating and the location
/// of a 1–8 MP preview inside the file, so we never pull a 60 MB RAW just to look at it.
enum HeaderParser {
    /// How many leading bytes to read. Covers EXIF (≤64 KB APP1) + MPF index in JPEGs and the
    /// RAF header + embedded JPEG's EXIF. TIFF RAWs keep their IFDs near the start too.
    static let headerLength = 192 * 1024

    static func parse(_ data: Data) -> HeaderInfo {
        let v = ByteView(data)
        var info = HeaderInfo()

        if v.matches(0, [0xFF, 0xD8]) {
            info.format = .jpeg
            parseJPEG(v, at: 0, into: &info, previewFromMPF: true)
        } else if v.matches(0, "FUJIFILMCCD-RAW") {
            info.format = .raf
            parseRAF(v, into: &info)
        } else if v.matches(4, "ftyp") {
            let brand = v.ascii(8, 4) ?? ""
            info.format = brand.hasPrefix("crx") ? .cr3 : .heif
            if info.format == .cr3 { parseCR3(v, into: &info) } else { parseHEIF(data, into: &info) }
        } else if let t = TIFFStructure(v, headerAt: 0) {
            info.format = .tiffRaw
            parseTIFFRaw(t, into: &info)
        }
        return info
    }

    // MARK: JPEG

    static func parseJPEG(_ v: ByteView, at start: Int, into info: inout HeaderInfo, previewFromMPF: Bool) {
        var pos = start + 2
        var exifBase: Int?
        var mpfBase: Int?
        while pos + 4 <= v.count {
            guard v.u8(pos) == 0xFF, let marker = v.u8(pos + 1) else { break }
            if marker == 0xFF { pos += 1; continue }                       // fill byte
            if marker == 0xD8 || (0xD0...0xD7).contains(marker) || marker == 0x01 { pos += 2; continue }
            if marker == 0xDA || marker == 0xD9 { break }                   // start of scan / end
            guard let len = v.u16(pos + 2, little: false), len >= 2 else { break }
            let body = pos + 4
            switch marker {
            case 0xE1 where v.matches(body, "Exif\0\0"):
                if exifBase == nil { exifBase = body + 6 }
            case 0xE1 where v.matches(body, "http://ns.adobe.com/xap/1.0/\0"):
                if info.meta.rating == nil {
                    info.meta.rating = xmpRating(v, from: body, length: Int(len) - 2)
                }
            case 0xE2 where v.matches(body, "MPF\0"):
                mpfBase = body + 4
            case 0xC0, 0xC1, 0xC2:                                          // SOF: pixel size
                if info.meta.height == nil {
                    info.meta.height = v.u16(body + 1, little: false).map(Int.init)
                    info.meta.width = v.u16(body + 3, little: false).map(Int.init)
                }
            default: break
            }
            pos += 2 + Int(len)
        }
        if let exifBase, let t = TIFFStructure(v, headerAt: exifBase) {
            parseExifTIFF(t, into: &info)
        }
        if previewFromMPF, let mpfBase, let t = TIFFStructure(v, headerAt: mpfBase) {
            if let r = mpfLargestPreview(t) { info.preview = r }
        }
    }

    /// Multi-Picture Format index: Fujifilm/Sony/Canon/Nikon JPEGs carry a ~2–8 MP preview here.
    static func mpfLargestPreview(_ t: TIFFStructure) -> ByteRange? {
        guard let first = t.firstIFDOffset else { return nil }
        let entries = t.entries(ifdAt: first)
        guard let mp = entries[tag: 0xB002] else { return nil }
        let n = mp.count / 16
        var best: ByteRange?
        for i in 1..<max(n, 1) {
            let p = mp.valuePos + i * 16
            guard let size = t.v.u32(p + 4, little: t.little),
                  let off = t.v.u32(p + 8, little: t.little), off > 0, size > 0 else { continue }
            let r = ByteRange(offset: t.base + Int(off), length: Int(size))
            if r.length > (best?.length ?? 0) { best = r }
        }
        return best
    }

    static func xmpRating(_ v: ByteView, from: Int, length: Int) -> Int? {
        let end = min(v.count, from + max(0, length))
        guard end > from else { return nil }
        let s = String(decoding: v.b[from..<end], as: UTF8.self)
        for pattern in ["xmp:Rating=\"", "<xmp:Rating>"] {
            if let r = s.range(of: pattern) {
                let digits = s[r.upperBound...].prefix { $0 == "-" || $0.isNumber }
                if let n = Int(digits) { return max(0, min(5, n)) }
            }
        }
        return nil
    }

    // MARK: EXIF

    static func parseExifTIFF(_ t: TIFFStructure, into info: inout HeaderInfo) {
        guard let ifd0Off = t.firstIFDOffset else { return }
        let ifd0 = t.entries(ifdAt: ifd0Off)
        var m = info.meta
        m.make = m.make ?? t.string(ifd0[tag: 0x010F])
        m.model = m.model ?? t.string(ifd0[tag: 0x0110])
        m.orientation = m.orientation ?? t.int(ifd0[tag: 0x0112])
        if let r = t.int(ifd0[tag: 0x4746]), r > 0 { m.rating = min(5, r) }   // Microsoft/EXIF Rating
        // XMP packet in IFD0 (tag 0x02BC, used by TIFF RAWs incl. Panasonic RW2). Canon/Nikon/
        // Olympus/Panasonic/Pentax have no maker-note rating tag, so this is where stars live.
        if m.rating == nil, let x = ifd0[tag: 0x02BC], let r = xmpRating(t.v, from: x.valuePos, length: x.count), r > 0 {
            m.rating = r
        }

        if let exifOff = t.int(ifd0[tag: 0x8769]) {
            let ex = t.entries(ifdAt: exifOff)
            parseExifIFD(t, ex, into: &m)
            if let mn = ex[tag: 0x927C] {
                let p = parseMakerNote(t, at: mn.valuePos, length: mn.count, make: m.make ?? "", into: &m)
                if let p { info.makerNotePreview = p }
            }
        }
        if let gpsOff = t.int(ifd0[tag: 0x8825]) {
            parseGPS(t, ifdAt: gpsOff, into: &m)
        }
        info.meta = m
    }

    static func parseGPS(_ t: TIFFStructure, ifdAt off: Int, into m: inout ShotMeta) {
        guard m.latitude == nil else { return }
        let g = t.entries(ifdAt: off)
        if let lat = dms(t, g[tag: 0x0002]), let lon = dms(t, g[tag: 0x0004]), lat != 0 || lon != 0 {
            m.latitude = (t.string(g[tag: 0x0001]) == "S") ? -lat : lat
            m.longitude = (t.string(g[tag: 0x0003]) == "W") ? -lon : lon
        }
    }

    static func parseExifIFD(_ t: TIFFStructure, _ ex: [TIFFEntry], into m: inout ShotMeta) {
        m.exposureTime = m.exposureTime ?? t.rational(ex[tag: 0x829A])
        m.fNumber = m.fNumber ?? t.rational(ex[tag: 0x829D])
        m.iso = m.iso ?? t.int(ex[tag: 0x8827])
        m.dateTimeOriginal = m.dateTimeOriginal ?? t.string(ex[tag: 0x9003])
        m.offsetTime = m.offsetTime ?? t.string(ex[tag: 0x9011])
        m.exposureBias = m.exposureBias ?? t.rational(ex[tag: 0x9204])
        m.focalLength = m.focalLength ?? t.rational(ex[tag: 0x920A])
        m.focalLength35 = m.focalLength35 ?? t.int(ex[tag: 0xA405])
        m.lens = m.lens ?? t.string(ex[tag: 0xA434])
        if m.width == nil { m.width = t.int(ex[tag: 0xA002]) }
        if m.height == nil { m.height = t.int(ex[tag: 0xA003]) }
    }

    static func dms(_ t: TIFFStructure, _ e: TIFFEntry?) -> Double? {
        guard let d = t.rational(e, index: 0) else { return nil }
        return d + (t.rational(e, index: 1) ?? 0) / 60 + (t.rational(e, index: 2) ?? 0) / 3600
    }

    /// Decodes the bits of each maker note we use. Returns a preview range when the note carries
    /// one (Olympus / OM System keep the full-size preview there, not in the TIFF IFDs).
    @discardableResult
    static func parseMakerNote(_ exif: TIFFStructure, at p: Int, length: Int, make: String, into m: inout ShotMeta) -> ByteRange? {
        let v = exif.v
        let mk = make.lowercased()
        // "FUJIFILM" headers also appear in some Leica/Minolta/Sharp notes (ExifTool), so check the make too.
        if v.matches(p, "FUJIFILM"), mk.isEmpty || mk.hasPrefix("fujifilm") {
            let (recipe, rating) = FujiMakerNote.parse(v, at: p)
            if let recipe { m.recipe = recipe }
            if let rating, rating > 0 { m.rating = min(5, rating) }
        } else if mk.hasPrefix("nikon"), v.matches(p, "Nikon\0") {
            // Type-3 Nikon maker note: embedded TIFF header 10 bytes in.
            if let t = TIFFStructure(v, headerAt: p + 10), let first = t.firstIFDOffset {
                let e = t.entries(ifdAt: first)
                if let pc = e[tag: 0x0023] {
                    // PictureControlData: 4-char version, then the name. ExifTool's PictureControl3
                    // (version "03xx", Z bodies) has 4 more bytes before the name; v1/v2 do not.
                    let version = v.ascii(pc.valuePos, 4) ?? ""
                    let nameAt = pc.valuePos + (version.hasPrefix("03") ? 8 : 4)
                    if let name = v.ascii(nameAt, 20) {
                        m.look = "Picture Control: " + name.capitalized
                    }
                }
            }
        } else if mk.hasPrefix("sony") {
            // "SONY DSC \0\0\0" / "SONY CAM": IFD 12 bytes in. ExifTool also knows a header-less
            // variant (Sony5, seen in ARW/SR2) where the IFD starts at the note itself.
            // Offsets are relative to the EXIF TIFF header in both cases.
            let ifdAt = v.matches(p, "SONY ") ? p + 12 : p
            let e = exif.entries(ifdAt: ifdAt - exif.base)
            if let r = exif.int(e[tag: 0x2002]), r > 0, r <= 5 { m.rating = r }
            if let cs = exif.string(e[tag: 0xB020]) { m.look = "Creative Style: " + cs }
        } else if v.matches(p, "OLYMPUS\0") || v.matches(p, "OM SYSTEM\0") {
            return olympusPreview(v, note: p)
        }
        return nil
    }

    /// Olympus "OLYMPUS\0II" (IFD at +12) and OM System "OM SYSTEM\0\0\0II" (IFD at +16) maker notes.
    /// Offsets are relative to the note start. The full-size preview is PreviewImageStart/Length
    /// (0x0101/0x0102) inside the CameraSettings sub-IFD 0x2020 (ExifTool Olympus tables).
    static func olympusPreview(_ v: ByteView, note p: Int) -> ByteRange? {
        let isOM = v.matches(p, "OM SYSTEM")
        let order = p + (isOM ? 12 : 8)
        guard let a = v.u8(order), a == v.u8(order + 1), a == 0x49 || a == 0x4D else { return nil }
        let t = TIFFStructure(v, base: p, little: a == 0x49)
        let e = t.entries(ifdAt: isOM ? 16 : 12)
        guard let cs = e[tag: 0x2020] else { return nil }
        // Newer notes store 0x2020 as an IFD/LONG pointer; older ones embed the IFD as UNDEFINED data.
        let sub: Int? = (cs.type == 7) ? cs.valuePos - p : t.int(cs)
        guard let sub else { return nil }
        let c = t.entries(ifdAt: sub)
        guard let start = t.int(c[tag: 0x0101]), let len = t.int(c[tag: 0x0102]), start > 0, len > 1024 else { return nil }
        return ByteRange(offset: p + start, length: len)
    }

    // MARK: RAF

    static func parseRAF(_ v: ByteView, into info: inout HeaderInfo) {
        guard let jOff = v.u32(84, little: false), let jLen = v.u32(88, little: false), jOff > 0 else { return }
        info.preview = ByteRange(offset: Int(jOff), length: Int(jLen))
        if v.matches(Int(jOff), [0xFF, 0xD8]) {
            parseJPEG(v, at: Int(jOff), into: &info, previewFromMPF: false)
            // Pixel size reported by the embedded preview is not the RAW size.
            info.meta.width = nil; info.meta.height = nil
        }
    }

    // MARK: HEIF (Sony/Canon/Nikon/Fujifilm HIF)

    /// HEIF keeps EXIF in an item referenced from the meta box; ImageIO resolves that for us and
    /// copes with the truncated header as long as the meta box and EXIF item fit in it.
    static func parseHEIF(_ data: Data, into info: inout HeaderInfo) {
        guard let src = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
              let p = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any] else { return }
        let tiff = p[kCGImagePropertyTIFFDictionary] as? [CFString: Any] ?? [:]
        let exif = p[kCGImagePropertyExifDictionary] as? [CFString: Any] ?? [:]
        let gps = p[kCGImagePropertyGPSDictionary] as? [CFString: Any] ?? [:]
        var m = info.meta
        m.make = m.make ?? tiff[kCGImagePropertyTIFFMake] as? String
        m.model = m.model ?? tiff[kCGImagePropertyTIFFModel] as? String
        m.orientation = m.orientation ?? (p[kCGImagePropertyOrientation] as? Int)
        m.exposureTime = m.exposureTime ?? exif[kCGImagePropertyExifExposureTime] as? Double
        m.fNumber = m.fNumber ?? exif[kCGImagePropertyExifFNumber] as? Double
        m.iso = m.iso ?? (exif[kCGImagePropertyExifISOSpeedRatings] as? [Int])?.first
        m.dateTimeOriginal = m.dateTimeOriginal ?? exif[kCGImagePropertyExifDateTimeOriginal] as? String
        m.offsetTime = m.offsetTime ?? exif[kCGImagePropertyExifOffsetTimeOriginal] as? String
        m.exposureBias = m.exposureBias ?? exif[kCGImagePropertyExifExposureBiasValue] as? Double
        m.focalLength = m.focalLength ?? exif[kCGImagePropertyExifFocalLength] as? Double
        m.focalLength35 = m.focalLength35 ?? exif[kCGImagePropertyExifFocalLenIn35mmFilm] as? Int
        m.lens = m.lens ?? exif[kCGImagePropertyExifLensModel] as? String
        m.width = m.width ?? (p[kCGImagePropertyPixelWidth] as? Int)
        m.height = m.height ?? (p[kCGImagePropertyPixelHeight] as? Int)
        if m.latitude == nil, let lat = gps[kCGImagePropertyGPSLatitude] as? Double, let lon = gps[kCGImagePropertyGPSLongitude] as? Double {
            m.latitude = (gps[kCGImagePropertyGPSLatitudeRef] as? String == "S") ? -lat : lat
            m.longitude = (gps[kCGImagePropertyGPSLongitudeRef] as? String == "W") ? -lon : lon
        }
        info.meta = m
    }

    // MARK: TIFF-based RAW (ARW, NEF, DNG, CR2, ORF, RW2, PEF…)

    static func parseTIFFRaw(_ t: TIFFStructure, into info: inout HeaderInfo) {
        parseExifTIFF(t, into: &info)
        var candidates: [ByteRange] = []
        if let p = info.makerNotePreview { candidates.append(p) }
        let ifd0 = t.firstIFDOffset

        // Panasonic RW2/RWL: the preview is a whole JPEG stored as tag 0x002E (JpgFromRaw) in IFD0,
        // and the EXIF lives inside that JPEG (IFD0 has no ExifIFD pointer). ExifTool PanasonicRaw.
        if t.magic == 0x55, let o = ifd0, let j = t.entries(ifdAt: o)[tag: 0x002E], j.count > 1024 {
            candidates.append(ByteRange(offset: j.valuePos, length: j.count))
            if t.v.matches(j.valuePos, [0xFF, 0xD8]) {
                let (w, h) = (info.meta.width, info.meta.height)
                parseJPEG(t.v, at: j.valuePos, into: &info, previewFromMPF: false)
                info.meta.width = w; info.meta.height = h
            }
        }
        var visited = Set<Int>()
        var queue: [Int] = []
        var off = t.firstIFDOffset
        while let o = off, queue.count < 8 { queue.append(o); off = t.nextIFD(after: o) }

        while let o = queue.popLast() {
            guard visited.insert(o).inserted, visited.count < 32 else { continue }
            let e = t.entries(ifdAt: o)
            for sub in t.ints(e[tag: 0x014A]) { queue.append(sub) }               // SubIFDs
            if let jo = t.int(e[tag: 0x0201]), let jl = t.int(e[tag: 0x0202]), jl > 1024 {
                candidates.append(ByteRange(offset: t.base + jo, length: jl))
            }
            let compression = t.int(e[tag: 0x0103]) ?? 1
            let photometric = t.int(e[tag: 0x0106]) ?? 0
            let bps = t.int(e[tag: 0x0102]) ?? 8
            let isJPEGStrip = compression == 6 || (compression == 7 && bps == 8 && (photometric == 2 || photometric == 6))
            let offs = t.ints(e[tag: 0x0111]), lens = t.ints(e[tag: 0x0117])
            // Old-style JPEG strips (compression 6) are a preview only in IFD0 (CR2). Canon's IFD3
            // holds the *raw sensor data* as lossless JPEG, also tagged 6 — never take it.
            let stripOK = compression == 6 ? (o == ifd0) : true
            if isJPEGStrip, stripOK, offs.count == 1, lens.count == 1, lens[0] > 1024 {
                candidates.append(ByteRange(offset: t.base + offs[0], length: lens[0]))
            }
        }
        info.preview = candidates.max { $0.length < $1.length }
    }

    // MARK: CR3 (ISO-BMFF)

    static func parseCR3(_ v: ByteView, into info: inout HeaderInfo) {
        // CMT1 = IFD0 (make/model), CMT2 = EXIF IFD — each is a self-contained TIFF.
        if let p = v.firstIndex(of: Array("CMT1".utf8)), let t = TIFFStructure(v, headerAt: p + 4) {
            parseExifTIFF(t, into: &info)
        }
        if let p = v.firstIndex(of: Array("CMT2".utf8)), let t = TIFFStructure(v, headerAt: p + 4), let first = t.firstIFDOffset {
            parseExifIFD(t, t.entries(ifdAt: first), into: &info.meta)
        }
        // CMT4 = GPS IFD (lclevy/canon_cr3).
        if let p = v.firstIndex(of: Array("CMT4".utf8)), let t = TIFFStructure(v, headerAt: p + 4), let first = t.firstIFDOffset {
            parseGPS(t, ifdAt: first, into: &info.meta)
        }
        // XMP lives in uuid box be7acfcb-97a9-42e8-9c71-999491e3afac, right after moov.
        let xmpUUID: [UInt8] = [0xBE, 0x7A, 0xCF, 0xCB, 0x97, 0xA9, 0x42, 0xE8, 0x9C, 0x71, 0x99, 0x94, 0x91, 0xE3, 0xAF, 0xAC]
        if info.meta.rating == nil, let u = v.firstIndex(of: xmpUUID), let boxSize = v.u32(u - 8, little: false), boxSize > 24 {
            if let r = xmpRating(v, from: u + 16, length: Int(boxSize) - 24), r > 0 { info.meta.rating = r }
        }
        // PRVW box: a ~1620×1080 JPEG, size stored as big-endian u32 right before SOI.
        if let p = v.firstIndex(of: Array("PRVW".utf8)),
           let soi = v.firstIndex(of: [0xFF, 0xD8, 0xFF], from: p, to: p + 64),
           let size = v.u32(soi - 4, little: false), size > 1024 {
            info.preview = ByteRange(offset: soi, length: Int(size))
        }
    }
}
