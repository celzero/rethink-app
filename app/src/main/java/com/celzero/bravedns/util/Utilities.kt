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


import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.provider.Settings
import android.provider.Settings.ACTION_VPN_SETTINGS
import android.text.TextUtils.SimpleStringSplitter
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.celzero.bravedns.R
import com.celzero.bravedns.net.doh.CountryMap
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants.Companion.ACTION_VPN_SETTINGS_INTENT
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import com.celzero.bravedns.util.Constants.Companion.MISSING_UID
import com.celzero.bravedns.util.Constants.Companion.UNSPECIFIED_IP
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_FIREWALL
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_VPN
import com.google.android.material.snackbar.Snackbar
import com.google.common.net.InetAddresses
import com.google.common.net.InternetDomainName
import java.io.IOException
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.*


class Utilities {

    companion object {

        private const val STORAGE_PERMISSION_CODE = 1008
        private const val READ_PHONE_STATE_REQUEST = 37

        fun checkPermission(activity: AppCompatActivity): Boolean {
            var permissionGranted = false

            if (ContextCompat.checkSelfPermission(activity,
                                                  Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                if (ActivityCompat.shouldShowRequestPermissionRationale(activity,
                                                                        Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                    val rootView: View = (activity).window.decorView.findViewById(
                        android.R.id.content)
                    Snackbar.make(rootView, "Storage permission required",
                                  Snackbar.LENGTH_LONG).setAction("Allow") {
                        ActivityCompat.requestPermissions(activity, arrayOf(
                            Manifest.permission.WRITE_EXTERNAL_STORAGE), STORAGE_PERMISSION_CODE)
                    }.setActionTextColor(Color.WHITE).show()
                } else {
                    ActivityCompat.requestPermissions(activity, arrayOf(
                        Manifest.permission.WRITE_EXTERNAL_STORAGE), STORAGE_PERMISSION_CODE)
                }
            } else {
                permissionGranted = true
            }

            return permissionGranted
        }

        fun getPermissionDetails(activity: Context, packageName: String): PackageInfo {
            val appInstall: PackageInfo
            val p = activity.packageManager
            appInstall = p.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            return appInstall
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
                if (DEBUG) Log.e(LOG_TAG_VPN,
                                 "isAccessibilityServiceEnabled checking for: ${enabledServiceInfo.packageName}")
                if (enabledServiceInfo.packageName == context.packageName && enabledServiceInfo.name == service.name) {
                    return true
                }
            }
            if (DEBUG) Log.e(LOG_TAG_VPN,
                             "isAccessibilityServiceEnabled failure, ${context.packageName},  ${service.name}, return size: ${enabledServices.size}")
            return false
        }

        fun isAccessibilityServiceEnabledEnhanced(context: Context,
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
                    if (enabledService != null && enabledService == expectedComponentName) {
                        if (DEBUG) Log.e(LOG_TAG_VPN,
                                         "isAccessibilityServiceEnabled Enhanced: ${expectedComponentName.packageName}")
                        return true
                    }
                }
                if (DEBUG) Log.e(LOG_TAG_VPN,
                                 "isAccessibilityServiceEnabled Enhanced: Failed to fetch enabledService so invoke isAccessibilityServiceEnabled()")
            } catch (e: Settings.SettingNotFoundException) {
                Log.e(LOG_TAG_VPN,
                      "isAccessibilityServiceEnabled Exception on isAccessibilityServiceEnabledEnhanced() ${e.message}",
                      e)
            }
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
                Log.e("BraveDNS Exception", e.message, e)
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
            if (DEBUG) Log.d(LOG_TAG_VPN, "Servers to remove - $serverList -- $liveServerList")
            var serversToSend: String = ""
            serverList.forEach {
                if (!liveServerList.contains(it)) {
                    serversToSend += "$it,"
                }
            }
            if (DEBUG) Log.d(LOG_TAG_VPN, "Servers to remove - $serversToSend")
            serversToSend = serversToSend.dropLast(1)
            return serversToSend
        }

        fun showToastUiCentered(context: Context?, message: String, toastLength: Int) {
            try {
                val toast = Toast.makeText(context, message, toastLength)
                toast.setGravity(Gravity.CENTER, 0, 0)
                toast.show()
            } catch (e: java.lang.IllegalStateException) {
                Log.w(LOG_TAG_VPN, "Show Toast issue : ${e.message}", e)
            }
        }

        fun isLanIpv4(ipAddress: String): Boolean {
            try {
                // InetAddresses - 'com.google.common.net.InetAddresses' is marked unstable with @Beta
                val ip = InetAddresses.forString(ipAddress)
                return ip.isLoopbackAddress || ip.isSiteLocalAddress || ip.equals(UNSPECIFIED_IP)
            } catch (e: IllegalArgumentException) {
                Log.w(LOG_TAG_VPN,
                      "Exception while converting string to InetAddresses, ${e.message}", e)
            }
            return false
        }

        fun isValidLocalPort(port: Int): Boolean {
            return port in 65535 downTo 1024
        }


        fun getTypeName(type: Int): String {
            // From https://www.iana.org/assignments/dns-parameters/dns-parameters.xhtml#dns-parameters-4
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
                Log.w(LOG_TAG_VPN, "Exception while opening app info: ${e.message}", e)
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

        fun isVpnLockdownEnabled(): Boolean? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                return false
            }
            return VpnController.getInstance().getBraveVpnService()?.isLockdownEnabled
        }

        fun killBg(context: Context, packageInfo: String) {
            val activityManager: ActivityManager = context.getSystemService(
                Activity.ACTIVITY_SERVICE) as ActivityManager
            try {
                activityManager.killBackgroundProcesses(packageInfo)
            } catch (e: Exception) {
                Log.w(LOG_TAG_FIREWALL, "firewall - kill app - exception" + e.message, e)
            }
        }
    }
}
