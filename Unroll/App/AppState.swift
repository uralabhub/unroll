import Foundation
import SwiftUI
import UIKit

@MainActor
@Observable
final class AppState {
    let settings = Settings()
    let ledger = ImportLedger()
    let recipes = RecipeBook()
    let quota = Quota()
    let pro = ProStore()
    let location = LocationRecorder()
    let usb = USBCameraService()
    @ObservationIgnored let metaCache = MetaCache()
    @ObservationIgnored private(set) lazy var importer = ImportEngine(app: self)

    var session: CameraSession?
    var showPaywall = false
    var toast: String?
    @ObservationIgnored private var securityScoped: URL?

    init() {
        usb.makeSession = { [unowned self] name in
            CameraSession(kind: .usb, name: name, metaCache: self.metaCache, ledger: self.ledger)
        }
        usb.onSession = { [unowned self] session, _ in self.activate(session) }
        // Plugging in with auto-import on imports what's already on the card, like opening a card folder.
        usb.onCatalogReady = { [unowned self] session in
            guard self.session === session else { return }
            self.autoImport(session.shots, live: false)
        }
        usb.start()
    }

    // MARK: Sessions

    private func activate(_ s: CameraSession) {
        session?.close()
        s.onNewShots = { [weak self, weak s] new in
            guard let self, self.session === s else { return }
            self.autoImport(new, live: true)
        }
        let previousClose = s.onClose
        s.onClose = { [weak self, weak s] in
            previousClose?()
            if self?.session === s { self?.session = nil }
        }
        session = s
        Haptics.success()
    }

    func openFolder(_ url: URL, kind: SourceKind = .card) async {
        securityScoped?.stopAccessingSecurityScopedResource()
        if url.startAccessingSecurityScopedResource() { securityScoped = url }
        let name = kind == .demo ? "X100VI · Demo Roll" : url.lastPathComponent
        let s = CameraSession(kind: kind, name: name, metaCache: metaCache, ledger: ledger)
        s.status = .cataloging(0)
        activate(s)
        let shots = await FolderSource.scan(url)
        s.setShots(shots)
        s.status = .ready
        // Name the session after the camera once the newest header is in.
        if let newest = s.shots.first {
            await s.loadMeta(newest)
            if kind != .demo, let cam = newest.meta?.cameraName { s.name = cam }
        }
        autoImport(s.shots, live: false)
    }

    func openDemo() async {
        let root = await DemoRoll.prepare()
        location.installDemoWalk()
        await openFolder(root, kind: .demo)
    }

    func connectRicoh() async {
        let s = CameraSession(kind: .wifi, name: "Ricoh GR", metaCache: metaCache, ledger: ledger)
        activate(s)
        do {
            let (model, shots) = try await RicohGRSource.connect()
            s.name = model
            s.setShots(shots)
            s.status = .ready
        } catch {
            s.status = .failed("Couldn't reach the camera. Join the GR's Wi-Fi in Settings, then try again.")
        }
    }

    func disconnect() {
        guard let s = session else { return }
        if s.kind == .usb { usb.eject(s) }
        s.close()
        session = nil
        securityScoped?.stopAccessingSecurityScopedResource()
        securityScoped = nil
    }

    // MARK: Import

    /// Auto-import rule on connect, and live import of shots taken while plugged in.
    func autoImport(_ shots: [Shot], live: Bool) {
        guard settings.autoImport != .off else { return }
        if !pro.isPro && settings.autoImport == .keepers { return }   // Pro feature
        let source = session
        Task {
            var picked: [Shot] = []
            for s in shots where !s.wasImported && !s.isVideo {
                if settings.autoImport == .keepers {
                    // Locked shots are keepers without a header read; stars need the header.
                    if !s.isLocked, s.meta == nil { await source?.loadMeta(s) }
                    guard source === session else { return }   // unplugged / switched meanwhile
                    if s.isKeeper { picked.append(s) }
                } else {
                    picked.append(s)
                }
            }
            if !picked.isEmpty { importShots(picked) }
        }
    }

    func importShots(_ shots: [Shot]) {
        let refused = importer.enqueue(shots)
        if refused > 0 {
            toast = "Free plan: \(Quota.freePerDay) imports a day. \(refused) waiting for Pro."
            showPaywall = true
        }
        Haptics.tap()
    }

    /// Capture time with the per-camera clock correction applied.
    func correctedDate(for shot: Shot) -> Date {
        let offset = settings.clockOffset(for: shot.meta?.model)
        return shot.captureDate.addingTimeInterval(-offset)
    }
}

enum Haptics {
    static func tap() { UIImpactFeedbackGenerator(style: .light).impactOccurred() }
    static func success() { UINotificationFeedbackGenerator().notificationOccurred(.success) }
}
