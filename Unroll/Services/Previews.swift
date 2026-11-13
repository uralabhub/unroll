import UIKit

/// Limits concurrent camera requests (PTP stacks serialise anyway; flooding them adds latency).
actor AsyncSemaphore {
    private var permits: Int
    private var waiters: [CheckedContinuation<Void, Never>] = []
    init(_ permits: Int) { self.permits = permits }
    func wait() async {
        if permits > 0 { permits -= 1; return }
        await withCheckedContinuation { waiters.append($0) }
    }
    func signal() {
        if waiters.isEmpty { permits += 1 } else { waiters.removeFirst().resume() }
    }
}

@MainActor
enum Previews {
    private static let cameraGate = AsyncSemaphore(3)
    private static let diskGate = AsyncSemaphore(8)
    private static let cache: NSCache<NSString, UIImage> = {
        let c = NSCache<NSString, UIImage>(); c.countLimit = 24; return c
    }()

    private static func gate(for f: ShotFile) -> AsyncSemaphore {
        if case .file = f.location { return diskGate }
        return cameraGate
    }

    /// Grid thumbnail (~320 px).
    static func thumbnail(_ shot: Shot) async {
        guard shot.thumbnail == nil else { return }
        let g = gate(for: shot.primary)
        await g.wait()
        defer { Task { await g.signal() } }
        guard shot.thumbnail == nil, !Task.isCancelled else { return }
        var img = await shot.primary.thumbnail(maxPixel: 360)
        if img == nil, let raw = shot.raw { img = await raw.thumbnail(maxPixel: 360) }
        shot.thumbnail = img
    }

    /// Screen-size preview: embedded RAW/MPF preview via a ranged read — never the whole file.
    static func large(_ shot: Shot, session: CameraSession?) async -> UIImage? {
        if let hit = cache.object(forKey: shot.id as NSString) { return hit }
        let img = await loadLarge(shot, session: session)
        if let img { cache.setObject(img, forKey: shot.id as NSString) }
        return img
    }

    private static func loadLarge(_ shot: Shot, session: CameraSession?) async -> UIImage? {
        let f = shot.primary
        switch f.location {
        case .file(let url):
            let raw = f.format.isRAW
            return await Task.detached(priority: .userInitiated) { ImageDecode.thumbnail(url: url, maxPixel: 2400, decodeFull: !raw) }.value
        case .http:
            return await f.thumbnail(maxPixel: 2000)
        case .usb:
            if shot.meta == nil { await session?.loadMeta(shot) }
            let orientation = shot.meta?.orientation
            if let r = shot.preview, r.length > 0, r.length < 16_000_000,
               let data = try? await f.read(offset: Int64(r.offset), length: r.length),
               let img = ImageDecode.downsample(data, maxPixel: 2400, orientation: orientation) {
                return img
            }
            if f.format == .jpeg || f.format == .heif, f.size < 40_000_000,
               let data = try? await f.read(offset: 0, length: Int(f.size)) {
                return ImageDecode.downsample(data, maxPixel: 2400)
            }
            return await f.thumbnail(maxPixel: 1024)
        }
    }

    /// Best full-resolution image for framing (original JPEG; embedded preview for RAW-only).
    static func full(_ shot: Shot, session: CameraSession?) async -> UIImage? {
        let f = shot.primary
        if !f.format.isRAW, f.format != .video {
            switch f.location {
            case .file(let url):
                return await Task.detached(priority: .userInitiated) { ImageDecode.thumbnail(url: url, maxPixel: 8000, decodeFull: true) }.value
            case .usb, .http:
                if let d = try? await f.read(offset: 0, length: Int(min(f.size, 60_000_000))), !d.isEmpty {
                    return ImageDecode.downsample(d, maxPixel: 8000)
                }
            }
        }
        return await large(shot, session: session)
    }
}
