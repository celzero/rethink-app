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
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.MutableLiveData
import com.celzero.bravedns.BuildConfig
import com.celzero.bravedns.R
import com.celzero.bravedns.database.DoHEndpoint
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.appMode
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.connectedDNS
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.lifeTimeQueries
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG_VPN
import com.celzero.bravedns.util.Constants.Companion.ORBOT_MODE_NONE
import hu.autsoft.krate.*
import settings.Settings

class PersistentState(private val context: Context) : SimpleKrate(context) {
    companion object {
        const val BRAVE_MODE = "brave_mode"
        const val BACKGROUND_MODE = "background_mode"
        const val DNS_TYPE = "dns_type"
        const val PROXY_MODE = "proxy_mode"
        const val ALLOW_BYPASS = "allow_bypass"
        const val EXCLUDE_FROM_VPN = "exclude_apps_vpn"
        const val IS_SCREEN_OFF = "screen_off"
        const val CONNECTION_CHANGE = "change_in_url"
        const val LOCAL_BLOCK_LIST = "enable_local_list"
        const val LOCAL_BLOCK_LIST_STAMP = "local_block_list_stamp"
        const val BLOCK_UNKNOWN_CONNECTIONS = "block_unknown_connections"
        const val HTTP_PROXY_ENABLED = "http_proxy_enabled"
        const val BLOCK_UDP_OTHER_THAN_DNS = "block_udp_traffic_other_than_dns"
        const val ORBOT_MODE_CHANGE = "orbot_mode_enabled"
        const val NETWORK = "add_all_networks_to_vpn"
        const val NOTIFICATION_ACTION = "notification_action"

        fun expandUrl(context: Context, url: String?): String {
            return if (url == null || url.isEmpty()) {
                context.resources.getStringArray(R.array.doh_endpoint_names)[3]
            } else url
        }
    }

    var vpnEnabled by booleanPref("enabled", false)
    var firstTimeLaunch by booleanPref("is_first_time_launch", true)
    var excludedPackagesWifi by stringSetPref("pref_apps_wifi", emptySet())
    var excludedPackagesData by stringSetPref("pref_apps_data", emptySet())
    private var _braveMode by intPref("brave_mode", 2)
    var firewallMode by intPref("firewall_mode", 1)
    var logsEnabled by booleanPref("local_logs", true)
    var downloadSource by intPref("app_downloaded_source", 0)
    var appVersion by intPref("app_version", 0)
    var lastAppUpdateCheck by longPref("app_update_last_check", 0)
    var numberOfRemoteBlocklists by intPref("remote_block_list_count", 0)
    var numberOfLocalBlocklists by intPref("local_block_list_count", 0)
    var udpBlockedSettings by booleanPref("block_udp_traffic_other_than_dns", false)
    var insertionCompleted by booleanPref("initial_insert_servers_complete", false)
    var remoteBraveDNSDownloaded by booleanPref("download_remote_block_list", false)
    var localBlockListStamp by stringPref("local_block_list_stamp", "")
    var blockUnknownConnections by booleanPref("block_unknown_connections", false)
    var blockListFilesDownloaded by booleanPref("download_block_list_files", false)
    var localBlocklistEnabled by booleanPref("enable_local_list", false)
    var remoteBlockListDownloadTime by longPref("remote_block_list_downloaded_time", 0)
    var tempRemoteBlockListDownloadTime by longPref("temp_remote_block_list_downloaded_time", 0)
    var workManagerStartTime by longPref("work_manager_start_time", 0)
    var localBlockListDownloadTime by longPref("local_block_list_downloaded_time", 0)
    var tempBlocklistDownloadTime by longPref("temp_time_during_download", 0)
    var httpProxyPort by intPref("http_proxy_port", 0)
    var httpProxyEnabled by booleanPref("http_proxy_enabled", false)
    var httpProxyHostAddress by stringPref("http_proxy_ipaddress", "")
    var excludedAppsFromVPN by stringSetPref("exclude_apps_vpn", emptySet())
    var connectionModeChange by stringPref("change_in_url", "")
    var socks5Enabled by booleanPref("socks5_proxy", false)
    var killAppOnFirewall by booleanPref("kill_app_on_firewall", true)
    var allowByPass by booleanPref("allow_bypass", true)
    var allowDNSTraffic by booleanPref("dns_all_traffic", true)
    var proxyMode by longPref("proxy_mode", Settings.ProxyModeNone)
    var dnsType by intPref("dns_type", 1)
    var prefAutoStartBootUp by booleanPref("auto_start_on_boot", true)
    var screenState by booleanPref("screen_state", false)
    private var _numberOfRequests by intPref("number_request", 0)
    var numberOfBlockedRequests by intPref("blocked_request", 0)
    var isBackgroundEnabled by booleanPref("background_mode", false)
    var checkForAppUpdate by booleanPref("check_for_app_update", true)
    var isScreenOff by booleanPref("screen_off", false)
    private var connectedDNSName by stringPref("connected_dns_name", "RethinkDNS Basic")
    var theme by intPref("app_theme", 0)
    var notificationAction by intPref("notification_action", 1)
    var isAddAllNetworks by booleanPref("add_all_networks_to_vpn", false)

    var orbotConnectionStatus: MutableLiveData<Boolean> = MutableLiveData()
    var orbotEnabled by booleanPref("orbot_enabled", false)
    var orbotMode by intPref("orbot_mode", ORBOT_MODE_NONE)
    var downloadIDs by stringSetPref("download_ids", emptySet())
    var orbotEnabledMode by intPref("orbot_mode_enabled", ORBOT_MODE_NONE)

    var fetchFavIcon by booleanPref("fav_icon_enabled", BuildConfig.FLAVOR != Constants.FLAVOR_FDROID)

    var isAccessibilityCrashDetected by booleanPref("accessibility_crash", false)

    var median: MutableLiveData<Long> = MutableLiveData()
    var blockedCount: MutableLiveData<Int> = MutableLiveData()
    var networkRequestHeartbeatTimestamp: Long = 0L

    fun wifiAllowed(forPackage: String): Boolean = !excludedPackagesWifi.contains(forPackage)

    // FIXME #200 - Removed the list from the persistent state.
    fun modifyAllowedWifi(forPackage: String, remove: Boolean) {
        excludedPackagesWifi = if (remove) {
            excludedPackagesWifi - forPackage
        } else {
            excludedPackagesWifi + forPackage
        }
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

    fun setLifetimeQueries() {
        val numReq = if (lifeTimeQueries > 0) lifeTimeQueries + 1
        else {
            _numberOfRequests + 1
        }
        lifeTimeQueries = numReq
        val now = SystemClock.elapsedRealtime()
        if (Math.abs(now - networkRequestHeartbeatTimestamp) > Constants.NETWORK_REQUEST_WRITE_THRESHOLD_MS) {
            networkRequestHeartbeatTimestamp = now
            _numberOfRequests = numReq
            HomeScreenActivity.GlobalVariable.lifeTimeQ.postValue(numReq)
        }
    }

    fun getLifetimeQueries(): Int {
        if (lifeTimeQueries >= 0) {
            return lifeTimeQueries
        }
        return _numberOfRequests
    }

    fun incrementBlockedReq() {
        numberOfBlockedRequests += 1
        blockedCount.postValue(numberOfBlockedRequests)
    }

    //fixme replace the below logic once the DNS data is streamlined.
    fun getConnectedDNS(): String {
        if (!connectedDNS.value.isNullOrEmpty()) {
            return connectedDNS.value!!
        }
        val dnsType = appMode?.getDNSType()
        return if (Constants.PREF_DNS_MODE_DOH == dnsType) {
            val dohDetail: DoHEndpoint?
            try {
                dohDetail = appMode?.getDOHDetails()
                dohDetail?.dohName!!
            } catch (e: Exception) {
                Log.e(LOG_TAG_VPN, "Exception while fetching DOH from the database", e)
                connectedDNSName
            }
        } else if (Constants.PREF_DNS_MODE_DNSCRYPT == dnsType) {
            if (appMode?.getDNSCryptServerCount() != null) {
                val cryptDetails = appMode?.getDNSCryptServerCount()
                context.getString(R.string.configure_dns_crypt, cryptDetails.toString())
            } else {
                context.getString(R.string.configure_dns_crypt, "0")
            }
        } else {
            val proxyDetails = appMode?.getDNSProxyServerDetails()
            proxyDetails?.proxyAppName!!
        }
    }

    fun setConnectedDNS(name: String) {
        connectedDNS.postValue(name)
        connectedDNSName = name
    }
}
