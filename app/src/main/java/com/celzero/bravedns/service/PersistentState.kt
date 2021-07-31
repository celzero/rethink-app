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
import com.celzero.bravedns.BuildConfig
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppMode
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS
import com.celzero.bravedns.util.Constants.Companion.INVALID_PORT
import hu.autsoft.krate.*
import org.koin.core.component.KoinComponent

class PersistentState(private val context: Context) : SimpleKrate(context), KoinComponent {
    companion object {
        const val BRAVE_MODE = "brave_mode"
        const val BACKGROUND_MODE = "background_mode"
        const val ALLOW_BYPASS = "allow_bypass"
        const val EXCLUDE_FROM_VPN = "exclude_apps_vpn"
        const val LOCAL_BLOCK_LIST = "enable_local_list"
        const val LOCAL_BLOCK_LIST_STAMP = "local_block_list_stamp"
        const val PROXY_TYPE = "proxy_proxytype"
        const val NETWORK = "add_all_networks_to_vpn"
        const val NOTIFICATION_ACTION = "notification_action"
        const val DNS_CHANGE = "connected_dns_name"
        const val DNS_RELAYS = "dnscrypt_relay"

        fun expandUrl(context: Context, url: String?): String {
            return if (url == null || url.isEmpty()) {
                context.resources.getStringArray(R.array.doh_endpoint_names)[3]
            } else url
        }
    }

    private var _vpnEnabled by booleanPref("enabled", false)
    var firstTimeLaunch by booleanPref("is_first_time_launch", true)
    var braveMode by intPref("brave_mode", 2)
    var firewallMode by intPref("firewall_mode", 1)
    var logsEnabled by booleanPref("local_logs", true)
    var appVersion by intPref("app_version", 0)
    var lastAppUpdateCheck by longPref("app_update_last_check", 0)
    var numberOfRemoteBlocklists by intPref("remote_block_list_count", 0)
    var numberOfLocalBlocklists by intPref("local_block_list_count", 0)
    var udpBlockedSettings by booleanPref("block_udp_traffic_other_than_dns", false)
    var insertionCompleted by booleanPref("initial_insert_servers_complete", false)
    var localBlocklistStamp by stringPref("local_block_list_stamp", "")
    var blockUnknownConnections by booleanPref("block_unknown_connections", false)
    var blocklistFilesDownloaded by booleanPref("download_block_list_files", false)
    var blocklistEnabled by booleanPref("enable_local_list", false)
    var remoteBlocklistDownloadTime by longPref("remote_block_list_downloaded_time", 0)
    var blocklistDownloadTime by longPref("local_block_list_downloaded_time", 0)
    var tempBlocklistDownloadTime by longPref("temp_time_during_download", 0)
    var httpProxyPort by intPref("http_proxy_port", INVALID_PORT)
    var httpProxyHostAddress by stringPref("http_proxy_ipaddress", "")

    var killAppOnFirewall by booleanPref("kill_app_on_firewall", true)
    var allowByPass by booleanPref("allow_bypass", true)
    var allowDNSTraffic by booleanPref("dns_all_traffic", true)
    var dnsType by intPref("dns_type", 1)
    var prefAutoStartBootUp by booleanPref("auto_start_on_boot", true)
    var screenState by booleanPref("screen_state", false)

    var oldNumberRequests by intPref("number_request", 0)
    var oldBlockedRequests by intPref("blocked_request", 0)
    var numberOfRequests by longPref("dns_number_request", 0)
    var numberOfBlockedRequests by longPref("dns_blocked_request", 0)

    var backgroundEnabled by booleanPref("background_mode", false)
    var checkForAppUpdate by booleanPref("check_for_app_update", true)
    private var connectedDnsName by stringPref("connected_dns_name", context.getString(R.string.dns_mode_3))
    var theme by intPref("app_theme", 0)
    var notificationAction by intPref("notification_action", 1)
    var isAddAllNetworks by booleanPref("add_all_networks_to_vpn", false)

    var lastAppRefreshTime by longPref("last_app_refresh_time", INIT_TIME_MS)

    var proxyType by stringPref("proxy_proxytype", AppMode.ProxyType.NONE.name)
    var proxyProvider by stringPref("proxy_proxyprovider", AppMode.ProxyProvider.NONE.name)

    private var _dnsCryptRelayCount by intPref("dnscrypt_relay", 0)

    var orbotConnectionStatus: MutableLiveData<Boolean> = MutableLiveData()

    var downloadIds by stringSetPref("download_ids", emptySet())

    var fetchFavIcon by booleanPref("fav_icon_enabled",
                                    BuildConfig.FLAVOR != Constants.FLAVOR_FDROID)

    var median: MutableLiveData<Long> = MutableLiveData()
    var dnsBlockedCountLiveData: MutableLiveData<Long> = MutableLiveData()
    var dnsRequestsCountLiveData: MutableLiveData<Long> = MutableLiveData()

    var vpnEnabledLiveData: MutableLiveData<Boolean> = MutableLiveData()

    fun setMedianLatency(median: Long) {
        this.median.postValue(median)
    }

    fun setConnectedDns(name: String) {
        connectedDnsName = name
    }

    fun getConnectedDns(): String {
        return connectedDnsName
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
}
