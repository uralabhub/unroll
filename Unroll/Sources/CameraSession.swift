import Foundation
import UIKit

enum SourceKind: String {
    case usb, card, wifi, demo

    var label: String {
        switch self {
        case .usb: return "USB-C"
        case .card: return "Card"
        case .wifi: return "Wi-Fi"
        case .demo: return "Demo"
        }
    }

    var symbol: String {
        switch self {
        case .usb: return "usb"
        case .card: return "sd-card"
        case .wifi: return "wifi-high"
        case .demo: return "images"
        }
    }
}

enum SessionStatus: Equatable {
    case connecting
    case cataloging(Int)
    case ready
    case failed(String)
}

/// A live connection to one camera / card. Owns the shot list and the background metadata scan.
@MainActor
@Observable
final class CameraSession: Identifiable {
    let id = UUID()
    let kind: SourceKind
    var name: String
    var status: SessionStatus = .connecting
    private(set) var shots: [Shot] = []
    /// How many shots have had their header parsed (drives the ★ filter progress).
    private(set) var scanned = 0
    /// How many shots have a header to read (videos don't).
    private(set) var scannable = 0
    /// Bumped (at most ~4×/s) whenever shots, metadata or import results change. The library
    /// rebuilds its filtered/grouped snapshot from this instead of observing every shot.
    private(set) var revision = 0
    private(set) var isClosed = false
    @ObservationIgnored private var revisionScheduled = false
    var batteryLevel: Int?
    let connectedAt = Date()

    /// Parallel header reads. Camera PTP stacks choke on many concurrent requests; disks don't.
    let readConcurrency: Int
    var onClose: (() -> Void)?
    var onNewShots: (([Shot]) -> Void)?

    @ObservationIgnored private var byID: [String: Shot] = [:]
    @ObservationIgnored private var scanTask: Task<Void, Never>?
    @ObservationIgnored private var pending: [Shot] = []
    @ObservationIgnored private var inFlight = Set<String>()
    @ObservationIgnored private var waiters: [String: [CheckedContinuation<Void, Never>]] = [:]
    @ObservationIgnored private var readFailures: [String: Int] = [:]
    @ObservationIgnored private let metaCache: MetaCache
    @ObservationIgnored private let ledger: ImportLedger

    init(kind: SourceKind, name: String, metaCache: MetaCache, ledger: ImportLedger) {
        self.kind = kind
        self.name = name
        self.metaCache = metaCache
        self.ledger = ledger
        self.readConcurrency = (kind == .usb || kind == .wifi) ? 2 : 6
    }

    var scanComplete: Bool { scanned >= scannable }

    /// Coalesces catalog changes into one `revision` bump per 250 ms.
    func catalogChanged() {
        guard !revisionScheduled else { return }
        revisionScheduled = true
        Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(250))
            guard let self else { return }
            self.revisionScheduled = false
            self.revision &+= 1
        }
    }

    // MARK: Shots

    /// Replaces the catalog. Newest first; cached metadata applied instantly.
    func setShots(_ list: [Shot]) {
        // Keep existing Shot objects (and their thumbnails / in-flight loads) across catalog refreshes.
        let previous = byID
        byID = [:]
        var merged: [Shot] = []
        merged.reserveCapacity(list.count)
        for s in list where byID[s.id] == nil {          // dual-slot backup cards list each frame twice
            if let old = previous[s.id] {
                if old !== s { old.rebind(to: s) }
                byID[s.id] = old; merged.append(old)
            } else { merged.append(adopt(s)) }
        }
        shots = merged.sorted { $0.fileDate > $1.fileDate }
        recount()
        restartScan()
        revision &+= 1
    }

    /// Shots that appeared while connected (user kept shooting). Triggers auto-import.
    func add(_ list: [Shot]) {
        let fresh = list.filter { byID[$0.id] == nil }.map(adopt)
        guard !fresh.isEmpty else { return }
        shots = (fresh + shots).sorted { $0.fileDate > $1.fileDate }
        recount()
        restartScan()
        catalogChanged()
        if status == .ready { onNewShots?(fresh) }
    }

    func remove(where predicate: (Shot) -> Bool) {
        shots.removeAll(where: predicate)
        byID = Dictionary(shots.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        recount()
        catalogChanged()
    }

    private func recount() {
        scannable = shots.reduce(0) { $0 + ($1.isVideo ? 0 : 1) }
        scanned = shots.reduce(0) { $0 + ($1.meta != nil && !$1.isVideo ? 1 : 0) }
    }

    private func adopt(_ s: Shot) -> Shot {
        if let cached = metaCache[s.id] {
            s.meta = cached.meta
            s.preview = cached.preview
        }
        s.wasImported = ledger.contains(s.id)
        byID[s.id] = s
        return s
    }

    func shot(id: String) -> Shot? { byID[id] }

    // MARK: Metadata scan (newest first)

    private func restartScan() {
        scanTask?.cancel()
        // ImageCaptureCore: a camera is "ready to receive requests" only after
        // deviceDidBecomeReady(withCompleteContentCatalog:). Reading earlier fails silently.
        if kind == .usb && status != .ready { pending = []; return }
        pending = shots.filter { $0.meta == nil && !$0.isVideo }
        let workers = readConcurrency
        scanTask = Task { [weak self] in
            await withTaskGroup(of: Void.self) { group in
                for _ in 0..<workers {
                    group.addTask { await self?.worker() }
                }
            }
        }
    }

    private func worker() async {
        while !Task.isCancelled {
            guard let shot = nextPending() else { return }
            await loadMeta(shot)
        }
    }

    private func nextPending() -> Shot? {
        while !pending.isEmpty {
            let s = pending.removeFirst()
            if s.meta == nil && !inFlight.contains(s.id) { return s }
        }
        return nil
    }

    /// Bumps a shot to the front (it just scrolled into view).
    func prioritize(_ shot: Shot) {
        guard shot.meta == nil, !shot.isVideo, !inFlight.contains(shot.id) else { return }
        pending.removeAll { $0.id == shot.id }
        pending.insert(shot, at: 0)
    }

    /// Reads the shot's header. If the background scan is already reading it, waits for that read
    /// instead of returning early — callers (auto-import of ★/🔒) rely on `meta` being set afterwards.
    func loadMeta(_ shot: Shot) async {
        guard shot.meta == nil else { return }
        if inFlight.contains(shot.id) {
            await withCheckedContinuation { c in waiters[shot.id, default: []].append(c) }
            return
        }
        inFlight.insert(shot.id)
        defer {
            inFlight.remove(shot.id)
            for c in waiters.removeValue(forKey: shot.id) ?? [] { c.resume() }
        }
        // RAW twin carries the same EXIF; the JPEG header is cheaper to parse.
        let file = shot.primary
        guard let data = try? await file.read(offset: 0, length: HeaderParser.headerLength) else {
            readFailures[shot.id, default: 0] += 1
            if readFailures[shot.id, default: 0] >= 3 || isClosed {
                // Give up on a file that keeps failing; it can still be imported.
                shot.meta = ShotMeta()
                scanned += 1
                catalogChanged()
            } else {
                // Retry later (camera busy / woke up) rather than silently losing its ★ rating.
                let n = readFailures[shot.id, default: 0]
                Task { [weak self, weak shot] in
                    try? await Task.sleep(for: .seconds(Double(n) * 3))
                    guard let self, let shot, !self.isClosed, shot.meta == nil else { return }
                    self.pending.append(shot)
                    if self.scanTask == nil || self.scanTask?.isCancelled == true || self.inFlight.isEmpty { self.restartScan() }
                }
            }
            return
        }
        let size = file.size == ShotFile.unknownSize ? nil : Int(file.size)
        var info = await HeaderParser.parseAdaptive(data, fileSize: size) { o, n in
            try await file.read(offset: Int64(o), length: n)
        }
        // RAW+HEIF: if the HEIF's EXIF didn't fit in the header, the RAW twin carries the same EXIF.
        // Its preview range points into the RAW file, so it's not used for the HEIF primary.
        if info.format == .heif, info.meta.dateTimeOriginal == nil, let raw = shot.raw,
           let rd = try? await raw.read(offset: 0, length: HeaderParser.headerLength) {
            let rawInfo = await Task.detached(priority: .utility) { HeaderParser.parse(rd) }.value
            info.meta = rawInfo.meta
        }
        shot.meta = info.meta
        shot.preview = info.format == .heif ? nil : (info.preview ?? info.makerNotePreview)
        scanned += 1
        catalogChanged()
        metaCache[shot.id] = MetaCache.Entry(meta: info.meta, preview: shot.preview)
    }

    func close() {
        isClosed = true
        scanTask?.cancel()
        onClose?()
    }
}
