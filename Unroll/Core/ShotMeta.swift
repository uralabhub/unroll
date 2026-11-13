import Foundation
import CoreLocation

/// Everything we can learn about a shot from its first ~128 KB, without downloading it.
struct ShotMeta: Codable, Hashable {
    var make: String?
    var model: String?
    var lens: String?
    /// Wall-clock capture time exactly as the camera wrote it ("yyyy:MM:dd HH:mm:ss").
    var dateTimeOriginal: String?
    /// "+07:00" when the camera records its time zone (OffsetTimeOriginal).
    var offsetTime: String?
    var fNumber: Double?
    var exposureTime: Double?
    var iso: Int?
    var focalLength: Double?
    var focalLength35: Int?
    var exposureBias: Double?
    var orientation: Int?
    var width: Int?
    var height: Int?
    /// 0–5 stars as set on the camera body (EXIF Rating, XMP, or maker note).
    var rating: Int?
    var latitude: Double?
    var longitude: Double?
    var recipe: FilmRecipe?
    /// Non-Fujifilm "look" (Nikon Picture Control, etc.) when we can read it.
    var look: String?

    var hasGPS: Bool { latitude != nil && longitude != nil }

    var cameraName: String {
        guard let model else { return make ?? "Camera" }
        // Most makers repeat the brand in the model ("Canon EOS R6"); a few don't ("X100VI").
        if let make, !model.lowercased().hasPrefix(make.lowercased().split(separator: " ").first.map(String.init) ?? "~") {
            let brand = make.split(separator: " ").first.map(String.init)?.capitalized ?? make
            return "\(brand) \(model)"
        }
        return model
    }

    /// Capture date resolved to an absolute instant. Without OffsetTimeOriginal we assume
    /// the camera clock is set to the phone's current time zone (true for most people).
    func captureDate(fallbackZone: TimeZone = .current) -> Date? {
        guard let dateTimeOriginal else { return nil }
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy:MM:dd HH:mm:ss"
        f.timeZone = fallbackZone
        if let off = offsetTime, let tz = ShotMeta.zone(fromOffset: off) { f.timeZone = tz }
        return f.date(from: String(dateTimeOriginal.prefix(19)))
    }

    static func zone(fromOffset s: String) -> TimeZone? {
        let t = s.trimmingCharacters(in: .whitespaces)
        guard t.count >= 6, let sign = t.first, sign == "+" || sign == "-" else { return nil }
        let parts = t.dropFirst().split(separator: ":")
        guard parts.count == 2, let h = Int(parts[0]), let m = Int(parts[1]) else { return nil }
        return TimeZone(secondsFromGMT: (sign == "-" ? -1 : 1) * (h * 3600 + m * 60))
    }

    // MARK: Display

    var apertureText: String? { fNumber.map { "ƒ/" + ShotMeta.trim($0) } }
    var isoText: String? { iso.map { "ISO \($0)" } }
    var focalText: String? { focalLength.map { ShotMeta.trim($0) + "mm" } }

    var shutterText: String? {
        guard let t = exposureTime, t > 0 else { return nil }
        if t >= 1 { return ShotMeta.trim(t) + "s" }
        return "1/\(Int((1 / t).rounded()))"
    }

    var biasText: String? {
        guard let b = exposureBias, abs(b) > 0.01 else { return nil }
        return (b > 0 ? "+" : "") + ShotMeta.trim((b * 10).rounded() / 10) + " EV"
    }

    /// "23mm  ƒ/2  1/250  ISO 160"
    var exposureLine: String {
        [focalText, apertureText, shutterText, isoText].compactMap { $0 }.joined(separator: "   ")
    }

    static func trim(_ d: Double) -> String {
        d.truncatingRemainder(dividingBy: 1) == 0 ? String(Int(d)) : String(format: "%.1f", d)
    }
}

/// A Fujifilm in-camera recipe as recorded in the maker note.
struct FilmRecipe: Codable, Hashable {
    var filmSimulation: String
    var grainEffect: String?          // "Weak, Small"
    var colorChrome: String?          // Off / Weak / Strong
    var colorChromeBlue: String?
    var whiteBalance: String?
    var kelvin: Int?
    var wbShiftRed: Int?
    var wbShiftBlue: Int?
    var dynamicRange: String?         // DR100 / DR200 / DR400 / DR-Auto
    var highlight: Double?
    var shadow: Double?
    var color: String?
    var sharpness: String?
    var noiseReduction: String?
    var clarity: Int?
    var monoWarmCool: Int?
    var monoMagentaGreen: Int?

    var isMonochrome: Bool {
        let s = filmSimulation.lowercased()
        return s.contains("acros") || s.contains("monochrome") || s.contains("sepia")
    }

    var wbText: String? {
        guard var wb = whiteBalance else { return nil }
        if let k = kelvin, wb == "Kelvin" { wb = "\(k)K" }
        let r = wbShiftRed ?? 0, b = wbShiftBlue ?? 0
        if r != 0 || b != 0 { wb += ", \(FilmRecipe.signed(r)) Red & \(FilmRecipe.signed(b)) Blue" }
        return wb
    }

    /// Ordered (label, value) rows — the format Fuji X Weekly–style recipes are shared in.
    var rows: [(String, String)] {
        var r: [(String, String)] = [("Film Simulation", filmSimulation)]
        if let v = grainEffect { r.append(("Grain Effect", v)) }
        if let v = colorChrome { r.append(("Color Chrome Effect", v)) }
        if let v = colorChromeBlue { r.append(("Color Chrome FX Blue", v)) }
        if let v = wbText { r.append(("White Balance", v)) }
        if let v = dynamicRange { r.append(("Dynamic Range", v)) }
        if let v = highlight { r.append(("Highlight", FilmRecipe.signed(v))) }
        if let v = shadow { r.append(("Shadow", FilmRecipe.signed(v))) }
        if let v = color, !isMonochrome { r.append(("Color", v)) }
        if isMonochrome, let wc = monoWarmCool { r.append(("Monochromatic Color", "WC \(FilmRecipe.signed(wc)) MG \(FilmRecipe.signed(monoMagentaGreen ?? 0))")) }
        if let v = sharpness { r.append(("Sharpness", v)) }
        if let v = noiseReduction { r.append(("High ISO NR", v)) }
        if let v = clarity { r.append(("Clarity", FilmRecipe.signed(v))) }
        return r
    }

    /// Stable signature used to group shots that share the same recipe.
    var signature: String { rows.map { "\($0.0)=\($0.1)" }.joined(separator: "|") }

    /// One-line summary for badges: "Classic Chrome · DR200 · H-1 S+1".
    var shortLine: String {
        var parts = [filmSimulation]
        if let dr = dynamicRange { parts.append(dr) }
        var tone = ""
        if let h = highlight { tone += "H\(FilmRecipe.signed(h))" }
        if let s = shadow { tone += (tone.isEmpty ? "" : " ") + "S\(FilmRecipe.signed(s))" }
        if !tone.isEmpty { parts.append(tone) }
        return parts.joined(separator: " · ")
    }

    static func signed(_ v: Int) -> String { v > 0 ? "+\(v)" : "\(v)" }
    static func signed(_ v: Double) -> String {
        let s = v.truncatingRemainder(dividingBy: 1) == 0 ? String(Int(v)) : String(format: "%.1f", v)
        return v > 0 ? "+" + s : s
    }
}

enum ShotFormat: String, Codable {
    case jpeg, heif, raf, cr3, tiffRaw, video, unknown

    var isRAW: Bool { self == .raf || self == .cr3 || self == .tiffRaw }

    static func from(filename: String) -> ShotFormat {
        switch (filename as NSString).pathExtension.lowercased() {
        case "jpg", "jpeg": return .jpeg
        case "heic", "heif", "hif": return .heif
        case "raf": return .raf
        case "cr3": return .cr3
        case "arw", "nef", "nrw", "dng", "cr2", "orf", "rw2", "pef", "srw", "3fr", "iiq", "rwl", "srf", "sr2", "erf", "x3f": return .tiffRaw
        case "mov", "mp4", "mts", "m4v", "avi": return .video
        default: return .unknown
        }
    }
}
