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
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.text.TextUtils
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.celzero.bravedns.BuildConfig.DEBUG
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.ProxyEndpoint
import com.celzero.bravedns.receiver.NotificationActionReceiver
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_VPN
import com.celzero.bravedns.util.Utilities.Companion.getActivityPendingIntent
import com.celzero.bravedns.util.Utilities.Companion.getBroadcastPendingIntent
import com.celzero.bravedns.util.Utilities.Companion.getThemeAccent
import com.celzero.bravedns.util.Utilities.Companion.isAtleastO
import com.celzero.bravedns.util.Utilities.Companion.isFdroidFlavour
import com.celzero.bravedns.util.Utilities.Companion.isPlayStoreFlavour
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit


/**
 * One-click Orbot setup.
 *
 * Broadcast receiver for Orbot's status change.
 * adopted from: github.com/guardianproject/NetCipher/blob/fee8571/libnetcipher/src/info/guardianproject/netcipher/proxy/OrbotHelper.java
 */
class OrbotHelper(
    private val context: Context, private val persistentState: PersistentState,
    private val appConfig: AppConfig
) {


    companion object {
        const val ORBOT_SERVICE_ID = 1111

        const val ORBOT_PACKAGE_NAME = "org.torproject.android"
        const val ORBOT_MARKET_URI = "market://details?id=$ORBOT_PACKAGE_NAME"

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
        private const val EXTRA_SOCKS_PROXY_HOST =
            "org.torproject.android.intent.extra.SOCKS_PROXY_HOST"
        private const val EXTRA_SOCKS_PROXY_PORT =
            "org.torproject.android.intent.extra.SOCKS_PROXY_PORT"
        private const val EXTRA_HTTP_PROXY_HOST =
            "org.torproject.android.intent.extra.HTTP_PROXY_HOST"
        private const val EXTRA_HTTP_PROXY_PORT =
            "org.torproject.android.intent.extra.HTTP_PROXY_PORT"
        private const val EXTRA_DNS_PORT = "org.torproject.android.intent.extra.DNS_PORT"

        private const val EXTRA_STATUS = "org.torproject.android.intent.extra.STATUS"

        // Orbot requestCode for PendingIntent.getBroadcast() for the notification action.
        private const val ORBOT_REQUEST_CODE = 200

        var selectedProxyType: String = AppConfig.ProxyType.NONE.name

        const val NOTIF_CHANNEL_ID_PROXY_ALERTS = "PROXY_ALERTS"

    }

    var socks5Port: Int? = null
    var httpsPort: Int? = null
    var socks5Ip: String? = null
    var httpsIp: String? = null
    var dnsPort: Int? = null

    @Volatile
    private var isResponseReceivedFromOrbot: Boolean = false

    /**
     * Returns the intent which will initiate the Orbot in non-vpn mode.
     */
    private fun getOrbotStartIntent(): Intent {
        val intent = Intent(ACTION_START)
        intent.setPackage(ORBOT_PACKAGE_NAME)
        intent.putExtra(EXTRA_PACKAGE_NAME, context.packageName)
        return intent
    }

    /**
     * Returns the intent which will call the stop of the Orbot.
     * The below intent is not stopping the Orbot.
     */
    private fun getOrbotStopIntent(): Intent {
        val intent = Intent(ACTION_STOP_VPN)
        intent.setPackage(ORBOT_PACKAGE_NAME)
        intent.putExtra(EXTRA_PACKAGE_NAME, context.packageName)
        return intent
    }


    fun getIntentForDownload(): Intent? {
        if (isPlayStoreFlavour()) { //For play store
            var intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse(ORBOT_MARKET_URI)

            val pm = context.packageManager
            val resInfos = pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))

            var foundPackageName: String? = null
            for (r in resInfos) {
                if (TextUtils.equals(r.activityInfo.packageName, PLAY_PACKAGE_NAME)) {
                    foundPackageName = r.activityInfo.packageName
                    break
                }
            }
            if (foundPackageName == null) {
                return null
            }

            intent.setPackage(foundPackageName)
            return intent

        } else if (isFdroidFlavour()) {  //For fdroid
            // Orbot is not available in fDroid for now, So taking the user to website download link.
            return Intent(
                Intent.ACTION_VIEW,
                context.resources.getString(R.string.orbot_download_link_website).toUri()
            )
        } else {
            return Intent(
                Intent.ACTION_VIEW,
                context.resources.getString(R.string.orbot_download_link_website).toUri()
            )
        }
    }

    /**
     * Sends the intent to initiate the start in Orbot
     * and registers for the Orbot ACTION_STATUS.
     */
    suspend fun startOrbot(type: String) {
        selectedProxyType = type
        val intent = getOrbotStartIntent()
        isResponseReceivedFromOrbot = false
        context.registerReceiver(orbotStatusReceiver, IntentFilter(ACTION_STATUS))
        context.sendBroadcast(intent)
        waitForOrbot()
        if (DEBUG) Log.d(LOG_TAG_VPN, "request orbot start by broadcast")
    }

    /**
     * Broadcast receiver to listen on the status of the Orbot.
     * The broadcast will be registered with the ACTION_STATUS.
     * Orbot will be sending the status of the non-vpn mode of the app(ON, OFF, STARTING, STOPPING).
     */
    private val orbotStatusReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(LOG_TAG_VPN, "received status from orbot, action: ${intent.action}")
            if (ACTION_STATUS != intent.action) {
                return
            }

            val status = intent.getStringExtra(EXTRA_STATUS)
            if (DEBUG) Log.d(
                LOG_TAG_VPN,
                "received status from orbot, action: ${intent.action}, status: $status"
            )

            when (status) {
                STATUS_ON -> {
                    isResponseReceivedFromOrbot = true
                    if (socks5Ip == null || httpsIp == null || socks5Port == null || httpsPort == null || dnsPort == null) {
                        updateOrbotProxyData(intent)
                    }
                    setOrbotMode()
                }
                STATUS_OFF -> {
                    stopOrbot(isInteractive = false)
                    context.unregisterReceiver(this)
                }
                STATUS_STARTING -> {
                    updateOrbotProxyData(intent)
                }
                STATUS_STOPPING -> {
                    updateOrbotProxyData(intent)
                    stopOrbot(isInteractive = false)
                }
            }
        }
    }

    /**
     * Updates the shared preference related to Orbot Stop.
     * Notifies the user about the Orbot failure/stop.
     * The notification will be sent if the user not initiated the stop.
     */
    fun stopOrbot(isInteractive: Boolean) {
        if (!isInteractive && selectedProxyType != AppConfig.ProxyType.NONE.name) {
            val notificationManager = context.getSystemService(
                VpnService.NOTIFICATION_SERVICE
            ) as NotificationManager
            notificationManager.notify(
                NOTIF_CHANNEL_ID_PROXY_ALERTS, ORBOT_SERVICE_ID,
                createNotification().build()
            )
        }
        selectedProxyType = AppConfig.ProxyType.NONE.name
        appConfig.removeAllProxies()
        persistentState.orbotConnectionStatus.postValue(false)
        context.sendBroadcast(getOrbotStopIntent())
        if (DEBUG) Log.d(LOG_TAG_VPN, "stop orbot, remove from proxy")
    }

    /**
     * Creates notification for the failure of Orbot.
     * Creates a new notification channel for the Orbot failure update.
     */
    private fun createNotification(): NotificationCompat.Builder {

        val pendingIntent = getActivityPendingIntent(
            context, Intent(
                context,
                HomeScreenActivity::class.java
            ),
            PendingIntent.FLAG_UPDATE_CURRENT,
            mutable = false
        )

        var builder: NotificationCompat.Builder
        if (isAtleastO()) {
            val name: CharSequence = context.getString(R.string.notif_channel_proxy_failure)
            val description = context.resources.getString(R.string.notif_channel_desc_proxy_failure)
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(NOTIF_CHANNEL_ID_PROXY_ALERTS, name, importance)
            channel.description = description
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
            builder = NotificationCompat.Builder(context, NOTIF_CHANNEL_ID_PROXY_ALERTS)
        } else {
            builder = NotificationCompat.Builder(context, NOTIF_CHANNEL_ID_PROXY_ALERTS)
        }

        val contentTitle = context.resources.getString(R.string.settings_orbot_notification_heading)
        val contentText = context.resources.getString(R.string.settings_orbot_notification_content)
        builder.setSmallIcon(R.drawable.dns_icon).setContentTitle(contentTitle).setContentIntent(
            pendingIntent
        ).setContentText(contentText)
        builder.setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
        builder.color = ContextCompat.getColor(context, getThemeAccent(context))
        val openIntent = getOrbotOpenIntent()
        val notificationAction: NotificationCompat.Action = NotificationCompat.Action(
            0,
            context.resources.getString(
                R.string.settings_orbot_notification_action
            ),
            openIntent
        )
        builder.addAction(notificationAction)
        builder.setAutoCancel(true)

        // Secret notifications are not shown on the lock screen.  No need for this app to show there.
        // Only available in API >= 21
        builder = builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        return builder
    }

    private fun getOrbotOpenIntent(): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java)
        intent.putExtra(Constants.NOTIFICATION_ACTION, ORBOT_NOTIFICATION_ACTION_TEXT)
        return getBroadcastPendingIntent(
            context, ORBOT_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT, mutable = false
        )
    }

    private fun setOrbotMode() {
        io {
            Log.i(LOG_TAG_VPN, "Initiate orbot start with type: $selectedProxyType")

            if (isTypeSocks5() && handleOrbotSocks5Update()) {
                enableOrbotDns()
                appConfig.addProxy(AppConfig.ProxyType.SOCKS5, AppConfig.ProxyProvider.ORBOT)
            } else if (isTypeHttp() && handleOrbotHttpUpdate()) {
                appConfig.addProxy(AppConfig.ProxyType.HTTP, AppConfig.ProxyProvider.ORBOT)
            } else if (isTypeHttpSocks5() && handleOrbotSocks5Update() && handleOrbotHttpUpdate()) {
                enableOrbotDns()
                appConfig.addProxy(AppConfig.ProxyType.HTTP_SOCKS5, AppConfig.ProxyProvider.ORBOT)
            } else {
                uiCtx {
                    stopOrbot(isInteractive = true)
                }
            }
            persistentState.orbotConnectionStatus.postValue(false)
        }
    }

    private fun enableOrbotDns() {
        if (dnsPort == null) return

        // no need to set dns if Android's private-dns is enabled
        if (Utilities.isPrivateDnsActive(context)) return

        // no need to set dns if Rethink's DNS/DNS+Firewall mode is not set
        if (!appConfig.getBraveMode().isDnsActive()) return

        io {
            val endpoint = appConfig.getOrbotDnsProxyEndpoint() ?: return@io

            // no-need to update, as database has same config and already selected
            if (endpoint.proxyPort == dnsPort && endpoint.isSelected) return@io

            endpoint.proxyPort = dnsPort!!
            endpoint.isSelected = true
            appConfig.handleOrbotDnsChange(endpoint)
        }
    }

    private fun isTypeSocks5(): Boolean {
        return selectedProxyType == AppConfig.ProxyType.SOCKS5.name
    }

    private fun isTypeHttp(): Boolean {
        return selectedProxyType == AppConfig.ProxyType.HTTP.name
    }

    private fun isTypeHttpSocks5(): Boolean {
        return selectedProxyType == AppConfig.ProxyType.HTTP_SOCKS5.name
    }

    private suspend fun handleOrbotSocks5Update(): Boolean {
        val proxyEndpoint = constructProxy()
        return if (proxyEndpoint != null) {
            appConfig.insertOrbotProxy(proxyEndpoint)
            true
        } else {
            Log.w(LOG_TAG_VPN, "Error inserting value in proxy database")
            false
        }
    }

    private fun handleOrbotHttpUpdate(): Boolean {
        return if (httpsIp != null && httpsPort != null) {
            persistentState.httpProxyHostAddress = httpsIp!!
            persistentState.httpProxyPort = httpsPort!!
            true
        } else {
            Log.w(LOG_TAG_VPN, "could not setup Orbot http proxy with ${httpsIp}:${httpsPort}")
            false
        }
    }

    private fun constructProxy(): ProxyEndpoint? {
        if (socks5Ip == null || socks5Port == null) {
            Log.w(
                LOG_TAG_VPN,
                "Cannot construct proxy with values ip: $socks5Ip, port: $socks5Port"
            )
            return null
        }

        return ProxyEndpoint(
            id = 0, orbot, proxyMode = 1, proxyType = "NONE", ORBOT_PACKAGE_NAME,
            socks5Ip!!, socks5Port!!, userName = "", password = "",
            isSelected = true, isCustom = true, isUDP = true,
            modifiedDataTime = 0L, latency = 0
        )
    }

    /**
     * Get the data from the received intent from the Orbot and assign the values.
     */
    fun updateOrbotProxyData(data: Intent?) {
        val socks5ProxyHost = data?.extras?.getString(EXTRA_SOCKS_PROXY_HOST)
        val socks5ProxyPort = data?.extras?.getString(EXTRA_SOCKS_PROXY_PORT)
        val httpsProxyHost = data?.extras?.getString(EXTRA_HTTP_PROXY_HOST)
        val httpsProxyPort = data?.extras?.getString(EXTRA_HTTP_PROXY_PORT)
        val dnsProxyPort = data?.extras?.getString(EXTRA_DNS_PORT)

        socks5Port = socks5ProxyPort as Int?
        httpsPort = httpsProxyPort as Int?
        socks5Ip = socks5ProxyHost
        httpsIp = httpsProxyHost
        dnsPort = dnsProxyPort as Int?

        if (DEBUG) Log.d(
            LOG_TAG_VPN,
            "OrbotHelper - Orbot - socks5:$socks5Ip($socks5Port), http: $httpsIp($httpsPort), dns port: $dnsPort"
        )
    }

    /**
     * Create a ScheduledExecutorService which will be executed after 30 sec of Orbot status
     * initiation.
     */
    private suspend fun waitForOrbot() {
        io {
            delay(TimeUnit.SECONDS.toMillis(25L))
            Log.i(LOG_TAG_VPN, "after timeout, isOrbotUp? $isResponseReceivedFromOrbot")

            // Execute a task in the background thread after 25 sec.
            // If there is no response for the broadcast from the Orbot,
            // then the executor will execute and will disable the Orbot settings.
            // Some cases where the Orbot won't be responding -eg., force stop of application,
            // user disabled auto-start of the application.
            if (isResponseReceivedFromOrbot) return@io

            withContext(Dispatchers.Main) {
                stopOrbot(isInteractive = false)
            }
        }
    }

    fun unregisterReceiver() {
        try {
            context.unregisterReceiver(orbotStatusReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(LOG_TAG_VPN, "Unregister not needed: ${e.message}")
        }
    }

    // Throw intent to start the Orbot application.
    fun openOrbotApp() {
        try {
            val launchIntent: Intent? = context.packageManager?.getLaunchIntentForPackage(
                ORBOT_PACKAGE_NAME
            )
            if (launchIntent != null) {//null pointer check in case package name was not found
                context.startActivity(launchIntent)
            } else {
                openOrbotAppInfo()
            }
        } catch (e: ActivityNotFoundException) {
            Log.w(LOG_TAG_VPN, "Failure calling app info: ${e.message}", e)
        }
    }

    private fun openOrbotAppInfo() {
        val text = context.getString(R.string.orbot_app_issue)
        Utilities.showToastUiCentered(context, text, Toast.LENGTH_SHORT)
        try {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.fromParts("package", ORBOT_PACKAGE_NAME, null)
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(LOG_TAG_VPN, "Failure calling app info: ${e.message}", e)
        }
    }

    private fun io(f: suspend () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            f()
        }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) {
            f()
        }
    }
}
