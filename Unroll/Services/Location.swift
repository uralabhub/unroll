import Foundation
import CoreLocation
import Photos

struct TrailPoint: Codable, Hashable {
    var lat: Double
    var lon: Double
    var t: Date
    var acc: Double

    var coordinate: CLLocationCoordinate2D { .init(latitude: lat, longitude: lon) }
    var location: CLLocation {
        CLLocation(coordinate: coordinate, altitude: 0, horizontalAccuracy: acc, verticalAccuracy: -1, timestamp: t)
    }
}

struct PhotoWalk: Codable, Identifiable, Hashable {
    var id = UUID()
    var start: Date
    var end: Date?
    var points: [TrailPoint] = []
    var isDemo = false

    var distanceMeters: Double {
        zip(points, points.dropFirst()).reduce(0) { $0 + $1.0.location.distance(from: $1.1.location) }
    }
    var duration: TimeInterval { (end ?? points.last?.t ?? start).timeIntervalSince(start) }
}

/// Records a battery-friendly GPS trail during a "Photo Walk" so imports can be geotagged
/// without the camera's Bluetooth link (the #1 battery + reliability complaint about brand apps).
@MainActor
@Observable
final class LocationRecorder: NSObject, CLLocationManagerDelegate {
    private(set) var walks: [PhotoWalk]
    private(set) var current: PhotoWalk?
    var authorization: CLAuthorizationStatus = .notDetermined
    var lastFix: CLLocation?

    @ObservationIgnored private let manager = CLLocationManager()
    @ObservationIgnored private var saveTask: Task<Void, Never>?

    override init() {
        walks = Disk.load([PhotoWalk].self, "walks.json") ?? []
        super.init()
        manager.delegate = self
        manager.activityType = .fitness
        manager.desiredAccuracy = kCLLocationAccuracyNearestTenMeters
        manager.distanceFilter = 25          // a point every ~25 m: plenty for street photos, easy on battery
        manager.pausesLocationUpdatesAutomatically = false
        authorization = manager.authorizationStatus
        // Keep 60 days.
        let cutoff = Date().addingTimeInterval(-60 * 86_400)
        walks.removeAll { $0.start < cutoff }
        if let open = walks.last, open.end == nil, !open.isDemo { current = open; resume() }
    }

    var isRecording: Bool { current != nil }

    func startWalk() {
        guard current == nil else { return }
        if authorization == .notDetermined { manager.requestWhenInUseAuthorization() }
        let w = PhotoWalk(start: Date())
        walks.append(w)
        current = w
        resume()
        persist()
    }

    private func resume() {
        manager.allowsBackgroundLocationUpdates = true
        manager.showsBackgroundLocationIndicator = true
        manager.startUpdatingLocation()
    }

    func stopWalk() {
        manager.stopUpdatingLocation()
        manager.allowsBackgroundLocationUpdates = false
        guard var w = current else { return }
        w.end = Date()
        replace(w)
        current = nil
        persist()
    }

    func delete(_ walk: PhotoWalk) {
        walks.removeAll { $0.id == walk.id }
        persist()
    }

    /// Demo walk matching the demo roll, so geotagging and the recap map work out of the box.
    func installDemoWalk() {
        walks.removeAll { $0.isDemo }
        let start = DemoRoll.startOfWalk()
        let end = DemoRoll.timestamp(DemoRoll.count)
        var w = PhotoWalk(start: start, end: end, isDemo: true)
        var t = start
        while t <= end {
            let c = DemoRoll.coordinate(at: t)
            w.points.append(TrailPoint(lat: c.latitude, lon: c.longitude, t: t, acc: 8))
            t.addTimeInterval(60)
        }
        walks.append(w)
        persist()
    }

    private func replace(_ w: PhotoWalk) {
        if let i = walks.firstIndex(where: { $0.id == w.id }) { walks[i] = w }
    }

    private func persist() {
        saveTask?.cancel()
        saveTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(1))
            guard let self, !Task.isCancelled else { return }
            Disk.save(self.walks, "walks.json")
        }
    }

    /// Location at `date`, interpolated between trail points (max 15 min gap).
    func location(at date: Date) -> CLLocation? {
        let maxGap: TimeInterval = 15 * 60
        for w in walks where w.start.addingTimeInterval(-maxGap) <= date && (w.end ?? Date()).addingTimeInterval(maxGap) >= date {
            let pts = w.points
            guard !pts.isEmpty else { continue }
            // Binary search for the first point after `date`.
            var lo = 0, hi = pts.count
            while lo < hi { let mid = (lo + hi) / 2; if pts[mid].t < date { lo = mid + 1 } else { hi = mid } }
            let after = lo < pts.count ? pts[lo] : nil
            let before = lo > 0 ? pts[lo - 1] : nil
            switch (before, after) {
            case let (b?, a?):
                let span = a.t.timeIntervalSince(b.t)
                if span > maxGap * 2 { return abs(date.timeIntervalSince(b.t)) < abs(a.t.timeIntervalSince(date)) ? b.location : a.location }
                let r = span > 0 ? date.timeIntervalSince(b.t) / span : 0
                let c = CLLocationCoordinate2D(latitude: b.lat + (a.lat - b.lat) * r, longitude: b.lon + (a.lon - b.lon) * r)
                return CLLocation(coordinate: c, altitude: 0, horizontalAccuracy: max(b.acc, a.acc), verticalAccuracy: -1, timestamp: date)
            case let (b?, nil) where date.timeIntervalSince(b.t) < maxGap: return b.location
            case let (nil, a?) where a.t.timeIntervalSince(date) < maxGap: return a.location
            default: continue
            }
        }
        return nil
    }

    /// Walk overlapping a set of capture dates (for the recap card).
    func walk(covering dates: [Date]) -> PhotoWalk? {
        guard let lo = dates.min(), let hi = dates.max() else { return nil }
        return walks.filter { $0.start <= hi && ($0.end ?? Date()) >= lo }.max { $0.points.count < $1.points.count }
    }

    // MARK: CLLocationManagerDelegate

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        Task { @MainActor in
            guard var w = self.current else { return }
            for l in locations where l.horizontalAccuracy >= 0 && l.horizontalAccuracy < 100 {
                w.points.append(TrailPoint(lat: l.coordinate.latitude, lon: l.coordinate.longitude, t: l.timestamp, acc: l.horizontalAccuracy))
                self.lastFix = l
            }
            self.current = w
            self.replace(w)
            self.persist()
        }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let s = manager.authorizationStatus
        Task { @MainActor in self.authorization = s }
    }
}

/// Fallback: borrow the location of iPhone photos taken around the same time.
/// Most people snap at least one phone picture on a walk — zero setup, zero battery.
enum PhoneLocationLookup {
    static func location(near date: Date, window: TimeInterval = 20 * 60) -> CLLocation? {
        guard PHPhotoLibrary.authorizationStatus(for: .readWrite) == .authorized else { return nil }
        let o = PHFetchOptions()
        o.predicate = NSPredicate(format: "creationDate >= %@ AND creationDate <= %@",
                                  date.addingTimeInterval(-window) as NSDate, date.addingTimeInterval(window) as NSDate)
        o.fetchLimit = 60
        let assets = PHAsset.fetchAssets(with: .image, options: o)
        var best: (CLLocation, TimeInterval)?
        assets.enumerateObjects { a, _, _ in
            guard let l = a.location, let d = a.creationDate else { return }
            let gap = abs(d.timeIntervalSince(date))
            if gap < (best?.1 ?? .infinity) { best = (l, gap) }
        }
        return best?.0
    }
}
