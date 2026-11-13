import Foundation
import ImageCaptureCore

/// Watches for cameras / card readers plugged into the USB-C port (PTP via ImageCaptureCore).
///
/// Speed tricks vs. the stock Photos import:
///  • On iOS 13+ ImageCaptureCore fetches no thumbnails/metadata unless asked, so we only ask for
///    what is on screen.
///  • Items stream into the UI while the catalog is still being built, newest first; headers are
///    read once the camera reports a complete catalog (that's when it accepts requests).
///  • Headers are read with `requestReadData(atOffset:length:)` — 192 KB, not 60 MB.
@MainActor
@Observable
final class USBCameraService: NSObject {
    enum Authorization { case unknown, authorized, denied }

    var authorization: Authorization = .unknown
    var isBrowsing = false
    /// Called when a camera session opens; AppState turns it into the active session.
    @ObservationIgnored var onSession: ((CameraSession, ICCameraDevice) -> Void)?
    @ObservationIgnored var makeSession: ((String) -> CameraSession)?
    /// Called once the camera has reported its full catalog — the moment to auto-import what's on the card.
    @ObservationIgnored var onCatalogReady: ((CameraSession) -> Void)?

    @ObservationIgnored private let browser = ICDeviceBrowser()
    @ObservationIgnored private var sessions: [ObjectIdentifier: CameraSession] = [:]
    @ObservationIgnored private var devices: [ObjectIdentifier: ICCameraDevice] = [:]
    @ObservationIgnored private var batchTimer: [ObjectIdentifier: Task<Void, Never>] = [:]

    override init() {
        super.init()
        browser.delegate = self
    }

    func start() {
        guard !isBrowsing else { return }
        #if targetEnvironment(simulator)
        // The Simulator's ImageCaptureCore is a stub (no USB, no authorization API).
        authorization = .authorized
        return
        #else
        guard browser.responds(to: #selector(ICDeviceBrowser.requestContentsAuthorization(completion:))) else {
            browser.start()
            isBrowsing = true
            return
        }
        #endif
        browser.requestContentsAuthorization { [weak self] status in
            Task { @MainActor in
                guard let self else { return }
                // Some iOS builds report notDetermined forever even when access works;
                // browse anyway and let the session open decide.
                self.authorization = (status == .denied || status == .restricted) ? .denied : .authorized
                self.browser.start()
                self.isBrowsing = true
            }
        }
    }

    func stop() {
        browser.stop()
        isBrowsing = false
    }

    func eject(_ session: CameraSession) {
        guard let (key, _) = sessions.first(where: { $0.value === session }), let dev = devices[key] else { return }
        dev.requestCloseSession()
        dev.requestEject { _ in }
    }

    // MARK: Catalog → shots

    fileprivate func rebuild(_ device: ICCameraDevice) {
        guard let session = sessions[ObjectIdentifier(device)] else { return }
        let all = device.mediaFiles ?? []
        session.setShots(shots(from: all, catalog: all))
        if device.contentCatalogPercentCompleted < 100 {
            session.status = .cataloging(Int(device.contentCatalogPercentCompleted))
        }
    }

    /// Debounce: ImageCaptureCore delivers items in many small batches while cataloging.
    fileprivate func scheduleRebuild(_ device: ICCameraDevice) {
        let key = ObjectIdentifier(device)
        guard batchTimer[key] == nil else { return }
        batchTimer[key] = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(250))
            guard let self else { return }
            self.batchTimer[key] = nil
            self.rebuild(device)
        }
    }

    /// Folder + file stem: how cameras name the halves of a RAW+JPEG pair (DSCF1234.JPG / .RAF).
    static func stem(_ f: ICCameraFile) -> String? {
        guard let name = f.name else { return nil }
        return "\(f.parentFolder?.name ?? "")/\((name as NSString).deletingPathExtension)".lowercased()
    }

    /// `items` are the files to turn into shots; `catalog` is everything on the camera, used to pair
    /// RAW+JPEG even when the halves arrive in different `didAdd` batches.
    func shots(from items: [ICCameraItem], catalog: [ICCameraItem]) -> [Shot] {
        let files = items.compactMap { $0 as? ICCameraFile }
        let all = catalog.compactMap { $0 as? ICCameraFile }
        // Per Apple, `isLocked` means the *storage card* is locked. If every file says locked it is
        // the card switch, not per-file Protect — don't turn the whole card into keepers.
        let cardLocked = all.count > 1 && all.allSatisfy(\.isLocked)
        // RAW halves of RAW+JPEG pairs are attached to their JPEG, not listed twice.
        var developedStems = Set<String>()
        var rawByStem: [String: ICCameraFile] = [:]
        for f in all {
            guard let s = Self.stem(f), let n = f.name else { continue }
            if ShotFormat.from(filename: n).isRAW { rawByStem[s] = f } else { developedStems.insert(s) }
        }
        let pairedRAW = Set(all.compactMap { $0.pairedRawImage.map(ObjectIdentifier.init) })
        return files.compactMap { f in
            guard let name = f.name else { return nil }
            let stem = Self.stem(f)
            if ShotFormat.from(filename: name).isRAW,
               pairedRAW.contains(ObjectIdentifier(f)) || stem.map(developedStems.contains) == true { return nil }
            let primary = ShotFile(name: name, size: Int64(f.fileSize), location: .usb(f))
            let rawFile = ShotFormat.from(filename: name).isRAW ? nil : (f.pairedRawImage ?? stem.flatMap { rawByStem[$0] })
            let raw = rawFile.flatMap { r in
                r.name.map { ShotFile(name: $0, size: Int64(r.fileSize), location: .usb(r)) }
            }
            let date = f.exifCreationDate ?? f.creationDate ?? f.fileCreationDate ?? .distantPast
            return Shot(primary: primary, raw: raw, fileDate: date, isLocked: f.isLocked && !cardLocked)
        }
    }
}

// MARK: - ICDeviceBrowserDelegate

extension USBCameraService: ICDeviceBrowserDelegate {
    nonisolated func deviceBrowser(_ browser: ICDeviceBrowser, didAdd device: ICDevice, moreComing: Bool) {
        Task { @MainActor in
            guard let camera = device as? ICCameraDevice, let make = self.makeSession else { return }
            let key = ObjectIdentifier(camera)
            let session = make(camera.name ?? "Camera")
            session.status = .connecting
            session.onClose = { [weak self, weak camera] in
                camera?.requestCloseSession()
                self?.sessions[key] = nil
                self?.devices[key] = nil
            }
            self.sessions[key] = session
            self.devices[key] = camera
            camera.delegate = self
            camera.requestOpenSession()
            self.onSession?(session, camera)
        }
    }

    nonisolated func deviceBrowser(_ browser: ICDeviceBrowser, didRemove device: ICDevice, moreGoing: Bool) {
        Task { @MainActor in self.drop(device) }
    }

    fileprivate func drop(_ device: ICDevice) {
        let key = ObjectIdentifier(device)
        guard let s = sessions[key] else { return }
        sessions[key] = nil
        devices[key] = nil
        s.status = .failed("Disconnected")
        s.onClose = nil
        s.close()
    }
}

// MARK: - ICCameraDeviceDelegate

extension USBCameraService: ICCameraDeviceDelegate {
    nonisolated func device(_ device: ICDevice, didOpenSessionWithError error: Error?) {
        Task { @MainActor in
            guard let s = self.sessions[ObjectIdentifier(device)] else { return }
            if let error {
                s.status = .failed(error.localizedDescription)
            } else {
                s.status = .cataloging(0)
            }
        }
    }

    nonisolated func device(_ device: ICDevice, didCloseSessionWithError error: Error?) {}

    nonisolated func didRemove(_ device: ICDevice) {
        Task { @MainActor in self.drop(device) }
    }

    nonisolated func deviceDidBecomeReady(withCompleteContentCatalog device: ICCameraDevice) {
        Task { @MainActor in
            guard let s = self.sessions[ObjectIdentifier(device)] else { return }
            let firstTime = s.status != .ready
            s.status = .ready            // before rebuild: the header scan starts only when ready
            self.rebuild(device)
            if firstTime { self.onCatalogReady?(s) }
        }
    }

    nonisolated func cameraDevice(_ camera: ICCameraDevice, didAdd items: [ICCameraItem]) {
        Task { @MainActor in
            guard let s = self.sessions[ObjectIdentifier(camera)] else { return }
            if s.status == .ready {
                // Shot while plugged in → live import. A RAW arriving after its JPEG joins that shot.
                let all = camera.mediaFiles ?? []
                for f in items.compactMap({ $0 as? ICCameraFile }) {
                    guard let n = f.name, ShotFormat.from(filename: n).isRAW, let stem = Self.stem(f) else { continue }
                    if let twin = s.shots.first(where: { shot in
                        guard shot.raw == nil, case .usb(let p) = shot.primary.location else { return false }
                        return Self.stem(p) == stem
                    }) {
                        twin.raw = ShotFile(name: n, size: Int64(f.fileSize), location: .usb(f))
                    }
                }
                s.add(self.shots(from: items, catalog: all))
            } else {
                self.scheduleRebuild(camera)
            }
        }
    }

    nonisolated func cameraDevice(_ camera: ICCameraDevice, didRemove items: [ICCameraItem]) {
        Task { @MainActor in
            guard let s = self.sessions[ObjectIdentifier(camera)] else { return }
            let gone = Set(items.map(ObjectIdentifier.init))
            s.remove { shot in
                if case .usb(let f) = shot.primary.location { return gone.contains(ObjectIdentifier(f)) }
                return false
            }
        }
    }

    // ICCameraDevice.h: these are asked "when the camera device is about to execute queued
    // requests" — i.e. our own requestThumbnailData calls. Answering false would abort them.
    // Nothing is fetched automatically on iOS 13+, so allowing them costs nothing.
    nonisolated func cameraDevice(_ cameraDevice: ICCameraDevice, shouldGetThumbnailOf item: ICCameraItem) -> Bool { true }
    nonisolated func cameraDevice(_ cameraDevice: ICCameraDevice, shouldGetMetadataOf item: ICCameraItem) -> Bool { true }

    nonisolated func cameraDevice(_ camera: ICCameraDevice, didReceiveThumbnail thumbnail: CGImage?, for item: ICCameraItem, error: Error?) {}
    nonisolated func cameraDevice(_ camera: ICCameraDevice, didReceiveMetadata metadata: [AnyHashable: Any]?, for item: ICCameraItem, error: Error?) {}
    nonisolated func cameraDevice(_ camera: ICCameraDevice, didRenameItems items: [ICCameraItem]) {}
    nonisolated func cameraDeviceDidChangeCapability(_ camera: ICCameraDevice) {}
    nonisolated func cameraDevice(_ camera: ICCameraDevice, didReceivePTPEvent eventData: Data) {}
    nonisolated func cameraDeviceDidRemoveAccessRestriction(_ device: ICDevice) {}
    nonisolated func cameraDeviceDidEnableAccessRestriction(_ device: ICDevice) {}
}
