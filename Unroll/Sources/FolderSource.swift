import Foundation

/// SD card in a USB-C reader, a folder in Files, or the demo roll — anything with a DCIM tree.
enum FolderSource {
    static let mediaExtensions: Set<String> = [
        "jpg", "jpeg", "heic", "heif", "hif",
        "raf", "cr3", "arw", "nef", "nrw", "dng", "cr2", "orf", "rw2", "pef", "srw", "3fr", "iiq", "rwl", "x3f",
        "mov", "mp4", "mts", "m4v",
    ]

    /// Scans `root` (prefers its DCIM folder). Runs off the main thread; ~4,000 files in well under a second.
    static func scan(_ root: URL) async -> [Shot] {
        await Task.detached(priority: .userInitiated) { scanSync(root) }.value
    }

    static func scanSync(_ root: URL) -> [Shot] {
        let fm = FileManager.default
        let dcim = ["DCIM", "dcim"].map { root.appendingPathComponent($0) }.first { fm.fileExists(atPath: $0.path) } ?? root
        let keys: [URLResourceKey] = [.fileSizeKey, .contentModificationDateKey, .creationDateKey, .isWritableKey, .isRegularFileKey]
        guard let it = fm.enumerator(at: dcim, includingPropertiesForKeys: keys, options: [.skipsHiddenFiles, .skipsPackageDescendants]) else { return [] }

        struct Item { let url: URL; let size: Int64; let date: Date; let locked: Bool; let format: ShotFormat }
        var groups: [String: [Item]] = [:]
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

        return groups.values.compactMap { items -> Shot? in
            let developed = items.first { $0.format == .jpeg || $0.format == .heif || $0.format == .video }
            let raw = items.first { $0.format.isRAW }
            guard let main = developed ?? raw else { return nil }
            let primary = ShotFile(name: main.url.lastPathComponent, size: main.size, location: .file(main.url))
            let twin = (developed != nil ? raw : nil).map {
                ShotFile(name: $0.url.lastPathComponent, size: $0.size, location: .file($0.url))
            }
            return Shot(primary: primary, raw: twin, fileDate: main.date, isLocked: items.contains { $0.locked })
        }
    }
}

/// Ricoh GR II / III / IIIx Wi-Fi (reverse-engineered HTTP API, no auth, camera at 192.168.0.1).
/// Join the camera's Wi-Fi first. Marked beta: untested on GR IV.
enum RicohGRSource {
    static let base = URL(string: "http://192.168.0.1/v1/")!

    struct Props: Decodable { let model: String? }
    struct Listing: Decodable {
        struct Dir: Decodable { let name: String; let files: [String] }
        let errCode: Int?
        let dirs: [Dir]
    }

    static func connect() async throws -> (model: String, shots: [Shot]) {
        let (pd, _) = try await CameraHTTP.session.data(from: base.appendingPathComponent("props"))
        let model = (try? JSONDecoder().decode(Props.self, from: pd))?.model ?? "Ricoh GR"
        let (ld, _) = try await CameraHTTP.session.data(from: base.appendingPathComponent("photos"))
        let listing = try JSONDecoder().decode(Listing.self, from: ld)

        // The list carries no dates or sizes; folder + file numbering is chronological, so we
        // synthesise an ordering date from position (real capture time arrives with the header).
        var shots: [Shot] = []
        var index = 0
        let names = listing.dirs.flatMap { d in d.files.map { (d.name, $0) } }
        var byStem: [String: [(String, String)]] = [:]
        for n in names { byStem["\(n.0)/\((n.1 as NSString).deletingPathExtension)", default: []].append(n) }
        for (_, group) in byStem.sorted(by: { $0.key < $1.key }) {
            index += 1
            func file(_ g: (String, String)) -> ShotFile {
                let url = base.appendingPathComponent("photos").appendingPathComponent(g.0).appendingPathComponent(g.1)
                return ShotFile(name: g.1, size: 1 << 36, location: .http(url))
            }
            let dev = group.first { !ShotFormat.from(filename: $0.1).isRAW }
            let raw = group.first { ShotFormat.from(filename: $0.1).isRAW }
            guard let main = dev ?? raw else { continue }
            shots.append(Shot(primary: file(main), raw: dev != nil ? raw.map(file) : nil,
                              fileDate: Date(timeIntervalSince1970: TimeInterval(index)), isLocked: false))
        }
        return (model, shots)
    }
}
