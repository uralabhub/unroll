import Foundation
import StoreKit

/// StoreKit 2: weekly / monthly / yearly subscriptions (one group) + one-time Founder lifetime unlock.
@MainActor
@Observable
final class ProStore {
    static let weeklyID = "com.uranashel.unroll.pro.weekly"
    static let monthlyID = "com.uranashel.unroll.pro.monthly"
    static let yearlyID = "com.uranashel.unroll.pro.yearly"
    static let lifetimeID = "com.uranashel.unroll.pro.lifetime"

    private(set) var products: [Product] = []
    private(set) var purchased: Set<String> = []
    var purchasing = false
    var message: String?
    /// Developer override (Settings → Developer) for demos and screenshots.
    var debugUnlock: Bool { didSet { UserDefaults.standard.set(debugUnlock, forKey: "debugPro") } }

    @ObservationIgnored private var updates: Task<Void, Never>?
    /// Products bought in this run: unlocked at once and kept even if a racing entitlement read lags behind.
    @ObservationIgnored private var justBought = Set<String>()

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
    var weekly: Product? { products.first { $0.id == ProStore.weeklyID } }
    var monthly: Product? { products.first { $0.id == ProStore.monthlyID } }
    var yearly: Product? { products.first { $0.id == ProStore.yearlyID } }
    var lifetime: Product? { products.first { $0.id == ProStore.lifetimeID } }

    func load() async {
        let loaded = (try? await Product.products(for: [ProStore.weeklyID, ProStore.monthlyID, ProStore.yearlyID, ProStore.lifetimeID])) ?? []
        if !loaded.isEmpty || products.isEmpty { products = loaded }
        await refresh()
    }

    /// Products fail to load offline at launch; the paywall calls this to try again.
    func loadIfNeeded() async {
        if products.count < 4 { await load() }
    }


    func refresh() async {
        var ids = Set<String>()
        for await result in Transaction.currentEntitlements {
            if case .verified(let t) = result, t.revocationDate == nil { ids.insert(t.productID) }
        }
        purchased = ids.union(justBought)
    }

    func buy(_ product: Product) async {
        purchasing = true
        message = nil
        defer { purchasing = false }
        do {
            switch try await product.purchase() {
            case .success(let v):
                if case .verified(let t) = v {
                    justBought.insert(t.productID)
                    purchased.insert(t.productID)      // unlock now, before the entitlement read
                    await t.finish()
                } else {
                    message = "The App Store couldn't verify this purchase. Try Restore."
                }
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
