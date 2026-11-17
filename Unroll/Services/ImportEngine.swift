import Foundation
import Photos
import CoreLocation
import UIKit

enum PhotoLibrary {
    static func authorize() async -> Bool {
        let s = await PHPhotoLibrary.requestAuthorization(for: .readWrite)
        return s == .authorized || s == .limited
    }

    /// Saves the original bytes untouched. RAW+JPEG become one asset (Photos shows the JPEG,
    /// keeps the RAW alongside). Location and capture date are attached as asset properties,
    /// so the camera file — maker notes, recipe, everything — stays bit-exact.
    static func save(main: URL, raw: URL?, isVideo: Bool, location: CLLocation?, date: Date?, album: String?) async throws {
        var albumID: String?
        if let album { albumID = try await albumIdentifier(named: album) }
        try await PHPhotoLibrary.shared().performChanges {
            let req = PHAssetCreationRequest.forAsset()
            let opts = PHAssetResourceCreationOptions()
            opts.shouldMoveFile = true
            req.addResource(with: isVideo ? .video : .photo, fileURL: main, options: opts)
            if let raw {
                let ro = PHAssetResourceCreationOptions()
                ro.shouldMoveFile = true
                req.addResource(with: .alternatePhoto, fileURL: raw, options: ro)
            }
            if let location { req.location = location }
            if let date { req.creationDate = date }
            if let albumID, let placeholder = req.placeholderForCreatedAsset,
               let coll = PHAssetCollection.fetchAssetCollections(withLocalIdentifiers: [albumID], options: nil).firstObject {
                PHAssetCollectionChangeRequest(for: coll)?.addAssets([placeholder] as NSArray)
            }
        }
    }

    /// Saves a rendered image (frame / recap) as a new JPEG asset.
    static func save(image data: Data, location: CLLocation?) async throws {
        try await PHPhotoLibrary.shared().performChanges {
            let req = PHAssetCreationRequest.forAsset()
            req.addResource(with: .photo, data: data, options: nil)
            if let location { req.location = location }
        }
    }

    private static var albumCache: [String: String] = [:]

    static func albumIdentifier(named name: String) async throws -> String {
        if let id = albumCache[name] { return id }
        let o = PHFetchOptions()
        o.predicate = NSPredicate(format: "title = %@", name)
        if let existing = PHAssetCollection.fetchAssetCollections(with: .album, subtype: .any, options: o).firstObject {
            albumCache[name] = existing.localIdentifier
            return existing.localIdentifier
        }
        var id = ""
        try await PHPhotoLibrary.shared().performChanges {
            id = PHAssetCollectionChangeRequest.creationRequestForAssetCollection(withTitle: name)
                .placeholderForCreatedAssetCollection.localIdentifier
        }
        albumCache[name] = id
        return id
    }
}

/// The transfer pipeline: download → (RAW twin) → locate → save to Photos → remember.
@MainActor
@Observable
final class ImportEngine {
    private(set) var queue: [Shot] = []
    private(set) var current: Shot?
    private(set) var completedInRun = 0
    private(set) var totalInRun = 0
    private(set) var bytesInRun: Int64 = 0
    private(set) var runStarted: Date?
    private(set) var geotaggedInRun = 0
    private(set) var failedInRun = 0
    var lastError: String?
    var lastFinishedRun: (count: Int, failed: Int, seconds: Double, geotagged: Int)?
    /// The finished-run summary is on screen (a few seconds after a run ends).
    private(set) var showingSummary = false
    @ObservationIgnored private var summaryTask: Task<Void, Never>?

    enum PauseReason { case background, disconnected }

    @ObservationIgnored private var worker: Task<Void, Never>?
    @ObservationIgnored private var backgroundTask: UIBackgroundTaskIdentifier = .invalid
    /// The run is on hold with its queue intact: iOS took our background time away, or the
    /// camera went away (cable, sleep). It resumes on return / when the camera is back.
    private(set) var pauseReason: PauseReason?
    var isPaused: Bool { pauseReason != nil }
    @ObservationIgnored unowned let app: AppState

    init(app: AppState) { self.app = app }

    var isRunning: Bool { current != nil || !queue.isEmpty }

    var progress: Double {
        guard totalInRun > 0 else { return 0 }
        var p = Double(completedInRun)
        if case .importing(let f)? = current?.importState { p += f }
        return p / Double(totalInRun)
    }

    var throughputMBps: Double? {
        guard let s = runStarted, bytesInRun > 0 else { return nil }
        let t = Date().timeIntervalSince(s)
        return t > 0.5 ? Double(bytesInRun) / 1_048_576 / t : nil
    }

    /// Queues shots. Returns how many were refused by the free daily quota.
    @discardableResult
    func enqueue(_ shots: [Shot]) -> Int {
        // Dedupe by id: dual-slot "backup" cards list every frame twice.
        var seen = Set(queue.map(\.id))
        if let c = current { seen.insert(c.id) }
        let fresh = shots.filter { s in
            s.importState != .queued && s.importState != .done && seen.insert(s.id).inserted
        }
        var accepted = fresh
        var refused = 0
        if !app.pro.isPro {
            let room = max(0, app.quota.remaining - queue.count - (current == nil ? 0 : 1))
            if accepted.count > room { refused = accepted.count - room; accepted = Array(accepted.prefix(room)) }
        }
        guard !accepted.isEmpty else { return refused }
        showingSummary = false
        if !isRunning {
            completedInRun = 0; totalInRun = 0; bytesInRun = 0; geotaggedInRun = 0; failedInRun = 0; runStarted = Date()
        }
        for s in accepted { s.importState = .queued }
        queue.append(contentsOf: accepted)
        totalInRun += accepted.count
        startIfNeeded()
        return refused
    }

    func cancelAll() {
        for s in queue { s.importState = .none }
        totalInRun -= queue.count
        queue.removeAll()
        pauseReason = nil
    }

    /// Called when the app comes back to the foreground.
    func resumeIfPaused() {
        guard pauseReason == .background else { return }
        pauseReason = nil
        startIfNeeded()
    }

    /// A camera / card session became ready. If a run was paused because the camera went away,
    /// swap the queued shots for the same frames in the new session (fresh file handles) and go on.
    func reattach(_ session: CameraSession) {
        guard pauseReason == .disconnected else { return }
        queue = queue.compactMap { old in
            guard let fresh = session.shot(id: old.id) else {
                old.importState = .none
                totalInRun -= 1
                return nil
            }
            fresh.importState = .queued
            return fresh
        }
        pauseReason = nil
        if queue.isEmpty { finishWorker() } else { startIfNeeded() }
    }

    /// Asks iOS for extra time so a run keeps going when the user switches apps or locks the phone.
    /// When that time runs out the run pauses cleanly (current shot goes back to the queue).
    private func beginBackgroundTime() {
        guard backgroundTask == .invalid else { return }
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "Unroll import") { [weak self] in
            MainActor.assumeIsolated {
                guard let self else { return }
                if self.pauseReason == nil { self.pauseReason = .background }
                self.endBackgroundTime()
            }
        }
    }

    private func endBackgroundTime() {
        guard backgroundTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundTask)
        backgroundTask = .invalid
    }

    private func startIfNeeded() {
        guard worker == nil, !isPaused else { return }
        beginBackgroundTime()
        // Keep the screen awake during a run: auto-lock is the usual way an import gets cut off.
        UIApplication.shared.isIdleTimerDisabled = true
        worker = Task { [weak self] in
            guard let self else { return }
            guard await PhotoLibrary.authorize() else {
                self.lastError = "Allow Photos access in Settings to import."
                self.cancelAll()
                self.finishWorker()
                return
            }
            while !self.queue.isEmpty && !self.isPaused {
                let shot = self.queue.removeFirst()
                self.current = shot
                switch await self.importOne(shot) {
                case .saved:
                    self.completedInRun += 1
                case .failed:
                    self.completedInRun += 1
                    self.failedInRun += 1
                case .interrupted:
                    // Backgrounded or unplugged: this shot goes first when we're back.
                    shot.importState = .queued
                    self.queue.insert(shot, at: 0)
                }
            }
            self.current = nil
            if self.isPaused {
                self.finishWorker()
                return
            }
            if let s = self.runStarted {
                self.lastFinishedRun = (self.completedInRun - self.failedInRun, self.failedInRun,
                                        Date().timeIntervalSince(s), self.geotaggedInRun)
                self.showSummary()
            }
            self.finishWorker()
            if self.app.settings.autoEject, self.failedInRun == 0, let session = self.app.session, session.kind == .usb {
                self.app.usb.eject(session)
            }
        }
    }

    private func showSummary() {
        showingSummary = true
        if failedInRun == 0 { Haptics.success() }
        summaryTask?.cancel()
        summaryTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(5))
            guard !Task.isCancelled else { return }
            self?.showingSummary = false
        }
    }

    private func finishWorker() {
        worker = nil
        UIApplication.shared.isIdleTimerDisabled = false
        endBackgroundTime()
    }

    enum Outcome { case saved, failed, interrupted }

    /// True while the shot still belongs to the live session (cable in, same camera).
    private func isAttached(_ shot: Shot) -> Bool {
        guard let s = app.session else { return false }
        return s.shot(id: shot.id) === shot && !s.isClosed && s.status == .ready
    }

    /// Imports one shot, retrying transient camera errors (timeouts, busy, Wi-Fi blips) twice.
    private func importOne(_ shot: Shot) async -> Outcome {
        var attempt = 0
        while true {
            let r = await importAttempt(shot)
            switch r {
            case .success: return .saved
            case .failure(let error):
                if pauseReason != nil || UIApplication.shared.applicationState == .background {
                    if pauseReason == nil { pauseReason = .background }
                    return .interrupted
                }
                if !isAttached(shot) {
                    // Unplugged / camera slept: keep the queue, resume when it's back.
                    pauseReason = .disconnected
                    return .interrupted
                }
                let ns = error as NSError
                let transient = (error as? ShotIOError)?.isTransient
                    ?? (ns.domain == "com.apple.ImageCaptureCore" || ns.domain == NSURLErrorDomain)
                if transient && attempt < 2 {
                    attempt += 1
                    shot.importState = .importing(0)
                    try? await Task.sleep(for: .seconds(attempt == 1 ? 1 : 3))
                    continue
                }
                shot.importState = .failed(error.localizedDescription)
                lastError = "\(shot.primary.name): \(error.localizedDescription)"
                return .failed
            }
        }
    }

    private func importAttempt(_ shot: Shot) async -> Result<Void, Error> {
        shot.importState = .importing(0)
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("import-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        do {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            let wantsRAW = app.settings.includeRAW && shot.raw != nil
            let mainShare = wantsRAW ? 0.5 : 1.0
            let mainURL = try await shot.primary.download(to: dir, progress: ProgressThrottle { [weak shot] f in
                Task { @MainActor in shot?.importState = .importing(f * mainShare) }
            }.report)
            var rawURL: URL?
            if wantsRAW, let raw = shot.raw {
                rawURL = try await raw.download(to: dir, progress: ProgressThrottle { [weak shot] f in
                    Task { @MainActor in shot?.importState = .importing(0.5 + f * 0.5) }
                }.report)
            }
            // Count what actually arrived (listing sizes can be unknown, e.g. Ricoh Wi-Fi).
            for u in [mainURL, rawURL].compactMap({ $0 }) {
                bytesInRun += Int64((try? u.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
            }

            if shot.meta == nil {
                let info = await Task.detached(priority: .utility) { () -> HeaderInfo? in
                    guard let d = try? Data(contentsOf: mainURL, options: .mappedIfSafe) else { return nil }
                    return HeaderParser.parse(d)
                }.value
                if let info { shot.meta = info.meta }
            }
            let meta = shot.meta ?? ShotMeta()
            let date = app.correctedDate(for: shot)
            var location: CLLocation?
            if let lat = meta.latitude, let lon = meta.longitude {
                location = CLLocation(latitude: lat, longitude: lon)
            } else if app.settings.geotag {
                location = app.location.location(at: date)
                if location == nil, app.settings.borrowPhoneLocations {
                    location = PhoneLocationLookup.location(near: date)
                }
                if location != nil { geotaggedInRun += 1 }
            }

            let album: String? = {
                switch app.settings.albumMode {
                case .single: return "Unroll"
                case .perCamera: return meta.cameraName
                case .none: return nil
                }
            }()
            try await PhotoLibrary.save(main: mainURL, raw: rawURL, isVideo: shot.isVideo,
                                        location: location, date: date, album: album)
            app.ledger.mark(shot.id)
            if !app.pro.isPro { app.quota.consume() }
            if let r = meta.recipe { app.recipes.record(r, camera: meta.cameraName, date: date) }
            shot.wasImported = true
            shot.importState = .done
            app.session?.catalogChanged()
            return .success(())
        } catch {
            return .failure(error)
        }
    }
}

/// Forwards transfer progress at most every 2% (and always at 100%), so a 60 MB RAW makes
/// ~50 UI updates instead of thousands. Called from ImageCaptureCore's KVO thread.
final class ProgressThrottle: @unchecked Sendable {
    private let lock = NSLock()
    private var last = -1.0
    private let forward: (Double) -> Void
    init(_ forward: @escaping (Double) -> Void) { self.forward = forward }
    func report(_ f: Double) {
        lock.lock()
        let emit = f >= 1 || f - last >= 0.02
        if emit { last = f }
        lock.unlock()
        if emit { forward(f) }
    }
}
