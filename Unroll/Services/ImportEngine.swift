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
    var lastError: String?
    var lastFinishedRun: (count: Int, seconds: Double, geotagged: Int)?

    @ObservationIgnored private var worker: Task<Void, Never>?
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
        let fresh = shots.filter { s in
            s.importState != .queued && !(current?.id == s.id) && s.importState != .done
        }
        var accepted = fresh
        var refused = 0
        if !app.pro.isPro {
            let room = max(0, app.quota.remaining - queue.count - (current == nil ? 0 : 1))
            if accepted.count > room { refused = accepted.count - room; accepted = Array(accepted.prefix(room)) }
        }
        guard !accepted.isEmpty else { return refused }
        if !isRunning {
            completedInRun = 0; totalInRun = 0; bytesInRun = 0; geotaggedInRun = 0; runStarted = Date()
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
    }

    private func startIfNeeded() {
        guard worker == nil else { return }
        worker = Task { [weak self] in
            guard let self else { return }
            guard await PhotoLibrary.authorize() else {
                self.lastError = "Allow Photos access in Settings to import."
                self.cancelAll()
                self.worker = nil
                return
            }
            while !self.queue.isEmpty {
                let shot = self.queue.removeFirst()
                self.current = shot
                await self.importOne(shot)
                self.completedInRun += 1
            }
            self.current = nil
            if let s = self.runStarted {
                self.lastFinishedRun = (self.completedInRun, Date().timeIntervalSince(s), self.geotaggedInRun)
            }
            self.worker = nil
            if self.app.settings.autoEject, let session = self.app.session, session.kind == .usb {
                self.app.usb.eject(session)
            }
        }
    }

    private func importOne(_ shot: Shot) async {
        shot.importState = .importing(0)
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("import-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        do {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            let wantsRAW = app.settings.includeRAW && shot.raw != nil
            let mainShare = wantsRAW ? 0.5 : 1.0
            let mainURL = try await shot.primary.download(to: dir) { [weak shot] f in
                Task { @MainActor in shot?.importState = .importing(f * mainShare) }
            }
            var rawURL: URL?
            if wantsRAW, let raw = shot.raw {
                rawURL = try await raw.download(to: dir) { [weak shot] f in
                    Task { @MainActor in shot?.importState = .importing(0.5 + f * 0.5) }
                }
            }
            bytesInRun += shot.primary.size + (rawURL != nil ? (shot.raw?.size ?? 0) : 0)

            if shot.meta == nil, let d = try? Data(contentsOf: mainURL, options: .mappedIfSafe) {
                let info = HeaderParser.parse(d.prefix(HeaderParser.headerLength))
                shot.meta = info.meta
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
        } catch {
            shot.importState = .failed(error.localizedDescription)
            lastError = "\(shot.primary.name): \(error.localizedDescription)"
        }
    }
}
