/*
 * Copyright 2026 RethinkDNS and its authors
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

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_CONNECTION
import com.celzero.bravedns.util.Utilities.isAtleastS

/**
 * A lightweight network observer that forwards raw network lifecycle events for
 * WiFi and Cellular networks to a [NetworkLifecycleObserver.Listener].
 *
 * For WiFi networks the observer additionally resolves the network SSID, mirroring
 * [ConnectionMonitor.getNetworkSSID] (without the WireGuard/permission notification
 * side-effect, which remains [ConnectionMonitor]'s responsibility). For Cellular
 * networks the SSID is always null and only the event type is forwarded.
 */
class NetworkLifecycleObserver(
    private val context: Context,
    private val listener: Listener
) {

    /**
     * Custom callback invoked by [NetworkLifecycleObserver] whenever a tracked network
     * lifecycle event occurs. Contains a single method so that implementers receive the
     * [Network] involved, an [EventType] describing what happened, and the resolved SSID
     * for WiFi networks (null for Cellular networks or when the SSID cannot be resolved).
     */
    interface Listener {
        fun onNetworkEvent(network: Network, eventType: EventType, ssid: String?)
    }

    /**
     * The set of network lifecycle events reported by [NetworkLifecycleObserver]. Each
     * value corresponds to a method on [ConnectivityManager.NetworkCallback].
     */
    enum class EventType {
        /** A new network matching the request became available. Maps to onAvailable. */
        NETWORK_ADDED,

        /** The network is no longer available. Maps to onLost. */
        NETWORK_LOST,

        /** The network's capabilities changed. Maps to onCapabilitiesChanged. */
        CAPABILITY_CHANGE,

        /** The network's link properties changed. Maps to onLinkPropertiesChanged. */
        LINK_PROPERTY_CHANGE
    }

    // Transport types are used to filter network requests. Only transport types are required here
    // (no NET_CAPABILITY_INTERNET) so the observer acts purely on network type. Two requests
    // are used because transport types within one NetworkRequest are ANDed.
    private val wifiNetworkRequest: NetworkRequest =
        NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

    private val cellularNetworkRequest: NetworkRequest =
        NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()


    @Volatile
    private var connectivityManager: ConnectivityManager? = null
    @Volatile
    private var wifiManager: WifiManager? = null
    @Volatile
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile
    private var cellularCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var stopped: Boolean = false

    private fun createCallback(resolveSsid: Boolean): ConnectivityManager.NetworkCallback {
        val label = if (resolveSsid) "wifi(ssid)" else "cellular"
        Logger.d(LOG_TAG_CONNECTION, "$TAG; createCallback($label), inclLocationInfo? ${resolveSsid && isAtleastS()}")
        return if (resolveSsid && isAtleastS()) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onAvailable(network: Network) =
                    dispatch(network, EventType.NETWORK_ADDED, null, resolveSsid)

                override fun onLost(network: Network) =
                    dispatch(network, EventType.NETWORK_LOST, null, resolveSsid)

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities
                ) = dispatch(network, EventType.CAPABILITY_CHANGE, capabilities, resolveSsid)

                override fun onLinkPropertiesChanged(
                    network: Network,
                    linkProperties: LinkProperties
                ) = dispatch(network, EventType.LINK_PROPERTY_CHANGE, null, resolveSsid)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) =
                    dispatch(network, EventType.NETWORK_ADDED, null, resolveSsid)

                override fun onLost(network: Network) =
                    dispatch(network, EventType.NETWORK_LOST, null, resolveSsid)

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities
                ) = dispatch(network, EventType.CAPABILITY_CHANGE, capabilities, resolveSsid)

                override fun onLinkPropertiesChanged(
                    network: Network,
                    linkProperties: LinkProperties
                ) = dispatch(network, EventType.LINK_PROPERTY_CHANGE, null, resolveSsid)
            }
        }
    }

    /**
     * Routes a callback event to the listener, resolving the SSID when the callback
     * is interested in SSIDs (WiFi) and ignoring it otherwise (Cellular). Drops the event
     * entirely once [stopped]. For WiFi [EventType.NETWORK_LOST] events no SSID/transport
     * is resolved (the network is gone). Otherwise, when capabilities are not provided by
     * the callback (onAvailable / link props), they are looked up from the connectivity
     * manager so the transport can still be determined.
     */
    private fun dispatch(
        network: Network,
        eventType: EventType,
        capabilities: NetworkCapabilities?,
        resolveSsid: Boolean
    ) {
        // Drop callbacks delivered after stop()
        if (stopped) return
        if (!resolveSsid) {
            notify(network, eventType, null, transportLabel(capabilities))
            return
        }
        // For NETWORK_LOST the network is already torn down: getNetworkCapabilities
        // would return null (wasting an IPC) and the SSID is irrelevant on loss, so
        // forward the event without resolving either.
        if (eventType == EventType.NETWORK_LOST) {
            notify(network, eventType, null, transportLabel(capabilities))
            return
        }
        val cap = capabilities ?: connectivityManager?.getNetworkCapabilities(network)
        notify(network, eventType, getNetworkSSID(network, cap), transportLabel(cap))
    }

    private fun transportLabel(cap: NetworkCapabilities?): String {
        return when {
            cap?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
            cap?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
            cap == null -> "unknown(no-cap)"
            else -> "other"
        }
    }

    /**
     * Resolves the SSID for a WiFi network, mirroring [ConnectionMonitor.getNetworkSSID]
     * but without the WireGuard/permission notification side-effect.
     *
     * @param network The network to get the SSID for.
     * @param cap The network's capabilities (used both to confirm WiFi transport and to
     *            read transportInfo on S+); may be null, in which case null is returned.
     * @return The cleaned SSID for the WiFi network, or null if the network is not WiFi
     *         or the SSID could not be resolved.
     */
    private fun getNetworkSSID(network: Network?, cap: NetworkCapabilities?): String? {
        if (network == null) {
            Logger.d(LOG_TAG_CONNECTION, "$TAG; getNetworkSSID: network is null")
            return null
        }

        // Only WiFi networks carry an SSID.
        if (cap?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) {
            Logger.d(
                LOG_TAG_CONNECTION,
                "$TAG; getNetworkSSID: network ${network.networkHandle} is not WiFi"
            )
            return null
        }

        try {
            if (isAtleastS()) {
                val transportInfo = cap.transportInfo
                if (transportInfo is WifiInfo) {
                    extractCleanSsid(transportInfo.ssid)?.let { return it }
                }
                Logger.v(
                    LOG_TAG_CONNECTION,
                    "$TAG; getNetworkSSID: transportInfo not WifiInfo (${transportInfo?.javaClass?.simpleName}) for ${network.networkHandle}, falling back to WifiManager"
                )
            }

            val wm = wifiManager
                ?: (context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                    ?.also { wifiManager = it }
            // Deprecated since API 31 and a synchronous IPC. Used only as a fallback
            // when transportInfo is unavailable (pre-S, or non-WifiInfo transportInfo)
            // and runs on the binder callback thread; best-effort and never blocks
            // start()/stop() (which do not touch WifiManager).
            @Suppress("DEPRECATION")
            val wifiInfo = wm?.connectionInfo
            if (wifiInfo != null) {
                extractCleanSsid(wifiInfo.ssid)?.let { return it }
            }

            Logger.d(
                LOG_TAG_CONNECTION,
                "$TAG; getNetworkSSID: SSID unavailable for ${network.networkHandle}, wmInfo? ${wifiInfo != null}"
            )
            return null
        } catch (e: SecurityException) {
            Logger.w(LOG_TAG_CONNECTION, "$TAG; getNetworkSSID SecurityException", e)
            return null
        } catch (e: Exception) {
            Logger.w(LOG_TAG_CONNECTION, "$TAG; getNetworkSSID err", e)
            return null
        }
    }

    private fun extractCleanSsid(ssid: String?): String? {
        if (ssid.isNullOrEmpty() || ssid == UNKNOWN_SSID) return null
        return ssid.removeSurrounding("\"")
    }

    // Forwards the event to the listener. No processing or business logic is performed
    // here beyond SSID resolution
    private fun notify(
        network: Network,
        eventType: EventType,
        ssid: String?,
        transport: String
    ) {
        Logger.d(
            LOG_TAG_CONNECTION,
            "$TAG; ${eventType.name}, transport: $transport, nwHandle: ${network.networkHandle}, ssid: $ssid"
        )
        listener.onNetworkEvent(network, eventType, ssid)
    }

    /**
     * Registers the network callbacks
     *
     * @return` true if the Cellular callback registered successfully (WiFi is
     *         best-effort and may fail), or the observer was already active;
     *         false if the connectivity manager is unavailable or the Cellular
     *         registration threw (any successful WiFi callback is rolled back).
     */
    fun start(): Boolean {
        if (wifiCallback != null || cellularCallback != null) {
            Logger.d(LOG_TAG_CONNECTION, "$TAG; already started, ignoring start()")
            return true
        }
        Logger.i(LOG_TAG_CONNECTION, "$TAG; start()")
        val manager = connectivityManager
            ?: (context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager)?.also { connectivityManager = it }
        if (manager == null) {
            Logger.w(LOG_TAG_CONNECTION, "$TAG; ConnectivityManager unavailable")
            return false
        }
        // (Re)enable forwarding before any callback is registered, so that the new
        // callbacks are not dropped by the stopped-guard in dispatch().
        stopped = false
        // WiFi and Cellular are independent transports, so each registration is
        // attempted independently: a failing WiFi registration no longer prevents
        // cellular-only observation. The atomic rollback contract is preserved for
        // the case where WiFi succeeded but Cellular then fails.
        val wifiCb = createCallback(resolveSsid = true)
        var wifiRegistered = false
        try {
            manager.registerNetworkCallback(wifiNetworkRequest, wifiCb)
            wifiCallback = wifiCb
            wifiRegistered = true
            Logger.i(LOG_TAG_CONNECTION, "$TAG; wifi callback registered, inclLocationInfo? ${isAtleastS()}")
        } catch (e: Exception) {
            Logger.w(LOG_TAG_CONNECTION, "$TAG; err registering wifi callback", e)
        }

        // Cellular registration is attempted regardless of the WiFi outcome.
        val cellCb = createCallback(resolveSsid = false)
        try {
            manager.registerNetworkCallback(cellularNetworkRequest, cellCb)
            cellularCallback = cellCb
            Logger.i(LOG_TAG_CONNECTION, "$TAG; cellular callback registered")
        } catch (e: Exception) {
            if (wifiRegistered) {
                // Roll back the WiFi callback so start() stays atomic when Cellular
                // fails alongside a successful WiFi registration.
                Logger.w(LOG_TAG_CONNECTION, "$TAG; err registering cellular callback, rolling back wifi", e)
                wifiCallback = null
                try {
                    manager.unregisterNetworkCallback(wifiCb)
                } catch (e2: Exception) {
                    Logger.w(LOG_TAG_CONNECTION, "$TAG; err rolling back wifi callback", e2)
                }
            } else {
                Logger.w(LOG_TAG_CONNECTION, "$TAG; err registering cellular callback", e)
            }
            return false
        }

        Logger.i(LOG_TAG_CONNECTION, "$TAG; start() ok, wifi? $wifiRegistered, cellular? true")
        return true
    }

    /**
     * Unregisters the network callbacks if any are active and sets the [stopped] guard
     * so that any callback delivered after this returns (the framework does not
     * synchronously cancel in-flight callbacks) is dropped in [dispatch].
     */
    fun stop() {
        // Set the guard first: any callback dispatched after this point (including
        // those already queued on the binder thread) is dropped in dispatch().
        stopped = true
        val manager = connectivityManager
        val cbs = listOfNotNull(wifiCallback, cellularCallback)
        wifiCallback = null
        cellularCallback = null
        if (cbs.isEmpty()) {
            Logger.d(LOG_TAG_CONNECTION, "$TAG; stop() no-op, not started")
            return
        }
        Logger.i(LOG_TAG_CONNECTION, "$TAG; stop(), unregistering ${cbs.size} callback/s")
        for (cb in cbs) {
            try {
                manager?.unregisterNetworkCallback(cb)
            } catch (e: Exception) {
                Logger.w(LOG_TAG_CONNECTION, "$TAG; err unregistering callback", e)
            }
        }
        // Intentionally NOT clearing connectivityManager / wifiManager: these are
        // app-wide singletons obtained from applicationContext, so holding them is
        // not a leak, and reusing them avoids a getSystemService lookup on re-start.
    }

    companion object {
        private const val UNKNOWN_SSID = "<unknown ssid>"
        private const val TAG = "NetworkLifecycleObserver"
    }
}
