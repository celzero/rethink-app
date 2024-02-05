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
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.data.AppConfig.TunnelOptions
import com.celzero.bravedns.database.ProxyEndpoint
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
import com.celzero.bravedns.util.InternetProtocol
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_VPN
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.blocklistDir
import com.celzero.bravedns.util.Utilities.blocklistFile
import com.celzero.bravedns.util.Utilities.isValidPort
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.celzero.bravedns.wireguard.Config
import dnsx.Dnsx
import dnsx.RDNS
import dnsx.Resolver
import inet.ipaddr.HostName
import inet.ipaddr.IPAddressString
import intra.Intra
import intra.Tunnel
import ipn.Proxies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import tun2socks.Tun2socks
import java.net.URI

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
            Tun2socks.connect(
                tunFd.fd.toLong(),
                opts.mtu.toLong(),
                opts.preferredEngine.value(),
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
        addTransport()
        setDnsAlg()
        setRDNS()
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
                addDohTransport(Dnsx.BlockFree)
                addDohTransport(Dnsx.Preferred)
            }
            AppConfig.DnsType.DOT -> {
                addDotTransport(Dnsx.BlockFree)
                addDotTransport(Dnsx.Preferred)
            }
            AppConfig.DnsType.ODOH -> {
                addOdohTransport(Dnsx.BlockFree)
                addOdohTransport(Dnsx.Preferred)
            }
            AppConfig.DnsType.DNSCRYPT -> {
                addDnscryptTransport(Dnsx.BlockFree)
                addDnscryptTransport(Dnsx.Preferred)
            }
            AppConfig.DnsType.DNS_PROXY -> {
                addDnsProxyTransport(Dnsx.BlockFree)
                addDnsProxyTransport(Dnsx.Preferred)
            }
            AppConfig.DnsType.SYSTEM_DNS -> {
                addSystemDnsAsTransport(Dnsx.BlockFree)
                addSystemDnsAsTransport(Dnsx.Preferred)
            }
            AppConfig.DnsType.RETHINK_REMOTE -> {
                // only rethink has different stamp for block free transport
                // create a new transport for block free
                val rdnsRemoteUrl = appConfig.getRemoteRethinkEndpoint()?.url
                val blockfreeUrl = appConfig.getBlockFreeRethinkEndpoint()

                if (blockfreeUrl.isNotEmpty()) {
                    Log.i(LOG_TAG_VPN, "adding blockfree url: $blockfreeUrl")
                    addRdnsTransport(Dnsx.BlockFree, blockfreeUrl)
                } else {
                    Log.i(LOG_TAG_VPN, "no blockfree url found")
                    // if blockfree url is not available, do not proceed further
                    return
                }
                if (!rdnsRemoteUrl.isNullOrEmpty()) {
                    Log.i(LOG_TAG_VPN, "adding rdns remote url: $rdnsRemoteUrl")
                    addRdnsTransport(Dnsx.Preferred, rdnsRemoteUrl)
                } else {
                    Log.i(LOG_TAG_VPN, "no rdns remote url found")
                }
            }
        }
    }

    private suspend fun addDohTransport(id: String) {
        try {
            val doh = appConfig.getDOHDetails()
            var url = doh?.dohURL
            // change the url from https to http if the isSecure is false
            if (doh?.isSecure == false) {
                if (DEBUG) Log.d(LOG_TAG_VPN, "changing url from https to http for $url")
                url = url?.replace("https", "http")
            }
            val ips: String = getIpString(context, url)
            Intra.addDoHTransport(tunnel, id, url, ips)
            Log.i(LOG_TAG_VPN, "new doh: $id (${doh?.dohName}), url: $url, ips: $ips")
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "connect-tunnel: doh failure", e)
        }
    }

    private suspend fun addDotTransport(id: String) {
        try {
            val dot = appConfig.getDOTDetails()
            var url = dot?.url
            if (dot?.isSecure == true && url?.startsWith("tls") == false) {
                if (DEBUG) Log.d(LOG_TAG_VPN, "adding tls to url for $url")
                // add tls to the url if isSecure is true and the url does not start with tls
                url = "tls://$url"
            }
            val ips: String = getIpString(context, url)
            Intra.addDoTTransport(tunnel, id, url, ips)
            Log.i(LOG_TAG_VPN, "new dot: $id (${dot?.name}), url: $url, ips: $ips")
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "connect-tunnel: dot failure", e)
        }
    }

    private suspend fun addOdohTransport(id: String) {
        try {
            val odoh = appConfig.getODoHDetails()
            val proxy = odoh?.proxy
            val resolver = odoh?.resolver
            val proxyIps = ""
            Intra.addODoHTransport(tunnel, id, proxy, resolver, proxyIps)
            Log.i(LOG_TAG_VPN, "new odoh: $id (${odoh?.name}), p: $proxy, r: $resolver")
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "connect-tunnel: odoh failure", e)
        }
    }

    private suspend fun addDnscryptTransport(id: String) {
        try {
            val dc = appConfig.getConnectedDnscryptServer()
            val url = dc.dnsCryptURL
            Intra.addDNSCryptTransport(tunnel, id, url)
            Log.i(LOG_TAG_VPN, "new dnscrypt: $id (${dc.dnsCryptName}), url: $url")
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "connect-tunnel: dns crypt failure", e)
            showDnscryptConnectionFailureToast()
        }
        // setDnscryptRelaysIfAny() is expected to catch exceptions
        setDnscryptRelaysIfAny()
    }

    private suspend fun addDnsProxyTransport(id: String) {
        try {
            val dnsProxy = appConfig.getSelectedDnsProxyDetails() ?: return
            Intra.addDNSProxy(tunnel, id, dnsProxy.proxyIP, dnsProxy.proxyPort.toString())
            Log.i(
                LOG_TAG_VPN,
                "new dns proxy: $id(${dnsProxy.proxyName}), ip: ${dnsProxy.proxyIP}, port: ${dnsProxy.proxyPort}"
            )
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "connect-tunnel: dns proxy failure", e)
            showDnsProxyConnectionFailureToast()
        }
    }

    private suspend fun addSystemDnsAsTransport(id: String) {
        try {
            val dns = appConfig.getSystemDns()
            Intra.addDNSProxy(tunnel, id, dns.ipAddress, dns.port.toString())
            Log.i(LOG_TAG_VPN, "new system dns ip: ${dns.ipAddress}, port: ${dns.port}")
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "connect-tunnel: system dns failure", e)
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
        val s = url.split(RETHINKDNS_DOMAIN)[1]
        val stamp =
            if (s.startsWith("/")) {
                s.substring(1)
            } else {
                s
            }
        return if (url.contains(MAX_ENDPOINT)) {
            // if the stamp is empty or "dns-query", then remove it
            if (stamp.isEmpty() || stamp == default) return "$tls$MAX_ENDPOINT.$RETHINKDNS_DOMAIN"

            "$tls$stamp.$MAX_ENDPOINT.$RETHINKDNS_DOMAIN"
        } else {
            "$RETHINK_BASE_URL_SKY$stamp"
        }
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

    fun setRDNS() {
        // Set brave dns to tunnel - Local/Remote
        if (DEBUG) Log.d(LOG_TAG_VPN, "set brave dns to tunnel (local/remote)")

        // enable local blocklist if enabled
        io {
            if (persistentState.blocklistEnabled) {
                setRDNSLocal()
            } else {
                // remove local blocklist, if any
                getResolver()?.setRdnsLocal(null, null, null, null)
            }

            // always set the remote blocklist
            setRDNSRemote()
        }
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
                getResolver()?.setRdnsRemote(remoteFile.absolutePath)
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

            Log.i(LOG_TAG_VPN, "new dnscrypt: $it")
            try {
                Intra.addDNSCryptRelay(tunnel, it)
            } catch (ex: Exception) {
                Log.e(LOG_TAG_VPN, "connect-tunnel: dnscrypt failure", ex)
                appConfig.removeDnscryptRelay(it)
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

    suspend fun setWireguardTunnelModeIfNeeded(tunProxyMode: AppConfig.TunProxyMode): Boolean {
        if (!tunProxyMode.isTunProxyWireguard()) return false

        val wgConfigs: List<Config> = WireguardManager.getEnabledConfigs()
        if (wgConfigs.isEmpty()) {
            Log.i(LOG_TAG_VPN, "no active wireguard configs found")
            return false
        }
        wgConfigs.forEach {
            val wgUserSpaceString = it.toWgUserspaceString()
            val id = ID_WG_BASE + it.getId()
            val isDnsNeeded = WireguardManager.getOneWireGuardProxyId() == it.getId()
            try {
                if (DEBUG) Log.d(LOG_TAG_VPN, "add wireguard proxy with id: $id")

                getProxies()?.addProxy(id, wgUserSpaceString)
                if (isDnsNeeded) {
                    setWireGuardDns(id)
                }
            } catch (e: Exception) {
                WireguardManager.disableConfig(id)
                showWireguardFailureToast(
                    e.message ?: context.getString(R.string.wireguard_connection_error)
                )
                Log.e(LOG_TAG_VPN, "connect-tunnel: could not start wireguard", e)
                return false
            }
        }
        return true
    }

    private fun setWireGuardDns(id: String) {
        try {
            val p = getProxies()?.getProxy(id)
            if (p == null) {
                Log.w(LOG_TAG_VPN, "wireguard proxy not found for id: $id")
                return
            }
            Intra.addProxyDNS(tunnel, p)
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "connect-tunnel: dns crypt failure", e)
        }
    }

    suspend fun setCustomProxy(tunProxyMode: AppConfig.TunProxyMode) {
        setSocks5TunnelModeIfNeeded(tunProxyMode)
        setHttpProxyIfNeeded(tunProxyMode)
    }

    private fun setSocks5TunnelModeIfNeeded(tunProxyMode: AppConfig.TunProxyMode) {
        if (!tunProxyMode.isTunProxySocks5() && !tunProxyMode.isTunProxyOrbot()) return

        io {
            val socks5: ProxyEndpoint? =
                if (
                    tunProxyMode.isTunProxyOrbot() &&
                        AppConfig.ProxyType.of(persistentState.proxyType).isSocks5Enabled()
                ) {
                    appConfig.getConnectedOrbotProxy()
                } else {
                    appConfig.getSocks5ProxyDetails()
                }
            if (socks5 == null) {
                Log.w(LOG_TAG_VPN, "could not fetch socks5 details for proxyMode: $tunProxyMode")
                return@io
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

    fun getProxyById(id: String): ipn.Proxy? {
        return try {
            getProxies()?.getProxy(id)
        } catch (ignored: Exception) {
            Log.i(LOG_TAG_VPN, "err getProxy($id) ignored: ${ignored.message}")
            null
        }
    }

    private suspend fun setHttpProxyIfNeeded(tunProxyMode: AppConfig.TunProxyMode) {
        if (!AppConfig.ProxyType.of(appConfig.getProxyType()).isProxyTypeHasHttp()) return

        val endpoint = appConfig.getHttpProxyDetails()
        try {
            val id =
                if (tunProxyMode.isTunProxyOrbot()) {
                    ProxyManager.ID_ORBOT_BASE
                } else {
                    ProxyManager.ID_HTTP_BASE
                }
            val httpProxyUrl = endpoint?.proxyIP ?: return

            getProxies()?.addProxy(id, httpProxyUrl)
            Log.i(LOG_TAG_VPN, "Http mode set with url: $httpProxyUrl")
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
            val proxyId: Int = id.substring(ID_WG_BASE.length).toInt()
            val wgConfig = WireguardManager.getConfigById(proxyId)
            val wgUserSpaceString = wgConfig?.toWgUserspaceString()
            getProxies()?.addProxy(id, wgUserSpaceString)
            Log.i(LOG_TAG_VPN, "add wireguard proxy with id: $id")
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "error adding wireguard proxy: ${e.message}", e)
            WireguardManager.disableConfig(id)
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
            return getResolver()?.get(id)?.status()
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "err getDnsStatus($id): ${e.message}", e)
        }
        return null
    }

    fun getRDNS(type: RethinkBlocklistManager.RethinkBlocklistType): RDNS? {
        return if (type.isLocal()) {
            getResolver()?.rdnsLocal
        } else {
            getResolver()?.rdnsRemote
        }
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
        val defaultDns = "8.8.4.4:53"
        try {
            // when the url is empty, set the default transport to 8.8.4.4:53
            if (url.isEmpty()) {
                Log.i(LOG_TAG_VPN, "set default transport to 8.8.4.4, as url is empty")
                return Intra.newDefaultDNS(Dnsx.DNS53, defaultDns, "")
            }
            val ips: String = getIpString(context, url)
            if (DEBUG) Log.d(LOG_TAG_VPN, "default transport url: $url ips: $ips")
            if (url.contains("http")) {
                return Intra.newDefaultDNS(Dnsx.DOH, url, ips)
            }
            // no need to set ips for dns53
            return Intra.newDefaultDNS(Dnsx.DNS53, url, "")
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "err new default transport: ${e.message}", e)
            // most of the android devices have google dns, so add it as default transport
            // TODO: notify the user that the default transport could not be set
            return Intra.newDefaultDNS(Dnsx.DNS53, defaultDns, "")
        }
    }

    fun addDefaultTransport(url: String?) {
        val defaultDns = "8.8.4.4:53"
        try {
            if (!tunnel.isConnected) {
                Log.i(LOG_TAG_VPN, "Tunnel not connected, skip new default transport")
                return
            }
            // when the url is empty, set the default transport to 8.8.4.4:53
            if (url.isNullOrEmpty()) {
                Log.i(LOG_TAG_VPN, "set default transport to 8.8.4.4, as url is empty")
                Intra.addDefaultTransport(tunnel, Dnsx.DNS53, defaultDns, "")
                return
            }

            val ips: String = getIpString(context, url)
            if (DEBUG) Log.d(LOG_TAG_VPN, "default transport url: $url ips: $ips")
            if (url.contains("http")) {
                Intra.addDefaultTransport(tunnel, Dnsx.DOH, url, ips)
            } else {
                Intra.addDefaultTransport(tunnel, Dnsx.DNS53, url, "")
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "err new default transport: ${e.message}", e)
            // most of the android devices have google dns, so add it as default transport
            // TODO: notify the user that the default transport could not be set
            Intra.addDefaultTransport(tunnel, Dnsx.DNS53, defaultDns, "")
        }
    }

    suspend fun setSystemDns() {
        val defaultDns = "8.8.4.4:53"
        if (!tunnel.isConnected) {
            Log.i(LOG_TAG_VPN, "Tunnel NOT connected, skip setting system-dns")
        }
        val systemDns = appConfig.getSystemDns()
        var dnsProxy: HostName? = null
        try {
            // TODO: system dns may be non existent; see: AppConfig#updateSystemDnsServers
            dnsProxy = HostName(IPAddressString(systemDns.ipAddress).address, systemDns.port)
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "set system dns: could not parse system dns", e)
        }

        if (dnsProxy == null || dnsProxy.host.isNullOrEmpty() || !isValidPort(dnsProxy.port)) {
            Log.e(LOG_TAG_VPN, "setSystemDns: unset dns proxy: $dnsProxy")
            // if system dns is empty, then remove the existing system dns from the tunnel
            // by setting it to empty string
            Intra.setSystemDNS(tunnel, defaultDns)
            return
        }

        // below code is commented out, add the code to set the system dns via resolver
        // val transport = Intra.newDNSProxy("ID", dnsProxy.host, dnsProxy.port.toString())
        // tunnel?.resolver?.addSystemDNS(transport)
        Log.d(LOG_TAG_VPN, "set system dns: ${dnsProxy.host}")
        // no need to send the dnsProxy.port for the below method, as it is not expecting port
        Intra.setSystemDNS(tunnel, dnsProxy.host)

        // add appropriate transports to the tunnel, if system dns is enabled
        if (appConfig.isSystemDns()) {
            addTransport()
        }
    }

    suspend fun updateLink(tunFd: ParcelFileDescriptor, opts: TunnelOptions): Boolean {
        if (!tunnel.isConnected) {
            Log.e(LOG_TAG_VPN, "updateLink: tunFd is null, returning")
            return false
        }
        Log.i(LOG_TAG_VPN, "updateLink with fd(${tunFd.fd}) opts: $opts")
        return try {
            tunnel.setLink(tunFd.fd.toLong(), opts.mtu.toLong())
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
            getResolver()?.gateway()?.translate(false)
            return
        }

        Log.i(LOG_TAG_VPN, "set dns alg: ${persistentState.enableDnsAlg}")
        getResolver()?.gateway()?.translate(persistentState.enableDnsAlg)
    }

    fun setRDNSStamp() {
        try {
            if (getResolver()?.rdnsLocal != null) {
                Log.i(LOG_TAG_VPN, "set local stamp: ${persistentState.localBlocklistStamp}")
                getResolver()?.rdnsLocal?.stamp = persistentState.localBlocklistStamp
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
            if (getResolver()?.rdnsLocal == null) {
                Log.i(LOG_TAG_VPN, "mode is not local, no need to reset local stamp")
                return
            }

            persistentState.localBlocklistStamp = getResolver()?.rdnsLocal?.stamp ?: ""
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
            Log.i(LOG_TAG_VPN, "local blocklist stamp: $stamp")

            val path: String =
                Utilities.blocklistDownloadBasePath(
                    context,
                    Constants.LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME,
                    persistentState.localBlocklistTimestamp
                )
            getResolver()
                ?.setRdnsLocal(
                    path + Constants.ONDEVICE_BLOCKLIST_FILE_TD,
                    path + Constants.ONDEVICE_BLOCKLIST_FILE_RD,
                    path + Constants.ONDEVICE_BLOCKLIST_FILE_BASIC_CONFIG,
                    path + ONDEVICE_BLOCKLIST_FILE_TAG
                )
            getResolver()?.rdnsLocal?.stamp = stamp
            Log.i(LOG_TAG_VPN, "local brave dns object is set")
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
            Tun2socks.logLevel(level)
        }
    }

    fun syncP50Latency(id: String) {
        try {
            val tid =
                if (persistentState.enableDnsCache && !id.startsWith(Dnsx.CT)) {
                    Dnsx.CT + id
                } else {
                    id
                }
            val transport = getResolver()?.get(tid)
            val p50 = transport?.p50() ?: return
            persistentState.setMedianLatency(p50)
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "err getP50: ${e.message}", e)
        }
    }

    private fun getResolver(): Resolver? {
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

    private fun getProxies(): Proxies? {
        try {
            if (!tunnel.isConnected) {
                Log.i(LOG_TAG_VPN, "Tunnel NOT connected, skip get proxies")
                return null
            }
            return tunnel.proxies
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "err get proxies: ${e.message}", e)
        }
        return null
    }

    private fun io(f: suspend () -> Unit) {
        externalScope.launch { withContext(Dispatchers.IO) { f() } }
    }

    private fun ui(f: suspend () -> Unit) {
        externalScope.launch { withContext(Dispatchers.Main) { f() } }
    }
}
