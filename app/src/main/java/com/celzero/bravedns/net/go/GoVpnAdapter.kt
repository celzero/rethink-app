/*
Copyright 2020 RethinkDNS developers

Copyright 2019 Jigsaw Operations LLC

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.celzero.bravedns.net.go

import android.content.Context
import android.content.res.Resources
import android.net.VpnService
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
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.DNSConfigureWebViewActivity
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.FILE_BASIC_CONFIG
import com.celzero.bravedns.util.Constants.Companion.FILE_RD_FILE
import com.celzero.bravedns.util.Constants.Companion.FILE_TAG_NAME
import com.celzero.bravedns.util.Constants.Companion.FILE_TD_FILE
import com.celzero.bravedns.util.LoggerConstants
import com.celzero.bravedns.util.Utilities.Companion.prepareServersToRemove
import com.celzero.bravedns.util.Utilities.Companion.showToastUiCentered
import dnsx.BraveDNS
import dnsx.Dnsx
import doh.Transport
import intra.Tunnel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import protect.Blocker
import protect.Protector
import settings.Settings
import tun2socks.Tun2socks
import java.io.File
import java.io.IOException
import java.util.*

/**
 * This is a VpnAdapter that captures all traffic and routes it through a go-tun2socks instance with
 * custom logic for Intra.
 */
class GoVpnAdapter(private var tunFd: ParcelFileDescriptor?,
                   private val vpnService: BraveVPNService) : KoinComponent {

    private val persistentState by inject<PersistentState>()
    private val appMode by inject<AppMode>()

    // The VPN service and tun2socks must agree on the layout of the network.  By convention, we
    // assign the following values to the final byte of an address within a subnet.
    // Value of the final byte, to be substituted into the template.
    private enum class LanIp(private val value: Int) {
        GATEWAY(1), ROUTER(2), DNS(3);

        fun make(template: String): String {
            return String.format(Locale.ROOT, template, value)
        }
    }

    // The Intra session object from go-tun2socks.  Initially null.
    private var tunnel: Tunnel? = null
    private var listener: GoIntraListener? = null
    private var localBraveDns: BraveDNS? = null

    @Synchronized
    fun start(tunnelOptions: TunnelOptions) {
        connectTunnel(tunnelOptions)
    }

    private fun connectTunnel(tunnelOptions: TunnelOptions) {
        if (tunnel != null) {
            return
        }
        // VPN parameters
        val fakeDns = "$FAKE_DNS_IP:$DNS_DEFAULT_PORT"

        // Strip leading "/" from ip:port string.
        listener = GoIntraListener(VpnController.getBraveVpnService())

        //TODO : The below statement is incorrect, adding the dohURL as const for testing
        try {
            // TODO : #321 As of now the app fallback on an unmaintained url. Requires a rewrite as
            // part of v055
            val dohURL: String = getDohUrl()
            val transport: Transport = makeDohTransport(dohURL)
            Log.i(LoggerConstants.LOG_TAG_VPN,
                  "Connect tunnel with url " + dohURL + ", dnsMode: " + tunnelOptions.dnsMode + ", blockMode: " + tunnelOptions.firewallMode + ", proxyMode: " + tunnelOptions.proxyMode)

            if (tunFd == null) return

            tunnel = Tun2socks.connectIntraTunnel(tunFd!!.fd.toLong(), fakeDns, transport,
                                                  getProtector(), getBlocker(), listener)
            if (DEBUG) {
                Tun2socks.enableDebugLog()
            }
            setBraveMode(tunnelOptions.dnsMode, dohURL)
            setTunnelMode(tunnelOptions)
        } catch (e: Exception) {
            Log.e(LoggerConstants.LOG_TAG_VPN, e.message, e)
            if (tunnel != null) tunnel?.disconnect()
            tunnel = null
        }
    }

    private fun isRethinkUrl(url: String): Boolean {
        return url.contains(Constants.BRAVE_BASIC_URL) || url.contains(Constants.RETHINK_BASIC_URL)
    }

    private fun isSock5Proxy(proxyMode: Long): Boolean {
        return Settings.ProxyModeSOCKS5 == proxyMode
    }

    private fun isOrbotProxy(proxyMode: Long): Boolean {
        return Constants.ORBOT_PROXY == proxyMode
    }

    private fun isDoh(dnsMode: Long): Boolean {
        return Settings.DNSModeIP == dnsMode || Settings.DNSModePort == dnsMode
    }

    private fun setTunnelMode(tunnelOptions: TunnelOptions) {
        if (!tunnelOptions.dnsMode.isDnscrypt()) {
            if (tunnelOptions.proxyMode.isOrbotProxy()) {
                tunnel?.setTunMode(tunnelOptions.dnsMode.mode, tunnelOptions.firewallMode.mode,
                                   Settings.ProxyModeSOCKS5)
            } else {
                tunnel?.setTunMode(tunnelOptions.dnsMode.mode, tunnelOptions.firewallMode.mode,
                                   tunnelOptions.proxyMode.mode)
            }
            checkForCryptRemoval()
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
        if (BuildConfig.DEBUG) Log.d(LoggerConstants.LOG_TAG_VPN, "Set brave dns mode initiated")
        // Set brave mode only if the selected DNS is either DoH or DnsCrypt
        // No need to set the brave mode in case of DNS Proxy (implementation pending in underlying Go library).
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
        if (!isRethinkUrl(dohURL) || localBraveDns != null) {
            return
        }
        try {
            val path: String = getRemoteBlocklistFilePath() ?: return
            val remoteFile = File(path)
            if (remoteFile.exists()) {
                tunnel?.braveDNS = Dnsx.newBraveDNSRemote(path)
                Log.i(LoggerConstants.LOG_TAG_VPN, "Enabled remote bravedns mode")
            } else {
                Log.w(LoggerConstants.LOG_TAG_VPN, "Remote blocklist filetag.json does not exists")
            }
        } catch (ex: Exception) {
            Log.e(LoggerConstants.LOG_TAG_VPN, "Cannot set remote bravedns:" + ex.message, ex)
        }
    }

    private fun getRemoteBlocklistFilePath(): String? {
        return try {
            VpnController.getBraveVpnService()?.filesDir?.canonicalPath + File.separator + DNSConfigureWebViewActivity.BLOCKLIST_REMOTE_FOLDER_NAME + File.separator + persistentState.remoteBlocklistDownloadTime  + File.separator + Constants.FILE_TAG_JSON
        } catch (e: IOException) {
            Log.e(LoggerConstants.LOG_TAG_VPN, "Could not fetch remote blocklist: " + e.message, e)
            null
        }
    }

    private fun checkForCryptRemoval() {
        try {
            if (tunnel?.dnsCryptProxy != null) {
                tunnel?.stopDNSCryptProxy()
                Log.i(LoggerConstants.LOG_TAG_VPN, "connect-tunnel - stopDNSCryptProxy")
            }
        } catch (e: Exception) {
            Log.e(LoggerConstants.LOG_TAG_VPN, "stop dnscrypt failure: " + e.message, e)
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
                val response: String? = tunnel?.startDNSCryptProxy(servers, routes, listener)
                Log.i(LoggerConstants.LOG_TAG_VPN,
                      "startDNSCryptProxy: $servers,$routes, Response: $response")
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
                Log.i(LoggerConstants.LOG_TAG_VPN,
                      "DNSCrypt with routes: $routes, relay count: $relayCount, servers: $servers, removed count:$serverCount")
            }
        } catch (ex: Exception) {
            Log.e(LoggerConstants.LOG_TAG_VPN, "connect-tunnel: dns crypt failure", ex)
        }
        if (servers.isNotEmpty()) {
            refreshCrypt(tunnelOptions)
        }
    }

    private fun setDNSProxy() {
        try {
            val dnsProxy: DNSProxyEndpoint = appMode.getConnectedProxyDetails()
            if (DEBUG) Log.d(LoggerConstants.LOG_TAG_VPN,
                             "setDNSProxy mode set: " + dnsProxy.proxyIP + ", " + dnsProxy.proxyPort)
            tunnel?.startDNSProxy(dnsProxy.proxyIP, dnsProxy.proxyPort.toString())
        } catch (e: Exception) {
            Log.e(LoggerConstants.LOG_TAG_VPN, "connect-tunnel: dns proxy" + e.message, e)
        }
    }

    /**
     * TODO - Move these code to common place and set the tunnel mode and
     * other parameters. Return the tunnel to the adapter.
     */
    private fun setProxyMode(userName: String?, password: String?, ipAddress: String?, port: Int) {
        try {
            tunnel?.startProxy(userName, password, ipAddress, port.toString())
            Log.i(LoggerConstants.LOG_TAG_VPN, "Proxy mode set: $userName$ipAddress$port")
        } catch (e: Exception) {
            Log.e(LoggerConstants.LOG_TAG_VPN, "connect-tunnel: proxy", e)
        }
    }

    private fun refreshCrypt(tunnelOptions: TunnelOptions) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (tunnel?.dnsCryptProxy != null) {
                    val liveServers: String? = tunnel?.dnsCryptProxy?.refresh()
                    appMode.updateDnscryptLiveServers(liveServers)
                    Log.i(LoggerConstants.LOG_TAG_VPN, "Refresh LiveServers: $liveServers")
                    if (liveServers.isNullOrEmpty()) {
                        Log.i(LoggerConstants.LOG_TAG_VPN,
                              "No live servers, falling back to default DoH mode")
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
                        Log.i(LoggerConstants.LOG_TAG_VPN,
                              "connect crypt tunnel set with mode: " + Settings.DNSModeCryptPort + tunnelOptions.firewallMode + tunnelOptions.proxyMode)
                    }
                } else {
                    handleDnscryptFailure()
                }
            } catch (e: Exception) {
                handleDnscryptFailure()
                Log.e(LoggerConstants.LOG_TAG_VPN, "connect-tunnel: dns crypt", e)
            }
        }
    }

    private suspend fun handleDnscryptFailure() {
        if (persistentState.dnsType == Constants.PREF_DNS_MODE_DNSCRYPT) {
            appMode.setDefaultConnection()
            showDnscryptConnectionFailureToast()
            Log.i(LoggerConstants.LOG_TAG_VPN,
                  "connect-tunnel: failure of dns crypt falling back to doh")
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

    private fun setSocks5TunnelMode(proxyMode: AppMode.ProxyMode) {
        val socks5: ProxyEndpoint?
        if (proxyMode.isOrbotProxy()) {
            socks5 = appMode.getOrbotProxyDetails()
        } else {
            socks5 = appMode.getSocks5ProxyDetails()
        }
        if (socks5 == null) {
            Log.w(LoggerConstants.LOG_TAG_VPN,
                  "could not fetch socks5 details for proxyMode: $proxyMode")
            return
        }
        setProxyMode(socks5.userName, socks5.password, socks5.proxyIP, socks5.proxyPort)
        Log.i(LoggerConstants.LOG_TAG_VPN,
              "Socks5 mode set: " + socks5.proxyIP + "," + socks5.proxyPort)
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
        return vpnService
    }

    private fun getBlocker(): Blocker {
        return vpnService
    }

    @Synchronized
    fun close() {
        if (tunnel != null) {
            tunnel?.disconnect()
            Log.i(LoggerConstants.LOG_TAG_VPN, "Tunnel disconnect")
        }
        if (tunFd != null) {
            try {
                tunFd?.close()
            } catch (e: IOException) {
                Log.e(LoggerConstants.LOG_TAG_VPN, e.message, e)
            }
        }
        tunFd = null
        tunnel = null
    }

    @Throws(Exception::class)
    private fun makeDohTransport(url: String?): Transport {
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
    fun updateDohUrl(tunnelOptions: TunnelOptions) {
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
            val dohTransport: Transport = makeDohTransport(dohURL)
            tunnel?.dns = dohTransport
            Log.i(LoggerConstants.LOG_TAG_VPN,
                  "Connect tunnel with url " + dohURL + ", dnsMode: " + tunnelOptions.dnsMode + ", blockMode:" + tunnelOptions.firewallMode + ", proxyMode:" + tunnelOptions.proxyMode)

            // Set brave dns to tunnel - Local/Remote
            setBraveMode(tunnelOptions.dnsMode, dohURL)
            setTunnelMode(tunnelOptions)
        } catch (e: Exception) {
            Log.e(LoggerConstants.LOG_TAG_VPN, e.message, e)
            tunnel?.disconnect()
            tunnel = null
        }
    }

    private fun getDohUrl(): String {
        var dohURL: String? = ""
        try {
            dohURL = appMode.getDOHDetails()?.dohURL
        } catch (e: Exception) {
            Log.e(LoggerConstants.LOG_TAG_VPN, "error while fetching doh details", e)
        }
        if (dohURL == null) dohURL = "https://basic.bravedns.com/dns-query"
        return dohURL
    }

    private fun setBraveDNSLocalMode() {
        try {
            if (!persistentState.blocklistFilesDownloaded || !persistentState.blocklistEnabled) {
                Log.i(LoggerConstants.LOG_TAG_VPN, "local stamp is set to null(on GO)")
                tunnel?.braveDNS = null
                localBraveDns = null
                return
            }

            // Set the localBraveDns object
            setupLocalBraveDns()
            val stamp: String = persistentState.localBlocklistStamp
            Log.i(LoggerConstants.LOG_TAG_VPN, "app dns mode is set with local stamp: $stamp")
            if (stamp.isEmpty() || localBraveDns == null) {
                // make localBraveDns as null when the stamp is empty
                localBraveDns = null
                return
            }

            // Set bravedns object to tunnel if the stamp and localBraveDns object is available.
            tunnel?.braveDNS = localBraveDns
            tunnel?.braveDNS?.stamp = stamp
        } catch (ex: Exception) {
            Log.e(LoggerConstants.LOG_TAG_VPN,
                  "Exception while setting brave dns for local:" + ex.message, ex)
        }
    }

    private fun setupLocalBraveDns() {
        if (localBraveDns != null) {
            Log.i(LoggerConstants.LOG_TAG_VPN, "Local brave dns object already available")
            return
        }
        try {
            val path: String = VpnController.getBraveVpnService()?.filesDir?.canonicalPath + File.separator + persistentState.blocklistDownloadTime
            localBraveDns = Dnsx.newBraveDNSLocal(path + FILE_TD_FILE, path + FILE_RD_FILE,
                                                  path + FILE_BASIC_CONFIG, path + FILE_TAG_NAME)
        } catch (e: Exception) {
            Log.e(LoggerConstants.LOG_TAG_APP_MODE, "Local brave dns set exception :\${e.message}",
                  e)
            // Set local blocklist enabled to false if there is a failure creating bravedns
            // from GO.
            persistentState.blocklistEnabled = false
        }
    }

    companion object {
        // This value must match the hardcoded MTU in outline-go-tun2socks.
        // TODO: Make outline-go-tun2socks's MTU configurable.
        private val VPN_INTERFACE_MTU: Int = 1500
        private val DNS_DEFAULT_PORT: Int = 53

        // IPv4 VPN constants
        private val IPV4_TEMPLATE: String = "10.111.222.%d"
        private val IPV4_PREFIX_LENGTH: Int = 24
        val FAKE_DNS_IP: String = LanIp.DNS.make(IPV4_TEMPLATE)

        fun establish(vpnService: BraveVPNService, appMode: AppMode): GoVpnAdapter? {
            val tunFd: ParcelFileDescriptor = establishVpn(vpnService, appMode) ?: return null
            return GoVpnAdapter(tunFd, vpnService)
        }

        private fun establishVpn(vpnService: BraveVPNService,
                                 appMode: AppMode): ParcelFileDescriptor? {
            try {
                val builder: VpnService.Builder = vpnService.newBuilder().setSession(
                    "RethinkDNS").setMtu(VPN_INTERFACE_MTU).addAddress(
                    LanIp.GATEWAY.make(IPV4_TEMPLATE), IPV4_PREFIX_LENGTH)
                if (appMode.getBraveMode().isDnsMode()) {
                    builder.addRoute(LanIp.DNS.make(IPV4_TEMPLATE), 32)
                    builder.addDnsServer(LanIp.DNS.make(IPV4_TEMPLATE))
                } else if (appMode.getBraveMode().isFirewallMode()) {
                    builder.addRoute(Constants.UNSPECIFIED_IP, Constants.UNSPECIFIED_PORT)
                } else {
                    builder.addDnsServer(LanIp.DNS.make(IPV4_TEMPLATE))
                    builder.addRoute(Constants.UNSPECIFIED_IP, Constants.UNSPECIFIED_PORT)
                }
                return builder.establish()
            } catch (e: Exception) {
                Log.e(LoggerConstants.LOG_TAG_VPN, e.message, e)
                return null
            }
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
}
