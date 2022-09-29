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
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.data.AppConfig.Companion.dnscryptRelaysToRemove
import com.celzero.bravedns.data.AppConfig.TunnelOptions
import com.celzero.bravedns.database.DnsProxyEndpoint
import com.celzero.bravedns.database.ProxyEndpoint
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.DEFAULT_DOH_URL
import com.celzero.bravedns.util.Constants.Companion.ONDEVICE_BLOCKLIST_FILE_TAG
import com.celzero.bravedns.util.Constants.Companion.REMOTE_BLOCKLIST_DOWNLOAD_FOLDER_NAME
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_VPN
import com.celzero.bravedns.util.Utilities.Companion.blocklistFile
import com.celzero.bravedns.util.Utilities.Companion.getNonLiveDnscryptServers
import com.celzero.bravedns.util.Utilities.Companion.remoteBlocklistFile
import com.celzero.bravedns.util.Utilities.Companion.showToastUiCentered
import dnsx.BraveDNS
import dnsx.Dnsx
import doh.Transport
import inet.ipaddr.HostName
import inet.ipaddr.IPAddressString
import intra.Listener
import intra.Tunnel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import settings.Settings
import tun2socks.Tun2socks
import java.io.IOException

/**
 * This is a VpnAdapter that captures all traffic and routes it through a go-tun2socks instance with
 * custom logic for Intra.
 * */
class GoVpnAdapter(private val context: Context, private val externalScope: CoroutineScope,
                   private var tunFd: ParcelFileDescriptor?) : KoinComponent {

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
            val dohURL: String = getDohUrl()

            val transport: Transport = makeDohTransport(dohURL, tunnelOptions.listener)
            Log.i(LOG_TAG_VPN,
                  "Connect tunnel with url " + dohURL + ", dnsMode: " + tunnelOptions.tunDnsMode + ", blockMode: " + tunnelOptions.tunFirewallMode + ", proxyMode: " + tunnelOptions.tunProxyMode + ", fake dns: " + tunnelOptions.fakeDns)

            if (tunFd == null) return

            setPreferredEngine(tunnelOptions)
            tunnel = Tun2socks.connectIntraTunnel(tunFd!!.fd.toLong(), tunnelOptions.fakeDns,
                                                  transport, tunnelOptions.blocker,
                                                  tunnelOptions.listener)

            setBraveDnsBlocklistMode(tunnelOptions.tunDnsMode, dohURL)
            setTunnelMode(tunnelOptions)
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, e.message, e)
            tunnel?.disconnect()
            tunnel = null
        }
    }

    private fun setPreferredEngine(tunnelOptions: TunnelOptions) {
        Log.i(LOG_TAG_VPN,
              "Preferred engine name:${tunnelOptions.preferredEngine.name},id: ${tunnelOptions.preferredEngine.getPreferredEngine()}")
        Tun2socks.preferredEngine(tunnelOptions.preferredEngine.getPreferredEngine())
    }

    private fun isRethinkDnsUrl(url: String): Boolean {
        return url.contains(Constants.BRAVEDNS_DOMAIN) || url.contains(Constants.RETHINKDNS_DOMAIN)
    }

    private suspend fun setTunnelMode(tunnelOptions: TunnelOptions) {
        if (tunnelOptions.tunDnsMode.isDnscrypt()) {
            setDnscryptMode(tunnelOptions)
            return
        }

        if (tunnelOptions.tunProxyMode.isTunProxyOrbot()) {
            tunnel?.setTunMode(tunnelOptions.tunDnsMode.mode, tunnelOptions.tunFirewallMode.mode,
                               Settings.ProxyModeSOCKS5, tunnelOptions.ptMode.id)
        } else {
            tunnel?.setTunMode(tunnelOptions.tunDnsMode.mode, tunnelOptions.tunFirewallMode.mode,
                               tunnelOptions.tunProxyMode.mode, tunnelOptions.ptMode.id)
        }
        stopDnscryptIfNeeded()
        setDnsProxyIfNeeded(tunnelOptions)
        setSocks5TunnelModeIfNeeded(tunnelOptions.tunProxyMode)
    }

    private fun setBraveDnsBlocklistMode(tunDnsMode: AppConfig.TunDnsMode, dohUrl: String) {
        if (DEBUG) Log.d(LOG_TAG_VPN, "init bravedns mode")
        tunnel?.braveDNS = null

        // No need to set the brave mode for DNS Proxy (implementation pending in underlying Go library).
        // TODO: remove the check once the implementation completed in underlying Go library
        io {
            if (persistentState.blocklistEnabled) {
                setBraveDNSLocalMode()
            } else if (tunDnsMode.isRethinkRemote()) {
                setBraveDNSRemoteMode(dohUrl)
            } else {
                // no-op
            }
        }
    }

    private fun setBraveDNSRemoteMode(dohURL: String) {
        // Brave mode remote will be set only if the selected DoH is RethinkDns
        if (!isRethinkDnsUrl(dohURL)) {
            return
        }

        try {
            val remoteDir = remoteBlocklistFile(context, REMOTE_BLOCKLIST_DOWNLOAD_FOLDER_NAME,
                                                persistentState.remoteBlocklistTimestamp) ?: return
            val remoteFile = blocklistFile(remoteDir.absolutePath,
                                           ONDEVICE_BLOCKLIST_FILE_TAG) ?: return
            if (remoteFile.exists()) {
                tunnel?.braveDNS = Dnsx.newBraveDNSRemote(remoteFile.absolutePath)
                Log.i(LOG_TAG_VPN, "remote-bravedns enabled")
            } else {
                Log.w(LOG_TAG_VPN, "filetag.json for remote-bravedns missing")
            }
        } catch (ex: Exception) {
            Log.e(LOG_TAG_VPN, "cannot set remote-bravedns: ${ex.message}", ex)
        }
    }

    private fun stopDnscryptIfNeeded() {
        try {
            if (tunnel?.dnsCryptProxy != null) {
                tunnel?.stopDNSCryptProxy()
                Log.i(LOG_TAG_VPN, "connect-tunnel - stopDNSCryptProxy")
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "stop dnscrypt failure: " + e.message, e)
        }
    }

    fun setDnscryptMode(tunnelOptions: TunnelOptions) {
        io {
            if (tunnel == null) return@io

            val servers: String = appConfig.getDnscryptServers()
            val routes: String = appConfig.getDnscryptRelayServers()
            val serversIndex: String = appConfig.getDnscryptServersToRemove()
            try {
                if (tunnel?.dnsCryptProxy == null) {
                    val response: String? = tunnel?.startDNSCryptProxy(servers, routes,
                                                                       tunnelOptions.listener)
                    Log.i(LOG_TAG_VPN, "startDNSCryptProxy: $servers,$routes, Response: $response")
                } else {
                    val serversToRemove: String? = tunnel?.dnsCryptProxy?.liveServers()?.let {
                        getNonLiveDnscryptServers(it, serversIndex)
                    }
                    if (!serversToRemove.isNullOrBlank()) {
                        tunnel?.dnsCryptProxy?.removeServers(serversToRemove)
                    }
                    if (dnscryptRelaysToRemove.isNotEmpty()) {
                        tunnel?.dnsCryptProxy?.removeRoutes(dnscryptRelaysToRemove)
                        dnscryptRelaysToRemove = ""
                    }
                    if (routes.isNotEmpty()) {
                        tunnel?.dnsCryptProxy?.removeRoutes(routes)
                    }
                    tunnel?.dnsCryptProxy?.addServers(servers)
                    if (routes.isNotEmpty()) tunnel?.dnsCryptProxy?.addRoutes(routes)
                    Log.i(LOG_TAG_VPN, "DNSCrypt with routes: $routes, servers: $servers")
                }
            } catch (ex: Exception) {
                Log.e(LOG_TAG_VPN, "connect-tunnel: dns crypt failure", ex)
                handleDnscryptFailure()
            }
            if (servers.isNotEmpty()) {
                refreshDnscrypt(tunnelOptions)
            }
        }
    }

    private suspend fun setDnsProxyIfNeeded(tunnelOptions: TunnelOptions) {
        if (!tunnelOptions.tunDnsMode.isDnsProxy() || !tunnelOptions.tunDnsMode.isSystemDns()) return

        try {
            val dnsProxy = getConnectedProxy()

            if (dnsProxy == null) {
                handleDnsProxyFailure()
                return
            }

            if (DEBUG) Log.d(LOG_TAG_VPN,
                             "setDNSProxy mode set: " + dnsProxy.host + ", " + dnsProxy.port)
            tunnel?.startDNSProxy(dnsProxy.host, dnsProxy.port.toString(), tunnelOptions.listener)
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "connect-tunnel: could not connect to dnsproxy: ${e.message}", e)
            handleDnsProxyFailure()
        }
    }

    private suspend fun getConnectedProxy(): HostName? {
        if (appConfig.isSystemDns()) {
            val systemDns = appConfig.getSystemDns()
            return HostName(IPAddressString(systemDns.ipAddress).address, systemDns.port)
        }

        if (appConfig.isDnsProxyActive()) {
            val dnsProxy: DnsProxyEndpoint = appConfig.getConnectedProxyDetails() ?: return null

            val proxyIp = IPAddressString(dnsProxy.proxyIP).address
            return HostName(proxyIp, dnsProxy.proxyPort)
        }

        return null
    }

    /**
     * TODO - Move these code to common place and set the tunnel mode and
     * other parameters. Return the tunnel to the adapter.
     */
    private fun setProxyMode(userName: String?, password: String?, ipAddress: String?, port: Int) {
        try {
            tunnel?.startProxy(userName, password, ipAddress, port.toString())
            Log.i(LOG_TAG_VPN,
                  "Proxy mode set: $userName$ipAddress$port with tunnel proxyoptions: ${tunnel?.proxyOptions}")
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "connect-tunnel: could not start proxy $userName@$ipAddress:$port",
                  e)
        }
    }

    private suspend fun refreshDnscrypt(tunnelOptions: TunnelOptions) {
        if (tunnel?.dnsCryptProxy == null) {
            handleDnscryptFailure()
            return
        }

        io {
            try {
                val liveServers: String? = tunnel?.dnsCryptProxy?.refresh()
                appConfig.updateDnscryptLiveServers(liveServers)
                Log.i(LOG_TAG_VPN,
                      "Refresh LiveServers: $liveServers, tunnelOptions: $tunnelOptions")
                if (liveServers.isNullOrEmpty()) {
                    tunnel?.stopDNSCryptProxy()
                    handleDnscryptFailure()
                } else {
                    tunnel?.setTunMode(Settings.DNSModeCryptPort,
                                       tunnelOptions.tunFirewallMode.mode,
                                       tunnelOptions.tunProxyMode.mode, tunnelOptions.ptMode.id)
                    setSocks5TunnelModeIfNeeded(tunnelOptions.tunProxyMode)
                }
            } catch (e: Exception) {
                handleDnscryptFailure()
                Log.e(LOG_TAG_VPN, "connect-tunnel: could not start dnscrypt-proxy: ${e.message}",
                      e)
            }
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
            showToastUiCentered(context, context.getString(R.string.dns_crypt_connection_failure),
                                Toast.LENGTH_SHORT)
        }
    }

    private fun showDnsProxyConnectionFailureToast() {
        ui {
            showToastUiCentered(context, context.getString(R.string.dns_proxy_connection_failure),
                                Toast.LENGTH_SHORT)
        }
    }

    private suspend fun setSocks5TunnelModeIfNeeded(tunProxyMode: AppConfig.TunProxyMode) {
        if (!tunProxyMode.isTunProxySocks5() && !tunProxyMode.isTunProxyOrbot()) return

        val socks5: ProxyEndpoint? = if (tunProxyMode.isTunProxyOrbot()) {
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

    fun getProxyTransport(): HostName? {
        val transport = tunnel?.dnsProxy ?: return null
        return HostName(transport.addr)
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
    private fun makeDohTransport(url: String?, listener: Listener): Transport {
        //TODO : Check the below code
        //@NonNull String realUrl = PersistentState.Companion.expandUrl(vpnService, url);
        val dohIPs: String = getIpString(context, url)
        return Tun2socks.newDoHTransport(url, dohIPs, null, listener)
    }

    /**
     * Updates the DOH server URL for the VPN.  If Go-DoH is enabled, DNS queries will be handled in
     * Go, and will not use the Java DoH implementation.  If Go-DoH is not enabled, this method
     * has no effect.
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

        // Overwrite the DoH Transport with a new one, even if the URL has not changed.  This function
        // is called on network changes, and it's important to switch to a fresh transport because the
        // old transport may be using sockets on a deleted interface, which may block until they time
        // out.
        val dohURL: String = getDohUrl()
        try {
            // For invalid URL connection request.
            // Check makeDohTransport, if it is not resolved don't close the tunnel.
            // So handling the exception in makeDohTransport and not resetting the tunnel. Below is the exception thrown from Tun2socks.aar
            // I/GoLog: Failed to read packet from TUN: read : bad file descriptor
            val dohTransport: Transport = makeDohTransport(dohURL, tunnelOptions.listener)
            tunnel?.dns = dohTransport
            Log.i(LOG_TAG_VPN, "update tun with doh: $dohURL, opts: $tunnelOptions")

            // Set brave dns to tunnel - Local/Remote
            setBraveDnsBlocklistMode(tunnelOptions.tunDnsMode, dohURL)
            setTunnelMode(tunnelOptions)
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, e.message, e)
            tunnel?.disconnect()
            tunnel = null
        }
    }

    private suspend fun getDohUrl(): String {
        var dohURL: String? = ""
        try {
            dohURL = appConfig.getRemoteRethinkEndpoint()?.url ?: appConfig.getDOHDetails()?.dohURL
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "error while fetching doh details", e)
        }
        if (dohURL == null) {
            dohURL = DEFAULT_DOH_URL
        }
        return dohURL
    }

    private fun setBraveDNSLocalMode() {
        try {
            val stamp: String = persistentState.localBlocklistStamp
            Log.i(LOG_TAG_VPN, "local-bravedns stamp: $stamp")
            if (stamp.isEmpty()) {
                return
            }

            tunnel?.braveDNS = makeLocalBraveDns()
            tunnel?.braveDNS?.stamp = stamp
        } catch (ex: Exception) {
            Log.e(LOG_TAG_VPN, "could not set local-bravedns: ${ex.message}", ex)
        }
    }

    fun setBraveDnsStamp() {
        try {
            if (tunnel == null) return

            if (tunnel?.braveDNS?.onDeviceBlock() == true) {
                tunnel?.braveDNS?.stamp = persistentState.localBlocklistStamp
            } else {
                Log.w(LOG_TAG_VPN,
                      "bravedns mode is not local but trying to set local stamp, this should not happen")
            }
        } catch (e: java.lang.Exception) {
            Log.e(LOG_TAG_VPN, "could not set set local-bravedns stamp: ${e.message}", e)
        }
    }

    private fun makeLocalBraveDns(): BraveDNS? {
        return appConfig.getBraveDnsObj()
    }

    companion object {

        suspend fun establish(context: Context, scope: CoroutineScope,
                              tunFd: ParcelFileDescriptor?): GoVpnAdapter? {
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
        externalScope.launch {
            withContext(Dispatchers.IO) {
                f()
            }
        }
    }

    private fun ui(f: suspend () -> Unit) {
        externalScope.launch {
            withContext(Dispatchers.Main) {
                f()
            }
        }
    }
}
