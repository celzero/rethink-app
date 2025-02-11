package com.celzero.bravedns.iab

import Logger
import Logger.LOG_IAB
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.Purchase
import com.celzero.bravedns.iab.InAppBillingHandler.isListenerRegistered
import com.celzero.bravedns.service.PersistentState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class SubscriptionCheckWorker(
    val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams), KoinComponent {

    private val persistentState by inject<PersistentState>()
    private var attempts = 0

    companion object {
        const val WORK_NAME = "SubscriptionCheckWorker"
    }

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            try {
                initiate()
                // by default, return success
                Result.success()
            } catch (e: Exception) {
                Logger.e(LOG_IAB, "$WORK_NAME; failed: ${e.message}")
                Result.retry()
            }
        }
    }

    private fun initiate() {
        if (InAppBillingHandler.isBillingClientSetup() && isListenerRegistered(listener)) {
            Logger.i(LOG_IAB, "initBilling: billing client already setup")
            return
        }
        if (InAppBillingHandler.isBillingClientSetup() && !isListenerRegistered(listener)) {
            InAppBillingHandler.registerListener(listener)
            Logger.i(LOG_IAB, "initBilling: billing listener registered")
            return
        }
        InAppBillingHandler.initiate(context, listener)
    }

    private fun reinitiate(attempt: Int = 0) {
        if (attempt > 3) {
            Logger.e(LOG_IAB, "$WORK_NAME; reinitiate failed after 3 attempts")
            return
        }
        // reinitiate the billing client
        initiate()
    }

    private val listener = object : BillingListener {
        override fun onConnectionResult(isSuccess: Boolean, message: String) {
            Logger.d(LOG_IAB, "$WORK_NAME; onConnectionResult: isSuccess: $isSuccess, message: $message")
            if (!isSuccess) {
                Logger.e(LOG_IAB, "$WORK_NAME;Billing connection failed: $message")
                reinitiate(attempts++)
                return
            }
            // check for the subscription status after the connection is established
            val productType = listOf(ProductType.SUBS)
            InAppBillingHandler.fetchPurchases(productType)
        }

        override fun purchasesResult(isSuccess: Boolean, purchaseDetailList: List<PurchaseDetail>) {
            val first = purchaseDetailList.firstOrNull()
            if (first == null) {
                Logger.d(LOG_IAB, "$WORK_NAME; No purchases found")
                persistentState.enableWarp = false
                return
            }
            if (first.productType == ProductType.SUBS) {
                Logger.d(LOG_IAB, "$WORK_NAME; Subscription found: ${first.state}")
                persistentState.enableWarp = first.state == Purchase.PurchaseState.PURCHASED
            } else {
                Logger.d(LOG_IAB, "$WORK_NAME; No subscription found")
                persistentState.enableWarp = false
            }
        }

        override fun productResult(isSuccess: Boolean, productList: List<ProductDetail>) {
            Logger.v(LOG_IAB, "$WORK_NAME; productResult: isSuccess: $isSuccess, productList: $productList")
        }
    }

}
