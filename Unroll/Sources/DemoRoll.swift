import UIKit
import CoreLocation

/// Generates a realistic fake SD card (DCIM/100_FUJI) so the full pipeline — header parsing,
/// recipes, ratings, protect flags, RAW pairing, geotagging, frames, recap — can be tried
/// without a camera (and in the Simulator). Files carry real EXIF + Fujifilm maker notes.
enum DemoRoll {
    struct Look {
        let filmMode: Int?        // nil → monochrome via saturation
        let saturation: Int
        let highlight: Int, shadow: Int
        let grain: Int, grainSize: Int, cce: Int, ccb: Int
        let wb: Int, red: Int, blue: Int
        let dr: Int
        let tint: UIColor
        var mono: Bool { filmMode == nil }
    }

    static let looks: [Look] = [
        Look(filmMode: 0x600, saturation: 0x400, highlight: 16, shadow: -16, grain: 32, grainSize: 16, cce: 64, ccb: 32, wb: 0x000, red: 40, blue: -100, dr: 200, tint: UIColor(red: 0.85, green: 0.78, blue: 0.62, alpha: 1)),
        Look(filmMode: 0x800, saturation: 0x100, highlight: -32, shadow: -32, grain: 64, grainSize: 16, cce: 64, ccb: 0, wb: 0x200, red: 60, blue: -80, dr: 400, tint: UIColor(red: 0.95, green: 0.72, blue: 0.55, alpha: 1)),
        Look(filmMode: nil, saturation: 0x501, highlight: -16, shadow: -48, grain: 64, grainSize: 32, cce: 0, ccb: 0, wb: 0x000, red: 0, blue: 0, dr: 200, tint: .white),
        Look(filmMode: 0xA00, saturation: 0x080, highlight: 0, shadow: -8, grain: 32, grainSize: 16, cce: 32, ccb: 32, wb: 0x100, red: 40, blue: -60, dr: 400, tint: UIColor(red: 1.0, green: 0.82, blue: 0.6, alpha: 1)),
        Look(filmMode: 0xB00, saturation: 0x000, highlight: 0, shadow: 0, grain: 0, grainSize: 0, cce: 32, ccb: 32, wb: 0x000, red: 0, blue: 0, dr: 100, tint: UIColor(red: 0.8, green: 0.88, blue: 0.95, alpha: 1)),
    ]

    /// A walk through District 1, Saigon — matches the demo timestamps.
    static let route: [CLLocationCoordinate2D] = [
        .init(latitude: 10.7769, longitude: 106.7009), .init(latitude: 10.7798, longitude: 106.6990),
        .init(latitude: 10.7820, longitude: 106.6958), .init(latitude: 10.7797, longitude: 106.6922),
        .init(latitude: 10.7745, longitude: 106.6980), .init(latitude: 10.7725, longitude: 106.6980),
        .init(latitude: 10.7712, longitude: 106.7040), .init(latitude: 10.7740, longitude: 106.7055),
        .init(latitude: 10.7769, longitude: 106.7009),
    ]

    static let count = 42
    static var folder: URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("DemoRoll", isDirectory: true)
    }

    static func startOfWalk(now: Date = Date()) -> Date {
        Calendar.current.date(bySettingHour: 7, minute: 30, second: 0, of: now) ?? now
    }

    static func timestamp(_ i: Int, now: Date = Date()) -> Date {
        startOfWalk(now: now).addingTimeInterval(TimeInterval(i) * 9 * 60 + TimeInterval((i * 37) % 120))
    }

    static func coordinate(at t: Date, now: Date = Date()) -> CLLocationCoordinate2D {
        let total = TimeInterval(count) * 9 * 60
        let f = max(0, min(1, t.timeIntervalSince(startOfWalk(now: now)) / total)) * Double(route.count - 1)
        let i = min(route.count - 2, Int(f)), r = f - Double(i)
        let a = route[i], b = route[i + 1]
        return .init(latitude: a.latitude + (b.latitude - a.latitude) * r, longitude: a.longitude + (b.longitude - a.longitude) * r)
    }

    /// Writes the roll (idempotent per day) and returns its root folder.
    static func prepare() async -> URL {
        await Task.detached(priority: .userInitiated) { () -> URL in
            let root = folder
            let dir = root.appendingPathComponent("DCIM/100_FUJI", isDirectory: true)
            let stamp = root.appendingPathComponent(".day")
            let today = ISO8601DateFormatter().string(from: Calendar.current.startOfDay(for: Date()))
            if (try? String(contentsOf: stamp, encoding: .utf8)) == today { return root }
            try? FileManager.default.removeItem(at: root)
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            for i in 0..<count { writeShot(i, into: dir) }
            try? today.write(to: stamp, atomically: true, encoding: .utf8)
            return root
        }.value
    }

    private static func writeShot(_ i: Int, into dir: URL) {
        let look = looks[(i / 3) % looks.count]
        let date = timestamp(i)
        let isXT5 = i % 4 == 3
        let size = isXT5 ? CGSize(width: 1200, height: 800) : CGSize(width: 1200, height: 800)
        let portrait = i % 5 == 2
        let canvas = portrait ? CGSize(width: size.height, height: size.width) : size
        let image = paint(i, look: look, size: canvas)
        guard let base = image.jpegData(compressionQuality: 0.86) else { return }

        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy:MM:dd HH:mm:ss"
        let offset = TimeZone.current.secondsFromGMT(for: date)
        let offStr = String(format: "%@%02d:%02d", offset >= 0 ? "+" : "-", abs(offset) / 3600, abs(offset) % 3600 / 60)

        let rating = [0, 0, 0, 4, 0, 0, 5, 0, 3, 0, 0, 0, 5][i % 13]
        var mn: [TIFFBuilder.Entry] = [
            .undefined(0x0000, Array("0130".utf8)),
            .short(0x1001, 0x82),
            .short(0x1002, look.wb),
            .short(0x1003, look.saturation),
            .slong(0x100A, [look.red, look.blue]),
            .short(0x100E, 0x2E0),
            .slong(0x100F, [isXT5 ? -2000 : 0]),
            .slong(0x1040, [look.shadow]),
            .slong(0x1041, [look.highlight]),
            .long(0x1047, look.grain),
            .long(0x1048, look.cce),
            .long(0x104C, look.grainSize),
            .long(0x104E, look.ccb),
            .short(0x1402, 0x100),
            .short(0x1403, look.dr),
            .long(0x1431, rating),
        ]
        if let fm = look.filmMode { mn.append(.short(0x1401, fm)) }

        let shutter = [125, 250, 500, 1000, 60, 2000][i % 6]
        let fnum = [20, 28, 40, 56, 80][i % 5]
        let iso = [125, 160, 200, 400, 800, 1600][i % 6]
        var ifd0: [TIFFBuilder.Entry] = [.ascii(0x010F, "FUJIFILM"), .ascii(0x0110, isXT5 ? "X-T5" : "X100VI"), .short(0x0112, 1), .ascii(0x0131, "Digital Camera X100VI Ver1.10")]
        ifd0.append(.ascii(0x0132, f.string(from: date)))
        var exif: [TIFFBuilder.Entry] = [
            .rational(0x829A, [(1, shutter)]),
            .rational(0x829D, [(fnum, 10)]),
            .short(0x8827, iso),
            .ascii(0x9003, f.string(from: date)),
            .ascii(0x9011, offStr),
            .srational(0x9204, (i % 3 == 0) ? -1 : 0, 3),
            .rational(0x920A, [(isXT5 ? 33 : 23, 1)]),
            .short(0xA405, isXT5 ? 50 : 35),
            .long(0xA002, Int(canvas.width)), .long(0xA003, Int(canvas.height)),
            .undefined(0x927C, TIFFBuilder.fujiMakerNote(mn)),
        ]
        if isXT5 { exif.append(.ascii(0xA434, "XF33mmF1.4 R LM WR")) }

        let tiff = TIFFBuilder.exifTIFF(ifd0: ifd0, exif: exif)
        let jpeg = TIFFBuilder.jpeg(base, withExif: tiff)
        let name = String(format: "DSCF%04d", 4120 + i)
        let jpgURL = dir.appendingPathComponent(name + ".JPG")
        try? jpeg.write(to: jpgURL)
        if i % 3 == 0 {
            let raf = dir.appendingPathComponent(name + ".RAF")
            try? TIFFBuilder.raf(embedding: jpeg).write(to: raf)
            try? FileManager.default.setAttributes([.modificationDate: date], ofItemAtPath: raf.path)
        }
        try? FileManager.default.setAttributes([.modificationDate: date], ofItemAtPath: jpgURL.path)
        if i % 7 == 5 {   // "Protect" on camera = read-only file
            try? FileManager.default.setAttributes([.posixPermissions: 0o444], ofItemAtPath: jpgURL.path)
        }
    }

    // MARK: Procedural "photos"

    private static func paint(_ i: Int, look: Look, size: CGSize) -> UIImage {
        var rng = SplitMix(seed: UInt64(i + 7) &* 0x9E3779B97F4A7C15)
        let fmt = UIGraphicsImageRendererFormat()
        fmt.scale = 1
        return UIGraphicsImageRenderer(size: size, format: fmt).image { ctx in
            let c = ctx.cgContext
            let hour = Double(i) / Double(count)            // morning → evening
            let skyTop = UIColor(hue: 0.58 - hour * 0.5, saturation: 0.55, brightness: 0.95 - hour * 0.35, alpha: 1)
            let skyLow = UIColor(hue: 0.08 + hour * 0.02, saturation: 0.35 + hour * 0.4, brightness: 1.0, alpha: 1)
            let horizon = size.height * CGFloat(0.55 + rng.next() * 0.15)
            drawGradient(c, [skyTop, skyLow], from: .zero, to: CGPoint(x: 0, y: horizon))

            // Sun
            let r = size.width * CGFloat(0.05 + rng.next() * 0.05)
            let sun = CGPoint(x: size.width * CGFloat(0.15 + rng.next() * 0.7), y: horizon * CGFloat(0.25 + hour * 0.6))
            UIColor(white: 1, alpha: 0.85).setFill()
            c.fillEllipse(in: CGRect(x: sun.x - r, y: sun.y - r, width: r * 2, height: r * 2))

            // City silhouette
            var x: CGFloat = 0
            let shade = UIColor(hue: 0.62, saturation: 0.35, brightness: 0.22 + CGFloat(rng.next()) * 0.1, alpha: 1)
            while x < size.width {
                let w = size.width * CGFloat(0.04 + rng.next() * 0.09)
                let h = size.height * CGFloat(0.12 + rng.next() * 0.38)
                shade.setFill()
                c.fill(CGRect(x: x, y: horizon - h, width: w, height: h + 2))
                UIColor(red: 1, green: 0.85, blue: 0.5, alpha: 0.5).setFill()
                var wy = horizon - h + 8
                while wy < horizon - 8 {
                    if rng.next() > 0.55 { c.fill(CGRect(x: x + w * 0.3, y: wy, width: w * 0.12, height: 5)) }
                    wy += 16
                }
                x += w + CGFloat(rng.next() * 6)
            }
            // Street
            drawGradient(c, [UIColor(white: 0.18, alpha: 1), UIColor(white: 0.05, alpha: 1)],
                         from: CGPoint(x: 0, y: horizon), to: CGPoint(x: 0, y: size.height))
            UIColor(white: 1, alpha: 0.25).setStroke()
            c.setLineWidth(4)
            c.move(to: CGPoint(x: size.width / 2, y: horizon)); c.addLine(to: CGPoint(x: size.width * 0.1, y: size.height)); c.strokePath()
            c.move(to: CGPoint(x: size.width / 2, y: horizon)); c.addLine(to: CGPoint(x: size.width * 0.9, y: size.height)); c.strokePath()

            // Film look
            if look.mono {
                c.setBlendMode(.saturation)
                UIColor.gray.setFill()
                c.fill(CGRect(origin: .zero, size: size))
            } else {
                c.setBlendMode(.multiply)
                look.tint.setFill()
                c.fill(CGRect(origin: .zero, size: size))
            }
            c.setBlendMode(.normal)
            // Grain
            if look.grain > 0 {
                for _ in 0..<Int(size.width * size.height / 900) {
                    UIColor(white: rng.next() > 0.5 ? 1 : 0, alpha: 0.06).setFill()
                    c.fill(CGRect(x: rng.next() * size.width, y: rng.next() * size.height, width: 2, height: 2))
                }
            }
        }
    }

    private static func drawGradient(_ c: CGContext, _ colors: [UIColor], from: CGPoint, to: CGPoint) {
        guard let g = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors.map(\.cgColor) as CFArray, locations: nil) else { return }
        c.saveGState()
        c.clip(to: CGRect(x: 0, y: from.y, width: 100_000, height: to.y - from.y))
        c.drawLinearGradient(g, start: from, end: to, options: [.drawsAfterEndLocation])
        c.restoreGState()
    }
}

struct SplitMix {
    var state: UInt64
    init(seed: UInt64) { state = seed }
    mutating func next() -> Double {
        state &+= 0x9E3779B97F4A7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9
        z = (z ^ (z >> 27)) &* 0x94D049BB133111EB
        z ^= z >> 31
        return Double(z >> 11) / Double(1 << 53)
    }
}
