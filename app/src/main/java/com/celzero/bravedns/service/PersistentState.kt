package com.celzero.bravedns.service

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.preference.PreferenceManager
import android.util.Log
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.HomeScreenActivity
//
import settings.Settings


class PersistentState {

    companion object {

        //private const val LOG_TAG = "PersistentState"

        private const val APPS_KEY_DATA = "pref_apps_data"
        private const val APPS_KEY_WIFI = "pref_apps_wifi"
        private const val CATEGORY_DATA = "blocked_categories"
        const val URL_KEY = "pref_server_url"
        private const val DNS_MODE = "dns_mode"
        private const val FIREWALL_MODE = "firewall_mode"
        const val BRAVE_MODE = "brave_mode"
        private const val BACKGROUND_MODE = "background_mode"
        private const val SCREEN_STATE = "screen_state"

        private const val APPROVED_KEY = "approved"
        private const val ENABLED_KEY = "enabled"
        private const val SERVER_KEY = "server"

        private const val MEDIAN_90 = "median_p90"
        private const val NUMBER_REQUEST = "number_request"
        private const val BLOCKED_COUNT = "blocked_request"

        private const val INTERNAL_STATE_NAME = "MainActivity"

        // The approval state is currently stored in a separate preferences file.
        // TODO: Unify preferences into a single file.
        private const val APPROVAL_PREFS_NAME = "IntroState"


        private fun getInternalState(context: Context): SharedPreferences {
            return context.getSharedPreferences(
                INTERNAL_STATE_NAME,
                Context.MODE_PRIVATE
            )
        }

        private fun getUserPreferences(context: Context): SharedPreferences? {
            return PreferenceManager.getDefaultSharedPreferences(context)
        }

        fun getVpnEnabled(context: Context): Boolean {
            return getInternalState(context).getBoolean(
                ENABLED_KEY,
                false
            )
        }

        fun expandUrl(context: Context, url: String?): String {
            return if (url == null || url.isEmpty()) {
                context.resources.getString(R.string.default_dummy_url)
            } else url
        }

        fun setVpnEnabled(context: Context, enabled: Boolean) {
            val editor = getInternalState(context).edit()
            editor.putBoolean(ENABLED_KEY, enabled)
            editor.apply()
        }


        fun syncLegacyState(context: Context) {
            // Copy the domain choice into the new URL setting, if necessary.
            if (getServerUrl(context) != null) {
                // New server URL is already populated
                return
            }

            // There is no URL setting, so read the legacy server name.
            val settings: SharedPreferences = getInternalState(context)
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
        }

        fun setServerUrl(context: Context?, url: String?) {
            val editor: SharedPreferences.Editor =
                getUserPreferences(context!!)!!.edit()
            editor.putString(URL_KEY, url)
            editor.apply()
        }

        fun getServerUrl(context: Context?): String? {
            val urlTemplate: String = getUserPreferences(context!!)!!.getString(URL_KEY, null)
                //?: return context.resources.getString(R.string.url0)
                ?: return context.resources.getString(R.string.default_dummy_url)
            return strip(urlTemplate)
        }


        fun getExcludedPackagesWifi(context: Context?): Set<String?>? {
            return getUserPreferences(context!!)?.getStringSet(
               APPS_KEY_WIFI,
                HashSet<String>()
            )
        }

        fun setExcludedPackagesWifi(packageName : String, toRemove : Boolean , context : Context?){

            //TODO : hardcode value for testing. Need to modify

            //sets = getUserPreferences(context!!)!!.getStringSet(APPS_KEY, HashSet<String>())!!
            val newSet: MutableSet<String> = HashSet(getUserPreferences(context!!)!!.getStringSet(APPS_KEY_WIFI, HashSet<String>()))
            if(toRemove) newSet.removeIf{newSet.contains(packageName)}
            else newSet.add(packageName)
            getUserPreferences(context)!!.edit().putStringSet(APPS_KEY_WIFI,newSet).apply()

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
            if(toRemove)    newSet.removeIf{newSet.contains(packageName)}
            else  newSet.add(packageName)
            getUserPreferences(context)!!.edit().putStringSet(APPS_KEY_DATA,newSet).apply()



/*

            val editor: SharedPreferences.Editor =
                getUserPreferences(context!!)!!.edit()
            editor.putStringSet(APPS_KEY, packages)
            editor.apply()*/
        }

        private fun strip(template: String): String? {
            return template.replace("\\{[^}]*\\}".toRegex(), "")
        }


        fun setBraveMode(context: Context , mode: Int){

            val editor: SharedPreferences.Editor =
                getUserPreferences(context)!!.edit()
            editor.putInt(BRAVE_MODE, mode)
            editor.apply()
        }

        fun getBraveMode(context: Context) : Int{
            return getUserPreferences(context)!!.getInt(BRAVE_MODE, 0)
        }

        fun setDnsMode(context: Context, mode : Int){
            val editor: SharedPreferences.Editor =
                getUserPreferences(context)!!.edit()
            editor.putInt(DNS_MODE, mode)
            editor.apply()
        }

        //TODO : Modify the hardcoded value
        fun getDnsMode(context: Context):Int {
            //return Settings.DNSModePort.toInt()
            return getUserPreferences(context)!!.getInt(DNS_MODE, 1)
        }

        fun setFirewallMode(context: Context, fwMode : Int){
            val editor: SharedPreferences.Editor =
                getUserPreferences(context)!!.edit()
            editor.putInt(FIREWALL_MODE, fwMode)
            editor.apply()
           // Log.d("Temp","setFirewallMode - $fwMode")
        }

        //TODO : Modify the hardcoded value
        fun getFirewallMode(context: Context):Int {
            //return Settings.BlockModeFilter.toInt()
            return getUserPreferences(context)!!.getInt(FIREWALL_MODE, 1)
        }


        fun setFirewallModeForScreenState(context : Context , state : Boolean) {
            val editor: SharedPreferences.Editor =
                getUserPreferences(context)!!.edit()
            editor.putBoolean(SCREEN_STATE, state)
            editor.apply()
        }


        fun getFirewallModeForScreenState(context : Context) : Boolean{
            return getUserPreferences(context)?.getBoolean(SCREEN_STATE,false)!!
        }


        fun getCategoriesBlocked(context: Context?): Set<String?>? {
            return getUserPreferences(context!!)?.getStringSet(
                CATEGORY_DATA,
                HashSet<String>()
            )
        }

        fun setCategoriesBlocked(categoryName : String, toRemove : Boolean , context : Context?){

            //TODO : hardcode value for testing. Need to modify
            //sets = getUserPreferences(context!!)!!.getStringSet(APPS_KEY, HashSet<String>())!!
            val newSet: MutableSet<String> = HashSet(getUserPreferences(context!!)!!.getStringSet(CATEGORY_DATA, HashSet<String>()))
            //Log.d("BraveDNS" ,"isCategoryBlocked : "+categoryName +"toRemove : "+ toRemove + "-- Contains : "+newSet.contains(categoryName))
            if(toRemove) newSet.removeIf{newSet.contains(categoryName)}
            else newSet.add(categoryName)

            getUserPreferences(context)!!.edit().putStringSet(CATEGORY_DATA,newSet).apply()

        }

        fun isCategoryBlocked(categoryName: String, context: Context) : Boolean{
            return getUserPreferences(context)?.getStringSet(CATEGORY_DATA,HashSet<String>())!!.contains(categoryName)
        }

        fun setMedianLatency(context: Context, medianP90 : Long){
            HomeScreenActivity.GlobalVariable.lifeTimeQueries = medianP90.toInt()
            val editor: SharedPreferences.Editor = getUserPreferences(context)!!.edit()
            editor.putLong(MEDIAN_90, medianP90)
            editor.apply()
        }

        fun getMedianLatency(context: Context) : Long{
            return getUserPreferences(context)?.getLong(MEDIAN_90,0L)!!
        }

        fun setNumOfReq(context : Context){
            val numReq =  getUserPreferences(context)?.getInt(NUMBER_REQUEST,0)!!
            val editor: SharedPreferences.Editor =
                getUserPreferences(context)!!.edit()
            editor.putInt(NUMBER_REQUEST, numReq+1)
            editor.apply()
        }

        fun getNumOfReq(context: Context) : Int{
            return getUserPreferences(context)?.getInt(NUMBER_REQUEST,0)!!
        }

        fun setBlockedReq(context : Context){
            val numReq =  getUserPreferences(context)?.getInt(BLOCKED_COUNT,0)!!
            val editor: SharedPreferences.Editor =
                getUserPreferences(context)!!.edit()
            editor.putInt(BLOCKED_COUNT, numReq+1)
            editor.apply()
        }

        fun getBlockedReq(context: Context) : Int{
            return getUserPreferences(context)?.getInt(BLOCKED_COUNT,0)!!
        }

        fun getBackgroundEnabled(context: Context) : Boolean{
            return getUserPreferences(context)?.getBoolean(BACKGROUND_MODE,false)!!
        }

        fun setBackgroundEnabled(context: Context, isEnabled : Boolean){
            val editor: SharedPreferences.Editor =
                getUserPreferences(context)!!.edit()
            editor.putBoolean(BACKGROUND_MODE, isEnabled)
            editor.apply()
        }
    }
}
