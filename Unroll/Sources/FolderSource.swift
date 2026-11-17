import Foundation

/// SD card in a USB-C reader, a folder in Files, or the demo roll — anything with a DCIM tree.
enum FolderSource {
    static var mediaExtensions: Set<String> { Set(ShotFormat.extensions.keys) }

    /// Scans `root` (prefers its DCIM folder). Runs off the main thread; ~4,000 files in well under a second.
    static func scan(_ root: URL) async -> [Shot] {
        await Task.detached(priority: .userInitiated) { scanSync(root) }.value
    }

    static func scanSync(_ root: URL) -> [Shot] {
        let fm = FileManager.default
        let dcim = ["DCIM", "dcim"].map { root.appendingPathComponent($0) }.first { fm.fileExists(atPath: $0.path) }
        // Sony XAVC and AVCHD clips live outside DCIM.
        let videoRoots = ["PRIVATE/M4ROOT/CLIP", "PRIVATE/AVCHD/BDMV/STREAM", "AVF_INFO", "MP_ROOT"]
            .map { root.appendingPathComponent($0) }.filter { fm.fileExists(atPath: $0.path) }
        let roots = dcim.map { [$0] + videoRoots } ?? [root]
        let keys: [URLResourceKey] = [.fileSizeKey, .contentModificationDateKey, .creationDateKey, .isWritableKey, .isRegularFileKey]
        let mediaExtensions = Self.mediaExtensions

        struct Item { let url: URL; let size: Int64; let date: Date; let locked: Bool; let format: ShotFormat }
        var groups: [String: [Item]] = [:]
        for r in roots {
        guard let it = fm.enumerator(at: r, includingPropertiesForKeys: keys, options: [.skipsHiddenFiles, .skipsPackageDescendants]) else { continue }
        for case let url as URL in it {
            let ext = url.pathExtension.lowercased()
            guard mediaExtensions.contains(ext),
                  let v = try? url.resourceValues(forKeys: Set(keys)), v.isRegularFile == true else { continue }
            let item = Item(
                url: url,
                size: Int64(v.fileSize ?? 0),
                date: v.contentModificationDate ?? v.creationDate ?? .distantPast,
                // Cameras implement "Protect" with the FAT read-only attribute.
                locked: v.isWritable == false,
                format: ShotFormat.from(filename: url.lastPathComponent)
            )
            let key = url.deletingPathExtension().path.lowercased()
            groups[key, default: []].append(item)
        }
        }

        // A write-protected card or read-only mount makes *every* file read-only. That's not the
        // camera's per-file Protect, so don't count it.
        let all = groups.values.flatMap { $0 }
        let cardLocked = all.count > 1 && all.allSatisfy(\.locked)
        return groups.values.compactMap { items -> Shot? in
            let developed = items.first { $0.format == .jpeg || $0.format == .heif || $0.format == .video }
            let raw = items.first { $0.format.isRAW }
            guard let main = developed ?? raw else { return nil }
            let primary = ShotFile(name: main.url.lastPathComponent, size: main.size, location: .file(main.url))
            let twin = (developed != nil ? raw : nil).map {
                ShotFile(name: $0.url.lastPathComponent, size: $0.size, location: .file($0.url))
            }
            return Shot(primary: primary, raw: twin, fileDate: main.date, isLocked: !cardLocked && items.contains { $0.locked })
        }
    }
}

/// Ricoh GR III / IIIx Wi-Fi (reverse-engineered HTTP API, no auth, camera at 192.168.0.1).
/// Join the camera's Wi-Fi first. Marked beta. The GR II uses a different API (/_gr/objs) and
/// is not supported; GR IV is untested.
enum RicohGRSource {
    static let base = URL(string: "http://192.168.0.1/v1/")!

    struct Props: Decodable { let model: String? }
    struct Listing: Decodable {
        struct Dir: Decodable { let name: String; let files: [String] }
        let errCode: Int?
        let dirs: [Dir]
    }

    /// Camera Wi-Fi drops requests while it wakes up: retry a few times before giving up.
    private static func fetch(_ url: URL) async throws -> (Data, URLResponse) {
        var last: Error = ShotIOError.noData
        for attempt in 0..<3 {
            do { return try await CameraHTTP.session.data(from: url) } catch { last = error }
            try await Task.sleep(for: .seconds(Double(attempt + 1)))
        }
        throw last
    }

    static func connect() async throws -> (model: String, shots: [Shot]) {
        let (pd, _) = try await fetch(base.appendingPathComponent("props"))
        let model = (try? JSONDecoder().decode(Props.self, from: pd))?.model ?? "Ricoh GR"
        let (ld, lr) = try await fetch(base.appendingPathComponent("photos"))
        let code = (lr as? HTTPURLResponse)?.statusCode ?? 0
        guard code == 200 else { throw ShotIOError.http(code) }
        let listing = try JSONDecoder().decode(Listing.self, from: ld)
        if let e = listing.errCode, e != 200, listing.dirs.isEmpty { throw ShotIOError.http(e) }

        // The list carries no dates or sizes. Folder + file numbers are chronological and don't
        // change when other photos are deleted, so they give a stable ordering date (and so a
        // stable shot ID for the import ledger). Real capture time arrives with the header.
        var shots: [Shot] = []
        var index = 0
        let names = listing.dirs.flatMap { d in d.files.map { (d.name, $0) } }
        var byStem: [String: [(String, String)]] = [:]
        for n in names { byStem["\(n.0)/\((n.1 as NSString).deletingPathExtension)", default: []].append(n) }
        for (_, group) in byStem.sorted(by: { $0.key < $1.key }) {
            index += 1
            func file(_ g: (String, String)) -> ShotFile {
                let url = base.appendingPathComponent("photos").appendingPathComponent(g.0).appendingPathComponent(g.1)
                return ShotFile(name: g.1, size: ShotFile.unknownSize, location: .http(url))
            }
            let dev = group.first { !ShotFormat.from(filename: $0.1).isRAW }
            let raw = group.first { ShotFormat.from(filename: $0.1).isRAW }
            guard let main = dev ?? raw else { continue }
            shots.append(Shot(primary: file(main), raw: dev != nil ? raw.map(file) : nil,
                              fileDate: Date(timeIntervalSince1970: TimeInterval(orderKey(dir: main.0, file: main.1) ?? index)),
                              isLocked: false))
        }
        return (model, shots)
    }

    /// "100RICOH" + "R0001234.DNG" → 1_000_001_234. Nil when the names aren't DCF-style.
    static func orderKey(dir: String, file: String) -> Int? {
        let d = Int(dir.prefix { $0.isNumber })
        let digits = (file as NSString).deletingPathExtension.reversed().prefix { $0.isNumber }
        guard let d, !digits.isEmpty, let n = Int(String(digits.reversed())) else { return nil }
        return d * 10_000_000 + n
    }
}
