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
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.celzero.firestack.backend.Backend
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.data.AppConfig.Companion.DOH_INDEX
import com.celzero.bravedns.data.AppConfig.Companion.DOT_INDEX
import com.celzero.bravedns.data.AppConfig.Companion.FALLBACK_DNS_IF_NET_DNS_EMPTY
import com.celzero.bravedns.data.AppConfig.TunnelOptions
import com.celzero.bravedns.database.RpnWinServerEntity
import com.celzero.bravedns.database.ConnectionTrackerRepository
import com.celzero.bravedns.database.DnsCryptRelayEndpoint
import com.celzero.bravedns.database.ProxyEndpoint
import com.celzero.bravedns.net.doh.Transaction
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.BraveVPNService.Companion.FIRESTACK_MUST_DUP_TUNFD
import com.celzero.bravedns.service.BraveVPNService.Companion.NW_ENGINE_NOTIFICATION_ID
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.ProxyManager.ID_WG_BASE
import com.celzero.bravedns.service.RethinkBlocklistManager
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.ui.activity.AntiCensorshipActivity
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
import com.celzero.bravedns.util.Utilities.tob
import com.celzero.bravedns.util.Utilities.togb
import com.celzero.bravedns.util.Utilities.togs
import com.celzero.bravedns.util.Utilities.tos
import com.celzero.bravedns.wireguard.Config
import com.celzero.bravedns.wireguard.WgHopManager
import com.celzero.firestack.backend.DNSResolver
import com.celzero.firestack.backend.DNSTransport
import com.celzero.firestack.backend.NetStat
import com.celzero.firestack.backend.Proxies
import com.celzero.firestack.backend.RDNS
import com.celzero.firestack.backend.RouterStats
import com.celzero.firestack.backend.RpnProxy
import com.celzero.firestack.intra.Controller
import com.celzero.firestack.intra.DefaultDNS
import com.celzero.firestack.intra.Intra
import com.celzero.firestack.intra.Tunnel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import com.celzero.firestack.settings.Settings
import kotlinx.coroutines.delay
import java.io.File
import java.net.URI
import java.net.URLEncoder
import kotlin.text.substring

/**
 * This is a VpnAdapter that captures all traffic and routes it through a go-tun2socks instance with
 * custom logic for Intra.
 */
class GoVpnAdapter : KoinComponent {
    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()
    private val connTrackerDb by inject<ConnectionTrackerRepository>()

    // The Intra session object from go-tun2socks.  Initially null.
    private var tunnel: Tunnel
    private var context: Context
    private var externalScope: CoroutineScope

    constructor(
        context: Context,
        externalScope: CoroutineScope,
        tunFd: Long,
        ifaceAddresses: String,
        mtu: Int,
        opts: TunnelOptions) {
        this.context = context
        this.externalScope = externalScope
        val defaultDns = newDefaultTransport(appConfig.getDefaultDns())
        // no need to connect tunnel if already connected, just reset the tunnel with new
        // parameters
        val prev = Settings.dupTunFd(FIRESTACK_MUST_DUP_TUNFD)
        Logger.i(LOG_TAG_VPN, "$TAG connect tunnel with new params")
        tunnel =
            Intra.connect(
                tunFd,
                mtu.toLong(),
                ifaceAddresses,
                opts.fakeDns,
                defaultDns,
                opts.bridge
            ) // may throw exception
        setTunMode(opts)
    }

    suspend fun initResolverProxiesPcap(opts: TunnelOptions) {
        Logger.v(LOG_TAG_VPN, "$TAG initResolverProxiesPcap")
        if (!tunnel.isConnected) {
            Logger.e(LOG_TAG_VPN, "$TAG no tunnel, skip init resolver proxies")
            return
        }

        // setTcpProxyIfNeeded()
        // no need to add default in init as it is already added in connect
        // addDefaultTransport(appConfig.getDefaultDns())
        // TODO: ideally the values required for transport, alg and rdns should be set in the
        // opts itself.
        setRDNS()
        addTransport()
        // Plus do not throw exception if the dns is not added, so check the status for client err
        val plusDnsStatus = getDnsStatus(Backend.Plus)
        if(plusDnsStatus == null || plusDnsStatus == Transaction.Status.CLIENT_ERROR.id) addMultipleDnsAsPlus()
        setWireguardTunnelModeIfNeeded(opts.tunProxyMode)
        setSocks5TunnelModeIfNeeded(opts.tunProxyMode)
        setHttpProxyIfNeeded(opts.tunProxyMode)
        setPcapMode(appConfig.getPcapFilePath())
        setDnsAlg()
        notifyLoopback()
        setDialStrategy()
        setTransparency()
        undelegatedDomains()
        setExperimentalWireGuardSettings()
        setAutoDialsParallel()
        setHappyEyeballs()
        // added for testing, use if needed
        if (DEBUG) panicAtRandom(persistentState.panicRandom) else panicAtRandom(false)
        Logger.v(LOG_TAG_VPN, "$TAG initResolverProxiesPcap done")
    }

    suspend fun setPcapMode(pcapFilePath: String) {
        Logger.v(LOG_TAG_VPN, "$TAG setPcapMode")
        if (!tunnel.isConnected) {
            Logger.e(LOG_TAG_VPN, "$TAG no tunnel, skip set pcap mode")
            return
        }

        // validate pcap file path before setting
        if (pcapFilePath.isNotEmpty() && pcapFilePath != "0") {
            val file = File(pcapFilePath)
            if (!file.exists() || !file.canWrite()) {
                Logger.e(LOG_TAG_VPN, "$TAG invalid pcap file path, file does not exist or not writable: $pcapFilePath")
                return
            }
        }

        try {
            Logger.i(LOG_TAG_VPN, "$TAG set pcap mode: $pcapFilePath")
            tunnel.setPcap(pcapFilePath)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG err setting pcap($pcapFilePath): ${e.message}", e)
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

    suspend fun addTransport() {
        Logger.v(LOG_TAG_VPN, "$TAG addTransport")
        if (!tunnel.isConnected) {
            Logger.e(LOG_TAG_VPN, "$TAG no tunnel, skip add transport")
            return
        }

        // TODO: #321 As of now there is no block free transport for dns types other than Rethink.
        // introduce block free transport for other dns types when there is a UI to configure the
        // block free transport
        // always set the block free transport before setting the transport to the resolver
        // because of the way the alg is implemented in the go code.

        // TODO: should show notification if the transport is not added?
        // now it will fallback to rethink default if the transport is not available in kt to add
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

            AppConfig.DnsType.SMART_DNS -> {
                addMultipleDnsAsPlus()
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
                    Logger.e(LOG_TAG_VPN, "$TAG no rdns remote url found")
                    fallbackToRethinkDefault()
                }
            }
        }
        Logger.v(LOG_TAG_VPN, "$TAG addTransport done")
    }

    private suspend fun fallbackToRethinkDefault() {
        // this should never happen, but as a safeguard, fall back to rethink default
        // to prevent silent failures. case: on a OnePlus device where the database contained
        // values, but none of the transport types were selected, causing vpnAdapter to skip
        // setting any preferred transport.
        Logger.w(LOG_TAG_VPN, "$TAG fallback to rethink default")
        val rethinkDefault = appConfig.getRethinkDefaultEndpoint()
        if (rethinkDefault == null || rethinkDefault.url.isEmpty()) {
            Logger.e(LOG_TAG_VPN, "$TAG connect-tunnel: fallback; rethink default is empty, cannot add transport")
            showDnsFailureToast("")
            return
        }
        // this will in-turn updates the ui and calls the adapter to update the transport
        appConfig.handleRethinkChanges(rethinkDefault)
    }

    private suspend fun removeResolver(id: String) {
        if (!tunnel.isConnected) {
            Logger.e(LOG_TAG_VPN, "$TAG no tunnel, skip remove resolver $id")
            return
        }
        try {
            getResolver()?.remove(id.togs())
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG err remove resolver $id: ${e.message}", e)
        }
    }

    private suspend fun addDohTransport(id: String) {
        Logger.v(LOG_TAG_VPN, "$TAG addDohTransport")
        var url: String? = null
        try {
            val doh = appConfig.getDOHDetails()
            if (doh == null || doh.dohURL.isEmpty()) {
                Logger.e(LOG_TAG_VPN, "$TAG connect-tunnel: doh url is empty, cannot add transport")
                fallbackToRethinkDefault()
                return
            }
            url = doh.dohURL
            val ips: String = getIpString(context, url)
            // change the url from https to http if the isSecure is false
            if (!doh.isSecure) {
                Logger.d(LOG_TAG_VPN, "$TAG changing url from https to http for $url")
                url = url.replace("https", "http")
            }
            // add replaces the existing transport with the same id if successful
            // so no need to remove the transport before adding
            Intra.addDoHTransport(tunnel, id.togs(), url.togs(), ips.togs())
            Logger.i(LOG_TAG_VPN, "$TAG new doh: $id (${doh.dohName}), url: $url, ips: $ips")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG connect-tunnel: doh failure, url: $url", e)
            removeResolver(id)
            showDnsFailureNotification(context.getString(R.string.other_dns_list_tab1), e.message ?: context.getString(R.string.system_dns_connection_failure))
            showDnsFailureToast(url ?: "")
        }
        Logger.v(LOG_TAG_VPN, "$TAG addDohTransport done")
    }

    private suspend fun addDotTransport(id: String) {
        Logger.v(LOG_TAG_VPN, "$TAG addDotTransport, id: $id")
        var url: String? = null
        try {
            val dot = appConfig.getDOTDetails()
            if (dot == null || dot.url.isEmpty()) {
                Logger.e(LOG_TAG_VPN, "$TAG connect-tunnel: dot url is empty, cannot add transport")
                fallbackToRethinkDefault()
                return
            }
            url = dot.url
            // if tls is present, remove it and pass it to getIpString
            val ips: String = getIpString(context, url.replace("tls://", ""))
            if (dot.isSecure && !url.startsWith("tls")) {
                Logger.d(LOG_TAG_VPN, "$TAG adding tls to url for $url")
                // add tls to the url if isSecure is true and the url does not start with tls
                url = "tls://$url"
            }
            // add replaces the existing transport with the same id if successful
            // so no need to remove the transport before adding
            Intra.addDoTTransport(tunnel, id.togs(), url.togs(), ips.togs())
            Logger.i(LOG_TAG_VPN, "$TAG new dot: $id (${dot.name}), url: $url, ips: $ips")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG connect-tunnel: dot failure, url: $url", e)
            removeResolver(id)
            showDnsFailureNotification(context.getString(R.string.lbl_dot), e.message ?: context.getString(R.string.system_dns_connection_failure))
            showDnsFailureToast(url ?: "")
        }
        Logger.v(LOG_TAG_VPN, "$TAG addDotTransport done")
    }

    private suspend fun addOdohTransport(id: String) {
        Logger.v(LOG_TAG_VPN, "$TAG addOdohTransport, id: $id")
        var resolver: String? = null
        try {
            val odoh = appConfig.getODoHDetails()
            if (odoh == null || odoh.resolver.isEmpty()) {
                Logger.e(LOG_TAG_VPN, "$TAG connect-tunnel: odoh resolver is empty, cannot add transport")
                fallbackToRethinkDefault()
                return
            }
            val proxy = odoh.proxy
            resolver = odoh.resolver
            val proxyIps = ""
            // add replaces the existing transport with the same id if successful
            // so no need to remove the transport before adding
            Intra.addODoHTransport(tunnel, id.togs(), proxy.togs(), resolver.togs(), proxyIps.togs())
            Logger.i(LOG_TAG_VPN, "$TAG new odoh: $id (${odoh.name}), p: $proxy, r: $resolver")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG connect-tunnel: odoh failure, res: $resolver", e)
            removeResolver(id)
            showDnsFailureNotification(context.getString(R.string.lbl_odoh), e.message ?: context.getString(R.string.system_dns_connection_failure))
            showDnsFailureToast(resolver ?: "")
        }
        Logger.v(LOG_TAG_VPN, "$TAG addOdohTransport done")
    }

    private suspend fun addDnscryptTransport(id: String) {
        Logger.v(LOG_TAG_VPN, "$TAG addDnscryptTransport, id: $id")
        try {
            val dc = appConfig.getConnectedDnscryptServer()
            if (dc == null || dc.dnsCryptURL.isEmpty()) {
                Logger.e(LOG_TAG_VPN, "$TAG connect-tunnel: dns-crypt is empty, cannot add transport")
                fallbackToRethinkDefault()
                return
            }
            val url = dc.dnsCryptURL
            // add replaces the existing transport with the same id if successful
            // so no need to remove the transport before adding
            Intra.addDNSCryptTransport(tunnel, id.togs(), url.togs())
            Logger.i(LOG_TAG_VPN, "$TAG new dnscrypt: $id (${dc.dnsCryptName}), url: $url")
            setDnscryptRelaysIfAny() // is expected to catch exceptions
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG connect-tunnel: dns crypt failure for $id", e)
            removeResolver(id)
            removeDnscryptRelaysIfAny()
            showDnsFailureNotification(context.getString(R.string.dc_dns_crypt), e.message ?: context.getString(R.string.dns_crypt_connection_failure))
            showDnscryptConnectionFailureToast()
        }
        Logger.v(LOG_TAG_VPN, "$TAG addDnscryptTransport done")
    }

    private suspend fun addDnsProxyTransport(id: String) {
        Logger.v(LOG_TAG_VPN, "$TAG addDnsProxyTransport, id: $id")
        try {
            val dnsProxy = appConfig.getSelectedDnsProxyDetails()
            if (dnsProxy == null || dnsProxy.proxyIP.isNullOrEmpty()) {
                Logger.e(LOG_TAG_VPN, "$TAG connect-tunnel: dns-proxy is empty, cannot add transport")
                fallbackToRethinkDefault()
                return
            }
            // append port number to each ip if multiple ips are present else add the single ip:port
            val port = dnsProxy.proxyPort
            val ipPortCsv = dnsProxy.proxyIP?.split(",")?.joinToString(",") {
                "${it.trim()}:$port"
            }
            // add replaces the existing transport with the same id if successful
            // so no need to remove the transport before adding
            Intra.addDNSProxy(tunnel, id.togs(), ipPortCsv.togs())
            Logger.i(LOG_TAG_VPN, "$TAG new dns proxy: $id(${dnsProxy.proxyName}), ip: $ipPortCsv")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG connect-tunnel: dns proxy failure", e)
            removeResolver(id)
            showDnsFailureNotification(context.getString(R.string.dc_dns_proxy), e.message ?: context.getString(R.string.dns_proxy_connection_failure))
            showDnsProxyConnectionFailureToast()
        }
        Logger.v(LOG_TAG_VPN, "$TAG addDnsProxyTransport done")
    }

    private fun showDnsFailureNotification(dnsType: String, message: String) {
        val notifChannelId = "DNS_failure_channel"
        ui {
            val notificationManager =
                context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager

            if (isAtleastO()) {
                val channelName = context.getString(R.string.status_dns_error)
                val importance = NotificationManager.IMPORTANCE_DEFAULT
                val channel = NotificationChannel(notifChannelId, channelName, importance)
                notificationManager.createNotificationChannel(channel)
            }

            val pendingIntent =
                Utilities.getActivityPendingIntent(
                    context,
                    Intent(context, AppLockActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    mutable = false
                )
            val dnsErrorTxt = context.getString(R.string.status_dns_error)
            // capitalize first 3 letter of the error message (dns error => DNS Error)
            val formattedErrTxt =
                if (dnsErrorTxt.length >= 5) {
                    dnsErrorTxt.replaceRange(0, 5, dnsErrorTxt.substring(0, 5).uppercase())
                } else {
                    dnsErrorTxt
                }
            val title = context.getString(R.string.two_argument_colon, formattedErrTxt, dnsType)
            val builder =
                NotificationCompat.Builder(context, notifChannelId)
                    .setSmallIcon(R.drawable.ic_notification_icon)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                    .setContentIntent(pendingIntent)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
            builder.color = ContextCompat.getColor(context, getAccentColor(persistentState.theme))
            notificationManager.notify(NW_ENGINE_NOTIFICATION_ID, builder.build())
        }
    }

    private suspend fun addRdnsTransport(id: String, url: String) {
        Logger.v(LOG_TAG_VPN, "$TAG addRdnsTransport, id: $id, url: $url")
        try {
            val useDot = false
            val ips: String = getIpString(context, url)
            val convertedUrl = getRdnsUrl(url) ?: return
            if (url.contains(RETHINK_BASE_URL_SKY) || !useDot) {
                Intra.addDoHTransport(tunnel, id.togs(), convertedUrl.togs(), ips.togs())
                Logger.i(LOG_TAG_VPN, "$TAG new doh (rdns): $id, url: $convertedUrl, ips: $ips")
            } else {
                Intra.addDoTTransport(tunnel, id.togs(), convertedUrl.togs(), ips.togs())
                Logger.i(LOG_TAG_VPN, "$TAG new dot (rdns): $id, url: $convertedUrl, ips: $ips")
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG connect-tunnel: rdns failure, url: $url", e)
            removeResolver(id)
            showDnsFailureNotification(context.getString(R.string.dc_rethink_dns_radio), e.message ?: context.getString(R.string.system_dns_connection_failure))
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
            Logger.e(LOG_TAG_VPN, "$TAG no tunnel, skip unlink")
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
            val flags = r?.stampToFlags(stamp.togs())
            b32Stamp = r?.flagsToStamp(flags, Backend.EB32).tos()
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG err get base32 stamp: ${e.message}")
        }
        return b32Stamp ?: stamp
    }

    fun setTunMode(tunnelOptionsWithoutMtu: TunnelOptions) {
        if (!tunnel.isConnected) {
            Logger.e(LOG_TAG_VPN, "$TAG no tunnel, skip set-tun-mode")
            return
        }
        try {
            Settings.setTunMode(
                tunnelOptionsWithoutMtu.tunDnsMode.mode.toInt(),
                tunnelOptionsWithoutMtu.tunFirewallMode.mode,
                tunnelOptionsWithoutMtu.ptMode.id
            )
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG err set tun mode: ${e.message}", e)
        }
    }

    suspend fun setRDNS() {
        // always set the remote blocklist
        setRDNSRemote()

        // Set brave dns to tunnel - Local/Remote
        Logger.d(LOG_TAG_VPN, "$TAG set brave dns to tunnel (local/remote)")

        // enable local blocklist if enabled
        if (persistentState.blocklistEnabled && !isPlayStoreFlavour()) {
            setRDNSLocal()
        } else {
            try {
                // remove local blocklist, if any
                getRDNSResolver()?.setRdnsLocal(null, null, null, null)
                Logger.i(LOG_TAG_VPN, "$TAG local-rdns disabled")
            } catch (_: Exception) { }
        }
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
                Intra.addDNSCryptRelay(tunnel, it.togs()) // entire url is the id
            } catch (ex: Exception) {
                Logger.e(LOG_TAG_VPN, "$TAG connect-tunnel: dnscrypt failure", ex)
                appConfig.removeDnscryptRelay(it)
                removeResolver(it)
            }
        }
    }

    private suspend fun removeDnscryptRelaysIfAny() {
        val routes: String = appConfig.getDnscryptRelayServers()
        routes.split(",").forEach {
            if (it.isBlank()) return@forEach

            Logger.i(LOG_TAG_VPN, "$TAG remove dnscrypt relay: $it")
            // remove from appConfig, as this is not from ui, but from the tunnel start up
            appConfig.removeDnscryptRelay(it)
            removeResolver(it)
        }
    }

    suspend fun addDnscryptRelay(relay: DnsCryptRelayEndpoint) {
        if (!tunnel.isConnected) {
            Logger.e(LOG_TAG_VPN, "$TAG no tunnel, skip add dnscrypt relay")
            return
        }
        try {
            Intra.addDNSCryptRelay(tunnel, relay.dnsCryptRelayURL.togs())
            Logger.i(LOG_TAG_VPN, "$TAG new dnscrypt relay: ${relay.dnsCryptRelayURL}")
        } catch (e: Exception) {
            Logger.e(
                LOG_TAG_VPN,
                "$TAG connect-tunnel: dnscrypt relay failure: ${relay.dnsCryptRelayURL}",
                e
            )
            appConfig.removeDnscryptRelay(relay.dnsCryptRelayURL)
            removeResolver(relay.dnsCryptRelayURL)
        }
    }

    suspend fun removeDnscryptRelay(relay: DnsCryptRelayEndpoint) {
        if (!tunnel.isConnected) {
            Logger.e(LOG_TAG_VPN, "$TAG no tunnel, skip remove dnscrypt relay")
            return
        }
        try {
            // no need to remove from appConfig, as it is already removed
            val rmv = getResolver()?.remove(relay.dnsCryptRelayURL.togs())
            Logger.i(
                LOG_TAG_VPN,
                "$TAG rmv dnscrypt relay: ${relay.dnsCryptRelayURL}, success? $rmv"
            )
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
            if (url.isEmpty()) {
                Logger.e(LOG_TAG_VPN, "$TAG connect-tunnel: err empty socks5 url")
                return
            }
            val id =
                if (tunProxyMode.isTunProxyOrbot()) {
                    ProxyManager.ID_ORBOT_BASE
                } else {
                    ProxyManager.ID_S5_BASE
                }
            val res = getProxies()?.addProxy(id.togs(), url.togs())
            Logger.i(LOG_TAG_VPN, "$TAG socks5 set with url($id): $url, success? ${res != null}")
        } catch (e: Exception) {
            Logger.e(
                LOG_TAG_VPN,
                "$TAG connect-tunnel: err start proxy $userName@$ipAddress:$port",
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
        try {
            // socks5://<username>:<password>@<ip>:<port>
            // convert string to url
            val proxyUrl = StringBuilder()
            proxyUrl.append("socks5://")
            if (!userName.isNullOrEmpty()) {
                val encUser = URLEncoder.encode(userName, Charsets.UTF_8.name())
                proxyUrl.append(encUser)
                if (!password.isNullOrEmpty()) {
                    val encPass = URLEncoder.encode(password, Charsets.UTF_8.name())
                    proxyUrl.append(":")
                    proxyUrl.append(encPass)
                }
                proxyUrl.append("@")
            }
            proxyUrl.append(ipAddress)
            proxyUrl.append(":")
            proxyUrl.append(port)
            return URI.create(proxyUrl.toString()).toASCIIString()
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG err construct sock5 url: ${e.message}")
        }
        return ""
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
            tunnel.proxies.hop(hop.togs(), origin.togs())
            Logger.i(LOG_TAG_VPN, "$TAG new hop for $origin -> $hop")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG err setting hop for $origin -> $hop; ${e.message}")
            showHopFailureNotification(origin, hop, err = e.message)
        }
    }

    fun hopStatus(src: String, hop: String): Pair<Long?, String> {
        return try {
            val status = tunnel.proxies.getProxy(src.togs()).router().via().status()
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
            tunnel.proxies.hop("".togs(), src.togs())
            Logger.i(LOG_TAG_VPN, "$TAG removed hop for $src -> empty")
            return Pair(true, context.getString(R.string.config_add_success_toast))
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG err removing hop: $src -> empty; ${e.message}")
            err = e.message ?: "err removing hop"
        }
        return Pair(false, err)
    }

    fun testHop(src: String, hop: String): Pair<Boolean, String?> {
        // returns empty on success, err msg on failure
        val s = tunnel.proxies.testHop(hop.togs(), src.togs()).tos()
        val res = s.isNullOrEmpty()
        val err = s
        if (!res) {
            Logger.w(LOG_TAG_VPN, "$TAG err testing hop: $src -> $hop; $s")
        } else {
            Logger.i(LOG_TAG_VPN, "$TAG test hop success: $src -> $hop")
        }
        return Pair(res, err)
    }

    private fun showHopFailureNotification(src: String, hop: String, err: String? = "") {
        val notifChannelId = "hop_failure_channel"
        ui {
            val notificationManager =
                context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager

            if (isAtleastO()) {
                val channelName = context.getString(R.string.hop_failure_notification_title)
                val importance = NotificationManager.IMPORTANCE_DEFAULT
                val channel = NotificationChannel(notifChannelId, channelName, importance)
                notificationManager.createNotificationChannel(channel)
            }
            val msg = context.getString(
                R.string.hop_failure_toast,
                src,
                hop
            ) + if (!err.isNullOrEmpty()) " ($err)" else ""
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
            val p = getProxies()?.getProxy(id.togs())
            if (p == null) {
                Logger.w(LOG_TAG_VPN, "$TAG wireguard proxy not found for id: $id")
                return
            }
            Intra.addProxyDNS(tunnel, p) // dns transport has same id as the proxy (p)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG wireguard dns failure($id)", e)
            removeResolver(id)
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
            Logger.w(
                LOG_TAG_VPN,
                "$TAG could not fetch socks5 details for proxyMode: $tunProxyMode"
            )
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
            val status = getProxies()?.getProxy(id.togs())?.status()
            Logger.d(LOG_TAG_VPN, "$TAG proxy status($id): $status")
            Pair(status, "")
        } catch (ex: Exception) {
            Logger.i(LOG_TAG_VPN, "$TAG err getProxy($id), reason: ${ex.message}")
            Pair(null, ex.message ?: "")
        }
    }

    private suspend fun setHttpProxyIfNeeded(tunProxyMode: AppConfig.TunProxyMode) {
        if (!AppConfig.ProxyType.of(appConfig.getProxyType()).isProxyTypeHasHttp()) return

        try {
            val endpoint: ProxyEndpoint
            val id =
                if (tunProxyMode.isTunProxyOrbot()) {
                    val orbotEndpoint = appConfig.getOrbotHttpEndpoint()
                    if (orbotEndpoint == null) {
                        Logger.e(LOG_TAG_VPN, "$TAG could not fetch Orbot HTTP endpoint for proxyMode: $tunProxyMode")
                        return
                    }
                    endpoint = orbotEndpoint
                    ProxyManager.ID_ORBOT_BASE
                } else {
                    val httpEndpoint = appConfig.getHttpProxyDetails()
                    if (httpEndpoint == null) {
                        Logger.e(LOG_TAG_VPN, "$TAG could not fetch http proxy details for proxyMode: $tunProxyMode")
                        return
                    }
                    endpoint = httpEndpoint
                    ProxyManager.ID_HTTP_BASE
                }
            val httpProxyUrl = endpoint.proxyIP ?: return

            val p = getProxies()?.addProxy(id.togs(), httpProxyUrl.togs())
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
            Logger.e(LOG_TAG_VPN, "$TAG no tunnel, skip refreshing wg")
            return
        }
        try {
            val wgId = ID_WG_BASE + id
            getProxies()?.removeProxy(wgId.togs())
            removeResolver(wgId)
            Logger.i(LOG_TAG_VPN, "$TAG remove wireguard proxy with id: $id")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG err removing wireguard proxy: ${e.message}", e)
        }
    }

    suspend fun addWgProxy(id: String, force: Boolean = false) {
        if (!tunnel.isConnected) {
            Logger.e(LOG_TAG_VPN, "$TAG no tunnel, skip add wg")
            return
        }
        try {
            val proxyId: Int? = id.substring(ID_WG_BASE.length).toIntOrNull()
            if (proxyId == null) {
                Logger.e(LOG_TAG_VPN, "$TAG invalid wg proxy id: $id")
                return
            }
            try {
                if (!force && getProxies()?.getProxy(id.togs()) != null) {
                    Logger.i(LOG_TAG_VPN, "$TAG wg proxy already exists in tunnel $id")
                    return
                }
            } catch (_: Exception) {
                Logger.i(LOG_TAG_VPN, "$TAG wg proxy not found in tunnel $id, proceed adding")
            }

            val wgConfig = WireguardManager.getConfigById(proxyId)
            val isOneWg = WireguardManager.getOneWireGuardProxyId() == proxyId
            if (!isOneWg) checkAndAddProxyForHopIfNeeded(id)
            val skipListenPort = !isOneWg && persistentState.randomizeListenPort
            val wgUserSpaceString = wgConfig?.toWgUserspaceString(skipListenPort)
            val p = getProxies()?.addProxy(id.togs(), wgUserSpaceString.togs())
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
        } catch (_: Exception) {}
    }

    suspend fun closeConnections(connIds: List<String>, isUid: Boolean = false, reason: String) {
        if (!tunnel.isConnected) {
            Logger.e(LOG_TAG_VPN, "$TAG no tunnel, skip closeConns")
            return
        }

        if (connIds.isEmpty()) {
            val res = tunnel.closeConns("") // closes all connections
            Logger.i(LOG_TAG_VPN, "$TAG close all connections($connIds), res: $res")
            return
        }

        val connIdsStr = connIds.joinToString(",")
        // there maybe chance that the res can be empty, when the conns are already closed
        val res = tunnel.closeConns(connIdsStr)
        val closedConns = res.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        // check if all connections are closed. for any connIds not present in the result,
        // mark their database entries as closed. These connections were either closed when
        // the tunnel was disconnected or were missed during database update.
        val diff = connIds.filter { !closedConns.contains(it) }
        // check if the elements in the diff has the size equal to the connIds size,
        // there can be case where the connIds contains uid,
        if (diff.isNotEmpty()) {
            try {
                if (isUid) {
                    // close the connections for all the uids in the diff
                    val uids = diff.mapNotNull { it.toIntOrNull() }
                    connTrackerDb.closeConnectionForUids(uids, reason)
                    Logger.i(LOG_TAG_VPN, "$TAG closeConns: $connIds, res: $res, uids: $uids, reason: $reason")
                } else {
                    connTrackerDb.closeConnections(diff, reason)
                    Logger.i(LOG_TAG_VPN, "$TAG closeConns: $connIds, res: $res, ids: $diff, reason: $reason")
                }
            } catch (e: Exception) {
                Logger.i(LOG_TAG_VPN, "$TAG err closing connections: ${e.message}")
            }
        }
        Logger.i(LOG_TAG_VPN, "$TAG close connection: $connIds, res: $res")
    }

    suspend fun refreshOrPauseOrResumeOrReAddProxies(isMobileActive: Boolean, ssid: String) {
        if (!tunnel.isConnected) {
            Logger.e(LOG_TAG_VPN, "$TAG no tunnel, skip refreshing proxies")
            return
        }
        try {
            // refresh proxies should never return error/exception
            val res = getProxies()?.refreshProxies()
            Logger.i(LOG_TAG_VPN, "$TAG wg refresh proxies: $res, mobile? $isMobileActive, ssid? $ssid")
            // re-add the proxies if the its not available in the tunnel
            val wgConfigs: List<Config> = WireguardManager.getActiveConfigs()
            if (wgConfigs.isEmpty()) {
                Logger.i(LOG_TAG_VPN, "$TAG no active wg-configs found")
                return
            }
            // re-add wireguard proxies in case of failure, consider proxy stats TNT as a failure
            // TNT means proxy UP but not responding
            wgConfigs.forEach { it ->
                val id = ID_WG_BASE + it.getId()
                val files = WireguardManager.getConfigFilesById(it.getId())
                // skip one-wg proxy, mobile-only doesn't apply
                val isWireGuardMobileOnly = files?.useOnlyOnMetered == true && !files.oneWireGuard
                val canResumeMobileWg = isWireGuardMobileOnly && isMobileActive

                val useOnlyOnSsid = files?.ssidEnabled == true && !files.oneWireGuard
                val configuredSsids = files?.ssids ?: ""
                val ssidMatch = WireguardManager.matchesSsidList(configuredSsids, ssid) && ssid.isNotEmpty()
                val canResumeSsidWg = useOnlyOnSsid && ssidMatch

                val canResume = canResumeMobileWg || canResumeSsidWg

                Logger.d(
                    LOG_TAG_VPN,
                    "$TAG refresh proxy: $id, mobileOnly: $isWireGuardMobileOnly, " +
                        "canResumeMobileWg: $canResumeMobileWg, canResumeSsidWg: $canResumeSsidWg, isMobileActive: $isMobileActive, " +
                        "useOnlyOnSsid: $useOnlyOnSsid, ssidMatch: $ssidMatch, ssid: $ssid, canResume: $canResume, wg-ssids: $configuredSsids"
                )
                val stats = getProxyStatusById(id).first
                if (stats == null || stats == Backend.TNT) {
                    Logger.w(LOG_TAG_VPN, "$TAG proxy stats for $id is null or tnt, $stats, re-adding")
                    // there are cases where the proxy needs to be re-added, so pingOrReAddProxy
                    // case: some of the wg proxies are added to tunnel but erring out, so
                    // re-adding those proxies seems working, work around for now
                    // now re-add logic is handled in go-tun
                    // (github.com/celzero/firestack/blob/61187f88c1/intra/ipn/wgproxy.go#L404)
                    addWgProxy(id, true)
                }
                if (stats == Backend.TPU && canResume) {
                    // if the proxy is paused, then resume it
                    // this is needed when the tunnel is reconnected and the proxies are paused
                    // so resume them, also when there is switch in wg-config for useOnlyOnMetered
                    // or ssid change for ssidEnabled wgs
                    val res = getProxies()?.getProxy(id.togs())?.resume()
                    Logger.i(LOG_TAG_VPN, "$TAG resumed proxy: $id, res: $res")
                } else if (isWireGuardMobileOnly && !isMobileActive && !canResume) {
                    // if the proxy is not paused, then pause it
                    // this is needed when the network is on mobile data
                    // and the wg-config is set to useOnlyOnMetered
                    val res = getProxies()?.getProxy(id.togs())?.pause()
                    Logger.i(LOG_TAG_VPN, "$TAG paused proxy (mobile): $id, res: $res")
                } else if (useOnlyOnSsid && !ssidMatch && !canResume) {
                    // when the ssidEnabled is set and the ssid does not match
                    val res = getProxies()?.getProxy(id.togs())?.pause()
                    Logger.i(LOG_TAG_VPN, "$TAG paused proxy (ssid): $id, res: $res")
                }

                if (stats == Backend.TPU && !isWireGuardMobileOnly && !useOnlyOnSsid) {
                    // if the proxy is paused, then resume it
                    // this is needed when the tunnel is reconnected and the proxies are paused
                    val res = getProxies()?.getProxy(id.togs())?.resume()
                    Logger.i(LOG_TAG_VPN, "$TAG resumed proxy (non-metered/ssid): $id, res: $res")
                }
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG err refreshing proxies: ${e.message}", e)
        }
    }

    suspend fun getProxyStats(id: String): RouterStats? {
        return try {
            val stats = getProxies()?.getProxy(id.togs())?.router()?.stat()
            stats
        } catch (e: Exception) {
            Logger.w(LOG_TAG_VPN, "$TAG err getting proxy stats($id): ${e.message}")
            null
        }
    }

    suspend fun getWireGuardStats(id: String): WireguardManager.WgStats? {
        return try {
            val proxy = getProxies()?.getProxy(id.togs())
            val status = proxy?.status()

            val router = proxy?.router()
            val stat = router?.stat()
            val mtu = router?.mtu()
            val ip4 = router?.iP4()
            val ip6 = router?.iP6()

            WireguardManager.WgStats(stat, mtu, status, ip4, ip6)
        } catch (e: Exception) {
            Logger.w(LOG_TAG_VPN, "$TAG err getting wg stats($id): ${e.message}")
            null
        }
    }

    suspend fun pauseWireguard(id: String): Boolean {
        return try {
            val res = getProxies()?.getProxy(id.togs())?.pause()
            Logger.i(LOG_TAG_VPN, "$TAG paused wg proxy: $id, res: $res")
            res ?: false
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG err pausing wg proxy($id): ${e.message}", e)
            false
        }
    }

    suspend fun resumeWireguard(id: String): Boolean {
        return try {
            val res = getProxies()?.getProxy(id.togs())?.resume()
            Logger.i(LOG_TAG_VPN, "$TAG resumed wg proxy: $id, res: $res")
            res ?: false
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG err resuming wg proxy($id): ${e.message}", e)
            false
        }
    }

    fun getNetStat(): NetStat? {
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
            val transport = getResolver()?.get(id.togs())
            val tid = transport?.id()
            val status = transport?.status()
            // some special transports like blockfree, preferred, alg etc are handled specially.
            // in those cases, if the transport id is not there, it will serve the default transport
            // so return null in those cases
            if (tid.tos() != id) {
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

    suspend fun getRDNS(type: RethinkBlocklistManager.RethinkBlocklistType): RDNS? {
        try {
            return if (type.isLocal() && !isPlayStoreFlavour()) {
                getRDNSResolver()?.rdnsLocal
            } else {
                getRDNSResolver()?.rdnsRemote
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG err getRDNS($type): ${e.message}", e)
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
            Logger.e(LOG_TAG_VPN, "$TAG no tunnel, skip refreshing resolvers")
            return
        }

        val id = if (appConfig.isSmartDnsEnabled()) Backend.Plus else Backend.Preferred
        val mainDnsOK = getDnsStatus(id) != null
        Logger.i(LOG_TAG_VPN, "preferred/plus set? ${mainDnsOK}, if not set it again")

        if (!mainDnsOK) {
            addTransport()
        }

        Logger.i(LOG_TAG_VPN, "$TAG refresh resolvers")
        try {
            getResolver()?.refresh()
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG err refreshing resolvers: ${e.message}", e)
        }
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

    private fun newDefaultTransport(url: String): DefaultDNS? {
        val fallbackDns = getDefaultFallbackDns()
        try {
            // when the url is empty, set the default transport to 9.9.9.9, 2620:fe::fe or
            // null based on the value of SKIP_DEFAULT_DNS_ON_INIT
            if (url.isEmpty()) { // empty url denotes default dns set to none (system dns)
                if (SKIP_DEFAULT_DNS_ON_INIT && !persistentState.routeRethinkInRethink) {
                    Logger.i(LOG_TAG_VPN, "$TAG set default dns to null, as url is empty")
                    return null
                }
                Logger.i(LOG_TAG_VPN, "$TAG set default dns to $fallbackDns, as url is empty")
                return Intra.newDefaultDNS(Backend.DNS53.togs(), fallbackDns.togs(), "".togs())
            }
            val ips: String = getIpString(context, url)
            Logger.d(LOG_TAG_VPN, "$TAG default dns url: $url ips: $ips")
            val res = if (url.contains("http")) {
                Intra.newDefaultDNS(Backend.DOH.togs(), url.togs(), ips.togs())
            } else {
                // no need to set ips for dns53
                Intra.newDefaultDNS(Backend.DNS53.togs(), url.togs(), "".togs())
            }
            Logger.i(LOG_TAG_VPN, "$TAG new default dns: $url, $ips, success? ${res != null}")
            return res
        } catch (e: Exception) {
            Logger.w(LOG_TAG_VPN, "$TAG err new default dns($url): ${e.message}", e)
            // most of the android devices have google dns, so add it as default transport
            // TODO: notify the user that the default transport could not be set
            try {
                Logger.i(LOG_TAG_VPN, "$TAG; fallback; set default dns to $fallbackDns")
                return Intra.newDefaultDNS(Backend.DNS53.togs(), fallbackDns.togs(), "".togs())
            } catch (e: Exception) {
                Logger.crash(LOG_TAG_VPN, "$TAG err add $fallbackDns dns: ${e.message}", e)
                return null
            }
        }
    }

    private fun getDefaultFallbackDns(): String {
        return try {
            val netDns1 = System.getProperty("net.dns1")
            val netDns2 = System.getProperty("net.dns2")
            if (netDns1?.isNotEmpty() == true && netDns2?.isNotEmpty() == true) {
                "$netDns1,$netDns2"
            } else if (netDns1?.isNotEmpty() == true) {
                netDns1
            } else if (netDns2?.isNotEmpty() == true) {
                netDns2
            } else {
                FALLBACK_DNS_IF_NET_DNS_EMPTY
            }
        } catch (_: Exception) {
            FALLBACK_DNS_IF_NET_DNS_EMPTY
        }
    }

    suspend fun addDefaultTransport(url: String?) {
        if (!tunnel.isConnected) {
            Logger.e(LOG_TAG_VPN, "$TAG no tunnel, skip add default dns")
            return
        }
        var type = Backend.DNS53
        val fallbackUrl = getDefaultFallbackDns()

        val usingSysDnsAsDefaultDns = isDefaultDnsNone()
        var usingGoos = fallbackUrl.isEmpty() // always false here, as fallbackUrl is not empty
        // make the tun to use goos as default transport if the system dns is set as none in
        // persistent state and not in rinr mode, as in that case the system dns
        if (SET_SYS_DNS_AS_DEFAULT_IFF_LOOPBACK && !persistentState.routeRethinkInRethink) {
            usingGoos = usingSysDnsAsDefaultDns
        }

        // default transport is always sent to Ipn.Exit in the go code and so dns
        // request sent to the default transport will not be looped back into the tunnel
        try {
            // when the url is empty, set the default transport to fallbackUrl
            if (url.isNullOrEmpty()) {
                Logger.i(LOG_TAG_VPN, "$TAG url empty, set default dns to $type, $fallbackUrl, usingGoos: $usingGoos")
                if (usingGoos) {
                    Intra.addDefaultTransport(tunnel, "".togs(), "".togs(), "".togs())
                } else {
                    Intra.addDefaultTransport(tunnel, type.togs(), fallbackUrl.togs(), "".togs())
                }
                return
            } else if (url.contains("http")) {
                type = Backend.DOH
            }

            val ips: String = getIpString(context, url)
            Intra.addDefaultTransport(tunnel, type.togs(), url.togs(), ips.togs())
            Logger.i(LOG_TAG_VPN, "$TAG default dns set, url: $url ips: $ips, type: $type")
        } catch (e: Exception) {
            Logger.w(LOG_TAG_VPN, "$TAG err new default dns($url): ${e.message}", e)
            // most of the android devices have google dns, so add it as default transport
            // TODO: notify the user that the default transport could not be set
            try {
                if (usingGoos) {
                    Logger.i(LOG_TAG_VPN, "$TAG; fallback; set empty default dns, usingGoos: $usingGoos")
                    Intra.addDefaultTransport(tunnel, "".togs(), "".togs(), "".togs())
                } else {
                    Logger.i(LOG_TAG_VPN, "$TAG; fallback; set default dns to $fallbackUrl")
                    Intra.addDefaultTransport(tunnel, type.togs(), fallbackUrl.togs(), "".togs())
                }
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

    suspend fun getSystemDns(): String? {
        return try {
            val sysDns = getResolver()?.get(Backend.System.togs())?.addr.tos()
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
            Logger.e(LOG_TAG_VPN, "$TAG no tunnel, skip setting system-dns")
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
            Intra.setSystemDNS(tunnel, sysDnsStr.togs())
        } catch (e: Exception) { // this is not expected to happen
            Logger.e(LOG_TAG_VPN, "$TAG set system dns: could not parse: $systemDns", e)
            // remove the system dns, if it could not be set
            removeResolver(Backend.System)
        }
    }

    suspend fun updateLinkAndRoutes(
        tunFd: Long,
        mtu: Int,
        proto: Long
    ): Boolean {
        if (!tunnel.isConnected) {
            Logger.e(LOG_TAG_VPN, "$TAG updateLink: tunnel disconnected, returning")
            return false
        }

        Logger.i(LOG_TAG_VPN, "$TAG updateLink with fd(${tunFd}) mtu: $mtu")
        return try {
            tunnel.setLinkAndRoutes(tunFd, mtu.toLong(), proto)
            true
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG err update tun: ${e.message}", e)
            false
        }
    }

    suspend fun restartTunnel(tunFd: Long, mtu: Int, proto: Long): Boolean {
        if (!tunnel.isConnected) {
            Logger.e(LOG_TAG_VPN, "$TAG restartTunnel: tunnel is not connected, returning")
            return false
        }

        Logger.i(LOG_TAG_VPN, "$TAG restarting tunnel")
        try {
            tunnel.restart(tunFd, mtu.toLong(), proto)
            return true
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG error restarting tunnel: ${e.message}", e)
            return false
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
                Logger.i(
                    LOG_TAG_VPN,
                    "$TAG set local stamp: ${persistentState.localBlocklistStamp}"
                )
                rl.stamp = persistentState.localBlocklistStamp.togs()
            } else {
                Logger.w(LOG_TAG_VPN, "$TAG mode is not local, this should not happen")
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG could not set local stamp: ${e.message}", e)
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

            persistentState.localBlocklistStamp =
                rl.stamp.tos() ?: "" // throws exception if stamp is invalid
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
            rdns?.rdnsLocal?.stamp = stamp.togs()
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

        fun setLogLevel(l1: Int, l2: Int = Logger.uiLogLevel.toInt()) {
            // 0 - very verbose, 1 - verbose, 2 - debug, 3 - info, 4 - warn, 5 - error, 6 - stacktrace, 7 - user, 8 - none
            // from UI, if none is selected, set the log level to 7 (user), usr will send only
            // user notifications
            // there is no user level shown in UI, so if the user selects none, set the log level
            // to 8 (none)
            val goLogLevel = if (l1 == 7) 8 else l1
            val consoleLogLevel = if (l2 == 7) 8 else l2
            Intra.logLevel(goLogLevel, consoleLogLevel)
            Logger.i(LOG_TAG_VPN, "$TAG set go-log level: $l1, $l2")
        }
    }

    suspend fun p50(id: String): Long {
        try {
            val transport = getResolver()?.get(id.togs())
            return transport?.p50() ?: -1L
        } catch (e: Exception) {
            Logger.w(LOG_TAG_VPN, "$TAG err p50($id): ${e.message}")
        }
        return -1L
    }

    private suspend fun getResolver(): DNSResolver? {
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

    private suspend fun getRDNSResolver(): DNSResolver? {
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

    private suspend fun getProxies(): Proxies? {
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
            val router =
                getProxies()?.getProxy(proxyId.togs())?.router() ?: return Pair(false, false)
            val has4 = router.iP4()
            val has6 = router.iP6()
            Logger.d(LOG_TAG_VPN, "$TAG supported ip version($proxyId): has4? $has4, has6? $has6")
            return Pair(has4, has6)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG err supported ip version($proxyId): ${e.message}")
        }
        return Pair(false, false)
    }

    suspend fun isSplitTunnelProxy(proxyId: String, pair: Pair<Boolean, Boolean>): Boolean {
        return try {
            val router = getProxies()?.getProxy(proxyId.togs())?.router() ?: return false
            // if the router contains 0.0.0.0, then it is not split tunnel for ipv4
            // if the router contains ::, then it is not split tunnel for ipv6
            val res: Boolean =
                if (pair.first && pair.second) {
                    // if the pair is true, check for both ipv4 and ipv6
                    !router.contains(UNSPECIFIED_IP_IPV4.togs()) || !router.contains(
                        UNSPECIFIED_IP_IPV6.togs()
                    )
                } else if (pair.first) {
                    !router.contains(UNSPECIFIED_IP_IPV4.togs())
                } else if (pair.second) {
                    !router.contains(UNSPECIFIED_IP_IPV6.togs())
                } else {
                    false
                }

            Logger.d(
                LOG_TAG_VPN,
                "$TAG split tunnel proxy($proxyId): ipv4? ${pair.first}, ipv6? ${pair.second}, res? $res"
            )
            res
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG err isSplitTunnelProxy($proxyId): ${e.message}")
            false
        }
    }

    suspend fun initiateWgPing(proxyId: String) {
        try {
            val res = getProxies()?.getProxy(proxyId.togs())?.ping()
            Logger.i(LOG_TAG_VPN, "$TAG initiateWgPing($proxyId): $res")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG err initiateWgPing($proxyId): ${e.message}")
        }
    }

    suspend fun getActiveProxiesIpAndMtu(): BraveVPNService.OverlayNetworks {
        try {
            val router = getProxies()?.router() ?: return BraveVPNService.OverlayNetworks()
            val has4 = router.iP4()
            val has6 = router.iP6()
            val failOpen = !router.iP4() && !router.iP6()
            val mtu = router.mtu().toInt()
            Logger.i(
                LOG_TAG_VPN,
                "$TAG proxy ip version, has4? $has4, has6? $has6, failOpen? $failOpen"
            )
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

    suspend fun setDialStrategy(
        mode: Int = persistentState.dialStrategy,
        retry: Int = persistentState.retryStrategy,
        tcpKeepAlive: Boolean = persistentState.tcpKeepAlive,
        timeoutSec: Int = persistentState.dialTimeoutSec
    ) {
        if (!tunnel.isConnected) {
            Logger.e(LOG_TAG_VPN, "$TAG no tunnel, skip set dial strategy")
            return
        }
        try {
            Settings.setDialerOpts(mode, retry, timeoutSec, tcpKeepAlive)
            Logger.i(
                LOG_TAG_VPN,
                "$TAG set dial strategy: $mode, retry: $retry, tcpKeepAlive: $tcpKeepAlive, timeout: $timeoutSec"
            )
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG err set dial strategy: ${e.message}", e)
        }
    }

    suspend fun setTransparency(eim: Boolean = persistentState.endpointIndependence) {
        if (!tunnel.isConnected) {
            Logger.e(LOG_TAG_VPN, "$TAG no tunnel, skip transparency")
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
            Logger.e(LOG_TAG_VPN, "$TAG no tunnel, skip undelegated domains")
            return
        }
        try {
            Intra.undelegatedDomains(useSystemDns)
            Logger.i(LOG_TAG_VPN, "$TAG undelegated domains: $useSystemDns")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG err undelegated domains: ${e.message}", e)
        }
    }

    suspend fun getRpnProps(rpnType: RpnProxyManager.RpnType): Pair<RpnProxyManager.RpnProps?, String?> {
        try {
            var errMsg: String? = ""
            val rpn: RpnProxy? = try {
                when (rpnType) {
                    RpnProxyManager.RpnType.WIN -> {
                        tunnel.proxies.rpn().win()
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

            val id = rpn.id().tos() ?: ""
            val status = rpn.status()
            val type = rpn.type().tos() ?: ""
            val kids = rpn.kids().tos() ?: ""
            val addr = rpn.addr.tos() ?: ""
            val created = rpn.created()
            val expires = rpn.expires()
            val locations = rpn.locations()
            val who = rpn.who().tos() ?: ""
            val prop = RpnProxyManager.RpnProps(id, status, type, kids, addr, created, expires, who, locations)
            return Pair(prop, errMsg)
        } catch (e: Exception) {
            Logger.w(LOG_TAG_PROXY, "$TAG err rpn props($rpnType): ${e.message}")
            return Pair(null, e.message)
        }
    }

    suspend fun testRpnProxy(proxyId: String): Boolean {
        if (!tunnel.isConnected) {
            Logger.i(LOG_TAG_PROXY, "$TAG no tunnel, skip test rpn proxy")
            return false
        }
        try {
            val ippcsv = tunnel.proxies.rpn().testWin().tos()
            Logger.i(LOG_TAG_PROXY, "$TAG test rpn proxy($proxyId): $ippcsv")
            return !ippcsv.isNullOrEmpty()
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG err test rpn proxy($proxyId): ${e.message}")
        }
        return false
    }

    suspend fun registerAndFetchWinIfNeeded(prevBytes: ByteArray? = null): ByteArray? {
        if (!tunnel.isConnected) {
            Logger.e(LOG_TAG_PROXY, "$TAG no tunnel, skip register win(rpn)")
            return null
        }
        try {
            if (tunnel.proxies.rpn().win() != null && prevBytes != null) {
                Logger.i(LOG_TAG_PROXY, "$TAG win(rpn) already registered")
                return null
            }
        } catch (ignore: Exception) {
            Logger.i(LOG_TAG_PROXY, "$TAG win(rpn) not registered, fall through")
        }
        return try {
            val string = String(prevBytes ?: ByteArray(0), Charsets.UTF_8)
            Logger.i(LOG_TAG_PROXY, "$TAG start win(rpn) reg, existing bytes size: ${prevBytes?.size}, value: $string")
            val bytes = tunnel.proxies.rpn().registerWin(prevBytes.togb()).tob()
            Logger.i(LOG_TAG_PROXY, "$TAG win(rpn) registered, ${bytes?.size} bytes")
            bytes
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG err register win(rpn): ${e.message}", e)
            null
        }
    }

    suspend fun isWinRegistered(): Boolean {
        if (!tunnel.isConnected) {
            Logger.i(LOG_TAG_PROXY, "$TAG no tunnel, skip is win(rpn) registered")
            return false
        }
        return try {
            val res = tunnel.proxies.rpn().win() != null
            Logger.i(LOG_TAG_PROXY, "$TAG is win(rpn) registered? $res")
            res
        } catch (_: Exception) {
            false
        }
    }

    suspend fun getWinLastConnectedTs(): Long? {
        if (!tunnel.isConnected) {
            Logger.i(LOG_TAG_PROXY, "$TAG no tunnel, skip last connected time of win(rpn)")
            return null
        }
        return try {
            val time = tunnel.proxies.rpn().win().router().stat().lastOK
            Logger.i(LOG_TAG_PROXY, "$TAG last connected time of win(rpn): $time")
            time
        } catch (e: Exception) {
            Logger.w(LOG_TAG_PROXY, "$TAG err last connected time of win(rpn): ${e.message}")
            null
        }
    }

    suspend fun updateWin(): ByteArray? {
        if (!tunnel.isConnected) {
            Logger.i(LOG_TAG_PROXY, "$TAG no tunnel, skip update win(rpn)")
            return null
        }
        return try {
            val bytes = tunnel.proxies.rpn().win().update().tob()
            Logger.i(LOG_TAG_PROXY, "$TAG updated win(rpn), ${bytes?.size} bytes")
            bytes
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG err update win(rpn): ${e.message}", e)
            throw e
        }
    }

    suspend fun addNewWinServer(server: RpnWinServerEntity): Pair<Boolean, String> {
        if (!tunnel.isConnected) {
            Logger.i(LOG_TAG_PROXY, "$TAG no tunnel, skip add new win(rpn) server")
            return Pair(false, "No tunnel connected")
        }
        if (server.countryCode.isEmpty()) {
            Logger.w(LOG_TAG_PROXY, "$TAG empty country code for new win(rpn) server")
            return Pair(false, "Empty country code for server")
        }
        return try {
            val win = tunnel.proxies.rpn().win()

            val prevServerCount = win.kids().tos()?.split(",")?.size ?: 0
            if (prevServerCount >= RpnProxyManager.MAX_WIN_SERVERS) {
                Logger.w(LOG_TAG_PROXY, "$TAG max win servers reached: $prevServerCount, skipping add")
                return Pair(false, "Max servers reached: $prevServerCount, skipping add")
            }
            val name = server.countryCode.togs()
            val res = win.fork(name)
            Logger.i(LOG_TAG_PROXY, "$TAG add new win(rpn) server: $res")
            return Pair(true, "Added new server: $name")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG err add new win(rpn) server: ${e.message}", e)
            Pair(false, e.message ?: "Error adding new server")
        }
    }

    suspend fun unregisterWin(): Boolean {
        if (!tunnel.isConnected) {
            Logger.i(LOG_TAG_PROXY, "$TAG no tunnel, skip unregister win(rpn)")
            return false
        }
        return try {
            val res = tunnel.proxies.rpn().unregisterWin()
            Logger.i(LOG_TAG_PROXY, "$TAG unregister win(rpn): $res")
            res
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG err unregister win(rpn): ${e.message}", e)
            false
        }
    }

    suspend fun setRpnAutoMode(): Boolean {
        if (!tunnel.isConnected) {
            Logger.i(LOG_TAG_PROXY, "$TAG no tunnel, skip set rpn auto mode")
            return false
        }
        if (!RpnProxyManager.isRpnEnabled()) {
            Logger.i(LOG_TAG_PROXY, "$TAG rpn is not enabled, skip set rpn auto mode")
            return false
        }
        return try {
            val mode = RpnProxyManager.RpnTunMode.getTunModeForAuto()
            val prev = Settings.setAutoMode(mode)
            if (!RpnProxyManager.rpnMode().isNone()) { // reset if mode is anti-censorship/hide ip
                // set dial strategy to split_auto and retry to retry_after_split regardless
                // of what is set in the settings
                val dialMode = AntiCensorshipActivity.DialStrategies.SPLIT_AUTO.mode
                val retryMode = AntiCensorshipActivity.RetryStrategies.RETRY_AFTER_SPLIT.mode
                setDialStrategy(mode = dialMode, retry = retryMode)
            } else {
                // set dial strategy to default values
                setDialStrategy()
            }
            Logger.i(LOG_TAG_PROXY, "$TAG set auto mode to: $mode, prev? $prev")
            true
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG err set rpn auto mode: ${e.message}", e)
            false
        }
    }

    suspend fun isProxyReachable(
        proxyId: String,
        csv: String
    ): Boolean { // can be ippcsv or hostpcsv
        if (!tunnel.isConnected) {
            Logger.i(LOG_TAG_PROXY, "$TAG no tunnel, skip ping proxy")
            return false
        }
        return try {
            val res = tunnel.proxies.getProxy(proxyId.togs()).router().reaches(csv.togs())
            Logger.i(LOG_TAG_PROXY, "$TAG ping $proxyId? ($csv) $res")
            res
        } catch (e: Exception) {
            Logger.w(LOG_TAG_PROXY, "$TAG err ping $proxyId: ${e.message}")
            false
        }
    }

    suspend fun setExperimentalWireGuardSettings(value: Boolean = persistentState.nwEngExperimentalFeatures) {
        if (!tunnel.isConnected) {
            Logger.e(LOG_TAG_VPN, "$TAG no tunnel, skip set experimental wg settings")
            return
        }
        try {
            // modified from overall experimental settings to only wireguard experimental settings
            Intra.experimentalWireGuard(value)
            // refresh proxies on experimental settings change (required for wireguard)
            //refreshOrReAddProxies()
            Logger.i(LOG_TAG_VPN, "$TAG set experimental wg settings: $value")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG err set experimental wg settings: ${e.message}", e)
        }
    }

    suspend fun setHappyEyeballs(value: Boolean = InternetProtocol.isAlwaysV46(persistentState.internetProtocolType)) {
        if (!tunnel.isConnected) {
            Logger.e(LOG_TAG_VPN, "$TAG no tunnel, skip happy eyeballs setting")
            return
        }
        try {
            Intra.happyEyeballs(value)
            Logger.i(LOG_TAG_VPN, "$TAG set happy eyeballs as $value")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG err; happy eyeballs: ${e.message}", e)
        }
    }

    suspend fun setAutoDialsParallel(value: Boolean = persistentState.autoDialsParallel) {
        if (!tunnel.isConnected) {
            Logger.e(LOG_TAG_VPN, "$TAG no tunnel, skip set auto dial option")
            return
        }
        try {
            Settings.setAutoDialsParallel(value)
            Logger.i(LOG_TAG_VPN, "$TAG set auto dial parallel as $value")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG err set auto dial: ${e.message}", e)
        }
    }

    suspend fun createHop(origin: String, hop: String): Pair<Boolean, String> {
        if (!tunnel.isConnected) {
            Logger.e(LOG_TAG_VPN, "$TAG createHop; no tunnel, skip create hop")
            return Pair(false, "no tunnel")
        }
        // to remove hop there is a separate method, so no need to check for empty
        if (hop.isEmpty()) {
            Logger.i(LOG_TAG_VPN, "$TAG createHop; hop is empty, returning")
            return Pair(false, "hop is empty")
        }
        return try {
            Logger.i(LOG_TAG_VPN, "$TAG create hop: $origin, hop: $hop")
            tunnel.proxies.hop(hop.togs(), origin.togs())
            Pair(true, context.getString(R.string.config_add_success_toast))
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG err create hop: ${e.message}", e)
            Pair(false, e.message ?: "Hop creation failed")
        }
    }

    suspend fun updateRpnProxy(type: RpnProxyManager.RpnType): ByteArray? {
        if (!tunnel.isConnected) {
            Logger.i(LOG_TAG_PROXY, "$TAG no tunnel, skip update rpn proxy")
            return null
        }
        return try {
            val res = when (type) {

                RpnProxyManager.RpnType.WIN -> {
                    tunnel.proxies.rpn().win().update().tob()
                }

                RpnProxyManager.RpnType.EXIT -> {
                    // no need to update exit
                    null
                }
            }
            res
        } catch (e: Exception) {
            Logger.w(LOG_TAG_PROXY, "$TAG err update rpn proxy($type): ${e.message}")
            null
        }
    }

    suspend fun addMultipleDnsAsPlus() {
        if (!tunnel.isConnected) {
            Logger.e(LOG_TAG_VPN, "$TAG; smart-dns; no tunnel, skip set multi dns as plus")
            return
        }

        Logger.vv(LOG_TAG_VPN, "$TAG smart-dns; add multiple doh & dots")
        // DoH endpoints
        val dohList = appConfig.getAllDefaultDoHEndpoints()
        dohList.forEach { doh ->
            try {
                var url: String? = null
                url = doh.dohURL
                // default transport-id(Plus), append index & individual id with this
                val id = Backend.Plus + DOH_INDEX + doh.id

                val ips: String = getIpString(context, url)
                if (ips.isEmpty()) {
                    Logger.i(LOG_TAG_VPN, "$TAG smart-dns; ips empty for $url, skip adding to plus doh")
                    return@forEach
                }
                // change the url from https to http if the isSecure is false
                if (!doh.isSecure) {
                    Logger.d(LOG_TAG_VPN, "$TAG smart-dns; changing url from https to http for $url")
                    url = url.replace("https", "http")
                }
                // add replaces the existing transport with the same id if successful
                // so no need to remove the transport before adding
                Intra.addDoHTransport(tunnel, id.togs(), url.togs(), ips.togs())
                Logger.i(
                    LOG_TAG_VPN, "$TAG smart-dns; new doh: $id (${doh.dohName}), url: $url, ips: $ips"
                )
            } catch (e: Exception) {
                Logger.e(LOG_TAG_VPN, "$TAG smart-dns; err add DoH: ${e.message}", e)
            }
        }

        // DoT endpoints
        val dots = appConfig.getAllDefaultDoTEndpoints()
        dots.forEach { dot ->
            // default transport-id(Plus), append index & individual id with this
            val id = Backend.Plus + DOT_INDEX + dot.id
            var url: String? = null
            try {
                url = dot.url
                // skip mullvad dots
                if (url.contains("mullvad.net") || url.contains("mullvad.org")) {
                    return@forEach
                }
                // if tls is present, remove it and pass it to getIpString
                val ips: String = getIpString(context, url.replace("tls://", ""))
                if (ips.isEmpty()) {
                    Logger.i(LOG_TAG_VPN, "$TAG smart-dns; ips empty for $url, skip adding")
                    return@forEach
                }
                if (dot.isSecure && !url.startsWith("tls")) {
                    Logger.d(LOG_TAG_VPN, "$TAG smart-dns; adding tls to url for $url")
                    // add tls to the url if isSecure is true and the url does not start with tls
                    url = "tls://$url"
                }
                // add replaces the existing transport with the same id if successful
                // so no need to remove the transport before adding
                Intra.addDoTTransport(tunnel, id.togs(), url.togs(), ips.togs())
                Logger.i(LOG_TAG_VPN, "$TAG smart-dns; new dot: $id (${dot.name}), url: $url, ips: $ips")
            } catch (e: Exception) {
                Logger.e(LOG_TAG_VPN, "$TAG smart-dns; dot failure, url: $url", e)
            }
        }
        Logger.v(LOG_TAG_VPN, "$TAG smart-dns; done")
    }

    suspend fun getPlusResolvers(): List<String> {
        if (!tunnel.isConnected) {
            Logger.i(LOG_TAG_VPN, "$TAG no tunnel, skip get plus resolvers")
            return emptyList()
        }
        return try {
            val plusResolvers = tunnel.resolver.getMult(Backend.Plus.togs())
            val resolvers = plusResolvers.liveTransports().tos()?.split(",")?.map { it.trim() }
            Logger.i(LOG_TAG_VPN, "$TAG plus resolvers: $resolvers")
            resolvers ?: emptyList()
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG err get plus resolvers: ${e.message}")
            emptyList()
        }
    }

    suspend fun getPlusTransportById(id: String): DNSTransport? {
        if (!tunnel.isConnected) {
            Logger.w(LOG_TAG_VPN, "$TAG no tunnel, skip get plus transport by id")
            return null
        }
        return try {
            val transport = tunnel.resolver.getMult(Backend.Plus.togs()).get(id.togs())
            Logger.d(LOG_TAG_VPN, "$TAG get plus transport by id($id): ${transport.addr}")
            transport
        } catch (e: Exception) {
            Logger.w(LOG_TAG_VPN, "$TAG err get plus transport by id($id): ${e.message}")
            null
        }
    }

    fun performConnectivityCheck(controller: Controller, id: String, addrPort: String): Boolean {
        if (!tunnel.isConnected) {
            Logger.e(LOG_TAG_VPN, "$TAG no tunnel, skip connectivity check")
            return false
        }
        try {
            val dummyAddrPort = "10.111.222.10:53"
            val router = Intra.controlledRouter(controller, id, dummyAddrPort)
            val res = router.reaches(addrPort.togs())
            Logger.i(LOG_TAG_VPN, "$TAG connectivity check, id($id) for addrPort($addrPort): $res")
            return res
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG err; connectivity check: ${e.message}", e)
        }
        return false
    }

    fun performAutoConnectivityCheck(controller: Controller, id: String, mode: String): Boolean {
        if (!tunnel.isConnected) {
            Logger.e(LOG_TAG_VPN, "$TAG no tunnel, skip auto connectivity check")
            return false
        }
        try {
            val dummyAddrPort = "10.111.222.11:53"
            val router = Intra.controlledRouter(controller, id, dummyAddrPort)
            val res = router.reaches(mode.togs())
            Logger.i(LOG_TAG_VPN, "$TAG auto connectivity check, id($id) for mode($mode): $res")
            return res
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG err; auto connectivity check: ${e.message}")
        }
        return false
    }

    fun setPlusStrategy(option: Long): Tunnel {
        // Settings.PlusFilterSafest, Settings.PlusOrderFastest
        // default value for PlusStrategy is Safest, which is the safest strategy
        // fastest is another strategy, which is not used for now (v055n)
        Settings.setPlusStrategy(Settings.PlusFilterSafest)
        return tunnel
    }

    suspend fun panicAtRandom(shouldPanic: Boolean = persistentState.panicRandom) {
        if (!tunnel.isConnected) {
            Logger.e(LOG_TAG_VPN, "$TAG no tunnel, skip panic at random")
            return
        }
        try {
            Intra.panicAtRandom(shouldPanic)
            Logger.i(LOG_TAG_VPN, "$TAG panic at random: $shouldPanic")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG err panic at random: ${e.message}")
        }
    }

    suspend fun performFlightRecording() {
        if (!DEBUG) return
        if (!tunnel.isConnected) {
            Logger.e(LOG_TAG_VPN, "$TAG no tunnel, skip start flight recorder")
            return
        }

        try {
            Intra.flightRecorder(true)
            // 10 secs delay before stopping the flight recorder
            delay(10 * 1000L)
            val logs = Intra.printFlightRecord(true)
            // write the logs to a file
            val fileName = "fltrcdr_${System.currentTimeMillis()}.pprof"
            val path = context.filesDir.toString() + "/" + "flightrecorder" + "/" + fileName
            val file = File(path)
            file.parentFile?.mkdirs()
            Utilities.writeToFile(file, logs)
            Logger.i(LOG_TAG_VPN, "$TAG flight recorder logs written to: $path")
            Intra.flightRecorder(false)
            Logger.i(LOG_TAG_VPN, "$TAG started flight recorder")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG err start flight recorder: ${e.message}")
        }
    }

    fun tunMtu(): Int {
        if (!tunnel.isConnected) {
            Logger.e(LOG_TAG_VPN, "$TAG no tunnel, skip tun mtu")
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
