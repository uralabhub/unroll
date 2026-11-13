import UIKit
import CoreImage
import CoreImage.CIFilterBuiltins
import Vision

/// Camera clocks drift (and forget DST / time zones), which silently breaks geotagging.
/// Fix: show a QR code containing the exact time; the user photographs it with the camera;
/// we read the QR back from that shot and compare it with the shot's EXIF time.
enum ClockSync {
    static let prefix = "UNROLL-CLOCK:"

    static func qr(for date: Date, size: CGFloat) -> UIImage? {
        let f = CIFilter.qrCodeGenerator()
        f.message = Data((prefix + String(Int(date.timeIntervalSince1970))).utf8)
        f.correctionLevel = "M"
        guard let out = f.outputImage else { return nil }
        let scale = size / out.extent.width
        let scaled = out.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        guard let cg = CIContext().createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cg)
    }

    /// Reads the timestamp encoded in a photo of the sync screen.
    static func readTimestamp(in image: UIImage) async -> Date? {
        guard let cg = image.cgImage else { return nil }
        return await Task.detached(priority: .userInitiated) { () -> Date? in
            let req = VNDetectBarcodesRequest()
            req.symbologies = [.qr]
            try? VNImageRequestHandler(cgImage: cg).perform([req])
            var payloads = req.results?.compactMap(\.payloadStringValue) ?? []
            if payloads.isEmpty {
                // Vision needs the Neural Engine; Core Image's detector also works in the Simulator.
                let det = CIDetector(ofType: CIDetectorTypeQRCode, context: nil, options: [CIDetectorAccuracy: CIDetectorAccuracyHigh])
                payloads = det?.features(in: CIImage(cgImage: cg)).compactMap { ($0 as? CIQRCodeFeature)?.messageString } ?? []
            }
            guard let p = payloads.first(where: { $0.hasPrefix(prefix) }),
                  let ts = TimeInterval(p.dropFirst(prefix.count)) else { return nil }
            return Date(timeIntervalSince1970: ts)
        }.value
    }

    /// Seconds the camera is ahead of real time (positive = camera fast).
    static func offset(exif: Date, truth: Date) -> Double { exif.timeIntervalSince(truth) }
}
