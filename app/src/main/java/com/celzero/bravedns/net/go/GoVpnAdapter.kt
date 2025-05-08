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

import Logger
import Logger.LOG_TAG_PROXY
import Logger.LOG_TAG_VPN
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.VpnService.NOTIFICATION_SERVICE
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import backend.Backend
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.data.AppConfig.Companion.FALLBACK_DNS
import com.celzero.bravedns.data.AppConfig.TunnelOptions
import com.celzero.bravedns.database.DnsCryptRelayEndpoint
import com.celzero.bravedns.database.ProxyEndpoint
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.BraveVPNService.Companion.NW_ENGINE_NOTIFICATION_ID
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.ProxyManager.ID_WG_BASE
import com.celzero.bravedns.service.RethinkBlocklistManager
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.ui.activity.AppLockActivity
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.MAX_ENDPOINT
import com.celzero.bravedns.util.Constants.Companion.ONDEVICE_BLOCKLIST_FILE_TAG
import com.celzero.bravedns.util.Constants.Companion.REMOTE_BLOCKLIST_DOWNLOAD_FOLDER_NAME
import com.celzero.bravedns.util.Constants.Companion.RETHINKDNS_DOMAIN
import com.celzero.bravedns.util.Constants.Companion.RETHINK_BASE_URL_MAX
import com.celzero.bravedns.util.Constants.Companion.RETHINK_BASE_URL_SKY
import com.celzero.bravedns.util.Constants.Companion.UNSPECIFIED_IP_IPV4
import com.celzero.bravedns.util.Constants.Companion.UNSPECIFIED_IP_IPV6
import com.celzero.bravedns.util.InternetProtocol
import com.celzero.bravedns.util.UIUtils.getAccentColor
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.blocklistDir
import com.celzero.bravedns.util.Utilities.blocklistFile
import com.celzero.bravedns.util.Utilities.isAtleastO
import com.celzero.bravedns.util.Utilities.isAtleastS
import com.celzero.bravedns.util.Utilities.isPlayStoreFlavour
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.celzero.bravedns.wireguard.Config
import com.celzero.bravedns.wireguard.WgHopManager
import intra.Intra
import intra.Tunnel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import settings.Settings
import java.net.URI
import kotlin.text.substring

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
        Logger.i(LOG_TAG_VPN, "$TAG connect tunnel with new params")
        tunnel =
            Intra.connect(
                tunFd.fd.toLong(),
                opts.mtu.toLong(),
                opts.fakeDns,
                defaultDns,
                opts.bridge
            ) // may throw exception
        setTunMode(opts)
    }

    suspend fun initResolverProxiesPcap(opts: TunnelOptions) {
        Logger.v(LOG_TAG_VPN, "$TAG initResolverProxiesPcap")
        if (!tunnel.isConnected) {
            Logger.i(LOG_TAG_VPN, "$TAG no tunnel, skip init resolver proxies")
            return
        }

        // setTcpProxyIfNeeded()
        // no need to add default in init as it is already added in connect
        // addDefaultTransport(appConfig.getDefaultDns())
        setRoute(opts)
        // TODO: ideally the values required for transport, alg and rdns should be set in the
        // opts itself.
        setRDNS()
        addTransport()
        setWireguardTunnelModeIfNeeded(opts.tunProxyMode)
        setSocks5TunnelModeIfNeeded(opts.tunProxyMode)
        setHttpProxyIfNeeded(opts.tunProxyMode)
        setPcapMode(appConfig.getPcapFilePath())
        setDnsAlg()
        notifyLoopback()
        setDialStrategy()
        setTransparency()
        undelegatedDomains()
        setExperimentalSettings()
        // added for testing, use if needed
        if (DEBUG) panicAtRandom(false) else panicAtRandom(false)
        Logger.v(LOG_TAG_VPN, "$TAG initResolverProxiesPcap done")
    }

    suspend fun setPcapMode(pcapFilePath: String) {
        Logger.v(LOG_TAG_VPN, "$TAG setPcapMode")
        if (!tunnel.isConnected) {
            Logger.i(LOG_TAG_VPN, "$TAG no tunnel, skip set pcap mode")
            return
        }
        try {
            Logger.i(LOG_TAG_VPN, "$TAG set pcap mode: $pcapFilePath")
            tunnel.setPcap(pcapFilePath)
        } catch (e: Exception) {
            Logger.w(LOG_TAG_VPN, "$TAG err setting pcap($pcapFilePath): ${e.message}", e)
        }
        Logger.v(LOG_TAG_VPN, "$TAG setPcapMode done")
    }

    /*
    // commented as the setCrashFd is not working as expected, need to revisit
    private suspend fun setCrashFd() {
        Logger.v(LOG_TAG_VPN, "GoVpnAdapter setCrashFd")
        val crashFd = EnhancedBugReport.getFileToWrite(context)
        if (crashFd != null) {
            val set = Intra.setCrashFd(crashFd.absolutePath)
            Logger.i(LOG_TAG_VPN, "set crash fd: $crashFd, set? $set")
        } else {
            Logger.w(LOG_TAG_VPN, "crash fd is null, cannot set")
        }
    }*/

    private suspend fun setRoute(tunnelOptions: TunnelOptions) {
        Logger.v(LOG_TAG_VPN, "$TAG setRoute")
        if (!tunnel.isConnected) {
            Logger.i(LOG_TAG_VPN, "$TAG no tunnel, skip set route")
            return
        }
        try {
            // setRoute can throw exception iff preferredEngine is invalid, which is not possible
            // Log.d(LOG_TAG_VPN, "set route: ${tunnelOptions.preferredEngine.value()}")
            // tunnel.setRoute(tunnelOptions.preferredEngine.value())
            // above code is commented as the route is now set as default instead of preferred
            tunnel.setRoute(InternetProtocol.IPv46.value())

            // on route change, set the tun mode again for ptMode
            setTunMode(tunnelOptions)

            Logger.i(LOG_TAG_VPN, "$TAG set route: ${InternetProtocol.IPv46.value()}")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG err setting route: ${e.message}", e)
        }
        Logger.v(LOG_TAG_VPN, "$TAG setRoute done")
    }

    suspend fun addTransport() {
        Logger.v(LOG_TAG_VPN, "$TAG addTransport")
        if (!tunnel.isConnected) {
            Logger.i(LOG_TAG_VPN, "$TAG no tunnel, skip add transport")
            return
        }

        // TODO: #321 As of now there is no block free transport for dns types other than Rethink.
        // introduce block free transport for other dns types when there is a UI to configure the
        // block free transport
        // always set the block free transport before setting the transport to the resolver
        // because of the way the alg is implemented in the go code.

        // TODO: should show notification if the transport is not added? as of now, it will just
        // show a toast and fail silently
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
                    Logger.i(LOG_TAG_VPN, "$TAG adding blockfree url: $blockfreeUrl")
                    addRdnsTransport(Backend.BlockFree, blockfreeUrl)
                } else {
                    Logger.i(LOG_TAG_VPN, "$TAG no blockfree url found")
                    // if blockfree url is not available, do not proceed further
                    return
                }
                if (!rdnsRemoteUrl.isNullOrEmpty()) {
                    Logger.i(LOG_TAG_VPN, "$TAG adding rdns remote url: $rdnsRemoteUrl")
                    addRdnsTransport(Backend.Preferred, rdnsRemoteUrl)
                } else {
                    Logger.i(LOG_TAG_VPN, "$TAG no rdns remote url found")
                }
            }
        }
        Logger.v(LOG_TAG_VPN, "$TAG addTransport done")
    }

    private suspend fun addDohTransport(id: String) {
        Logger.v(LOG_TAG_VPN, "$TAG addDohTransport")
        var url: String? = null
        try {
            val doh = appConfig.getDOHDetails()
            url = doh?.dohURL
            val ips: String = getIpString(context, url)
            // change the url from https to http if the isSecure is false
            if (doh?.isSecure == false) {
                Logger.d(LOG_TAG_VPN, "$TAG changing url from https to http for $url")
                url = url?.replace("https", "http")
            }
            // add replaces the existing transport with the same id if successful
            // so no need to remove the transport before adding
            Intra.addDoHTransport(tunnel, id, url, ips)
            Logger.i(LOG_TAG_VPN, "$TAG new doh: $id (${doh?.dohName}), url: $url, ips: $ips")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG connect-tunnel: doh failure, url: $url", e)
            getResolver()?.remove(id)
            showDnsFailureToast(url ?: "")
        }
        Logger.v(LOG_TAG_VPN, "$TAG addDohTransport done")
    }

    private suspend fun addDotTransport(id: String) {
        Logger.v(LOG_TAG_VPN, "$TAG addDotTransport, id: $id")
        var url: String? = null
        try {
            val dot = appConfig.getDOTDetails()
            url = dot?.url
            // if tls is present, remove it and pass it to getIpString
            val ips: String = getIpString(context, url?.replace("tls://", ""))
            if (dot?.isSecure == true && url?.startsWith("tls") == false) {
                Logger.d(LOG_TAG_VPN, "$TAG adding tls to url for $url")
                // add tls to the url if isSecure is true and the url does not start with tls
                url = "tls://$url"
            }
            // add replaces the existing transport with the same id if successful
            // so no need to remove the transport before adding
            Intra.addDoTTransport(tunnel, id, url, ips)
            Logger.i(LOG_TAG_VPN, "$TAG new dot: $id (${dot?.name}), url: $url, ips: $ips")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG connect-tunnel: dot failure, url: $url", e)
            getResolver()?.remove(id)
            showDnsFailureToast(url ?: "")
        }
        Logger.v(LOG_TAG_VPN, "$TAG addDotTransport done")
    }

    private suspend fun addOdohTransport(id: String) {
        Logger.v(LOG_TAG_VPN, "$TAG addOdohTransport, id: $id")
        var resolver: String? = null
        try {
            val odoh = appConfig.getODoHDetails()
            val proxy = odoh?.proxy
            resolver = odoh?.resolver
            val proxyIps = ""
            // add replaces the existing transport with the same id if successful
            // so no need to remove the transport before adding
            Intra.addODoHTransport(tunnel, id, proxy, resolver, proxyIps)
            Logger.i(LOG_TAG_VPN, "$TAG new odoh: $id (${odoh?.name}), p: $proxy, r: $resolver")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG connect-tunnel: odoh failure, res: $resolver", e)
            getResolver()?.remove(id)
            showDnsFailureToast(resolver ?: "")
        }
        Logger.v(LOG_TAG_VPN, "$TAG addOdohTransport done")
    }

    private suspend fun addDnscryptTransport(id: String) {
        Logger.v(LOG_TAG_VPN, "$TAG addDnscryptTransport, id: $id")
        try {
            val dc = appConfig.getConnectedDnscryptServer()
            val url = dc.dnsCryptURL
            // add replaces the existing transport with the same id if successful
            // so no need to remove the transport before adding
            Intra.addDNSCryptTransport(tunnel, id, url)
            Logger.i(LOG_TAG_VPN, "$TAG new dnscrypt: $id (${dc.dnsCryptName}), url: $url")
            setDnscryptRelaysIfAny() // is expected to catch exceptions
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG connect-tunnel: dns crypt failure for $id", e)
            getResolver()?.remove(id)
            removeDnscryptRelaysIfAny()
            showDnscryptConnectionFailureToast()
        }
        Logger.v(LOG_TAG_VPN, "$TAG addDnscryptTransport done")
    }

    private suspend fun addDnsProxyTransport(id: String) {
        Logger.v(LOG_TAG_VPN, "$TAG addDnsProxyTransport, id: $id")
        try {
            val dnsProxy = appConfig.getSelectedDnsProxyDetails() ?: return
            // add replaces the existing transport with the same id if successful
            // so no need to remove the transport before adding
            Intra.addDNSProxy(tunnel, id, dnsProxy.proxyIP, dnsProxy.proxyPort.toString())
            Logger.i(
                LOG_TAG_VPN,
                "$TAG new dns proxy: $id(${dnsProxy.proxyName}), ip: ${dnsProxy.proxyIP}, port: ${dnsProxy.proxyPort}"
            )
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG connect-tunnel: dns proxy failure", e)
            getResolver()?.remove(id)
            showDnsProxyConnectionFailureToast()
        }
        Logger.v(LOG_TAG_VPN, "$TAG addDnsProxyTransport done")
    }

    private suspend fun addRdnsTransport(id: String, url: String) {
        Logger.v(LOG_TAG_VPN, "$TAG addRdnsTransport, id: $id, url: $url")
        try {
            val useDot = false
            val ips: String = getIpString(context, url)
            val convertedUrl = getRdnsUrl(url) ?: return
            if (url.contains(RETHINK_BASE_URL_SKY) || !useDot) {
                Intra.addDoHTransport(tunnel, id, convertedUrl, ips)
                Logger.i(LOG_TAG_VPN, "$TAG new doh (rdns): $id, url: $convertedUrl, ips: $ips")
            } else {
                Intra.addDoTTransport(tunnel, id, convertedUrl, ips)
                Logger.i(LOG_TAG_VPN, "$TAG new dot (rdns): $id, url: $convertedUrl, ips: $ips")
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG connect-tunnel: rdns failure, url: $url", e)
            getResolver()?.remove(id)
            showDnsFailureToast(url)
        }
        Logger.v(LOG_TAG_VPN, "$TAG addRdnsTransport done")
    }

    private suspend fun getRdnsUrl(url: String, useDot: Boolean = false): String? {
        val tls = "tls://"
        val default = "dns-query"
        // do not proceed if rethinkdns.com is not available
        if (!url.contains(RETHINKDNS_DOMAIN)) return null

        // if url is MAX, convert it to dot format, DOT format stamp.max.rethinkdns.com
        // if url is SKY, convert it to doh format, DOH format https://sky.rethikdns.com/stamp

        // for blockfree, the url is https://max.rethinkdns.com/dns-query
        if (url == Constants.BLOCK_FREE_DNS_MAX && useDot) {
            return "$tls$MAX_ENDPOINT.$RETHINKDNS_DOMAIN"
        } else if (url == Constants.BLOCK_FREE_DNS_SKY || url == Constants.BLOCK_FREE_DNS_MAX) {
            return url
        } else {
            // no-op, pass-through
        }
        val stamp = getRdnsStamp(url)
        return if (url.contains(MAX_ENDPOINT) && useDot) {
            // if the stamp is empty or "dns-query", then remove it
            if (stamp.isEmpty() || stamp == default) {
                return "$tls$MAX_ENDPOINT.$RETHINKDNS_DOMAIN"
            }
            "$tls$stamp.$MAX_ENDPOINT.$RETHINKDNS_DOMAIN"
        } else {
            if (url.contains(MAX_ENDPOINT)) {
                return "$RETHINK_BASE_URL_MAX$stamp"
            } else {
                return "$RETHINK_BASE_URL_SKY$stamp"
            }
        }
    }

    suspend fun unlink() {
        if (!tunnel.isConnected) {
            Logger.i(LOG_TAG_VPN, "$TAG no tunnel, skip unlink")
            return
        }
        try {
            tunnel.unlink()
            Logger.i(LOG_TAG_VPN, "$TAG tunnel unlinked")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG err dispose: ${e.message}", e)
        }
    }

    private suspend fun getRdnsStamp(url: String): String {
        val s = url.split(RETHINKDNS_DOMAIN)[1]
        val stamp =
            if (s.startsWith("/")) {
                s.substring(1)
            } else {
                s
            }
        return getBase32Stamp(stamp)
    }

    private suspend fun getBase32Stamp(stamp: String): String {
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
            Logger.w(LOG_TAG_VPN, "$TAG err get base32 stamp: ${e.message}")
        }
        return b32Stamp ?: stamp
    }

    fun setTunMode(tunnelOptions: TunnelOptions) {
        if (!tunnel.isConnected) {
            Logger.i(LOG_TAG_VPN, "$TAG no tunnel, skip set-tun-mode")
            return
        }
        try {
            Settings.setTunMode(
                tunnelOptions.tunDnsMode.mode.toInt(),
                tunnelOptions.tunFirewallMode.mode,
                tunnelOptions.ptMode.id
            )
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG err set tun mode: ${e.message}", e)
        }
    }

    suspend fun setRDNS() {
        // Set brave dns to tunnel - Local/Remote
        Logger.d(LOG_TAG_VPN, "$TAG set brave dns to tunnel (local/remote)")

        // enable local blocklist if enabled
        if (persistentState.blocklistEnabled && !isPlayStoreFlavour()) {
            setRDNSLocal()
        } else {
            // remove local blocklist, if any
            getRDNSResolver()?.setRdnsLocal(null, null, null, null)
            Logger.i(LOG_TAG_VPN, "$TAG local-rdns disabled")
        }

        // always set the remote blocklist
        setRDNSRemote()
    }

    private suspend fun setRDNSRemote() {
        Logger.d(LOG_TAG_VPN, "$TAG init remote rdns mode")
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
                Logger.i(LOG_TAG_VPN, "$TAG remote-rdns enabled")
            } else {
                Logger.w(LOG_TAG_VPN, "$TAG filetag.json for remote-rdns missing")
            }
        } catch (ex: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG cannot set remote-rdns: ${ex.message}", ex)
        }
    }

    private suspend fun setDnscryptRelaysIfAny() {
        val routes: String = appConfig.getDnscryptRelayServers()
        routes.split(",").forEach {
            if (it.isBlank()) return@forEach

            Logger.i(LOG_TAG_VPN, "$TAG new dnscrypt relay: $it")
            try {
                Intra.addDNSCryptRelay(tunnel, it) // entire url is the id
            } catch (ex: Exception) {
                Logger.e(LOG_TAG_VPN, "$TAG connect-tunnel: dnscrypt failure", ex)
                appConfig.removeDnscryptRelay(it)
                getResolver()?.remove(it)
            }
        }
    }

    private suspend fun removeDnscryptRelaysIfAny() {
        val routes: String = appConfig.getDnscryptRelayServers()
        routes.split(",").forEach {
            if (it.isBlank()) return@forEach

            Logger.i(LOG_TAG_VPN, "$TAG remove dnscrypt relay: $it")
            try {
                // remove from appConfig, as this is not from ui, but from the tunnel start up
                appConfig.removeDnscryptRelay(it)
                getResolver()?.remove(it)
            } catch (ex: Exception) {
                Logger.w(LOG_TAG_VPN, "$TAG connect-tunnel: dnscrypt rmv failure", ex)
            }
        }
    }

    suspend fun addDnscryptRelay(relay: DnsCryptRelayEndpoint) {
        if (!tunnel.isConnected) {
            Logger.i(LOG_TAG_VPN, "$TAG no tunnel, skip add dnscrypt relay")
            return
        }
        try {
            Intra.addDNSCryptRelay(tunnel, relay.dnsCryptRelayURL)
            Logger.i(LOG_TAG_VPN, "$TAG new dnscrypt relay: ${relay.dnsCryptRelayURL}")
        } catch (e: Exception) {
            Logger.e(
                LOG_TAG_VPN,
                "$TAG connect-tunnel: dnscrypt relay failure: ${relay.dnsCryptRelayURL}",
                e
            )
            appConfig.removeDnscryptRelay(relay.dnsCryptRelayURL)
            getResolver()?.remove(relay.dnsCryptRelayURL)
        }
    }

    suspend fun removeDnscryptRelay(relay: DnsCryptRelayEndpoint) {
        if (!tunnel.isConnected) {
            Logger.i(LOG_TAG_VPN, "$TAG no tunnel, skip remove dnscrypt relay")
            return
        }
        try {
            // no need to remove from appConfig, as it is already removed
            val rmv = getResolver()?.remove(relay.dnsCryptRelayURL)
            Logger.i(LOG_TAG_VPN, "$TAG rmv dnscrypt relay: ${relay.dnsCryptRelayURL}, success? $rmv")
        } catch (e: Exception) {
            Logger.w(LOG_TAG_VPN, "$TAG connect-tunnel: dnscrypt rmv failure")
        }
    }

    /**
     * TODO - Move these code to common place and set the tunnel mode and other parameters. Return
     * the tunnel to the adapter.
     */
    private suspend fun setSocks5Proxy(
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
            val res = getProxies()?.addProxy(id, url)
            Logger.i(LOG_TAG_VPN, "$TAG socks5 set with url($id): $url, success? ${res != null}")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG connect-tunnel: err start proxy $userName@$ipAddress:$port", e)
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

        val wgConfigs: List<Config> = WireguardManager.getActiveConfigs()
        if (wgConfigs.isEmpty()) {
            Logger.i(LOG_TAG_VPN, "$TAG no active wg-configs found")
            return
        }
        // re-order the configs so that hops are added first
        val hops = WgHopManager.getAllHop()
        Logger.d(LOG_TAG_VPN, "$TAG total active proxies: ${wgConfigs.size}")
        // separate the id, hop has WG1 where id is 1
        val hopIds = hops.map { it.substring(ID_WG_BASE.length).toIntOrNull() }
        val hopConfigs = wgConfigs.filter { it.getId() in hopIds }
        val nonHopConfigs = wgConfigs.filter { it.getId() !in hopIds }
        Logger.i(LOG_TAG_VPN, "$TAG added wireguards with hops: ${hopConfigs.size}")
        // add hop wireguard first
        hopConfigs.forEach {
            val id = ID_WG_BASE + it.getId()
            addWgProxy(id)
        }
        Logger.i(LOG_TAG_VPN, "$TAG added wireguards without hops: ${nonHopConfigs.size}")
        nonHopConfigs.forEach {
            val id = ID_WG_BASE + it.getId()
            addWgProxy(id)
        }
    }

    private suspend fun addHopIfAny(origin: String) {
        // assumption: by this time the add call should have happened
        val hop = WgHopManager.getHop(origin)
        if (hop.isEmpty()) {
            Logger.i(LOG_TAG_VPN, "$TAG no hop found for $origin")
            return
        }
        try {
            tunnel.proxies.hop(hop, origin)
            Logger.i(LOG_TAG_VPN, "$TAG new hop for $origin -> $hop")
        } catch (e: Exception) {
            Logger.w(LOG_TAG_VPN, "$TAG err setting hop for $origin -> $hop; ${e.message}")
            showHopFailureNotification(origin, hop, err = e.message)
        }
    }

    fun hopStatus(src: String, hop: String): Pair<Long?, String> {
        return try {
            val status = tunnel.proxies.getProxy(src).router().via().status()
            Logger.v(LOG_TAG_VPN, "$TAG hop $src -> $hop; status: $status")
            Pair(status, "")
        } catch (e: Exception) {
            Logger.w(LOG_TAG_VPN, "$TAG hop failing for $src -> $hop; ${e.message}")
            Pair(null, e.message ?: "failure")
        }
    }

    fun removeHop(src: String): Pair<Boolean, String> {
        var err = ""
        try {
            tunnel.proxies.hop("", src)
            Logger.i(LOG_TAG_VPN, "$TAG removed hop for $src -> empty")
            return Pair(true, context.getString(R.string.config_add_success_toast))
        } catch (e: Exception) {
            Logger.w(LOG_TAG_VPN, "$TAG err removing hop: $src -> empty; ${e.message}")
            err = e.message ?: "err removing hop"
        }
        return Pair(false, err)
    }

    fun testHop(src: String, hop: String): Pair<Boolean, String> {
        var res = false
        var err = ""
        try {
            val s = tunnel.proxies.testHop(hop, src)
            // return empty on success, err msg on failure
            res = s.isEmpty()
            err = s
        } catch (e: Exception) {
            Logger.w(LOG_TAG_VPN, "$TAG err testing hop: $src -> $hop; ${e.message}")
            err = e.message ?: ""
        }
        return Pair(res, err)
    }

    private fun showHopFailureNotification(src: String, hop: String, err: String? = "") {
        val notifChannelId = "hop_failure_channel"
        ui {
            val notificationManager = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager

            if (isAtleastO()) {
                val channelName = context.getString(R.string.hop_failure_notification_title)
                val importance = NotificationManager.IMPORTANCE_DEFAULT
                val channel = NotificationChannel(notifChannelId, channelName, importance)
                notificationManager.createNotificationChannel(channel)
            }
            val msg = context.getString(R.string.hop_failure_toast, src, hop) + if (!err.isNullOrEmpty()) " ($err)" else ""
            showToastUiCentered(context.applicationContext, msg, Toast.LENGTH_LONG)
            val pendingIntent =
                Utilities.getActivityPendingIntent(
                    context,
                    Intent(context, AppLockActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    mutable = false
                )
            val builder =
                NotificationCompat.Builder(context, notifChannelId)
                    .setSmallIcon(R.drawable.ic_notification_icon)
                    .setContentTitle(msg)
                    .setContentIntent(pendingIntent)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
            builder.color = ContextCompat.getColor(context, getAccentColor(persistentState.theme))
            notificationManager.notify(NW_ENGINE_NOTIFICATION_ID, builder.build())
        }
    }

    private suspend fun setWireGuardDns(id: String) {
        try {
            val p = getProxies()?.getProxy(id)
            if (p == null) {
                Logger.w(LOG_TAG_VPN, "$TAG wireguard proxy not found for id: $id")
                return
            }
            Intra.addProxyDNS(tunnel, p) // dns transport has same id as the proxy (p)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG wireguard dns failure($id)", e)
            getResolver()?.remove(id)
            showDnsFailureToast(id)
        }
    }

    suspend fun setCustomProxy(tunProxyMode: AppConfig.TunProxyMode) {
        setSocks5TunnelModeIfNeeded(tunProxyMode)
        setHttpProxyIfNeeded(tunProxyMode)
    }

    private suspend fun setSocks5TunnelModeIfNeeded(tunProxyMode: AppConfig.TunProxyMode) {
        val socksEnabled = AppConfig.ProxyType.of(appConfig.getProxyType()).isSocks5Enabled()
        if (!socksEnabled) return

        val socks5: ProxyEndpoint? =
            if (tunProxyMode.isTunProxyOrbot()) {
                appConfig.getConnectedOrbotProxy()
            } else {
                appConfig.getSocks5ProxyDetails()
            }
        if (socks5 == null) {
            Logger.w(LOG_TAG_VPN, "$TAG could not fetch socks5 details for proxyMode: $tunProxyMode")
            return
        }
        setSocks5Proxy(
            tunProxyMode,
            socks5.userName,
            socks5.password,
            socks5.proxyIP,
            socks5.proxyPort
        )
        Logger.i(LOG_TAG_VPN, "$TAG Socks5 mode set: " + socks5.proxyIP + "," + socks5.proxyPort)
    }

    suspend fun getProxyStatusById(id: String): Pair<Long?, String> {
        return try {
            val status = getProxies()?.getProxy(id)?.status()
            Logger.d(LOG_TAG_VPN, "$TAG proxy status($id): $status")
            Pair(status, "")
        } catch (ex: Exception) {
            Logger.i(LOG_TAG_VPN, "$TAG err getProxy($id) ignored: ${ex.message}")
            Pair(null, ex.message ?: "")
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

            val p = getProxies()?.addProxy(id, httpProxyUrl)
            Logger.i(LOG_TAG_VPN, "$TAG http proxy set, url: $httpProxyUrl, success? ${p != null}")
        } catch (e: Exception) {
            if (tunProxyMode.isTunProxyOrbot()) {
                appConfig.removeProxy(AppConfig.ProxyType.HTTP, AppConfig.ProxyProvider.ORBOT)
            } else {
                appConfig.removeProxy(AppConfig.ProxyType.HTTP, AppConfig.ProxyProvider.CUSTOM)
            }
            Logger.e(LOG_TAG_VPN, "$TAG error setting http proxy: ${e.message}", e)
        }
    }

    suspend fun removeWgProxy(id: Int) {
        if (!tunnel.isConnected) {
            Logger.i(LOG_TAG_VPN, "$TAG no tunnel, skip refreshing wg")
            return
        }
        try {
            val wgId = ID_WG_BASE + id
            getProxies()?.removeProxy(wgId)
            getResolver()?.remove(wgId)
            Logger.i(LOG_TAG_VPN, "$TAG remove wireguard proxy with id: $id")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG err removing wireguard proxy: ${e.message}", e)
        }
    }

    suspend fun addWgProxy(id: String) {
        if (!tunnel.isConnected) {
            Logger.i(LOG_TAG_VPN, "$TAG no tunnel, skip add wg")
            return
        }
        try {
            val proxyId: Int? = id.substring(ID_WG_BASE.length).toIntOrNull()
            if (proxyId == null) {
                Logger.e(LOG_TAG_VPN, "$TAG invalid wg proxy id: $id")
                return
            }
            try {
                if (getProxies()?.getProxy(id) != null) {
                    Logger.i(LOG_TAG_VPN, "$TAG wg proxy already exists in tunnel $id")
                    return
                }
            } catch (ignored: Exception) {
                Logger.i(LOG_TAG_VPN, "$TAG wg proxy not found in tunnel $id, proceed adding")
            }

            val wgConfig = WireguardManager.getConfigById(proxyId)
            val isOneWg = WireguardManager.getOneWireGuardProxyId() == proxyId
            if (!isOneWg) checkAndAddProxyForHopIfNeeded(id)
            val skipListenPort = !isOneWg && persistentState.randomizeListenPort
            val wgUserSpaceString = wgConfig?.toWgUserspaceString(skipListenPort)
            val p = getProxies()?.addProxy(id, wgUserSpaceString)
            //if (isOneWg) setWireGuardDns(id)
            setWireGuardDns(id)
            // initiate a ping request to the wg proxy
            initiateWgPing(id)
            Logger.i(LOG_TAG_VPN, "$TAG added wireguard proxy with $id; success? ${p != null}")
            addHopIfAny(id)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG err adding wireguard proxy: ${e.message}", e)
            // do not auto remove failed wg proxy, let the user decide hop UI
            // WireguardManager.disableConfig(id)
            showWireguardFailureToast(
                e.message ?: context.getString(R.string.wireguard_connection_error)
            )
        }
    }

    private suspend fun checkAndAddProxyForHopIfNeeded(id: String) {
        // see if there is hop for this proxy, start the hop-wg if not started
        try {
            val hop = WgHopManager.getHop(id)
            val hopId = hop.substring(ID_WG_BASE.length).toIntOrNull()
            if (hopId == null) { // hop can be empty, in that case, return
                Logger.w(LOG_TAG_VPN, "$TAG no hop for $id")
                return
            }
            val config = WireguardManager.getConfigFilesById(hopId)
            if (config == null) {
                Logger.w(LOG_TAG_VPN, "$TAG no wg config found for id: $id, but hop: $hop")
                return
            }
            Logger.i(LOG_TAG_VPN, "$TAG start hop config: $hop")
            // this will enable the config and initiate the add proxy to the tunnel
            WireguardManager.enableConfig(config)
        } catch (ignored: Exception) { }
    }

    suspend fun closeConnections(connIds: List<String>) {
        if (!tunnel.isConnected) {
            Logger.i(LOG_TAG_VPN, "$TAG no tunnel, skip closeConns")
            return
        }

        if (connIds.isEmpty()) {
            val res = tunnel.closeConns("") // closes all connections
            Logger.i(LOG_TAG_VPN, "$TAG close all connections, res: $res")
        } else {
            val connIdsStr = connIds.joinToString(",")
            val res = tunnel.closeConns(connIdsStr)
            Logger.i(LOG_TAG_VPN, "$TAG close connection: $connIds, res: $res")
        }
    }

    suspend fun refreshProxies() {
        if (!tunnel.isConnected) {
            Logger.i(LOG_TAG_VPN, "$TAG no tunnel, skip refreshing proxies")
            return
        }
        try {
            val res = getProxies()?.refreshProxies()
            Logger.i(LOG_TAG_VPN, "$TAG refresh proxies: $res")
            // re-add the proxies if the its not available in the tunnel
            val wgConfigs: List<Config> = WireguardManager.getActiveConfigs()
            if (wgConfigs.isEmpty()) {
                Logger.i(LOG_TAG_VPN, "$TAG no active wg-configs found")
                return
            }
            wgConfigs.forEach {
                val id = ID_WG_BASE + it.getId()
                addWgProxy(id) // will not add if already present
            }
        } catch (e: Exception) {
            Logger.w(LOG_TAG_VPN, "$TAG err refreshing proxies: ${e.message}", e)
        }
    }

    suspend fun getProxyStats(id: String): backend.RouterStats? {
        return try {
            val stats = getProxies()?.getProxy(id)?.router()?.stat()
            stats
        } catch (e: Exception) {
            Logger.w(LOG_TAG_VPN, "$TAG err getting proxy stats($id): ${e.message}")
            null
        }
    }

    fun getNetStat(): backend.NetStat? {
        return try {
            val stat = tunnel.stat()
            Logger.i(LOG_TAG_VPN, "$TAG net stat: $stat")
            stat
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG err getting net stat: ${e.message}")
            null
        }
    }

    suspend fun getDnsStatus(id: String): Long? {
        try {
            val transport = getResolver()?.get(id)
            val tid = transport?.id()
            val status = transport?.status()
            // some special transports like blockfree, preferred, alg etc are handled specially.
            // in those cases, if the transport id is not there, it will serve the default transport
            // so return null in those cases
            if (tid != id) {
                Logger.d(LOG_TAG_VPN, "$TAG dns status; not desired transport($id), return null")
                return null
            }
            Logger.d(LOG_TAG_VPN, "$TAG dns status($id): $status for tid: $tid")
            return status
        } catch (e: Exception) {
            Logger.w(LOG_TAG_VPN, "$TAG err dns status($id): ${e.message}")
        }
        return null
    }

    suspend fun getRDNS(type: RethinkBlocklistManager.RethinkBlocklistType): backend.RDNS? {
        try {
            return if (type.isLocal() && !isPlayStoreFlavour()) {
                getRDNSResolver()?.rdnsLocal
            } else {
                getRDNSResolver()?.rdnsRemote
            }
        } catch (e: Exception) {
            Logger.w(LOG_TAG_VPN, "$TAG err getRDNS($type): ${e.message}", e)
        }
        return null
    }

    // v055, unused
    /*suspend fun setTcpProxy() {
        if (!appConfig.isTcpProxyEnabled()) {
            Logger.i(LOG_TAG_VPN, "$TAG tcp proxy not enabled")
            return
        }

        val u = TcpProxyHelper.getActiveTcpProxy()
        if (u == null) {
            Logger.w(LOG_TAG_VPN, "$TAG could not fetch tcp proxy details")
            return
        }

        val ips = getIpString(context, "https://sky.rethinkdns.com/")
        Logger.d(LOG_TAG_VPN, "$TAG ips: $ips")
        // svc.rethinkdns.com / duplex.deno.dev
        val testpipwsurl = "pipws://proxy.nile.workers.dev/ws/nosig"
        val ok = getProxies()?.addProxy(ProxyManager.ID_TCP_BASE, testpipwsurl)
        Logger.d(LOG_TAG_VPN, "$TAG tcp-mode(${ProxyManager.ID_TCP_BASE}): ${u.url}, ok? $ok")

        val secWarp = RpnProxyManager.getSecWarpConfig()
        if (secWarp == null) {
            Logger.w(LOG_TAG_VPN, "$TAG no sec warp config found")
            return
        }
        val wgUserSpaceString = secWarp.toWgUserspaceString()
        val ok2 = getProxies()?.addProxy(ID_WG_BASE + secWarp.getId(), wgUserSpaceString)
        Logger.d(
            LOG_TAG_VPN,
            "$TAG tcp-mode(wg) set(${ID_WG_BASE + secWarp.getId()}): ${secWarp.getName()}, res: $ok2"
        )
    }*/

    fun hasTunnel(): Boolean {
        return tunnel.isConnected
    }

    suspend fun refreshResolvers() {
        if (!tunnel.isConnected) {
            Logger.i(LOG_TAG_VPN, "$TAG no tunnel, skip refreshing resolvers")
            return
        }
        // if preferred is not available, then add explicitly
        val isPrefAvailable = getResolver()?.get(Backend.Preferred)
        if (isPrefAvailable == null) {
            addTransport()
        }
        Logger.i(LOG_TAG_VPN, "$TAG refresh resolvers")
        getResolver()?.refresh()
    }

    suspend fun closeTun() {
        try {
            if (tunnel.isConnected) {
                // this is not the only place where tunnel is disconnected
                // netstack can also close the tunnel on errors
                tunnel.disconnect()
            } else {
                Logger.i(LOG_TAG_VPN, "$TAG tunnel already disconnected")
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG err disconnect tunnel: ${e.message}", e)
        }
    }

    private fun newDefaultTransport(url: String): intra.DefaultDNS? {
        val defaultDns = FALLBACK_DNS
        try {
            // when the url is empty, set the default transport to 8.8.4.4, 2001:4860:4860::8844 or
            // null based on the value of SKIP_DEFAULT_DNS_ON_INIT
            if (url.isEmpty()) { // empty url denotes default dns set to none (system dns)
                if (SKIP_DEFAULT_DNS_ON_INIT) {
                    Logger.i(LOG_TAG_VPN, "$TAG set default transport to null, as url is empty")
                    return null
                }
                Logger.i(LOG_TAG_VPN, "$TAG set default transport to $defaultDns, as url is empty")
                return Intra.newDefaultDNS(Backend.DNS53, defaultDns, "")
            }
            val ips: String = getIpString(context, url)
            Logger.d(LOG_TAG_VPN, "$TAG default transport url: $url ips: $ips")
            val res = if (url.contains("http")) {
                Intra.newDefaultDNS(Backend.DOH, url, ips)
            } else {
                // no need to set ips for dns53
                Intra.newDefaultDNS(Backend.DNS53, url, "")
            }
            Logger.i(LOG_TAG_VPN, "$TAG new default transport: $url, $ips, success? ${res != null}")
            return res
        } catch (e: Exception) {
            Logger.w(LOG_TAG_VPN, "$TAG err new default transport($url): ${e.message}", e)
            // most of the android devices have google dns, so add it as default transport
            // TODO: notify the user that the default transport could not be set
            try {
                Logger.i(LOG_TAG_VPN, "$TAG; fallback; set default transport to $defaultDns")
                return Intra.newDefaultDNS(Backend.DNS53, defaultDns, "")
            } catch (e: Exception) {
                Logger.crash(LOG_TAG_VPN, "$TAG err add $defaultDns transport: ${e.message}", e)
                return null
            }
        }
    }

    suspend fun addDefaultTransport(url: String?) {
        if (!tunnel.isConnected) {
            Logger.i(LOG_TAG_VPN, "$TAG no tunnel, skip new  transport")
            return
        }
        var type = Backend.DNS53
        var fallbackUrl = FALLBACK_DNS

        val usingSysDnsAsDefaultDns = isDefaultDnsNone()
        val canUseGoos = !persistentState.routeRethinkInRethink
        if (SET_SYS_DNS_AS_DEFAULT_IFF_LOOPBACK && canUseGoos && usingSysDnsAsDefaultDns) {
            // empty string denotes to Goos
            fallbackUrl = ""
            type = ""
        }

        // default transport is always sent to Ipn.Exit in the go code and so dns
        // request sent to the default transport will not be looped back into the tunnel
        try {
            // when the url is empty, set the default transport to fallbackUrl
            if (url.isNullOrEmpty()) {
                Logger.i(LOG_TAG_VPN, "$TAG url empty, set default trans to $type : $fallbackUrl")
                Intra.addDefaultTransport(tunnel, type, fallbackUrl, "")
                return
            } else if (url.contains("http")){
                type = Backend.DOH
            }

            val ips: String = getIpString(context, url)
            Intra.addDefaultTransport(tunnel, type, url, ips)
            Logger.i(LOG_TAG_VPN, "$TAG default transport set, url: $url ips: $ips")
        } catch (e: Exception) {
            Logger.w(LOG_TAG_VPN, "$TAG err new default transport($url): ${e.message}", e)
            // most of the android devices have google dns, so add it as default transport
            // TODO: notify the user that the default transport could not be set
            try {
                Intra.addDefaultTransport(tunnel, type, fallbackUrl, "")
            } catch (e: Exception) {
                // fixme: this is not expected to happen, should show a notification?
                Logger.e(LOG_TAG_VPN, "$TAG err add $fallbackUrl transport: ${e.message}", e)
            }
        }
    }

    private fun isDefaultDnsNone(): Boolean {
        // if none is set then the url will either be empty or will not be one of the default dns
        return persistentState.defaultDnsUrl.isEmpty() ||
                !Constants.DEFAULT_DNS_LIST.any { it.url == persistentState.defaultDnsUrl }
    }

    suspend fun getSystemDns(): String {
        return try {
            val sysDns = getResolver()?.get(Backend.System)?.addr ?: ""
            Logger.i(LOG_TAG_VPN, "$TAG get system dns: $sysDns")
            sysDns
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG err get system dns: ${e.message}", e)
            ""
        }
    }

    suspend fun notifyLoopback() {
        val t = persistentState.routeRethinkInRethink
        try {
            Intra.loopback(t)
            Logger.i(LOG_TAG_VPN, "$TAG notify loopback? $t")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG err notify loopback? $t, ${e.message}", e)
        }
    }

    suspend fun setSystemDns(systemDns: List<String?>) {
        if (!tunnel.isConnected) {
            Logger.i(LOG_TAG_VPN, "$TAG no tunnel, skip setting system-dns")
            return
        }
        // for Rethink within rethink mode, the system dns is system dns is always set to Ipn.Base
        // in go and so dns request sent to the system dns will be looped back into the tunnel
        try {
            // TODO: system dns may be non existent; see: AppConfig#updateSystemDnsServers
            // convert list to comma separated string, as Intra expects as csv
            val sysDnsStr = systemDns.filter { it?.isNotEmpty() == true }.joinToString(",")
            // below code is commented out, add the code to set the system dns via resolver
            // val transport = Intra.newDNSProxy("ID", dnsProxy.host, dnsProxy.port.toString())
            // tunnel?.resolver?.addSystemDNS(transport)
            Logger.i(LOG_TAG_VPN, "$TAG set system dns: $sysDnsStr")
            // no need to send the dnsProxy.port for the below method, as it is not expecting port
            Intra.setSystemDNS(tunnel, sysDnsStr)
        } catch (e: Exception) { // this is not expected to happen
            Logger.e(LOG_TAG_VPN, "$TAG set system dns: could not parse: $systemDns", e)
            // remove the system dns, if it could not be set
            getResolver()?.remove(Backend.System)
        }
    }

    suspend fun updateLinkAndRoutes(
        tunFd: ParcelFileDescriptor,
        opts: TunnelOptions,
        proto: Long
    ): Boolean {
        if (!tunnel.isConnected) {
            Logger.e(LOG_TAG_VPN, "$TAG updateLink: tunnel disconnected, returning")
            
            return false
        }
        Logger.i(LOG_TAG_VPN, "$TAG updateLink with fd(${tunFd.fd}) mtu: ${opts.mtu}")
        return try {
            tunnel.setLinkAndRoutes(tunFd.fd.toLong(), opts.mtu.toLong(), proto)
            true
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG err update tun: ${e.message}", e)
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
            Logger.e(LOG_TAG_VPN, "$TAG updateTun: tunnel is not connected, returning")
            return false
        }

        Logger.i(LOG_TAG_VPN, "$TAG received update tun with opts: $tunnelOptions")
        // ok to init again, as updateTun is called to handle edge cases
        initResolverProxiesPcap(tunnelOptions)
        return tunnel.isConnected
    }

    suspend fun setDnsAlg() {
        // set translate to false for dns mode (regardless of setting in dns screen),
        // since apps cannot understand alg ips
        if (appConfig.getBraveMode().isDnsMode()) {
            Logger.i(LOG_TAG_VPN, "$TAG dns mode, set translate to false")
            getRDNSResolver()?.translate(false)
            return
        }

        Logger.i(LOG_TAG_VPN, "$TAG set dns alg: ${persistentState.enableDnsAlg}")
        getRDNSResolver()?.translate(persistentState.enableDnsAlg)
    }

    suspend fun setRDNSStamp() {
        try {
            val rl = getRDNS(RethinkBlocklistManager.RethinkBlocklistType.LOCAL)
            if (rl != null) {
                Logger.i(LOG_TAG_VPN, "$TAG set local stamp: ${persistentState.localBlocklistStamp}")
                rl.stamp = persistentState.localBlocklistStamp
            } else {
                Logger.w(LOG_TAG_VPN, "$TAG mode is not local, this should not happen")
            }
        } catch (e: java.lang.Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG could not set local stamp: ${e.message}", e)
        } finally {
            resetLocalBlocklistStampFromTunnel()
        }
    }

    private suspend fun resetLocalBlocklistStampFromTunnel() {
        if (isPlayStoreFlavour()) return

        try {
            val rl = getRDNS(RethinkBlocklistManager.RethinkBlocklistType.LOCAL)
            if (rl == null) {
                Logger.i(LOG_TAG_VPN, "$TAG mode is not local, no need to reset local stamp")
                return
            }

            persistentState.localBlocklistStamp = rl.stamp // throws exception if stamp is invalid
            Logger.i(LOG_TAG_VPN, "$TAG reset local stamp: ${persistentState.localBlocklistStamp}")
        } catch (e: Exception) {
            persistentState.localBlocklistStamp = ""
            Logger.e(LOG_TAG_VPN, "$TAG could not reset local stamp: ${e.message}", e)
        }
    }

    private suspend fun setRDNSLocal() {
        try {
            val stamp: String = persistentState.localBlocklistStamp
            val rdns = getRDNSResolver()
            Logger.i(LOG_TAG_VPN, "$TAG local blocklist stamp: $stamp, rdns? ${rdns != null}")

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
            Logger.i(LOG_TAG_VPN, "$TAG local brave dns object is set with stamp: $stamp")
        } catch (ex: Exception) {
            // Set local blocklist enabled to false and reset the timestamp
            // if there is a failure creating bravedns
            persistentState.blocklistEnabled = false
            // Set local blocklist enabled to false and reset the timestamp to make sure
            // user is prompted to download blocklists again on the next try
            persistentState.localBlocklistTimestamp = Constants.INIT_TIME_MS
            Logger.e(LOG_TAG_VPN, "$TAG could not set local-brave dns: ${ex.message}", ex)
        }
    }

    companion object {

        private const val TAG = "TunAdapter;"

        // if the default dns is set to none then no need to set the system dns as default,
        // set it as empty or null, which was the behaviour before v055a
        private const val SKIP_DEFAULT_DNS_ON_INIT = true

        // on system dns changes, set the system dns if default dns is none and loopback is true
        private const val SET_SYS_DNS_AS_DEFAULT_IFF_LOOPBACK = true

        fun getIpString(context: Context?, url: String?): String {
            if (url.isNullOrEmpty()) return ""

            val res: Resources? = context?.resources
            val urls: Array<out String>? = res?.getStringArray(R.array.urls)
            val ips: Array<out String>? = res?.getStringArray(R.array.ips)
            if (urls == null) return ""
            for (i in urls.indices) {
                // either the url is a substring of urls[i] or urls[i] is a substring of url
                if ((url.contains(urls[i])) || (urls[i].contains(url))) {
                    if (ips != null) return ips[i]
                }
            }
            return ""
        }

        fun setLogLevel(level: Int) {
            // 0 - very verbose, 1 - verbose, 2 - debug, 3 - info, 4 - warn, 5 - error, 6 - stacktrace, 7 - user, 8 - none
            // from UI, if none is selected, set the log level to 7 (user), usr will send only
            // user notifications
            Intra.logLevel(level, level)
            Logger.i(LOG_TAG_VPN, "$TAG set go-log level: ${Logger.LoggerType.fromId(level)}")
        }
    }

    suspend fun syncP50Latency(id: String) {
        try {
            val transport = getResolver()?.get(id)
            val p50 = transport?.p50() ?: return
            persistentState.setMedianLatency(p50)
        } catch (e: Exception) {
            Logger.w(LOG_TAG_VPN, "$TAG err getP50($id): ${e.message}")
        }
    }

    private suspend fun getResolver(): backend.DNSResolver? {
        try {
            if (!tunnel.isConnected) {
                Logger.i(LOG_TAG_VPN, "$TAG no tunnel, skip get resolver")
                return null
            }
            return tunnel.resolver
        } catch (e: Exception) {
            Logger.crash(LOG_TAG_VPN, "$TAG err get resolver: ${e.message}", e)
        }
        return null
    }

    private suspend fun getRDNSResolver(): backend.DNSResolver? {
        try {
            if (!tunnel.isConnected) {
                Logger.w(LOG_TAG_VPN, "$TAG no tunnel, skip get resolver")
                return null
            }
            return tunnel.resolver
        } catch (e: Exception) {
            Logger.crash(LOG_TAG_VPN, "$TAG err get resolver: ${e.message}", e)
        }
        return null
    }

    private suspend fun getProxies(): backend.Proxies? {
        try {
            if (!tunnel.isConnected) {
                Logger.w(LOG_TAG_VPN, "$TAG no tunnel, skip get proxies")
                return null
            }
            val px = tunnel.proxies
            return px
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG err get proxies: ${e.message}", e)
        }
        return null
    }

    suspend fun getSupportedIpVersion(proxyId: String): Pair<Boolean, Boolean> {
        try {
            val router = getProxies()?.getProxy(proxyId)?.router() ?: return Pair(false, false)
            val has4 = router.iP4()
            val has6 = router.iP6()
            Logger.d(LOG_TAG_VPN, "$TAG supported ip version($proxyId): has4? $has4, has6? $has6")
            return Pair(has4, has6)
        } catch (e: Exception) {
            Logger.w(LOG_TAG_VPN, "$TAG err supported ip version($proxyId): ${e.message}")
        }
        return Pair(false, false)
    }

    suspend fun isSplitTunnelProxy(proxyId: String, pair: Pair<Boolean, Boolean>): Boolean {
        return try {
            val router = getProxies()?.getProxy(proxyId)?.router() ?: return false
            // if the router contains 0.0.0.0, then it is not split tunnel for ipv4
            // if the router contains ::, then it is not split tunnel for ipv6
            val res: Boolean =
                if (pair.first && pair.second) {
                    // if the pair is true, check for both ipv4 and ipv6
                    !router.contains(UNSPECIFIED_IP_IPV4) || !router.contains(UNSPECIFIED_IP_IPV6)
                } else if (pair.first) {
                    !router.contains(UNSPECIFIED_IP_IPV4)
                } else if (pair.second) {
                    !router.contains(UNSPECIFIED_IP_IPV6)
                } else {
                    false
                }

            Logger.d(
                LOG_TAG_VPN,
                "$TAG split tunnel proxy($proxyId): ipv4? ${pair.first}, ipv6? ${pair.second}, res? $res"
            )
            res
        } catch (e: Exception) {
            Logger.w(LOG_TAG_VPN, "$TAG err isSplitTunnelProxy($proxyId): ${e.message}")
            false
        }
    }

    suspend fun initiateWgPing(proxyId: String) {
        try {
            val res = getProxies()?.getProxy(proxyId)?.ping()
            Logger.i(LOG_TAG_VPN, "$TAG initiateWgPing($proxyId): $res")
        } catch (e: Exception) {
            Logger.w(LOG_TAG_VPN, "$TAG err initiateWgPing($proxyId): ${e.message}")
        }
    }

    suspend fun getActiveProxiesIpAndMtu(): BraveVPNService.OverlayNetworks {
        try {
            val router = getProxies()?.router() ?: return BraveVPNService.OverlayNetworks()
            val has4 = router.iP4()
            val has6 = router.iP6()
            val failOpen = !router.iP4() && !router.iP6()
            val mtu = router.mtu().toInt()
            Logger.i(LOG_TAG_VPN, "$TAG proxy ip version, has4? $has4, has6? $has6, failOpen? $failOpen")
            return BraveVPNService.OverlayNetworks(has4, has6, failOpen, mtu)
        } catch (e: Exception) {
            // exceptions are expected only when the wireguard is not available
            Logger.w(LOG_TAG_VPN, "$TAG err proxy ip version: ${e.message}")
        }
        return BraveVPNService.OverlayNetworks()
    }

    suspend fun onLowMemory() {
        val limitBytes: Long = 512 * 1024 * 1024 // 512MB
        Intra.lowMem(limitBytes)
        Logger.i(LOG_TAG_VPN, "$TAG low memory, called Intra.lowMem()")
    }

    fun goBuildVersion(full: Boolean = false): String {
        return try {
            val version = Intra.build(full)
            Logger.i(LOG_TAG_VPN, "$TAG go build version: $version")
            version
        } catch (e: Exception) {
            Logger.w(LOG_TAG_VPN, "$TAG err go build version: ${e.message}")
            ""
        }
    }

    suspend fun setDialStrategy(mode: Int = persistentState.dialStrategy, retry: Int = persistentState.retryStrategy, tcpKeepAlive: Boolean = persistentState.tcpKeepAlive, timeoutSec: Int = persistentState.dialTimeoutSec) {
        if (!tunnel.isConnected) {
            Logger.i(LOG_TAG_VPN, "$TAG no tunnel, skip set dial strategy")
            return
        }
        try {
            Settings.setDialerOpts(mode, retry, timeoutSec, tcpKeepAlive)
            Logger.i(LOG_TAG_VPN, "$TAG set dial strategy: $mode, retry: $retry, tcpKeepAlive: $tcpKeepAlive, timeout: $timeoutSec")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG err set dial strategy: ${e.message}", e)
        }
    }

    suspend fun setTransparency(eim: Boolean = persistentState.endpointIndependence) {
        if (!tunnel.isConnected) {
            Logger.i(LOG_TAG_VPN, "$TAG no tunnel, skip transparency")
            return
        }
        if (!isAtleastS()) {
            Intra.transparency(false, false)
            Logger.i(LOG_TAG_VPN, "$TAG Android version is less than S, set transparency: false")
            return
        }
        // set both endpoint independent mapping and endpoint independent filtering
        // as a single value, as for the user is concerned, it is a single setting
        Intra.transparency(eim, eim)
        Logger.i(LOG_TAG_VPN, "$TAG set transparency: $eim")
    }

    suspend fun undelegatedDomains(useSystemDns: Boolean = persistentState.useSystemDnsForUndelegatedDomains) {
        if (!tunnel.isConnected) {
            Logger.i(LOG_TAG_VPN, "$TAG no tunnel, skip undelegated domains")
            return
        }
        try {
            Intra.undelegatedDomains(useSystemDns)
            Logger.i(LOG_TAG_VPN, "$TAG undelegated domains: $useSystemDns")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG err undelegated domains: ${e.message}", e)
        }
    }

    suspend fun setSlowdownMode(slowdown: Boolean = persistentState.slowdownMode) {
        if (!tunnel.isConnected) {
            Logger.i(LOG_TAG_VPN, "$TAG no tunnel, skip slowdown mode")
            return
        }
        try {
            Intra.slowdown(slowdown)
            Logger.i(LOG_TAG_VPN, "$TAG set slowdown mode: $slowdown")
        } catch (e: Exception) {
            Logger.w(LOG_TAG_VPN, "$TAG err slowdown mode: ${e.message}")
        }
    }

    suspend fun registerAndFetchWarpConfigIfNeeded(prevBytes: ByteArray? = null): ByteArray? {
        if (!tunnel.isConnected) {
            Logger.i(LOG_TAG_PROXY, "$TAG no tunnel, skip register warp")
            return null
        }
        try {
            // prevBytes null denotes that the warp needs to be registered again
            // either by the user or new registration
            if (tunnel.proxies.rpn().warp() != null && prevBytes != null) {
                Logger.i(LOG_TAG_PROXY, "$TAG warp already registered")
                return null
            }
        } catch (ignore: Exception) {
            Logger.i(LOG_TAG_PROXY, "$TAG warp not registered, fall through")
        }
        return try {
            Logger.i(LOG_TAG_PROXY, "$TAG start warp registration")
            val bytes = tunnel.proxies.rpn().registerWarp(prevBytes)
            Logger.i(LOG_TAG_PROXY, "$TAG registered warp? ${bytes != null}")
            bytes
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG err getting tunnel stats: ${e.message}", e)
            null
        }
    }

    suspend fun getRpnProps(rpnType: RpnProxyManager.RpnType): Pair<RpnProxyManager.RpnProps?, String?> {
        try {
            var errMsg: String? = ""
            val rpn: backend.RpnProxy? = try {
                when (rpnType) {
                    RpnProxyManager.RpnType.WARP -> {
                        tunnel.proxies.rpn().warp()
                    }

                    RpnProxyManager.RpnType.AMZ -> {
                        tunnel.proxies.rpn().amnezia()
                    }

                    RpnProxyManager.RpnType.PROTON -> {
                        tunnel.proxies.rpn().proton()
                    }

                    RpnProxyManager.RpnType.SE -> {
                        tunnel.proxies.rpn().se()
                    }

                    RpnProxyManager.RpnType.EXIT_64 -> {
                        tunnel.proxies.rpn().exit64()
                    }

                    RpnProxyManager.RpnType.EXIT -> {
                        null
                    }
                }
            } catch (e: Exception) {
                Logger.w(LOG_TAG_PROXY, "$TAG err rpn proxy($rpnType): ${e.message}")
                errMsg = e.message
                null
            }
            if (rpn == null) { // exit is not an rpn proxy, so return null
                Logger.i(LOG_TAG_PROXY, "$TAG rpn props($rpnType) is null")
                return Pair(null, errMsg)
            }

            val id = rpn.id()
            val status = rpn.status()
            val type = rpn.type()
            val kids = rpn.kids()
            val addr = rpn.addr
            val created = rpn.created()
            val expires = rpn.expires()
            val who = rpn.who()
            val prop = RpnProxyManager.RpnProps(id, status, type, kids, addr, created, expires, who)
            return Pair(prop, errMsg)
        } catch (e: Exception) {
            Logger.w(LOG_TAG_PROXY, "$TAG err rpn props($rpnType): ${e.message}")
            return Pair(null, e.message)
        }
    }

    suspend fun registerAndFetchAmzConfigIfNeeded(prevBytes: ByteArray? = null): ByteArray? {
        if (!tunnel.isConnected) {
            Logger.i(LOG_TAG_PROXY, "$TAG no tunnel, skip register amz")
            return null
        }
        try {
            if (tunnel.proxies.rpn().amnezia() != null && prevBytes != null) {
                Logger.i(LOG_TAG_PROXY, "$TAG amz already registered")
                return null
            }
        } catch (ignore: Exception) {
            Logger.i(LOG_TAG_PROXY, "$TAG amz not registered, fall through")
        }
        return try {
            val bytes = tunnel.proxies.rpn().registerAmnezia(prevBytes)
            Logger.i(LOG_TAG_PROXY, "$TAG registered amz")
            bytes
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG err getting tunnel stats: ${e.message}", e)
            null
        }
    }

    suspend fun testRpnProxy(type: RpnProxyManager.RpnType): Boolean {
        if (!tunnel.isConnected) {
            Logger.i(LOG_TAG_PROXY, "$TAG no tunnel, skip test rpn proxy")
            return false
        }
        try {
            val ippcsv = when (type) {
                RpnProxyManager.RpnType.WARP -> {
                    tunnel.proxies.rpn().testWarp()
                }
                RpnProxyManager.RpnType.AMZ -> {
                    tunnel.proxies.rpn().testAmnezia()
                }
                RpnProxyManager.RpnType.PROTON -> {
                    tunnel.proxies.rpn().testProton()
                }
                RpnProxyManager.RpnType.SE -> {
                    tunnel.proxies.rpn().testSE()
                }
                RpnProxyManager.RpnType.EXIT_64 -> {
                    tunnel.proxies.rpn().testExit64()
                }
                RpnProxyManager.RpnType.EXIT -> {
                    // no need to test exit
                    "true" // dummy value, just for logging, return just checks for empty string
                }
            }
            Logger.i(LOG_TAG_PROXY, "$TAG test rpn proxy($type): $ippcsv")
            return ippcsv.isNotEmpty()
        } catch (e: Exception) {
            Logger.w(LOG_TAG_PROXY, "$TAG err test rpn proxy($type): ${e.message}")
        }
        return false
    }

    suspend fun registerAndFetchProtonIfNeeded(prevBytes: ByteArray? = null): ByteArray? {
        if (!tunnel.isConnected) {
            Logger.i(LOG_TAG_PROXY, "$TAG no tunnel, skip register proton")
            return null
        }
        try {
            // in case of fresh-cred generation, the bytes are null and the proton needs to be
            // registered again
            if (tunnel.proxies.rpn().proton() != null && prevBytes != null) {
                Logger.i(LOG_TAG_PROXY, "$TAG proton already registered")
                return null
            }
        } catch (ignore: Exception) {
            Logger.i(LOG_TAG_PROXY, "$TAG proton not registered, fall through")
        }
        return try {
            Logger.i(LOG_TAG_PROXY, "$TAG start proton reg, existing bytes size: ${prevBytes?.size}")
            val bytes = tunnel.proxies.rpn().registerProton(prevBytes)
            Logger.i(LOG_TAG_PROXY, "$TAG proton registered, ${bytes?.size} bytes")
            bytes
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG err register proton: ${e.message}", e)
            null
        }
    }

    suspend fun unregisterWarp(): Boolean {
        if (!tunnel.isConnected) {
            Logger.i(LOG_TAG_PROXY, "$TAG no tunnel, skip unregister warp")
            return false
        }
        return try {
            val res = tunnel.proxies.rpn().unregisterWarp()
            Logger.i(LOG_TAG_PROXY, "$TAG unregister warp: $res")
            res
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG err unregister warp: ${e.message}", e)
            false
        }
    }

    suspend fun unregisterAmnezia(): Boolean {
        if (!tunnel.isConnected) {
            Logger.i(LOG_TAG_PROXY, "$TAG no tunnel, skip unregister amz")
            return false
        }
        return try {
            val res = tunnel.proxies.rpn().unregisterAmnezia()
            Logger.i(LOG_TAG_PROXY, "$TAG unregister amz: $res")
            res
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG err unregister amz: ${e.message}", e)
            false
        }
    }

    suspend fun unregisterProton(): Boolean {
        if (!tunnel.isConnected) {
            Logger.i(LOG_TAG_PROXY, "$TAG no tunnel, skip unregister proton")
            return false
        }
        return try {
            val res = tunnel.proxies.rpn().unregisterProton()
            Logger.i(LOG_TAG_PROXY, "$TAG unregister proton: $res")
            res
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG err unregister proton: ${e.message}", e)
            false
        }
    }

    suspend fun unregisterSE(): Boolean {
        if (!tunnel.isConnected) {
            Logger.i(LOG_TAG_PROXY, "$TAG no tunnel, skip unregister se")
            return false
        }
        return try {
            val res = tunnel.proxies.rpn().unregisterSE()
            Logger.i(LOG_TAG_PROXY, "$TAG unregister se: $res")
            res
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG err unregister se: ${e.message}", e)
            false
        }
    }

    suspend fun setRpnAutoMode(rpnMode: RpnProxyManager.RpnMode): Boolean {
        if (!tunnel.isConnected) {
            Logger.i(LOG_TAG_PROXY, "$TAG no tunnel, skip set rpn auto mode")
            return false
        }
        if (RpnProxyManager.isRpnActive()) {
            Logger.i(LOG_TAG_PROXY, "$TAG rpn is active, skip set rpn auto mode")
            return false
        }
        return try {
            // in case of hide-ip mode, the exit proxy will not be used
            val res = Settings.setAutoAlwaysRemote(rpnMode.isHideIp())
            Logger.i(LOG_TAG_PROXY, "$TAG set auto always remore to: ${rpnMode.isHideIp()}")
            res
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG err set rpn auto mode: ${e.message}", e)
            false
        }
    }

    suspend fun isProxyReachable(proxyId: String, csv: String): Boolean { // can be ippcsv or hostpcsv
        if (!tunnel.isConnected) {
            Logger.i(LOG_TAG_PROXY, "$TAG no tunnel, skip ping proxy")
            return false
        }
        return try {
            val res = tunnel.proxies.getProxy(proxyId).router().reaches(csv)
            Logger.i(LOG_TAG_PROXY, "$TAG ping $proxyId? ($csv) $res")
            res
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG err ping $proxyId: ${e.message}", e)
            false
        }
    }

    suspend fun registerSurfEasyIfNeeded(): Boolean {
        if (!tunnel.isConnected) {
            Logger.i(LOG_TAG_PROXY, "$TAG no tunnel, skip register surf easy")
            return false
        }
        try {
            if (tunnel.proxies.rpn().se() != null) {
                Logger.i(LOG_TAG_PROXY, "$TAG se already registered")
                return true
            }
        } catch (ignore: Exception) {
            Logger.i(LOG_TAG_PROXY, "$TAG se not registered, fall through")
        }
        return try {
            tunnel.proxies.rpn().registerSE()
            Logger.i(LOG_TAG_VPN, "$TAG se registered")
            true
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG err register se: ${e.message}", e)
            false
        }
    }

    suspend fun setExperimentalSettings(value: Boolean = persistentState.nwEngExperimentalFeatures) {
        if (!tunnel.isConnected) {
            Logger.i(LOG_TAG_VPN, "$TAG no tunnel, skip set experimental settings")
            return
        }
        try {
            Intra.experimental(value)
            // refresh proxies on experimental settings change (required for wireguard)
            refreshProxies()
            Logger.i(LOG_TAG_VPN, "$TAG set experimental settings: $value")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG err set experimental settings: ${e.message}", e)
        }
    }

    suspend fun createHop(origin: String, hop: String): Pair<Boolean, String> {
        if (!tunnel.isConnected) {
            Logger.i(LOG_TAG_VPN, "$TAG createHop; no tunnel, skip create hop")
            return Pair(false, "no tunnel")
        }
        // to remove hop there is a separate method, so no need to check for empty
        if (hop.isEmpty()) {
            Logger.i(LOG_TAG_VPN, "$TAG createHop; hop is empty, returning")
            return Pair(false, "hop is empty")
        }
        return try {
            Logger.i(LOG_TAG_VPN, "$TAG create hop: $origin, hop: $hop")
            tunnel.proxies.hop(hop, origin)
            Pair(true, context.getString(R.string.config_add_success_toast))
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG err create hop: ${e.message}", e)
            Pair(false, e.message ?: "Hop creation failed")
        }
    }

    suspend fun hop(proxyId: String): String {
        return try {
            val res = getProxies()?.getProxy(proxyId)?.router()?.via()?.id() ?: ""
            Logger.i(LOG_TAG_VPN, "$TAG hop proxy($proxyId): $res")
            res
        } catch (e: Exception) {
            Logger.w(LOG_TAG_VPN, "$TAG err hop proxy($proxyId): ${e.message}")
            ""
        }
    }

    suspend fun updateRpnProxy(type: RpnProxyManager.RpnType): ByteArray? {
        if (!tunnel.isConnected) {
            Logger.i(LOG_TAG_PROXY, "$TAG no tunnel, skip update rpn proxy")
            return null
        }
        return try {
            val res = when (type) {
                RpnProxyManager.RpnType.WARP -> {
                    tunnel.proxies.rpn().warp().update()
                }
                RpnProxyManager.RpnType.AMZ -> {
                    tunnel.proxies.rpn().amnezia().update()
                }
                RpnProxyManager.RpnType.PROTON -> {
                    tunnel.proxies.rpn().proton().update()
                }
                RpnProxyManager.RpnType.SE -> {
                    // no need to update se
                    null
                }
                RpnProxyManager.RpnType.EXIT_64 -> {
                    // no need to update exit64
                    null
                }
                RpnProxyManager.RpnType.EXIT -> {
                    // no need to update exit
                    null
                }
            }
            res
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG err update rpn proxy($type): ${e.message}", e)
            null
        }
    }

    suspend fun addMultipleDoHAsPlus() {
        if (!tunnel.isConnected) {
            Logger.i(LOG_TAG_VPN, "$TAG no tunnel, skip set multi dns as plus")
            return
        }

        val dohList = appConfig.getAllDoHEndpoints()
        Logger.vv(LOG_TAG_VPN, "$TAG add multiple doh as plus dns: $dohList")
        dohList.forEach { doh ->
            try {
                var url: String? = null
                url = doh.dohURL
                // default transport-id, append individual id with this
                val id = Backend.Plus + doh.id
                val ips: String = getIpString(context, url)
                if (ips.isEmpty()) {
                    Logger.i(LOG_TAG_VPN, "$TAG ips empty for $url, skip adding to plus dns")
                    return@forEach
                }
                // change the url from https to http if the isSecure is false
                if (doh.isSecure == false) {
                    Logger.d(LOG_TAG_VPN, "$TAG changing url from https to http for $url")
                    url = url.replace("https", "http")
                }
                // add replaces the existing transport with the same id if successful
                // so no need to remove the transport before adding
                Intra.addDoHTransport(tunnel, id, url, ips)
                Logger.i(
                    LOG_TAG_VPN, "$TAG add to plus, dns: $id (${doh.dohName}), url: $url, ips: $ips"
                )
            } catch (e: Exception) {
                Logger.e(LOG_TAG_VPN, "$TAG err add DoH to plus: ${e.message}", e)
            }
        }

    }

    private suspend fun panicAtRandom(shouldPanic: Boolean) {
        if (!tunnel.isConnected) {
            Logger.i(LOG_TAG_VPN, "$TAG no tunnel, skip panic at random")
            return
        }
        try {
            Intra.panicAtRandom(shouldPanic)
            Logger.i(LOG_TAG_VPN, "$TAG panic at random: $shouldPanic")
        } catch (e: Exception) {
            Logger.i(LOG_TAG_VPN, "$TAG err panic at random: ${e.message}")
        }
    }

    fun tunMtu(): Int {
        if (!tunnel.isConnected) {
            Logger.i(LOG_TAG_VPN, "$TAG no tunnel, skip tun mtu")
            return 0
        }
        return try {
            val mtu = tunnel.mtu()
            Logger.i(LOG_TAG_VPN, "$TAG tun mtu: $mtu")
            mtu
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG err tun mtu: ${e.message}", e)
            0
        }
    }

    private fun ui(f: suspend () -> Unit) {
        externalScope.launch(Dispatchers.Main) { f() }
    }
}
