import UIKit

/// Limits concurrent requests to one camera. Waiters are served newest-first (the cell that
/// just scrolled into view beats the ones the user already flicked past), and a waiter whose
/// task is cancelled leaves the queue instead of holding a slot.
actor AsyncSemaphore {
    private var permits: Int
    private var waiters: [(id: UUID, cont: CheckedContinuation<Bool, Never>)] = []
    init(_ permits: Int) { self.permits = permits }

    /// Returns false if the caller was cancelled while waiting (it then holds no permit).
    @discardableResult
    func wait() async -> Bool {
        if Task.isCancelled { return false }
        if permits > 0 { permits -= 1; return true }
        let id = UUID()
        return await withTaskCancellationHandler {
            await withCheckedContinuation { c in waiters.append((id, c)) }
        } onCancel: {
            Task { await self.cancel(id) }
        }
    }

    private func cancel(_ id: UUID) {
        if let i = waiters.firstIndex(where: { $0.id == id }) { waiters.remove(at: i).cont.resume(returning: false) }
    }

    func signal() {
        if let w = waiters.popLast() { w.cont.resume(returning: true) } else { permits += 1 }
    }
}

/// Grid thumbnails, bounded by memory cost (bytes), evicted by the system under pressure.
final class ThumbnailCache: @unchecked Sendable {
    static let shared = ThumbnailCache()
    private let cache = NSCache<NSString, UIImage>()
    init() {
        // ~1/16 of RAM, between 64 and 256 MB: about 2,000 thumbnails on a 6 GB phone.
        let ram = Double(ProcessInfo.processInfo.physicalMemory)
        cache.totalCostLimit = Int(min(256e6, max(64e6, ram / 16)))
    }
    func image(for id: String) -> UIImage? { cache.object(forKey: id as NSString) }
    func set(_ img: UIImage, for id: String) {
        let cost = Int(img.size.width * img.scale * img.size.height * img.scale * 4)
        cache.setObject(img, forKey: id as NSString, cost: cost)
    }
}

@MainActor
enum Previews {
    private static let cameraGate = AsyncSemaphore(2)
    private static let diskGate = AsyncSemaphore(6)
    private static let cache: NSCache<NSString, UIImage> = {
        let c = NSCache<NSString, UIImage>()
        c.countLimit = 12          // ~12 × 2400 px ≈ 180 MB worst case
        return c
    }()

    private static func gate(for f: ShotFile) -> AsyncSemaphore {
        if case .file = f.location { return diskGate }
        return cameraGate
    }

    /// Grid thumbnail (~360 px). Cached by memory cost; nil if cancelled (cell scrolled away).
    @discardableResult
    static func thumbnail(_ shot: Shot) async -> UIImage? {
        if let hit = ThumbnailCache.shared.image(for: shot.id) { return hit }
        let g = gate(for: shot.primary)
        guard await g.wait() else { return nil }
        defer { Task { await g.signal() } }
        if let hit = ThumbnailCache.shared.image(for: shot.id) { return hit }
        guard !Task.isCancelled else { return nil }
        var img = await shot.primary.thumbnail(maxPixel: 360)
        if img == nil, let raw = shot.raw, !Task.isCancelled { img = await raw.thumbnail(maxPixel: 360) }
        // Camera had no thumbnail (some RAW-only bodies): cut one from the embedded preview.
        if img == nil, !Task.isCancelled, let r = shot.preview, r.length > 0, r.length < 16_000_000,
           let data = try? await shot.primary.read(offset: Int64(r.offset), length: r.length) {
            let o = shot.meta?.orientation
            img = await Task.detached(priority: .userInitiated) { ImageDecode.downsample(data, maxPixel: 360, orientation: o) }.value
        }
        if let img { ThumbnailCache.shared.set(img, for: shot.id) }
        return img
    }

    /// Screen-size preview: embedded RAW/MPF preview via a ranged read, never the whole RAW.
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
               let img = await decode(data, maxPixel: 2400, orientation: orientation) {
                return img
            }
            // JPEG/HEIF without an MPF preview: the file itself is small enough to fetch.
            if f.format == .jpeg || f.format == .heif, f.size < 40_000_000,
               let data = try? await f.read(offset: 0, length: Int(f.size)) {
                return await decode(data, maxPixel: 2400, orientation: nil)
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
                return await Task.detached(priority: .userInitiated) { ImageDecode.thumbnail(url: url, maxPixel: 6000, decodeFull: true) }.value
            case .usb, .http:
                if let d = try? await f.read(offset: 0, length: Int(min(f.size, 60_000_000))), !d.isEmpty {
                    return await decode(d, maxPixel: 6000, orientation: nil)
                }
            }
        }
        return await large(shot, session: session)
    }

    private static func decode(_ data: Data, maxPixel: Int, orientation: Int?) async -> UIImage? {
        await Task.detached(priority: .userInitiated) { ImageDecode.downsample(data, maxPixel: maxPixel, orientation: orientation) }.value
    }
}
