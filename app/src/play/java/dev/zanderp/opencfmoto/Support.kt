// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Play build: donations as consumable in-app products (Google Play forbids links to other payment
 * methods). The products are created in Play Console → Monetize → In-app products with these ids;
 * until they exist the dialog just says donations aren't available. A donation unlocks nothing.
 */
object Support {
    private val PRODUCTS = linkedMapOf(
        "donate_cafe" to R.string.support_coffee,
        "donate_bocadillo" to R.string.support_sandwich,
        "donate_deposito" to R.string.support_tank,
    )

    private val main = Handler(Looper.getMainLooper())
    private var appCtx: Context? = null
    private var client: BillingClient? = null
    private var connecting = false
    private val waiting = mutableListOf<(BillingClient?) -> Unit>()

    private val purchases = PurchasesUpdatedListener { result, list ->
        if (result.responseCode == BillingClient.BillingResponseCode.OK && list != null) {
            list.forEach { consume(it) }
        }
    }

    /** Finishes purchases that completed while the app was closed (e.g. pending payment methods). */
    fun onResume(activity: Activity) {
        connect(activity.applicationContext) { c ->
            c ?: return@connect
            c.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
            ) { r, list -> if (r.responseCode == BillingClient.BillingResponseCode.OK) list.forEach { consume(it) } }
        }
    }

    fun open(activity: Activity) {
        connect(activity.applicationContext) { c ->
            if (c == null) {
                main.post { unavailable(activity) }
                return@connect
            }
            val params = QueryProductDetailsParams.newBuilder().setProductList(
                PRODUCTS.keys.map {
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(it)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                }
            ).build()
            c.queryProductDetailsAsync(params) { r, result ->
                val found = if (r.responseCode == BillingClient.BillingResponseCode.OK) {
                    result.productDetailsList
                        .sortedBy { it.oneTimePurchaseOfferDetails?.priceAmountMicros ?: Long.MAX_VALUE }
                } else emptyList()
                main.post {
                    if (activity.isFinishing || activity.isDestroyed) return@post
                    if (found.isEmpty()) unavailable(activity) else choose(activity, c, found)
                }
            }
        }
    }

    private fun choose(activity: Activity, c: BillingClient, products: List<ProductDetails>) {
        val labels = products.map { pd ->
            val name = PRODUCTS[pd.productId]?.let { activity.getString(it) } ?: pd.name
            val price = pd.oneTimePurchaseOfferDetails?.formattedPrice.orEmpty()
            "$name — $price"
        }.toTypedArray()
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.support_title_play)
            .setItems(labels) { _, i ->
                val flow = BillingFlowParams.newBuilder().setProductDetailsParamsList(
                    listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(products[i]).build())
                ).build()
                c.launchBillingFlow(activity, flow)
            }
            .setNegativeButton(R.string.support_later, null)
            .show()
    }

    private fun consume(p: Purchase) {
        if (p.purchaseState != Purchase.PurchaseState.PURCHASED) return
        val c = client ?: return
        c.consumeAsync(ConsumeParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) { r, _ ->
            if (r.responseCode == BillingClient.BillingResponseCode.OK) {
                LogBus.log("[SUPPORT] donation received ${p.products}")
                val ctx = appCtx ?: return@consumeAsync
                main.post { Toast.makeText(ctx, R.string.support_thanks, Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun unavailable(activity: Activity) {
        if (!activity.isFinishing) Toast.makeText(activity, R.string.support_unavailable, Toast.LENGTH_LONG).show()
    }

    private fun connect(ctx: Context, then: (BillingClient?) -> Unit) {
        main.post {
            appCtx = ctx
            val c = client ?: BillingClient.newBuilder(ctx)
                .setListener(purchases)
                .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                .build()
                .also { client = it }
            if (c.isReady) {
                then(c)
                return@post
            }
            waiting += then
            if (connecting) return@post
            connecting = true
            c.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(r: BillingResult) {
                    main.post {
                        connecting = false
                        val ok = r.responseCode == BillingClient.BillingResponseCode.OK
                        if (!ok) LogBus.log("[SUPPORT] billing unavailable: ${r.responseCode} ${r.debugMessage}")
                        val cbs = waiting.toList()
                        waiting.clear()
                        cbs.forEach { it(if (ok) c else null) }
                    }
                }

                override fun onBillingServiceDisconnected() {
                    main.post { connecting = false }
                }
            })
        }
    }
}
