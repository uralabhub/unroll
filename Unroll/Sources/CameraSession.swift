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
        case .usb: return "cable.connector"
        case .card: return "sdcard"
        case .wifi: return "wifi"
        case .demo: return "photo.stack"
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
    @ObservationIgnored private let metaCache: MetaCache
    @ObservationIgnored private let ledger: ImportLedger

    init(kind: SourceKind, name: String, metaCache: MetaCache, ledger: ImportLedger) {
        self.kind = kind
        self.name = name
        self.metaCache = metaCache
        self.ledger = ledger
        self.readConcurrency = (kind == .usb || kind == .wifi) ? 2 : 6
    }

    var scanComplete: Bool { scanned >= shots.filter { !$0.isVideo }.count }

    // MARK: Shots

    /// Replaces the catalog. Newest first; cached metadata applied instantly.
    func setShots(_ list: [Shot]) {
        // Keep existing Shot objects (and their thumbnails / in-flight loads) across catalog refreshes.
        let previous = byID
        byID = [:]
        let merged = list.map { s -> Shot in
            if let old = previous[s.id] { byID[s.id] = old; return old }
            return adopt(s)
        }
        shots = merged.sorted { $0.fileDate > $1.fileDate }
        scanned = shots.filter { $0.meta != nil }.count
        restartScan()
    }

    /// Shots that appeared while connected (user kept shooting). Triggers auto-import.
    func add(_ list: [Shot]) {
        let fresh = list.map(adopt).filter { s in !shots.contains { $0.id == s.id } }
        guard !fresh.isEmpty else { return }
        shots = (fresh + shots).sorted { $0.fileDate > $1.fileDate }
        restartScan()
        if status == .ready { onNewShots?(fresh) }
    }

    func remove(where predicate: (Shot) -> Bool) {
        shots.removeAll(where: predicate)
        byID = Dictionary(uniqueKeysWithValues: shots.map { ($0.id, $0) })
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

    func loadMeta(_ shot: Shot) async {
        guard shot.meta == nil, inFlight.insert(shot.id).inserted else { return }
        defer { inFlight.remove(shot.id) }
        // RAW twin carries the same EXIF; the JPEG header is cheaper to parse.
        guard let data = try? await shot.primary.read(offset: 0, length: HeaderParser.headerLength) else {
            shot.meta = ShotMeta()      // don't retry forever on a broken file
            scanned += 1
            return
        }
        let info = await Task.detached(priority: .utility) { HeaderParser.parse(data) }.value
        shot.meta = info.meta
        shot.preview = info.preview
        scanned += 1
        metaCache[shot.id] = MetaCache.Entry(meta: info.meta, preview: info.preview)
    }

    func close() {
        scanTask?.cancel()
        onClose?()
    }
}
