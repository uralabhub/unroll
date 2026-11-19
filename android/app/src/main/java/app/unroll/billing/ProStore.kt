package app.unroll.billing

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/**
 * Google Play Billing: weekly, monthly and yearly subscriptions (the yearly one has a 7-day free
 * trial, set on its base plan's offer in Play Console) and a one-time Founder lifetime unlock. Mirrors the iOS StoreKit products.
 */
class ProStore(context: Context) {
    companion object {
        const val WEEKLY_ID = "com.uranashel.unroll.pro.weekly"
        const val MONTHLY_ID = "com.uranashel.unroll.pro.monthly"
        const val YEARLY_ID = "com.uranashel.unroll.pro.yearly"
        const val LIFETIME_ID = "com.uranashel.unroll.pro.lifetime"
    }

    private val prefs = context.getSharedPreferences("pro", Context.MODE_PRIVATE)

    var weekly by mutableStateOf<ProductDetails?>(null)
        private set
    var monthly by mutableStateOf<ProductDetails?>(null)
        private set
    var yearly by mutableStateOf<ProductDetails?>(null)
        private set
    var lifetime by mutableStateOf<ProductDetails?>(null)
        private set
    var purchased by mutableStateOf(prefs.getStringSet("owned", emptySet())!!.toSet())
        private set
    var purchasing by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var connected by mutableStateOf(false)
        private set

    /** Products bought in this run: unlocked at once and kept even if a racing query (onResume) lags behind. */
    private val justBought = mutableSetOf<String>()

    private val _debug = mutableStateOf(prefs.getBoolean("debugPro", false))
    /** Developer override (Settings → Developer) for demos and screenshots. */
    var debugUnlock: Boolean
        get() = _debug.value
        set(v) { _debug.value = v; prefs.edit().putBoolean("debugPro", v).apply() }

    val isPro: Boolean get() = (app.unroll.BuildConfig.DEBUG && debugUnlock) || purchased.isNotEmpty()

    private val client: BillingClient = BillingClient.newBuilder(context)
        .setListener { result, purchases -> onPurchases(result, purchases) }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    fun start() {
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                connected = result.responseCode == BillingResponseCode.OK
                if (connected) { load(); refresh() }
            }
            override fun onBillingServiceDisconnected() { connected = false }
        })
    }

    /** Products fail to load when Play is unreachable at launch; the paywall calls this to try again. */
    fun loadIfNeeded() {
        if (weekly != null && monthly != null && yearly != null && lifetime != null) return
        if (connected) load() else start()
    }

    private fun load() {
        fun query(id: String, type: String, set: (ProductDetails?) -> Unit) {
            val p = QueryProductDetailsParams.Product.newBuilder().setProductId(id).setProductType(type).build()
            client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(listOf(p)).build()) { _, res ->
                res.productDetailsList.firstOrNull()?.let(set)
            }
        }
        query(WEEKLY_ID, ProductType.SUBS) { weekly = it }
        query(MONTHLY_ID, ProductType.SUBS) { monthly = it }
        query(YEARLY_ID, ProductType.SUBS) { yearly = it }
        query(LIFETIME_ID, ProductType.INAPP) { lifetime = it }
    }

    /** Current entitlements from Play (subscriptions + one-time). */
    fun refresh(after: (() -> Unit)? = null) {
        val owned = mutableSetOf<String>()
        var pending = 2
        fun done() {
            if (--pending > 0) return
            owned += justBought
            purchased = owned
            prefs.edit().putStringSet("owned", owned).apply()
            after?.invoke()
        }
        for (type in listOf(ProductType.SUBS, ProductType.INAPP)) {
            client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(type).build()) { r, list ->
                if (r.responseCode == BillingResponseCode.OK) {
                    list.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }.forEach { p ->
                        owned += p.products
                        acknowledge(p)
                    }
                } else {
                    owned += purchased      // keep the cached entitlement when Play is unreachable
                }
                done()
            }
        }
    }

    private fun acknowledge(p: Purchase) {
        if (p.isAcknowledged) return
        client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) {}
    }

    private fun onPurchases(result: BillingResult, purchases: List<Purchase>?) {
        purchasing = false
        when (result.responseCode) {
            BillingResponseCode.OK -> {
                purchases.orEmpty().forEach { p ->
                    if (p.purchaseState == Purchase.PurchaseState.PENDING) message = "Purchase pending approval."
                    if (p.purchaseState == Purchase.PurchaseState.PURCHASED) {
                        justBought += p.products
                        purchased = purchased + p.products      // unlock now, before the query round-trip
                        prefs.edit().putStringSet("owned", purchased).apply()
                        acknowledge(p)
                    }
                }
                refresh()
            }
            BillingResponseCode.USER_CANCELED -> Unit
            BillingResponseCode.ITEM_ALREADY_OWNED -> refresh()
            else -> message = result.debugMessage.ifBlank { "Google Play couldn't complete the purchase." }
        }
    }

    /** Subscriptions prefer an offer with a free-trial phase (the yearly 7-day trial) when there is one. */
    fun buy(activity: Activity, product: ProductDetails) {
        val params = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product)
        if (product.productType == ProductType.SUBS) {
            val offers = product.subscriptionOfferDetails.orEmpty()
            val trial = offers.firstOrNull { o -> o.pricingPhases.pricingPhaseList.any { it.priceAmountMicros == 0L } }
            (trial ?: offers.firstOrNull())?.let { params.setOfferToken(it.offerToken) } ?: run {
                message = "This plan isn't available right now."; return
            }
        }
        purchasing = true
        message = null
        val r = client.launchBillingFlow(activity, BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(params.build())).build())
        if (r.responseCode != BillingResponseCode.OK) { purchasing = false; message = r.debugMessage.ifBlank { "Google Play isn't available." } }
    }

    fun restore() = refresh { message = if (isPro) "Pro restored. Welcome back!" else "No previous purchase found." }

    private fun recurringPrice(p: ProductDetails?): String? =
        p?.subscriptionOfferDetails?.flatMap { it.pricingPhases.pricingPhaseList }
            ?.lastOrNull { it.priceAmountMicros > 0 }?.formattedPrice

    val weeklyPrice: String get() = recurringPrice(weekly) ?: "$1.99"
    val monthlyPrice: String get() = recurringPrice(monthly) ?: "$3.99"
    val yearlyPrice: String get() = recurringPrice(yearly) ?: "$14.99"
    val lifetimePrice: String get() = lifetime?.oneTimePurchaseOfferDetails?.formattedPrice ?: "$29.99"
}
