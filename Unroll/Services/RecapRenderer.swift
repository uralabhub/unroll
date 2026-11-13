import UIKit
import MapKit

/// "Photo Walk Recap" — the shareable story card (1080×1920) that doubles as our growth loop.
struct RecapInput {
    var title: String
    var subtitle: String
    var photos: [UIImage]
    var shotCount: Int
    var keeperCount: Int
    var distanceKm: Double?
    var duration: TimeInterval?
    var cameras: [String]
    var looks: [String]
    var route: [CLLocationCoordinate2D]
    var watermark: Bool
}

enum RecapRenderer {
    static let size = CGSize(width: 1080, height: 1920)

    static func mapSnapshot(route: [CLLocationCoordinate2D], size: CGSize) async -> UIImage? {
        guard route.count >= 2 else { return nil }
        let poly = MKPolyline(coordinates: route, count: route.count)
        var rect = poly.boundingMapRect
        let padX = max(rect.size.width * 0.25, 600), padY = max(rect.size.height * 0.25, 600)
        rect = rect.insetBy(dx: -padX, dy: -padY)
        let o = MKMapSnapshotter.Options()
        o.mapRect = rect
        o.size = size
        o.scale = 1
        o.traitCollection = UITraitCollection(userInterfaceStyle: .dark)
        o.pointOfInterestFilter = .excludingAll
        guard let snap = try? await MKMapSnapshotter(options: o).start() else { return nil }
        let f = UIGraphicsImageRendererFormat(); f.scale = 1
        return UIGraphicsImageRenderer(size: size, format: f).image { ctx in
            snap.image.draw(at: .zero)
            let c = ctx.cgContext
            let pts = route.map { snap.point(for: $0) }
            c.setLineCap(.round); c.setLineJoin(.round)
            c.setStrokeColor(UIColor.black.withAlphaComponent(0.5).cgColor); c.setLineWidth(14)
            c.addLines(between: pts); c.strokePath()
            c.setStrokeColor(UIColor(red: 1, green: 0.66, blue: 0.25, alpha: 1).cgColor); c.setLineWidth(8)
            c.addLines(between: pts); c.strokePath()
            for (p, col) in [(pts.first!, UIColor.white), (pts.last!, UIColor(red: 1, green: 0.66, blue: 0.25, alpha: 1))] {
                col.setFill(); c.fillEllipse(in: CGRect(x: p.x - 12, y: p.y - 12, width: 24, height: 24))
            }
        }
    }

    static func render(_ r: RecapInput, map: UIImage?) -> UIImage {
        let S = size
        let f = UIGraphicsImageRendererFormat(); f.scale = 1; f.opaque = true
        return UIGraphicsImageRenderer(size: S, format: f).image { ctx in
            let c = ctx.cgContext
            // Background
            let bg = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(),
                                colors: [UIColor(red: 0.07, green: 0.07, blue: 0.08, alpha: 1).cgColor,
                                         UIColor(red: 0.13, green: 0.10, blue: 0.08, alpha: 1).cgColor] as CFArray, locations: nil)!
            c.drawLinearGradient(bg, start: .zero, end: CGPoint(x: 0, y: S.height), options: [])
            let ink = UIColor(white: 0.96, alpha: 1), sub = UIColor(white: 0.96, alpha: 0.55)
            let amber = UIColor(red: 1, green: 0.66, blue: 0.25, alpha: 1)

            FrameRenderer.draw("PHOTO WALK", at: CGPoint(x: 72, y: 110), size: 30, weight: .bold, color: amber, align: .left, kern: 6)
            FrameRenderer.draw(r.title, at: CGPoint(x: 72, y: 175), size: 64, weight: .bold, color: ink, align: .left)
            FrameRenderer.draw(r.subtitle, at: CGPoint(x: 72, y: 240), size: 32, weight: .regular, color: sub, align: .left)

            // Photo grid
            let gridTop: CGFloat = 300, gap: CGFloat = 14, side: CGFloat = 72
            let photos = Array(r.photos.prefix(6))
            let cols = photos.count <= 1 ? 1 : 2
            let rows = max(1, Int(ceil(Double(photos.count) / Double(cols))))
            let gridH: CGFloat = map == nil ? 1080 : 820
            let cellW = (S.width - side * 2 - gap * CGFloat(cols - 1)) / CGFloat(cols)
            let cellH = (gridH - gap * CGFloat(rows - 1)) / CGFloat(rows)
            for (i, img) in photos.enumerated() {
                let col = i % cols, row = i / cols
                // A lone last photo spans the full row.
                let span = (i == photos.count - 1 && photos.count % cols == 1 && cols == 2)
                let rect = CGRect(x: side + CGFloat(col) * (cellW + gap), y: gridTop + CGFloat(row) * (cellH + gap),
                                  width: span ? S.width - side * 2 : cellW, height: cellH)
                c.saveGState()
                UIBezierPath(roundedRect: rect, cornerRadius: 22).addClip()
                drawAspectFill(img.normalized(), in: rect)
                c.restoreGState()
            }

            var y = gridTop + gridH + 36
            if let map {
                let mr = CGRect(x: side, y: y, width: S.width - side * 2, height: 330)
                c.saveGState(); UIBezierPath(roundedRect: mr, cornerRadius: 22).addClip(); map.draw(in: mr); c.restoreGState()
                y = mr.maxY + 40
            }

            // Stats row
            var stats: [(String, String)] = [("\(r.shotCount)", "shots"), ("\(r.keeperCount)", "keepers")]
            if let d = r.distanceKm, d > 0.05 { stats.append((String(format: "%.1f", d), "km")) }
            if let t = r.duration, t > 60 {
                let h = Int(t) / 3600, m = Int(t) % 3600 / 60
                stats.append((h > 0 ? "\(h)h\(m)" : "\(m)m", "walked"))
            }
            let colW = (S.width - side * 2) / CGFloat(stats.count)
            for (i, s) in stats.enumerated() {
                let x = side + colW * CGFloat(i)
                FrameRenderer.draw(s.0, at: CGPoint(x: x, y: y + 22), size: 54, weight: .bold, color: ink, align: .left)
                FrameRenderer.draw(s.1.uppercased(), at: CGPoint(x: x, y: y + 72), size: 22, weight: .semibold, color: sub, align: .left, kern: 3)
            }
            y += 130

            // Looks chips
            var x = side
            for look in r.looks.prefix(4) {
                let font = UIFont.systemFont(ofSize: 26, weight: .semibold)
                let w = (look as NSString).size(withAttributes: [.font: font]).width + 40
                if x + w > S.width - side { break }
                let chip = CGRect(x: x, y: y, width: w, height: 52)
                amber.withAlphaComponent(0.16).setFill()
                UIBezierPath(roundedRect: chip, cornerRadius: 26).fill()
                FrameRenderer.draw(look, at: CGPoint(x: chip.midX, y: chip.midY), size: 26, weight: .semibold, color: amber, align: .center)
                x += w + 12
            }
            FrameRenderer.draw(r.cameras.joined(separator: " + "), at: CGPoint(x: side, y: S.height - 90), size: 28, weight: .medium, color: sub, align: .left)
            if r.watermark {
                FrameRenderer.draw("made with Unroll", at: CGPoint(x: S.width - side, y: S.height - 90), size: 26, weight: .semibold, color: sub, align: .right)
            }
        }
    }

    static func drawAspectFill(_ img: UIImage, in rect: CGRect) {
        let s = max(rect.width / img.size.width, rect.height / img.size.height)
        let w = img.size.width * s, h = img.size.height * s
        img.draw(in: CGRect(x: rect.midX - w / 2, y: rect.midY - h / 2, width: w, height: h))
    }
}
