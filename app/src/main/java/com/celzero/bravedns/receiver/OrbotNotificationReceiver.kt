package com.celzero.bravedns.receiver

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.celzero.bravedns.R
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.OrbotHelper
import com.celzero.bravedns.util.Utilities

class OrbotNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        Log.d(Constants.LOG_TAG, "OrbotNotificationReceiver: onReceive")
        val action: String? = intent?.getStringExtra(OrbotHelper.ORBOT_NOTIFICATION_ACTION)
        if(action == OrbotHelper.ORBOT_NOTIFICATION_ACTION_TEXT){
            openOrbotApp(context)
            val manager = context?.getSystemService(Context. NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(OrbotHelper.ORBOT_SERVICE_ID)
        }
    }

    private fun openOrbotApp(context: Context?) {
        val packageName = OrbotHelper.ORBOT_PACKAGE_NAME
        Log.d(Constants.LOG_TAG, "appInfoForPackage: $packageName")
        try {
            val launchIntent: Intent? = context?.packageManager?.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {//null pointer check in case package name was not found
                Log.d(Constants.LOG_TAG, "launchIntent: $packageName")
                context.startActivity(launchIntent)
            } else {
                val text = context?.getString(R.string.orbot_app_issue)
                if(text != null) {
                    Utilities.showToastInMidLayout(context, text, Toast.LENGTH_SHORT)
                }
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.fromParts("package", packageName, null)
                context?.startActivity(intent)
            }
        } catch (e: ActivityNotFoundException) {
            Log.w(Constants.LOG_TAG, "Exception while opening app info: ${e.message}", e)
        }
    }

}