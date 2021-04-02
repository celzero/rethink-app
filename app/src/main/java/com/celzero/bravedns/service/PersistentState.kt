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
import com.celzero.bravedns.R
import com.celzero.bravedns.database.DoHEndpoint
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.appMode
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.braveMode
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.connectedDNS
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.lifeTimeQueries
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.ORBAT_MODE_NONE
import hu.autsoft.krate.*
import settings.Settings

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
        const val ORBOT_MODE_CHANGE = "orbot_mode_enabled"

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
    private var _localBlockListStamp by stringPref("local_block_list_stamp", "")
    var blockUnknownConnections by booleanPref("block_unknown_connections", false)
    var blockListFilesDownloaded by booleanPref("download_block_list_files", false)
    var localBlocklistEnabled by booleanPref("enable_local_list", false)
    var remoteBlockListDownloadTime by longPref("remote_block_list_downloaded_time", 0)
    var tempRemoteBlockListDownloadTime by longPref("temp_remote_block_list_downloaded_time", 0)
    var workManagerStartTime by longPref("work_manager_start_time", 0)
    var localBlockListDownloadTime by longPref("local_block_list_downloaded_time", 0)
    var tempBlocklistDownloadTime by longPref("temp_time_during_download",0)
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
    var _screenState by booleanPref("screen_state", false)
    private var _median90 by longPref("median_p90", 0)
    private var _numberOfRequests by intPref("number_request", 0)
    var numberOfBlockedRequests by intPref("blocked_request", 0)
    var backgroundEnabled by booleanPref("background_mode", false)
    var checkForAppUpdate by booleanPref("check_for_app_update", true)
        //private set
    var isScreenOff by booleanPref("screen_off", false)
    private var connectedDNSName by stringPref("connected_dns_name","RethinkDNS Basic")
    var theme by intPref("app_theme", 0)

    var orbotConnectionStatus : MutableLiveData<Boolean> = MutableLiveData()
    //var orbotConnectionInitiated by booleanPref("orbot_connection_initiated", false)
    var orbotEnabled by booleanPref("orbot_enabled", false)
    private var orbotMode by intPref("orbot_mode", ORBAT_MODE_NONE)
    var downloadIDs by stringSetPref("download_ids", emptySet())
    var orbotEnabledMode by intPref("orbot_mode_enabled", ORBAT_MODE_NONE)

    var isAccessibilityCrashDetected by booleanPref("accessibility_crash", false)

    var orbotModeConst : Int = 0
    var median50: MutableLiveData<Long> = MutableLiveData()
    var blockedCount: MutableLiveData<Int> = MutableLiveData()

    fun wifiAllowed(forPackage:String):Boolean = !excludedPackagesWifi.contains(forPackage)

    fun modifyAllowedWifi(forPackage:String, remove:Boolean) {
        if(remove) {
            excludedPackagesWifi = excludedPackagesWifi - forPackage
        } else {
            excludedPackagesWifi = excludedPackagesWifi + forPackage
        }
    }

    fun modifyAllowedData(forPackage:String, remove:Boolean) {
        if(remove) {
            excludedPackagesData = excludedPackagesData - forPackage
        } else {
            excludedPackagesData = excludedPackagesData + forPackage
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
        } else {
            HomeScreenActivity.GlobalVariable.isScreenLockedSetting = 0
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
        median50.postValue(medianP90)
        _median90 = medianP90
    }

    fun setNumOfReq(){
        val numReq = if(HomeScreenActivity.GlobalVariable.lifeTimeQueries > 0) HomeScreenActivity.GlobalVariable.lifeTimeQueries + 1
        else {
            _numberOfRequests + 1
        }
        _numberOfRequests = numReq
        HomeScreenActivity.GlobalVariable.lifeTimeQueries = numReq
        HomeScreenActivity.GlobalVariable.lifeTimeQ.postValue(numReq)
    }

    fun getNumOfReq(): Int {
        if (HomeScreenActivity.GlobalVariable.lifeTimeQueries >= 0) {
            HomeScreenActivity.GlobalVariable.lifeTimeQ.postValue(lifeTimeQueries)
            return HomeScreenActivity.GlobalVariable.lifeTimeQueries
        }
        HomeScreenActivity.GlobalVariable.lifeTimeQ.postValue(_numberOfRequests)
        return _numberOfRequests
    }

    fun incrementBlockedReq() {
        numberOfBlockedRequests += 1
        blockedCount.postValue(numberOfBlockedRequests)
    }

    fun setIsBackgroundEnabled(isEnabled: Boolean) {
        backgroundEnabled = isEnabled
        HomeScreenActivity.GlobalVariable.isBackgroundEnabled = backgroundEnabled
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

    //fixme replace the below logic once the DNS data is streamlined.
    fun getConnectedDNS() : String{
        if(connectedDNS.value.isNullOrEmpty()){
            val dnsType = appMode?.getDNSType()
            return if(dnsType == 1){
                val dohDetail: DoHEndpoint?
                try {
                    dohDetail = appMode?.getDOHDetails()
                    dohDetail?.dohName!!
                } catch (e: Exception) {
                    connectedDNSName
                }
            }else if(dnsType == 2){
                if(appMode?.getDNSCryptServerCount() != null) {
                    val cryptDetails = appMode?.getDNSCryptServerCount()
                    "DNSCrypt: $cryptDetails resolvers"
                }
                "DNSCrypt: 0 resolvers"
            }else{
                val proxyDetails = appMode?.getDNSProxyServerDetails()
                proxyDetails?.proxyAppName!!
            }
        }
        else
            return connectedDNS.value!!
    }

    fun setConnectedDNS(name : String) {
        connectedDNS.postValue(name)
        connectedDNSName = name
    }

    fun setOrbotModePersistence(mode : Int){
        orbotModeConst = mode
        orbotMode = mode
    }

    fun getOrbotModePersistence(): Int{
        if(orbotModeConst == 0)
            return orbotMode
        else
            return orbotModeConst
    }
}