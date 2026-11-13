import Foundation

/// Tiny JSON-on-disk persistence helper.
enum Disk {
    static var support: URL {
        let u = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }
    static func load<T: Decodable>(_ type: T.Type, _ name: String) -> T? {
        guard let d = try? Data(contentsOf: support.appendingPathComponent(name)) else { return nil }
        return try? JSONDecoder().decode(T.self, from: d)
    }
    static func save<T: Encodable>(_ value: T, _ name: String) {
        guard let d = try? JSONEncoder().encode(value) else { return }
        try? d.write(to: support.appendingPathComponent(name), options: .atomic)
    }
}

/// Parsed headers keyed by shot id — reconnecting the same card is instant.
@MainActor
final class MetaCache {
    struct Entry: Codable { var meta: ShotMeta; var preview: ByteRange? }
    private var map: [String: Entry]
    private var saveTask: Task<Void, Never>?

    init() { map = Disk.load([String: Entry].self, "meta-cache.json") ?? [:] }

    subscript(id: String) -> Entry? {
        get { map[id] }
        set {
            map[id] = newValue
            scheduleSave()
        }
    }

    private func scheduleSave() {
        saveTask?.cancel()
        saveTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(2))
            guard let self, !Task.isCancelled else { return }
            if self.map.count > 30_000 { self.map = [:] }
            Disk.save(self.map, "meta-cache.json")
        }
    }
}

/// Remembers what was already imported so "Import new" never duplicates.
@MainActor
@Observable
final class ImportLedger {
    private(set) var ids: Set<String>
    init() { ids = Disk.load(Set<String>.self, "imported.json") ?? [] }
    func contains(_ id: String) -> Bool { ids.contains(id) }
    func mark(_ id: String) {
        ids.insert(id)
        Disk.save(ids, "imported.json")
    }
    func reset() { ids = []; Disk.save(ids, "imported.json") }
}

/// Every recipe seen on imported shots, grouped by signature.
@MainActor
@Observable
final class RecipeBook {
    struct Item: Codable, Identifiable {
        var id: String { recipe.signature }
        var recipe: FilmRecipe
        var name: String?
        var camera: String?
        var count: Int
        var lastUsed: Date
        var title: String { name ?? recipe.filmSimulation }
    }

    private(set) var items: [Item]
    init() { items = Disk.load([Item].self, "recipes.json") ?? [] }

    var sorted: [Item] { items.sorted { $0.lastUsed > $1.lastUsed } }

    func record(_ recipe: FilmRecipe, camera: String?, date: Date) {
        if let i = items.firstIndex(where: { $0.recipe.signature == recipe.signature }) {
            items[i].count += 1
            items[i].lastUsed = max(items[i].lastUsed, date)
        } else {
            items.append(Item(recipe: recipe, name: nil, camera: camera, count: 1, lastUsed: date))
        }
        Disk.save(items, "recipes.json")
    }

    func rename(_ id: String, to name: String) {
        guard let i = items.firstIndex(where: { $0.id == id }) else { return }
        items[i].name = name.isEmpty ? nil : name
        Disk.save(items, "recipes.json")
    }

    func delete(_ id: String) {
        items.removeAll { $0.id == id }
        Disk.save(items, "recipes.json")
    }
}

enum AutoImportRule: String, CaseIterable, Identifiable {
    case off, keepers, allNew
    var id: String { rawValue }
    var label: String {
        switch self {
        case .off: return "Ask me"
        case .keepers: return "★ & 🔒 shots"
        case .allNew: return "Everything new"
        }
    }
}

enum AlbumMode: String, CaseIterable, Identifiable {
    case single, perCamera, none
    var id: String { rawValue }
    var label: String {
        switch self {
        case .single: return "“Unroll” album"
        case .perCamera: return "One album per camera"
        case .none: return "Library only"
        }
    }
}

/// User preferences (UserDefaults-backed).
@MainActor
@Observable
final class Settings {
    private let d = UserDefaults.standard

    var autoImport: AutoImportRule { didSet { d.set(autoImport.rawValue, forKey: "autoImport") } }
    var includeRAW: Bool { didSet { d.set(includeRAW, forKey: "includeRAW") } }
    var geotag: Bool { didSet { d.set(geotag, forKey: "geotag") } }
    var borrowPhoneLocations: Bool { didSet { d.set(borrowPhoneLocations, forKey: "borrowPhone") } }
    var albumMode: AlbumMode { didSet { d.set(albumMode.rawValue, forKey: "albumMode") } }
    var autoEject: Bool { didSet { d.set(autoEject, forKey: "autoEject") } }
    var hasOnboarded: Bool { didSet { d.set(hasOnboarded, forKey: "onboarded") } }
    /// Seconds the camera clock runs AHEAD of true time, per camera model.
    var clockOffsets: [String: Double] { didSet { d.set(clockOffsets, forKey: "clockOffsets") } }

    init() {
        d.register(defaults: ["includeRAW": true, "geotag": true, "borrowPhone": true, "autoEject": false])
        autoImport = AutoImportRule(rawValue: d.string(forKey: "autoImport") ?? "") ?? .off
        includeRAW = d.bool(forKey: "includeRAW")
        geotag = d.bool(forKey: "geotag")
        borrowPhoneLocations = d.bool(forKey: "borrowPhone")
        albumMode = AlbumMode(rawValue: d.string(forKey: "albumMode") ?? "") ?? .single
        autoEject = d.bool(forKey: "autoEject")
        hasOnboarded = d.bool(forKey: "onboarded")
        clockOffsets = (d.dictionary(forKey: "clockOffsets") as? [String: Double]) ?? [:]
    }

    func clockOffset(for model: String?) -> Double { model.flatMap { clockOffsets[$0] } ?? 0 }
}

/// Free tier: N imports per day. Pro: unlimited.
@MainActor
@Observable
final class Quota {
    static let freePerDay = 10
    private let d = UserDefaults.standard
    private(set) var usedToday: Int

    init() {
        usedToday = d.string(forKey: "quotaDay") == Quota.today ? d.integer(forKey: "quotaUsed") : 0
    }

    static var today: String {
        let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd"; return f.string(from: Date())
    }

    var remaining: Int { max(0, Quota.freePerDay - usedToday) }

    func consume() {
        if d.string(forKey: "quotaDay") != Quota.today { usedToday = 0 }
        usedToday += 1
        d.set(Quota.today, forKey: "quotaDay")
        d.set(usedToday, forKey: "quotaUsed")
    }
}
