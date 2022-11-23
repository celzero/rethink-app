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
package com.celzero.bravedns.util

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.PendingIntent
import android.content.*
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.content.res.TypedArray
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.provider.Settings.ACTION_VPN_SETTINGS
import android.text.Html
import android.text.Spanned
import android.text.TextUtils
import android.text.TextUtils.SimpleStringSplitter
import android.text.format.DateUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.getSystemService
import androidx.core.text.HtmlCompat
import androidx.lifecycle.LifecycleCoroutineScope
import com.celzero.bravedns.BuildConfig
import com.celzero.bravedns.R
import com.celzero.bravedns.database.AppInfoRepository.Companion.NO_PACKAGE
import com.celzero.bravedns.net.doh.CountryMap
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.ui.PauseActivity
import com.celzero.bravedns.util.Constants.Companion.ACTION_VPN_SETTINGS_INTENT
import com.celzero.bravedns.util.Constants.Companion.FLAVOR_FDROID
import com.celzero.bravedns.util.Constants.Companion.FLAVOR_PLAY
import com.celzero.bravedns.util.Constants.Companion.FLAVOR_WEBSITE
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import com.celzero.bravedns.util.Constants.Companion.LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME
import com.celzero.bravedns.util.Constants.Companion.MISSING_UID
import com.celzero.bravedns.util.Constants.Companion.REMOTE_BLOCKLIST_DOWNLOAD_FOLDER_NAME
import com.celzero.bravedns.util.Constants.Companion.TIME_FORMAT_1
import com.celzero.bravedns.util.Constants.Companion.UNSPECIFIED_IP_IPV4
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_APP_DB
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_DNS
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_DOWNLOAD
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_FIREWALL
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_VPN
import com.google.common.base.CharMatcher
import com.google.common.net.InternetDomainName
import inet.ipaddr.IPAddressString
import kotlinx.coroutines.launch
import xdns.Xdns
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.*
import java.util.Calendar.DAY_OF_YEAR

class Utilities {

    companion object {

        // Convert an FQDN like "www.example.co.uk." to an eTLD + 1 like "example.co.uk".
        fun getETldPlus1(fqdn: String): String? {
            return try {
                val name: InternetDomainName = InternetDomainName.from(fqdn)
                try {
                    name.topPrivateDomain().toString()
                } catch (e: IllegalStateException) {
                    // The name doesn't end in a recognized TLD.  This can happen for randomly
                    // generated
                    // names, or when new TLDs are introduced.
                    val parts: List<String> = name.parts()
                    val size = parts.count()
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

        fun isAccessibilityServiceEnabled(
            context: Context,
            service: Class<out AccessibilityService?>
        ): Boolean {
            val am = context.getSystemService<AccessibilityManager>() ?: return false
            val enabledServices =
                am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            for (enabledService in enabledServices) {
                val enabledServiceInfo: ServiceInfo = enabledService.resolveInfo.serviceInfo
                if (DEBUG)
                    Log.i(
                        LOG_TAG_VPN,
                        "Accessibility enabled check for: ${enabledServiceInfo.packageName}"
                    )
                if (
                    enabledServiceInfo.packageName == context.packageName &&
                        enabledServiceInfo.name == service.name
                ) {
                    return true
                }
            }
            if (DEBUG)
                Log.e(
                    LOG_TAG_VPN,
                    "Accessibility failure, ${context.packageName},  ${service.name}, return size: ${enabledServices.count()}"
                )
            return false
        }

        fun isAccessibilityServiceEnabledViaSettingsSecure(
            context: Context,
            accessibilityService: Class<out AccessibilityService?>
        ): Boolean {
            try {
                val expectedComponentName = ComponentName(context, accessibilityService)
                val enabledServicesSetting: String =
                    Settings.Secure.getString(
                        context.contentResolver,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                    )
                        ?: return false
                val colonSplitter = SimpleStringSplitter(':')
                colonSplitter.setString(enabledServicesSetting)
                while (colonSplitter.hasNext()) {
                    val componentNameString = colonSplitter.next()
                    val enabledService = ComponentName.unflattenFromString(componentNameString)
                    if (expectedComponentName == enabledService) {
                        if (DEBUG)
                            Log.i(
                                LOG_TAG_VPN,
                                "SettingsSecure accessibility enabled for: ${expectedComponentName.packageName}"
                            )
                        return true
                    }
                }
            } catch (e: Settings.SettingNotFoundException) {
                Log.e(
                    LOG_TAG_VPN,
                    "isAccessibilityServiceEnabled Exception on isAccessibilityServiceEnabledViaSettingsSecure() ${e.message}",
                    e
                )
            }
            if (DEBUG) Log.d(LOG_TAG_VPN, "Accessibility enabled check failed")
            return isAccessibilityServiceEnabled(context, accessibilityService)
        }

        private var countryMap: CountryMap? = null

        // Return a two-letter ISO country code, or null if that fails.
        fun getCountryCode(address: InetAddress?, context: Context): String? {
            initCountryMapIfNeeded(context)
            return (if (countryMap == null) {
                null
            } else {
                countryMap?.getCountryCode(address)
            })
        }

        private fun initCountryMapIfNeeded(context: Context) {
            if (countryMap != null) {
                return
            }

            try {
                countryMap = CountryMap(context.assets)
            } catch (e: IOException) {
                Log.e(LOG_TAG_VPN, "Failure fetching country map ${e.message}", e)
            }
        }

        fun getFlag(countryCode: String?): String {
            if (countryCode == null) {
                return ""
            }
            // Flag emoji consist of two "regional indicator symbol letters", which are
            // Unicode characters that correspond to the English alphabet and are arranged in the
            // same
            // order.  Therefore, to convert from a country code to a flag, we simply need to apply
            // an
            // offset to each character, shifting it from the normal A-Z range into the region
            // indicator
            // symbol letter range.
            val alphaBase = 'A'.code // Start of alphabetic country code characters.
            val flagBase = 0x1F1E6 // Start of regional indicator symbol letters.
            val offset = flagBase - alphaBase
            val firstHalf = Character.codePointAt(countryCode, 0) + offset
            val secondHalf = Character.codePointAt(countryCode, 1) + offset
            return String(Character.toChars(firstHalf)) + String(Character.toChars(secondHalf))
        }

        fun makeAddressPair(countryCode: String?, ipAddress: String?): String {
            return if (ipAddress == null) {
                ""
            } else if (countryCode == null) {
                ipAddress
            } else {
                String.format("%s (%s)", countryCode, ipAddress)
            }
        }

        fun convertLongToTime(time: Long, template: String): String {
            val date = Date(time)
            return SimpleDateFormat(template, Locale.ENGLISH).format(date)
        }

        fun humanReadableTime(timestamp: Long): String {
            val offSet = TimeZone.getDefault().rawOffset + TimeZone.getDefault().dstSavings
            val now = timestamp - offSet
            return convertLongToTime(now, TIME_FORMAT_1)
        }

        fun formatToRelativeTime(context: Context, timestamp: Long): String {
            val now = System.currentTimeMillis()
            return if (DateUtils.isToday(timestamp)) {
                context.getString(R.string.relative_time_today)
            } else if (isYesterday(Date(timestamp))) {
                context.getString(R.string.relative_time_yesterday)
            } else {
                val d =
                    DateUtils.getRelativeTimeSpanString(
                        timestamp,
                        now,
                        DateUtils.MINUTE_IN_MILLIS,
                        DateUtils.FORMAT_ABBREV_RELATIVE
                    )
                d.toString()
            }
        }

        // ref: https://stackoverflow.com/a/3006423
        private fun isYesterday(day: Date): Boolean {
            val c1 = Calendar.getInstance()
            c1.add(DAY_OF_YEAR, -1)
            val c2 = Calendar.getInstance()
            c2.time = day
            if (
                c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) &&
                    c1.get(DAY_OF_YEAR) == c2.get(DAY_OF_YEAR)
            ) {
                return true
            }

            return false
        }

        fun getNonLiveDnscryptServers(servers: String, liveServers: String): String {
            val serverList = servers.split(",")
            val liveServerList = liveServers.split(",")
            var serversToSend = ""

            serverList.forEach {
                if (!liveServerList.contains(it)) {
                    serversToSend += "${it.trim()},"
                }
            }
            if (DEBUG) Log.d(LOG_TAG_VPN, "In: $serverList / Out: $serversToSend")
            return serversToSend.dropLast(1)
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
            val ip = IPAddressString(ipAddress).address ?: return false

            return ip.isLoopback || ip.isLocal || ip.isAnyLocal || UNSPECIFIED_IP_IPV4.equals(ip)
        }

        fun isValidLocalPort(port: Int): Boolean {
            return isValidPort(port)
        }

        fun isValidPort(port: Int): Boolean {
            return port in 65535 downTo 0
        }

        fun getThemeAccent(context: Context): Int {
            return if (isDarkSystemTheme(context)) {
                R.color.accentGoodBlack
            } else {
                R.color.accentBadLight
            }
        }

        private fun isDarkSystemTheme(context: Context): Boolean {
            return context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        }

        fun openVpnProfile(context: Context) {
            try {
                val intent =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        Intent(ACTION_VPN_SETTINGS)
                    } else {
                        Intent(ACTION_VPN_SETTINGS_INTENT)
                    }
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                showToastUiCentered(
                    context,
                    context.getString(R.string.vpn_profile_error),
                    Toast.LENGTH_SHORT
                )
                Log.w(LOG_TAG_VPN, "Failure opening app info: ${e.message}", e)
            }
        }

        fun isMissingOrInvalidUid(uid: Int): Boolean {
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
            } catch (e: Exception) { // Throws NoSuchFileException, IOException
                Log.e(LOG_TAG_DOWNLOAD, "Error copying file ${e.message}", e)
                return false
            }

            return true
        }

        // ref: https://stackoverflow.com/a/41818556
        fun copyWithStream(readStream: InputStream, writeStream: OutputStream): Boolean {
            val length = 256
            val buffer = ByteArray(length)
            return try {
                var bytesRead: Int = readStream.read(buffer, 0, length)
                // write the required bytes
                while (bytesRead > 0) {
                    writeStream.write(buffer, 0, bytesRead)
                    bytesRead = readStream.read(buffer, 0, length)
                }
                readStream.close()
                writeStream.close()
                true
            } catch (e: Exception) {
                Log.w(LOG_TAG_DOWNLOAD, "Issue while copying files using streams: ${e.message}, $e")
                false
            }
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
            if (isAtleastQ()) {
                return vpnService?.isAlwaysOn == true
            }

            val alwaysOn = Settings.Secure.getString(context.contentResolver, "always_on_vpn_app")
            return context.packageName == alwaysOn
        }

        // This function is not supported from version 12 onwards.
        fun isOtherVpnHasAlwaysOn(context: Context): Boolean {
            return try {
                val alwaysOn =
                    Settings.Secure.getString(context.contentResolver, "always_on_vpn_app")
                !TextUtils.isEmpty(alwaysOn) && context.packageName != alwaysOn
            } catch (e: Exception) {
                Log.e(LOG_TAG_VPN, "Failure while retrieving Settings.Secure value ${e.message}", e)
                false
            }
        }

        fun getIcon(context: Context, packageName: String, appName: String?): Drawable? {
            if (!isValidAppName(appName, packageName)) {
                return getDefaultIcon(context)
            }

            return try {
                context.packageManager.getApplicationIcon(packageName)
            } catch (e: PackageManager.NameNotFoundException) {
                // Not adding exception details in logs.
                Log.e(
                    LOG_TAG_FIREWALL,
                    "Application Icon not available for package: $packageName" + e.message
                )
                getDefaultIcon(context)
            }
        }

        private fun isValidAppName(appName: String?, packageName: String): Boolean {
            return !isNonApp(packageName) && Constants.UNKNOWN_APP != appName
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

        fun delay(ms: Long, lifecycleScope: LifecycleCoroutineScope, updateUi: () -> Unit) {
            lifecycleScope.launch {
                kotlinx.coroutines.delay(ms)
                updateUi()
            }
        }

        fun getPackageInfoForUid(context: Context, uid: Int): Array<out String>? {
            try {
                return context.packageManager.getPackagesForUid(uid)
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(LoggerConstants.LOG_TAG_FIREWALL_LOG, "Package Not Found: " + e.message)
            }
            return null
        }

        fun isAtleastN(): Boolean {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
        }

        fun isAtleastO(): Boolean {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        }

        fun isAtleastR(): Boolean {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        }

        fun isAtleastQ(): Boolean {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        }

        fun isAtleastS(): Boolean {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        }

        fun isAtleastT(): Boolean {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        }

        fun isFdroidFlavour(): Boolean {
            return BuildConfig.FLAVOR == FLAVOR_FDROID
        }

        fun isWebsiteFlavour(): Boolean {
            return BuildConfig.FLAVOR == FLAVOR_WEBSITE
        }

        fun isPlayStoreFlavour(): Boolean {
            return BuildConfig.FLAVOR == FLAVOR_PLAY
        }

        fun getApplicationInfo(context: Context, packageName: String): ApplicationInfo? {
            return try {
                context.packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(
                    LOG_TAG_FIREWALL,
                    "ApplicationInfo is not available for package name: $packageName"
                )
                null
            }
        }

        fun sendEmailIntent(context: Context) {
            val intent =
                Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse(context.getString(R.string.about_mail_to_string))
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(context.getString(R.string.about_mail_to)))
                    putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.about_mail_subject))
                }
            context.startActivity(
                Intent.createChooser(
                    intent,
                    context.getString(R.string.about_mail_bugreport_share_title)
                )
            )
        }

        fun deleteRecursive(fileOrDirectory: File) {
            try {
                if (fileOrDirectory.isDirectory) {
                    fileOrDirectory.listFiles()?.forEach { child -> deleteRecursive(child) }
                }
                val isDeleted: Boolean =
                    if (isAtleastO()) {
                        fileOrDirectory.deleteRecursively()
                    } else {
                        fileOrDirectory.delete()
                    }
                if (DEBUG)
                    Log.d(
                        LOG_TAG_DOWNLOAD,
                        "deleteRecursive File : ${fileOrDirectory.path}, $isDeleted"
                    )
            } catch (e: Exception) {
                Log.w(LOG_TAG_DOWNLOAD, "File delete exception: ${e.message}", e)
            }
        }

        fun localBlocklistFileDownloadPath(ctx: Context, which: String, timestamp: Long): String {
            return blocklistDownloadBasePath(ctx, LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME, timestamp) +
                File.separator +
                which
        }

        fun oldLocalBlocklistDownloadDir(ctx: Context, timestamp: Long): String {
            return ctx.filesDir.canonicalPath + File.separator + timestamp + File.separator
        }

        fun hasLocalBlocklists(ctx: Context, timestamp: Long): Boolean {
            val a =
                Constants.ONDEVICE_BLOCKLISTS.all {
                    localBlocklistFile(ctx, it.filename, timestamp)?.exists() == true
                }
            return a
        }

        fun tempDownloadBasePath(ctx: Context, which: String, timestamp: Long): String {
            // instead of creating folder for actual timestamp, create for its negative value
            return blocklistCanonicalPath(ctx, which) + File.separator + (-1 * timestamp)
        }

        fun blocklistDownloadBasePath(ctx: Context, which: String, timestamp: Long): String {
            return blocklistCanonicalPath(ctx, which) + File.separator + timestamp
        }

        fun blocklistCanonicalPath(ctx: Context, which: String): String {
            return ctx.filesDir.canonicalPath + File.separator + which
        }

        private fun localBlocklistFile(ctx: Context, which: String, timestamp: Long): File? {
            return try {
                val localBlocklist = localBlocklistFileDownloadPath(ctx, which, timestamp)

                return File(localBlocklist)
            } catch (e: IOException) {
                Log.e(LOG_TAG_VPN, "Could not fetch local blocklist: " + e.message, e)
                null
            }
        }

        fun hasRemoteBlocklists(ctx: Context, timestamp: Long): Boolean {
            val remoteDir =
                remoteBlocklistFile(ctx, REMOTE_BLOCKLIST_DOWNLOAD_FOLDER_NAME, timestamp)
                    ?: return false
            val remoteFile =
                blocklistFile(remoteDir.absolutePath, Constants.ONDEVICE_BLOCKLIST_FILE_TAG)
                    ?: return false
            if (remoteFile.exists()) {
                return true
            }

            return false
        }

        fun remoteBlocklistFile(ctx: Context?, which: String, timestamp: Long): File? {
            if (ctx == null) return null

            return try {
                File(blocklistDownloadBasePath(ctx, which, timestamp))
            } catch (e: IOException) {
                Log.e(LOG_TAG_VPN, "Could not fetch remote blocklist: " + e.message, e)
                null
            }
        }

        fun blocklistFile(dirPath: String, fileName: String): File? {
            return try {
                return File(dirPath + fileName)
            } catch (e: IOException) {
                Log.e(LOG_TAG_VPN, "Could not fetch remote blocklist: " + e.message, e)
                null
            }
        }

        fun openPauseActivityAndFinish(act: Activity) {
            val intent = Intent()
            intent.setClass(act, PauseActivity::class.java)
            act.startActivity(intent)
            act.finish()
        }

        fun isNonApp(p: String): Boolean {
            return p.contains(NO_PACKAGE)
        }

        fun openAndroidAppInfo(context: Context, packageName: String?) {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.fromParts("package", packageName, null)
                context.startActivity(intent)
            } catch (e: Exception) { // ActivityNotFoundException | NullPointerException
                Log.w(LOG_TAG_FIREWALL, "Failure calling app info: ${e.message}", e)
                showToastUiCentered(
                    context,
                    context.getString(R.string.ctbs_app_info_not_available_toast),
                    Toast.LENGTH_SHORT
                )
            }
        }

        fun removeLeadingAndTrailingDots(str: String?): String {
            if (str.isNullOrBlank()) return ""

            // remove leading and trailing dots(.) from the given string
            // eg., (...adsd.asd.asa... will result in adsd.asd.asa)
            val s = CharMatcher.`is`('.').trimLeadingFrom(str)
            return CharMatcher.`is`('.').trimTrailingFrom(s)
        }

        fun fetchColor(context: Context, attr: Int): Int {
            val typedValue = TypedValue()
            val a: TypedArray = context.obtainStyledAttributes(typedValue.data, intArrayOf(attr))
            val color = a.getColor(0, 0)
            a.recycle()
            return color
        }

        fun fetchToggleBtnColors(context: Context, attr: Int): Int {
            val attributeFetch =
                if (attr == R.color.firewallNoRuleToggleBtnTxt) {
                    R.attr.firewallNoRuleToggleBtnTxt
                } else if (attr == R.color.firewallNoRuleToggleBtnBg) {
                    R.attr.firewallNoRuleToggleBtnBg
                } else if (attr == R.color.firewallBlockToggleBtnTxt) {
                    R.attr.firewallBlockToggleBtnTxt
                } else if (attr == R.color.firewallBlockToggleBtnBg) {
                    R.attr.firewallBlockToggleBtnBg
                } else if (attr == R.color.firewallWhiteListToggleBtnTxt) {
                    R.attr.firewallWhiteListToggleBtnTxt
                } else if (attr == R.color.firewallWhiteListToggleBtnBg) {
                    R.attr.firewallWhiteListToggleBtnBg
                } else if (attr == R.color.firewallExcludeToggleBtnBg) {
                    R.attr.firewallExcludeToggleBtnBg
                } else if (attr == R.color.firewallExcludeToggleBtnTxt) {
                    R.attr.firewallExcludeToggleBtnTxt
                } else if (attr == R.color.defaultToggleBtnBg) {
                    R.attr.defaultToggleBtnBg
                } else if (attr == R.color.defaultToggleBtnTxt) {
                    R.attr.defaultToggleBtnTxt
                } else if (attr == R.color.accentGood) {
                    R.attr.accentGood
                } else {
                    R.attr.chipBgColorPositive
                }
            val typedValue = TypedValue()
            val a: TypedArray =
                context.obtainStyledAttributes(typedValue.data, intArrayOf(attributeFetch))
            val color = a.getColor(0, 0)
            a.recycle()
            return color
        }

        // https://medium.com/androiddevelopers/all-about-pendingintents-748c8eb8619
        fun getActivityPendingIntent(
            context: Context,
            intent: Intent,
            flag: Int,
            mutable: Boolean
        ): PendingIntent {
            return if (isAtleastS()) {
                val sFlag =
                    if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
                PendingIntent.getActivity(context, 0, intent, sFlag)
            } else {
                PendingIntent.getActivity(context, 0, intent, flag)
            }
        }

        fun getBroadcastPendingIntent(
            context: Context,
            requestCode: Int,
            intent: Intent,
            flag: Int,
            mutable: Boolean
        ): PendingIntent {
            return if (isAtleastS()) {
                val sFlag =
                    if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
                PendingIntent.getBroadcast(context, requestCode, intent, sFlag)
            } else {
                PendingIntent.getBroadcast(context, requestCode, intent, flag)
            }
        }

        fun getRemoteBlocklistStamp(url: String): String {
            // Interacts with GO lib to fetch the stamp (Xdnx#getBlocklistStampFromURL)
            return try {
                Xdns.getBlocklistStampFromURL(url)
            } catch (e: Exception) {
                Log.w(LOG_TAG_DNS, "failure fetching stamp from Go ${e.message}", e)
                ""
            }
        }

        enum class PrivateDnsMode {
            NONE, // The setting is "Off" or "Opportunistic", and the DNS connection is not using
                  // TLS.
            UPGRADED, // The setting is "Opportunistic", and the DNS connection has upgraded to TLS.
            STRICT // The setting is "Strict".
        }

        fun getPrivateDnsMode(context: Context): PrivateDnsMode {
            // https://github.com/celzero/rethink-app/issues/408
            if (!isAtleastQ()) {
                // Private DNS was introduced in P.
                return PrivateDnsMode.NONE
            }

            val linkProperties: LinkProperties =
                getLinkProperties(context) ?: return PrivateDnsMode.NONE
            if (linkProperties.privateDnsServerName != null) {
                return PrivateDnsMode.STRICT
            }
            return if (linkProperties.isPrivateDnsActive) {
                PrivateDnsMode.UPGRADED
            } else {
                PrivateDnsMode.NONE
            }
        }

        fun isPrivateDnsActive(context: Context): Boolean {
            return getPrivateDnsMode(context) != PrivateDnsMode.NONE
        }

        private fun getLinkProperties(context: Context): LinkProperties? {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = connectivityManager.activeNetwork ?: return null
            return connectivityManager.getLinkProperties(activeNetwork)
        }

        fun removeBeginningTrailingCommas(value: String): String {
            return value.removePrefix(",").dropLastWhile { it == ',' }
        }
    }
}
