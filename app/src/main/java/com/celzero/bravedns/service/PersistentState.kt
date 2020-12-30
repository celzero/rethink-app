/*
Copyright 2020 RethinkDNS and its authors

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

package com.celzero.bravedns.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.blockedCount
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.braveMode
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.isScreenLocked
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.isScreenLockedSetting
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.lifeTimeQ
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.lifeTimeQueries
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.median50
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.medianP90
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.numUniversalBlock
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import settings.Settings


class PersistentState internal constructor(context: Context) {
    companion object {
        private const val IS_FIRST_LAUNCH = "is_first_time_launch"
        private const val APPS_KEY_DATA = "pref_apps_data"
        private const val APPS_KEY_WIFI = "pref_apps_wifi"
        private const val CATEGORY_DATA = "blocked_categories"

        //const val URL_KEY = "pref_server_url"
        private const val DNS_MODE = "dns_mode"
        private const val FIREWALL_MODE = "firewall_mode"
        const val BRAVE_MODE = "brave_mode"
        const val BACKGROUND_MODE = "background_mode"
        private const val SCREEN_STATE = "screen_state"
        const val DNS_TYPE = "dns_type"
        const val PROXY_MODE = "proxy_mode"
        private const val DNS_ALL_TRAFFIC = "dns_all_traffic"
        const val ALLOW_BYPASS = "allow_bypass"
        const val PRIVATE_DNS = "private_dns"
        private const val ENABLE_LOCAL_LOGS = "local_logs"
        const val EXCLUDE_FROM_VPN = "exclude_apps_vpn"
        private const val APP_VERSION = "app_version"

        const val IS_SCREEN_OFF = "screen_off"
        private const val CUSTOM_URL_BOOLEAN = "custom_url_boolean"
        private const val CUSTOM_URL = "custom_url"

        private const val pref_auto_start_bootup = "auto_start_on_boot"
        private const val KILL_APP_FIREWALL = "kill_app_on_firewall"
        private const val SOCKS5 = "socks5_proxy"

        const val CONNECTION_CHANGE = "change_in_url"
        private const val DOWNLOAD_BLOCK_LIST_FILES = "download_block_list_files"
        const val LOCAL_BLOCK_LIST = "enable_local_list"
        private const val LOCAL_BLOCK_LIST_TIME = "local_block_list_downloaded_time"
        private const val REMOTE_BLOCK_LIST_TIME_MS = "remote_block_list_downloaded_time"
        private const val REMOTE_BLOCK_COMPLETE = "download_remote_block_list"
        const val LOCAL_BLOCK_LIST_STAMP = "local_block_list_stamp"
        private const val LOCAL_BLOCK_LIST_COUNT = "local_block_list_count"
        private const val REMOTE_BLOCK_LIST_COUNT = "remote_block_list_count"
        const val BLOCK_UNKNOWN_CONNECTIONS = "block_unknown_connections"
        private const val IS_INSERT_COMPLETE = "initial_insert_servers_complete"
        private const val APP_UPDATE_LAST_CHECK = "app_update_last_check"
        private const val APP_DOWNLOAD_SOURCE = "app_downloaded_source"

        private const val APPROVED_KEY = "approved"
        private const val ENABLED_KEY = "enabled"
        private const val SERVER_KEY = "server"

        private const val MEDIAN_90 = "median_p90"
        private const val NUMBER_REQUEST = "number_request"
        private const val BLOCKED_COUNT = "blocked_request"

        const val HTTP_PROXY_ENABLED = "http_proxy_enabled"
        private const val HTTP_PROXY_IPADDRESS = "http_proxy_ipaddress"
        private const val HTTP_PROXY_PORT = "http_proxy_port"

        const val DNS_PROXY_ID = "dns_proxy_change"

        const val BLOCK_UDP_OTHER_THAN_DNS = "block_udp_traffic_other_than_dns"

        private const val INTERNAL_STATE_NAME = "MainActivity"

        // The approval state is currently stored in a separate preferences file.
        // TODO: Unify preferences into a single file.
        private const val APPROVAL_PREFS_NAME = "IntroState"

        fun expandUrl(context: Context, url: String?): String {
            return if (url == null || url.isEmpty()) {
                context.resources.getStringArray(R.array.doh_endpoint_names)[3]
            } else url
        }
    }

    val userPreferences: SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context)

    /*fun getInternalState(context: Context): SharedPreferences {
        return context.getSharedPreferences(
            INTERNAL_STATE_NAME,
            Context.MODE_PRIVATE
        )
    }*/

    fun getVpnEnabled(): Boolean {
        return userPreferences.getBoolean(ENABLED_KEY, false)
    }

    fun setFirstTimeLaunch(isFirstTime: Boolean) =
        userPreferences.edit {
            putBoolean(IS_FIRST_LAUNCH, isFirstTime)
        }

    fun isFirstTimeLaunch(): Boolean {
        return userPreferences.getBoolean(IS_FIRST_LAUNCH, true)
    }

    fun setVpnEnabled(enabled: Boolean) =
        userPreferences.edit {
            putBoolean(ENABLED_KEY, enabled)
        }


    /*fun syncLegacyState(context: Context) {
        // Copy the domain choice into the new URL setting, if necessary.
        if (getServerUrl(context) != null) {
            // New server URL is already populated
            return
        }

        // There is no URL setting, so read the legacy server name.
        val settings: SharedPreferences = userPreferences
        val domain =
            settings.getString(SERVER_KEY, null)
                ?: // Legacy setting is in the default state, so we can leave the new URL setting in the default
                // state as well.
                return
        val urls = context.resources.getStringArray(R.array.urls)
        val defaultDomain = context.resources.getString(R.string.legacy_domain0)
        var url: String? = null
        if (domain == defaultDomain) {
            // Common case: the domain is dns.google.com, which now corresponds to dns.google (url 0).
            url = urls[0]
        } else {
            // In practice, we expect that domain will always be cloudflare-dns.com at this point, because
            // that was the only other option in the relevant legacy versions of Intra.
            // Look for the corresponding URL among the builtin servers.
            for (u in urls) {
                val parsed = Uri.parse(u)
                if (domain == parsed.host) {
                    url = u
                    break
                }
            }
        }
        if (url == null) {
            return
        }
        setServerUrl(context, url)
    }*/

    /* fun setServerUrl(context: Context?, url: String?) {
         val editor: SharedPreferences.Editor =
             userPreferences!!.edit()
         editor.putString(URL_KEY, url)
         editor.apply()
     }

     fun getServerUrl(context: Context?): String {
         val urlTemplate: String = userPreferences.getString(URL_KEY, null)
             //?: return context.resources.getString(R.string.url0)
             ?: return context.resources.getStringArray(R.array.doh_endpoint_urls).get(2)
         return strip(urlTemplate)
     }*/

    fun getExcludedPackagesWifi(): Set<String?>? {
        return userPreferences.getStringSet(APPS_KEY_WIFI,HashSet())
    }

    fun setExcludedPackagesWifi(packageName : String, toRemove : Boolean){
        val newSet: MutableSet<String> = HashSet(userPreferences.getStringSet(APPS_KEY_WIFI, HashSet<String>()))
        if(toRemove) {
            if (newSet.contains(packageName)) {
                newSet.remove(packageName)
            }
        }
        else newSet.add(packageName)
        userPreferences.edit { putStringSet(APPS_KEY_WIFI,newSet) }
        //appsBlocked.postValue(newSet.size)
    }

    fun isWifiAllowed(packageName : String) : Boolean{
        return !userPreferences.getStringSet(APPS_KEY_WIFI,HashSet<String>())!!.contains(packageName)
    }


    fun getExcludedPackagesData(): Set<String?>? {
        return userPreferences.getStringSet(
            APPS_KEY_DATA,
            HashSet<String>()
        )
    }

    fun setExcludedPackagesData(packageName : String, toRemove : Boolean){

        //TODO : hardcode value for testing. Need to modify
        // sets = userPreferences!!.getStringSet(APPS_KEY, HashSet<String>())!!
        val newSet: MutableSet<String> = HashSet(userPreferences.getStringSet(APPS_KEY_DATA, HashSet<String>()))
        if(toRemove){
            if(newSet.contains(packageName))
                newSet.remove(packageName)
        }
        else  newSet.add(packageName)
        userPreferences.edit { putStringSet(APPS_KEY_DATA,newSet) }
    }

    private fun strip(template: String): String {
        return template.replace("\\{[^}]*\\}".toRegex(), "")
    }


    fun setBraveMode(mode: Int) =
        userPreferences.edit {
            putInt(BRAVE_MODE, mode)
        }

    fun getBraveMode(): Int {
        if (braveMode == -1)
            return userPreferences.getInt(BRAVE_MODE, 2)
        else
            return braveMode
    }

    /* fun setDnsMode(context: Context, mode : Int){
         val editor: SharedPreferences.Editor =
             userPreferences!!.edit()
         editor.putInt(DNS_MODE, mode)
         editor.apply()
     }

     //TODO : Modify the hardcoded value
     fun getDnsMode(context: Context):Int {
         if(dnsMode == -1)
             return userPreferences!!.getInt(DNS_MODE, 1)
         else
             return dnsMode
     }*/

    fun setFirewallMode(fwMode: Int) =
        userPreferences.edit {
            putInt(FIREWALL_MODE, fwMode)
        }

    //TODO : Modify the hardcoded value
    fun getFirewallMode(): Int {
        return userPreferences.getInt(FIREWALL_MODE, 1)
    }


    fun setFirewallModeForScreenState(state : Boolean) {
        userPreferences.edit {
            putBoolean(SCREEN_STATE, state)
        }
        if (state) {
            isScreenLockedSetting = 1
            if (getBackgroundEnabled()) {
                numUniversalBlock.postValue(2)
            } else {
                numUniversalBlock.postValue(1)
            }
        } else {
            isScreenLockedSetting = 0
            if (getBackgroundEnabled()) {
                numUniversalBlock.postValue(1)
            } else {
                numUniversalBlock.postValue(0)
            }
        }
    }


    fun getFirewallModeForScreenState() : Boolean{
        return if(isScreenLockedSetting == 0) {
            false
        }else if(isScreenLockedSetting == 1){
            true
        }else{
            userPreferences.getBoolean(SCREEN_STATE,false)
        }
    }

    fun setMedianLatency(medianP90 : Long){
        HomeScreenActivity.GlobalVariable.medianP90 = medianP90
        median50.postValue(medianP90)
        userPreferences.edit {
            putLong(MEDIAN_90, medianP90)
        }
    }

    fun getMedianLatency() : Long{
        if(medianP90 == (-1).toLong())
            return userPreferences.getLong(MEDIAN_90, 0L)
        else
            return medianP90
    }

    fun setNumOfReq(){
        var numReq = 0
        if(lifeTimeQueries > 0)
            numReq = lifeTimeQueries + 1
        else {
            numReq = userPreferences.getInt(NUMBER_REQUEST, 0) + 1
        }
        userPreferences.edit {
            putInt(NUMBER_REQUEST, numReq)
        }
        lifeTimeQueries = numReq
        lifeTimeQ.postValue(numReq)
    }

    fun getNumOfReq(): Int {
        if (lifeTimeQueries >= 0)
            return lifeTimeQueries
        return userPreferences.getInt(NUMBER_REQUEST, 0)
    }

    fun setBlockedReq() {
        val bCount = userPreferences.getInt(BLOCKED_COUNT, 0) + 1
        userPreferences.edit {
            putInt(BLOCKED_COUNT, bCount)
        }
        blockedCount.postValue(bCount)
    }

    fun getBlockedReq(): Int {
        return userPreferences.getInt(BLOCKED_COUNT, 0)
    }

    fun getBackgroundEnabled(): Boolean {
        return userPreferences.getBoolean(BACKGROUND_MODE, false)
    }

    fun setBackgroundEnabled(isEnabled : Boolean){
        userPreferences.edit {
            putBoolean(BACKGROUND_MODE, isEnabled)
        }
        var uValue = numUniversalBlock.value
        if(isEnabled) {
            if (getScreenLockData()) {
                numUniversalBlock.postValue(2)
            }else{
                numUniversalBlock.postValue(1)
            }
        }else{
            if (getScreenLockData()) {
                numUniversalBlock.postValue(1)
            }else{
                numUniversalBlock.postValue(0)
            }
        }

    }

    fun setScreenLockData(isEnabled : Boolean) {
        //HomeScreenActivity.GlobalVariable.isScreenLocked = isEnabled
        isScreenLocked = if(isEnabled){
            1
        }else{
            0
        }
        userPreferences.edit {
            putBoolean(IS_SCREEN_OFF, isEnabled)
        }
    }

    fun getScreenLockData(): Boolean {
        return if (isScreenLocked == 0) {
            false
        } else if (isScreenLocked == 1) {
            true
        } else {
            userPreferences.getBoolean(IS_SCREEN_OFF, false)
        }
    }


    fun setPrefAutoStartBootup(isEnabled: Boolean) =
        userPreferences.edit {
            putBoolean(IS_SCREEN_OFF, isEnabled)
        }


    fun getPrefAutoStartBootUp(): Boolean {
        return userPreferences.getBoolean(pref_auto_start_bootup, true)
    }

/*

        fun setCustomURLBool(context: Context, isEnabled: Boolean) {
            val editor: SharedPreferences.Editor = userPreferences.edit()
            editor.putBoolean(CUSTOM_URL_BOOLEAN, isEnabled)
            editor.apply()
        }

        fun getCustomURLBool(context: Context): Boolean {
            return userPreferences?.getBoolean(CUSTOM_URL_BOOLEAN, false)
        }

        fun setCustomURLVal(context: Context, url: String) {
            val editor: SharedPreferences.Editor = userPreferences.edit()
            editor.putString(CUSTOM_URL, url)
            editor.apply()
        }

        fun getCustomURLVal(context: Context): String {
            return userPreferences?.getString(CUSTOM_URL, "https://")!!
        }
*/

    fun setDNSType(type: Int) =
        userPreferences.edit {
            putInt(DNS_TYPE, type)
        }

    fun getDNSType(): Int {
        return userPreferences.getInt(DNS_TYPE, 1)
    }

    fun getProxyMode(): Long {
        return userPreferences.getLong(PROXY_MODE, Settings.ProxyModeNone)
    }

    fun setProxyMode(proxyMode: Long) =
        userPreferences.edit {
            putLong(PROXY_MODE, proxyMode)
        }

    fun getAllDNSTraffic(): Boolean {
        return userPreferences.getBoolean(DNS_ALL_TRAFFIC, true)
    }

    // FIXME: 10-10-2020 DNS Traffic change
    fun setAllDNSTraffic(isSelected: Boolean) =
        userPreferences.edit {
            putBoolean(DNS_ALL_TRAFFIC, isSelected)
        }

    fun setAllowByPass(isEnabled: Boolean) =
        userPreferences.edit {
            putBoolean(ALLOW_BYPASS, isEnabled)
        }

    fun getAllowByPass(): Boolean {
        return userPreferences.getBoolean(ALLOW_BYPASS, true)
    }

    fun getAllowPrivateDNS(): Boolean {
        return userPreferences.getBoolean(PRIVATE_DNS, false)
    }

    fun setAllowPrivateDNS(isEnabled: Boolean) =
        userPreferences.edit {
            putBoolean(PRIVATE_DNS, isEnabled)
        }

    fun getKillAppOnFirewall(): Boolean {
        return userPreferences.getBoolean(KILL_APP_FIREWALL, true)
    }

    fun setKillAppOnFirewall(isEnabled: Boolean) =
        userPreferences.edit {
            putBoolean(KILL_APP_FIREWALL, isEnabled)
        }

    fun setSocks5Enabled(context: Context, isEnabled: Boolean) =
        userPreferences.edit {
            putBoolean(SOCKS5, isEnabled)
    }


    fun setSocks5Enabled(isEnabled: Boolean) =
        userPreferences.edit {
            putBoolean(KILL_APP_FIREWALL, isEnabled)
        }

    fun setConnectionModeChange(url: String) =
        userPreferences.edit {
            putString(CONNECTION_CHANGE, url)
        }

    fun getConnectionModeChange(): String {
        return userPreferences.getString(CONNECTION_CHANGE, "")!!
    }


    fun getExcludedAppsFromVPN(): MutableSet<String>? {
        return userPreferences.getStringSet(EXCLUDE_FROM_VPN, HashSet())
    }

    fun setExcludedAppsFromVPN(newSet: MutableSet<String>) {
        /* val newSet: MutableSet<String> = HashSet(userPreferences.getStringSet(EXCLUDE_FROM_VPN, HashSet<String>())!!)
         if (toRemove) {
             if (newSet.contains(packageName)) {
                 newSet.remove(packageName)
             }
         } else newSet.add(packageName)*/
        userPreferences.edit { putStringSet(EXCLUDE_FROM_VPN, newSet) }
    }

    fun setHttpProxyEnabled(isEnabled: Boolean) =
        userPreferences.edit {
            putBoolean(HTTP_PROXY_ENABLED, isEnabled)
        }

    fun getHttpProxyEnabled(): Boolean {
        return userPreferences.getBoolean(HTTP_PROXY_ENABLED, false)
    }

    fun setHttpProxyHostAddress(ipAddress: String) =
        userPreferences.edit {
            putString(HTTP_PROXY_IPADDRESS, ipAddress)
        }

    fun getHttpProxyHostAddress(): String? {
        return userPreferences.getString(HTTP_PROXY_IPADDRESS, "")
    }

    fun setHttpProxyPort(port: Int) =
        userPreferences.edit {
            putInt(HTTP_PROXY_PORT, port)
        }

    fun getHttpProxyPort(): Int {
        return userPreferences.getInt(HTTP_PROXY_PORT, 0)
    }

    fun setLocalBlockListEnabled(isEnabled: Boolean) =
        userPreferences.edit {
            putBoolean(LOCAL_BLOCK_LIST, isEnabled)
        }

    fun setLocalBlockListDownloadTime(time: Long) =
        userPreferences.edit {
            putLong(LOCAL_BLOCK_LIST_TIME, time)
        }

    fun getLocalBlockListDownloadTime(): Long {
        return userPreferences.getLong(LOCAL_BLOCK_LIST_TIME, 0L)
    }

    fun setRemoteBlockListDownloadTime(time: Long) =
        userPreferences.edit {
            putLong(REMOTE_BLOCK_LIST_TIME_MS, time)
        }

    fun getRemoteBlockListDownloadTime(): Long {
        return userPreferences.getLong(REMOTE_BLOCK_LIST_TIME_MS, 0L)
    }

    fun isLocalBlockListEnabled(): Boolean {
        return userPreferences.getBoolean(LOCAL_BLOCK_LIST, false)
    }

    fun isBlockListFilesDownloaded(): Boolean {
        return userPreferences.getBoolean(DOWNLOAD_BLOCK_LIST_FILES, false)
    }

    fun setBlockListFilesDownloaded(isDownloaded: Boolean) =
        userPreferences.edit {
            putBoolean(DOWNLOAD_BLOCK_LIST_FILES, isDownloaded)
        }

    fun getBlockUnknownConnections(): Boolean {
        return userPreferences.getBoolean(BLOCK_UNKNOWN_CONNECTIONS, false)
    }

    fun setBlockUnknownConnections(isEnabled: Boolean) =
        userPreferences.edit {
            putBoolean(BLOCK_UNKNOWN_CONNECTIONS, isEnabled)
        }

    fun setLocalBlockListStamp(stamp: String) {
        if (DEBUG) Log.d(LOG_TAG, "In preference, Set local stamp: $stamp")
        userPreferences.edit {
            putString(LOCAL_BLOCK_LIST_STAMP, stamp)
        }
    }

    fun getLocalBlockListStamp(): String? {
        if (DEBUG) Log.d(
            LOG_TAG,
            "In preference, Get local stamp: ${
                userPreferences.getString(
                    LOCAL_BLOCK_LIST_STAMP,
                    ""
                )
            }"
        )
        return userPreferences.getString(LOCAL_BLOCK_LIST_STAMP, "")
    }

    fun isRemoteBraveDNSDownloaded(): Boolean {
        return userPreferences.getBoolean(REMOTE_BLOCK_COMPLETE, false)
    }

    fun setRemoteBraveDNSDownloaded(isDownloaded: Boolean) =
        userPreferences.edit {
            putBoolean(REMOTE_BLOCK_COMPLETE, isDownloaded)
        }

    fun isInsertionCompleted(): Boolean {
        return userPreferences.getBoolean(IS_INSERT_COMPLETE, false)
    }

    fun setInsertionCompleted(isComplete: Boolean) =
        userPreferences.edit {
            putBoolean(IS_INSERT_COMPLETE, isComplete)
        }

    fun setDNSProxyIDChange(id: Int) =
        userPreferences.edit {
            putInt(DNS_PROXY_ID, id)
        }

    fun getDNSProxyIDChange(): Int {
        return userPreferences.getInt(DNS_PROXY_ID, 0)
    }

    fun getUDPBlockedSettings(): Boolean {
        return userPreferences.getBoolean(BLOCK_UDP_OTHER_THAN_DNS, false)
    }

    fun setUDPBlockedSettings(isEnabled: Boolean) =
        userPreferences.edit {
            putBoolean(BLOCK_UDP_OTHER_THAN_DNS, isEnabled)
        }

    fun getNumberOfLocalBlockLists(): Int {
        return userPreferences.getInt(LOCAL_BLOCK_LIST_COUNT, 0)
    }

    fun setNumberOfLocalBlockLists(blockCount: Int) =
        userPreferences.edit {
            putInt(LOCAL_BLOCK_LIST_COUNT, blockCount)
        }


    fun getNumberOfRemoteBlockLists(): Int {
        return userPreferences.getInt(REMOTE_BLOCK_LIST_COUNT, 0)
    }

    fun setNumberOfRemoteBlockLists(blockCount: Int) =
        userPreferences.edit {
            putInt(REMOTE_BLOCK_LIST_COUNT, blockCount)
        }


    fun setLastAppUpdateCheckTime(time: Long) =
        userPreferences.edit {
            putLong(APP_UPDATE_LAST_CHECK, time)
        }

    fun getLastAppUpdateCheckTime(): Long {
        return userPreferences.getLong(APP_UPDATE_LAST_CHECK, 0)
    }

    fun setAppVersion(version: Int) =
        userPreferences.edit {
            putInt(APP_VERSION, version)
        }

    fun getAppVersion(): Int {
        return userPreferences.getInt(APP_VERSION, 0)
    }

    fun setDownloadSource(source: Int) =
        userPreferences.edit {
            putInt(APP_DOWNLOAD_SOURCE, source)
        }

    fun getDownloadSource(): Int {
        return userPreferences.getInt(APP_DOWNLOAD_SOURCE, 0)
    }

    fun isLogsEnabled(): Boolean {
        return userPreferences.getBoolean(ENABLE_LOCAL_LOGS, true)
    }

    fun setLogsEnabled(isLogsEnabled: Boolean) =
        userPreferences.edit {
            putBoolean(ENABLE_LOCAL_LOGS, isLogsEnabled)
        }
}
