import Foundation

/// Decodes the Fujifilm maker note into a shareable film recipe.
/// Layout: "FUJIFILM" + u32 LE offset to IFD; IFD is little-endian, offsets relative to the note start.
/// Tag IDs and value tables follow ExifTool's FujiFilm table.
enum FujiMakerNote {
    enum Tag {
        static let sharpness: UInt16 = 0x1001
        static let whiteBalance: UInt16 = 0x1002
        static let saturation: UInt16 = 0x1003
        static let colorTemperature: UInt16 = 0x1005
        static let wbFineTune: UInt16 = 0x100A
        static let noiseReduction: UInt16 = 0x100E
        static let clarity: UInt16 = 0x100F
        static let shadowTone: UInt16 = 0x1040
        static let highlightTone: UInt16 = 0x1041
        static let grainRoughness: UInt16 = 0x1047
        static let colorChrome: UInt16 = 0x1048
        static let bwWarmCool: UInt16 = 0x1049
        static let bwMagentaGreen: UInt16 = 0x104B
        static let grainSize: UInt16 = 0x104C
        static let colorChromeBlue: UInt16 = 0x104E
        static let filmMode: UInt16 = 0x1401
        static let dynamicRangeSetting: UInt16 = 0x1402
        static let developmentDR: UInt16 = 0x1403
        static let rating: UInt16 = 0x1431
    }

    static let filmModes: [Int: String] = [
        0x000: "Provia", 0x100: "Studio Portrait", 0x110: "Studio Portrait Enhanced Saturation",
        0x120: "Astia", 0x130: "Studio Portrait Sharp", 0x200: "Velvia", 0x300: "Studio Portrait Ex",
        0x400: "Velvia", 0x500: "Pro Neg. Std", 0x501: "Pro Neg. Hi", 0x600: "Classic Chrome",
        0x700: "Eterna", 0x800: "Classic Negative", 0x900: "Eterna Bleach Bypass",
        0xA00: "Nostalgic Neg.", 0xB00: "Reala Ace",
    ]

    /// Saturation values that actually encode a monochrome film simulation.
    static let monoModes: [Int: String] = [
        0x300: "Monochrome", 0x301: "Monochrome + R", 0x302: "Monochrome + Ye", 0x303: "Monochrome + G",
        0x310: "Sepia", 0x500: "Acros", 0x501: "Acros + R", 0x502: "Acros + Ye", 0x503: "Acros + G",
    ]

    static let colorLevels: [Int: String] = [
        0x000: "0", 0x080: "+1", 0x100: "+2", 0x0C0: "+3", 0x0E0: "+4",
        0x180: "-1", 0x400: "-2", 0x4C0: "-3", 0x4E0: "-4", 0x200: "Low",
    ]

    static let sharpnessLevels: [Int: String] = [
        0x00: "-4", 0x01: "-3", 0x02: "-2", 0x03: "0", 0x04: "+2", 0x05: "+3", 0x06: "+4",
        0x82: "-1", 0x84: "+1",
    ]

    static let nrLevels: [Int: String] = [
        0x000: "0", 0x100: "+2", 0x180: "+1", 0x1C0: "+3", 0x1E0: "+4",
        0x200: "-2", 0x280: "-1", 0x2C0: "-3", 0x2E0: "-4",
    ]

    static let whiteBalances: [Int: String] = [
        0x000: "Auto", 0x001: "Auto (White Priority)", 0x002: "Auto (Ambience Priority)",
        0x100: "Daylight", 0x200: "Cloudy", 0x300: "Fluorescent 1", 0x301: "Fluorescent 2",
        0x302: "Fluorescent 3", 0x303: "Fluorescent 4", 0x304: "Fluorescent 5",
        0x400: "Incandescent", 0x500: "Flash", 0x600: "Underwater",
        0xF00: "Custom 1", 0xF01: "Custom 2", 0xF02: "Custom 3", 0xF03: "Custom 4", 0xF04: "Custom 5",
        0xFF0: "Kelvin",
    ]

    static func effect(_ v: Int?) -> String? {
        switch v { case 0: return "Off"; case 32: return "Weak"; case 64: return "Strong"; default: return nil }
    }

    /// GrainEffectRoughness (0x1047). Some bodies (e.g. X-HF1) fold the size into this tag:
    /// ExifTool lists 16 = Weak/Small and 48 = Strong/Small.
    static func grainRoughness(_ v: Int?) -> (rough: String, impliedSize: String?)? {
        switch v {
        case 16: return ("Weak", "Small")
        case 48: return ("Strong", "Small")
        default: return effect(v).map { ($0, nil) }
        }
    }

    /// Returns the recipe (nil if the note carries no film-simulation info) and the in-camera rating.
    static func parse(_ v: ByteView, at start: Int) -> (FilmRecipe?, Int?) {
        guard v.matches(start, "FUJIFILM"), let ifd = v.u32(start + 8, little: true) else { return (nil, nil) }
        let t = TIFFStructure(v, base: start, little: true)
        let e = t.entries(ifdAt: Int(ifd))
        guard !e.isEmpty else { return (nil, nil) }

        let rating = t.int(e[tag: Tag.rating])
        let saturation = t.int(e[tag: Tag.saturation])

        let sim: String
        if let s = saturation, let mono = monoModes[s] {
            sim = mono
        } else if let fm = t.int(e[tag: Tag.filmMode]) {
            // Values newer than the table (future firmware) are shown as-is, never guessed.
            sim = filmModes[fm] ?? "Film sim 0x\(String(fm, radix: 16, uppercase: true))"
        } else {
            // No FilmMode tag (e.g. the original X100, per ExifTool): we can't tell the simulation.
            return (nil, rating)
        }

        var r = FilmRecipe(filmSimulation: sim)

        // Grain: roughness + size (size tag only exists on newer bodies).
        if let (rough, implied) = grainRoughness(t.int(e[tag: Tag.grainRoughness])) {
            if rough == "Off" {
                r.grainEffect = "Off"
            } else {
                let size: String? = { switch t.int(e[tag: Tag.grainSize]) { case 16: return "Small"; case 32: return "Large"; default: return implied } }()
                r.grainEffect = [rough, size].compactMap { $0 }.joined(separator: ", ")
            }
        }
        r.colorChrome = effect(t.int(e[tag: Tag.colorChrome]))
        r.colorChromeBlue = effect(t.int(e[tag: Tag.colorChromeBlue]))

        if let wb = t.int(e[tag: Tag.whiteBalance]) {
            r.whiteBalance = whiteBalances[wb] ?? "WB \(String(wb, radix: 16))"
            if wb == 0xFF0 { r.kelvin = t.int(e[tag: Tag.colorTemperature]) }
        }
        if let ft = e[tag: Tag.wbFineTune], ft.count >= 2,
           let red = t.int(ft, index: 0).map({ Int32(truncatingIfNeeded: $0) }),
           let blue = t.int(ft, index: 1).map({ Int32(truncatingIfNeeded: $0) }) {
            // Newer bodies store 20 × the in-camera ±9 step (ExifTool 0x100A). Like Exiv2, only
            // scale when both values are whole multiples of 20; otherwise keep the raw numbers.
            let scaled = red % 20 == 0 && blue % 20 == 0
            r.wbShiftRed = scaled ? Int(red) / 20 : Int(red)
            r.wbShiftBlue = scaled ? Int(blue) / 20 : Int(blue)
        }

        let drSetting = t.int(e[tag: Tag.dynamicRangeSetting])
        if drSetting == 0 {
            r.dynamicRange = "DR-Auto"
        } else if let dr = t.int(e[tag: Tag.developmentDR]), dr >= 100 {
            r.dynamicRange = "DR\(dr)"
        }

        // Tones: stored as -16 × value, half steps allowed (-8 = +0.5).
        if let h = t.int(e[tag: Tag.highlightTone]).map({ Int32(truncatingIfNeeded: $0) }) { r.highlight = -Double(h) / 16 }
        if let s = t.int(e[tag: Tag.shadowTone]).map({ Int32(truncatingIfNeeded: $0) }) { r.shadow = -Double(s) / 16 }

        if let s = saturation, monoModes[s] == nil { r.color = colorLevels[s] }
        if let s = t.int(e[tag: Tag.sharpness]) { r.sharpness = sharpnessLevels[s] }
        if let n = t.int(e[tag: Tag.noiseReduction]) { r.noiseReduction = nrLevels[n] }
        if let c = t.int(e[tag: Tag.clarity]).map({ Int32(truncatingIfNeeded: $0) }) { r.clarity = Int(c) / 1000 }
        if r.isMonochrome {
            // Both are signed bytes (ExifTool int8s, Exiv2 casts to int8_t): -2 is stored as 0xFE.
            r.monoWarmCool = t.int(e[tag: Tag.bwWarmCool]).map { Int(Int8(truncatingIfNeeded: $0)) }
            r.monoMagentaGreen = t.int(e[tag: Tag.bwMagentaGreen]).map { Int(Int8(truncatingIfNeeded: $0)) }
        }
        return (r, rating)
    }
}
