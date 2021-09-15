/*
 * Copyright 2021 RethinkDNS and its authors
 *
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
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.os.ParcelFileDescriptor
import android.util.Log
import android.widget.Toast
import com.celzero.bravedns.BuildConfig
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppMode
import com.celzero.bravedns.data.AppMode.Companion.cryptRelayToRemove
import com.celzero.bravedns.data.AppMode.TunnelOptions
import com.celzero.bravedns.database.DNSProxyEndpoint
import com.celzero.bravedns.database.ProxyEndpoint
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.DNSConfigureWebViewActivity.Companion.BLOCKLIST_REMOTE_FOLDER_NAME
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.ONDEVICE_BLOCKLIST_FILE_BASIC_CONFIG
import com.celzero.bravedns.util.Constants.Companion.ONDEVICE_BLOCKLIST_FILE_RD_FILE
import com.celzero.bravedns.util.Constants.Companion.ONDEVICE_BLOCKLIST_FILE_TAG_NAME
import com.celzero.bravedns.util.Constants.Companion.ONDEVICE_BLOCKLIST_FILE_TD_FILE
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_VPN
import com.celzero.bravedns.util.Utilities.Companion.prepareServersToRemove
import com.celzero.bravedns.util.Utilities.Companion.remoteBlocklistDir
import com.celzero.bravedns.util.Utilities.Companion.remoteBlocklistFile
import com.celzero.bravedns.util.Utilities.Companion.showToastUiCentered
import dnsx.BraveDNS
import dnsx.Dnsx
import doh.Transport
import intra.Tunnel
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import protect.Protector
import settings.Settings
import tun2socks.Tun2socks
import java.io.File
import java.io.IOException

/**
 * This is a VpnAdapter that captures all traffic and routes it through a go-tun2socks instance with
 * custom logic for Intra.
 */
class GoVpnAdapter(private var tunFd: ParcelFileDescriptor?) : KoinComponent {

    private val persistentState by inject<PersistentState>()
    private val appMode by inject<AppMode>()

    // The Intra session object from go-tun2socks.  Initially null.
    private var tunnel: Tunnel? = null
    private var localBraveDns: BraveDNS? = null

    @Synchronized
    suspend fun start(tunnelOptions: TunnelOptions) {
        connectTunnel(tunnelOptions)
    }

    private suspend fun connectTunnel(tunnelOptions: TunnelOptions) {
        if (tunnel != null) {
            return
        }

        //TODO : The below statement is incorrect, adding the dohURL as const for testing
        try {
            // TODO : #321 As of now the app fallback on an unmaintained url. Requires a rewrite as
            // part of v055
            val dohURL: String = getDohUrl()
            val transport: Transport = makeDohTransport(dohURL, tunnelOptions.listener)
            Log.i(LOG_TAG_VPN,
                  "Connect tunnel with url " + dohURL + ", dnsMode: " + tunnelOptions.dnsMode + ", blockMode: " + tunnelOptions.firewallMode + ", proxyMode: " + tunnelOptions.proxyMode)

            if (tunFd == null) return

            tunnel = Tun2socks.connectIntraTunnel(tunFd!!.fd.toLong(), tunnelOptions.fakeDns,
                                                  transport, getProtector(), tunnelOptions.blocker,
                                                  tunnelOptions.listener)
            if (DEBUG) {
                Tun2socks.enableDebugLog()
            }
            setBraveMode(tunnelOptions.dnsMode, dohURL)
            setTunnelMode(tunnelOptions)
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, e.message, e)
            if (tunnel != null) tunnel?.disconnect()
            tunnel = null
        }
    }

    private fun isRethinkDnsUrl(url: String): Boolean {
        return url.contains(Constants.BRAVE_BASE_URL) || url.contains(Constants.RETHINK_BASE_URL)
    }

    private suspend fun setTunnelMode(tunnelOptions: TunnelOptions) {
        if (!tunnelOptions.dnsMode.isDnscrypt()) {
            if (tunnelOptions.proxyMode.isOrbotProxy()) {
                tunnel?.setTunMode(tunnelOptions.dnsMode.mode, tunnelOptions.firewallMode.mode,
                                   Settings.ProxyModeSOCKS5)
            } else {
                tunnel?.setTunMode(tunnelOptions.dnsMode.mode, tunnelOptions.firewallMode.mode,
                                   tunnelOptions.proxyMode.mode)
            }
            stopDnscryptIfNeeded()
            if (tunnelOptions.dnsMode.isDnsProxy()) {
                setDNSProxy()
            }
            if (tunnelOptions.proxyMode.isCustomHttpsProxy() || tunnelOptions.proxyMode.isOrbotProxy()) {
                setSocks5TunnelMode(tunnelOptions.proxyMode)
            }
        } else {
            setDnscryptMode(tunnelOptions)
        }
    }

    private fun setBraveMode(dnsMode: AppMode.DnsMode, dohURL: String) {
        if (BuildConfig.DEBUG) Log.d(LOG_TAG_VPN, "init bravedns mode")
        // No need to set the brave mode for DNS Proxy (implementation pending in underlying Go library).
        // TODO: remove the check once the implementation completed in underlying Go library
        CoroutineScope(Dispatchers.IO).launch {
            if (dnsMode.isDoh() || dnsMode.isDnscrypt()) {
                setBraveDNSLocalMode()
                setBraveDNSRemoteMode(dohURL)
            }
        }
    }

    private fun setBraveDNSRemoteMode(dohURL: String) {
        // Brave mode remote will be set only if the selected DoH is RethinkDns
        // and if the local brave dns is not set in the tunnel.
        if (!isRethinkDnsUrl(dohURL) || localBraveDns != null) {
            return
        }
        try {
            val remoteDir = remoteBlocklistDir(VpnController.getBraveVpnService(),
                                               BLOCKLIST_REMOTE_FOLDER_NAME,
                                               persistentState.remoteBlocklistTimestamp) ?: return
            val remoteFile = remoteBlocklistFile(remoteDir.absolutePath,
                                                 ONDEVICE_BLOCKLIST_FILE_TAG_NAME) ?: return
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
        CoroutineScope(Dispatchers.IO).launch {
            setDnscrypt(tunnelOptions)
        }
    }

    private suspend fun setDnscrypt(tunnelOptions: TunnelOptions) {
        if (tunnel == null) return

        val servers: String = appMode.getDnscryptServers()
        val routes: String = appMode.getDnscryptRelayServers()
        val serversIndex: String = appMode.getDnscryptServersToRemove()
        try {
            if (tunnel?.dnsCryptProxy == null) {
                val response: String? = tunnel?.startDNSCryptProxy(servers, routes,
                                                                   tunnelOptions.listener)
                Log.i(LOG_TAG_VPN, "startDNSCryptProxy: $servers,$routes, Response: $response")
            } else {
                var serverCount: Long? = 0L
                var relayCount: Long? = 0L
                val serversToRemove: String? = tunnel?.dnsCryptProxy?.liveServers()?.let {
                    prepareServersToRemove(it, serversIndex)
                }
                if (serversToRemove != null) {
                    if (serversToRemove.isNotEmpty()) serverCount = tunnel?.dnsCryptProxy?.removeServers(
                        serversToRemove)
                }
                if (cryptRelayToRemove.isNotEmpty()) {
                    tunnel?.dnsCryptProxy?.removeRoutes(cryptRelayToRemove)
                    cryptRelayToRemove = ""
                }
                if (routes.isNotEmpty()) relayCount = tunnel?.dnsCryptProxy?.removeRoutes(routes)
                tunnel?.dnsCryptProxy?.addServers(servers)
                if (routes.isNotEmpty()) tunnel?.dnsCryptProxy?.addRoutes(routes)
                Log.i(LOG_TAG_VPN,
                      "DNSCrypt with routes: $routes, relay count: $relayCount, servers: $servers, removed count:$serverCount")
            }
        } catch (ex: Exception) {
            Log.e(LOG_TAG_VPN, "connect-tunnel: dns crypt failure", ex)
        }
        if (servers.isNotEmpty()) {
            refreshDnscrypt(tunnelOptions)
        }
    }

    private suspend fun setDNSProxy() {
        try {
            val dnsProxy: DNSProxyEndpoint = appMode.getConnectedProxyDetails()
            if (DEBUG) Log.d(LOG_TAG_VPN,
                             "setDNSProxy mode set: " + dnsProxy.proxyIP + ", " + dnsProxy.proxyPort)
            tunnel?.startDNSProxy(dnsProxy.proxyIP, dnsProxy.proxyPort.toString())
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "connect-tunnel: could not connect to dnsproxy: ${e.message}", e)
        }
    }

    /**
     * TODO - Move these code to common place and set the tunnel mode and
     * other parameters. Return the tunnel to the adapter.
     */
    private fun setProxyMode(userName: String?, password: String?, ipAddress: String?, port: Int) {
        try {
            tunnel?.startProxy(userName, password, ipAddress, port.toString())
            Log.i(LOG_TAG_VPN, "Proxy mode set: $userName$ipAddress$port")
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "connect-tunnel: could not start proxy $userName@$ipAddress:$port", e)
        }
    }

    private fun refreshDnscrypt(tunnelOptions: TunnelOptions) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (tunnel?.dnsCryptProxy != null) {
                    val liveServers: String? = tunnel?.dnsCryptProxy?.refresh()
                    appMode.updateDnscryptLiveServers(liveServers)
                    Log.i(LOG_TAG_VPN, "Refresh LiveServers: $liveServers")
                    if (liveServers.isNullOrEmpty()) {
                        Log.i(LOG_TAG_VPN, "no dnscrypt live-servers, fall back to doh")
                        tunnel?.stopDNSCryptProxy()
                        appMode.setDefaultConnection()
                        showDnscryptConnectionFailureToast()
                    } else {
                        tunnel?.setTunMode(Settings.DNSModeCryptPort,
                                           tunnelOptions.firewallMode.mode,
                                           tunnelOptions.proxyMode.mode)
                        if (tunnelOptions.proxyMode.isCustomSocks5Proxy() || tunnelOptions.proxyMode.isOrbotProxy()) {
                            setSocks5TunnelMode(tunnelOptions.proxyMode)
                        }
                        Log.i(LOG_TAG_VPN,
                              "connect crypt tunnel set with mode: " + Settings.DNSModeCryptPort + tunnelOptions.firewallMode + tunnelOptions.proxyMode)
                    }
                } else {
                    handleDnscryptFailure()
                }
            } catch (e: Exception) {
                handleDnscryptFailure()
                Log.e(LOG_TAG_VPN, "connect-tunnel: could not start dnscrypt-proxy: ${e.message}", e)
            }
        }
    }

    private suspend fun handleDnscryptFailure() {
        if (persistentState.dnsType == AppMode.DnsType.DNSCRYPT.type) {
            appMode.setDefaultConnection()
            showDnscryptConnectionFailureToast()
            Log.i(LOG_TAG_VPN, "connect-tunnel: falling back to doh since dnscrypt failed")
        } else {
            // no-op
        }
    }

    private fun showDnscryptConnectionFailureToast() {
        CoroutineScope(Dispatchers.Main).launch {
            VpnController.getBraveVpnService()?.let {
                showToastUiCentered(it, it.getString(R.string.dns_crypt_connection_failure),
                                    Toast.LENGTH_SHORT)
            }
        }
    }

    private suspend fun setSocks5TunnelMode(proxyMode: AppMode.ProxyMode) {
        val socks5: ProxyEndpoint?
        if (proxyMode.isOrbotProxy()) {
            socks5 = appMode.getOrbotProxyDetails()
        } else {
            socks5 = appMode.getSocks5ProxyDetails()
        }
        if (socks5 == null) {
            Log.w(LOG_TAG_VPN, "could not fetch socks5 details for proxyMode: $proxyMode")
            return
        }
        setProxyMode(socks5.userName, socks5.password, socks5.proxyIP, socks5.proxyPort)
        Log.i(LOG_TAG_VPN, "Socks5 mode set: " + socks5.proxyIP + "," + socks5.proxyPort)
    }

    fun hasTunnel(): Boolean {
        return (tunnel != null)
    }

    // We don't need socket protection in these versions because the call to
    // "addDisallowedApplication" effectively protects all sockets in this app.
    private fun getProtector(): Protector? {
        if (VERSION.SDK_INT >= VERSION_CODES.LOLLIPOP) {
            // We don't need socket protection in these versions because the call to
            // "addDisallowedApplication" effectively protects all sockets in this app.
            return null
        }
        return null
    }

    @Synchronized
    fun close() {
        if (tunnel != null) {
            tunnel?.disconnect()
            Log.i(LOG_TAG_VPN, "Tunnel disconnect")
        }
        if (tunFd != null) {
            try {
                tunFd?.close()
            } catch (e: IOException) {
                Log.e(LOG_TAG_VPN, e.message, e)
            }
        }
        tunFd = null
        tunnel = null
    }

    @Throws(Exception::class)
    private fun makeDohTransport(url: String?, listener: GoIntraListener): Transport {
        //TODO : Check the below code
        //@NonNull String realUrl = PersistentState.Companion.expandUrl(vpnService, url);
        val dohIPs: String = getIpString(VpnController.getBraveVpnService(), url)
        return Tun2socks.newDoHTransport(url, dohIPs, getProtector(), null, listener)
    }

    /**
     * Updates the DOH server URL for the VPN.  If Go-DoH is enabled, DNS queries will be handled in
     * Go, and will not use the Java DoH implementation.  If Go-DoH is not enabled, this method
     * has no effect.
     */
    @Synchronized
    suspend fun updateDohUrl(tunnelOptions: TunnelOptions) {
        // FIXME: 18-10-2020  - Check for the tunFD null code. Removed because of the connect tunnel

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
            Log.i(LOG_TAG_VPN,
                  "Connect tunnel with url " + dohURL + ", dnsMode: " + tunnelOptions.dnsMode + ", blockMode:" + tunnelOptions.firewallMode + ", proxyMode:" + tunnelOptions.proxyMode)

            // Set brave dns to tunnel - Local/Remote
            setBraveMode(tunnelOptions.dnsMode, dohURL)
            setTunnelMode(tunnelOptions)
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, e.message, e)
            tunnel?.disconnect()
            tunnel = null
        }
    }

    private fun getDohUrl(): String {
        var dohURL: String? = ""
        try {
            dohURL = appMode.getDOHDetails()?.dohURL
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "error while fetching doh details", e)
        }
        if (dohURL == null) dohURL = "https://basic.bravedns.com/dns-query"
        return dohURL
    }

    private fun setBraveDNSLocalMode() {
        try {
            if (!persistentState.blocklistEnabled) {
                Log.i(LOG_TAG_VPN, "local-bravedns stamp set to null")
                tunnel?.braveDNS = null
                localBraveDns = null
                return
            }

            // Set the localBraveDns object
            setupLocalBraveDns()
            val stamp: String = persistentState.localBlocklistStamp
            Log.i(LOG_TAG_VPN, "local-bravedns stamp: $stamp")
            if (stamp.isEmpty() || localBraveDns == null) {
                // make localBraveDns as null when the stamp is empty
                localBraveDns = null
                return
            }

            // Set bravedns object to tunnel if the stamp and localBraveDns object is available.
            tunnel?.braveDNS = localBraveDns
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

    private fun setupLocalBraveDns() {
        if (localBraveDns != null) {
            Log.i(LOG_TAG_VPN, "Local brave dns object already available")
            return
        }
        try {
            // FIXME: canonical path may go missing but is unhandled
            val path: String = VpnController.getBraveVpnService()?.filesDir?.canonicalPath + File.separator + persistentState.localBlocklistTimestamp
            localBraveDns = Dnsx.newBraveDNSLocal(path + ONDEVICE_BLOCKLIST_FILE_TD_FILE,
                                                  path + ONDEVICE_BLOCKLIST_FILE_RD_FILE,
                                                  path + ONDEVICE_BLOCKLIST_FILE_BASIC_CONFIG,
                                                  path + ONDEVICE_BLOCKLIST_FILE_TAG_NAME)
        } catch (e: Exception) {
            Log.e(LOG_TAG_VPN, "Local brave dns set exception :${e.message}", e)
            // Set local blocklist enabled to false if there is a failure creating bravedns
            persistentState.blocklistEnabled = false
        }
    }

    companion object {

        suspend fun establish(tunFd: ParcelFileDescriptor?): GoVpnAdapter? {
            if (tunFd == null) return null
            return GoVpnAdapter(tunFd)
        }

        fun getIpString(context: Context?, url: String?): String {
            val res: Resources? = context?.resources
            val urls: Array<out String>? = res?.getStringArray(R.array.urls)
            val ips: Array<out String>? = res?.getStringArray(R.array.ips)
            if (urls == null) return ""
            for (i in urls.indices) {
                // TODO: Consider relaxing this equality condition to a match on just the domain.
                // Code has been modified from equals to contains to match on the domain. Need to
                // come up with some other solution to check by extracting the domain name
                if (urls[i].contains((url.toString()))) {
                    if (ips != null) return ips[i]
                }
            }
            return ""
        }
    }

    init {
        this.tunFd = tunFd
    }

    private fun io(s: String, f: suspend () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch(CoroutineName(s)) {
            f()
        }
    }
}
