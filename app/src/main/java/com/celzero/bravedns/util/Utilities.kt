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
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.Environment
import android.provider.Settings
import android.text.TextUtils.SimpleStringSplitter
import android.text.format.DateUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.celzero.bravedns.net.doh.CountryMap
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import com.google.android.material.snackbar.Snackbar
import com.google.common.net.InternetDomainName
import java.io.*
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.*


class Utilities {


    companion object {

        private const val STORAGE_PERMISSION_CODE = 1008
        private const val READ_PHONE_STATE_REQUEST = 37

        fun checkPermission(activity: AppCompatActivity): Boolean {
            var permissionGranted = false

            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                if (ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                    val rootView: View = (activity).window.decorView.findViewById(android.R.id.content)
                    Snackbar.make(rootView, "Storage permission required", Snackbar.LENGTH_LONG).setAction("Allow") {
                            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), STORAGE_PERMISSION_CODE)
                        }.setActionTextColor(Color.WHITE).show()
                } else {
                    ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), STORAGE_PERMISSION_CODE)
                }
            } else {
                permissionGranted = true
            }

            return permissionGranted
        }

        fun checkExternalStorage(): Boolean {
            return Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED
        }


        fun getPermissionDetails(activity: Context, packageName: String): PackageInfo {
            val appInstall: PackageInfo
            val p = activity.packageManager
            appInstall = p.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            return appInstall
        }

        fun hasPermissionToReadPhoneStats(context: Context): Boolean {
            return ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_DENIED
        }


        fun requestPhoneStateStats(context: Activity) {
            ActivityCompat.requestPermissions(context, arrayOf(Manifest.permission.READ_PHONE_STATE), READ_PHONE_STATE_REQUEST)
        }

        fun isServiceRunning(c: Context, serviceClass: Class<*>): Boolean {
            val manager = c.getSystemService<ActivityManager>() ?: return false
            for (service in manager.getRunningServices(Int.MAX_VALUE)) {
                if (serviceClass.name == service.service.className) {
                    return true
                }
            }
            return false
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

        fun isAccessibilityServiceEnabled(context: Context, service: Class<out AccessibilityService?>): Boolean {
            val am = context.getSystemService<AccessibilityManager>() ?: return false
            val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            for (enabledService in enabledServices) {
                val enabledServiceInfo: ServiceInfo = enabledService.resolveInfo.serviceInfo
                if (enabledServiceInfo.packageName == context.packageName && enabledServiceInfo.name == service.name) return true
            }
            if(DEBUG) Log.e(LOG_TAG, "isAccessibilityServiceEnabled failure: ${context.packageName},  ${service.name}, return size: ${enabledServices.size}")
            return false
        }


        fun isAccessibilityServiceEnabledEnhanced(context: Context, accessibilityService: Class<out AccessibilityService?>): Boolean {
            try {
                val expectedComponentName = ComponentName(context, accessibilityService)
                val enabledServicesSetting: String = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
                val colonSplitter = SimpleStringSplitter(':')
                colonSplitter.setString(enabledServicesSetting)
                while (colonSplitter.hasNext()) {
                    val componentNameString = colonSplitter.next()
                    val enabledService = ComponentName.unflattenFromString(componentNameString)
                    if (enabledService != null && enabledService == expectedComponentName) return true
                }
                return isAccessibilityServiceEnabled(context, accessibilityService)
            }catch (e: Exception){
                return isAccessibilityServiceEnabled(context, accessibilityService)
            }
        }



        private var countryMap: CountryMap? = null

        // Return a two-letter ISO country code, or null if that fails.
        fun getCountryCode(address: InetAddress, context: Context): String {
            activateCountryMap(context)
            return (if (countryMap == null) {
                null
            } else {
                countryMap!!.getCountryCode(address)
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

        fun convertLongToDate(timeStamp: Long): String{
            val date = Date(timeStamp)
            val format = SimpleDateFormat("yy.MM (dd)", Locale.US)
            return format.format(date)
        }

        fun convertLongToRelativeTime(timeStamp: Long): String{
            return "Last updated: ${DateUtils.getRelativeTimeSpanString(timeStamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)}"
        }

        fun prepareServersToRemove(servers: String, liveServers: String): String{
            val serverList  = servers.split(",")
            val liveServerList = liveServers.split(",")
            if(DEBUG) Log.d(LOG_TAG, "Servers to remove - $serverList -- $liveServerList")
            var serversToSend : String =""
            serverList.forEach{
                if(!liveServerList.contains(it)){
                    serversToSend += "$it,"
                }
            }
            if(DEBUG) Log.d(LOG_TAG, "Servers to remove - $serversToSend")
            serversToSend = serversToSend.dropLast(1)
            return serversToSend
        }

        fun showToastInMidLayout(context: Context, message: String, toastLength: Int){
            try {
                val toast = Toast.makeText(context, message, toastLength)
                toast.setGravity(Gravity.CENTER, 0, 0)
                toast.show()
            }catch (e: java.lang.IllegalStateException){
                Log.w(LOG_TAG,"Show Toast issue : ${e.message}",e)
            }
        }

        //Check app is installed via playstore -
        //https://stackoverflow.com/questions/37539949/detect-if-an-app-is-installed-from-play-store
        fun verifyInstallerId(context: Context): Boolean {
            // A list with valid installers package name
            val validInstallers: List<String> = ArrayList(listOf("com.android.vending", "com.google.android.feedback"))
            // The package name of the app that has installed your app
            val installer = context.packageManager.getInstallerPackageName(context.packageName)
            // true if your app has been downloaded from Play Store
            return installer != null && validInstallers.contains(installer)
        }

        fun isIPLocal(ipAddress: String): Boolean{
            return try{
                val ip = InetAddress.getByName(ipAddress)
                val regex = Regex("(^127\\.0\\.0\\.1)|(^10\\.)|(^172\\.1[6-9]\\.)|(^172\\.2[0-9]\\.)|(^172\\.3[0-1]\\.)|(^192\\.168\\.)")
                ip.isAnyLocalAddress || ipAddress.matches(regex) || ipAddress == "0.0.0.0"
            }catch (e: Exception){
                Log.w(LOG_TAG, "Exception while converting string to inetaddress, ${e.message}", e)
                false
            }
        }


        fun getTypeName(type: Int): String {
            // From https://www.iana.org/assignments/dns-parameters/dns-parameters.xhtml#dns-parameters-4
            val names = arrayOf("0", "A", "NS", "MD", "MF", "CNAME", "SOA", "MB", "MG", "MR", "NULL", "WKS", "PTR", "HINFO", "MINFO", "MX", "TXT", "RP", "AFSDB", "X25", "ISDN", "RT", "NSAP", "NSAP+PTR", "SIG", "KEY", "PX", "GPOS", "AAAA", "LOC", "NXT", "EID", "NIMLOC", "SRV", "ATMA", "NAPTR", "KX", "CERT", "A6", "DNAME", "SINK", "OPT", "APL", "DS", "SSHFP", "IPSECKEY", "RRSIG", "NSEC", "DNSKEY", "DHCID", "NSEC3", "NSEC3PARAM", "TLSA", "SMIMEA")
            return if (type < names.size) {
                names[type]
            } else String.format(Locale.ROOT, "%d", type)
        }


        /**
        * To move the files from the the source to destination folder.
        * Utility will be used to move the files which are downloaded as part of
        * local/remote blocklist.*/
        @Throws(FileNotFoundException::class, IOError::class)
        fun moveTo(source: File, dest: File, destDirectory: File? = null) {

            if (destDirectory?.exists() == false) {
                destDirectory.mkdir()
            }

            val fis = FileInputStream(source)
            val bufferLength = 1024
            val buffer = ByteArray(bufferLength)
            val fos = FileOutputStream(dest)
            val bos = BufferedOutputStream(fos, bufferLength)
            var read = fis.read(buffer, 0, bufferLength)
            while (read != -1) {
                bos.write(buffer, 0, read)
                read = fis.read(buffer) // if read value is -1, it escapes loop.
            }
            fis.close()
            bos.flush()
            bos.close()

            if (!source.delete()) {
                Log.w(LOG_TAG, "failed to delete ${source.name}")
            }
        }

        /**
         * Clean up the folder which had the old download files.
         * This was introduced in v053, before that the files downloaded as part of blocklists
         * are stored in external files dir by the DownloadManager and moved to canonicalPath.
         * Now in v053 we are moving the files from external dir to canonical path.
         * So deleting the old files in the external directory.
         */
        fun deleteOldFiles(context : Context){
            val dir = File(context.getExternalFilesDir(null).toString() + Constants.DOWNLOAD_PATH)
            if (dir.isDirectory) {
                val children = dir.list()
                if(children != null && children.isNotEmpty()) {
                    for (i in children.indices) {
                        File(dir, children[i]).delete()
                    }
                }
            }
        }

    }
}

