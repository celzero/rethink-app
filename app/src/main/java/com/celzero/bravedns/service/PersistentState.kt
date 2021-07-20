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
import android.util.Log
import androidx.lifecycle.MutableLiveData
import com.celzero.bravedns.BuildConfig
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppMode
import com.celzero.bravedns.database.DoHEndpoint
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.connectedDNS
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS
import com.celzero.bravedns.util.Constants.Companion.INVALID_PORT
import com.celzero.bravedns.util.Constants.Companion.ORBOT_MODE_NONE
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_VPN
import hu.autsoft.krate.*
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class PersistentState(private val context: Context) : SimpleKrate(context), KoinComponent {
    companion object {
        const val BRAVE_MODE = "brave_mode"
        const val BACKGROUND_MODE = "background_mode"
        const val DNS_TYPE = "dns_type"
        const val ALLOW_BYPASS = "allow_bypass"
        const val EXCLUDE_FROM_VPN = "exclude_apps_vpn"
        const val LOCAL_BLOCK_LIST = "enable_local_list"
        const val LOCAL_BLOCK_LIST_STAMP = "local_block_list_stamp"
        const val PROXY_TYPE = "proxy_proxytype"
        const val PROXY_PROVIDER = "proxy_proxyprovider"
        const val NETWORK = "add_all_networks_to_vpn"
        const val NOTIFICATION_ACTION = "notification_action"

        fun expandUrl(context: Context, url: String?): String {
            return if (url == null || url.isEmpty()) {
                context.resources.getStringArray(R.array.doh_endpoint_names)[3]
            } else url
        }
    }

    private val appMode by inject<AppMode>()

    private var _vpnEnabled by booleanPref("enabled", false)
    var firstTimeLaunch by booleanPref("is_first_time_launch", true)
    var excludedPackagesWifi by stringSetPref("pref_apps_wifi", emptySet())
    var excludedPackagesData by stringSetPref("pref_apps_data", emptySet())
    private var _braveMode by intPref("brave_mode", 2)
    var firewallMode by intPref("firewall_mode", 1)
    var logsEnabled by booleanPref("local_logs", true)
    var appVersion by intPref("app_version", 0)
    var lastAppUpdateCheck by longPref("app_update_last_check", 0)
    var numberOfRemoteBlocklists by intPref("remote_block_list_count", 0)
    var numberOfLocalBlocklists by intPref("local_block_list_count", 0)
    var udpBlockedSettings by booleanPref("block_udp_traffic_other_than_dns", false)
    var insertionCompleted by booleanPref("initial_insert_servers_complete", false)
    var remoteBraveDNSDownloaded by booleanPref("download_remote_block_list", false)
    var localBlocklistStamp by stringPref("local_block_list_stamp", "")
    var blockUnknownConnections by booleanPref("block_unknown_connections", false)
    var blocklistFilesDownloaded by booleanPref("download_block_list_files", false)
    var blocklistEnabled by booleanPref("enable_local_list", false)
    var remoteBlocklistDownloadTime by longPref("remote_block_list_downloaded_time", 0)
    var blocklistDownloadTime by longPref("local_block_list_downloaded_time", 0)
    var tempBlocklistDownloadTime by longPref("temp_time_during_download", 0)
    var httpProxyPort by intPref("http_proxy_port", INVALID_PORT)
    var httpProxyHostAddress by stringPref("http_proxy_ipaddress", "")

    // FIXME - excludedAppsFromVPN - Try to remove this persistentState and try to get it from DB
    var excludedAppsFromVPN by stringSetPref("exclude_apps_vpn", emptySet())
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
    private var connectedDNSName by stringPref("connected_dns_name",
                                               context.getString(R.string.dns_mode_3))
    var theme by intPref("app_theme", 0)
    var notificationAction by intPref("notification_action", 1)
    var isAddAllNetworks by booleanPref("add_all_networks_to_vpn", false)

    var lastAppRefreshTime by longPref("last_app_refresh_time", INIT_TIME_MS)

    var proxyType by stringPref("proxy_proxytype", AppMode.ProxyType.NONE.name)
    var proxyProvider by stringPref("proxy_proxyprovider", AppMode.ProxyProvider.NONE.name)

    var orbotConnectionStatus: MutableLiveData<Boolean> = MutableLiveData()

    // When set, indicates user action to start Orbot and connect to it. The default value
    // "1"(NONE) indicates no action / action to connect was reset.
    var orbotRequestMode by intPref("orbot_mode", ORBOT_MODE_NONE)

    var downloadIDs by stringSetPref("download_ids", emptySet())

    var fetchFavIcon by booleanPref("fav_icon_enabled",
                                    BuildConfig.FLAVOR != Constants.FLAVOR_FDROID)

    var isAccessibilityCrashDetected by booleanPref("accessibility_crash", false)

    var median: MutableLiveData<Long> = MutableLiveData()
    var blockedCountLiveData: MutableLiveData<Long> = MutableLiveData()
    var requestCountLiveData: MutableLiveData<Long> = MutableLiveData()

    var vpnEnabledLiveData: MutableLiveData<Boolean> = MutableLiveData()

    fun wifiAllowed(forPackage: String): Boolean = !excludedPackagesWifi.contains(forPackage)

    // FIXME #200 - Removed the list from the persistent state.
    // excludedPackagesWifi - is used by the BraveVPNService#newBuilder() when the
    // firewall mode is set to Settings.BlockModeSink.
    // Remove this and make use of appInfo database value.
    fun modifyAllowedWifi(forPackage: String, remove: Boolean) {
        excludedPackagesWifi = if (remove) {
            excludedPackagesWifi - forPackage
        } else {
            excludedPackagesWifi + forPackage
        }
    }

    fun updateExcludedListWifi(excludedApps: List<String>) {
        excludedAppsFromVPN = excludedApps.toMutableSet()
    }

    // FIXME #200 - Remove the usage of lists from persistent state.
    fun modifyAllowedData(forPackage: String, remove: Boolean) {
        excludedPackagesData = if (remove) {
            excludedPackagesData - forPackage
        } else {
            excludedPackagesData + forPackage
        }
    }

    fun getBraveMode() = _braveMode // TODO #200 - remove app logic from settings

    fun setBraveMode(mode: Int) {
        _braveMode = mode
    }

    fun setMedianLatency(median: Long) {
        this.median.postValue(median)
    }

    /* fun setLifetimeQueries() {
         _numberOfRequests += 1
         lifeTimeQ.postValue(_numberOfRequests)
     }

     fun getLifetimeQueries(): Long {
         return _numberOfRequests
     }

     fun incrementBlockedReq() {
         numberOfBlockedRequests += 1
         blockedCount.postValue(numberOfBlockedRequests)
     }*/

    //FIXME replace the below logic once the DNS data is streamlined.
    fun getConnectedDNS(): String {
        if (!connectedDNS.value.isNullOrEmpty()) {
            return connectedDNS.value!!
        }

        val dnsType = appMode.getDNSType()
        return if (Constants.PREF_DNS_MODE_DOH == dnsType) {
            val dohDetail: DoHEndpoint?
            try {
                dohDetail = appMode.getDOHDetails()
                dohDetail?.dohName!!
            } catch (e: Exception) { //FIXME - #320
                Log.e(LOG_TAG_VPN, "Issue while DOH details fetch from the database", e)
                connectedDNSName
            }
        } else if (Constants.PREF_DNS_MODE_DNSCRYPT == dnsType) {
            context.getString(R.string.configure_dns_crypt,
                              appMode.getDNSCryptServerCount().toString())
        } else {
            val proxyDetails = appMode.getDNSProxyServerDetails()
            proxyDetails.proxyAppName!!
        }
    }

    fun setConnectedDNS(name: String) {
        connectedDNS.postValue(name)
        connectedDNSName = name
    }

    fun setVpnEnabled(isOn: Boolean) {
        vpnEnabledLiveData.postValue(isOn)
        _vpnEnabled = isOn
    }

    fun getVpnEnabled(): Boolean {
        return _vpnEnabled
    }
}
