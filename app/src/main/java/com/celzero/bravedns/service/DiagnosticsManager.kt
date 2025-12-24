/*
 * Copyright 2025 RethinkDNS and its authors
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
package com.celzero.bravedns.service

import Logger
import Logger.LOG_TAG_CONNECTION
import android.content.Context
import android.net.ConnectivityDiagnosticsManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.annotation.RequiresApi
import com.celzero.bravedns.util.Daemons
import com.celzero.bravedns.util.Utilities.isAtleastS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent

@RequiresApi(Build.VERSION_CODES.R)
class DiagnosticsManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val listener: DiagnosticsListener
) : ConnectivityDiagnosticsManager.ConnectivityDiagnosticsCallback(), KoinComponent {

    interface DiagnosticsListener {
        suspend fun maybeNetworkStall()
    }

    companion object {
        private const val TAG = "DiagsMgr"
    }

    private fun logd(message: String) {
        Logger.d(LOG_TAG_CONNECTION, "$TAG; $message")
    }

    private var cdm: ConnectivityDiagnosticsManager? = null

    // clear-cap ref: github.com/celzero/rethink-app/issues/347
    private val diagRequest: NetworkRequest = NetworkRequest.Builder().clearCapabilities()
        .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
        .apply { if (isAtleastS()) setIncludeOtherUidNetworks(true) }
        .build()

    override fun onConnectivityReportAvailable(report: ConnectivityDiagnosticsManager.ConnectivityReport) {
        super.onConnectivityReportAvailable(report)
        logd("$TAG; connectivity rpt for nw: ${report.network.networkHandle}, ts: ${report.reportTimestamp}")
        logAdditionalInfo(report.additionalInfo)
        report.networkCapabilities.let { cap ->
            logd("$TAG; nw-cap: ${cap.transportInfo?.toString()}, ownerUid: ${cap.ownerUid}, nwhandle: ${report.network.networkHandle}")
        }
    }

    private fun logAdditionalInfo(additionalInfo: android.os.PersistableBundle?) {
        try {
            logd("$TAG; ---additional info details---")

            if (additionalInfo == null) {
                logd( "$TAG; additional info: null")
                return
            }

            if (additionalInfo.isEmpty) {
                logd("$TAG; additional info: empty bundle")
                return
            }

            // log the size first
            logd("$TAG; additional info bundle size: ${additionalInfo.size()}")

            // iterate through all keys and values
            for (key in additionalInfo.keySet()) {
                @Suppress("DEPRECATION")
                val value = additionalInfo.get(key)
                logd("$TAG; Key: '$key' -> Value: '$value' (${value?.javaClass?.simpleName})")
            }

            logd("$TAG; ---end additional info---")

        } catch (e: Exception) {
            Logger.w(LOG_TAG_CONNECTION, "$TAG; err logging additional info", e)
        }
    }

    override fun onDataStallSuspected(report: ConnectivityDiagnosticsManager.DataStallReport) {
        super.onDataStallSuspected(report)
        val cap = report.networkCapabilities
        val lp = report.linkProperties
        val dm = report.detectionMethod
        // if detection method is TCP/DNS then report the data stall let vpn service decide
        if (dm == ConnectivityDiagnosticsManager.DataStallReport.DETECTION_METHOD_TCP_METRICS || dm == ConnectivityDiagnosticsManager.DataStallReport.DETECTION_METHOD_DNS_EVENTS) {
            val period = report.stallDetails.getString(ConnectivityDiagnosticsManager.DataStallReport.KEY_TCP_METRICS_COLLECTION_PERIOD_MILLIS)
            val failRate = report.stallDetails.getString(ConnectivityDiagnosticsManager.DataStallReport.KEY_TCP_PACKET_FAIL_RATE)
            Logger.d(LOG_TAG_CONNECTION, "$TAG; data stall suspected, method: $dm, cap: ${cap.transportInfo?.toString()}, lp-routes: ${lp.routes}, period: $period, failRate: $failRate")
            scope.launch { listener.maybeNetworkStall() }
        } else {
            Logger.d(LOG_TAG_CONNECTION, "$TAG; data stall suspected, invalid detection method? $dm, cap: ${cap.transportInfo?.toString()}, lp-routes: ${lp.routes}")
        }
    }

    override fun onNetworkConnectivityReported(network: Network, hasConnectivity: Boolean) {
        super.onNetworkConnectivityReported(network, hasConnectivity)
        logd("$TAG; nw-connectivity reported for ${network.networkHandle}, hasConnectivity? $hasConnectivity")
        if (!hasConnectivity) {
            scope.launch {
                logd( "$TAG; report to vpn service, let it decide on the network stall")
                listener.maybeNetworkStall()
            }
        } else {
            // no-op
        }
    }

    fun register() {
        if (!isAtleastS()) return

        if (cdm != null) {
            Logger.w(LOG_TAG_CONNECTION, "$TAG; already registered")
            return
        }

        try {
            cdm = context.getSystemService("connectivity_diagnostics") as ConnectivityDiagnosticsManager
            val executor = Daemons.make("diagExecutor").executor
            cdm?.registerConnectivityDiagnosticsCallback(diagRequest, executor, this)
            logd("$TAG; nw diags mgr registered")
        } catch (e: Exception) {
            Logger.w(LOG_TAG_CONNECTION, "$TAG; err reg diagnostics mgr", e)
        }
    }

    fun unregister() {
        if (!isAtleastS()) return

        try {
            cdm?.unregisterConnectivityDiagnosticsCallback(this)
            logd("$TAG; nw diags mgr unregistered")
        } catch (e: Exception) {
            Logger.w(LOG_TAG_CONNECTION, "$TAG; err unreg diagnostics mgr", e)
        }
    }
}
