package com.celzero.bravedns.util


import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.Environment
import android.util.Log
import android.view.View
import android.view.accessibility.AccessibilityManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.celzero.bravedns.net.doh.CountryMap
import com.google.android.material.snackbar.Snackbar
import com.google.common.net.InternetDomainName
import java.io.IOException
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.*


class Utilities {


    companion object {

        val STORAGE_PERMISSION_CODE = 1008
        private const val READ_PHONE_STATE_REQUEST = 37

        fun checkPermission(activity: AppCompatActivity): Boolean {
            var permissionGranted = false

            if (ContextCompat.checkSelfPermission(
                    activity,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                if (ActivityCompat.shouldShowRequestPermissionRationale(
                        activity,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    )
                ) {
                    val rootView: View =
                        (activity).window.decorView.findViewById(android.R.id.content)
                    Snackbar.make(rootView, "Storage permission required", Snackbar.LENGTH_LONG)
                        .setAction("Allow") {
                            ActivityCompat.requestPermissions(
                                activity, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                                STORAGE_PERMISSION_CODE
                            )
                        }
                        .setActionTextColor(Color.WHITE)
                        .show()
                } else {
                    ActivityCompat.requestPermissions(
                        activity, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                        STORAGE_PERMISSION_CODE
                    )
                }
            } else {
                permissionGranted = true
            }

            return permissionGranted
        }

        fun checkExternalStorage(): Boolean {
            return Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)
        }


        fun getPermissionDetails(activity: Context, packageName: String): PackageInfo {

            var appInstall: PackageInfo
            var p = activity.packageManager
            appInstall = p.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)

            return appInstall
        }

        fun hasPermissionToReadPhoneStats(context: Context): Boolean {
            return ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_PHONE_STATE
            ) != PackageManager.PERMISSION_DENIED
        }


        fun requestPhoneStateStats(context: Activity) {
            ActivityCompat.requestPermissions(
                context,
                arrayOf(Manifest.permission.READ_PHONE_STATE),
                READ_PHONE_STATE_REQUEST
            )
        }

        fun isServiceRunning(
            c: Context,
            serviceClass: Class<*>
        ): Boolean {
            val manager =
                c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
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

        fun isAccessibilityServiceEnabled(
            context: Context,
            service: Class<out AccessibilityService?>
        ): Boolean {
            val am =
                context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val enabledServices =
                am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            for (enabledService in enabledServices) {
                val enabledServiceInfo: ServiceInfo = enabledService.resolveInfo.serviceInfo
                if (enabledServiceInfo.packageName.equals(context.packageName) && enabledServiceInfo.name.equals(
                        service.name
                    )
                ) return true
            }
            return false
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

        fun getFlag(countryCode: String?): String? {
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
            return String(Character.toChars(firstHalf)) + String(
                Character.toChars(
                    secondHalf
                )
            )
        }

        fun makeAddressPair(countryCode: String?, ipAddress: String): String? {
            return if (countryCode == null) {
                ipAddress
            } else String.format("%s (%s)", countryCode, ipAddress)
        }

        fun convertLongToTime(time: Long): String {
            val date = Date(time)
            val format = SimpleDateFormat("HH:mm:ss", Locale.US)
            return format.format(date)
        }
    }
}

