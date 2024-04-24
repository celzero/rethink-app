/*
 * Copyright 2023 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.scheduler

import Logger
import Logger.LOG_TAG_DOWNLOAD
import android.content.Context
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.celzero.bravedns.customdownloader.ITcpProxy
import com.celzero.bravedns.customdownloader.RetrofitManager
import com.celzero.bravedns.service.TcpProxyHelper
import org.json.JSONObject
import org.koin.core.component.KoinComponent
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class PaymentWorker(val context: Context, workerParameters: WorkerParameters) :
    CoroutineWorker(context, workerParameters), KoinComponent {

    companion object {
        val MAX_RETRY_TIMEOUT = TimeUnit.MINUTES.toMillis(40)

        private const val JSON_PAYMENT_STATUS = "payment_status"
        private const val JSON_STATUS = "status"
    }

    override suspend fun doWork(): Result {

        val startTime = inputData.getLong("workerStartTime", 0)

        if (SystemClock.elapsedRealtime() - startTime > MAX_RETRY_TIMEOUT) {
            Logger.w(LOG_TAG_DOWNLOAD, "Payment verification timed out")
            TcpProxyHelper.updatePaymentStatus(TcpProxyHelper.PaymentStatus.FAILED)
            return Result.failure()
        }

        val paymentStatus = getPaymentStatusFromServer()
        Logger.i(
            LOG_TAG_DOWNLOAD,
            "Payment status: $paymentStatus received at ${System.currentTimeMillis()}"
        )

        return when (paymentStatus) {
            TcpProxyHelper.PaymentStatus.PAID -> {
                Result.success()
            }
            TcpProxyHelper.PaymentStatus.FAILED -> {
                Result.failure()
            }
            else -> {
                Result.retry()
            }
        }
    }

    private suspend fun getPaymentStatusFromServer(
        retryCount: Int = 0
    ): TcpProxyHelper.PaymentStatus {
        var paymentStatus = TcpProxyHelper.PaymentStatus.INITIATED
        try {
            val retrofit =
                RetrofitManager.getTcpProxyBaseBuilder(retryCount)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
            val retrofitInterface = retrofit.create(ITcpProxy::class.java)
            // TODO: get refId from EncryptedFile
            val refId = ""
            val response = retrofitInterface.getPaymentStatus(refId)
            Logger.d(
                Logger.LOG_TAG_PROXY,
                "getPaymentStatusFromServer: ${response?.headers()}, ${response?.message()}, ${response?.raw()?.request?.url}"
            )

            if (response?.isSuccessful == true) {
                val jsonObject = JSONObject(response.body().toString())
                val status = jsonObject.optString(JSON_STATUS, "")
                val paymentStatusString = jsonObject.optString(JSON_PAYMENT_STATUS, "")
                paymentStatus =
                    TcpProxyHelper.PaymentStatus.entries.find { it.name == paymentStatusString }
                        ?: TcpProxyHelper.PaymentStatus.NOT_PAID
                Logger.i(
                    Logger.LOG_TAG_PROXY,
                    "getPaymentStatusFromServer: status: $status, paymentStatus: $paymentStatus"
                )
                if (paymentStatus.isPaid() || paymentStatus.isFailed()) {
                    TcpProxyHelper.updatePaymentStatus(paymentStatus)
                }
                return paymentStatus
            } else {
                Logger.w(
                    Logger.LOG_TAG_PROXY,
                    "unsuccessful response for ${response?.raw()?.request?.url}"
                )
            }
        } catch (e: Exception) {
            Logger.w(
                Logger.LOG_TAG_PROXY,
                "getPaymentStatusFromServer: exception while checking payment status",
                e
            )
        }
        return if (
            isRetryRequired(retryCount) && paymentStatus == TcpProxyHelper.PaymentStatus.INITIATED
        ) {
            Logger.i(LOG_TAG_DOWNLOAD, "retrying the payment status check")
            getPaymentStatusFromServer(retryCount + 1)
        } else {
            Logger.i(LOG_TAG_DOWNLOAD, "retry count exceeded, returning null")
            return paymentStatus
        }
    }

    private fun isRetryRequired(retryCount: Int): Boolean {
        return retryCount < RetrofitManager.Companion.OkHttpDnsType.entries.size - 1
    }
}
