import Foundation
import StoreKit

/// StoreKit 2: yearly subscription + one-time Founder lifetime unlock.
@MainActor
@Observable
final class ProStore {
    static let yearlyID = "com.uranashel.unroll.pro.yearly"
    static let lifetimeID = "com.uranashel.unroll.pro.lifetime"

    private(set) var products: [Product] = []
    private(set) var purchased: Set<String> = []
    var purchasing = false
    var message: String?
    /// Developer override (Settings → Developer) for demos and screenshots.
    var debugUnlock: Bool { didSet { UserDefaults.standard.set(debugUnlock, forKey: "debugPro") } }

    @ObservationIgnored private var updates: Task<Void, Never>?

    init() {
        debugUnlock = UserDefaults.standard.bool(forKey: "debugPro")
        updates = Task { [weak self] in
            for await result in Transaction.updates {
                if case .verified(let t) = result { await t.finish() }
                await self?.refresh()
            }
        }
        Task { await load() }
    }

    var isPro: Bool {
        #if DEBUG
        if debugUnlock { return true }
        #endif
        return !purchased.isEmpty
    }
    var yearly: Product? { products.first { $0.id == ProStore.yearlyID } }
    var lifetime: Product? { products.first { $0.id == ProStore.lifetimeID } }

    func load() async {
        products = (try? await Product.products(for: [ProStore.yearlyID, ProStore.lifetimeID])) ?? []
        await refresh()
    }

    func refresh() async {
        var ids = Set<String>()
        for await result in Transaction.currentEntitlements {
            if case .verified(let t) = result, t.revocationDate == nil { ids.insert(t.productID) }
        }
        purchased = ids
    }

    func buy(_ product: Product) async {
        purchasing = true
        defer { purchasing = false }
        do {
            switch try await product.purchase() {
            case .success(let v):
                if case .verified(let t) = v { await t.finish() }
                await refresh()
            case .pending: message = "Purchase pending approval."
            default: break
            }
        } catch {
            message = error.localizedDescription
        }
    }

    func restore() async {
        try? await AppStore.sync()
        await refresh()
        message = isPro ? "Pro restored. Welcome back!" : "No previous purchase found."
    }
}
