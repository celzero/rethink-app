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
package com.celzero.bravedns.util


import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.content.*
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.CountDownTimer
import android.provider.Settings
import android.provider.Settings.ACTION_VPN_SETTINGS
import android.text.Html
import android.text.Spanned
import android.text.TextUtils
import android.text.TextUtils.SimpleStringSplitter
import android.util.Log
import android.view.Gravity
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.getSystemService
import androidx.core.text.HtmlCompat
import com.celzero.bravedns.R
import com.celzero.bravedns.net.doh.CountryMap
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.VpnControllerHelper.persistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants.Companion.ACTION_VPN_SETTINGS_INTENT
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import com.celzero.bravedns.util.Constants.Companion.MISSING_UID
import com.celzero.bravedns.util.Constants.Companion.UNSPECIFIED_IP
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_APP_DB
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_DOWNLOAD
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_FIREWALL
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_VPN
import com.google.common.net.InetAddresses
import com.google.common.net.InternetDomainName
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.*


class Utilities {

    companion object {

        fun getPermissionDetails(context: Context, packageName: String): PackageInfo {
            return context.packageManager.getPackageInfo(packageName,
                                                         PackageManager.GET_PERMISSIONS)
        }

        // Convert an FQDN like "www.example.co.uk." to an eTLD + 1 like "example.co.uk".
        fun getETldPlus1(fqdn: String): String? {
            return try {
                val name: InternetDomainName = InternetDomainName.from(fqdn)
                try {
                    name.topPrivateDomain().toString()
                } catch (e: IllegalStateException) {
                    // The name doesn't end in a recognized TLD.  This can happen for randomly generated
                    // names, or when new TLDs are introduced.
                    val parts: List<String> = name.parts()
                    val size = parts.size
                    if (size >= 2) {
                        parts[size - 2] + "." + parts[size - 1]
                    } else if (size == 1) {
                        parts[0]
                    } else {
                        // Empty input?
                        fqdn
                    }
                }
            } catch (e: IllegalArgumentException) {
                // If fqdn is not a valid domain name, InternetDomainName.from() will throw an
                // exception.  Since this function is only for aesthetic purposes, we can
                // return the input unmodified in this case.
                fqdn
            }
        }

        fun isAccessibilityServiceEnabled(context: Context,
                                          service: Class<out AccessibilityService?>): Boolean {
            val am = context.getSystemService<AccessibilityManager>() ?: return false
            val enabledServices = am.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            for (enabledService in enabledServices) {
                val enabledServiceInfo: ServiceInfo = enabledService.resolveInfo.serviceInfo
                if (DEBUG) Log.i(LOG_TAG_VPN,
                                 "Accessibility enabled check for: ${enabledServiceInfo.packageName}")
                if (enabledServiceInfo.packageName == context.packageName && enabledServiceInfo.name == service.name) {
                    return true
                }
            }
            if (DEBUG) Log.e(LOG_TAG_VPN,
                             "Accessibility failure, ${context.packageName},  ${service.name}, return size: ${enabledServices.size}")
            return false
        }

        fun isAccessibilityServiceEnabledViaSettingsSecure(context: Context,
                                                           accessibilityService: Class<out AccessibilityService?>): Boolean {
            try {
                val expectedComponentName = ComponentName(context, accessibilityService)
                val enabledServicesSetting: String = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
                val colonSplitter = SimpleStringSplitter(':')
                colonSplitter.setString(enabledServicesSetting)
                while (colonSplitter.hasNext()) {
                    val componentNameString = colonSplitter.next()
                    val enabledService = ComponentName.unflattenFromString(componentNameString)
                    if (expectedComponentName.equals(enabledService)) {
                        if (DEBUG) Log.i(LOG_TAG_VPN,
                                         "SettingsSecure accessibility enabled for: ${expectedComponentName.packageName}")
                        return true
                    }
                }
            } catch (e: Settings.SettingNotFoundException) {
                Log.e(LOG_TAG_VPN,
                      "isAccessibilityServiceEnabled Exception on isAccessibilityServiceEnabledViaSettingsSecure() ${e.message}",
                      e)
            }
            if (DEBUG) Log.d(LOG_TAG_VPN, "Accessibility enabled check failed")
            return isAccessibilityServiceEnabled(context, accessibilityService)
        }

        private var countryMap: CountryMap? = null

        // Return a two-letter ISO country code, or null if that fails.
        fun getCountryCode(address: InetAddress?, context: Context): String {
            activateCountryMap(context)
            return (if (countryMap == null) {
                null
            } else {
                countryMap?.getCountryCode(address)
            })!!
        }

        private fun activateCountryMap(context: Context) {
            if (countryMap != null) {
                return
            }
            try {
                countryMap = CountryMap(context.getAssets())
            } catch (e: IOException) {
                Log.e(LOG_TAG_VPN, "Failure fetching country map ${e.message}", e)
            }
        }

        fun getFlag(countryCode: String?): String {
            if (countryCode == null) {
                return ""
            }
            // Flag emoji consist of two "regional indicator symbol letters", which are
            // Unicode characters that correspond to the English alphabet and are arranged in the same
            // order.  Therefore, to convert from a country code to a flag, we simply need to apply an
            // offset to each character, shifting it from the normal A-Z range into the region indicator
            // symbol letter range.
            val alphaBase = 'A'.toInt() // Start of alphabetic country code characters.
            val flagBase = 0x1F1E6 // Start of regional indicator symbol letters.
            val offset = flagBase - alphaBase
            val firstHalf = Character.codePointAt(countryCode, 0) + offset
            val secondHalf = Character.codePointAt(countryCode, 1) + offset
            return String(Character.toChars(firstHalf)) + String(Character.toChars(secondHalf))
        }

        fun makeAddressPair(countryCode: String?, ipAddress: String): String {
            return if (countryCode == null) {
                ipAddress
            } else String.format("%s (%s)", countryCode, ipAddress)
        }

        fun convertLongToTime(time: Long): String {
            val date = Date(time)
            val format = SimpleDateFormat("HH:mm:ss", Locale.US)
            return format.format(date)
        }

        fun convertLongToDate(timestamp: Long): String {
            val date = Date(timestamp)
            val format = SimpleDateFormat("yy.MM (dd)", Locale.US)
            return format.format(date)
        }

        fun prepareServersToRemove(servers: String, liveServers: String): String {
            val serverList = servers.split(",")
            val liveServerList = liveServers.split(",")
            var serversToSend = ""
            serverList.forEach {
                if (!liveServerList.contains(it)) {
                    serversToSend += "$it,"
                }
            }
            if (DEBUG) Log.d(LOG_TAG_VPN, "In: $serverList / Out: $serversToSend")
            serversToSend = serversToSend.dropLast(1)
            return serversToSend
        }

        fun showToastUiCentered(context: Context, message: String, toastLength: Int) {
            try {
                val toast = Toast.makeText(context, message, toastLength)
                toast.setGravity(Gravity.CENTER, 0, 0)
                toast.show()
            } catch (e: IllegalStateException) {
                Log.w(LOG_TAG_VPN, "Show Toast issue : ${e.message}", e)
            }
        }

        fun isLanIpv4(ipAddress: String): Boolean {
            try {
                // InetAddresses - 'com.google.common.net.InetAddresses' is marked unstable with @Beta
                val ip = InetAddresses.forString(ipAddress)
                return ip.isLoopbackAddress || ip.isSiteLocalAddress || ip.isAnyLocalAddress || UNSPECIFIED_IP.equals(
                    ip)
            } catch (e: IllegalArgumentException) {
                Log.w(LOG_TAG_VPN, "Failed parsing IP from $ipAddress with ${e.message}", e)
            }
            return false
        }

        fun isValidLocalPort(port: Int): Boolean {
            return port in 65535 downTo 1024
        }

        fun isValidPort(port: Int): Boolean {
            return port in 65535 downTo 0
        }


        fun getTypeName(type: Int): String {
            // From https://www.iana.org/assignments/dns-parameters/dns-parameters.xhtml#dns-parameters-4
            // additional ref: https://www.netmeister.org/blog/dns-rrs.html
            val names = arrayOf("0", "A", "NS", "MD", "MF", "CNAME", "SOA", "MB", "MG", "MR",
                                "NULL", "WKS", "PTR", "HINFO", "MINFO", "MX", "TXT", "RP", "AFSDB",
                                "X25", "ISDN", "RT", "NSAP", "NSAP+PTR", "SIG", "KEY", "PX", "GPOS",
                                "AAAA", "LOC", "NXT", "EID", "NIMLOC", "SRV", "ATMA", "NAPTR", "KX",
                                "CERT", "A6", "DNAME", "SINK", "OPT", "APL", "DS", "SSHFP",
                                "IPSECKEY", "RRSIG", "NSEC", "DNSKEY", "DHCID", "NSEC3",
                                "NSEC3PARAM", "TLSA", "SMIMEA")
            return if (type < names.size) {
                names[type]
            } else String.format(Locale.ROOT, "%d", type)
        }

        fun getThemeAccent(context: Context): Int {
            return if (isDarkSystemTheme(context)) {
                R.color.accentGoodBlack
            } else {
                R.color.negative_white
            }
        }

        private fun isDarkSystemTheme(context: Context): Boolean {
            return context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }

        fun openVpnProfile(context: Context) {
            try {
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    Intent(ACTION_VPN_SETTINGS)
                } else {
                    Intent(ACTION_VPN_SETTINGS_INTENT)
                }
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                showToastUiCentered(context, context.getString(R.string.vpn_profile_error),
                                    Toast.LENGTH_SHORT)
                Log.w(LOG_TAG_VPN, "Failure opening app info: ${e.message}", e)
            }
        }

        fun isValidUid(uid: Int): Boolean {
            return AndroidUidConfig.isValidUid(uid)
        }

        fun isInvalidUid(uid: Int): Boolean {
            return when (uid) {
                MISSING_UID -> true
                INVALID_UID -> true
                else -> false
            }
        }

        fun isVpnLockdownEnabled(vpnService: BraveVPNService?): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                return false
            }
            return vpnService?.isLockdownEnabled == true
        }

        fun killBg(activityManager: ActivityManager, packageName: String) {
            try {
                activityManager.killBackgroundProcesses(packageName)
            } catch (e: Exception) {
                Log.w(LOG_TAG_FIREWALL, "firewall - kill app - exception" + e.message, e)
            }
        }

        fun getCurrentTheme(isDarkThemeOn: Boolean, theme: Int): Int {
            return if (theme == Constants.THEME_SYSTEM_DEFAULT) {
                if (isDarkThemeOn) {
                    R.style.AppThemeTrueBlack
                } else {
                    R.style.AppThemeWhite
                }
            } else if (theme == Constants.THEME_LIGHT) {
                R.style.AppThemeWhite
            } else if (theme == Constants.THEME_DARK) {
                R.style.AppTheme
            } else {
                R.style.AppThemeTrueBlack
            }
        }

        fun getBottomsheetCurrentTheme(isDarkThemeOn: Boolean): Int {
            return if (persistentState.theme == Constants.THEME_SYSTEM_DEFAULT) {
                if (isDarkThemeOn) {
                    R.style.BottomSheetDialogThemeTrueBlack
                } else {
                    R.style.BottomSheetDialogThemeWhite
                }
            } else if (persistentState.theme == Constants.THEME_LIGHT) {
                R.style.BottomSheetDialogThemeWhite
            } else if (persistentState.theme == Constants.THEME_DARK) {
                R.style.BottomSheetDialogTheme
            } else {
                R.style.BottomSheetDialogThemeTrueBlack
            }
        }

        fun getPackageMetadata(pm: PackageManager, pi: String): PackageInfo? {
            var metadata: PackageInfo? = null

            try {
                metadata = pm.getPackageInfo(pi, PackageManager.GET_META_DATA)
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(LOG_TAG_APP_DB, "Application not available $pi" + e.message, e)
            }
            return metadata
        }

        fun copy(from: String, to: String): Boolean {
            try {
                val src = File(from)
                val dest = File(to)

                if (!src.isFile) return false

                src.copyTo(dest, true)
            } catch (e: NoSuchFileException) { // Throws NoSuchFileException, IOException
                Log.e(LOG_TAG_DOWNLOAD, "Error copying file ${e.message}", e)
                return false
            } catch (e: IOException) {
                Log.e(LOG_TAG_DOWNLOAD, "Error copying file ${e.message}", e)
                return false
            }

            return true
        }


        fun isAlwaysOnEnabled(context: Context, vpnService: BraveVPNService?): Boolean {
            // Introduced as part of issue fix #325
            // From android version 12+(R) Settings keys annotated with @hide are restricted to
            // system_server and system apps only. "always_on_vpn_app" is annotated with @hide.

            // For versions above 29(Q), there is vpnService.isAlwaysOn property to check
            // whether always-on is enabled.
            // For versions prior to 29 the check is made with Settings.Secure.
            // In our case, the always-on check is for all the vpn profiles. So using
            // vpnService?.isAlwaysOn will not be much helpful
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return vpnService?.isAlwaysOn == true
            }

            val alwaysOn = Settings.Secure.getString(context.contentResolver, "always_on_vpn_app")
            return context.packageName == alwaysOn
        }

        // This function is not supported from version 12 onwards.
        fun isOtherVpnHasAlwaysOn(context: Context): Boolean {
            return try {
                val alwaysOn = Settings.Secure.getString(context.contentResolver,
                                                         "always_on_vpn_app")
                !TextUtils.isEmpty(alwaysOn) && context.packageName != alwaysOn
            } catch (e: Exception) {
                Log.e(LOG_TAG_VPN, "Failure while retrieving Settings.Secure value ${e.message}", e)
                false
            }
        }

        fun getIcon(context: Context, packageName: String, appName: String): Drawable? {
            if (!isValidAppName(appName, packageName)) {
                return getDefaultIcon(context)
            }

            return try {
                context.packageManager.getApplicationIcon(packageName)
            } catch (e: PackageManager.NameNotFoundException) {
                Log.e(LOG_TAG_FIREWALL,
                      "Application Icon not available for package: $packageName" + e.message, e)
                getDefaultIcon(context)
            }
        }

        private fun isValidAppName(appName: String, packageName: String): Boolean {
            return !packageName.contains(Constants.NO_PACKAGE) && appName != Constants.UNKNOWN_APP
        }

        fun isValidAppName(appName: String?): Boolean {
            return appName != Constants.UNKNOWN_APP
        }

        fun getDefaultIcon(context: Context): Drawable? {
            return AppCompatResources.getDrawable(context, R.drawable.default_app_icon)
        }

        fun clipboardCopy(context: Context, s: String, label: String) {
            val clipboard: ClipboardManager? = context.getSystemService()
            val clip = ClipData.newPlainText(label, s)
            clipboard?.setPrimaryClip(clip)
        }

        fun updateHtmlEncodedText(text: String): Spanned {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY)
            } else {
                HtmlCompat.fromHtml(text, HtmlCompat.FROM_HTML_MODE_LEGACY)
            }
        }

        fun delay(ms: Long, updateUi: () -> Unit) {
            object : CountDownTimer(ms, ms) {
                override fun onTick(millisUntilFinished: Long) {}

                override fun onFinish() {
                    updateUi()
                }
            }.start()
        }
    }
}
