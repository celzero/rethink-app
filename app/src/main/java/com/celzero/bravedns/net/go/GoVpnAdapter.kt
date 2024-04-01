/*
 * Copyright 2021 RethinkDNS and its authors
 * Copyright 2019 Jigsaw Operations LLC
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
package com.celzero.bravedns.net.go

import android.content.Context
import android.content.res.Resources
import android.os.ParcelFileDescriptor
import android.util.Log
import android.widget.Toast
import backend.Backend
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.data.AppConfig.Companion.FALLBACK_DNS
import com.celzero.bravedns.data.AppConfig.TunnelOptions
import com.celzero.bravedns.database.ProxyEndpoint
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.ProxyManager.ID_WG_BASE
import com.celzero.bravedns.service.RethinkBlocklistManager
import com.celzero.bravedns.service.TcpProxyHelper
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.MAX_ENDPOINT
import com.celzero.bravedns.util.Constants.Companion.ONDEVICE_BLOCKLIST_FILE_TAG
import com.celzero.bravedns.util.Constants.Companion.REMOTE_BLOCKLIST_DOWNLOAD_FOLDER_NAME
import com.celzero.bravedns.util.Constants.Companion.RETHINKDNS_DOMAIN
import com.celzero.bravedns.util.Constants.Companion.RETHINK_BASE_URL_SKY
import com.celzero.bravedns.util.Constants.Companion.UNSPECIFIED_IP_IPV4
import com.celzero.bravedns.util.Constants.Companion.UNSPECIFIED_IP_IPV6
import com.celzero.bravedns.util.InternetProtocol
import com.celzero.bravedns.util.Logger.Companion.LOG_TAG_VPN
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.blocklistDir
import com.celzero.bravedns.util.Utilities.blocklistFile
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.celzero.bravedns.wireguard.Config
import intra.Intra
import intra.Tunnel
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * This is a VpnAdapter that captures all traffic and routes it through a go-tun2socks instance with
 * custom logic for Intra.
 */
class GoVpnAdapter : KoinComponent {
    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()

    // The Intra session object from go-tun2socks.  Initially null.
    private var tunnel: Tunnel
    private var context: Context
    private var externalScope: CoroutineScope

    constructor(
        context: Context,
        externalScope: CoroutineScope,
        tunFd: ParcelFileDescriptor,
        opts: TunnelOptions
    ) {
        this.context = context
        this.externalScope = externalScope

        val defaultDns = newDefaultTransport(appConfig.getDefaultDns())
        // no need to connect tunnel if already connected, just reset the tunnel with new
        // parameters
        Log.i(LOG_TAG_VPN, "connect tunnel with new params")
        this.tunnel =
            Intra.connect(
                tunFd.fd.toLong(),
                opts.mtu.toLong(),
                opts.fakeDns,
                defaultDns,
                opts.bridge
            ) // may throw exception
        setTunnelMode(opts)
    }

    suspend fun initResolverProxiesPcap(opts: TunnelOptions) {
        if (!tunnel.isConnected) {
            Log.i(LOG_TAG_VPN, "Tunnel NOT connected, skip init resolver proxies")
            return
        }

        // setTcpProxyIfNeeded()
        // no need to add default in init as it is already added in connect
        // addDefaultTransport(appConfig.getDefaultDns())
        setRoute(opts)
        setWireguardTunnelModeIfNeeded(opts.tunProxyMode)
        setSocks5TunnelModeIfNeeded(opts.tunProxyMode)
        setHttpProxyIfNeeded(opts.tunProxyMode)
        setPcapMode(appConfig.getPcapFilePath())
        // TODO: ideally the values required for transport, alg and rdns should be set in the
        // opts itself.
        setRDNS()
        addTransport()
        setDnsAlg()
    }

    fun setPcapMode(pcapFilePath: String) {
        try {
            Log.i(LOG_TAG_VPN, "set pcap mode: $pcapFilePath")
            tunnel.setPcap(pcapFilePath)
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "err setting pcap: ${e.message}", e)
        }
    }

    private fun setRoute(tunnelOptions: TunnelOptions) {
        try {
            // setRoute can throw exception iff preferredEngine is invalid, which is not possible
            // Log.d(LOG_TAG_VPN, "set route: ${tunnelOptions.preferredEngine.value()}")
            // tunnel.setRoute(tunnelOptions.preferredEngine.value())
            // above code is commented as the route is now set as default instead of preferred
            tunnel.setRoute(InternetProtocol.IPv46.value())

            // on route change, set the tun mode again for ptMode
            tunnel.setTunMode(
                tunnelOptions.tunDnsMode.mode,
                tunnelOptions.tunFirewallMode.mode,
                tunnelOptions.ptMode.id
            )
            Log.i(LOG_TAG_VPN, "set route: ${InternetProtocol.IPv46.value()}")
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "err setting route: ${e.message}", e)
        }
    }

    suspend fun addTransport() {
        if (!tunnel.isConnected) {
            Log.i(LOG_TAG_VPN, "Tunnel NOT connected, skip add transport")
            return
        }

        // TODO: #321 As of now there is no block free transport for dns types other than Rethink.
        // introduce block free transport for other dns types when there is a UI to configure the
        // block free transport
        // always set the block free transport before setting the transport to the resolver
        // because of the way the alg is implemented in the go code.
        when (appConfig.getDnsType()) {
            AppConfig.DnsType.DOH -> {
                addDohTransport(Backend.Preferred)
            }
            AppConfig.DnsType.DOT -> {
                addDotTransport(Backend.Preferred)
            }
            AppConfig.DnsType.ODOH -> {
                addOdohTransport(Backend.Preferred)
            }
            AppConfig.DnsType.DNSCRYPT -> {
                addDnscryptTransport(Backend.Preferred)
            }
            AppConfig.DnsType.DNS_PROXY -> {
                addDnsProxyTransport(Backend.Preferred)
            }
            AppConfig.DnsType.SYSTEM_DNS -> {
                // no-op; system dns propagated by ConnectionMonitor
            }
            AppConfig.DnsType.RETHINK_REMOTE -> {
                // only rethink has different stamp for block free transport
                // create a new transport for block free
                val rdnsRemoteUrl = appConfig.getRemoteRethinkEndpoint()?.url
                val blockfreeUrl = appConfig.getBlockFreeRethinkEndpoint()

                if (blockfreeUrl.isNotEmpty()) {
                    Log.i(LOG_TAG_VPN, "adding blockfree url: $blockfreeUrl")
                    addRdnsTransport(Backend.BlockFree, blockfreeUrl)
                } else {
                    Log.i(LOG_TAG_VPN, "no blockfree url found")
                    // if blockfree url is not available, do not proceed further
                    return
                }
                if (!rdnsRemoteUrl.isNullOrEmpty()) {
                    Log.i(LOG_TAG_VPN, "adding rdns remote url: $rdnsRemoteUrl")
                    addRdnsTransport(Backend.Preferred, rdnsRemoteUrl)
                } else {
                    Log.i(LOG_TAG_VPN, "no rdns remote url found")
                }
            }
        }
    }

    private suspend fun addDohTransport(id: String) {
        var url: String? = null
        try {
            val doh = appConfig.getDOHDetails()
            url = doh?.dohURL
            // change the url from https to http if the isSecure is false
            if (doh?.isSecure == false) {
                if (DEBUG) Log.d(LOG_TAG_VPN, "changing url from https to http for $url")
                url = url?.replace("https", "http")
            }
            val ips: String = getIpString(context, url)
            // add replaces the existing transport with the same id if successful
            // so no need to remove the transport before adding
            Intra.addDoHTransport(tunnel, id, url, ips)
            Log.i(LOG_TAG_VPN, "new doh: $id (${doh?.dohName}), url: $url, ips: $ips")
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "connect-tunnel: doh failure", e)
            getResolver()?.remove(id)
            showDnsFailureToast(url ?: "")
        }
    }

    private suspend fun addDotTransport(id: String) {
        var url: String? = null
        try {
            val dot = appConfig.getDOTDetails()
            url = dot?.url
            if (dot?.isSecure == true && url?.startsWith("tls") == false) {
                if (DEBUG) Log.d(LOG_TAG_VPN, "adding tls to url for $url")
                // add tls to the url if isSecure is true and the url does not start with tls
                url = "tls://$url"
            }
            val ips: String = getIpString(context, url)
            // add replaces the existing transport with the same id if successful
            // so no need to remove the transport before adding
            Intra.addDoTTransport(tunnel, id, url, ips)
            Log.i(LOG_TAG_VPN, "new dot: $id (${dot?.name}), url: $url, ips: $ips")
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "connect-tunnel: dot failure", e)
            getResolver()?.remove(id)
            showDnsFailureToast(url ?: "")
        }
    }

    private suspend fun addOdohTransport(id: String) {
        var resolver: String? = null
        try {
            val odoh = appConfig.getODoHDetails()
            val proxy = odoh?.proxy
            resolver = odoh?.resolver
            val proxyIps = ""
            // add replaces the existing transport with the same id if successful
            // so no need to remove the transport before adding
            Intra.addODoHTransport(tunnel, id, proxy, resolver, proxyIps)
            Log.i(LOG_TAG_VPN, "new odoh: $id (${odoh?.name}), p: $proxy, r: $resolver")
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "connect-tunnel: odoh failure", e)
            getResolver()?.remove(id)
            showDnsFailureToast(resolver ?: "")
        }
    }

    private suspend fun addDnscryptTransport(id: String) {
        try {
            val dc = appConfig.getConnectedDnscryptServer()
            val url = dc.dnsCryptURL
            // add replaces the existing transport with the same id if successful
            // so no need to remove the transport before adding
            Intra.addDNSCryptTransport(tunnel, id, url)
            Log.i(LOG_TAG_VPN, "new dnscrypt: $id (${dc.dnsCryptName}), url: $url")
            // setDnscryptRelaysIfAny() is expected to catch exceptions
            setDnscryptRelaysIfAny()
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "connect-tunnel: dns crypt failure for $id", e)
            getResolver()?.remove(id)
            showDnscryptConnectionFailureToast()
        }
    }

    private suspend fun addDnsProxyTransport(id: String) {
        try {
            val dnsProxy = appConfig.getSelectedDnsProxyDetails() ?: return
            // add replaces the existing transport with the same id if successful
            // so no need to remove the transport before adding
            Intra.addDNSProxy(tunnel, id, dnsProxy.proxyIP, dnsProxy.proxyPort.toString())
            Log.i(
                LOG_TAG_VPN,
                "new dns proxy: $id(${dnsProxy.proxyName}), ip: ${dnsProxy.proxyIP}, port: ${dnsProxy.proxyPort}"
            )
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "connect-tunnel: dns proxy failure", e)
            getResolver()?.remove(id)
            showDnsProxyConnectionFailureToast()
        }
    }

    private suspend fun addRdnsTransport(id: String, url: String) {
        try {
            val ips: String = getIpString(context, url)
            val convertedUrl = getRdnsUrl(url) ?: return
            if (url.contains(RETHINK_BASE_URL_SKY)) {
                Intra.addDoHTransport(tunnel, id, convertedUrl, ips)
                Log.i(LOG_TAG_VPN, "new doh (rdns): $id, url: $convertedUrl, ips: $ips")
            } else {
                Intra.addDoTTransport(tunnel, id, convertedUrl, ips)
                Log.i(LOG_TAG_VPN, "new dot (rdns): $id, url: $convertedUrl, ips: $ips")
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "connect-tunnel: rdns creation failure", e)
            getResolver()?.remove(id)
            showDnsFailureToast(url)
        }
    }

    private fun getRdnsUrl(url: String): String? {
        val tls = "tls://"
        val default = "dns-query"
        // do not proceed if rethinkdns.com is not available
        if (!url.contains(RETHINKDNS_DOMAIN)) return null

        // if url is MAX, convert it to dot format, DOT format stamp.max.rethinkdns.com
        // if url is SKY, convert it to doh format, DOH format https://sky.rethikdns.com/stamp

        // for blockfree, the url is https://max.rethinkdns.com/dns-query
        if (url == Constants.BLOCK_FREE_DNS_MAX) {
            return "$tls$MAX_ENDPOINT.$RETHINKDNS_DOMAIN"
        } else if (url == Constants.BLOCK_FREE_DNS_SKY) {
            return url
        } else {
            // no-op, pass-through
        }
        val stamp = getRdnsStamp(url)
        return if (url.contains(MAX_ENDPOINT)) {
            // if the stamp is empty or "dns-query", then remove it
            if (stamp.isEmpty() || stamp == default) {
                return "$tls$MAX_ENDPOINT.$RETHINKDNS_DOMAIN"
            }
            "$tls$stamp.$MAX_ENDPOINT.$RETHINKDNS_DOMAIN"
        } else {
            "$RETHINK_BASE_URL_SKY$stamp"
        }
    }

    private fun getRdnsStamp(url: String): String {
        val s = url.split(RETHINKDNS_DOMAIN)[1]
        val stamp =
            if (s.startsWith("/")) {
                s.substring(1)
            } else {
                s
            }
        return getBase32Stamp(stamp)
    }

    private fun getBase32Stamp(stamp: String): String {
        // in v055a, the stamps are generated either by base32 or base64
        // if the stamp is base64, then convert it to base32
        // if the stamp is base32, then return the stamp
        // as of now, only way to find the base 32 stamp is to get the flags and convert it to stamp
        // with eb32 encoding
        // sample stamp https://max.rethinkdns.com/1:-AcHAL6d_5-Yxf7zMQAKAAAI which is base64
        // output stamp 1-7adqoaf6tx7z7ggf73ztcaakaaaaq which is base32
        var b32Stamp: String? = null
        try {
            val r = getRDNS(RethinkBlocklistManager.RethinkBlocklistType.REMOTE)
            val flags = r?.stampToFlags(stamp)
            b32Stamp = r?.flagsToStamp(flags, Backend.EB32)
        } catch (e: Exception) {
            Log.w(LOG_TAG_VPN, "err get base32 stamp: ${e.message}")
        }
        return b32Stamp ?: stamp
    }

    private fun setTunnelMode(tunnelOptions: TunnelOptions) {
        tunnel.setTunMode(
            tunnelOptions.tunDnsMode.mode,
            tunnelOptions.tunFirewallMode.mode,
            tunnelOptions.ptMode.id
        )
    }

    suspend fun setTunMode(tunnelOptions: TunnelOptions) {
        if (!tunnel.isConnected) {
            Log.i(LOG_TAG_VPN, "Tunnel NOT connected, skip set-tun-mode")
            return
        }
        try {
            tunnel.setTunMode(
                tunnelOptions.tunDnsMode.mode,
                tunnelOptions.tunFirewallMode.mode,
                tunnelOptions.ptMode.id
            )
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "err set tun mode: ${e.message}", e)
        }
    }

    suspend fun setRDNS() {
        // Set brave dns to tunnel - Local/Remote
        if (DEBUG) Log.d(LOG_TAG_VPN, "set brave dns to tunnel (local/remote)")

        // enable local blocklist if enabled
        if (persistentState.blocklistEnabled) {
            setRDNSLocal()
        } else {
            // remove local blocklist, if any
            getRDNSResolver()?.setRdnsLocal(null, null, null, null)
        }

        // always set the remote blocklist
        setRDNSRemote()
    }

    private fun setRDNSRemote() {
        if (DEBUG) Log.d(LOG_TAG_VPN, "init remote rdns mode")
        try {
            val remoteDir =
                blocklistDir(
                    context,
                    REMOTE_BLOCKLIST_DOWNLOAD_FOLDER_NAME,
                    persistentState.remoteBlocklistTimestamp
                ) ?: return
            val remoteFile =
                blocklistFile(remoteDir.absolutePath, ONDEVICE_BLOCKLIST_FILE_TAG) ?: return
            if (remoteFile.exists()) {
                getRDNSResolver()?.setRdnsRemote(remoteFile.absolutePath)
                Log.i(LOG_TAG_VPN, "remote-rdns enabled")
            } else {
                Log.w(LOG_TAG_VPN, "filetag.json for remote-rdns missing")
            }
        } catch (ex: Exception) {
            Log.e(LOG_TAG_VPN, "cannot set remote-rdns: ${ex.message}", ex)
        }
    }

    private suspend fun setDnscryptRelaysIfAny() {
        val routes: String = appConfig.getDnscryptRelayServers()
        routes.split(",").forEach {
            if (it.isBlank()) return@forEach

            Log.i(LOG_TAG_VPN, "new dnscrypt relay: $it")
            try {
                Intra.addDNSCryptRelay(tunnel, it) // entire url is the id
            } catch (ex: Exception) {
                Log.e(LOG_TAG_VPN, "connect-tunnel: dnscrypt failure", ex)
                appConfig.removeDnscryptRelay(it)
                getResolver()?.remove(it)
            }
        }
    }

    /**
     * TODO - Move these code to common place and set the tunnel mode and other parameters. Return
     * the tunnel to the adapter.
     */
    private fun setSocks5Proxy(
        tunProxyMode: AppConfig.TunProxyMode,
        userName: String?,
        password: String?,
        ipAddress: String?,
        port: Int
    ) {
        try {
            val url = constructSocks5ProxyUrl(userName, password, ipAddress, port)
            val id =
                if (tunProxyMode.isTunProxyOrbot()) {
                    ProxyManager.ID_ORBOT_BASE
                } else {
                    ProxyManager.ID_S5_BASE
                }
            val result = getProxies()?.addProxy(id, url)
            Log.i(LOG_TAG_VPN, "Proxy mode set with tunnel url($id): $url, result: $result")
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "connect-tunnel: err start proxy $userName@$ipAddress:$port", e)
        }
    }

    private fun constructSocks5ProxyUrl(
        userName: String?,
        password: String?,
        ipAddress: String?,
        port: Int
    ): String {
        // socks5://<username>:<password>@<ip>:<port>
        // convert string to url
        val proxyUrl = StringBuilder()
        proxyUrl.append("socks5://")
        if (!userName.isNullOrEmpty()) {
            proxyUrl.append(userName)
            if (!password.isNullOrEmpty()) {
                proxyUrl.append(":")
                proxyUrl.append(password)
            }
            proxyUrl.append("@")
        }
        proxyUrl.append(ipAddress)
        proxyUrl.append(":")
        proxyUrl.append(port)
        return URI.create(proxyUrl.toString()).toASCIIString()
    }

    private fun showDnscryptConnectionFailureToast() {
        ui {
            showToastUiCentered(
                context.applicationContext,
                context.getString(R.string.dns_crypt_connection_failure),
                Toast.LENGTH_LONG
            )
        }
    }

    private fun showDnsFailureToast(url: String) {
        ui {
            val msg =
                context.getString(
                    R.string.two_argument_colon,
                    url,
                    context.getString(R.string.dns_proxy_connection_failure)
                )
            showToastUiCentered(context.applicationContext, msg, Toast.LENGTH_LONG)
        }
    }

    private fun showWireguardFailureToast(message: String) {
        ui { showToastUiCentered(context.applicationContext, message, Toast.LENGTH_LONG) }
    }

    private fun showDnsProxyConnectionFailureToast() {
        ui {
            showToastUiCentered(
                context.applicationContext,
                context.getString(R.string.dns_proxy_connection_failure),
                Toast.LENGTH_SHORT
            )
        }
    }

    private suspend fun setWireguardTunnelModeIfNeeded(tunProxyMode: AppConfig.TunProxyMode) {
        if (!tunProxyMode.isTunProxyWireguard()) return

        val wgConfigs: List<Config> = WireguardManager.getEnabledConfigs()
        if (wgConfigs.isEmpty()) {
            Log.i(LOG_TAG_VPN, "no active wireguard configs found")
            return
        }
        wgConfigs.forEach {
            val id = ID_WG_BASE + it.getId()
            addWgProxy(id)
        }
    }

    private fun setWireGuardDns(id: String) {
        try {
            val p = getProxies()?.getProxy(id)
            if (p == null) {
                Log.w(LOG_TAG_VPN, "wireguard proxy not found for id: $id")
                return
            }
            Intra.addProxyDNS(tunnel, p) // dns transport has same id as the proxy (p)
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "wireguard dns failure", e)
            getResolver()?.remove(id)
            showDnsFailureToast(id)
        }
    }

    suspend fun setCustomProxy(tunProxyMode: AppConfig.TunProxyMode) {
        setSocks5TunnelModeIfNeeded(tunProxyMode)
        setHttpProxyIfNeeded(tunProxyMode)
    }

    private suspend fun setSocks5TunnelModeIfNeeded(tunProxyMode: AppConfig.TunProxyMode) {
        val socksEnabled = AppConfig.ProxyType.of(persistentState.proxyType).isSocks5Enabled()
        if (!socksEnabled) return

        val socks5: ProxyEndpoint? =
            if (tunProxyMode.isTunProxyOrbot()) {
                appConfig.getConnectedOrbotProxy()
            } else {
                appConfig.getSocks5ProxyDetails()
            }
        if (socks5 == null) {
            Log.w(LOG_TAG_VPN, "could not fetch socks5 details for proxyMode: $tunProxyMode")
            return
        }
        setSocks5Proxy(
            tunProxyMode,
            socks5.userName,
            socks5.password,
            socks5.proxyIP,
            socks5.proxyPort
        )
        Log.i(LOG_TAG_VPN, "Socks5 mode set: " + socks5.proxyIP + "," + socks5.proxyPort)
    }

    fun getProxyStatusById(id: String): Long? {
        return try {
            val status = getProxyById(id)?.status()
            if (DEBUG) Log.d(LOG_TAG_VPN, "proxy status($id): $status")
            status
        } catch (ignored: Exception) {
            Log.i(LOG_TAG_VPN, "err getProxy($id) ignored: ${ignored.message}")
            null
        }
    }

    private fun getProxyById(id: String): backend.Proxy? {
        return try {
            getProxies()?.getProxy(id)
        } catch (ignored: Exception) {
            Log.i(LOG_TAG_VPN, "err getProxy($id) ignored: ${ignored.message}")
            null
        }
    }

    private suspend fun setHttpProxyIfNeeded(tunProxyMode: AppConfig.TunProxyMode) {
        if (!AppConfig.ProxyType.of(appConfig.getProxyType()).isProxyTypeHasHttp()) return

        try {
            val endpoint: ProxyEndpoint
            val id =
                if (tunProxyMode.isTunProxyOrbot()) {
                    endpoint = appConfig.getOrbotHttpEndpoint()
                    ProxyManager.ID_ORBOT_BASE
                } else {
                    endpoint = appConfig.getHttpProxyDetails()
                    ProxyManager.ID_HTTP_BASE
                }
            val httpProxyUrl = endpoint.proxyIP ?: return

            Log.i(LOG_TAG_VPN, "Http mode set with url: $httpProxyUrl")
            getProxies()?.addProxy(id, httpProxyUrl)
        } catch (e: Exception) {
            if (tunProxyMode.isTunProxyOrbot()) {
                appConfig.removeProxy(AppConfig.ProxyType.HTTP, AppConfig.ProxyProvider.ORBOT)
            } else {
                appConfig.removeProxy(AppConfig.ProxyType.HTTP, AppConfig.ProxyProvider.CUSTOM)
            }
            Log.e(LOG_TAG_VPN, "error setting http proxy: ${e.message}", e)
        }
    }

    fun removeWgProxy(id: Int) {
        if (!tunnel.isConnected) {
            Log.i(LOG_TAG_VPN, "Tunnel NOT connected, skip refreshing wg")
            return
        }
        try {
            val id0 = ID_WG_BASE + id
            getProxies()?.removeProxy(id0)
            val isDnsNeeded = WireguardManager.getOneWireGuardProxyId() == id
            // remove the dns if the wg proxy is removed
            if (isDnsNeeded) {
                getResolver()?.remove(id0)
            }
            Log.i(LOG_TAG_VPN, "remove wireguard proxy with id: $id")
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "error removing wireguard proxy: ${e.message}", e)
        }
    }

    suspend fun addWgProxy(id: String) {
        if (!tunnel.isConnected) {
            Log.i(LOG_TAG_VPN, "Tunnel NOT connected, skip add wg")
            return
        }
        try {
            val proxyId: Int? = id.substring(ID_WG_BASE.length).toIntOrNull()
            if (proxyId == null) {
                Log.e(LOG_TAG_VPN, "invalid wireguard proxy id: $id")
                return
            }

            val wgConfig = WireguardManager.getConfigById(proxyId)
            val withDNS = WireguardManager.getOneWireGuardProxyId() == proxyId
            val wgUserSpaceString = wgConfig?.toWgUserspaceString()
            getProxies()?.addProxy(id, wgUserSpaceString)
            if (withDNS) setWireGuardDns(id)
            Log.i(LOG_TAG_VPN, "add wireguard proxy with $id; dns? $withDNS")
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "error adding wireguard proxy: ${e.message}", e)
            // do not auto remove failed wg proxy, let the user decide via UI
            // WireguardManager.disableConfig(id)
            showWireguardFailureToast(
                e.message ?: context.getString(R.string.wireguard_connection_error)
            )
        }
    }

    fun closeConnections(connIds: List<String>) {
        if (!tunnel.isConnected) {
            Log.i(LOG_TAG_VPN, "Tunnel not connected, skip disconnecting connection")
            return
        }

        val connIdsStr = connIds.joinToString(",")
        val res = tunnel.closeConns(connIdsStr)
        Log.i(LOG_TAG_VPN, "close connection: $connIds, res: $res")
    }

    fun refreshProxies() {
        if (!tunnel.isConnected) {
            Log.i(LOG_TAG_VPN, "Tunnel NOT connected, skip refreshing proxies")
            return
        }
        try {
            val res = getProxies()?.refreshProxies()
            Log.i(LOG_TAG_VPN, "refresh proxies: $res")
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "error refreshing proxies: ${e.message}", e)
        }
    }

    fun getDnsStatus(id: String): Long? {
        try {
            val transport = getResolver()?.get(id)
            val tid = transport?.id()
            val status = transport?.status()
            // some special transports like blockfree, preferred,alg etc are handled specially.
            // in those cases, if the transport id is not there, it will serve the default transport
            // so return null in those cases
            if (tid != id) {
                return null
            }
            return status
        } catch (e: Exception) {
            Log.w(LOG_TAG_VPN, "err dns status($id): ${e.message}")
        }
        return null
    }

    fun getRDNS(type: RethinkBlocklistManager.RethinkBlocklistType): backend.RDNS? {
        try {
            return if (type.isLocal()) {
                getRDNSResolver()?.rdnsLocal
            } else {
                getRDNSResolver()?.rdnsRemote
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG_VPN, "err getRDNS($type): ${e.message}", e)
        }
        return null
    }

    // v055, unused
    suspend fun setTcpProxy() {
        if (!appConfig.isTcpProxyEnabled()) {
            Log.i(LOG_TAG_VPN, "tcp proxy not enabled")
            return
        }

        val u = TcpProxyHelper.getActiveTcpProxy()
        if (u == null) {
            Log.w(LOG_TAG_VPN, "could not fetch tcp proxy details")
            return
        }

        val ips = getIpString(context, "https://sky.rethinkdns.com/")
        Log.d(LOG_TAG_VPN, "ips: $ips")
        // svc.rethinkdns.com / duplex.deno.dev
        val testpipwsurl = "pipws://proxy.nile.workers.dev/ws/nosig"
        val ok = getProxies()?.addProxy(ProxyManager.ID_TCP_BASE, testpipwsurl)
        if (DEBUG) Log.d(LOG_TAG_VPN, "tcp-mode(${ProxyManager.ID_TCP_BASE}): ${u.url}, ok? $ok")

        val secWarp = WireguardManager.getSecWarpConfig()
        if (secWarp == null) {
            Log.w(LOG_TAG_VPN, "no sec warp config found")
            return
        }
        val wgUserSpaceString = secWarp.toWgUserspaceString()
        val ok2 = getProxies()?.addProxy(ID_WG_BASE + secWarp.getId(), wgUserSpaceString)
        if (DEBUG)
            Log.d(
                LOG_TAG_VPN,
                "tcp-mode(wg) set(${ID_WG_BASE+ secWarp.getId()}): ${secWarp.getName()}, res: $ok2"
            )
    }

    fun hasTunnel(): Boolean {
        return tunnel.isConnected
    }

    fun refreshResolvers() {
        getResolver()?.refresh()
    }

    fun closeTun() {
        if (tunnel.isConnected) {
            Log.i(LOG_TAG_VPN, "Tunnel disconnect")
            tunnel.disconnect()
        } else {
            Log.i(LOG_TAG_VPN, "Tunnel already disconnected")
        }
    }

    private fun newDefaultTransport(url: String): intra.DefaultDNS {
        val defaultDns = FALLBACK_DNS
        try {
            // when the url is empty, set the default transport to 8.8.4.4:53
            if (url.isEmpty()) {
                Log.i(LOG_TAG_VPN, "set default transport to $defaultDns, as url is empty")
                return Intra.newDefaultDNS(Backend.DNS53, defaultDns, "")
            }
            val ips: String = getIpString(context, url)
            if (DEBUG) Log.d(LOG_TAG_VPN, "default transport url: $url ips: $ips")
            if (url.contains("http")) {
                return Intra.newDefaultDNS(Backend.DOH, url, ips)
            }
            // no need to set ips for dns53
            return Intra.newDefaultDNS(Backend.DNS53, url, "")
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "err new default transport: ${e.message}", e)
            // most of the android devices have google dns, so add it as default transport
            // TODO: notify the user that the default transport could not be set
            return Intra.newDefaultDNS(Backend.DNS53, defaultDns, "")
        }
    }

    fun addDefaultTransport(url: String?) {
        val defaultDns = FALLBACK_DNS
        try {
            if (!tunnel.isConnected) {
                Log.i(LOG_TAG_VPN, "Tunnel not connected, skip new default transport")
                return
            }
            // when the url is empty, set the default transport to DEFAULT_DNS_IP
            // default transport is always sent to Ipn.Exit in the go code and so dns
            // request sent to the default transport will not be looped back into the tunnel
            if (url.isNullOrEmpty()) {
                Log.i(LOG_TAG_VPN, "url empty, set default trans to $defaultDns")
                Intra.addDefaultTransport(tunnel, Backend.DNS53, defaultDns, "")
                return
            }

            val ips: String = getIpString(context, url)
            if (DEBUG) Log.d(LOG_TAG_VPN, "default transport url: $url ips: $ips")
            if (url.contains("http")) {
                Intra.addDefaultTransport(tunnel, Backend.DOH, url, ips)
            } else {
                Intra.addDefaultTransport(tunnel, Backend.DNS53, url, "")
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "err new default transport: ${e.message}", e)
            // most of the android devices have google dns, so add it as default transport
            // TODO: notify the user that the default transport could not be set
            Intra.addDefaultTransport(tunnel, Backend.DNS53, defaultDns, "")
        }
    }

    suspend fun setSystemDns(systemDns: List<String>) {
        if (!tunnel.isConnected) {
            Log.i(LOG_TAG_VPN, "Tunnel NOT connected, skip setting system-dns")
        }
        // for Rethink within rethink mode, the system dns is system dns is always set to Ipn.Base
        // in go and so dns request sent to the system dns will be looped back into the tunnel
        try {
            // TODO: system dns may be non existent; see: AppConfig#updateSystemDnsServers
            // convert list to comma separated string, as Intra expects as csv
            val sysDnsStr = systemDns.joinToString(",")
            // below code is commented out, add the code to set the system dns via resolver
            // val transport = Intra.newDNSProxy("ID", dnsProxy.host, dnsProxy.port.toString())
            // tunnel?.resolver?.addSystemDNS(transport)
            Log.i(LOG_TAG_VPN, "set system dns: $sysDnsStr")
            // no need to send the dnsProxy.port for the below method, as it is not expecting port
            Intra.setSystemDNS(tunnel, sysDnsStr)
        } catch (e: Exception) { // this is not expected to happen
            Log.e(LOG_TAG_VPN, "set system dns: could not parse system dns", e)
            // remove the system dns, if it could not be set
            tunnel.resolver.remove(Backend.System)
        }
    }

    suspend fun updateLinkAndRoutes(tunFd: ParcelFileDescriptor, opts: TunnelOptions, proto: Long): Boolean {
        if (!tunnel.isConnected) {
            Log.e(LOG_TAG_VPN, "updateLink: tunFd is null, returning")
            return false
        }
        Log.i(LOG_TAG_VPN, "updateLink with fd(${tunFd.fd}) mtu: ${opts.mtu}")
        return try {
            tunnel.setLinkAndRoutes(tunFd.fd.toLong(), opts.mtu.toLong(), proto)
            true
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "err update tun: ${e.message}", e)
            false
        }
    }

    /**
     * Updates the DOH server URL for the VPN. If Go-DoH is enabled, DNS queries will be handled in
     * Go, and will not use the Java DoH implementation. If Go-DoH is not enabled, this method has
     * no effect.
     */
    suspend fun updateTun(tunnelOptions: TunnelOptions): Boolean {
        // changes made in connectTunnel()
        if (!tunnel.isConnected) {
            // Adapter is closed.
            Log.e(LOG_TAG_VPN, "updateTun: tunnel is not connected, returning")
            return false
        }

        Log.i(LOG_TAG_VPN, "received update tun with opts: $tunnelOptions")
        // ok to init again, as updateTun is called to handle edge cases
        initResolverProxiesPcap(tunnelOptions)
        return tunnel.isConnected
    }

    fun setDnsAlg() {
        // set translate to false for dns mode (regardless of setting in dns screen),
        // since apps cannot understand alg ips
        if (appConfig.getBraveMode().isDnsMode()) {
            Log.i(LOG_TAG_VPN, "dns mode, set translate to false")
            getRDNSResolver()?.translate(false)
            return
        }

        Log.i(LOG_TAG_VPN, "set dns alg: ${persistentState.enableDnsAlg}")
        getRDNSResolver()?.translate(persistentState.enableDnsAlg)
    }

    fun setRDNSStamp() {
        try {
            val rl = getRDNS(RethinkBlocklistManager.RethinkBlocklistType.LOCAL)
            if (rl != null) {
                Log.i(LOG_TAG_VPN, "set local stamp: ${persistentState.localBlocklistStamp}")
                rl.stamp = persistentState.localBlocklistStamp
            } else {
                Log.w(LOG_TAG_VPN, "mode is not local, this should not happen")
            }
        } catch (e: java.lang.Exception) {
            Log.e(LOG_TAG_VPN, "could not set local stamp: ${e.message}", e)
        } finally {
            resetLocalBlocklistStampFromTunnel()
        }
    }

    private fun resetLocalBlocklistStampFromTunnel() {
        try {
            val rl = getRDNS(RethinkBlocklistManager.RethinkBlocklistType.LOCAL)
            if (rl == null) {
                Log.i(LOG_TAG_VPN, "mode is not local, no need to reset local stamp")
                return
            }

            persistentState.localBlocklistStamp = rl.stamp // throws exception if stamp is invalid
            if (DEBUG)
                Log.d(LOG_TAG_VPN, "reset local stamp: ${persistentState.localBlocklistStamp}")
        } catch (e: Exception) {
            persistentState.localBlocklistStamp = ""
            Log.e(LOG_TAG_VPN, "could not reset local stamp: ${e.message}", e)
        }
    }

    private fun setRDNSLocal() {
        try {
            val stamp: String = persistentState.localBlocklistStamp
            val rdns = getRDNSResolver()
            Log.i(LOG_TAG_VPN, "local blocklist stamp: $stamp, rdns? ${rdns != null}")

            val path: String =
                Utilities.blocklistDownloadBasePath(
                    context,
                    Constants.LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME,
                    persistentState.localBlocklistTimestamp
                )
            rdns?.setRdnsLocal(
                path + Constants.ONDEVICE_BLOCKLIST_FILE_TD,
                path + Constants.ONDEVICE_BLOCKLIST_FILE_RD,
                path + Constants.ONDEVICE_BLOCKLIST_FILE_BASIC_CONFIG,
                path + ONDEVICE_BLOCKLIST_FILE_TAG
            )
            rdns?.rdnsLocal?.stamp = stamp
            Log.i(LOG_TAG_VPN, "local brave dns object is set with stamp: $stamp")
        } catch (ex: Exception) {
            // Set local blocklist enabled to false and reset the timestamp
            // if there is a failure creating bravedns
            persistentState.blocklistEnabled = false
            // Set local blocklist enabled to false and reset the timestamp to make sure
            // user is prompted to download blocklists again on the next try
            persistentState.localBlocklistTimestamp = Constants.INIT_TIME_MS
            Log.e(LOG_TAG_VPN, "could not set local-brave dns: ${ex.message}", ex)
        }
    }

    companion object {
        fun getIpString(context: Context?, url: String?): String {
            if (url.isNullOrEmpty()) return ""

            val res: Resources? = context?.resources
            val urls: Array<out String>? = res?.getStringArray(R.array.urls)
            val ips: Array<out String>? = res?.getStringArray(R.array.ips)
            if (urls == null) return ""
            for (i in urls.indices) {
                if (url.contains((urls[i]))) {
                    if (ips != null) return ips[i]
                }
            }
            return ""
        }

        fun setLogLevel(level: Long) {
            // 0 - verbose, 1 - debug, 2 - info, 3 - warn, 4 - error, 5 - fatal
            Intra.logLevel(level)
        }
    }

    fun syncP50Latency(id: String) {
        val tid: String = if (persistentState.enableDnsCache && !id.startsWith(Backend.CT)) {
            Backend.CT + id
        } else {
            id
        }
        try {
            val transport = getResolver()?.get(tid)
            val p50 = transport?.p50() ?: return
            persistentState.setMedianLatency(p50)
        } catch (e: Exception) {
            Log.w(LOG_TAG_VPN, "err getP50($tid): ${e.message}")
        }
    }

    private fun getResolver(): backend.DNSResolver? {
        try {
            if (!tunnel.isConnected) {
                Log.i(LOG_TAG_VPN, "Tunnel NOT connected, skip get resolver")
                return null
            }
            return tunnel.resolver
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "err get resolver: ${e.message}", e)
        }
        return null
    }

    private fun getRDNSResolver(): backend.DNSResolver? {
        try {
            if (!tunnel.isConnected) {
                Log.w(LOG_TAG_VPN, "Tunnel NOT connected, skip get resolver")
                return null
            }
            return tunnel.resolver
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "err get resolver: ${e.message}", e)
        }
        return null
    }

    private fun getProxies(): backend.Proxies? {
        try {
            if (!tunnel.isConnected) {
                Log.w(LOG_TAG_VPN, "Tunnel NOT connected, skip get proxies")
                return null
            }
            return tunnel.proxies
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "err get proxies: ${e.message}", e)
        }
        return null
    }

    fun canRouteIp(wgId: String, ip: String, default: Boolean): Boolean {
        return try {
            val router = tunnel.proxies.getProxy(wgId).router()
            val res = router.contains(ip)
            Log.i(LOG_TAG_VPN, "canRouteIp($wgId, $ip), res? $res")
            res
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "err canRouteIp($wgId, $ip): ${e.message}", e)
            default
        }
    }

    fun getSupportedIpVersion(proxyId: String): Pair<Boolean, Boolean> {
        try {
            val router = tunnel.proxies.getProxy(proxyId).router()
            val has4 = router.iP4()
            val has6 = router.iP6()
            Log.i(LOG_TAG_VPN, "supported ip version($proxyId): has4? $has4, has6? $has6")
            return Pair(has4, has6)
        } catch (e: Exception) {
            Log.w(LOG_TAG_VPN, "err supported ip version($proxyId): ${e.message}")
        }
        return Pair(false, false)
    }

    fun isSplitTunnelProxy(proxyId: String, pair: Pair<Boolean, Boolean>): Boolean {
        return try {
            val router = tunnel.proxies.getProxy(proxyId).router()
            // if the router contains 0.0.0.0, then it is not split tunnel for ipv4
            // if the router contains ::, then it is not split tunnel for ipv6
            val res: Boolean = if (pair.first && pair.second) {
                // if the pair is true, check for both ipv4 and ipv6
                !router.contains(UNSPECIFIED_IP_IPV4) || !router.contains(UNSPECIFIED_IP_IPV6)
            } else if (pair.first) {
                !router.contains(UNSPECIFIED_IP_IPV4)
            } else if (pair.second) {
                !router.contains(UNSPECIFIED_IP_IPV6)
            } else {
                false
            }

            Log.i(LOG_TAG_VPN, "split tunnel proxy($proxyId): ipv4? ${pair.first}, ipv6? ${pair.second}, res? $res")
            res
        } catch (e: Exception) {
            Log.w(LOG_TAG_VPN, "err isSplitTunnelProxy($proxyId): ${e.message}")
            false
        }
    }

    fun getActiveProxiesIpVersion(): BraveVPNService.OverlayNetworks {
        try {
            val router = tunnel.proxies.router()
            val has4 = router.iP4()
            val has6 = router.iP6()
            val failOpen = !router.iP4() && !router.iP6()
            Log.i(LOG_TAG_VPN, "proxy ip version, has4? $has4, has6? $has6, failOpen? $failOpen")
            return BraveVPNService.OverlayNetworks(has4, has6, failOpen)
        } catch (e: Exception) {
            Log.w(LOG_TAG_VPN, "err proxy ip version: ${e.message}")
        }
        return BraveVPNService.OverlayNetworks()
    }

    private fun ui(f: suspend () -> Unit) {
        externalScope.launch(Dispatchers.Main) { f() }
    }
}
