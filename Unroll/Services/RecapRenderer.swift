import UIKit
import CoreImage
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
            grayscale(snap.image).draw(at: .zero)
            let c = ctx.cgContext
            let pts = route.map { snap.point(for: $0) }
            c.setLineCap(.round); c.setLineJoin(.round)
            c.setStrokeColor(UIColor.black.withAlphaComponent(0.5).cgColor); c.setLineWidth(14)
            c.addLines(between: pts); c.strokePath()
            c.setStrokeColor(UIColor.white.cgColor); c.setLineWidth(6)
            c.addLines(between: pts); c.strokePath()
            UIColor.white.setFill()
            c.fillEllipse(in: CGRect(x: pts.first!.x - 10, y: pts.first!.y - 10, width: 20, height: 20))
            c.fill(CGRect(x: pts.last!.x - 10, y: pts.last!.y - 10, width: 20, height: 20))
        }
    }

    static func render(_ r: RecapInput, map: UIImage?) -> UIImage {
        let S = size
        let f = UIGraphicsImageRendererFormat(); f.scale = 1; f.opaque = true
        return UIGraphicsImageRenderer(size: S, format: f).image { ctx in
            let c = ctx.cgContext
            // Background
            UIColor.black.setFill(); c.fill(CGRect(origin: .zero, size: S))
            let ink = UIColor(white: 0.96, alpha: 1), sub = UIColor(white: 0.96, alpha: 0.5)
            let monoFont = { (size: CGFloat, w: UIFont.Weight) in UIFont.monospacedSystemFont(ofSize: size, weight: w) }

            NSAttributedString(string: "PHOTO WALK", attributes: [.font: monoFont(26, .medium), .foregroundColor: sub, .kern: 5])
                .draw(at: CGPoint(x: 72, y: 92))
            FrameRenderer.draw(r.title, at: CGPoint(x: 72, y: 175), size: 64, weight: .bold, color: ink, align: .left)
            FrameRenderer.draw(r.subtitle, at: CGPoint(x: 72, y: 240), size: 32, weight: .regular, color: sub, align: .left)

            // Photo grid
            let gridTop: CGFloat = 300, gap: CGFloat = 8, side: CGFloat = 72
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
                UIBezierPath(rect: rect).addClip()
                drawAspectFill(img.normalized(), in: rect)
                c.restoreGState()
            }

            var y = gridTop + gridH + 36
            if let map {
                let mr = CGRect(x: side, y: y, width: S.width - side * 2, height: 330)
                c.saveGState(); UIBezierPath(rect: mr).addClip(); map.draw(in: mr); c.restoreGState()
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
                NSAttributedString(string: s.0, attributes: [.font: monoFont(52, .medium), .foregroundColor: ink]).draw(at: CGPoint(x: x, y: y - 8))
                NSAttributedString(string: s.1.uppercased(), attributes: [.font: monoFont(20, .medium), .foregroundColor: sub, .kern: 3])
                    .draw(at: CGPoint(x: x, y: y + 62))
            }
            y += 130

            // Looks chips
            var x = side
            for look in r.looks.prefix(4) {
                let font = Montserrat.uiFont(26, .medium)
                let w = (look as NSString).size(withAttributes: [.font: font]).width + 40
                if x + w > S.width - side { break }
                let chip = CGRect(x: x, y: y, width: w, height: 52)
                sub.setStroke()
                let path = UIBezierPath(roundedRect: chip.insetBy(dx: 1, dy: 1), cornerRadius: 26); path.lineWidth = 2; path.stroke()
                FrameRenderer.draw(look, at: CGPoint(x: chip.midX, y: chip.midY), size: 26, weight: .medium, color: ink, align: .center)
                x += w + 12
            }
            FrameRenderer.draw(r.cameras.joined(separator: " + "), at: CGPoint(x: side, y: S.height - 90), size: 28, weight: .medium, color: sub, align: .left)
            if r.watermark {
                FrameRenderer.draw("made with Unroll", at: CGPoint(x: S.width - side, y: S.height - 90), size: 26, weight: .semibold, color: sub, align: .right)
            }
        }
    }

    /// Map tiles in black and white so the card stays monochrome.
    static func grayscale(_ img: UIImage) -> UIImage {
        guard let ci = CIImage(image: img), let f = CIFilter(name: "CIPhotoEffectMono") else { return img }
        f.setValue(ci, forKey: kCIInputImageKey)
        guard let out = f.outputImage, let cg = CIContext().createCGImage(out, from: out.extent) else { return img }
        return UIImage(cgImage: cg, scale: img.scale, orientation: img.imageOrientation)
    }

    static func drawAspectFill(_ img: UIImage, in rect: CGRect) {
        let s = max(rect.width / img.size.width, rect.height / img.size.height)
        let w = img.size.width * s, h = img.size.height * s
        img.draw(in: CGRect(x: rect.midX - w / 2, y: rect.midY - h / 2, width: w, height: h))
    }
}
