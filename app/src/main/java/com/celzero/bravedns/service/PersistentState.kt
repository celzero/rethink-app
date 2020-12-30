package com.celzero.bravedns.service

import android.content.Context
import android.util.Log
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.braveMode
import com.celzero.bravedns.util.Constants
import hu.autsoft.krate.*
import settings.Settings

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
class PersistentState(context: Context):SimpleKrate(context) {
    companion object {
        const val BRAVE_MODE = "brave_mode"
        const val BACKGROUND_MODE = "background_mode"
        const val DNS_TYPE = "dns_type"
        const val PROXY_MODE = "proxy_mode"
        const val ALLOW_BYPASS = "allow_bypass"
        const val PRIVATE_DNS = "private_dns"
        const val EXCLUDE_FROM_VPN = "exclude_apps_vpn"
        const val IS_SCREEN_OFF = "screen_off"
        const val CONNECTION_CHANGE = "change_in_url"
        const val LOCAL_BLOCK_LIST = "enable_local_list"
        const val LOCAL_BLOCK_LIST_STAMP = "local_block_list_stamp"
        const val BLOCK_UNKNOWN_CONNECTIONS = "block_unknown_connections"
        const val HTTP_PROXY_ENABLED = "http_proxy_enabled"
        const val DNS_PROXY_ID = "dns_proxy_change"
        const val BLOCK_UDP_OTHER_THAN_DNS = "block_udp_traffic_other_than_dns"

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
    var dnsProxyIDChange by intPref("dns_proxy_change", 0)
    var insertionCompleted by booleanPref("initial_insert_servers_complete", false)
    var remoteBraveDNSDownloaded by booleanPref("download_remote_block_list", false)
    private var _localBlockListStamp by stringPref("local_block_list_stamp", "")
    var blockUnknownConnections by booleanPref("block_unknown_connections", false)
    var blockListFilesDownloaded by booleanPref("download_block_list_files", false)
    var localBlocklistEnabled by booleanPref("enable_local_list", false)
    var remoteBlockListDownloadTime by longPref("remote_block_list_downloaded_time", 0)
    var localBlockListDownloadTime by longPref("local_block_list_downloaded_time", 0)
    var httpProxyPort by intPref("http_proxy_port", 0)
    var httpProxyEnabled by booleanPref("http_proxy_enabled", false)
    var httpProxyHostAddress by stringPref("http_proxy_ipaddress", "")
    var excludedAppsFromVPN by stringSetPref("exclude_apps_vpn", emptySet())
    var connectionModeChange by stringPref("change_in_url", "")
    var socks5Enabled by booleanPref("socks5_proxy", false)
    var killAppOnFirewall by booleanPref("kill_app_on_firewall", true)
    var allowPrivateDNS by booleanPref("private_dns", false)
    var allowByPass by booleanPref("allow_bypass", true)
    var allowDNSTraffic by booleanPref("dns_all_traffic", true)
    var proxyMode by longPref("proxy_mode", Settings.ProxyModeNone)
    var dnsType by intPref("dns_type", 1)
    var prefAutoStartBootUp by booleanPref("auto_start_on_boot", true)
    private var _screenState by booleanPref("screen_state", false)
    private var _median90 by longPref("median_p90", 0)
    private var _numberOfRequests by intPref("number_request", 0)
    var numberOfBlockedRequests by intPref("blocked_request", 0)
    var backgroundEnabled by booleanPref("background_mode", false)
        private set
    private var isScreenOff by booleanPref("screen_off", false)

    fun wifiAllowed(forPackage:String):Boolean = !excludedPackagesWifi.contains(forPackage)

    fun modifyAllowedWifi(forPackage:String, remove:Boolean) {
        if(remove) {
            excludedPackagesWifi -= forPackage
        } else {
            excludedPackagesWifi += forPackage
        }
    }

    fun modifyAllowedData(forPackage:String, remove:Boolean) {
        if(remove) {
            excludedPackagesData -= forPackage
        } else {
            excludedPackagesData += forPackage
        }
    }

    fun getBraveMode() = if(braveMode == -1) _braveMode else braveMode // TODO remove app logic from settings

    fun setBraveMode(mode:Int) {
        _braveMode = mode
    }

    fun setLocalBlockListStamp(stamp: String) {
        if (HomeScreenActivity.GlobalVariable.DEBUG) Log.d(Constants.LOG_TAG, "In preference, Set local stamp: $stamp")
        _localBlockListStamp = stamp
    }

    fun getLocalBlockListStamp(): String {
        if (HomeScreenActivity.GlobalVariable.DEBUG)
            Log.d(Constants.LOG_TAG, "In preference, Get local stamp: $_localBlockListStamp")
        return _localBlockListStamp
    }

    fun setFirewallModeForScreenState(state : Boolean) {
        // TODO Remove UI logic from settings
        _screenState = state
        if (state) {
            HomeScreenActivity.GlobalVariable.isScreenLockedSetting = 1
            if (backgroundEnabled) {
                HomeScreenActivity.GlobalVariable.numUniversalBlock.postValue(2)
            } else {
                HomeScreenActivity.GlobalVariable.numUniversalBlock.postValue(1)
            }
        } else {
            HomeScreenActivity.GlobalVariable.isScreenLockedSetting = 0
            if (backgroundEnabled) {
                HomeScreenActivity.GlobalVariable.numUniversalBlock.postValue(1)
            } else {
                HomeScreenActivity.GlobalVariable.numUniversalBlock.postValue(0)
            }
        }
    }

    fun getFirewallModeForScreenState() : Boolean{
        // TODO Remove UI logic from settings
        return when (HomeScreenActivity.GlobalVariable.isScreenLockedSetting) {
            0 -> false
            1 -> true
            else -> _screenState
        }
    }

    fun setMedianLatency(medianP90 : Long){
        // TODO Remove UI logic from settings
        HomeScreenActivity.GlobalVariable.medianP90 = medianP90
        HomeScreenActivity.GlobalVariable.median50.postValue(medianP90)
        _median90 = medianP90
    }

    fun getMedianLatency() : Long{
        return HomeScreenActivity.GlobalVariable.medianP90.takeIf {
            it != -1L
        } ?: _median90
    }

    fun setNumOfReq(){
        var numReq = 0
        numReq = if(HomeScreenActivity.GlobalVariable.lifeTimeQueries > 0) HomeScreenActivity.GlobalVariable.lifeTimeQueries + 1
        else {
            _numberOfRequests + 1
        }
        _numberOfRequests = numReq
        HomeScreenActivity.GlobalVariable.lifeTimeQueries = numReq
        HomeScreenActivity.GlobalVariable.lifeTimeQ.postValue(numReq)
    }

    fun getNumOfReq(): Int {
        if (HomeScreenActivity.GlobalVariable.lifeTimeQueries >= 0)
            return HomeScreenActivity.GlobalVariable.lifeTimeQueries
        return _numberOfRequests
    }

    fun incrementBlockedReq() {
        numberOfBlockedRequests += 1
        HomeScreenActivity.GlobalVariable.blockedCount.postValue(numberOfBlockedRequests)
    }

    fun setIsBackgroundEnabled(isEnabled: Boolean) {
        backgroundEnabled = isEnabled
        var uValue = HomeScreenActivity.GlobalVariable.numUniversalBlock.value
        if (isEnabled) {
            if (getScreenLockData()) {
                HomeScreenActivity.GlobalVariable.numUniversalBlock.postValue(2)
            } else {
                HomeScreenActivity.GlobalVariable.numUniversalBlock.postValue(1)
            }
        } else {
            if (getScreenLockData()) {
                HomeScreenActivity.GlobalVariable.numUniversalBlock.postValue(1)
            } else {
                HomeScreenActivity.GlobalVariable.numUniversalBlock.postValue(0)
            }
        }
    }

    fun setScreenLockData(isEnabled : Boolean) {
        HomeScreenActivity.GlobalVariable.isScreenLocked = if(isEnabled) 1
        else 0
        isScreenOff = isEnabled
    }

    fun getScreenLockData(): Boolean {
        return when (HomeScreenActivity.GlobalVariable.isScreenLocked) {
            0 -> false
            1 -> true
            else -> isScreenOff
        }
    }
}