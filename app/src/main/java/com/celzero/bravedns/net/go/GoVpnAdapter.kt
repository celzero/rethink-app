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
import com.celzero.bravedns.BuildConfig.DEBUG
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.data.AppConfig.TunnelOptions
import com.celzero.bravedns.database.ProxyEndpoint
import com.celzero.bravedns.service.BraveVPNService.Companion.USER_SELECTED_TRANSPORT_ID
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Constants.Companion.DEFAULT_DOH_URL
import com.celzero.bravedns.util.Constants.Companion.ONDEVICE_BLOCKLIST_FILE_TAG
import com.celzero.bravedns.util.Constants.Companion.REMOTE_BLOCKLIST_DOWNLOAD_FOLDER_NAME
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_VPN
import com.celzero.bravedns.util.Utilities.Companion.blocklistFile
import com.celzero.bravedns.util.Utilities.Companion.remoteBlocklistFile
import com.celzero.bravedns.util.Utilities.Companion.showToastUiCentered
import dnsx.BraveDNS
import dnsx.Dnsx
import dnsx.Transport
import inet.ipaddr.HostName
import inet.ipaddr.IPAddressString
import intra.Intra
import intra.Tunnel
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import settings.Settings
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
            if (DEBUG) {
                Tun2socks.enableDebugLog()
            }

            // TODO : #321 As of now the app fallback on an unmaintained url. Requires a rewrite as
            // part of v055
            val dohURL: String = getDefaultDohUrl()

            val transport: Transport = makeDefaultTransport(dohURL)
            Log.i(
                LOG_TAG_VPN,
                "Connect tunnel with url $tunFd dnsMode: ${tunnelOptions.tunDnsMode}, blockMode: ${tunnelOptions.tunFirewallMode}, proxyMode: ${tunnelOptions.tunProxyMode}, fake dns: ${tunnelOptions.fakeDns}, mtu:${tunnelOptions.mtu}"
            )

            if (tunFd == null) return

            setPreferredEngine(tunnelOptions)
            tunnel =
                Tun2socks.connectIntraTunnel(
                    tunFd!!.fd.toLong(),
                    tunnelOptions.mtu.toLong(),
                    tunnelOptions.fakeDns,
                    transport,
                    tunnelOptions.blocker,
                    tunnelOptions.listener
                )

            setTunnelMode(tunnelOptions)
            addTransport()
            setBraveDnsBlocklistMode()
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, e.message, e)
            tunnel?.disconnect()
            tunnel = null
        }
    }

    private fun setPreferredEngine(tunnelOptions: TunnelOptions) {
        Log.i(
            LOG_TAG_VPN,
            "Preferred engine name:${tunnelOptions.preferredEngine.name},id: ${tunnelOptions.preferredEngine.getPreferredEngine()}"
        )
        Tun2socks.preferredEngine(tunnelOptions.preferredEngine.getPreferredEngine())
    }

    private suspend fun addTransport() {
        var transport: Transport? = null
        when (appConfig.getDnsType()) {
            AppConfig.DnsType.DOH -> {
                transport = createDohTransport()
            }
            AppConfig.DnsType.DNSCRYPT -> {
                transport = createDNSCryptTransport()
            }
            AppConfig.DnsType.DNS_PROXY -> {
                transport = createDnsProxyTransport()
            }
            AppConfig.DnsType.NETWORK_DNS -> {
                transport = createNetworkDnsTransport()
            }
            AppConfig.DnsType.RETHINK_REMOTE -> {
                transport = createRethinkDnsTransport()
            }
        }

        if (transport != null) {
            tunnel?.resolver?.add(transport)
            Log.i(LOG_TAG_VPN, "add transport to resolver, addr: ${transport.addr}")
        } else {
            Log.e(LOG_TAG_VPN, "transport is null for dns type: ${appConfig.getDnsType()}")
        }
    }

    private suspend fun createDohTransport(): Transport? {
        val doh = appConfig.getDOHDetails()
        val url = doh?.dohURL ?: DEFAULT_DOH_URL
        val transport = Intra.newDoHTransport(USER_SELECTED_TRANSPORT_ID, url, "", null)
        Log.i(
            LOG_TAG_VPN,
            "create doh transport with id: $USER_SELECTED_TRANSPORT_ID(${doh?.dohName}), url: $url, transport: $transport"
        )
        return transport
    }

    private suspend fun createDNSCryptTransport(): Transport? {
        try {
            val dnscrypt = appConfig.getConnectedDnscryptServer()
            val url = dnscrypt.dnsCryptURL
            val resolver = tunnel?.resolver
            val transport = Intra.newDNSCryptTransport(resolver, USER_SELECTED_TRANSPORT_ID, url)
            Log.d(
                LOG_TAG_VPN,
                "create dnscrypt transport with id: $USER_SELECTED_TRANSPORT_ID(${dnscrypt.dnsCryptName}), url: $url, transport: $transport"
            )
            setDnscryptResolversIfAny()
            return transport
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "connect-tunnel: dns crypt failure", e)
            handleDnscryptFailure()
            return null
        }
    }

    private suspend fun createDnsProxyTransport(): Transport? {
        try {
            val dnsProxy = appConfig.getSelectedDnsProxyDetails() ?: return null
            val transport =
                Intra.newDNSProxy(
                    USER_SELECTED_TRANSPORT_ID,
                    dnsProxy.proxyIP,
                    dnsProxy.proxyPort.toString()
                )
            Log.d(
                LOG_TAG_VPN,
                "create dns proxy transport with id: $USER_SELECTED_TRANSPORT_ID(${dnsProxy.proxyName}), ip: ${dnsProxy.proxyIP}, port: ${dnsProxy.proxyPort}"
            )
            return transport
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "connect-tunnel: dns proxy failure", e)
            handleDnsProxyFailure()
            return null
        }
    }

    private suspend fun createNetworkDnsTransport(): Transport? {
        try {
            val systemDns = appConfig.getSystemDns()
            val transport =
                Intra.newDNSProxy(
                    USER_SELECTED_TRANSPORT_ID,
                    systemDns.ipAddress,
                    systemDns.port.toString()
                )
            Log.d(
                LOG_TAG_VPN,
                "create network dnsproxy transport with id: $USER_SELECTED_TRANSPORT_ID, url: ${systemDns.ipAddress}/${systemDns.port}, transport: $transport"
            )
            return transport
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "connect-tunnel: dns proxy failure", e)
            handleDnsProxyFailure()
            return null
        }
    }

    private suspend fun createRethinkDnsTransport(): Transport? {
        return try {
            val rethinkDns = appConfig.getRemoteRethinkEndpoint()
            val url = rethinkDns?.url
            val transport = Intra.newDoHTransport(USER_SELECTED_TRANSPORT_ID, url, "", null)
            Log.i(
                LOG_TAG_VPN,
                "create doh transport with id: $USER_SELECTED_TRANSPORT_ID(${rethinkDns?.name}), url: $url, transport: $transport"
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
                Settings.ProxyModeSOCKS5,
                tunnelOptions.ptMode.id
            )
        } else {
            tunnel?.setTunMode(
                tunnelOptions.tunDnsMode.mode,
                tunnelOptions.tunFirewallMode.mode,
                tunnelOptions.tunProxyMode.mode,
                tunnelOptions.ptMode.id
            )
        }
        setSocks5TunnelModeIfNeeded(tunnelOptions.tunProxyMode)
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
                remoteBlocklistFile(
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
    private fun setProxyMode(userName: String?, password: String?, ipAddress: String?, port: Int) {
        try {
            tunnel?.startProxy(userName, password, ipAddress, port.toString())
            Log.i(
                LOG_TAG_VPN,
                "Proxy mode set: $userName$ipAddress$port with tunnel proxyoptions: ${tunnel?.proxyOptions}"
            )
        } catch (e: Exception) {
            Log.e(
                LOG_TAG_VPN,
                "connect-tunnel: could not start proxy $userName@$ipAddress:$port",
                e
            )
        }
    }

    private suspend fun handleDnscryptFailure() {
        appConfig.setDefaultConnection()
        showDnscryptConnectionFailureToast()
        Log.i(LOG_TAG_VPN, "connect-tunnel: falling back to doh since dnscrypt failed")
    }

    private suspend fun handleDnsProxyFailure() {
        appConfig.setDefaultConnection()
        showDnsProxyConnectionFailureToast()
        Log.i(LOG_TAG_VPN, "connect-tunnel: falling back to doh since dns proxy failed")
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
        setProxyMode(socks5.userName, socks5.password, socks5.proxyIP, socks5.proxyPort)
        Log.i(LOG_TAG_VPN, "Socks5 mode set: " + socks5.proxyIP + "," + socks5.proxyPort)
    }

    fun hasTunnel(): Boolean {
        return (tunnel != null)
    }

    fun close() {
        if (tunnel != null) {
            tunnel?.disconnect()
            Log.i(LOG_TAG_VPN, "Tunnel disconnect")
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
        return Intra.newDoHTransport(Dnsx.Default, url, dohIPs, null)
    }

    fun setSystemDns() {
        if (tunnel != null) {
            val dnsProxy =
                if (appConfig.isSystemDns()) {
                    val systemDns = appConfig.getSystemDns()
                    HostName(IPAddressString(systemDns.ipAddress).address, systemDns.port)
                } else {
                    null
                }

            if (dnsProxy == null) {
                return
            }

            if (DEBUG)
                Log.d(LOG_TAG_VPN, "setSystemDns mode set: ${dnsProxy.host} , ${dnsProxy.port}")

            // below code is commented out, add the code to set the system dns via resolver
            // val transport = Intra.newDNSProxy("ID", dnsProxy.host, dnsProxy.port.toString())
            // tunnel?.resolver?.addSystemDNS(transport)
            tunnel?.setSystemDNS(dnsProxy.toNormalizedString())
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
            return
        }

        if (tunnel == null) {
            // Attempt to re-create the tunnel.  Creation may have failed originally because the DoH
            // server could not be reached.  This will update the DoH URL as well.
            connectTunnel(tunnelOptions)
            return
        }
        Log.i(LOG_TAG_VPN, "received update tun with opts: $tunnelOptions")
        // Overwrite the DoH Transport with a new one, even if the URL has not changed.  This
        // function is called on network changes, and it's important to switch to a fresh transport
        // because the old transport may be using sockets on a deleted interface, which may block
        // until they time out.
        // val dohURL: String = getDohUrl()
        try {
            // For invalid URL connection request.
            // Check makeDohTransport, if it is not resolved don't close the tunnel.
            // So handling the exception in makeDohTransport and not resetting the tunnel. Below is
            // the exception thrown from Tun2socks.aar
            // I/GoLog: Failed to read packet from TUN: read : bad file descriptor
            // val defaultTransport: Transport = makeDefaultTransport(dohURL)

            // add transport to resolver, no need to set default transport on updateTunnel
            addTransport()

            setTunnelMode(tunnelOptions)
            // Set brave dns to tunnel - Local/Remote
            setBraveDnsBlocklistMode()
            setDnscryptResolversIfAny()
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, e.message, e)
            tunnel?.disconnect()
            tunnel = null
        }
    }

    private fun getDefaultDohUrl(): String {
        return DEFAULT_DOH_URL
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
            if (tunnel == null) return

            if (tunnel?.resolver?.rdnsLocal != null) {
                tunnel?.resolver?.rdnsLocal?.stamp = persistentState.localBlocklistStamp
            } else {
                Log.w(
                    LOG_TAG_VPN,
                    "brave dns mode is not local but trying to set local stamp, this should not happen"
                )
            }
        } catch (e: java.lang.Exception) {
            Log.e(LOG_TAG_VPN, "could not set set local-brave dns stamp: ${e.message}", e)
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
            if (tunFd == null) return null
            return GoVpnAdapter(context, scope, tunFd)
        }

        fun getIpString(context: Context?, url: String?): String {
            val res: Resources? = context?.resources
            val urls: Array<out String>? = res?.getStringArray(R.array.urls)
            val ips: Array<out String>? = res?.getStringArray(R.array.ips)
            if (urls == null) return ""
            for (i in urls.indices) {
                if (urls[i].contains((url.toString()))) {
                    if (ips != null) return ips[i]
                }
            }
            return ""
        }
    }

    private fun io(f: suspend () -> Unit) {
        externalScope.launch { withContext(Dispatchers.IO) { f() } }
    }

    private fun ui(f: suspend () -> Unit) {
        externalScope.launch { withContext(Dispatchers.Main) { f() } }
    }
}
