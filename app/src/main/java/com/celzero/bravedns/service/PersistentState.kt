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
import android.preference.PreferenceManager
import android.util.Log
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


class PersistentState {

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
        const val EXCLUDE_FROM_VPN = "exclude_apps_vpn"
        private const val APP_VERSION = "app_version"

        const val IS_SCREEN_OFF = "screen_off"
        private const val CUSTOM_URL_BOOLEAN = "custom_url_boolean"
        private const val CUSTOM_URL = "custom_url"

        private const val pref_auto_start_bootup = "auto_start_on_boot"
        private const val KILL_APP_FIREWALL = "kill_app_on_firewall"
        private const val SOCKS5 = "socks5_proxy"

        const val CONNECTION_CHANGE =  "change_in_url"
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
        private const val APP_DOWNLOAD_SOURCE ="app_downloaded_source"

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


        /*fun getInternalState(context: Context): SharedPreferences {
            return context.getSharedPreferences(
                INTERNAL_STATE_NAME,
                Context.MODE_PRIVATE
            )
        }*/

        fun getUserPreferences(context: Context): SharedPreferences {
            return PreferenceManager.getDefaultSharedPreferences(context)
        }

        fun getVpnEnabled(context: Context): Boolean {
            return getUserPreferences(context).getBoolean(ENABLED_KEY, false)
        }


        fun setFirstTimeLaunch(context: Context, isFirstTime : Boolean){
            val editor = getUserPreferences(context).edit()
               editor.putBoolean(IS_FIRST_LAUNCH, isFirstTime)
               editor.apply()
           }

           fun isFirstTimeLaunch(context:Context) : Boolean {
               return getUserPreferences(context)!!.getBoolean(IS_FIRST_LAUNCH,true)
           }

        fun expandUrl(context: Context, url: String?): String {
            return if (url == null || url.isEmpty()) {
                context.resources.getStringArray(R.array.doh_endpoint_names).get(2)
            } else url
        }

        fun setVpnEnabled(context: Context, enabled: Boolean) {
            val editor = getUserPreferences(context).edit()
            editor.putBoolean(ENABLED_KEY, enabled)
            editor.apply()
        }


        /*fun syncLegacyState(context: Context) {
            // Copy the domain choice into the new URL setting, if necessary.
            if (getServerUrl(context) != null) {
                // New server URL is already populated
                return
            }

            // There is no URL setting, so read the legacy server name.
            val settings: SharedPreferences = getUserPreferences(context)
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
                getUserPreferences(context!!)!!.edit()
            editor.putString(URL_KEY, url)
            editor.apply()
        }

        fun getServerUrl(context: Context?): String {
            val urlTemplate: String = getUserPreferences(context!!).getString(URL_KEY, null)
                //?: return context.resources.getString(R.string.url0)
                ?: return context.resources.getStringArray(R.array.doh_endpoint_urls).get(2)
            return strip(urlTemplate)
        }*/


        fun getExcludedPackagesWifi(context: Context?): Set<String?>? {
            return getUserPreferences(context!!).getStringSet(APPS_KEY_WIFI,HashSet())
        }

        fun setExcludedPackagesWifi(packageName : String, toRemove : Boolean , context : Context){
            val newSet: MutableSet<String> = HashSet(getUserPreferences(context!!)!!.getStringSet(APPS_KEY_WIFI, HashSet<String>()))
            if(toRemove) {
                if (newSet.contains(packageName)) {
                    newSet.remove(packageName)
                }
            }
            else newSet.add(packageName)
            getUserPreferences(context)!!.edit().putStringSet(APPS_KEY_WIFI,newSet).apply()
            //appsBlocked.postValue(newSet.size)
        }

        fun isWifiAllowed(packageName : String, context: Context) : Boolean{
            return !getUserPreferences(context)?.getStringSet(APPS_KEY_WIFI,HashSet<String>())!!.contains(packageName)
        }


        fun getExcludedPackagesData(context: Context?): Set<String?>? {
            return getUserPreferences(context!!)?.getStringSet(
                APPS_KEY_DATA,
                HashSet<String>()
            )
        }

        fun setExcludedPackagesData(packageName : String, toRemove : Boolean , context : Context?){

            //TODO : hardcode value for testing. Need to modify
            // sets = getUserPreferences(context!!)!!.getStringSet(APPS_KEY, HashSet<String>())!!
            val newSet: MutableSet<String> = HashSet(getUserPreferences(context!!)!!.getStringSet(APPS_KEY_DATA, HashSet<String>()))
            if(toRemove){
                if(newSet.contains(packageName))
                    newSet.remove(packageName)
            }
            else  newSet.add(packageName)
            getUserPreferences(context)!!.edit().putStringSet(APPS_KEY_DATA,newSet).apply()
        }

        private fun strip(template: String): String {
            return template.replace("\\{[^}]*\\}".toRegex(), "")
        }


        fun setBraveMode(context: Context , mode: Int){

            val editor: SharedPreferences.Editor =
                getUserPreferences(context)!!.edit()
            editor.putInt(BRAVE_MODE, mode)
            editor.apply()
        }

        fun getBraveMode(context: Context) : Int{
            if(braveMode == -1)
                return getUserPreferences(context)!!.getInt(BRAVE_MODE, -1)
            else
                return  braveMode
        }

       /* fun setDnsMode(context: Context, mode : Int){
            val editor: SharedPreferences.Editor =
                getUserPreferences(context)!!.edit()
            editor.putInt(DNS_MODE, mode)
            editor.apply()
        }

        //TODO : Modify the hardcoded value
        fun getDnsMode(context: Context):Int {
            if(dnsMode == -1)
                return getUserPreferences(context)!!.getInt(DNS_MODE, 1)
            else
                return dnsMode
        }*/

        fun setFirewallMode(context: Context, fwMode : Int){
            val editor: SharedPreferences.Editor =
                getUserPreferences(context).edit()
            editor.putInt(FIREWALL_MODE, fwMode)
            editor.apply()
        }

        //TODO : Modify the hardcoded value
        fun getFirewallMode(context: Context):Int {
            return getUserPreferences(context).getInt(FIREWALL_MODE, 1)
        }


        fun setFirewallModeForScreenState(context : Context , state : Boolean) {
            val editor: SharedPreferences.Editor = getUserPreferences(context).edit()
            editor.putBoolean(SCREEN_STATE, state)
            editor.apply()
            if (state) {
                isScreenLockedSetting = 1
                if (getBackgroundEnabled(context)) {
                    numUniversalBlock.postValue(2)
                } else {
                    numUniversalBlock.postValue(1)
                }
            } else {
                isScreenLockedSetting = 0
                if (getBackgroundEnabled(context)) {
                    numUniversalBlock.postValue(1)
                } else {
                    numUniversalBlock.postValue(0)
                }
            }
        }


        fun getFirewallModeForScreenState(context : Context) : Boolean{
            return if(isScreenLockedSetting == 0) {
                false
            }else if(isScreenLockedSetting == 1){
                true
            }else{
                getUserPreferences(context).getBoolean(SCREEN_STATE,false)
            }
        }

        fun setMedianLatency(context: Context, medianP90 : Long){
            HomeScreenActivity.GlobalVariable.medianP90 = medianP90
            median50.postValue(medianP90)
            val editor: SharedPreferences.Editor = getUserPreferences(context)!!.edit()
            editor.putLong(MEDIAN_90, medianP90)
            editor.apply()
        }

        fun getMedianLatency(context: Context) : Long{
            if(medianP90 == (-1).toLong())
                return getUserPreferences(context).getLong(MEDIAN_90, 0L)
            else
                return medianP90
        }

        fun setNumOfReq(context : Context){
            var numReq = 0
            if(lifeTimeQueries > 0)
                numReq = lifeTimeQueries + 1
            else {
                numReq = getUserPreferences(context)?.getInt(NUMBER_REQUEST, 0)!! + 1
            }
            val editor: SharedPreferences.Editor = getUserPreferences(context)!!.edit()
            editor.putInt(NUMBER_REQUEST, numReq)
            editor.apply()
            lifeTimeQueries = numReq
            lifeTimeQ.postValue(numReq)
        }

        fun getNumOfReq(context: Context) : Int{
            if(lifeTimeQueries >=0 )
                return lifeTimeQueries
            return getUserPreferences(context)?.getInt(NUMBER_REQUEST,0)!!
        }

        fun setBlockedReq(context : Context){
            val bCount =  getUserPreferences(context)?.getInt(BLOCKED_COUNT,0) + 1
            val editor: SharedPreferences.Editor = getUserPreferences(context).edit()
            editor.putInt(BLOCKED_COUNT, bCount)
            editor.apply()
            blockedCount.postValue(bCount)
        }

        fun getBlockedReq(context: Context) : Int{
            return getUserPreferences(context).getInt(BLOCKED_COUNT,0)
        }

        fun getBackgroundEnabled(context: Context) : Boolean{
            return getUserPreferences(context).getBoolean(BACKGROUND_MODE,false)
        }

        fun setBackgroundEnabled(context: Context, isEnabled : Boolean){
            val editor: SharedPreferences.Editor =
                getUserPreferences(context).edit()
            editor.putBoolean(BACKGROUND_MODE, isEnabled)
            editor.apply()
            var uValue = numUniversalBlock.value
            if(isEnabled) {
                if (getScreenLockData(context)) {
                    numUniversalBlock.postValue(2)
                }else{
                    numUniversalBlock.postValue(1)
                }
            }else{
                if (getScreenLockData(context)) {
                    numUniversalBlock.postValue(1)
                }else{
                    numUniversalBlock.postValue(0)
                }
            }

        }

        fun setScreenLockData(context: Context, isEnabled : Boolean) {
            //HomeScreenActivity.GlobalVariable.isScreenLocked = isEnabled
            isScreenLocked = if(isEnabled){
                1
            }else{
                0
            }
            val editor: SharedPreferences.Editor =
                getUserPreferences(context).edit()
            editor.putBoolean(IS_SCREEN_OFF, isEnabled)
            editor.apply()
        }

        fun getScreenLockData(context: Context) : Boolean{
            return if(isScreenLocked == 0){
                false
            } else if(isScreenLocked == 1){
                true
            }else {
                getUserPreferences(context).getBoolean(IS_SCREEN_OFF, false)
            }
        }


        fun setPrefAutoStartBootup(context: Context, isEnabled: Boolean) {
            val editor: SharedPreferences.Editor =
                getUserPreferences(context).edit()
            editor.putBoolean(pref_auto_start_bootup, isEnabled)
            editor.apply()
        }

        fun getPrefAutoStartBootUp(context: Context): Boolean {
            return getUserPreferences(context).getBoolean(pref_auto_start_bootup, true)
        }

/*

        fun setCustomURLBool(context: Context, isEnabled: Boolean) {
            val editor: SharedPreferences.Editor = getUserPreferences(context).edit()
            editor.putBoolean(CUSTOM_URL_BOOLEAN, isEnabled)
            editor.apply()
        }

        fun getCustomURLBool(context: Context): Boolean {
            return getUserPreferences(context)?.getBoolean(CUSTOM_URL_BOOLEAN, false)
        }

        fun setCustomURLVal(context: Context, url: String) {
            val editor: SharedPreferences.Editor = getUserPreferences(context).edit()
            editor.putString(CUSTOM_URL, url)
            editor.apply()
        }

        fun getCustomURLVal(context: Context): String {
            return getUserPreferences(context)?.getString(CUSTOM_URL, "https://")!!
        }
*/

        fun setDNSType(context : Context, type : Int){
            val editor: SharedPreferences.Editor = getUserPreferences(context).edit()
            editor.putInt(DNS_TYPE, type)
            editor.apply()
        }

        fun getDNSType(context: Context): Int {
            return getUserPreferences(context).getInt(DNS_TYPE, 1)
        }

        fun getProxyMode(context: Context): Long{
            return getUserPreferences(context).getLong(PROXY_MODE, Settings.ProxyModeNone)
        }

        fun setProxyMode(context: Context , proxyMode : Long){
            val editor: SharedPreferences.Editor = getUserPreferences(context).edit()
            editor.putLong(PROXY_MODE, proxyMode)
            editor.apply()
        }

        fun getAllDNSTraffic(context : Context): Boolean{
            return getUserPreferences(context).getBoolean(DNS_ALL_TRAFFIC, true)
        }

        // FIXME: 10-10-2020 DNS Traffic change
        fun setAllDNSTraffic(context: Context, isSelected  :Boolean){
            val editor: SharedPreferences.Editor = getUserPreferences(context).edit()
            editor.putBoolean(DNS_ALL_TRAFFIC, isSelected)
            editor.apply()
        }

        fun setAllowByPass(context: Context, isEnabled: Boolean){
            val editor: SharedPreferences.Editor = getUserPreferences(context).edit()
            editor.putBoolean(ALLOW_BYPASS, isEnabled)
            editor.apply()
        }

        fun getAllowByPass(context : Context): Boolean{
            return getUserPreferences(context).getBoolean(ALLOW_BYPASS, true)
        }

        fun getAllowPrivateDNS(context: Context) : Boolean{
            return getUserPreferences(context).getBoolean(PRIVATE_DNS, false)
        }

        fun setAllowPrivateDNS(context : Context, isEnabled: Boolean){
            val editor: SharedPreferences.Editor = getUserPreferences(context).edit()
            editor.putBoolean(PRIVATE_DNS, isEnabled)
            editor.apply()
        }

        fun getKillAppOnFirewall(context: Context): Boolean {
            return getUserPreferences(context).getBoolean(KILL_APP_FIREWALL, true)
        }

        fun setKillAppOnFirewall(context: Context, isEnabled: Boolean) {
            val editor: SharedPreferences.Editor = getUserPreferences(context).edit()
            editor.putBoolean(KILL_APP_FIREWALL, isEnabled)
            editor.apply()
        }

        fun getSocks5Enabled(context: Context): Boolean{
            return getUserPreferences(context).getBoolean(SOCKS5, false)
        }

        fun setSocks5Enabled(context: Context, isEnabled: Boolean){
            val editor: SharedPreferences.Editor = getUserPreferences(context).edit()
            editor.putBoolean(SOCKS5, isEnabled)
            editor.apply()
        }

        fun setConnectionModeChange(context: Context, url: String){
            val editor: SharedPreferences.Editor = getUserPreferences(context).edit()
            editor.putString(CONNECTION_CHANGE, url)
            editor.apply()
        }

        fun getConnectionModeChange(context: Context) : String{
            return getUserPreferences(context).getString(CONNECTION_CHANGE, "")!!
        }


        fun getExcludedAppsFromVPN(context: Context?): MutableSet<String>? {
            return getUserPreferences(context!!).getStringSet(EXCLUDE_FROM_VPN, HashSet())
        }

        fun setExcludedAppsFromVPN(newSet: MutableSet<String>, context: Context) {
           /* val newSet: MutableSet<String> = HashSet(getUserPreferences(context).getStringSet(EXCLUDE_FROM_VPN, HashSet<String>())!!)
            if (toRemove) {
                if (newSet.contains(packageName)) {
                    newSet.remove(packageName)
                }
            } else newSet.add(packageName)*/
            getUserPreferences(context).edit().putStringSet(EXCLUDE_FROM_VPN, newSet).apply()
        }

        fun setHttpProxyEnabled(context: Context, isEnabled: Boolean){
            val editor: SharedPreferences.Editor = getUserPreferences(context).edit()
            editor.putBoolean(HTTP_PROXY_ENABLED, isEnabled)
            editor.apply()
        }

        fun getHttpProxyEnabled(context: Context): Boolean{
            return getUserPreferences(context).getBoolean(HTTP_PROXY_ENABLED, false)
        }

        fun setHttpProxyHostAddress(context: Context, ipAddress : String){
            val editor: SharedPreferences.Editor = getUserPreferences(context).edit()
            editor.putString(HTTP_PROXY_IPADDRESS, ipAddress)
            editor.apply()
        }

        fun getHttpProxyHostAddress(context: Context) : String? {
            return getUserPreferences(context).getString(HTTP_PROXY_IPADDRESS, "")
        }

        fun setHttpProxyPort(context: Context, port: Int){
            val editor: SharedPreferences.Editor = getUserPreferences(context).edit()
            editor.putInt(HTTP_PROXY_PORT, port)
            editor.apply()
        }

        fun getHttpProxyPort(context: Context): Int?{
            return getUserPreferences(context).getInt(HTTP_PROXY_PORT, 0)
        }

        fun setLocalBlockListEnabled(context: Context, isEnabled: Boolean){
            val editor: SharedPreferences.Editor = getUserPreferences(context).edit()
            editor.putBoolean(LOCAL_BLOCK_LIST, isEnabled)
            editor.apply()
        }

        fun setLocalBlockListDownloadTime(context: Context, time : Long){
            val editor: SharedPreferences.Editor = getUserPreferences(context).edit()
            editor.putLong(LOCAL_BLOCK_LIST_TIME, time)
            editor.apply()
        }

        fun getLocalBlockListDownloadTime(context: Context) : Long{
            return getUserPreferences(context).getLong(LOCAL_BLOCK_LIST_TIME, 0L)
        }

        fun setRemoteBlockListDownloadTime(context: Context, time: Long) {
            val editor: SharedPreferences.Editor = getUserPreferences(context).edit()
            editor.putLong(REMOTE_BLOCK_LIST_TIME_MS, time)
            editor.apply()
        }

        fun getRemoteBlockListDownloadTime(context: Context): Long {
            return getUserPreferences(context).getLong(REMOTE_BLOCK_LIST_TIME_MS, 0L)
        }

        fun isLocalBlockListEnabled(context: Context): Boolean {
            return getUserPreferences(context).getBoolean(LOCAL_BLOCK_LIST, false)
        }

        fun isBlockListFilesDownloaded(context: Context): Boolean{
            return getUserPreferences(context).getBoolean(DOWNLOAD_BLOCK_LIST_FILES, false)
        }

        fun setBlockListFilesDownloaded(context: Context, isDownloaded: Boolean){
            val editor: SharedPreferences.Editor = getUserPreferences(context).edit()
            editor.putBoolean(DOWNLOAD_BLOCK_LIST_FILES, isDownloaded)
            editor.apply()
        }

        fun getBlockUnknownConnections(context: Context): Boolean{
            return getUserPreferences(context).getBoolean(BLOCK_UNKNOWN_CONNECTIONS, false)
        }

        fun setBlockUnknownConnections(context: Context, isEnabled: Boolean){
            val editor: SharedPreferences.Editor = getUserPreferences(context).edit()
            editor.putBoolean(BLOCK_UNKNOWN_CONNECTIONS, isEnabled)
            editor.apply()
        }

        fun setLocalBlockListStamp(context: Context, stamp: String) {
            if(DEBUG) Log.d(LOG_TAG,"In preference, Set local stamp: $stamp")
            val editor: SharedPreferences.Editor = getUserPreferences(context).edit()
            editor.putString(LOCAL_BLOCK_LIST_STAMP, stamp)
            editor.apply()
        }

        fun getLocalBlockListStamp(context: Context): String? {
            if(DEBUG) Log.d(LOG_TAG,"In preference, Get local stamp: ${getUserPreferences(context).getString(LOCAL_BLOCK_LIST_STAMP, "")}")
            return getUserPreferences(context).getString(LOCAL_BLOCK_LIST_STAMP, "")
        }

        fun isRemoteBraveDNSDownloaded(context: Context) : Boolean{
            return getUserPreferences(context).getBoolean(REMOTE_BLOCK_COMPLETE, false)
        }

        fun setRemoteBraveDNSDownloaded(context: Context, isDownloaded: Boolean){
            val editor: SharedPreferences.Editor = getUserPreferences(context).edit()
            editor.putBoolean(REMOTE_BLOCK_COMPLETE, isDownloaded)
            editor.apply()
        }

        fun isInsertionCompleted(context:Context) : Boolean{
            return getUserPreferences(context).getBoolean(IS_INSERT_COMPLETE, false)
        }

        fun setInsertionCompleted(context: Context, isComplete : Boolean){
            val editor: SharedPreferences.Editor = getUserPreferences(context).edit()
            editor.putBoolean(IS_INSERT_COMPLETE, isComplete)
            editor.apply()
        }

        fun setDNSProxyIDChange(context: Context, id: Int){
            val editor: SharedPreferences.Editor = getUserPreferences(context).edit()
            editor.putInt(DNS_PROXY_ID, id)
            editor.apply()
        }

        fun getDNSProxyIDChange(context: Context): Int {
            return getUserPreferences(context).getInt(DNS_PROXY_ID, 0)
        }

        fun getUDPBlockedSettings(context: Context): Boolean{
            return getUserPreferences(context).getBoolean(BLOCK_UDP_OTHER_THAN_DNS, false)
        }

        fun setUDPBlockedSettings(context: Context, isEnabled: Boolean) {
            val editor: SharedPreferences.Editor = getUserPreferences(context).edit()
            editor.putBoolean(BLOCK_UDP_OTHER_THAN_DNS, isEnabled)
            editor.apply()
        }

        fun getNumberOfLocalBlockLists(context: Context): Int{
            return getUserPreferences(context).getInt(LOCAL_BLOCK_LIST_COUNT, 0)
        }

        fun setNumberOfLocalBlockLists(context: Context, blockCount : Int){
            val editor: SharedPreferences.Editor = getUserPreferences(context).edit()
            editor.putInt(LOCAL_BLOCK_LIST_COUNT, blockCount)
            editor.apply()
        }


        fun getNumberOfRemoteBlockLists(context: Context): Int {
            return getUserPreferences(context).getInt(REMOTE_BLOCK_LIST_COUNT, 0)
        }

        fun setNumberOfRemoteBlockLists(context: Context, blockCount: Int) {
            val editor: SharedPreferences.Editor = getUserPreferences(context).edit()
            editor.putInt(REMOTE_BLOCK_LIST_COUNT, blockCount)
            editor.apply()
        }


        fun setLastAppUpdateCheckTime(context: Context, time : Long){
            val editor: SharedPreferences.Editor = getUserPreferences(context).edit()
            editor.putLong(APP_UPDATE_LAST_CHECK, time)
            editor.apply()
        }

        fun getLastAppUpdateCheckTime(context: Context) : Long{
            return getUserPreferences(context).getLong(APP_UPDATE_LAST_CHECK , 0)
        }

        fun setAppVersion(context: Context, version: Int) {
            val editor: SharedPreferences.Editor = getUserPreferences(context).edit()
            editor.putInt(APP_VERSION, version)
            editor.apply()
        }

        fun getAppVersion(context: Context): Int{
            return getUserPreferences(context).getInt(APP_VERSION , 0)
        }

        fun setDownloadSource(context: Context, source : Int){
            val editor: SharedPreferences.Editor = getUserPreferences(context).edit()
            editor.putInt(APP_DOWNLOAD_SOURCE, source)
            editor.apply()
        }

        fun getDownloadSource(context: Context): Int{
            return getUserPreferences(context).getInt(APP_DOWNLOAD_SOURCE , 0)
        }

    }
}
