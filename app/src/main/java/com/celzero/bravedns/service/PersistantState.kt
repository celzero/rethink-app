package com.celzero.bravedns.service

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.preference.PreferenceManager
import android.util.Log
import com.celzero.bravedns.R


class PersistantState {

    companion object {

        private val LOG_TAG = "PersistentState"

        val APPS_KEY = "pref_apps"
        val URL_KEY = "pref_server_url"
        val DNS_MODE = "dns_mode"
        val FIREWALL_MODE = "firewall_mode"

        private val APPROVED_KEY = "approved"
        private val ENABLED_KEY = "enabled"
        private val SERVER_KEY = "server"

        private val INTERNAL_STATE_NAME = "MainActivity"

        // The approval state is currently stored in a separate preferences file.
        // TODO: Unify preferences into a single file.
        private val APPROVAL_PREFS_NAME = "IntroState"


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
                context.resources.getString(R.string.url0)
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
                Log.i(LOG_TAG,"Legacy domain is unrecognized")

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
                ?: return context.resources.getString(R.string.url0)
            return strip(urlTemplate)
        }


        fun getExcludedPackages(context: Context?): Set<String?>? {
            return getUserPreferences(context!!)?.getStringSet(
               APPS_KEY,
                HashSet<String>()
            )
        }

        fun setExcludedPackages(packages : Set<String?>, context : Context?){

            //TODO : hardcode value for testing. Need to modify
            var sets: MutableSet<String> = HashSet()
            sets.add("in.swiggy.android")
            getUserPreferences(context!!)!!.edit().putStringSet(APPS_KEY, sets).apply()

            //sets = getUserPreferences(context!!)!!.getStringSet(APPS_KEY, HashSet<String>())!!
            var newSet: MutableSet<String> = HashSet(getUserPreferences(context!!)!!.getStringSet(APPS_KEY, HashSet<String>()))
            newSet.add("org.mozilla.firefox");
            getUserPreferences(context!!)!!.edit().putStringSet(APPS_KEY,newSet).apply()

/*

            val editor: SharedPreferences.Editor =
                getUserPreferences(context!!)!!.edit()
            editor.putStringSet(APPS_KEY, packages)
            editor.apply()*/
        }

        fun strip(template: String): String? {
            return template.replace("\\{[^}]*\\}".toRegex(), "")
        }


        fun setDnsMode(context: Context, mode : Int){
            val editor: SharedPreferences.Editor =
                getUserPreferences(context!!)!!.edit()
            editor.putInt(DNS_MODE, mode)
            editor.apply()
        }

        fun getDnsMode(context: Context):Int {
            return getUserPreferences(context!!)!!.getInt(DNS_MODE, 2)
        }

        fun setFirewallMode(context: Context, mode : Int){
            val editor: SharedPreferences.Editor =
                getUserPreferences(context!!)!!.edit()
            editor.putInt(FIREWALL_MODE, mode)
            editor.apply()
        }

        fun getFirewallMode(context: Context):Int {
            return getUserPreferences(context!!)!!.getInt(FIREWALL_MODE, 0)
        }

    }
}
