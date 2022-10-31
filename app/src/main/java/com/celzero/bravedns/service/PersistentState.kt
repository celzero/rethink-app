/*
 * Copyright 2020 RethinkDNS and its authors
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
package com.celzero.bravedns.service

import android.content.Context
import androidx.lifecycle.MutableLiveData
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS
import com.celzero.bravedns.util.Constants.Companion.INVALID_PORT
import com.celzero.bravedns.util.InternetProtocol
import com.celzero.bravedns.util.Utilities
import hu.autsoft.krate.*
import hu.autsoft.krate.default.withDefault
import org.koin.core.component.KoinComponent

class PersistentState(context: Context) : SimpleKrate(context), KoinComponent {
    companion object {
        const val BRAVE_MODE = "brave_mode"
        const val BACKGROUND_MODE = "background_mode"
        const val ALLOW_BYPASS = "allow_bypass"
        const val LOCAL_BLOCK_LIST = "enable_local_list"
        const val LOCAL_BLOCK_LIST_STAMP = "local_block_list_stamp"
        const val PROXY_TYPE = "proxy_proxytype"
        const val NETWORK = "add_all_networks_to_vpn"
        const val NOTIFICATION_ACTION = "notification_action"
        const val DNS_CHANGE = "connected_dns_name"
        const val DNS_RELAYS = "dnscrypt_relay"
        const val INTERNET_PROTOCOL = "internet_protocol"
        const val PROTOCOL_TRANSLATION = "protocol_translation"

        // const val APP_STATE = "app_state"
        const val REMOTE_BLOCK_LIST_STAMP = "remote_block_list_count"

        fun expandUrl(context: Context, url: String?): String {
            return if (url == null || url.isEmpty()) {
                context.resources.getStringArray(R.array.doh_endpoint_names)[3]
            } else url
        }
    }

    // when vpn is started by the user, this is set to true; set to false when user stops
    // the vpn. In case vpn crashes, this value remains true, which is expected.
    private var _vpnEnabled by booleanPref("enabled").withDefault<Boolean>(false)

    // OOBE (out-of-the-box experience) screens are shown if the user
    // launches the app for the very first time (after install or post clear-data)
    var firstTimeLaunch by booleanPref("is_first_time_launch").withDefault<Boolean>(true)

    // One among AppConfig.BraveMode enum; 2's default, which is BraveMode.DNS_FIREWAL
    var braveMode by intPref("brave_mode").withDefault<Int>(AppConfig.BraveMode.DNS_FIREWALL.mode)

    // enable / disable logging dns and tcp/udp connections to db
    var logsEnabled by booleanPref("local_logs").withDefault<Boolean>(true)

    // the last-set app version; useful to detect an update where the current
    // app version is going to be greater than the one stored, and so, update flow
    // can be triggered accordingly, if any,
    var appVersion by intPref("app_version").withDefault<Int>(0)

    // Last known time when app update checks were done (successful?)
    var lastAppUpdateCheck by longPref("app_update_last_check").withDefault<Long>(0)

    // total blocklists set by the user for RethinkDNS+ (server-side dns blocking)
    private var numberOfRemoteBlocklists by intPref("remote_block_list_count").withDefault<Int>(0)

    // total blocklists set by the user (on-device dns blocking)
    var numberOfLocalBlocklists by intPref("local_block_list_count").withDefault<Int>(0)

    // whether all udp connection except dns must be dropped
    var udpBlockedSettings by booleanPref("block_udp_traffic_other_than_dns").withDefault<Boolean>(
        false)

    // user chosen blocklists stored custom dictionary indexed in base64
    var localBlocklistStamp by stringPref("local_block_list_stamp").withDefault<String>(
        if (Utilities.isHeadlessFlavour()) "1:0B__A___-v-gHTaQgADkAgAo"
            else "")

    // whether to drop packets when the source app originating the reqs couldn't be determined
    var blockUnknownConnections by booleanPref("block_unknown_connections").withDefault<Boolean>(
        false)

    // whether user has enable on-device blocklists
    var blocklistEnabled by booleanPref("enable_local_list").withDefault<Boolean>(
        Utilities.isHeadlessFlavour())

    // the version (which is a unix timestamp) of the current rethinkdns+ remote blocklist files
    var remoteBlocklistTimestamp by longPref("remote_block_list_downloaded_time").withDefault<Long>(
        INIT_TIME_MS)

    // the version (which is a unix timestamp) of the current on-device blocklist files
    var localBlocklistTimestamp by longPref("local_block_list_downloaded_time")
        .withDefault<Long>(if (Utilities.isHeadlessFlavour()) 1655223903366 else 0)

    // user set http proxy port
    var httpProxyPort by intPref("http_proxy_port").withDefault<Int>(INVALID_PORT)

    // user set http proxy ip / hostname
    var httpProxyHostAddress by stringPref("http_proxy_ipaddress").withDefault<String>("")

    // whether apps subject to the RethinkDNS VPN tunnel can bypass the tunnel on-demand
    // default: false for fdroid flavour
    var allowBypass by booleanPref("allow_bypass").withDefault<Boolean>(
        !Utilities.isFdroidFlavour())

    // user set among AppConfig.DnsType enum; RETHINK_REMOTE is default which is Rethink-DoH
    var dnsType by intPref("dns_type").withDefault<Int>(if (!Utilities.isHeadlessFlavour())
        AppConfig.DnsType.RETHINK_REMOTE.type else AppConfig.DnsType.NETWORK_DNS.type)

    // whether the app must attempt to startup on reboot if it was running before shutdown
    var prefAutoStartBootUp by booleanPref("auto_start_on_boot").withDefault<Boolean>(true)

    // user set preference whether firewall should block all connections when device is locked
    var blockWhenDeviceLocked by booleanPref("screen_state").withDefault<Boolean>(false)

    var oldNumberRequests by intPref("number_request").withDefault<Int>(0)
    var oldBlockedRequests by intPref("blocked_request").withDefault<Int>(0)

    // total dns requests the app has served since installation (or post clear data)
    var numberOfRequests by longPref("dns_number_request").withDefault<Long>(0)

    // total dns requests blocked since installation
    var numberOfBlockedRequests by longPref("dns_blocked_request").withDefault<Long>(0)

    // whether to block connections from apps not in the foreground
    var blockAppWhenBackground by booleanPref("background_mode").withDefault<Boolean>(false)

    // whether to check for app updates once-a-week (on website / play-store builds)
    var checkForAppUpdate by booleanPref("check_for_app_update").withDefault<Boolean>(true)

    // last connected dns label name
    var connectedDnsName by stringPref("connected_dns_name").withDefault<String>(
        context.getString(R.string.default_dns_name))

    // the current light/dark theme; 0's the default which is "Set by System"
    var theme by intPref("app_theme").withDefault<Int>(0)

    // user selected notification action type, ref: Constants#NOTIFICATION_ACTION_STOP
    var notificationActionType by intPref("notification_action").withDefault<Int>(0)

    // add all networks (say, both wifi / mobile) with internet capability to the vpn tunnel
    var useMultipleNetworks by booleanPref("add_all_networks_to_vpn").withDefault<Boolean>(false)

    // user selected proxy type (e.g., http, socks5)
    var proxyType by stringPref("proxy_proxytype").withDefault<String>(
        AppConfig.ProxyType.NONE.name)

    // user selected proxy provider, as of now two providers (custom, orbot)
    var proxyProvider by stringPref("proxy_proxyprovider").withDefault<String>(
        AppConfig.ProxyProvider.NONE.name)

    // total dnscrypt server currently connected to
    private var _dnsCryptRelayCount by intPref("dnscrypt_relay").withDefault<Int>(0)

    // the last collected app exit info's timestamp
    var lastAppExitInfoTimestamp by longPref("prev_trace_timestamp").withDefault<Long>(INIT_TIME_MS)

    // fetch fav icons for domains in dns request
    var fetchFavIcon by booleanPref("fav_icon_enabled").withDefault<Boolean>(
        !Utilities.isFdroidFlavour())

    // whether to show "what's new" chip on the homescreen, usually
    // shown after a update and until the user dismisses it
    var showWhatsNewChip by booleanPref("show_whats_new_chip").withDefault<Boolean>(true)

    // block dns which are not resolved by app
    var disallowDnsBypass by booleanPref("disallow_dns_bypass").withDefault<Boolean>(false)

    // trap all packets on port 53 to be sent to a dns endpoint or just the packets sent to vpn's dns-ip
    var preventDnsLeaks by booleanPref("prevent_dns_leaks").withDefault<Boolean>(true)

    // block all newly installed apps
    var blockNewlyInstalledApp by booleanPref("block_new_app").withDefault<Boolean>(false)

    // user setting to use custom download manager or android's default download manager
    var useCustomDownloadManager by booleanPref("use_custom_download_managet").withDefault<Boolean>(
        true)

    // custom download manager's last generated id
    var customDownloaderLastGeneratedId by longPref(
        "custom_downloader_last_generated_id").withDefault<Long>(0)

    // local timestamp for which the update is available
    var newestLocalBlocklistTimestamp by longPref("local_blocklist_update_ts").withDefault<Long>(
        INIT_TIME_MS)

    // remote timestamp for which the update is available
    var newestRemoteBlocklistTimestamp by longPref("remote_blocklist_update_ts").withDefault<Long>(
        INIT_TIME_MS)

    // auto-check for blocklist update periodically (once in a day)
    var periodicallyCheckBlocklistUpdate by booleanPref(
        "check_blocklist_update").withDefault<Boolean>(false)

    // user-preferred Internet Protocol type, default IPv4
    var internetProtocolType by intPref(INTERNET_PROTOCOL).withDefault<Int>(
        if (Utilities.isHeadlessFlavour()) InternetProtocol.IPv46.id else InternetProtocol.IPv4.id)

    // user-preferred 6to4 protocol translation, on IPv6 mode (default: PTMODEAUTO)
    var protocolTranslationType by booleanPref(PROTOCOL_TRANSLATION).withDefault<Boolean>(false)

    // filter IPv6 compatible IPv4 address in custom ips
    var filterIpv4inIpv6 by booleanPref("filter_ip4_ipv6").withDefault<Boolean>(true)

    // universal firewall settings to block all http connections
    var blockHttpConnections by booleanPref("block_http_connections").withDefault<Boolean>(false)

    // universal firewall settings to block all metered connections
    var blockMeteredConnections by booleanPref("block_metered_connections").withDefault<Boolean>(
        false)

    // universal firewall settings to lockdown all apps
    var universalLockdown by booleanPref("universal_lockdown").withDefault<Boolean>(false)

    var orbotConnectionStatus: MutableLiveData<Boolean> = MutableLiveData()
    var median: MutableLiveData<Long> = MutableLiveData()
    var dnsBlockedCountLiveData: MutableLiveData<Long> = MutableLiveData()
    var dnsRequestsCountLiveData: MutableLiveData<Long> = MutableLiveData()
    var vpnEnabledLiveData: MutableLiveData<Boolean> = MutableLiveData()

    var remoteBlocklistCount: MutableLiveData<Int> = MutableLiveData()

    fun setMedianLatency(median: Long) {
        this.median.postValue(median)
    }

    fun setDnsCryptRelayCount(count: Int) {
        _dnsCryptRelayCount = count
    }

    fun getDnsCryptRelayCount(): Int {
        return _dnsCryptRelayCount
    }

    fun setVpnEnabled(isOn: Boolean) {
        vpnEnabledLiveData.postValue(isOn)
        _vpnEnabled = isOn
    }

    fun getVpnEnabled(): Boolean {
        return _vpnEnabled
    }

    fun setRemoteBlocklistCount(c: Int) {
        numberOfRemoteBlocklists = c
        remoteBlocklistCount.postValue(c)
    }

    fun getRemoteBlocklistCount(): Int {
        return numberOfRemoteBlocklists
    }
}
