/*
 * Copyright 2021 RethinkDNS and its authors
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
import Logger.LOG_TAG_VPN
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Context.NOTIFICATION_SERVICE
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.service.FirewallManager.NOTIF_CHANNEL_ID_FIREWALL_ALERTS
import com.celzero.bravedns.service.VpnBuilderPolicy.Companion.getNetworkBehaviourDuration
import com.celzero.bravedns.service.WireguardManager.NOTIF_CHANNEL_ID_WIREGUARD_ALERTS
import com.celzero.bravedns.ui.NotificationHandlerActivity
import com.celzero.bravedns.util.ConnectivityCheckHelper
import com.celzero.bravedns.util.Constants.Companion.NOTIF_WG_PERMISSION_NAME
import com.celzero.bravedns.util.Constants.Companion.NOTIF_WG_PERMISSION_VALUE
import com.celzero.bravedns.util.InternetProtocol
import com.celzero.bravedns.util.SsidPermissionManager
import com.celzero.bravedns.util.UIUtils.getAccentColor
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastO
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.Utilities.isAtleastR
import com.celzero.bravedns.util.Utilities.isAtleastS
import com.celzero.bravedns.util.Utilities.isNetworkSame
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

class ConnectionMonitor(private val context: Context, private val networkListener: NetworkListener, private val serializer: CoroutineDispatcher, private val scope: CoroutineScope) : KoinComponent, DiagnosticsManager.DiagnosticsListener {

    private val networkSet: MutableSet<NetworkAndSsid> = ConcurrentHashMap.newKeySet()
    data class NetworkAndSsid(val network: Network, val ssid: String?)

    // Connectivity check tracking
    private data class ConnectivityCheckState(
        val lastCheckTime: Long,
        val networkHandles: Set<Long>
    )

    private var lastConnectivityCheckState: ConnectivityCheckState? = null

    // create drop oldest channel to handle the network changes from the connectivity manager
    private lateinit var channel: Channel<OpPrefs>

    /**
     * Checks if connectivity check should be performed based on event type and timing
     */
    private fun shouldPerformConnectivityCheck(eventType: String): Boolean {
        val currentTime = SystemClock.elapsedRealtime()
        val currentNetworkHandles = networkSet.map { it.network.networkHandle }.toSet()

        when (eventType) {
            EVENT_ON_AVAILABLE, EVENT_ON_LOST, EVENT_ON_LINK_PROPERTIES_CHANGED -> {
                // Always perform connectivity check for these events
                updateLastConnectivityCheckState(currentTime, currentNetworkHandles)
                Logger.d(LOG_TAG_CONNECTION, "Connectivity check approved for $eventType")
                return true
            }
            EVENT_ON_CAPABILITIES_CHANGED -> {
                val lastState = lastConnectivityCheckState

                // Check if 15 seconds have passed since last check
                val timeSinceLastCheck = if (lastState != null) {
                    currentTime - lastState.lastCheckTime
                } else {
                    Long.MAX_VALUE // First time, always check
                }

                // Check if network handles have changed
                val networkHandlesChanged = if (lastState != null) {
                    lastState.networkHandles != currentNetworkHandles
                } else {
                    true // First time, consider as changed
                }

                val shouldCheck = timeSinceLastCheck >= CONNECTIVITY_CHECK_INTERVAL_MS || networkHandlesChanged

                if (shouldCheck) {
                    updateLastConnectivityCheckState(currentTime, currentNetworkHandles)
                    Logger.d(LOG_TAG_CONNECTION, "Connectivity check approved for $eventType - time since last: ${timeSinceLastCheck}ms, network changed: $networkHandlesChanged")
                } else {
                    Logger.d(LOG_TAG_CONNECTION, "Connectivity check skipped for $eventType - time since last: ${timeSinceLastCheck}ms, network changed: $networkHandlesChanged")
                }

                return shouldCheck
            }
            else -> {
                Logger.w(LOG_TAG_CONNECTION, "Unknown event type: $eventType")
                return false
            }
        }
    }

    /**
     * Updates the last connectivity check state
     */
    private fun updateLastConnectivityCheckState(time: Long, networkHandles: Set<Long>) {
        lastConnectivityCheckState = ConnectivityCheckState(time, networkHandles)
    }

    fun internetValidatedCallback(): ConnectivityManager.NetworkCallback {
        return if (isAtleastS()) {
            // Only called on S+ devices
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    scope.launch(CoroutineName("cmIntCap") + serializer) {
                        Logger.d(LOG_TAG_CONNECTION, "onCapabilitiesChanged(1S), ${network.networkHandle}, netId: ${netId(network.networkHandle)}")
                        val ssid = getNetworkSSID(network, capabilities)
                        addToNwSet(network, ssid)
                        if (shouldPerformConnectivityCheck(EVENT_ON_CAPABILITIES_CHANGED)) {
                            sendNetworkChanges()
                        }
                    }
                }

                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    scope.launch(CoroutineName("cmIntLink") + serializer) {
                        Logger.d(LOG_TAG_CONNECTION, "onLinkPropertiesChanged(1S), ${network.networkHandle}, netId: ${netId(network.networkHandle)}")
                        addToNwSet(network)
                        if (shouldPerformConnectivityCheck(EVENT_ON_LINK_PROPERTIES_CHANGED)) {
                            sendNetworkChanges()
                        }
                    }
                }

                override fun onAvailable(network: Network) {
                    val behaviour = getConnectionMonitorBehaviour()
                    if (behaviour != VpnBuilderPolicy.ConnectionMonitorBehaviour.VALIDATED_NETWORKS) {
                        // no-op, as we expect the transportCallback to add to network set and send message
                        Logger.d(LOG_TAG_CONNECTION, "onAvailable(1S), aggressive policy, ignoring networks from net-validated callback")
                        return
                    }
                    scope.launch(CoroutineName("cmIntAvl") + serializer) {
                        Logger.d(LOG_TAG_CONNECTION, "onAvailable(1S), ${network.networkHandle}, netId: ${netId(network.networkHandle)}")
                        addToNwSet(network)
                        if (shouldPerformConnectivityCheck(EVENT_ON_AVAILABLE)) {
                            sendNetworkChanges()
                        }
                    }
                }

                override fun onLost(network: Network) {
                    val behaviour = getConnectionMonitorBehaviour()
                    if (behaviour != VpnBuilderPolicy.ConnectionMonitorBehaviour.VALIDATED_NETWORKS) {
                        // no-op, as we expect the transportCallback to add to network set and send message
                        Logger.d(LOG_TAG_CONNECTION, "onLost(1S), aggressive policy, ignoring networks from net-cap callback")
                        return
                    }
                    scope.launch(CoroutineName("cmIntLost") + serializer) {
                        Logger.d(LOG_TAG_CONNECTION, "onLost(1S), ${network.networkHandle}, netId: ${netId(network.networkHandle)}")
                        removeFromNwSet(network)
                        if (shouldPerformConnectivityCheck(EVENT_ON_LOST)) {
                            sendNetworkChanges()
                        }
                    }
                }
            }
        } else {
            // Pre-S devices
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    scope.launch(CoroutineName("cmTransCap") + serializer) {
                        Logger.d(LOG_TAG_CONNECTION, "onCapabilitiesChanged(2), ${network.networkHandle}, netId: ${netId(network.networkHandle)}")
                        val ssid = getNetworkSSID(network, capabilities)
                        addToNwSet(network, ssid)
                        if (shouldPerformConnectivityCheck(EVENT_ON_CAPABILITIES_CHANGED)) {
                            sendNetworkChanges()
                        }
                    }
                }

                override fun onAvailable(network: Network) {
                    scope.launch(CoroutineName("cmTransAvl") + serializer) {
                        Logger.d(LOG_TAG_CONNECTION, "onAvailable(2), ${network.networkHandle}, netId: ${netId(network.networkHandle)}")
                        addToNwSet(network)
                        if (shouldPerformConnectivityCheck(EVENT_ON_AVAILABLE)) {
                            sendNetworkChanges()
                        }
                    }
                }

                override fun onLost(network: Network) {
                    scope.launch(CoroutineName("cmTransLost") + serializer) {
                        Logger.d(LOG_TAG_CONNECTION, "onLost(2), ${network.networkHandle}, netId: ${netId(network.networkHandle)}")
                        removeFromNwSet(network)
                        if (shouldPerformConnectivityCheck(EVENT_ON_LOST)) {
                            sendNetworkChanges()
                        }
                    }
                }

                override fun onLinkPropertiesChanged(
                    network: Network,
                    linkProperties: LinkProperties
                ) {
                    scope.launch(CoroutineName("cmTransLink") + serializer) {
                        Logger.d(LOG_TAG_CONNECTION, "onLinkPropertiesChanged(2), ${network.networkHandle}, netId: ${netId(network.networkHandle)}")
                        addToNwSet(network)
                        if (shouldPerformConnectivityCheck(EVENT_ON_LINK_PROPERTIES_CHANGED)) {
                            sendNetworkChanges()
                        }
                    }
                }
            }
        }
    }

    fun transportCallback(): ConnectivityManager.NetworkCallback {
        return if (isAtleastS()) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    scope.launch(CoroutineName("cmTransCap") + serializer) {
                        Logger.d(LOG_TAG_CONNECTION, "onCapabilitiesChanged(2S), ${network.networkHandle}, netId: ${netId(network.networkHandle)}")
                        val ssid = getNetworkSSID(network, capabilities)
                        addToNwSet(network, ssid)
                        if (shouldPerformConnectivityCheck(EVENT_ON_CAPABILITIES_CHANGED)) {
                            sendNetworkChanges()
                        }
                    }
                }

                override fun onAvailable(network: Network) {
                    scope.launch(CoroutineName("cmTransAvl") + serializer) {
                        Logger.d(LOG_TAG_CONNECTION, "onAvailable(2S), ${network.networkHandle}, netId: ${netId(network.networkHandle)}")
                        addToNwSet(network)
                        if (shouldPerformConnectivityCheck(EVENT_ON_AVAILABLE)) {
                            sendNetworkChanges()
                        }
                    }
                }

                override fun onLost(network: Network) {
                    scope.launch(CoroutineName("cmTransLost") + serializer) {
                        Logger.d(LOG_TAG_CONNECTION, "onLost(2S), ${network.networkHandle}, netId: ${netId(network.networkHandle)}")
                        removeFromNwSet(network)
                        if (shouldPerformConnectivityCheck(EVENT_ON_LOST)) {
                            sendNetworkChanges()
                        }
                    }
                }

                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    scope.launch(CoroutineName("cmTransLink") + serializer) {
                        Logger.d(LOG_TAG_CONNECTION, "onLinkPropertiesChanged(2S), ${network.networkHandle}, netId: ${netId(network.networkHandle)}")
                        addToNwSet(network)
                        if (shouldPerformConnectivityCheck(EVENT_ON_LINK_PROPERTIES_CHANGED)) {
                            sendNetworkChanges()
                        }
                    }
                }
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    scope.launch(CoroutineName("cmTransCap") + serializer) {
                        Logger.d(LOG_TAG_CONNECTION, "onCapabilitiesChanged(2), ${network.networkHandle}, netId: ${netId(network.networkHandle)}")
                        val ssid = getNetworkSSID(network, capabilities)
                        addToNwSet(network, ssid)
                        if (shouldPerformConnectivityCheck(EVENT_ON_CAPABILITIES_CHANGED)) {
                            sendNetworkChanges()
                        }
                    }
                }

                override fun onAvailable(network: Network) {
                    scope.launch(CoroutineName("cmTransAvl") + serializer) {
                        Logger.d(LOG_TAG_CONNECTION, "onAvailable(2), ${network.networkHandle}, netId: ${netId(network.networkHandle)}")
                        addToNwSet(network)
                        if (shouldPerformConnectivityCheck(EVENT_ON_AVAILABLE)) {
                            sendNetworkChanges()
                        }
                    }
                }

                override fun onLost(network: Network) {
                    scope.launch(CoroutineName("cmTransLost") + serializer) {
                        Logger.d(LOG_TAG_CONNECTION, "onLost(2), ${network.networkHandle}, netId: ${netId(network.networkHandle)}")
                        removeFromNwSet(network)
                        if (shouldPerformConnectivityCheck(EVENT_ON_LOST)) {
                            sendNetworkChanges()
                        }
                    }
                }

                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    scope.launch(CoroutineName("cmTransLink") + serializer) {
                        Logger.d(LOG_TAG_CONNECTION, "onLinkPropertiesChanged(2), ${network.networkHandle}, netId: ${netId(network.networkHandle)}")
                        addToNwSet(network)
                        if (shouldPerformConnectivityCheck(EVENT_ON_LINK_PROPERTIES_CHANGED)) {
                            sendNetworkChanges()
                        }
                    }
                }
            }
        }
    }


    /**
     * Fetches the SSID for the given network if it's a WiFi network.
     *
     * @param network The network to get the SSID for
     * @return The SSID of the network if it's a WiFi network and SSID is available,
     *         null otherwise or if the network is not WiFi
     */
    fun getNetworkSSID(network: Network?, cap: NetworkCapabilities?): String? {
        if (network == null) {
            Logger.d(LOG_TAG_CONNECTION, "getNetworkSSID: network is null")
            return null
        }

        // Check if this is a WiFi network
        if (cap?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) {
            Logger.v(
                LOG_TAG_CONNECTION,
                "getNetworkSSID: network ${network.networkHandle} is not a WiFi network"
            )
            return null
        }

        try {
            if (isAtleastS()) {
                // Check if transportInfo is actually WifiInfo before casting
                val transportInfo = cap.transportInfo
                val isTransportInfoWifi = transportInfo is WifiInfo
                var ssid: String? = null
                if (isTransportInfoWifi) {
                    ssid = transportInfo.ssid
                    extractCleanSsid(ssid)?.let { clean ->
                        Logger.i(
                            LOG_TAG_CONNECTION,
                            "getNetworkSSID(S): $clean (${network.networkHandle})"
                        )
                        return clean
                    }
                }
                Logger.v(
                    LOG_TAG_CONNECTION,
                    "isWifiInfo? $isTransportInfoWifi, (${transportInfo?.javaClass?.simpleName}), ssid: $ssid , falling back to WifiManager"
                )
            }

            @Suppress("DEPRECATION")
            val wifiInfo: WifiInfo? = wm.connectionInfo
            if (wifiInfo == null) {
                Logger.v(LOG_TAG_CONNECTION, "getNetworkSSID: WifiInfo is null")
                return null
            }

            extractCleanSsid(wifiInfo.ssid)?.let { clean ->
                Logger.i(
                    LOG_TAG_CONNECTION,
                    "getNetworkSSID(WM): $clean (${network.networkHandle})"
                )
                return clean
            }

            Logger.w(
                LOG_TAG_CONNECTION,
                "getNetworkSSID: SSID is null or unknown for network ${network.networkHandle}"
            )
            showNotificationIfNeeded()
            return null

        } catch (e: SecurityException) {
            Logger.w(LOG_TAG_CONNECTION, "getNetworkSSID: SecurityException accessing WiFi info", e)
            return null
        } catch (e: Exception) {
            Logger.w(
                LOG_TAG_CONNECTION,
                "getNetworkSSID: Exception getting SSID for network ${network.networkHandle}",
                e
            )
            return null
        }
    }

    private fun extractCleanSsid(ssid: String?): String? {
        if (ssid.isNullOrEmpty() || ssid == UNKNOWN_SSID) return null
        return ssid.removeSurrounding("\"")
    }

    private fun showNotificationIfNeeded() {
        val wgs = WireguardManager.getActiveSsidEnabledConfigs()
        if (wgs.isEmpty()) return

        val hasPermission = SsidPermissionManager.hasRequiredPermissions(context)
        val locationEnabled = SsidPermissionManager.isLocationEnabled(context)
        if (hasPermission && locationEnabled) return

        val notificationManager = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // check if notification is already active to prevent duplicates
        val activeNotifications = notificationManager.activeNotifications
        val isNotificationAlreadyActive = activeNotifications.any { notification ->
            notification.id == NOTIF_ID_SSID_LOCATION_PERMISSION
        }
        if (isNotificationAlreadyActive) {
            Logger.i(LOG_TAG_VPN, "ssid wgs: notification already active, skipping")
            return
        }

        Logger.w(LOG_TAG_VPN, "ssid wgs: missing permissions, show notification")
        val intent = Intent(context, NotificationHandlerActivity::class.java)
        intent.putExtra(
            NOTIF_WG_PERMISSION_NAME,
            NOTIF_WG_PERMISSION_VALUE
        )
        val pendingIntent =
            Utilities.getActivityPendingIntent(
                context,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                mutable = false
            )

        var builder: NotificationCompat.Builder
        if (isAtleastO()) {
            val name: CharSequence = context.getString(R.string.notif_channel_firewall_alerts)
            val description = context.resources.getString(R.string.notif_channel_desc_firewall_alerts)
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(NOTIF_CHANNEL_ID_WIREGUARD_ALERTS, name, importance)
            channel.description = description
            notificationManager.createNotificationChannel(channel)
            builder = NotificationCompat.Builder(context, NOTIF_CHANNEL_ID_WIREGUARD_ALERTS)
        } else {
            builder = NotificationCompat.Builder(context, NOTIF_CHANNEL_ID_WIREGUARD_ALERTS)
        }

        val contentTitle: String = context.resources.getString(R.string.lbl_action_required)
        val contentText: String =
            context.getString(R.string.location_enable_explanation, context.getString(R.string.lbl_ssids))

        builder
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentTitle(contentTitle)
            .setContentIntent(pendingIntent)
            .setContentText(contentText)

        builder.setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
        builder.color = ContextCompat.getColor(context, getAccentColor(persistentState.theme))

        // Secret notifications are not shown on the lock screen.  No need for this app to show
        // there.
        // Only available in API >= 21
        builder = builder.setVisibility(NotificationCompat.VISIBILITY_SECRET)

        // Cancel the notification after clicking.
        builder.setAutoCancel(true)

        notificationManager.notify(
            NOTIF_CHANNEL_ID_FIREWALL_ALERTS,
            NOTIF_ID_SSID_LOCATION_PERMISSION,
            builder.build()
        )
    }

    private val networkRequest: NetworkRequest =
        NetworkRequest.Builder()
            .apply { if (isAtleastR()) clearCapabilities() else removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) }
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .apply { if (isAtleastS()) setIncludeOtherUidNetworks(true) }
            .build()

    private val networkRequestWithTransports: NetworkRequest =
        NetworkRequest.Builder()
            .apply { if (isAtleastR()) clearCapabilities() else removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) }
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .apply { if (isAtleastS()) setIncludeOtherUidNetworks(true) }
            // api27: .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            // api26: .addTransportType(NetworkCapabilities.TRANSPORT_LOWPAN)
            .build()

    private val persistentState by inject<PersistentState>()

    private lateinit var cm: ConnectivityManager
    private lateinit var wm: WifiManager

    private var diagsMgr: DiagnosticsManager? = null

    companion object {
        // add active network as underlying vpn network
        const val MSG_ADD_ACTIVE_NETWORK = 1

        // add all available networks as underlying vpn networks
        const val MSG_ADD_ALL_NETWORKS = 2

        // below constants are used for probe connectivity checks in Auto mode
        const val PROTOCOL_V4 = "v4"
        const val PROTOCOL_V6 = "v6"
        const val SCHEME_HTTP = "http"
        const val SCHEME_HTTPS = "https"
        const val SCHEME_IP = "ip"

        private const val UNKNOWN_SSID = "<unknown ssid>"

        const val NOTIF_ID_SSID_LOCATION_PERMISSION = 105

        // variable to check whether to rely on the TCP/UDP reachability checks from
        // kotlin end instead of tunnel reachability checks, set false by default for now
        // TODO: set it to true when the reachability checks are required to be done from
        // kotlin end
        const val USE_KOTLIN_REACHABILITY_CHECKS = false

        fun networkType(netCap: NetworkCapabilities?): String {
            val a =
                if (netCap?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
                    "VPN"
                } else if (
                    netCap?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                ) {
                    "WiFi"
                } else if (
                    netCap?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ==
                    true
                ) {
                    "Cellular"
                } else {
                    "Unknown"
                }
            return a
        }


        fun netId(nwHandle: Long?): Long {
            if (nwHandle == null) {
                return -1L
            }
            // ref: cs.android.com/android/platform/superproject/main/+/main:packages/modules/Connectivity/framework/src/android/net/Network.java;drc=0209c366627e98d6311629a0592c6e22be7d13e0;l=491
            return nwHandle shr (32)
        }

        const val CONNECTIVITY_CHECK_INTERVAL_MS = 15_000L // 15 seconds

        // Network callback event types for connectivity checks
        const val EVENT_ON_CAPABILITIES_CHANGED = "onCapabilitiesChanged"
        const val EVENT_ON_AVAILABLE = "onAvailable"
        const val EVENT_ON_LOST = "onLost"
        const val EVENT_ON_LINK_PROPERTIES_CHANGED = "onLinkPropertiesChanged"
    }

    // data class that holds the below information for handlers to process the network changes
    // all values in OpPrefs should always remain immutable
    data class OpPrefs(
        val msgType: Int,
        val networkSet: Set<NetworkAndSsid>,
        val testReachability: Boolean,
        val stallOnNoNetwork: Boolean,
        val useAutoConnectivityChecks: Boolean
    )

    // capabilities used only to indicate whether the network is metered or not
    // TODO: send only the required capabilities to the handler instead of the whole
    data class ProbeResult(val ip: String, val ok: Boolean, val capabilities: NetworkCapabilities?)

    interface NetworkListener {
        suspend fun onNetworkRegistrationFailed()

        suspend fun maybeNetworkStall()

        suspend fun onNetworkChange(networks: UnderlyingNetworks)
    }

    private fun addToNwSet(network: Network, ssid: String? = null) {
        val old = networkSet.find { it.network == network }
        val networkSsid = NetworkAndSsid(network, ssid ?: old?.ssid )
        networkSet.removeIf { it.network == network }
        networkSet.add(networkSsid) // ensure the network is added to the set
    }

    private fun removeFromNwSet(network: Network) {
        networkSet.removeIf { it.network == network }
    }

    private fun getConnectionMonitorBehaviour(): VpnBuilderPolicy.ConnectionMonitorBehaviour {
        val policyId = persistentState.vpnBuilderPolicy
        return VpnBuilderPolicy.fromOrdinalOrDefault(policyId).connectionMonitorBehaviour
    }

    /**
     * Handles user preference changes, ie, when the user elects to see either multiple underlying
     * networks, or just one (the active network).
     */
    fun onUserPreferenceChanged() {
        Logger.d(LOG_TAG_CONNECTION, "onUserPreferenceChanged")
        scope.launch(CoroutineName("cmPref") + serializer) { sendNetworkChanges() }
    }

    fun onPolicyChanged() {
        Logger.d(LOG_TAG_CONNECTION, "onPolicyChanged")
        scope.launch(CoroutineName("cmPolicy") + serializer) {
            // re-register the network callbacks based on the new policy
            if (::cm.isInitialized) {
                // register the network callbacks, can throw exception
                try {
                    cm.unregisterNetworkCallback(internetValidatedCallback())
                } catch (e: Exception) {
                    Logger.w(LOG_TAG_CONNECTION, "err unregistering internetValidatedCallBack, ${e.message}")
                }
                try {
                    cm.unregisterNetworkCallback(transportCallback())
                } catch (e: Exception) {
                    Logger.w(LOG_TAG_CONNECTION, "err unregistering transportCallback, ${e.message}")
                }
            }
            val success = registerCallbackBasedOnPolicy()

            if (!success) {
                networkListener.onNetworkRegistrationFailed()
            }
        }
    }

    /**
     * Force updates the VPN's underlying network based on the preference. Will be initiated when
     * the VPN start is completed. Always called from the main thread
     */
    suspend fun onVpnStart(context: Context): Boolean  {
        val deferred = scope.async {
            val isNewVpn = !::cm.isInitialized

            if (!isNewVpn) {
                Logger.w(LOG_TAG_CONNECTION, "connection monitor is already running")
                return@async false
            }

            // initialize channel before registering
            channel = Channel(Channel.CONFLATED)
            cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

            val networkBehaviour = getConnectionMonitorBehaviour()
            Logger.i(LOG_TAG_CONNECTION, "new vpn is created force update the network, policy: $networkBehaviour")
            val success = registerCallbackBasedOnPolicy()

            if (!success) {
                networkListener.onNetworkRegistrationFailed()
                return@async false
            }

            // register for diagnostics manager if the android version is R or above
            if (isAtleastR()) {
                registerDiags(context)
            }

            scope.launch(CoroutineName("nwHdl") + serializer) {

                // Filter out empty strings from probe IPs to avoid unnecessary probe attempts
                val ips = IpsAndUrlToProbe(
                    persistentState.pingv4Ips.split(",").map { it.trim() }
                        .filter { it.isNotEmpty() },
                    persistentState.pingv6Ips.split(",").map { it.trim() }
                        .filter { it.isNotEmpty() },
                    persistentState.pingv4Url.split(",").map { it.trim() }
                        .filter { it.isNotEmpty() },
                    persistentState.pingv6Url.split(",").map { it.trim() }
                        .filter { it.isNotEmpty() }
                )

                val hdl = NetworkRequestHandler(cm, networkListener, ips, ::sendNetworkChanges)
                sendNetworkChanges()
                for (m in channel) {
                    // process the message in a coroutine context
                    val deferred = async { hdl.handleMessage(m) }
                    deferred.await()
                    // add a delay to avoid processing multiple network changes in quick succession
                    val duration = getNetworkBehaviourDuration(networkBehaviour)
                    delay(duration)
                }
            }

            return@async isNewVpn
        }

        return deferred.await()
    }

    private fun registerCallbackBasedOnPolicy(): Boolean {
        val behaviour = getConnectionMonitorBehaviour()
        Logger.i(LOG_TAG_CONNECTION, "register nw callback/s, policy: ${behaviour.name}")
        return when (behaviour) {
            VpnBuilderPolicy.ConnectionMonitorBehaviour.TRANSPORTS -> {
                // process the network changes with 2 seconds delay
                registerNetworkCallback(networkRequestWithTransports, transportCallback())
            }

            VpnBuilderPolicy.ConnectionMonitorBehaviour.VALIDATED_NETWORKS -> {
                // process the network changes with 1 second delay
                registerNetworkCallback(networkRequest, internetValidatedCallback())
            }

            VpnBuilderPolicy.ConnectionMonitorBehaviour.VALIDATED_NETWORKS_AND_TRANSPORTS -> {
                // delay the processing of network changes, ie, process the network changes with 5 seconds delay
                registerNetworkCallback(networkRequestWithTransports, transportCallback()) &&
                        registerNetworkCallback(networkRequest, internetValidatedCallback())
            }
        }
    }

    private fun registerNetworkCallback(req: NetworkRequest, callback: ConnectivityManager.NetworkCallback): Boolean {
        return try {
            // TODO: use a custom Looper(HandlerThread) to avoid blocking the main thread
            cm.registerNetworkCallback(req, callback)
            true
        } catch (e: Exception) {
            Logger.w(LOG_TAG_CONNECTION, "err registering network callback", e)
            false
        }
    }

    @RequiresApi(30)
    fun registerDiags(context: Context) {
        if (isAtleastR()) {
            try {
                val diagnosticMgr = DiagnosticsManager(context, scope, this)
                diagnosticMgr.register()
            } catch (e: Exception) {
                Logger.w(LOG_TAG_CONNECTION, "DiagnosticsManager; err while getting connectivity diagnostics manager")
            }
        }
    }

    @RequiresApi(30)
    fun unregisterDiags() {
        if (isAtleastR()) {
            // unregister the diag network callback
            try {
                diagsMgr?.unregister()
                diagsMgr = null
            } catch (e: Exception) {
                Logger.w(LOG_TAG_CONNECTION, "DiagnosticsManager; err while unregistering diag network callback")
            }
        }
    }


    // Always called from the main thread
    suspend fun onVpnStop() {
        scope.launch(CoroutineName("cmStop") + serializer) {
            try {
                // check if connectivity manager is initialized as it is lazy initialized
                if (::cm.isInitialized) {
                    try {
                        cm.unregisterNetworkCallback(internetValidatedCallback())
                    } catch (_: Exception) { }
                    try {
                        cm.unregisterNetworkCallback(transportCallback())
                    } catch (_: Exception) { }
                }
                if (isAtleastR()) {
                    unregisterDiags()
                }
                networkSet.clear()
                if (::channel.isInitialized) {
                    channel.close()
                }

            } catch (e: Exception) {
                Logger.w(LOG_TAG_CONNECTION, "err while unregistering; ${e.message}")
            }

        }
    }

    private suspend fun sendNetworkChanges() {
        val dualStack =
            InternetProtocol.getInternetProtocol(persistentState.internetProtocolType).isIPv46()
        val testReachability = dualStack && persistentState.connectivityChecks
        val failOpenOnNoNetwork = !persistentState.stallOnNoNetwork
        val useAutoConnectivityChecks = persistentState.performAutoNetworkConnectivityChecks
        val msg =
            constructNetworkMessage(
                if (persistentState.useMultipleNetworks) MSG_ADD_ALL_NETWORKS
                else MSG_ADD_ACTIVE_NETWORK,
                testReachability,
                failOpenOnNoNetwork,
                useAutoConnectivityChecks
            )

        // channel is initialized only when the vpn is started, so check if it is initialized
        // before sending the message (should not happen, but just in case)
        if (!::channel.isInitialized) {
            // channel is not initialized, return
            Logger.e(LOG_TAG_CONNECTION, "sendNetworkChanges, channel is not initialized")
            return
        }
        // TODO: process after a delay to avoid processing multiple network changes in short bursts
        @Suppress("OPT_IN_USAGE")
        if (DEBUG) Logger.v(LOG_TAG_CONNECTION, "sendNetworkChanges, channel closed? ${channel.isClosedForSend} msg: ${msg.msgType}, test: ${msg.testReachability}, stall: ${msg.stallOnNoNetwork}, useAutoChecks: ${msg.useAutoConnectivityChecks}, networks: ${msg.networkSet.size}")
        try {
            channel.send(msg)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_CONNECTION, "sendNetworkChanges, err while sending message to channel", e)
        }
    }

    /**
     * Constructs the message object for Network handler. Add the active network to the message
     * object in case of setUnderlying network has only active networks.
     */
    private fun constructNetworkMessage(
        what: Int,
        testReachability: Boolean,
        failOpenOnNoNetwork: Boolean,
        useAutoConnectivityChecks: Boolean
    ): OpPrefs {
        return OpPrefs(what, networkSet.toSet(), testReachability, failOpenOnNoNetwork, useAutoConnectivityChecks)
    }

    override suspend fun maybeNetworkStall() {
        Logger.i(LOG_TAG_CONNECTION, "onNetworkStallDetected")
        networkListener.maybeNetworkStall()
    }

    data class NetworkProperties(
        val network: Network,
        val capabilities: NetworkCapabilities,
        val linkProperties: LinkProperties?,
        val networkType: String,
        val ssid: String?
    )

    data class UnderlyingNetworks(
        val ipv4Net: List<NetworkProperties>,
        val ipv6Net: List<NetworkProperties>,
        val vpnRoutes: Pair<Boolean, Boolean>?,
        val useActive: Boolean,
        val minMtu: Int,
        var isActiveNetworkMetered: Boolean, // may be updated by client listener
        var isActiveNetworkCellular: Boolean,
        val activeSsid: String?, // may be updated by client listener
        var lastUpdated: Long, // may be updated by client listener
        val dnsServers: Map<InetAddress, Network>,
        var vpnLockdown: Boolean = false // updated by client listener
    )

    data class IpsAndUrlToProbe(
        val ip4probes: Collection<String>,
        val ip6probes: Collection<String>,
        val url4Probe: Collection<String>,
        val url6Probe: Collection<String>
    )

    /**
     * Handles network messages from the connectivity manager callback.
     *
     * This class is responsible for processing network change events, determining network
     * properties (like reachability, MTU, DNS servers), and informing a [NetworkListener]
     * about these changes. It manages a set of current underlying networks for the VPN,
     * prioritizing active and non-metered networks.
     *
     * It uses a [Handler] to process messages on a specific [Looper] to avoid blocking
     * the main thread.
     *
     * Key responsibilities include:
     * - Processing messages for adding active or all available networks.
     * - Determining VPN protocol support (IPv4/IPv6) based on routes.
     * - Informing the listener about network connections and disconnections, including
     *   details like MTU, metered status, cellular status, and DNS servers.
     * - Repopulating tracked IPv4 and IPv6 networks based on reachability and network capabilities.
     * - Retrying reachability checks if no usable networks are found initially.
     * - Rearranging networks to prioritize active and non-metered connections.
     * - Probing IP addresses for connectivity on specific networks.
     * - Performing TCP/UDP reachability checks as fallbacks.
     *
     * @property cm The system's [ConnectivityManager] instance.
     * @property listener The [NetworkListener] to be informed of network changes.
     * @property ipsAndUrl An [IpsAndUrlToProbe] object containing IP addresses for reachability checks.
     * @param looper The [Looper] on which this handler will process messages.
     */// Handles the network messages from the callback from the connectivity manager
    private class NetworkRequestHandler(
        val cm: ConnectivityManager,
        val listener: NetworkListener,
        ipsAndUrl: IpsAndUrlToProbe,
        val redrive: suspend () -> Unit
    ) {

        // number of times the reachability check is performed due to failures
        private var reachabilityCount = 0L
        private val maxReachabilityCount = 3L

        companion object {
            private const val DEFAULT_MTU = 1280 // same as BraveVpnService#VPN_INTERFACE_MTU
            private const val MIN_MTU = 1280
        }

        val ip4probes = ipsAndUrl.ip4probes
        // probing with domain names is not viable because some domains will resolve to both
        // ipv4 and ipv6 addresses. So, we use ipv6 addresses for probing ipv6 connectivity.
        val ip6probes = ipsAndUrl.ip6probes
        val url4Probe = ipsAndUrl.url4Probe
        val url6Probe = ipsAndUrl.url6Probe

        // ref - https://developer.android.com/reference/kotlin/java/util/LinkedHashSet
        // The network list is maintained in a linked-hash-set to preserve insertion and iteration
        // order. This is required because {@link android.net.VpnService#setUnderlyingNetworks}
        // defines network priority depending on the iteration order, that is, the network
        // in the 0th index is preferred over the one at 1st index, and so on.
        var currentNetworks: LinkedHashSet<NetworkProperties> = linkedSetOf()

        var trackedIpv4Networks: LinkedHashSet<NetworkProperties> = linkedSetOf()
        var trackedIpv6Networks: LinkedHashSet<NetworkProperties> = linkedSetOf()

        suspend fun handleMessage(opPrefs: OpPrefs) {
            // isForceUpdate - true if onUserPreferenceChanged is changes, the messages should be
            // processed forcefully regardless of the current and new networks.
            when (opPrefs.msgType) {
                MSG_ADD_ACTIVE_NETWORK -> {
                    processActiveNetwork(opPrefs)
                }

                MSG_ADD_ALL_NETWORKS -> {
                    processAllNetworks(opPrefs)
                }
            }
        }

        /**
         * tracks the changes in active network. Set the underlying network if the current active
         * network is different from already assigned one unless the force update is required.
         *
         * call only iff useAllAvaialble is false, else the `cm.activeNetwork` will be always
         * returns vpn network.
         */
        private suspend fun processActiveNetwork(opPrefs: OpPrefs) {
            val newActiveNetwork = cm.activeNetwork
            val newActiveNetworkCap = cm.getNetworkCapabilities(newActiveNetwork)
            // set active network's connection status
            val isActiveNetworkMetered = isActiveConnectionMetered()
            val isActiveNetworkCellular = isNetworkCellular(newActiveNetwork)
            val newNetworks = createNetworksSet(newActiveNetwork, opPrefs.networkSet)
            val isNewNetwork = hasDifference(currentNetworks, newNetworks)
            val vpnRoutes = determineVpnProtos(opPrefs.networkSet)
            val isDnsChanged = hasNwDnsChanged(currentNetworks, newNetworks)
            val isLinkAddressChanged = hasLinkAddrChanged(currentNetworks, newNetworks)
            var activeSsid = newNetworks.firstOrNull { it.network.networkHandle == newActiveNetwork?.networkHandle }?.ssid
            if (activeSsid == null) {
                // fetch from newNetworks set
                activeSsid = newNetworks.firstOrNull { it.ssid != null }?.ssid
            }

            Logger.i(LOG_TAG_CONNECTION, "process message active nws, currNws: $newNetworks")
            Logger.i(
                LOG_TAG_CONNECTION,
                "Connected network: ${newActiveNetwork?.networkHandle} ${
                    networkType(newActiveNetworkCap)
                }, netid: ${netId(newActiveNetwork?.networkHandle)}, new? $isNewNetwork, test? ${opPrefs.testReachability}," +
                 "cellular? $isActiveNetworkCellular, metered? $isActiveNetworkMetered, dns-changed? $isDnsChanged, link-address-changed? $isLinkAddressChanged, activeSsid: $activeSsid"
            )

            currentNetworks = newNetworks
            repopulateTrackedNetworks(opPrefs, currentNetworks)
            // client code must call setUnderlyingNetworks() to invoke linkCapabilities for other uids
            informListener(true, isActiveNetworkMetered, isActiveNetworkCellular, activeSsid, vpnRoutes)
        }

        private suspend fun hasNwDnsChanged(currNws: Set<NetworkProperties>, newNws: Set<NetworkProperties>): Boolean {
            // check equality on addr bytes and not on string representation to avoid issues with IPv4-mapped IPv6 addresses
            val currDnsServers = currNws.map { it.linkProperties }.mapNotNull { it?.dnsServers }.flatMap { it }.map { it.address }.toSet()
            val newDnsServers = newNws.map { it.linkProperties }.mapNotNull { it?.dnsServers }.flatMap { it }.map { it.address }.toSet()
            return newDnsServers == currDnsServers
        }

        private suspend fun hasLinkAddrChanged(currNws: Set<NetworkProperties>, newNws: Set<NetworkProperties>): Boolean {
            val currLinkAddresses = currNws.map { it.linkProperties }.mapNotNull { it?.linkAddresses }.flatMap { it }.map { it.address.address }.toSet()
            val newLinkAddresses = newNws.map { it.linkProperties }.mapNotNull { it?.linkAddresses }.flatMap { it }.map { it.address.address }.toSet()
            return newLinkAddresses == currLinkAddresses
        }

        /** Adds all the available network to the underlying network. */
        private suspend fun processAllNetworks(opPrefs: OpPrefs) {
            val newActiveNetwork = cm.activeNetwork
            // set active network's connection status
            val isActiveNetworkMetered = isActiveConnectionMetered()
            val newNetworks = createNetworksSet(newActiveNetwork, opPrefs.networkSet)
            val isNewNetwork = hasDifference(currentNetworks, newNetworks)
            val vpnRoutes = determineVpnProtos(opPrefs.networkSet)
            val isActiveNetworkCellular = isNetworkCellular(newActiveNetwork)
            val isDnsChanged = hasNwDnsChanged(currentNetworks, newNetworks)
            val isLinkAddressChanged = hasLinkAddrChanged(currentNetworks, newNetworks)
            var activeSsid = newNetworks.firstOrNull { it.network.networkHandle == newActiveNetwork?.networkHandle }?.ssid
            if (activeSsid == null) {
                // fetch from newNetworks set
                activeSsid = newNetworks.firstOrNull { it.ssid != null }?.ssid
            }

            Logger.i(LOG_TAG_CONNECTION, "process message all nws, currNws: $newNetworks")
            Logger.i(LOG_TAG_CONNECTION, "process message all nws, newNws: $newNetworks \nnew? $isNewNetwork, test? ${opPrefs.testReachability}, cellular? $isActiveNetworkCellular, metered? $isActiveNetworkMetered, dns-changed? $isDnsChanged, link-addr-changed? $isLinkAddressChanged, activeSsid: $activeSsid")

            currentNetworks = newNetworks
            repopulateTrackedNetworks(opPrefs, currentNetworks)
            informListener(false, isActiveNetworkMetered, isActiveNetworkCellular, activeSsid, vpnRoutes)
        }

        /**
         * Determines the IP protocols (IPv4 and/or IPv6) supported by the VPN network.
         *
         * This function checks the routes of the VPN network to determine if it has
         * default routes for IPv4 and IPv6. This depends on the routes configured
         * in the builder, when exclude private networks is set to true then routes
         * will not have default route
         *
         * @param nws A set of [Network] objects, which may include the VPN network.
         * @return A [Pair] where the first element indicates IPv4 support (true if supported)
         *         and the second element indicates IPv6 support (true if supported).
         *         Returns null if no VPN network is found in the provided set.
         */
        private suspend fun determineVpnProtos(nws: Set<NetworkAndSsid?>): Pair<Boolean, Boolean>? {
            val vpnNw = nws.firstOrNull { isVPN(it?.network) == true }
            /*if (vpnNw == null) {
                // fallback to the active network if the vpn network is not found
                val allNws = cm.allNetworks
                vpnNw = allNws.firstOrNull { isVPN(it) == true }
            }*/
            if (vpnNw == null) {
                // vpn routes is just the suggestion to mitigate the discrepancy between
                // actual vpn routes and the ones handled by BraveVpnService, in that case
                // if the vpn routes are not available, set it to null and return let the
                // obj(builderRoutes) in BraveVpnService to handle the rest
                Logger.i(LOG_TAG_CONNECTION, "determineVpnProtos; no vpn networks found")
                return null
            }

            // fixme: using below code has issues when private networks are excluded, return null for now
            // come up with a better way to determine the protocols
            val lp = cm.getLinkProperties(vpnNw.network)
            var has4 = false
            var has6 = false

            /*lp?.routes?.forEach { route ->
                val dst = route.destination
                val addr = dst.address
                val prefix = dst.prefixLength

                when (addr) {
                    is Inet4Address -> {
                        val octet = addr.address.map { it.toInt() and 0xFF }

                        val isPrivate =
                            (octet[0] == 10) ||
                                    (octet[0] == 172 && octet[1] in 16..31) ||
                                    (octet[0] == 192 && octet[1] == 168)

                        val isLoopback = (octet[0] == 127)
                        val isLinkLocal = (octet[0] == 169 && octet[1] == 254)
                        val isMulticast = (octet[0] in 224..239)
                        val isSelf = (prefix == 32)

                        if (!isPrivate && !isLoopback && !isLinkLocal && !isMulticast && !isSelf) {
                            has4 = true
                            Logger.vv(
                                LOG_TAG_CONNECTION,
                                "determineVpnProtos2; adding IPv4 route: $route"
                            )
                        }
                    }

                    is Inet6Address -> {
                        val bytes = addr.address
                        val firstByte = bytes[0].toInt() and 0xFF
                        val secondByte = bytes[1].toInt() and 0xFF

                        val isULA = (firstByte and 0xFE) == 0xFC          // fd00::/8
                        val isLinkLocal =
                            (firstByte == 0xFE) && ((secondByte and 0xC0) == 0x80)  // fe80::/10
                        val isSelf = (prefix == 128)

                        if (!isULA && !isLinkLocal && !isSelf) {
                            has6 = true
                            Logger.vv(
                                LOG_TAG_CONNECTION,
                                "determineVpnProtos2; adding IPv6 route: $route"
                            )
                        }
                    }
                }

                Logger.vv(
                    LOG_TAG_CONNECTION,
                    "determineVpnProtos2; for $route, has4? $has4, has6? $has6"
                )

                if (has4 && has6) return@forEach
            } */

            if (!has4 && !has6) {
                lp?.routes?.forEach rloop@{
                    // ref: androidxref.com/9.0.0_r3/xref/frameworks/base/core/java/android/net/RouteInfo.java#328
                    val hasDefaultRoute4 =
                        (it.isDefaultRoute && it.destination.address is Inet4Address)
                    val hasDefaultRoute6 =
                        (it.isDefaultRoute && it.destination.address is Inet6Address)

                    has4 = hasDefaultRoute4
                    has4 = hasDefaultRoute6

                    /*Logger.v(
                        LOG_TAG_CONNECTION,
                        "determineVpnProtos; for $it, has4? $has4, has6? $has6, ${it.destination}, ${it.gateway}, ${it.isDefaultRoute}, ${it.`interface`}"
                    )*/
                    if (has4 && has6) return@rloop
                }
            }

            Logger.i(LOG_TAG_CONNECTION, "determineVpnProtos; has4? $has4, has6? $has6, $lp")
            return Pair(has4, has6)
        }

        /**
         * Informs the listener about network changes.
         *
         * This function constructs an [UnderlyingNetworks] object based on the current state of
         * tracked IPv4 and IPv6 networks and then calls either `onNetworkConnected` or
         * `onNetworkDisconnected` on the `listener` depending on whether any networks are available.
         *
         * It also gathers DNS server information from the tracked networks and includes it in the
         * [UnderlyingNetworks] object.
         *
         * @param useActiveNetwork A boolean indicating whether the active network should be
         *                         prioritized or if all available networks should be considered.
         * @param isActiveNetworkMetered A boolean indicating if the currently active network is metered.
         * @param isActiveNetworkCellular A boolean indicating if the currently active network is cellular.
         * @param vpnRoutes A [Pair] indicating whether VPN routes for IPv4 (first element) and
         *                  IPv6 (second element) are available. Can be null if VPN routes are not
         *                  determined.
         */
        private suspend fun informListener(
            useActiveNetwork: Boolean = false,
            isActiveNetworkMetered: Boolean,
            isActiveNetworkCellular: Boolean,
            activeSsid: String?,
            vpnRoutes: Pair<Boolean, Boolean>?
        ) {
            // TODO: use currentNetworks instead of trackedIpv4Networks and trackedIpv6Networks
            // to determine whether to call onNetworkConnected or onNetworkDisconnected
            val sz = trackedIpv4Networks.size + trackedIpv6Networks.size
            Logger.i(
                LOG_TAG_CONNECTION,
                "inform network change: ${sz}, useActive? $useActiveNetwork, metered? $isActiveNetworkMetered"
            )
            // maintain a map of dns servers for ipv4 and ipv6 networks
            val dns4 = getDnsServers(trackedIpv4Networks)
            val dns6 = getDnsServers(trackedIpv6Networks)
            val dnsServers: LinkedHashMap<InetAddress, Network> = LinkedHashMap()
            dnsServers.putAll(dns4)
            dnsServers.putAll(dns6)

            if (sz > 0) {
                trackedIpv4Networks.forEach {
                    Logger.d(LOG_TAG_CONNECTION, "inform4: ${it.network}, ${it.networkType}, $sz")
                }
                trackedIpv6Networks.forEach {
                    Logger.d(LOG_TAG_CONNECTION, "inform6: ${it.network}, ${it.networkType}, $sz")
                }
                val underlyingNetworks =
                    UnderlyingNetworks(
                        trackedIpv4Networks.map { it }, // map to produce shallow copy
                        trackedIpv6Networks.map { it },
                        vpnRoutes,
                        useActiveNetwork,
                        determineMtu(useActiveNetwork),
                        isActiveNetworkMetered,
                        isActiveNetworkCellular,
                        activeSsid,
                        SystemClock.elapsedRealtimeNanos(),
                        Collections.unmodifiableMap(dnsServers)
                    )
                listener.onNetworkChange(underlyingNetworks)
            } else {
                val underlyingNetworks =
                    UnderlyingNetworks(
                        emptyList(),
                        emptyList(),
                        vpnRoutes,
                        useActiveNetwork,
                        DEFAULT_MTU,
                        isActiveNetworkMetered = false,
                        isActiveNetworkCellular = false,
                        null,
                        SystemClock.elapsedRealtimeNanos(),
                        LinkedHashMap()
                    )
                listener.onNetworkChange(underlyingNetworks)
            }
        }

        /**
         * Retrieves a map of DNS servers and their associated networks from a set of
         * [NetworkProperties].
         *
         * This function iterates through the provided `nws` (set of [NetworkProperties]).
         * For each `NetworkProperties` object, it accesses its `linkProperties` and then
         * its `dnsServers`. Each DNS server found is added as a key to the returned
         * `LinkedHashMap`, with the corresponding `Network` object from the
         * `NetworkProperties` as its value.
         *
         * Using a `LinkedHashMap` ensures that the order of DNS servers is preserved
         * based on their insertion order, which might be relevant for prioritization.
         * Duplicate DNS server addresses will be overwritten by later occurrences if they
         * are associated with a different network, or simply ignored if the network is the same.
         *
         * @param nws A [LinkedHashSet] of [NetworkProperties] objects, each potentially
         *            containing DNS server information.
         * @return A [LinkedHashMap] where keys are [InetAddress] objects representing
         *         DNS servers, and values are the [Network] objects they are associated with.
         *         Returns an empty map if no DNS servers are found or if `nws` is empty.
         */
        private suspend fun getDnsServers(
            nws: LinkedHashSet<NetworkProperties>
        ): LinkedHashMap<InetAddress, Network> {
            // add dns servers into a set to avoid duplicates and add corresponding network to it
            val dnsServers = LinkedHashMap<InetAddress, Network>()
            nws.forEach {
                it.linkProperties?.dnsServers?.forEach v@{ dns ->
                    val address = dns ?: return@v
                    dnsServers[address] = it.network
                }
            }
            return dnsServers
        }

        /**
         * Determines the MTU (Maximum Transmission Unit) for the VPN interface.
         *
         * The MTU is determined based on the following logic:
         * 1. If the Android version is below Q (API level 29), it returns `MIN_MTU` for safety
         * 2. If `useActiveNetwork` is true:
         *    a. It attempts to get the MTU from the active network.
         *    b. If the active network is null or its MTU is invalid, it falls back to the
         *       MTU of the first tracked IPv4 and IPv6 networks.
         * 3. If `useActiveNetwork` is false:
         *    a. It iterates through all tracked IPv4 and IPv6 networks and determines the
         *       minimum non-zero MTU for each protocol.
         * 4. If both IPv4 and IPv6 MTUs are invalid (less than or equal to 0), it returns `MIN_MTU`.
         * 5. Otherwise, it returns the maximum of `MIN_MTU` and the minimum of the valid
         *    IPv4 and IPv6 MTUs. This ensures the MTU is never below `MIN_MTU`.
         *
         * @param useActiveNetwork A boolean indicating whether to consider only the active network
         *                         for MTU determination.
         * @return The calculated MTU value.
         */
        private suspend fun determineMtu(useActiveNetwork: Boolean): Int {
            var minMtu4: Int = -1
            var minMtu6: Int = -1
            if (!isAtleastQ()) {
                // If not at least Q, return MIN_MTU for safety
                return MIN_MTU
            }
            if (useActiveNetwork) {
                cm.activeNetwork?.let {
                    val lp = cm.getLinkProperties(it)
                    minMtu4 = minNonZeroMtu(lp?.mtu, minMtu4)
                    minMtu6 = minNonZeroMtu(lp?.mtu, minMtu6)
                    Logger.v(LOG_TAG_CONNECTION, "active network mtu: ${lp?.mtu}, minMtu4: $minMtu4, minMtu6: $minMtu6")
                }
                    ?: run {
                        // consider first network in underlying network as active network,
                        //  in case active network is null
                        val lp4 = trackedIpv4Networks.firstOrNull()?.linkProperties
                        val lp6 = trackedIpv6Networks.firstOrNull()?.linkProperties
                        Logger.v(LOG_TAG_CONNECTION, "tracked network mtu: ${lp4?.mtu}, ${lp6?.mtu}")
                        minMtu4 = minNonZeroMtu(lp4?.mtu, minMtu4)
                        minMtu6 = minNonZeroMtu(lp6?.mtu, minMtu6)
                    }
            } else {
                // parse through all the networks and get the minimum mtu
                trackedIpv4Networks.forEach {
                    val c = it.linkProperties
                    minMtu4 = minNonZeroMtu(c?.mtu, minMtu4)
                    Logger.v(LOG_TAG_CONNECTION, "tracked network4 mtu: ${c?.mtu}, using $minMtu4")
                }
                trackedIpv6Networks.forEach {
                    val c = it.linkProperties
                    minMtu6 = minNonZeroMtu(c?.mtu, minMtu6)
                    Logger.v(LOG_TAG_CONNECTION, "tracked network6 mtu: ${c?.mtu}, using $minMtu6")
                }
            }
            // If both are -1, return MIN_MTU explicitly
            if (minMtu4 <= 0 && minMtu6 <= 0) {
                Logger.i(LOG_TAG_CONNECTION, "Both MTUs are invalid, using MIN_MTU: $MIN_MTU")
                return MIN_MTU
            }
            // set mtu to MIN_MTU (1280) if mtu4/mtu6 are less than MIN_MTU
            val mtu = max(min(minMtu4.takeIf { it > 0 } ?: Int.MAX_VALUE, minMtu6.takeIf { it > 0 } ?: Int.MAX_VALUE), MIN_MTU)
            Logger.i(LOG_TAG_CONNECTION, "mtu4: $minMtu4, mtu6: $minMtu6; final mtu: $mtu")
            return mtu
        }

        /**
         * Returns the minimum non-zero MTU (Maximum Transmission Unit) between two given values.
         *
         * This function is used to determine the smallest valid MTU when comparing two potential
         * MTU values. An MTU can be null if the LinkProperties object is null, or it can be 0 if
         * the value is not set (see LinkProperties#getMtu()). This function handles these cases
         * by preferring a non-null, positive MTU.
         *
         * @param m1 The first MTU value. Can be null or 0.
         * @param m2 The second MTU value.
         * @return The minimum of the two MTUs, considering only positive values. If `m1` is valid
         *         (not null and > 0) and `m2` is not positive, `m1` is returned. If `m1` is not
         *         valid, `m2` is returned. Otherwise, the minimum of `m1` and `m2` is returned.
         */
        private suspend fun minNonZeroMtu(m1: Int?, m2: Int): Int {
            // treat 0 mtu as 1500 as default mtu is 1500 (android-def) and android will send 0 in
            // case of default
            // developer.android.com/reference/android/net/LinkProperties#getMtu()
            val mtu1 = if (m1 == 0) 1500 else m1
            val mtu2 = if (m2 == 0) 1500 else m2
            return if (mtu1 != null && mtu1 > 0) {
                // mtu can be null when lp is null
                // mtu can be 0 when the value is not set, see:LinkProperties#getMtu()
                if (mtu2 <= 0) mtu1 else min(mtu1, mtu2)
            } else {
                if (mtu2 <= 0) {
                    // both m1 and m2 are invalid, return MIN_MTU
                    MIN_MTU
                } else {
                    // m1 is invalid, return m2
                    mtu2
                }
            }
        }

        /**
         * Checks if the active network connection is metered.
         *
         * @return True if the active network connection is metered, false otherwise.
         */
        private suspend fun isActiveConnectionMetered(): Boolean {
            // TODO: revisit this logic, see if this also needs similar treatment as
            // isActiveConnectionCellular
            return cm.isActiveNetworkMetered
        }

        /**
         * Checks if the active connection is cellular.
         *
         * @param network The network to check.
         * @return True if the active connection is cellular, false otherwise.
         */
        private suspend fun isNetworkCellular(network: Network?): Boolean {
            if (network == null) {
                Logger.d(LOG_TAG_CONNECTION, "isNetworkCellular: network is null")
                return false
            }

            val cap = cm.getNetworkCapabilities(network)
            val hasCellular = cap?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ?: false
            val hasWifi = cap?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false
            Logger.v(LOG_TAG_CONNECTION, "isNetworkCellular: netid: ${netId(network.networkHandle)}, hasCellular? $hasCellular, hasWifi? $hasWifi, metered? ${cm.isActiveNetworkMetered}")
            val isCellular = cap?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
            // when "use all available networks" is enabled, both cellular and wifi can be active.
            // in this case, `cm` always returns true for TRANSPORT_CELLULAR when both are active.
            // mark cellular networks as true only if wifi is not active.
            return isCellular
        }

        /**
         * Repopulates the tracked IPv4 and IPv6 networks based on the provided set of networks
         * and operation preferences.
         *
         * This function iterates through the given `networks` and determines their suitability
         * for IPv4 and IPv6 connectivity. The process involves:
         *
         * 1. **Filtering by Link Properties:** Networks without valid link properties are skipped.
         * 2. **Identifying Network State:** It determines if a network is active, a captive portal,
         *    validated, and has internet access.
         * 3. **Reachability Testing (Optional):** If `opPrefs.testReachability` is true, it probes
         *    for IPv4 and IPv6 connectivity using [probeConnectivity]. Networks that pass these
         *    tests are added to `trackedIpv4Networks` and `trackedIpv6Networks` respectively.
         *    If both IPv4 and IPv6 are reachable on a network, it moves to the next network.
         * 4. **Default Route Check (Fallback):** If reachability testing is disabled or fails to
         *    establish both IPv4 and IPv6 connectivity, it checks if the network has default
         *    routes for IPv4 and/or IPv6. This check is performed if the network has internet
         *    capability and either:
         *    - It's a captive portal and active (or active network is null).
         *    - `opPrefs.failOpenOnNoNetwork` is true.
         *    - The network is validated.
         *    Networks with default IPv4 routes are added to `trackedIpv4Networks`, and those
         *    with default IPv6 routes are added to `trackedIpv6Networks`.
         * 5. **Retry Mechanism:** If no usable IPv4 or IPv6 networks are found after the initial
         *    pass, [redoReachabilityIfNeeded] is called to schedule a retry.
         * 6. **Rearrangement:** Finally, `trackedIpv4Networks` and `trackedIpv6Networks` are
         *    rearranged using [rearrangeNetworks] to prioritize active and non-metered networks.
         *
         * @param opPrefs The operation preferences, which include settings like whether to test
         */
        private suspend fun repopulateTrackedNetworks(
            opPrefs: OpPrefs,
            nwProps: LinkedHashSet<NetworkProperties>
        ) {
            val testReachability: Boolean = opPrefs.testReachability

            val activeNetwork = cm.activeNetwork // null in vpn lockdown mode

            trackedIpv4Networks.clear()
            trackedIpv6Networks.clear()

            nwProps.forEach outer@{ prop ->
                val network: Network = prop.network

                val lp = cm.getLinkProperties(network)
                if (lp == null) {
                    Logger.i(LOG_TAG_CONNECTION, "skipping: ${network.networkHandle}, netid: ${netId(network.networkHandle)}; no link properties")
                    return@outer
                }

                val isActiveNull = activeNetwork == null
                val isActive = !isActiveNull && isNetworkSame(network, activeNetwork)
                val isCaptive = isCaptivePortal(network)
                val maybeCaptiveActive = isCaptive && (isActive || isActiveNull)
                val isValidated = isValidated(network)
                val hasInternet = hasInternet(network)
                Logger.d(LOG_TAG_CONNECTION, "processing: ${network.networkHandle}, netid: ${netId(network.networkHandle)}, active? $isActive, activeNull? $isActiveNull, internet? $hasInternet, captive? $isCaptive, validated? $isValidated")
                // TODO: case: CAPTIVE_PORTAL, should we not test reachability?
                if (testReachability) {
                    // for active network, ICMP echo is additionally used with TCP and UDP checks
                    // but ICMP echo will always return reachable when app is in rinr mode
                    // so till we have checks for rinr mode, we should not use ICMP reachability
                    val has4 = probeConnectivity(opPrefs.useAutoConnectivityChecks, network, SCHEME_HTTPS, PROTOCOL_V4)
                    val has6 = probeConnectivity(opPrefs.useAutoConnectivityChecks, network, SCHEME_HTTPS, PROTOCOL_V6)
                    if (has4) trackedIpv4Networks.add(prop)
                    if (has6) trackedIpv6Networks.add(prop)
                    Logger.i(LOG_TAG_CONNECTION, "url probe, nw(${network.networkHandle}, netid: ${netId(network.networkHandle)}): has4? $has4, has6? $has6, $prop")
                    if (has4 && has6) {
                        return@outer
                    }
                    // else: fall-through to check reachability with ips or network capabilities
                }

                val nwHas4 = trackedIpv4Networks.any { it.network == network }
                val nwHas6 = trackedIpv6Networks.any { it.network == network }
                // if either of the trackedIpv4Networks or trackedIpv6Networks has the network,
                // no need to check reachability again with ip4probes or ip6probes
                if (testReachability && (!nwHas4 && !nwHas6)) {
                    // both the ipv4 and ipv6 networks are not reachable, so try to check
                    // for ip reachability
                    val has4 = probeConnectivity(opPrefs.useAutoConnectivityChecks, network, SCHEME_IP, PROTOCOL_V4)
                    val has6 = probeConnectivity(opPrefs.useAutoConnectivityChecks, network, SCHEME_IP, PROTOCOL_V6)
                    if (has4) trackedIpv4Networks.add(prop)
                    if (has6) trackedIpv6Networks.add(prop)
                    Logger.i(LOG_TAG_CONNECTION, "ip probe, nw(${network.networkHandle}, netid: ${netId(network.networkHandle)}): has4? $has4, has6? $has6, $prop")
                    if (has4 && has6) {
                        return@outer
                    }
                }

                val nwHas4AfterProbe = trackedIpv4Networks.any { it.network == network }
                val nwHas6AfterProbe = trackedIpv6Networks.any { it.network == network }
                // if either of the trackedIpv4Networks or trackedIpv6Networks has the network,
                // no need to check for below conditions
                if (nwHas4AfterProbe || nwHas6AfterProbe) {
                    Logger.i(LOG_TAG_CONNECTION, "nw(${network.networkHandle}, netid: ${netId(network.networkHandle)}) already has ipv4? $nwHas4AfterProbe, ipv6? $nwHas6AfterProbe, skipping further checks")
                    return@outer
                }

                // treat captive portal as having internet, if client code is not going to fail-open
                val isCaptivePortal = maybeCaptiveActive && hasInternet
                val relyOnValidation = !testReachability && isValidated && hasInternet

                // see #createNetworksSet for why we are using hasInternet
                // if no network has been validated, then fail open
                // expect captive portal to have internet bound routes
                if (isCaptivePortal || relyOnValidation) {
                    var hasDefaultRoute4 = false
                    var hasDefaultRoute6 = false

                    // TODO: handle transport types like bluetooth, ethernet which may not have
                    // default routes, but can still have internet access
                    lp.routes.forEach rloop@{
                        // ref:
                        // androidxref.com/9.0.0_r3/xref/frameworks/base/core/java/android/net/RouteInfo.java#328
                        hasDefaultRoute4 =
                            hasDefaultRoute4 ||
                                    (it.isDefaultRoute && it.destination.address is Inet4Address)
                        hasDefaultRoute6 =
                            hasDefaultRoute6 ||
                                    (it.isDefaultRoute && it.destination.address is Inet6Address)

                        if (hasDefaultRoute4 && hasDefaultRoute6) return@rloop
                    }

                    if (hasDefaultRoute6) {
                        trackedIpv6Networks.add(prop)
                    }
                    if (hasDefaultRoute4) {
                        trackedIpv4Networks.add(prop)
                    }

                    Logger.i(
                        LOG_TAG_CONNECTION,
                        "nwValidation, nw(${network.networkHandle}, netid: ${netId(network.networkHandle)}) default4? $hasDefaultRoute4, default6? $hasDefaultRoute6 for $prop"
                    )
                } else {
                    Logger.i(LOG_TAG_CONNECTION, "skip: ${network.networkHandle}, netid: ${netId(network.networkHandle)}, cap: ${prop.capabilities}; no internet capability")
                }
            }

            // handle fail-open when no networks are found in both IPv4 and IPv6 sets
            val failOpen = !opPrefs.stallOnNoNetwork
            if (trackedIpv4Networks.isEmpty() && trackedIpv6Networks.isEmpty() && failOpen) {
                Logger.i(LOG_TAG_CONNECTION, "no networks found, but fail-open is enabled")
                nwProps.forEach outer@{ prop ->
                    val network: Network = prop.network

                    val lp = cm.getLinkProperties(network)
                    if (lp == null) {
                        Logger.i(
                            LOG_TAG_CONNECTION,
                            "skip fail-open: ${network.networkHandle}, netid: ${netId(network.networkHandle)}; no link properties"
                        )
                        return@outer
                    }

                    val hasInternet = hasInternet(network)
                    if (!hasInternet) {
                        Logger.i(
                            LOG_TAG_CONNECTION,
                            "skip fail-open: ${network.networkHandle}, netid: ${netId(network.networkHandle)}; no internet capability"
                        )
                        return@outer
                    }
                    var hasDefaultRoute4 = false
                    var hasDefaultRoute6 = false
                    lp.routes.forEach rloop@{
                        // ref:
                        // androidxref.com/9.0.0_r3/xref/frameworks/base/core/java/android/net/RouteInfo.java#328
                        hasDefaultRoute4 =
                            hasDefaultRoute4 ||
                                    (it.isDefaultRoute && it.destination.address is Inet4Address)
                        hasDefaultRoute6 =
                            hasDefaultRoute6 ||
                                    (it.isDefaultRoute && it.destination.address is Inet6Address)

                        if (hasDefaultRoute4 && hasDefaultRoute6) return@rloop
                    }

                    if (hasDefaultRoute6) {
                        trackedIpv6Networks.add(prop)
                    }
                    if (hasDefaultRoute4) {
                        trackedIpv4Networks.add(prop)
                    }

                    Logger.i(
                        LOG_TAG_CONNECTION,
                        "fail-open nw(${network.networkHandle}, netid: ${netId(network.networkHandle)}) default4? $hasDefaultRoute4, default6? $hasDefaultRoute6 for $prop"
                    )
                }
            }

            redoReachabilityIfNeeded(trackedIpv4Networks, trackedIpv6Networks)

            trackedIpv4Networks = rearrangeNetworks(trackedIpv4Networks)
            trackedIpv6Networks = rearrangeNetworks(trackedIpv6Networks)

            Logger.d(
                LOG_TAG_CONNECTION,
                "repopulate v6: $trackedIpv6Networks,\nv4: $trackedIpv4Networks"
            )
        }

        private suspend fun probeConnectivity(useAuto: Boolean, network: Network, scheme: String, protocol: String): Boolean {
            // TODO: add http url probes for ipv4 and ipv6
            val ipOrUrls = if (protocol == PROTOCOL_V4 && scheme == SCHEME_HTTPS) {
                url4Probe
            } else if (protocol == PROTOCOL_V6 && scheme == SCHEME_HTTPS) {
                url6Probe
            } else if (protocol == PROTOCOL_V4 && scheme == SCHEME_IP) {
                ip4probes
            } else if (protocol == PROTOCOL_V6 && scheme == SCHEME_IP) {
                ip6probes
            } else {
                Logger.w(LOG_TAG_CONNECTION, "unknown protocol: $protocol, scheme: $scheme")
                url4Probe
            }
            return if (useAuto) {
                ConnectivityCheckHelper.probeConnectivityInAutoMode(
                    network,
                    scheme,
                    protocol,
                    ipOrUrls,
                    USE_KOTLIN_REACHABILITY_CHECKS
                )
            } else {
                ConnectivityCheckHelper.probeConnectivityInManualMode(ipOrUrls, network, USE_KOTLIN_REACHABILITY_CHECKS)
            }
        }

        /**
         * Retries reachability checks if no IPv4 or IPv6 networks are found.
         * This function is called when the initial reachability check fails to find any usable
         * IPv4 or IPv6 networks. It increments a counter (`reachabilityCount`) and, if the
         * counter is within the `maxReachabilityCount`, schedules another reachability check
         * with a delay. The delay increases with each retry. If a usable network is found in
         * a subsequent check, the `reachabilityCount` is reset.
         *
         * @param ipv4 A set of IPv4 network properties. If empty, it indicates no usable IPv4
         *             networks were found.
         * @param ipv6 A set of IPv6 network properties. If empty, it indicates no usable IPv6
         *             networks were found.
         * @param opPrefs The operation preferences containing the message type and other settings
         *                for the reachability check. It's assumed to be immutable.
         */
        private suspend fun redoReachabilityIfNeeded(
            ipv4: LinkedHashSet<NetworkProperties>,
            ipv6: LinkedHashSet<NetworkProperties>
        ) {
            if (ipv4.isEmpty() && ipv6.isEmpty()) {
                reachabilityCount++
                Logger.i(LOG_TAG_CONNECTION, "redo reachability, try: $reachabilityCount")
                if (reachabilityCount > maxReachabilityCount) return

                val delay = TimeUnit.SECONDS.toMillis(10 * reachabilityCount)
                delay(delay)
                redrive()
            } else {
                Logger.d(LOG_TAG_CONNECTION, "reset reachability count, prev: $reachabilityCount")
                // reset the reachability count
                reachabilityCount = 0
            }
        }

        /**
         * Check if there is any difference between the current and new networks.
         * The difference is determined by comparing the size of the sets and the symmetric
         * difference of the network handles.
         * @param currentNetworks The set of current networks.
         * @param newNetworks The set of new networks.
         * @return True if there is a difference, false otherwise.
         */
        private suspend fun hasDifference(
            currentNetworks: LinkedHashSet<NetworkProperties>,
            newNetworks: LinkedHashSet<NetworkProperties>
        ): Boolean {
            if (currentNetworks.size != newNetworks.size) {
                return true
            }
            val cn = currentNetworks.map { it.network.networkHandle }.toHashSet()
            val nn = newNetworks.map { it.network.networkHandle }.toHashSet()
            return cn != nn
        }

        /**
         * Rearranges the given set of networks.
         * The active network is added first, followed by non-metered networks, and then metered networks.
         *
         * @param networks The set of networks to rearrange.
         * @return A new set of networks with the rearranged order.
         */
        private suspend fun rearrangeNetworks(
            networks: LinkedHashSet<NetworkProperties>
        ): LinkedHashSet<NetworkProperties> {
            val newNetworks: LinkedHashSet<NetworkProperties> = linkedSetOf()
            // add active network first, then add non metered networks, then metered networks
            val activeNetwork = cm.activeNetwork
            val n = networks.firstOrNull { isNetworkSame(it.network, activeNetwork) }
            if (n != null) {
                newNetworks.add(n)
            }
            networks
                .filter { isConnectionNotMetered(it.capabilities) }
                .forEach { newNetworks.add(it) }
            // add remaining networks, ie, metered networks
            networks
                .filter { !isConnectionNotMetered(it.capabilities) }
                .forEach { newNetworks.add(it) }
            return newNetworks
        }

        private fun isConnectionNotMetered(capabilities: NetworkCapabilities?): Boolean {
            return capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                ?: false
        }

        /**
         * Create network set(available networks) based on the user preference. The first element of
         * the list must be active network. requireAllNetwork - if true adds all networks to the set
         * else adds active network alone to the set.
         */
        private suspend fun createNetworksSet(
            activeNetwork: Network?,
            networkSet: Set<NetworkAndSsid>
        ): LinkedHashSet<NetworkProperties> {
            val newNetworks: LinkedHashSet<NetworkProperties> = linkedSetOf()
            activeNetwork?.let {
                val activeCap = cm.getNetworkCapabilities(activeNetwork)
                val activeLp = cm.getLinkProperties(activeNetwork)
                val nwType =
                    networkType(activeCap) + ", NotMetered?" + isConnectionNotMetered(activeCap)
                val activeProp =
                    if (activeCap != null) {
                        val ssid = networkSet.firstOrNull { ns -> ns.network.networkHandle == it.networkHandle }?.ssid
                        NetworkProperties(it, activeCap, activeLp, nwType, ssid)
                    } else {
                        null
                    }
                // test for internet capability iff opPrefs.testReachability is false
                if (/*hasInternet(it) == true &&*/ activeProp != null && isVPN(it) == false) {
                    newNetworks.add(activeProp)
                }
            }
            val networks =
                if (networkSet.isEmpty()) {
                    Logger.d(LOG_TAG_CONNECTION, "networkSet is empty")
                    @Suppress("DEPRECATION")
                    cm.allNetworks
                } else {
                    Logger.d(LOG_TAG_CONNECTION, "networkSet size: ${networkSet.size}")
                    networkSet.map { it.network }.toTypedArray()
                }

            networks.forEach {
                val cap = cm.getNetworkCapabilities(it)
                val lp = cm.getLinkProperties(it)
                val prop =
                    if (cap != null) {
                        val nwType =
                            networkType(cap) + ", NotMetered?" + isConnectionNotMetered(cap)
                        val ssid = networkSet.firstOrNull { ns -> ns.network == it }?.ssid
                        NetworkProperties(it, cap, lp, nwType, ssid)
                    } else {
                        null
                    }
                if (isNetworkSame(it, activeNetwork)) {
                    return@forEach
                }

                // test for internet capability iff opPrefs.testReachability is false
                if (/*hasInternet(it) == true &&*/ prop != null && isVPN(it) == false) {
                    newNetworks.add(prop)
                }
            }

            return newNetworks
        }


        /**
         * Checks if the given network has internet capability.
         *
         * @param network The network to check.
         * @return True if the network has internet capability, false otherwise.
         */
        private fun hasInternet(network: Network?): Boolean {
            // TODO: consider checking for NET_CAPABILITY_NOT_SUSPENDED, NET_CAPABILITY_VALIDATED?
            if (network == null) return false

            return cm
                .getNetworkCapabilities(network)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }

        /**
         * Checks if the network is a captive portal.
         * A captive portal is a web page that the user of a public-access network is
         * obliged to view and interact with before access is granted.
         *
         * @param network The network to check.
         * @return True if the network is a captive portal, false otherwise.
         */
        private fun isCaptivePortal(network: Network): Boolean {
            return cm
                .getNetworkCapabilities(network)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true
        }

        /**
         * Checks if the network is validated.
         * A validated network is a network that has been tested by the system to have functional
         * Internet connectivity.
         * @param network The network to check.
         * @return True if the network is validated, false otherwise.
         */
        private fun isValidated(network: Network): Boolean {
            return cm
                .getNetworkCapabilities(network)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        }

        /**
         * Checks if the given network is a VPN network.
         *
         * @param network The network to check.
         * @return True if the network is a VPN, false if it's not, or null if the network is null
         * or its capabilities cannot be determined.
         */
        private fun isVPN(network: Network?): Boolean? {
            if (network == null) return null
            return cm
                .getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }
}
