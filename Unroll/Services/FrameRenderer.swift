import UIKit

enum FrameStyle: String, CaseIterable, Identifiable, Codable {
    case gallery, caption, polaroid, recipe, film, minimal
    var id: String { rawValue }
    var label: String {
        switch self {
        case .gallery: return "Gallery"
        case .caption: return "Caption Bar"
        case .polaroid: return "Instant"
        case .recipe: return "Recipe Card"
        case .film: return "Film Strip"
        case .minimal: return "Thin"
        }
    }
    var isPro: Bool { self == .recipe || self == .film || self == .polaroid }
}

enum FrameAspect: String, CaseIterable, Identifiable, Codable {
    case original, square, portrait, story
    var id: String { rawValue }
    var label: String {
        switch self { case .original: return "Fit"; case .square: return "1:1"; case .portrait: return "4:5"; case .story: return "9:16" }
    }
    var ratio: CGFloat? {
        switch self { case .original: return nil; case .square: return 1; case .portrait: return 4.0 / 5.0; case .story: return 9.0 / 16.0 }
    }
}

struct FrameOptions: Equatable, Codable {
    var style: FrameStyle = .gallery
    var aspect: FrameAspect = .original
    var dark = false
    var showExposure = true
    var showRecipe = true
    var showDate = false
    var showPlace = false
    var placeName: String?
    var watermark = true
}

/// Renders a framed export at the image's full resolution.
enum FrameRenderer {
    static func render(_ image: UIImage, meta: ShotMeta?, options o: FrameOptions) -> UIImage {
        let img = image.normalized()
        let W = img.size.width, H = img.size.height
        let u = max(W, H) / 100                                     // 1% of the long edge
        let bg: UIColor = o.style == .film ? UIColor(white: 0.06, alpha: 1) : (o.dark ? UIColor(white: 0.07, alpha: 1) : UIColor(white: 0.985, alpha: 1))
        let ink: UIColor = (o.dark || o.style == .film) ? UIColor(white: 0.95, alpha: 1) : UIColor(white: 0.08, alpha: 1)
        let sub = ink.withAlphaComponent(0.55)

        // Borders (left, top, right, bottom) in units of u.
        var pad: (l: CGFloat, t: CGFloat, r: CGFloat, b: CGFloat)
        switch o.style {
        case .gallery:  pad = (4, 4, 4, 13)
        case .caption:  pad = (0, 0, 0, 9)
        case .polaroid: pad = (4.5, 4.5, 4.5, 20)
        case .recipe:   pad = (4, 4, 4, CGFloat(meta?.recipe?.rows.count ?? 3) * 2.6 + 12)
        case .film:     pad = (3, 9, 3, 13)
        case .minimal:  pad = (2.5, 2.5, 2.5, 2.5)
        }
        var canvas = CGSize(width: W + (pad.l + pad.r) * u, height: H + (pad.t + pad.b) * u)
        var origin = CGPoint(x: pad.l * u, y: pad.t * u)
        if let ratio = o.aspect.ratio {                              // grow the shorter side to hit the ratio
            if canvas.width / canvas.height > ratio {
                let h = canvas.width / ratio
                origin.y += (h - canvas.height) / 2 * (pad.b > pad.t ? 0.6 : 1)
                canvas.height = h
            } else {
                let w = canvas.height * ratio
                origin.x += (w - canvas.width) / 2
                canvas.width = w
            }
        }

        let fmt = UIGraphicsImageRendererFormat()
        fmt.scale = 1
        fmt.opaque = true
        return UIGraphicsImageRenderer(size: canvas, format: fmt).image { ctx in
            bg.setFill()
            ctx.fill(CGRect(origin: .zero, size: canvas))
            let photoRect = CGRect(origin: origin, size: img.size)
            img.draw(in: photoRect)

            let below = CGRect(x: photoRect.minX, y: photoRect.maxY, width: photoRect.width, height: canvas.height - photoRect.maxY)
            let camera = meta?.cameraName ?? "Camera"
            let lens = meta?.lens
            let exposure = o.showExposure ? (meta?.exposureLine ?? "") : ""
            let recipeLine = (o.showRecipe ? meta?.recipe?.shortLine ?? meta?.look : nil)
            let dateText: String? = o.showDate ? meta?.captureDate().map { $0.formatted(date: .abbreviated, time: .omitted) } : nil
            let place = o.showPlace ? o.placeName : nil

            switch o.style {
            case .gallery, .polaroid:
                let center = below.minY + (o.style == .polaroid ? 7 : 5.5) * u
                draw(camera, at: CGPoint(x: canvas.width / 2, y: center - 1.8 * u), size: 2.9 * u, weight: .semibold, color: ink, align: .center)
                let line2 = [exposure, recipeLine].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: "   ·   ")
                draw(line2, at: CGPoint(x: canvas.width / 2, y: center + 2.1 * u), size: 1.85 * u, weight: .regular, color: sub, align: .center)
                let line3 = [lens, place, dateText].compactMap { $0 }.joined(separator: "   ·   ")
                if !line3.isEmpty {
                    draw(line3, at: CGPoint(x: canvas.width / 2, y: center + 5.0 * u), size: 1.55 * u, weight: .regular, color: sub, align: .center)
                }
            case .caption:
                let y = below.minY + below.height / 2
                draw(camera, at: CGPoint(x: photoRect.minX + 3 * u, y: y - 1.3 * u), size: 2.2 * u, weight: .bold, color: ink, align: .left)
                draw([lens, place].compactMap { $0 }.joined(separator: " · "), at: CGPoint(x: photoRect.minX + 3 * u, y: y + 1.7 * u), size: 1.4 * u, weight: .regular, color: sub, align: .left)
                draw(exposure, at: CGPoint(x: photoRect.maxX - 3 * u, y: y - 1.3 * u), size: 1.9 * u, weight: .semibold, color: ink, align: .right)
                draw([recipeLine, dateText].compactMap { $0 }.joined(separator: " · "), at: CGPoint(x: photoRect.maxX - 3 * u, y: y + 1.7 * u), size: 1.4 * u, weight: .regular, color: sub, align: .right)
                // divider
                sub.withAlphaComponent(0.3).setFill()
                ctx.fill(CGRect(x: canvas.width / 2 + 2 * u, y: y - 2.4 * u, width: max(1, u * 0.12), height: 4.8 * u).offsetBy(dx: 0, dy: 0).insetBy(dx: 0, dy: 0))
            case .recipe:
                var y = below.minY + 4 * u
                draw(camera + (lens.map { "  ·  " + $0 } ?? ""), at: CGPoint(x: photoRect.minX, y: y), size: 2.2 * u, weight: .semibold, color: ink, align: .left)
                draw(exposure, at: CGPoint(x: photoRect.maxX, y: y), size: 1.7 * u, weight: .regular, color: sub, align: .right)
                y += 4 * u
                if let rows = meta?.recipe?.rows {
                    for (k, v) in rows {
                        draw(k.uppercased(), at: CGPoint(x: photoRect.minX, y: y), size: 1.25 * u, weight: .medium, color: sub, align: .left, kern: 0.15 * u)
                        draw(v, at: CGPoint(x: photoRect.maxX, y: y), size: 1.5 * u, weight: .medium, color: ink, align: .right)
                        y += 2.6 * u
                    }
                } else if let look = meta?.look {
                    draw(look, at: CGPoint(x: photoRect.minX, y: y), size: 1.5 * u, weight: .medium, color: ink, align: .left)
                }
            case .film:
                // Sprocket holes + edge print.
                let hole = CGSize(width: 2.2 * u, height: 3.2 * u)
                UIColor(white: 0.85, alpha: 1).setFill()
                var x = 1.5 * u
                while x < canvas.width - hole.width {
                    UIBezierPath(roundedRect: CGRect(x: x, y: 2.9 * u, width: hole.width, height: hole.height), cornerRadius: 0.4 * u).fill()
                    UIBezierPath(roundedRect: CGRect(x: x, y: canvas.height - 6.1 * u, width: hole.width, height: hole.height), cornerRadius: 0.4 * u).fill()
                    x += 4.4 * u
                }
                let amber = UIColor(red: 1, green: 0.62, blue: 0.2, alpha: 0.95)
                let edge = [meta?.recipe?.filmSimulation.uppercased(), meta?.isoText].compactMap { $0 }.joined(separator: "  ")
                draw(edge.isEmpty ? camera.uppercased() : edge, at: CGPoint(x: photoRect.minX + u, y: photoRect.minY - 1.6 * u), size: 1.3 * u, weight: .bold, color: amber, align: .left, kern: 0.3 * u)
                draw([camera, exposure].joined(separator: "   "), at: CGPoint(x: photoRect.minX + u, y: photoRect.maxY + 2 * u), size: 1.3 * u, weight: .medium, color: amber, align: .left, kern: 0.15 * u)
            case .minimal:
                break
            }

            if o.watermark {
                let wm = "Unroll"
                if o.style == .minimal || o.style == .film {
                    draw(wm, at: CGPoint(x: canvas.width - 1.5 * u, y: canvas.height - 1.2 * u), size: 1.0 * u, weight: .medium, color: sub, align: .right)
                } else {
                    draw(wm, at: CGPoint(x: canvas.width - 2 * u, y: canvas.height - 2 * u), size: 1.1 * u, weight: .medium, color: sub.withAlphaComponent(0.4), align: .right)
                }
            }
        }
    }

    enum Align { case left, center, right }

    /// Draws single-line text vertically centred on `p.y`.
    static func draw(_ s: String, at p: CGPoint, size: CGFloat, weight: UIFont.Weight, color: UIColor, align: Align, kern: CGFloat = 0) {
        guard !s.isEmpty else { return }
        let font = Mono.uiSans(size, weight: weight)
        let attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: color, .kern: kern]
        let str = NSAttributedString(string: s, attributes: attrs)
        let sz = str.size()
        let x: CGFloat = align == .left ? p.x : (align == .center ? p.x - sz.width / 2 : p.x - sz.width)
        str.draw(at: CGPoint(x: x, y: p.y - sz.height / 2))
    }
}

extension UIImage {
    /// Bakes orientation into pixels so Core Graphics drawing is upright.
    func normalized() -> UIImage {
        guard imageOrientation != .up else { return self }
        let f = UIGraphicsImageRendererFormat()
        f.scale = 1
        return UIGraphicsImageRenderer(size: CGSize(width: size.width * scale, height: size.height * scale), format: f).image { _ in
            draw(in: CGRect(origin: .zero, size: CGSize(width: size.width * scale, height: size.height * scale)))
        }
    }
}
