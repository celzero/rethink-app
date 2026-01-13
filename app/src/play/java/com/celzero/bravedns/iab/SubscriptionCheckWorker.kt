package com.celzero.bravedns.iab

import Logger
import Logger.LOG_IAB
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.android.billingclient.api.BillingClient.ProductType
import com.celzero.bravedns.iab.InAppBillingHandler.isListenerRegistered
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent

class SubscriptionCheckWorker(
    val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams), KoinComponent {

    private var attempts = 0

    companion object {
        private const val TAG = "SubscriptionCheckWorker"
        const val WORK_NAME = "SubscriptionCheckWorker"
    }

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            try {
                initiateAndFetchPurchases()
                // by default, return success
                Result.success()
            } catch (e: Exception) {
                Logger.e(LOG_IAB, "$TAG; failed: ${e.message}")
                Result.retry()
            }
        }
    }

    private fun initiateAndFetchPurchases() {
        if (InAppBillingHandler.isBillingClientSetup() && isListenerRegistered(listener)) {
            Logger.i(LOG_IAB, "$TAG; initBilling: billing client already setup")
            InAppBillingHandler.fetchPurchases(listOf(ProductType.SUBS, ProductType.INAPP))
            return
        }
        if (InAppBillingHandler.isBillingClientSetup() && !isListenerRegistered(listener)) {
            InAppBillingHandler.registerListener(listener)
            InAppBillingHandler.fetchPurchases(listOf(ProductType.SUBS, ProductType.INAPP))
            Logger.i(LOG_IAB, "$TAG; initBilling: billing listener registered")
            return
        }
        // initiate the billing client, which will also register the listener and fetch purchases
        InAppBillingHandler.initiate(context, listener)
    }

    private fun reinitiate(attempt: Int = 0) {
        if (attempt > 3) {
            Logger.e(LOG_IAB, "$TAG; reinitiate failed after 3 attempts")
            return
        }
        // reinitiate the billing client
        initiateAndFetchPurchases()
    }

    private val listener = object : BillingListener {
        override fun onConnectionResult(isSuccess: Boolean, message: String) {
            Logger.d(LOG_IAB, "$TAG; onConnectionResult: isSuccess: $isSuccess, message: $message")
            if (!isSuccess) {
                Logger.e(LOG_IAB, "$TAG; billing connection failed: $message")
                reinitiate(attempts++)
                return
            }
            // check for the subscription status after the connection is established
            val productType = listOf(ProductType.SUBS, ProductType.INAPP)
            InAppBillingHandler.fetchPurchases(productType)
        }

        override fun purchasesResult(isSuccess: Boolean, purchaseDetailList: List<PurchaseDetail>) {
            // no need to process the purchase details as the InAppBillingHandler will handle it
            Logger.v(LOG_IAB, "$TAG; purchasesResult: isSuccess: $isSuccess, purchaseDetailList: $purchaseDetailList")
            if (!isSuccess) {
                Logger.e(LOG_IAB, "$TAG; failed to fetch purchases")
                reinitiate(attempts++)
                return
            }
            if (purchaseDetailList.isEmpty()) {
                Logger.i(LOG_IAB, "$TAG; no active subscriptions found")
                return
            }
            Logger.i(LOG_IAB, "$TAG; active subscriptions found: $purchaseDetailList")
        }

        override fun productResult(isSuccess: Boolean, productList: List<ProductDetail>) {
            Logger.v(LOG_IAB, "$TAG; productResult: isSuccess: $isSuccess, productList: $productList")
        }
    }

}
