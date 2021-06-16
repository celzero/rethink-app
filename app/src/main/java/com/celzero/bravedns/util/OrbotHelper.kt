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

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.text.TextUtils
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.celzero.bravedns.R
import com.celzero.bravedns.database.ProxyEndpoint
import com.celzero.bravedns.database.ProxyEndpointRepository
import com.celzero.bravedns.receiver.NotificationActionReceiver
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.appList
import com.celzero.bravedns.util.Constants.Companion.DOWNLOAD_SOURCE_FDROID
import com.celzero.bravedns.util.Constants.Companion.DOWNLOAD_SOURCE_PLAY_STORE
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG_VPN
import com.celzero.bravedns.util.Utilities.Companion.getThemeAccent
import settings.Settings
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit


/**
 * OrbotHelper - Integration of One touch Orbot configuration.
 * Helps to send intent to Orbot application and the receiver registered to listen for the
 * status change of Orbot.
 *
 * ref : github.com/guardianproject/NetCipher/blob/fee8571/libnetcipher/src/info/guardianproject/netcipher/proxy/OrbotHelper.java
 */
class OrbotHelper(private val persistentState: PersistentState, private val proxyEndpointRepository: ProxyEndpointRepository) {

    /**
     * Constants - ORBOT constants
     */
    companion object {
        const val ORBOT_NOTIFICATION_ID = "Orbot"
        const val ORBOT_SERVICE_ID = 1111

        const val ORBOT_PACKAGE_NAME = "org.torproject.android"
        const val ORBOT_MARKET_URI = "market://details?id=$ORBOT_PACKAGE_NAME"
        const val ORBOT_FDROID_URI = "https://f-droid.org/repository/browse/?fdid=$ORBOT_PACKAGE_NAME"

        const val FDROID_PACKAGE_NAME = "org.fdroid.fdroid"
        const val PLAY_PACKAGE_NAME = "com.android.vending"

        private const val ACTION_STATUS = "org.torproject.android.intent.action.STATUS"
        private const val ACTION_START = "org.torproject.android.intent.action.START"
        private const val ACTION_STOP_VPN = "org.torproject.android.intent.action.STOP_VPN"

        private const val EXTRA_PACKAGE_NAME = "org.torproject.android.intent.extra.PACKAGE_NAME"

        private const val STATUS_ON = "ON"
        private const val STATUS_STARTING = "STARTING"
        private const val STATUS_STOPPING = "STOPPING"
        private const val STATUS_OFF = "OFF"

        private const val orbot = "ORBOT"

        const val ORBOT_NOTIFICATION_ACTION_TEXT = "OPEN_ORBOT_INTENT"

        //https://github.com/guardianproject/orbot/blob/master/orbotservice/src/main/java/org/torproject/android/service/TorServiceConstants.java
        private const val EXTRA_SOCKS_PROXY_HOST = "org.torproject.android.intent.extra.SOCKS_PROXY_HOST"
        private const val EXTRA_SOCKS_PROXY_PORT = "org.torproject.android.intent.extra.SOCKS_PROXY_PORT"
        private const val EXTRA_HTTP_PROXY_HOST = "org.torproject.android.intent.extra.HTTP_PROXY_HOST"
        private const val EXTRA_HTTP_PROXY_PORT = "org.torproject.android.intent.extra.HTTP_PROXY_PORT"

        private const val EXTRA_STATUS = "org.torproject.android.intent.extra.STATUS"

        // Orbot requestCode for PendingIntent.getBroadcast() for the notification action.
        private const val ORBOT_REQUEST_CODE = 200
    }

    var socks5Port: Int? = null
    var httpsPort: Int? = null
    var socks5IP: String? = null
    var httpsIP: String? = null

    private var isResponseReceivedFromOrbot: Boolean = false

    /**
     * Checks whether the Orbot package name in the list
     */
    fun isOrbotInstalled(): Boolean {
        if (appList.contains(ORBOT_PACKAGE_NAME)) return true
        if (DEBUG) Log.d(LOG_TAG_VPN, "Settings - Orbot - isOrbotInstalled is false")
        return false
    }

    /**
     * Returns the intent which will initiate the Orbot in non-vpn mode.
     */
    private fun getOrbotStartIntent(context: Context): Intent? {
        if (DEBUG) Log.d(LOG_TAG_VPN, "Settings - Orbot - getOrbotStartIntent")
        val intent = Intent(ACTION_START)
        intent.setPackage(ORBOT_PACKAGE_NAME)
        intent.putExtra(EXTRA_PACKAGE_NAME, context.packageName)
        return intent
    }

    /**
     * Returns the intent which will call the stop of the Orbot.
     * The below intent is not stopping the Orbot.
     */
    private fun getOrbotStopIntent(context: Context): Intent? {
        if (DEBUG) Log.d(LOG_TAG_VPN, "Settings - Orbot - getOrbotStopIntent")
        val intent = Intent(ACTION_STOP_VPN)
        intent.setPackage(ORBOT_PACKAGE_NAME)
        intent.putExtra(EXTRA_PACKAGE_NAME, context.packageName)
        return intent
    }


    fun getIntentForDownload(context: Context, mode: Int): Intent? {
        if (mode == DOWNLOAD_SOURCE_PLAY_STORE) { //For play store
            var intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse(ORBOT_MARKET_URI)

            val pm = context.packageManager
            val resInfos = pm.queryIntentActivities(intent, 0)

            var foundPackageName: String? = null
            for (r in resInfos) {
                Log.i("OrbotHelper", "market: " + r.activityInfo.packageName)
                if (TextUtils.equals(r.activityInfo.packageName, PLAY_PACKAGE_NAME)) {
                    foundPackageName = r.activityInfo.packageName
                    break
                }
            }
            if (foundPackageName == null) {
                return null
            } else {
                intent.setPackage(foundPackageName)
            }
            return intent

        } else if (mode == DOWNLOAD_SOURCE_FDROID) {  //For fdroid
            // Orbot is not available in fDroid for now, So commenting the below code
            // and taking the user to website download link.
            // Will add the below commented code in later versions if the fdroid added Orbot.
            /*val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse(ORBOT_FDROID_URI)
            return intent*/
            return Intent(Intent.ACTION_VIEW, context.resources.getString(R.string.orbot_download_link_website).toUri())
        } else {
            return Intent(Intent.ACTION_VIEW, context.resources.getString(R.string.orbot_download_link_website).toUri())
        }
    }

    /**
     * Returns the intent to get the status of the Orbot App.
     * Not used as of now.
     */
    private fun getOrbotStatusIntent(context: Context): Intent? {
        if (DEBUG) Log.d(LOG_TAG_VPN, "Settings - Orbot - getOrbotStatusIntent")
        val intent = Intent(ACTION_STATUS)
        intent.setPackage(ORBOT_PACKAGE_NAME)
        intent.putExtra(EXTRA_PACKAGE_NAME, context.packageName)
        return intent
    }

    /**
     * Sends the intent to initiate the start in Orbot
     * and registers for the Orbot ACTION_STATUS.
     */
    fun startOrbot(context: Context) {
        val intent = getOrbotStartIntent(context)
        isResponseReceivedFromOrbot = false
        context.registerReceiver(orbotStatusReceiver, IntentFilter(ACTION_STATUS))
        context.sendBroadcast(intent)
        timeOutForOrbot()
        if (DEBUG) Log.d(LOG_TAG_VPN, "Settings - Orbot - requestOrbotStatus")
    }

    /**
     * Broadcast receiver to listen on the status of the Orbot.
     * The broadcast will be registered with the ACTION_STATUS.
     * Orbot will be sending the status of the non-vpn mode of the app(ON, OFF, STARTING, STOPPING).
     */
    private val orbotStatusReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (DEBUG) Log.d(LOG_TAG_VPN, "OrbotHelper - Orbot - OnStatusReceiver - ${intent.action}")
            if (TextUtils.equals(intent.action, ACTION_STATUS)) {
                val status = intent.getStringExtra(EXTRA_STATUS)
                if (DEBUG) Log.d(LOG_TAG_VPN, "OrbotHelper - Orbot - status - $status")
                if (status == STATUS_ON) {
                    isResponseReceivedFromOrbot = true
                    if (socks5IP != null && httpsIP != null && socks5IP != null && httpsIP != null) {
                        orbotStarted()
                    } else {
                        updateOrbotProxyData(intent)
                        orbotStarted()
                    }
                } else if (status == STATUS_OFF) {
                    stopOrbot(context, isUserInitiated = false)
                    context.unregisterReceiver(this)
                } else if (status == STATUS_STARTING) {
                    updateOrbotProxyData(intent)
                } else if (status == STATUS_STOPPING) {
                    updateOrbotProxyData(intent)
                    stopOrbot(context, isUserInitiated = false)
                }
            }
        }
    }

    /**
     * Updates the shared preference related to Orbot Stop.
     * Notifies the user about the Orbot failure/stop.
     * The notification will be sent if the user not initiated the stop.
     */
    fun stopOrbot(context: Context, isUserInitiated: Boolean) {
        if (!isUserInitiated && persistentState.orbotMode != Constants.ORBOT_MODE_NONE) {
            val notificationManager = context.getSystemService(VpnService.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancelAll()
            val builder = createNotification(context)
            notificationManager.notify(ORBOT_SERVICE_ID, builder.build())
        }
        persistentState.orbotMode = Constants.ORBOT_MODE_NONE
        persistentState.orbotEnabledMode = Constants.ORBOT_MODE_NONE
        persistentState.orbotConnectionStatus.postValue(false)
        persistentState.httpProxyPort = 0
        persistentState.httpProxyHostAddress = ""
        HomeScreenActivity.GlobalVariable.appMode?.setProxyMode(Settings.ProxyModeNone)
        val intent = getOrbotStopIntent(context)
        context.sendBroadcast(intent)
        if (DEBUG) Log.d(LOG_TAG_VPN, "OrbotHelper - Orbot - stopOrbot")
    }

    /**
     * Creates notification for the failure of Orbot.
     * Creates a new notification channel for the Orbot failure update.
     */
    private fun createNotification(context: Context): NotificationCompat.Builder {
        val mainActivityIntent = PendingIntent.getActivity(context, 0, Intent(context, HomeScreenActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT)
        var builder: NotificationCompat.Builder
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name: CharSequence = ORBOT_NOTIFICATION_ID
            val description = context.resources.getString(R.string.settings_orbot_notification_desc)
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(ORBOT_NOTIFICATION_ID, name, importance)
            channel.description = description
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
            builder = NotificationCompat.Builder(context, ORBOT_NOTIFICATION_ID)
        } else {
            builder = NotificationCompat.Builder(context, ORBOT_NOTIFICATION_ID)
        }

        val contentTitle = context.resources.getString(R.string.settings_orbot_notification_heading)
        val contentText = context.resources.getString(R.string.settings_orbot_notification_content)
        builder.setSmallIcon(R.drawable.dns_icon).setContentTitle(contentTitle).setContentIntent(mainActivityIntent).setContentText(contentText)
        builder.setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
        builder.color = ContextCompat.getColor(context, getThemeAccent(context))
        val openIntent = getOrbotOpenIntent(context)
        val notificationAction: NotificationCompat.Action = NotificationCompat.Action(0, context.resources.getString(R.string.settings_orbot_notification_action), openIntent)
        builder.addAction(notificationAction)

        // Secret notifications are not shown on the lock screen.  No need for this app to show there.
        // Only available in API >= 21
        builder = builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        builder.build()
        return builder
    }

    private fun getOrbotOpenIntent(context: Context): PendingIntent? {
        val intentAction = Intent(context, NotificationActionReceiver::class.java)
        intentAction.putExtra(Constants.NOTIFICATION_ACTION, ORBOT_NOTIFICATION_ACTION_TEXT)
        return PendingIntent.getBroadcast(context, ORBOT_REQUEST_CODE, intentAction, PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /**
     * Updates the shared pref values - Orbot started status
     */
    private fun orbotStarted() {
        persistentState.orbotConnectionStatus.postValue(false)
        setOrbotMode()
    }


    private fun setOrbotMode() {
        Log.i(LOG_TAG_VPN, "OrbotHelper - Orbot - startOrbot with ${persistentState.orbotMode}")
        if (persistentState.orbotMode == Constants.ORBOT_MODE_SOCKS5) {
            val proxyEndpoint = constructProxy()
            if (proxyEndpoint != null) {
                proxyEndpointRepository.clearOrbotData()
                proxyEndpointRepository.insertAsync(proxyEndpoint)
            }
            HomeScreenActivity.GlobalVariable.appMode?.setProxyMode(Constants.ORBOT_SOCKS)
            persistentState.orbotEnabledMode = Constants.ORBOT_MODE_SOCKS5
        } else if (persistentState.orbotMode == Constants.ORBOT_MODE_HTTP) {
            if (httpsIP != null && httpsPort != null) {
                persistentState.httpProxyHostAddress = httpsIP!!
                persistentState.httpProxyPort = httpsPort!!
            }
            HomeScreenActivity.GlobalVariable.appMode?.setProxyMode(Settings.ProxyModeNone)
            persistentState.orbotEnabledMode = Constants.ORBOT_MODE_HTTP
        } else if (persistentState.orbotMode == Constants.ORBOT_MODE_BOTH) {
            val proxyEndpoint = constructProxy()
            if (proxyEndpoint != null) {
                proxyEndpointRepository.clearOrbotData()
                proxyEndpointRepository.insertAsync(proxyEndpoint)
            }
            if (httpsIP != null && httpsPort != null) {
                persistentState.httpProxyHostAddress = httpsIP!!
                persistentState.httpProxyPort = httpsPort!!
            }
            HomeScreenActivity.GlobalVariable.appMode?.setProxyMode(Constants.ORBOT_SOCKS)
            persistentState.orbotEnabledMode = Constants.ORBOT_MODE_BOTH
        } else {
            HomeScreenActivity.GlobalVariable.appMode?.setProxyMode(Settings.ProxyModeNone)
            persistentState.orbotEnabledMode = Constants.ORBOT_MODE_NONE
        }

    }

    private fun constructProxy(): ProxyEndpoint? {
        if (socks5IP != null && socks5Port != null) {
            return ProxyEndpoint(-1, orbot, 1, "NONE", ORBOT_PACKAGE_NAME, socks5IP!!, socks5Port!!, "", "", true, true, true, 0L, 0)
        }
        return null
    }

    /**
     * Get the data from the received intent from the Orbot and assign the values.
     */
    fun updateOrbotProxyData(data: Intent?) {
        val socks5ProxyHost = data?.extras?.get(EXTRA_SOCKS_PROXY_HOST)
        val socks5ProxyPort = data?.extras?.get(EXTRA_SOCKS_PROXY_PORT)
        val httpsProxyHost = data?.extras?.get(EXTRA_HTTP_PROXY_HOST)
        val httpsProxyPort = data?.extras?.get(EXTRA_HTTP_PROXY_PORT)

        socks5Port = socks5ProxyPort as Int?
        httpsPort = httpsProxyPort as Int?
        socks5IP = socks5ProxyHost as String?
        httpsIP = httpsProxyHost as String?

        if (DEBUG) Log.d(LOG_TAG_VPN, "OrbotHelper - Orbot - $socks5Port, $httpsPort, $socks5IP, $httpsIP")
    }

    /**
     * Create a ScheduledExecutorService which will be executed after 30 sec of Orbot status
     * initiation.
     */
    private fun timeOutForOrbot() {
        // Create an executor that executes tasks in a background thread.
        val backgroundExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

        // Execute a task in the background thread after 30 sec.
        // If there is no response for the broadcast from the Orbot,
        // then the executor will execute and will disable the Orbot settings.
        // Some cases where the Orbot won't be responding -eg., force stop of application,
        // user disabled auto-start of the application.
        backgroundExecutor.schedule({
            Log.i(LOG_TAG_VPN, "timeOutForOrbot executor triggered - $isResponseReceivedFromOrbot")
            if (!isResponseReceivedFromOrbot) {
                val vpnService = VpnController.getInstance().getBraveVpnService()
                if (vpnService != null) {
                    stopOrbot(vpnService, isUserInitiated = false)
                }
            }
            backgroundExecutor.shutdown()
        }, 25, TimeUnit.SECONDS)
    }

    fun unregisterReceiver(context: Context) {
        try {
            context.unregisterReceiver(orbotStatusReceiver)
        } catch (e: Exception) {
            if (DEBUG) Log.w(LOG_TAG_VPN, "Unregister not needed: ${e.message}")
        }
    }


}