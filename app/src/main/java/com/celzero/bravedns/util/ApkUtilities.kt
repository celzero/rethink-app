package com.celzero.bravedns.util


import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Environment
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.google.common.net.InternetDomainName


class ApkUtilities {


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
    }
}

