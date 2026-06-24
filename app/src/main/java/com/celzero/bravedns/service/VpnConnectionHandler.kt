/*
 * Copyright 2024 RethinkDNS and its authors
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
import Logger.LOG_TAG_VPN
import android.content.pm.PackageManager
import android.net.VpnService
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.util.InternetProtocol
import com.celzero.bravedns.util.Utilities

/**
 * Handles VPN connection builder configuration.
 * Extracted from BraveVPNService for better separation of concerns.
 */
class VpnConnectionHandler(
    private val vpnService: VpnService,
    private val persistentState: PersistentState,
    private val appConfig: AppConfig
) {
    companion object {
        private const val TAG = "VpnConnHandler"

        // IPv4 VPN constants
        const val IPV4_TEMPLATE = "10.111.222.%d"
        const val IPV4_PREFIX_LENGTH = 24

        // IPv6 VPN constants
        const val IPV6_TEMPLATE = "fd66:f83a:c650::%d"
        const val IPV6_PREFIX_LENGTH = 120

        const val VPN_INTERFACE_MTU = 1500
        const val MIN_MTU = 1280
        const val MAX_MTU = 10000

        private const val IPV4_DNS_ADDR = 44
        private const val IPV6_DNS_ADDR = 44
    }

    enum class LanIp(val id: Int) {
        DNS(44);

        fun make(template: String): String {
            return String.format(template, id)
        }
    }

    /**
     * Creates a new VPN.Builder with appropriate configuration.
     */
    fun createBuilder(
        underlyingNetworks: NetworkBindingService.UnderlyingNetworks?,
        isVpnLockdown: Boolean,
        excludedApps: Set<String>,
        rethinkUid: Int
    ): VpnService.Builder {
        var builder = vpnService.Builder()
        
        val networks = getUnderlyingNetworkArray(underlyingNetworks)
        builder.setUnderlyingNetworks(networks)
        
        if (!isVpnLockdown && !Utilities.isPlayStoreFlavour() && canAllowBypass()) {
            Logger.i(LOG_TAG_VPN, "$TAG allow apps to bypass vpn on-demand")
            builder = builder.allowBypass()
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            builder.setMetered(persistentState.setVpnBuilderToMetered)
        }

        if (!persistentState.routeRethinkInRethink) {
            Logger.i(LOG_TAG_VPN, "$TAG builder: exclude rethink app from builder")
            addDisallowedApplication(builder, vpnService.packageName)
        }

        if (isAppPaused()) {
            if (!isVpnLockdown) {
                // Simplified: in paused mode, just skip excluded apps handling
                Logger.i(LOG_TAG_VPN, "$TAG paused mode, skipping app exclusions")
            }
            return builder
        }

        if (appConfig.determineFirewallMode().isFirewallSinkMode()) {
            addAllowedApplication(builder, excludedApps)
        } else if (!isVpnLockdown) {
            Logger.i(LOG_TAG_VPN, "$TAG builder, vpn is not lockdown, exclude-apps ${excludedApps.size}")
            addDisallowedApplications(builder, excludedApps)
        }

        return builder
    }

    /**
     * Configures the VPN interface with addresses and routes.
     */
    fun configureInterface(
        builder: VpnService.Builder,
        mtu: Int,
        customLanMode: Boolean
    ): VpnService.Builder {
        builder.setMtu(mtu)
        
        val ipType = persistentState.internetProtocolType

        when (ipType) {
            InternetProtocol.IPv4.id -> {
                configureIPv4(builder, customLanMode)
            }
            InternetProtocol.IPv6.id -> {
                configureIPv6(builder, customLanMode)
            }
            InternetProtocol.IPv46.id, InternetProtocol.ALWAYSv46.id -> {
                configureIPv4(builder, customLanMode)
                configureIPv6(builder, customLanMode)
            }
        }

        return builder
    }

    private fun configureIPv4(builder: VpnService.Builder, customLanMode: Boolean) {
        val fakeDns = LanIp.DNS.make(IPV4_TEMPLATE)
        
        if (customLanMode) {
            val customDns = persistentState.customLanDnsIpv4.split("/").firstOrNull() ?: ""
            if (customDns.isNotEmpty()) {
                builder.addAddress(customDns, IPV4_PREFIX_LENGTH)
            }
        } else {
            builder.addAddress(fakeDns, IPV4_PREFIX_LENGTH)
        }

        if (!customLanMode) {
            builder.addDnsServer(fakeDns)
        }

        builder.addRoute("0.0.0.0", 0)
    }

    private fun configureIPv6(builder: VpnService.Builder, customLanMode: Boolean) {
        val fakeDns = LanIp.DNS.make(IPV6_TEMPLATE)
        
        if (customLanMode) {
            val customDns = persistentState.customLanDnsIpv6.split("/").firstOrNull() ?: ""
            if (customDns.isNotEmpty()) {
                builder.addAddress(customDns, IPV6_PREFIX_LENGTH)
            }
        } else {
            builder.addAddress(fakeDns, IPV6_PREFIX_LENGTH)
        }

        if (!customLanMode) {
            builder.addDnsServer(fakeDns)
        }

        builder.addRoute("::", 0)
    }

    private fun canAllowBypass(): Boolean {
        return persistentState.allowBypass && !appConfig.isProxyEnabled()
    }

    private fun addDisallowedApplication(builder: VpnService.Builder, packageName: String) {
        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            Logger.w(LOG_TAG_VPN, "$TAG failed to exclude $packageName: ${e.message}")
        }
    }

    private fun addDisallowedApplications(builder: VpnService.Builder, packageNames: Collection<String>) {
        packageNames.forEach { addDisallowedApplication(builder, it) }
    }

    private fun addAllowedApplication(builder: VpnService.Builder, packageNames: Collection<String>) {
        packageNames.forEach { pkg ->
            try {
                builder.addAllowedApplication(pkg)
            } catch (e: PackageManager.NameNotFoundException) {
                Logger.w(LOG_TAG_VPN, "$TAG failed to allow $pkg: ${e.message}")
            }
        }
    }

    private fun getUnderlyingNetworkArray(
        networks: NetworkBindingService.UnderlyingNetworks?
    ): Array<android.net.Network>? {
        if (networks == null) return null
        
        val allNetworks = mutableListOf<android.net.Network>()
        allNetworks.addAll(networks.ipv4Net.map { it.network })
        allNetworks.addAll(networks.ipv6Net.map { it.network })
        
        return if (allNetworks.isEmpty()) null else allNetworks.toTypedArray()
    }

    private fun isAppPaused(): Boolean = VpnController.isAppPaused()
}
