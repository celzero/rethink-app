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
import android.net.ProxyInfo
import android.os.ParcelFileDescriptor
import android.util.Log
import android.widget.Toast
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.data.AppConfig.TunnelOptions
import com.celzero.bravedns.database.ProxyEndpoint
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.ONDEVICE_BLOCKLIST_FILE_TAG
import com.celzero.bravedns.util.Constants.Companion.REMOTE_BLOCKLIST_DOWNLOAD_FOLDER_NAME
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_VPN
import com.celzero.bravedns.util.Utilities.blocklistDir
import com.celzero.bravedns.util.Utilities.blocklistFile
import com.celzero.bravedns.util.Utilities.isValidDnsPort
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import dnsx.BraveDNS
import dnsx.Dnsx
import dnsx.Transport
import inet.ipaddr.HostName
import inet.ipaddr.IPAddressString
import intra.Intra
import intra.Tunnel
import ipn.Ipn
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import tun2socks.Tun2socks

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

    suspend fun start(tunnelOptions: TunnelOptions) {
        connectTunnel(tunnelOptions)
    }

    private suspend fun connectTunnel(tunnelOptions: TunnelOptions) {
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
            setDnsAlg()
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
            }
            AppConfig.DnsType.DNSCRYPT -> {
                transport = createDNSCryptTransport(Dnsx.Preferred)
            }
            AppConfig.DnsType.DNS_PROXY -> {
                transport = createDnsProxyTransport(Dnsx.Preferred)
            }
            AppConfig.DnsType.NETWORK_DNS -> {
                transport = createNetworkDnsTransport(Dnsx.Preferred)
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
            Log.i(
                LOG_TAG_VPN,
                "add blockfree transport to resolver, addr: ${blockFreeTransport.addr}, $added"
            )
        } else {
            // remove the block free transport from the resolver
            val removed = tunnel?.resolver?.remove(Dnsx.BlockFree)
            Log.i(
                LOG_TAG_VPN,
                "blockfree transport is null for current dns type: ${appConfig.getDnsType()}, so remove the block free transport from the resolver if any, $removed"
            )
        }

        if (transport != null) {
            val added = tunnel?.resolver?.add(transport)
            Log.i(
                LOG_TAG_VPN,
                "add transport to resolver, id: ${transport.id()} addr:  ${transport.addr}, $added"
            )
        } else {
            Log.e(LOG_TAG_VPN, "transport is null for dns type: ${appConfig.getDnsType()}")
        }
    }

    private suspend fun createDohTransport(id: String): Transport? {
        val doh = appConfig.getDOHDetails()
        val url = doh?.dohURL
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
            Log.d(
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
            Log.d(
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
            Log.d(
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
        if (tunnelOptions.tunProxyMode.isTunProxyOrbot()) {
            tunnel?.setTunMode(
                tunnelOptions.tunDnsMode.mode,
                tunnelOptions.tunFirewallMode.mode,
                tunnelOptions.ptMode.id
            )
        } else {
            tunnel?.setTunMode(
                tunnelOptions.tunDnsMode.mode,
                tunnelOptions.tunFirewallMode.mode,
                tunnelOptions.ptMode.id
            )
        }
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
    private fun setProxyMode(
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
                    Ipn.OrbotS5
                } else {
                    Ipn.SOCKS5
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
        val proxyUrl = StringBuilder()
        proxyUrl.append("socks5://")
        if (!userName.isNullOrEmpty() && !password.isNullOrEmpty()) {
            proxyUrl.append(userName)
            proxyUrl.append(":")
            proxyUrl.append(password)
            proxyUrl.append("@")
        }
        proxyUrl.append(ipAddress)
        proxyUrl.append(":")
        proxyUrl.append(port)
        return proxyUrl.toString()
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

    private fun showDnsProxyConnectionFailureToast() {
        ui {
            showToastUiCentered(
                context.applicationContext,
                context.getString(R.string.dns_proxy_connection_failure),
                Toast.LENGTH_SHORT
            )
        }
    }

    private suspend fun setSocks5TunnelModeIfNeeded(tunProxyMode: AppConfig.TunProxyMode) {
        if (!tunProxyMode.isTunProxySocks5() && !tunProxyMode.isTunProxyOrbot()) return

        val socks5: ProxyEndpoint? =
            if (tunProxyMode.isTunProxyOrbot()) {
                appConfig.getOrbotProxyDetails()
            } else {
                appConfig.getSocks5ProxyDetails()
            }
        if (socks5 == null) {
            Log.w(LOG_TAG_VPN, "could not fetch socks5 details for proxyMode: $tunProxyMode")
            return
        }
        setProxyMode(
            tunProxyMode,
            socks5.userName,
            socks5.password,
            socks5.proxyIP,
            socks5.proxyPort
        )
        Log.i(LOG_TAG_VPN, "Socks5 mode set: " + socks5.proxyIP + "," + socks5.proxyPort)
    }

    private fun setHttpProxyIfNeeded(tunProxyMode: AppConfig.TunProxyMode) {
        if (!tunProxyMode.isTunProxyHttps()) return

        val httpProxy: ProxyInfo? = appConfig.getHttpProxyInfo()
        if (httpProxy == null) {
            Log.w(LOG_TAG_VPN, "could not fetch http proxy details for proxyMode: $tunProxyMode")
            return
        }
        val httpProxyUrl = constructHttpsProxyUrl(httpProxy)
        tunnel?.proxies?.addProxy(Ipn.HTTP1, httpProxyUrl)
        Log.i(LOG_TAG_VPN, "Http mode set with url: $httpProxyUrl")
    }

    private fun constructHttpsProxyUrl(p: ProxyInfo): String {
        val proxyUrl = StringBuilder()
        proxyUrl.append("https://")
        proxyUrl.append(p.host)
        proxyUrl.append(":")
        proxyUrl.append(p.port)
        return proxyUrl.toString()
    }

    fun hasTunnel(): Boolean {
        return (tunnel != null)
    }

    fun refresh() {
        if (tunnel != null) {
            tunnel?.resolver?.refresh()
        }
    }

    fun close() {
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
        val dohIPs: String = getIpString(context, url)
        return Intra.newDoHTransport(Dnsx.Default, url, dohIPs)
    }

    fun setSystemDns() {
        if (tunnel != null) {
            try {
                val systemDns = appConfig.getSystemDns()
                val dnsProxy =
                    HostName(IPAddressString(systemDns.ipAddress).address, systemDns.port)

                if (dnsProxy.host.isNullOrEmpty() || !isValidDnsPort(dnsProxy.port)) {
                    Log.e(LOG_TAG_VPN, "setSystemDns: invalid dnsProxy: $dnsProxy")
                    return
                }

                if (DEBUG)
                    Log.d(LOG_TAG_VPN, "setSystemDns mode set: ${dnsProxy.host} , ${dnsProxy.port}")

                // below code is commented out, add the code to set the system dns via resolver
                // val transport = Intra.newDNSProxy("ID", dnsProxy.host, dnsProxy.port.toString())
                // tunnel?.resolver?.addSystemDNS(transport)
                tunnel?.setSystemDNS(dnsProxy.toNormalizedString())

                // if system dns is set, then set the system dns to the tunnel
                if (appConfig.getDnsType().isNetworkDns()) {
                    val transport = createNetworkDnsTransport(Dnsx.Preferred)
                    val blockFreeTransport = createNetworkDnsTransport(Dnsx.BlockFree)

                    if (blockFreeTransport != null) {
                        tunnel?.resolver?.add(blockFreeTransport)
                    } else {
                        Log.e(LOG_TAG_VPN, "setSystemDns: could not set block free dns")
                    }

                    if (transport != null) {
                        tunnel?.resolver?.add(transport)
                    } else {
                        Log.e(LOG_TAG_VPN, "setSystemDns: could not set system dns")
                    }
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG_VPN, "setSystemDns: could not set system dns", e)
            }
        } else {
            Log.e(LOG_TAG_VPN, "setSystemDns: tunnel is null")
        }
    }

    /**
     * Updates the DOH server URL for the VPN. If Go-DoH is enabled, DNS queries will be handled in
     * Go, and will not use the Java DoH implementation. If Go-DoH is not enabled, this method has
     * no effect.
     */
    suspend fun updateTun(tunnelOptions: TunnelOptions) {
        // changes made in connectTunnel()
        if (tunFd == null) {
            // Adapter is closed.
            Log.e(LOG_TAG_VPN, "updateTun: tunFd is null, returning")
            return
        }

        if (tunnel == null) {
            // Attempt to re-create the tunnel.  Creation may have failed originally because the DoH
            // server could not be reached.  This will update the DoH URL as well.
            Log.w(LOG_TAG_VPN, "updateTun: tunnel is null, calling connectTunnel")
            connectTunnel(tunnelOptions)
            return
        }
        Log.i(LOG_TAG_VPN, "received update tun with opts: $tunnelOptions")
        try {
            setTunnelMode(tunnelOptions)
            // add transport to resolver, no need to set default transport on updateTunnel
            addTransport()
            setDnsAlg()
            // Set brave dns to tunnel - Local/Remote
            setBraveDnsBlocklistMode()
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, e.message, e)
            tunnel?.disconnect()
            tunnel = null
        }
    }

    fun setDnsAlg() {
        tunnel?.resolver?.gateway()?.translate(persistentState.enableDnsAlg)
    }

    private fun getDefaultDohUrl(): String {
        return persistentState.defaultDnsUrl.ifEmpty { Constants.DEFAULT_DNS_LIST[0].url }
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
                if (DEBUG) Log.d(LOG_TAG_VPN, "brave dns object is set")
                tunnel?.resolver?.rdnsLocal = braveDNS
                tunnel?.resolver?.rdnsLocal?.stamp = stamp
            } else {
                Log.e(LOG_TAG_VPN, "Issue creating local brave dns object")
            }
        } catch (ex: Exception) {
            Log.e(LOG_TAG_VPN, "could not set local-brave dns: ${ex.message}", ex)
        }
    }

    fun setBraveDnsStamp() {
        try {
            if (tunnel == null) {
                Log.e(LOG_TAG_VPN, "tunnel is null, not setting brave dns stamp")
                return
            }

            if (tunnel?.resolver?.rdnsLocal != null) {
                tunnel?.resolver?.rdnsLocal?.stamp = persistentState.localBlocklistStamp
            } else {
                Log.w(
                    LOG_TAG_VPN,
                    "brave dns mode is not local but trying to set local stamp, this should not happen"
                )
            }
        } catch (e: java.lang.Exception) {
            Log.e(LOG_TAG_VPN, "could not set local-brave dns stamp: ${e.message}", e)
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
