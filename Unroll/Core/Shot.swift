import Foundation
import UIKit
import ImageIO
import ImageCaptureCore

/// One physical file on the camera / card.
final class ShotFile {
    enum Location {
        case usb(ICCameraFile)
        case file(URL)
        case http(URL)          // Ricoh GR style: GET <url>, ?size=thumb|view
    }

    let name: String
    /// Byte size. `ShotFile.unknownSize` when the source doesn't say (Ricoh listing) — still a
    /// safe upper bound for reads, but excluded from totals.
    let size: Int64
    static let unknownSize: Int64 = 1 << 36
    var knownSize: Int64 { size == ShotFile.unknownSize ? 0 : size }
    let format: ShotFormat
    let location: Location

    /// True when bytes come over USB/Wi-Fi from a camera (vs. a mounted card / local file).
    var isRemote: Bool { if case .file = location { return false }; return true }

    init(name: String, size: Int64, location: Location) {
        self.name = name
        self.size = size
        self.format = ShotFormat.from(filename: name)
        self.location = location
    }
}

enum ImportState: Equatable {
    case none, queued, importing(Double), done, failed(String)
}

/// A capture as the user thinks of it: a JPEG/HEIF and (optionally) its RAW twin.
@Observable
final class Shot: Identifiable, Hashable {
    let id: String
    let primary: ShotFile
    /// Set later when the RAW half of a pair shows up in a separate camera event.
    var raw: ShotFile?
    /// File date from the card — available instantly, used for newest-first ordering.
    let fileDate: Date
    var isLocked: Bool
    var meta: ShotMeta?
    var preview: ByteRange?
    var thumbnail: UIImage?
    var importState: ImportState = .none
    var wasImported = false

    init(primary: ShotFile, raw: ShotFile?, fileDate: Date, isLocked: Bool) {
        self.primary = primary
        self.raw = raw
        self.fileDate = fileDate
        self.isLocked = isLocked
        self.id = Shot.key(name: primary.name, size: primary.size, date: fileDate)
    }

    static func key(name: String, size: Int64, date: Date) -> String {
        "\(name)|\(size)|\(Int(date.timeIntervalSince1970))"
    }

    static func == (a: Shot, b: Shot) -> Bool { a.id == b.id }
    func hash(into h: inout Hasher) { h.combine(id) }

    var rating: Int { meta?.rating ?? 0 }
    var isRated: Bool { rating > 0 }
    var isKeeper: Bool { isRated || isLocked }
    var captureDate: Date { meta?.captureDate() ?? fileDate }
    var totalBytes: Int64 { primary.knownSize + (raw?.knownSize ?? 0) }
    var isRAWOnly: Bool { primary.format.isRAW }
    var isVideo: Bool { primary.format == .video }

    var formatBadge: String {
        if isVideo { return "VIDEO" }
        if raw != nil { return primary.format == .heif ? "HEIF+RAW" : "JPG+RAW" }
        if primary.format.isRAW { return "RAW" }
        return primary.format == .heif ? "HEIF" : "JPG"
    }
}

// MARK: - Byte access (the same API whatever the transport)

enum ShotIOError: LocalizedError {
    case noData, unsupported, http(Int)
    var errorDescription: String? {
        switch self {
        case .noData: return "The camera returned no data."
        case .unsupported: return "Not supported for this connection."
        case .http(let c): return "Camera answered HTTP \(c)."
        }
    }
}

extension ShotFile {
    func read(offset: Int64, length: Int) async throws -> Data {
        let len = Int64(max(0, min(Int64(length), size - offset)))
        guard len > 0 else { return Data() }
        switch location {
        case .usb(let f):
            return try await withCheckedThrowingContinuation { cont in
                f.requestReadData(atOffset: off_t(offset), length: off_t(len)) { data, error in
                    if let data { cont.resume(returning: data) } else { cont.resume(throwing: error ?? ShotIOError.noData) }
                }
            }
        case .file(let url):
            return try await Task.detached(priority: .userInitiated) {
                let h = try FileHandle(forReadingFrom: url)
                defer { try? h.close() }
                try h.seek(toOffset: UInt64(offset))
                return try h.read(upToCount: Int(len)) ?? Data()
            }.value
        case .http(let url):
            var req = URLRequest(url: url, timeoutInterval: 20)
            req.setValue("bytes=\(offset)-\(offset + len - 1)", forHTTPHeaderField: "Range")
            let (data, resp) = try await CameraHTTP.session.data(for: req)
            let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
            guard code == 200 || code == 206 else { throw ShotIOError.http(code) }
            // Servers that ignore Range send the whole file; slice it ourselves.
            if code == 200, data.count > len {
                let s = Int(offset), e = min(data.count, s + Int(len))
                return s < e ? data.subdata(in: s..<e) : Data()
            }
            return data
        }
    }

    func thumbnail(maxPixel: Int) async -> UIImage? {
        switch location {
        case .usb(let f):
            let data: Data? = await withCheckedContinuation { cont in
                f.requestThumbnailData(options: [.imageSourceThumbnailMaxPixelSize: maxPixel]) { data, _ in
                    cont.resume(returning: data)
                }
            }
            return data.flatMap { ImageDecode.downsample($0, maxPixel: maxPixel) }
        case .file(let url):
            let raw = format.isRAW
            return await Task.detached(priority: .userInitiated) {
                ImageDecode.thumbnail(url: url, maxPixel: maxPixel, decodeFull: !raw)
            }.value
        case .http(let url):
            guard var c = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return nil }
            c.queryItems = [URLQueryItem(name: "size", value: maxPixel > 400 ? "view" : "thumb")]
            guard let u = c.url, let (data, _) = try? await CameraHTTP.session.data(from: u) else { return nil }
            return ImageDecode.downsample(data, maxPixel: maxPixel)
        }
    }

    /// Downloads the full original into `dir`, returning its local URL.
    /// Protected (🔒) files arrive read-only; Photos needs to move them, so we make the copy writable.
    func download(to dir: URL, progress: ((Double) -> Void)? = nil) async throws -> URL {
        let url = try await rawDownload(to: dir, progress: progress)
        try? FileManager.default.setAttributes([.posixPermissions: 0o644], ofItemAtPath: url.path)
        return url
    }

    private func rawDownload(to dir: URL, progress: ((Double) -> Void)?) async throws -> URL {
        let dest = dir.appendingPathComponent(name)
        try? FileManager.default.removeItem(at: dest)
        switch location {
        case .usb(let f):
            let observation = ObservationBox()
            return try await withCheckedThrowingContinuation { cont in
                let p = f.requestDownload(options: [
                    .downloadsDirectoryURL: dir,
                    .saveAsFilename: name,
                ]) { filename, error in
                    observation.token?.invalidate()
                    if let error { cont.resume(throwing: error); return }
                    cont.resume(returning: dir.appendingPathComponent(filename ?? self.name))
                }
                if let p, let progress {
                    observation.token = p.observe(\.fractionCompleted) { pr, _ in progress(pr.fractionCompleted) }
                }
            }
        case .file(let url):
            try await Task.detached(priority: .userInitiated) {
                try FileManager.default.copyItem(at: url, to: dest)
            }.value
            return dest
        case .http(let url):
            let (tmp, resp) = try await CameraHTTP.session.download(from: url)
            let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
            guard code == 200 else { throw ShotIOError.http(code) }
            try FileManager.default.moveItem(at: tmp, to: dest)
            return dest
        }
    }
}

/// Keeps a KVO token alive for the duration of a download.
final class ObservationBox: @unchecked Sendable { var token: NSKeyValueObservation? }

enum CameraHTTP {
    /// Camera Wi-Fi has no internet: forbid cellular so iOS never routes our requests away from it.
    static let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.allowsCellularAccess = false
        c.waitsForConnectivity = false
        c.timeoutIntervalForRequest = 20
        c.httpMaximumConnectionsPerHost = 2
        return URLSession(configuration: c)
    }()
}

// MARK: - Decoding helpers

enum ImageDecode {
    static func downsample(_ data: Data, maxPixel: Int, orientation: Int? = nil) -> UIImage? {
        guard let src = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary) else { return nil }
        return image(from: src, maxPixel: maxPixel, decodeFull: true, fallbackOrientation: orientation)
    }

    static func thumbnail(url: URL, maxPixel: Int, decodeFull: Bool) -> UIImage? {
        guard let src = CGImageSourceCreateWithURL(url as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary) else { return nil }
        return image(from: src, maxPixel: maxPixel, decodeFull: decodeFull, fallbackOrientation: nil)
    }

    private static func image(from src: CGImageSource, maxPixel: Int, decodeFull: Bool, fallbackOrientation: Int?) -> UIImage? {
        let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any]
        let hasOrientation = props?[kCGImagePropertyOrientation] != nil
        let opts: [CFString: Any] = [
            decodeFull ? kCGImageSourceCreateThumbnailFromImageAlways : kCGImageSourceCreateThumbnailFromImageIfAbsent: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixel,
            kCGImageSourceCreateThumbnailWithTransform: true,
        ]
        guard let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary) else { return nil }
        if !hasOrientation, let o = fallbackOrientation, o > 1 {
            return UIImage(cgImage: cg, scale: 1, orientation: uiOrientation(o))
        }
        return UIImage(cgImage: cg)
    }

    static func uiOrientation(_ exif: Int) -> UIImage.Orientation {
        switch exif {
        case 2: return .upMirrored
        case 3: return .down
        case 4: return .downMirrored
        case 5: return .leftMirrored
        case 6: return .right
        case 7: return .rightMirrored
        case 8: return .left
        default: return .up
        }
    }
}
