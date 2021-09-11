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
import com.celzero.bravedns.data.AppMode
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS
import com.celzero.bravedns.util.Constants.Companion.INVALID_PORT
import com.celzero.bravedns.util.Utilities
import hu.autsoft.krate.*
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
    private var _vpnEnabled by booleanPref("enabled", false)

    // OOBE (out-of-the-box experience) screens are shown if the user
    // launches the app for the very first time (after install or post clear-data)
    var firstTimeLaunch by booleanPref("is_first_time_launch", true)

    // One among AppMode.BraveMode enum; 2's default, which is BraveMode.DNS_FIREWAL
    var braveMode by intPref("brave_mode", AppMode.BraveMode.DNS_FIREWALL.mode)

    // enable / disable logging dns and tcp/udp connections to db
    var logsEnabled by booleanPref("local_logs", true)

    // the last-set app version; useful to detect an update where the current
    // app version is going to be greater than the one stored, and so, update flow
    // can be triggered accordingly, if any,
    var appVersion by intPref("app_version", 0)

    // Last known time when app update checks were done (successful?)
    var lastAppUpdateCheck by longPref("app_update_last_check", 0)

    // total blocklists set by the user for RethinkDNS+ (server-side dns blocking)
    private var numberOfRemoteBlocklists by intPref("remote_block_list_count", 0)

    // total blocklists set by the user (on-device dns blocking)
    var numberOfLocalBlocklists by intPref("local_block_list_count", 0)

    // whether all udp connection except dns must be dropped
    var udpBlockedSettings by booleanPref("block_udp_traffic_other_than_dns", false)

    // whether the initial database inserts on first-run completed successfully, see: HomeScreenActivity#insertDefaultData
    var isDefaultDataInsertComplete by booleanPref("initial_insert_servers_complete", false)

    // user chosen blocklists stored custom dictionary indexed in base64
    var localBlocklistStamp by stringPref("local_block_list_stamp", "")

    // whether to drop packets when the source app originating the reqs couldn't be determined
    var blockUnknownConnections by booleanPref("block_unknown_connections", false)

    // whether user has enable on-device blocklists
    var blocklistEnabled by booleanPref("enable_local_list", false)

    // the version (which is a unix timestamp) of the current rethinkdns+ remote blocklist files
    var remoteBlocklistTimestamp by longPref("remote_block_list_downloaded_time", 0)

    // the version (which is a unix timestamp) of the current on-device blocklist files
    var localBlocklistTimestamp by longPref("local_block_list_downloaded_time", 0)

    // user set http proxy port
    var httpProxyPort by intPref("http_proxy_port", INVALID_PORT)

    // user set http proxy ip / hostname
    var httpProxyHostAddress by stringPref("http_proxy_ipaddress", "")

    // whether RethinkDNS should signal activity-manager to kill a firewalled app
    var killAppOnFirewall by booleanPref("kill_app_on_firewall", true)

    // whether apps subject to the RethinkDNS VPN tunnel can bypass the tunnel on-demand
    var allowBypass by booleanPref("allow_bypass", true)

    // user set among AppMode.DnsType enum; 1's the default which is DoH
    var dnsType by intPref("dns_type", 1)

    // whether the app must attempt to startup on reboot if it was running before shutdown
    var prefAutoStartBootUp by booleanPref("auto_start_on_boot", true)

    // user set preference whether firewall should block all connections when device is locked
    var blockWhenDeviceLocked by booleanPref("screen_state", false)

    var oldNumberRequests by intPref("number_request", 0)
    var oldBlockedRequests by intPref("blocked_request", 0)

    // total dns requests the app has served since installation (or post clear data)
    var numberOfRequests by longPref("dns_number_request", 0)

    // total dns requests blocked since installation
    var numberOfBlockedRequests by longPref("dns_blocked_request", 0)

    // whether to block connections from apps not in the foreground
    var blockAppWhenBackground by booleanPref("background_mode", false)

    // whether to check for app updates once-a-week (on website / play-store builds)
    var checkForAppUpdate by booleanPref("check_for_app_update", true)

    // last connected dns label name
    var connectedDnsName by stringPref("connected_dns_name", context.getString(R.string.dns_mode_3))

    // the current light/dark theme; 0's the default which is "Set by System"
    var theme by intPref("app_theme", 0)

    // user selected notification action type, ref: Constants#NOTIFICATION_ACTION_STOP
    var notificationActionType by intPref("notification_action", 1)

    // add all networks (say, both wifi / mobile) with internet capability to the vpn tunnel
    var useMultipleNetworks by booleanPref("add_all_networks_to_vpn", false)

    // user selected proxy type (e.g., http, socks5)
    var proxyType by stringPref("proxy_proxytype", AppMode.ProxyType.NONE.name)

    // user selected proxy provider, as of now two providers (custom, orbot)
    var proxyProvider by stringPref("proxy_proxyprovider", AppMode.ProxyProvider.NONE.name)

    // total dnscrypt server currently connected to
    private var _dnsCryptRelayCount by intPref("dnscrypt_relay", 0)

    // the last collected app exit info's timestamp
    var lastAppExitInfoTimestamp by longPref("prev_trace_timestamp", INIT_TIME_MS)

    // fetch fav icons for domains in dns request
    var fetchFavIcon by booleanPref("fav_icon_enabled", Utilities.isFdroidFlavour())

    // whether to show "what's new" chip on the homescreen, usually
    // shown after a update and until the user dismisses it
    var showWhatsNewChip by booleanPref("show_whats_new_chip", true)

    // block dns which are not resolved by app
    var disallowDnsBypass by booleanPref("disallow_dns_bypass", false)

    // trap all packets on port 53 to be sent to a dns endpoint or just the packets sent to vpn's dns-ip
    var preventDnsLeaks by booleanPref("prevent_dns_leaks", true)

    // block all newly installed apps
    var blockNewlyInstalledApp by booleanPref("block_new_app", false)

    var orbotConnectionStatus: MutableLiveData<Boolean> = MutableLiveData()
    var median: MutableLiveData<Long> = MutableLiveData()
    var dnsBlockedCountLiveData: MutableLiveData<Long> = MutableLiveData()
    var dnsRequestsCountLiveData: MutableLiveData<Long> = MutableLiveData()
    var vpnEnabledLiveData: MutableLiveData<Boolean> = MutableLiveData()

    // requires livedata as the app state can be changed from more than one place
    //var appStateObserver: MutableLiveData<AppMode.AppState> = MutableLiveData()
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
