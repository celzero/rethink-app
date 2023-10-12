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
import com.celzero.bravedns.service.TcpProxyHelper
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.util.Constants.Companion.ONDEVICE_BLOCKLIST_FILE_TAG
import com.celzero.bravedns.util.Constants.Companion.REMOTE_BLOCKLIST_DOWNLOAD_FOLDER_NAME
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_VPN
import com.celzero.bravedns.util.Utilities.blocklistDir
import com.celzero.bravedns.util.Utilities.blocklistFile
import com.celzero.bravedns.util.Utilities.isValidPort
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.celzero.bravedns.wireguard.Config
import dnsx.BraveDNS
import dnsx.Dnsx
import dnsx.Transport
import inet.ipaddr.HostName
import inet.ipaddr.IPAddressString
import intra.Intra
import intra.Tunnel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import tun2socks.Tun2socks
import java.io.IOException
import java.net.URI

/**
 * This is a VpnAdapter that captures all traffic and routes it through a go-tun2socks instance with
 * custom logic for Intra.
 */
class GoVpnAdapter(
    private val context: Context,
    private val externalScope: CoroutineScope,
    private var tunFd: ParcelFileDescriptor?
) : KoinComponent {

    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()

    // The Intra session object from go-tun2socks.  Initially null.
    private var tunnel: Tunnel? = null

    // protected by VpnController.mutex
    suspend fun startLocked(tunnelOptions: TunnelOptions): Boolean {
        connectTunnelLocked(tunnelOptions)
        return tunnel != null
    }

    // protected by VpnController.mutex
    private suspend fun connectTunnelLocked(tunnelOptions: TunnelOptions) {
        if (tunnel != null) {
            return
        }

        try {
            // 0 - verbose, 1 - debug, 2 - info, 3 - warn, 4 - error, 5 - fatal
            Tun2socks.logLevel(persistentState.goLoggerLevel)

            // TODO : #321 As of now the app fallback on an unmaintained url. Requires a rewrite as
            // part of v055
            val dohURL: String = getDefaultDohUrl()

            val transport: Transport = makeDefaultTransport(dohURL)
            Log.i(
                LOG_TAG_VPN,
                "Connect tunnel with url $tunFd dnsMode: ${tunnelOptions.tunDnsMode}, blockMode: ${tunnelOptions.tunFirewallMode}, proxyMode: ${tunnelOptions.tunProxyMode}, fake dns: ${tunnelOptions.fakeDns}, mtu:${tunnelOptions.mtu}, pcap: ${tunnelOptions.pcapFilePath}, preferredEngine: ${tunnelOptions.preferredEngine.getPreferredEngine()}"
            )

            if (tunFd == null) return

            tunnel =
                Tun2socks.connectIntraTunnel(
                    tunFd!!.fd.toLong(),
                    tunnelOptions.pcapFilePath, // fd for pcap logging
                    tunnelOptions.mtu.toLong(),
                    tunnelOptions.preferredEngine.getPreferredEngine(),
                    tunnelOptions.fakeDns,
                    transport,
                    tunnelOptions.blocker,
                    tunnelOptions.listener
                )

            setTunnelMode(tunnelOptions)
            addTransport()
            setDnsAlgLocked()
            setBraveDnsBlocklistMode()
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, e.message, e)
            tunnel?.disconnect()
            tunnel = null
        }
    }

    private suspend fun addTransport() {
        var transport: Transport? = null
        // TODO: #321 As of now there is no block free transport for dns types other than Rethink.
        // introduce block free transport for other dns types when there is a UI to configure the
        // block free transport
        var blockFreeTransport: Transport? = null

        when (appConfig.getDnsType()) {
            AppConfig.DnsType.DOH -> {
                transport = createDohTransport(Dnsx.Preferred)
                blockFreeTransport = createDohTransport(Dnsx.BlockFree)
            }
            AppConfig.DnsType.DNSCRYPT -> {
                transport = createDNSCryptTransport(Dnsx.Preferred)
                blockFreeTransport = createDNSCryptTransport(Dnsx.BlockFree)
            }
            AppConfig.DnsType.DNS_PROXY -> {
                transport = createDnsProxyTransport(Dnsx.Preferred)
                blockFreeTransport = createDnsProxyTransport(Dnsx.BlockFree)
            }
            AppConfig.DnsType.NETWORK_DNS -> {
                transport = createNetworkDnsTransport(Dnsx.Preferred)
                blockFreeTransport = createNetworkDnsTransport(Dnsx.BlockFree)
            }
            AppConfig.DnsType.RETHINK_REMOTE -> {
                transport = createRethinkDnsTransport()
                // only rethink has different stamp for block free transport
                // create a new transport for block free
                blockFreeTransport = createBlockFreeTransport()
            }
        }

        // always set the block free transport before setting the transport to the resolver
        // because of the way the alg is implemented in the go code.
        if (blockFreeTransport != null) {
            val added = tunnel?.resolver?.add(blockFreeTransport)
            Log.i(LOG_TAG_VPN, "add blockfree transport, addr: ${blockFreeTransport.addr}, $added")
        } else {
            // remove the block free transport from the resolver
            val removed = tunnel?.resolver?.remove(Dnsx.BlockFree)
            Log.i(
                LOG_TAG_VPN,
                "null blockfree transport for: ${appConfig.getDnsType()}, removed? $removed"
            )
        }

        if (transport != null) {
            val added = tunnel?.resolver?.add(transport)
            Log.i(
                LOG_TAG_VPN,
                "add transport, id: ${transport.id()} addr:  ${transport.addr}, $added"
            )
        } else {
            val removed = tunnel?.resolver?.remove(Dnsx.Preferred)
            Log.e(
                LOG_TAG_VPN,
                "null preferred transport for: ${appConfig.getDnsType()}, removed? $removed"
            )
        }
    }

    private suspend fun createDohTransport(id: String): Transport? {
        val doh = appConfig.getDOHDetails()
        var url = doh?.dohURL
        // change the url from https to http if the isSecure is false
        if (doh?.isSecure == false) {
            if (DEBUG) Log.d(LOG_TAG_VPN, "changing url from https to http for $url")
            url = url?.replace("https", "http")
        }
        val transport = Intra.newDoHTransport(id, url, "")
        Log.i(
            LOG_TAG_VPN,
            "create doh transport with id: $id (${doh?.dohName}), url: $url, transport: $transport"
        )
        return transport
    }

    private suspend fun createDNSCryptTransport(id: String): Transport? {
        try {
            val dnscrypt = appConfig.getConnectedDnscryptServer()
            val url = dnscrypt.dnsCryptURL
            val resolver = tunnel?.resolver
            val transport = Intra.newDNSCryptTransport(resolver, id, url)
            Log.i(
                LOG_TAG_VPN,
                "create dnscrypt transport with id: $id, (${dnscrypt.dnsCryptName}), url: $url, transport: $transport"
            )
            setDnscryptResolversIfAny()
            return transport
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "connect-tunnel: dns crypt failure", e)
            showDnscryptConnectionFailureToast()
            return null
        }
    }

    private suspend fun createDnsProxyTransport(id: String): Transport? {
        try {
            val dnsProxy = appConfig.getSelectedDnsProxyDetails() ?: return null
            val transport = Intra.newDNSProxy(id, dnsProxy.proxyIP, dnsProxy.proxyPort.toString())
            Log.i(
                LOG_TAG_VPN,
                "create dns proxy transport with id: $id(${dnsProxy.proxyName}), ip: ${dnsProxy.proxyIP}, port: ${dnsProxy.proxyPort}"
            )
            return transport
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "connect-tunnel: dns proxy failure", e)
            showDnsProxyConnectionFailureToast()
            return null
        }
    }

    private fun createNetworkDnsTransport(id: String): Transport? {
        return try {
            val systemDns = appConfig.getSystemDns()
            val transport = Intra.newDNSProxy(id, systemDns.ipAddress, systemDns.port.toString())
            Log.i(
                LOG_TAG_VPN,
                "create network dnsproxy transport with id: $id, url: ${systemDns.ipAddress}/${systemDns.port}, transport: $transport"
            )
            transport
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "connect-tunnel: dns proxy failure", e)
            null
        }
    }

    private suspend fun createRethinkDnsTransport(): Transport? {
        return try {
            val rethinkDns = appConfig.getRemoteRethinkEndpoint()
            val url = rethinkDns?.url
            val ips: String = getIpString(context, url)
            val transport = Intra.newDoHTransport(Dnsx.Preferred, url, ips)
            Log.i(
                LOG_TAG_VPN,
                "create doh transport with id: ${Dnsx.Preferred}(${rethinkDns?.name}), url: $url, transport: $transport, ips: $ips"
            )
            transport
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "connect-tunnel: rethinkdns creation failure", e)
            null
        }
    }

    private suspend fun createBlockFreeTransport(): Transport? {
        return try {
            val url = appConfig.getBlockFreeRethinkEndpoint()
            val ips: String = getIpString(context, url)
            val transport = Intra.newDoHTransport(Dnsx.BlockFree, url, ips)
            Log.i(
                LOG_TAG_VPN,
                "create doh transport with id: ${Dnsx.BlockFree}, url: $url, transport: $transport, ips: $ips"
            )
            transport
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "connect-tunnel: rethinkdns creation failure", e)
            null
        }
    }

    private suspend fun setTunnelMode(tunnelOptions: TunnelOptions) {
        tunnel?.setTunMode(
            tunnelOptions.tunDnsMode.mode,
            tunnelOptions.tunFirewallMode.mode,
            tunnelOptions.ptMode.id
        )
        // setTcpProxyIfNeeded()
        setWireguardTunnelModeIfNeeded(tunnelOptions.tunProxyMode)
        setSocks5TunnelModeIfNeeded(tunnelOptions.tunProxyMode)
        setHttpProxyIfNeeded(tunnelOptions.tunProxyMode)
    }

    private fun setBraveDnsBlocklistMode() {
        if (DEBUG) Log.d(LOG_TAG_VPN, "init bravedns mode")

        // enable local blocklist if enabled
        io {
            if (persistentState.blocklistEnabled) {
                setBraveDNSLocalMode()
            } else {
                // remove local blocklist, if any
                tunnel?.resolver?.rdnsLocal = null
            }

            // always set the remote blocklist
            setBraveDNSRemoteMode()
        }
    }

    private fun setBraveDNSRemoteMode() {
        if (DEBUG) Log.d(LOG_TAG_VPN, "init remote bravedns mode")
        try {
            val remoteDir =
                blocklistDir(
                    context,
                    REMOTE_BLOCKLIST_DOWNLOAD_FOLDER_NAME,
                    persistentState.remoteBlocklistTimestamp
                )
                    ?: return
            val remoteFile =
                blocklistFile(remoteDir.absolutePath, ONDEVICE_BLOCKLIST_FILE_TAG) ?: return
            if (remoteFile.exists()) {
                tunnel?.resolver?.rdnsRemote = Dnsx.newBraveDNSRemote(remoteFile.absolutePath)
                Log.i(LOG_TAG_VPN, "remote-bravedns enabled")
            } else {
                Log.w(LOG_TAG_VPN, "filetag.json for remote-bravedns missing")
            }
        } catch (ex: Exception) {
            Log.e(LOG_TAG_VPN, "cannot set remote-bravedns: ${ex.message}", ex)
        }
    }

    private suspend fun setDnscryptResolversIfAny() {
        try {
            val routes: String = appConfig.getDnscryptRelayServers()
            routes.split(",").forEach {
                if (it.isBlank()) return@forEach

                Log.i(LOG_TAG_VPN, "create new dns crypt route: $it")
                val transport = Intra.newDNSCryptRelay(tunnel?.resolver, it)
                if (transport == null) {
                    Log.e(LOG_TAG_VPN, "cannot create dns crypt route: $it")
                    appConfig.removeDnscryptRelay(it)
                } else {
                    if (DEBUG) Log.d(LOG_TAG_VPN, "adding dns crypt route: $it")
                    tunnel?.resolver?.add(transport)
                }
            }
        } catch (ex: Exception) {
            Log.e(LOG_TAG_VPN, "connect-tunnel: dns crypt failure", ex)
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
            val result = tunnel?.proxies?.addProxy(id, url)
            Log.i(LOG_TAG_VPN, "Proxy mode set with tunnel url($id): $url, result: $result")
        } catch (e: Exception) {
            Log.e(
                LOG_TAG_VPN,
                "connect-tunnel: could not start proxy $userName@$ipAddress:$port",
                e
            )
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
                Toast.LENGTH_SHORT
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

    private fun setWireguardTunnelModeIfNeeded(tunProxyMode: AppConfig.TunProxyMode) {
        if (!tunProxyMode.isTunProxyWireguard()) return

        val wgConfigs: List<Config> = WireguardManager.getActiveConfigs()
        if (wgConfigs.isEmpty()) {
            Log.i(LOG_TAG_VPN, "no active wireguard configs found")
            return
        }
        wgConfigs.forEach {
            val wgUserSpaceString = it.toWgUserspaceString()
            val id = ID_WG_BASE + it.getId()
            try {
                tunnel?.proxies?.addProxy(id, wgUserSpaceString)
            } catch (e: Exception) {
                WireguardManager.disableConfig(id)
                showWireguardFailureToast(
                    e.message ?: context.getString(R.string.wireguard_connection_error)
                )
                Log.e(LOG_TAG_VPN, "connect-tunnel: could not start wireguard", e)
            }
        }
    }

    private suspend fun setSocks5TunnelModeIfNeeded(tunProxyMode: AppConfig.TunProxyMode) {
        if (!tunProxyMode.isTunProxySocks5() && !tunProxyMode.isTunProxyOrbot()) return

        val socks5: ProxyEndpoint? =
            if (
                tunProxyMode.isTunProxyOrbot() &&
                    AppConfig.ProxyType.of(persistentState.proxyType).isSocks5Enabled()
            ) {
                appConfig.getOrbotProxyDetails()
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
            val status = tunnel?.proxies?.getProxy(id)?.status()
            if (DEBUG) Log.d(LOG_TAG_VPN, "getProxyStatusById: $id, $status")
            status
        } catch (ignored: Exception) {
            Log.e(LOG_TAG_VPN, "err getProxy($id): ${ignored.message}", ignored)
            null
        }
    }

    private fun setHttpProxyIfNeeded(tunProxyMode: AppConfig.TunProxyMode) {
        if (!AppConfig.ProxyType.of(appConfig.getProxyType()).isProxyTypeHasHttp()) return

        try {
            val id =
                if (tunProxyMode.isTunProxyOrbot()) {
                    ProxyManager.ID_ORBOT_BASE
                } else {
                    ProxyManager.ID_HTTP_BASE
                }
            val httpProxyUrl = persistentState.httpProxyHostAddress
            tunnel?.proxies?.addProxy(id, httpProxyUrl)
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

    fun removeWgProxyLocked(id: String) {
        try {
            tunnel?.proxies?.removeProxy(id)
            Log.i(LOG_TAG_VPN, "remove wireguard proxy with id: $id")
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "error removing wireguard proxy: ${e.message}", e)
        }
    }

    fun addWgProxyLocked(id: String) {
        try {
            val proxyId: Int = id.substring(ID_WG_BASE.length).toInt()
            val wgConfig = WireguardManager.getConfigById(proxyId)
            val wgUserSpaceString = wgConfig?.toWgUserspaceString()
            tunnel?.proxies?.addProxy(id, wgUserSpaceString)
            Log.i(LOG_TAG_VPN, "add wireguard proxy with id: $id")
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "error adding wireguard proxy: ${e.message}", e)
            WireguardManager.disableConfig(id)
            showWireguardFailureToast(
                e.message ?: context.getString(R.string.wireguard_connection_error)
            )
        }
    }

    fun refreshProxiesLocked() {
        try {
            val res = tunnel?.proxies?.refreshProxies()
            Log.i(LOG_TAG_VPN, "refresh proxies: $res")
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "error refreshing proxies: ${e.message}", e)
        }
    }

    // v055, unused
    private fun setTcpProxyIfNeeded() {
        if (!appConfig.isTcpProxyEnabled()) {
            Log.i(LOG_TAG_VPN, "tcp proxy not enabled")
            return
        }

        val tcpProxyUrl = TcpProxyHelper.getActiveTcpProxy()
        if (tcpProxyUrl == null) {
            Log.w(LOG_TAG_VPN, "could not fetch tcp proxy details")
            return
        }

        val ips = getIpString(context, "https://sky.rethinkdns.com/")
        Log.d(LOG_TAG_VPN, "ips: $ips")
        // svc.rethinkdns.com / duplex.deno.dev
        val added =
            tunnel
                ?.proxies
                ?.addProxy(ProxyManager.ID_TCP_BASE, "pipws://proxy.nile.workers.dev/ws/nosig")
        if (DEBUG)
            Log.d(
                LOG_TAG_VPN,
                "Tcp mode set(${ProxyManager.ID_TCP_BASE}): ${tcpProxyUrl.url}, res: $added"
            )
        val secWarp = WireguardManager.getSecWarpConfig()
        if (secWarp == null) {
            Log.w(LOG_TAG_VPN, "no sec warp config found")
            return
        }
        val wgUserSpaceString = secWarp.toWgUserspaceString()
        val added2 = tunnel?.proxies?.addProxy(ID_WG_BASE + secWarp.getId(), wgUserSpaceString)
        if (DEBUG)
            Log.d(
                LOG_TAG_VPN,
                "Tcp mode(wireguard) set(${ID_WG_BASE+ secWarp.getId()}): ${secWarp.getName()}, res: $added2"
            )
    }

    fun hasTunnel(): Boolean {
        return (tunnel != null)
    }

    // protected by VpnController.mutex
    fun refreshLocked() {
        if (tunnel != null) {
            tunnel?.resolver?.refresh()
        }
    }

    // protected by VpnController.mutex
    fun closeLocked() {
        if (tunnel != null) {
            tunnel?.disconnect()
            Log.i(LOG_TAG_VPN, "Tunnel disconnect")
        } else {
            Log.i(LOG_TAG_VPN, "Tunnel already disconnected")
        }

        try {
            tunFd?.close()
        } catch (e: IOException) {
            Log.e(LOG_TAG_VPN, e.message, e)
        }
        tunFd = null
        tunnel = null
    }

    @Throws(Exception::class)
    private fun makeDefaultTransport(url: String?): Transport {
        // when the user has selected none as the dns mode, we use the grounded transport
        if (url.isNullOrEmpty()) {
            Log.i(LOG_TAG_VPN, "using grounded transport as default dns is set to none")
            return Intra.newGroundedTransport(Dnsx.Default)
        }

        val dohIPs: String = getIpString(context, url)
        return Intra.newDoHTransport(Dnsx.Default, url, dohIPs)
    }

    // protected by VpnController.mutex
    fun setSystemDnsLocked() {
        if (tunnel != null) {
            val systemDns = appConfig.getSystemDns()
            var dnsProxy: HostName? = null
            try {
                // TODO: system dns may be non existent; see: AppConfig#updateSystemDnsServers
                dnsProxy = HostName(IPAddressString(systemDns.ipAddress).address, systemDns.port)
            } catch (e: Exception) {
                Log.e(LOG_TAG_VPN, "setSystemDns: could not parse system dns", e)
            }

            if (dnsProxy == null || dnsProxy.host.isNullOrEmpty() || !isValidPort(dnsProxy.port)) {
                Log.e(LOG_TAG_VPN, "setSystemDns: unset dnsProxy: $dnsProxy")
                // if system dns is empty, then remove the existing system dns from the tunnel
                // by setting it to empty string
                tunnel?.setSystemDNS("")
                return
            }

            // below code is commented out, add the code to set the system dns via resolver
            // val transport = Intra.newDNSProxy("ID", dnsProxy.host, dnsProxy.port.toString())
            // tunnel?.resolver?.addSystemDNS(transport)
            tunnel?.setSystemDNS(dnsProxy.toNormalizedString())
        } else {
            Log.e(LOG_TAG_VPN, "setSystemDns: tunnel is null")
        }
    }

    /**
     * Updates the DOH server URL for the VPN. If Go-DoH is enabled, DNS queries will be handled in
     * Go, and will not use the Java DoH implementation. If Go-DoH is not enabled, this method has
     * no effect.
     *
     * protected by VpnController.mutex
     */
    suspend fun updateTunLocked(tunnelOptions: TunnelOptions): Boolean {
        // changes made in connectTunnel()
        if (tunFd == null) {
            // Adapter is closed.
            Log.e(LOG_TAG_VPN, "updateTun: tunFd is null, returning")
            return false
        }

        if (tunnel == null) {
            // Attempt to re-create the tunnel.  Creation may have failed originally because the DoH
            // server could not be reached.  This will update the DoH URL as well.
            Log.w(LOG_TAG_VPN, "updateTun: tunnel is null, calling connectTunnel")
            return startLocked(tunnelOptions)
        }
        Log.i(LOG_TAG_VPN, "received update tun with opts: $tunnelOptions")
        try {
            setTunnelMode(tunnelOptions)
            // add transport to resolver, no need to set default transport on updateTunnel
            addTransport()
            setDnsAlgLocked()
            // Set brave dns to tunnel - Local/Remote
            setBraveDnsBlocklistMode()
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, e.message, e)
            tunnel?.disconnect()
            tunnel = null
        }
        return tunnel != null
    }

    // protected by VpnController.mutex
    fun setDnsAlgLocked() {
        // set translate to false for dns mode (regardless of setting in dns screen),
        // since apps cannot understand alg ips
        if (appConfig.getBraveMode().isDnsMode()) {
            Log.i(LOG_TAG_VPN, "dns mode, set translate to false")
            tunnel?.resolver?.gateway()?.translate(false)
            return
        }

        Log.i(LOG_TAG_VPN, "set dns alg: ${persistentState.enableDnsAlg}")
        tunnel?.resolver?.gateway()?.translate(persistentState.enableDnsAlg)
    }

    private fun getDefaultDohUrl(): String {
        return persistentState.defaultDnsUrl
    }

    private fun setBraveDNSLocalMode() {
        try {
            val stamp: String = persistentState.localBlocklistStamp
            Log.i(LOG_TAG_VPN, "local blocklist stamp: $stamp")
            // no need to set braveDNS to tunnel when stamp is empty
            if (stamp.isEmpty()) {
                return
            }

            val braveDNS = makeLocalBraveDns()
            if (braveDNS != null) {
                tunnel?.resolver?.rdnsLocal = braveDNS
                tunnel?.resolver?.rdnsLocal?.stamp = stamp
                Log.i(LOG_TAG_VPN, "local brave dns object is set")
            } else {
                Log.e(LOG_TAG_VPN, "Issue creating local brave dns object")
            }
        } catch (ex: Exception) {
            persistentState.blocklistEnabled = false
            Log.e(LOG_TAG_VPN, "could not set local-brave dns: ${ex.message}", ex)
        }
    }

    // protected by VpnController.mutex
    fun setBraveDnsStampLocked() {
        try {
            if (tunnel == null) {
                Log.e(LOG_TAG_VPN, "tunnel is null, not setting brave dns stamp")
                return
            }

            if (tunnel?.resolver?.rdnsLocal != null) {
                Log.i(LOG_TAG_VPN, "set local stamp: ${persistentState.localBlocklistStamp}")
                tunnel?.resolver?.rdnsLocal?.stamp = persistentState.localBlocklistStamp
            } else {
                Log.w(LOG_TAG_VPN, "mode is not local, this should not happen")
            }
        } catch (e: java.lang.Exception) {
            Log.e(LOG_TAG_VPN, "could not set local stamp: ${e.message}", e)
        } finally {
            resetLocalBlocklistFromTunnel()
        }
    }

    private fun resetLocalBlocklistFromTunnel() {
        try {
            if (tunnel?.resolver?.rdnsLocal == null) {
                Log.i(LOG_TAG_VPN, "mode is not local, no need to reset local stamp")
                return
            }

            persistentState.localBlocklistStamp = tunnel?.resolver?.rdnsLocal?.stamp ?: ""
            if (DEBUG)
                Log.d(LOG_TAG_VPN, "reset local stamp: ${persistentState.localBlocklistStamp}")
        } catch (e: Exception) {
            persistentState.localBlocklistStamp = ""
            Log.e(LOG_TAG_VPN, "could not reset local stamp: ${e.message}", e)
        }
    }

    private fun makeLocalBraveDns(): BraveDNS? {
        return appConfig.getBraveDnsObj()
    }

    companion object {

        fun establish(
            context: Context,
            scope: CoroutineScope,
            tunFd: ParcelFileDescriptor?
        ): GoVpnAdapter? {
            if (tunFd == null) {
                Log.e(LOG_TAG_VPN, "establish: tunFd is null")
                return null
            }
            return GoVpnAdapter(context, scope, tunFd)
        }

        fun getIpString(context: Context?, url: String?): String {
            if (url == null) return ""

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

        fun setLogLevel(level: Int) {
            Tun2socks.logLevel(level.toLong())
        }
    }

    private fun io(f: suspend () -> Unit) {
        externalScope.launch { withContext(Dispatchers.IO) { f() } }
    }

    private fun ui(f: suspend () -> Unit) {
        externalScope.launch { withContext(Dispatchers.Main) { f() } }
    }
}
