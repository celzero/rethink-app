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

import Logger
import Logger.LOG_TAG_VPN
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.text.TextUtils
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.ProxyEndpoint
import com.celzero.bravedns.database.ProxyEndpoint.Companion.DEFAULT_PROXY_TYPE
import com.celzero.bravedns.receiver.NotificationActionReceiver
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.ui.activity.AppLockActivity
import com.celzero.bravedns.util.Constants.Companion.HTTP_PROXY_PORT
import com.celzero.bravedns.util.Constants.Companion.SOCKS_DEFAULT_PORT
import com.celzero.bravedns.util.Utilities.getActivityPendingIntent
import com.celzero.bravedns.util.Utilities.getBroadcastPendingIntent
import com.celzero.bravedns.util.Utilities.isAtleastO
import com.celzero.bravedns.util.Utilities.isAtleastT
import com.celzero.bravedns.util.Utilities.isFdroidFlavour
import com.celzero.bravedns.util.Utilities.isPlayStoreFlavour
import com.celzero.bravedns.util.Utilities.isValidPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * One-click Orbot setup.
 *
 * Broadcast receiver for Orbot's status change. adopted from:
 * github.com/guardianproject/NetCipher/blob/fee8571/libnetcipher/src/info/guardianproject/netcipher/proxy/OrbotHelper.java
 */
class OrbotHelper(
    private val context: Context,
    private val persistentState: PersistentState,
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

        private const val ORBOT = "ORBOT"

        const val ORBOT_NOTIFICATION_ACTION_TEXT = "OPEN_ORBOT_INTENT"

        // https://github.com/guardianproject/orbot/blob/master/orbotservice/src/main/java/org/torproject/android/service/TorServiceConstants.java
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

    private var socks5Port: Int? = null
    private var httpsPort: Int? = null
    private var socks5Ip: String? = null
    private var httpsIp: String? = null
    private var dnsPort: Int? = null

    @Volatile
    private var isResponseReceivedFromOrbot: Boolean = false

    private var retryCount = 3

    /** Returns the intent which will initiate the Orbot in non-vpn mode. */
    private fun getOrbotStartIntent(): Intent {
        val intent = Intent(ACTION_START)
        intent.setPackage(ORBOT_PACKAGE_NAME)
        intent.putExtra(EXTRA_PACKAGE_NAME, context.packageName)
        return intent
    }

    /**
     * Returns the intent which will call the stop of the Orbot. The below intent is not stopping
     * the Orbot.
     */
    private fun getOrbotStopIntent(): Intent {
        val intent = Intent(ACTION_STOP_VPN)
        intent.setPackage(ORBOT_PACKAGE_NAME)
        intent.putExtra(EXTRA_PACKAGE_NAME, context.packageName)
        return intent
    }

    fun getIntentForDownload(): Intent? {
        if (isPlayStoreFlavour()) { // For play store
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse(ORBOT_MARKET_URI)

            val pm = context.packageManager
            val resInfos =
                if (isAtleastT()) {
                    pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
                } else {
                    pm.queryIntentActivities(intent, 0)
                }

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
        } else if (isFdroidFlavour()) { // For fdroid
            // Orbot is not available in fDroid for now, So taking the user to website download
            // link.
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
     * Sends the intent to initiate the start in Orbot and registers for the Orbot ACTION_STATUS.
     */
    suspend fun startOrbot(type: String) {
        selectedProxyType = type
        val intent = getOrbotStartIntent()
        isResponseReceivedFromOrbot = false
        ContextCompat.registerReceiver(
            context,
            orbotStatusReceiver,
            IntentFilter(ACTION_STATUS),
            ContextCompat.RECEIVER_EXPORTED
        )
        context.sendBroadcast(intent)
        waitForOrbot()
        Logger.d(LOG_TAG_VPN, "request orbot start by broadcast")
    }

    /**
     * Broadcast receiver to listen on the status of the Orbot. The broadcast will be registered
     * with the ACTION_STATUS. Orbot will be sending the status of the non-vpn mode of the app(ON,
     * OFF, STARTING, STOPPING).
     */
    private val orbotStatusReceiver: BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                Logger.i(LOG_TAG_VPN, "received status from orbot, action: ${intent.action}")
                if (ACTION_STATUS != intent.action) {
                    return
                }

                val status = intent.getStringExtra(EXTRA_STATUS)
                Logger.d(
                    LOG_TAG_VPN,
                    "received status from orbot, action: ${intent.action}, status: $status"
                )

                when (status) {
                    STATUS_ON -> {
                        Logger.i(LOG_TAG_VPN, "Orbot is ON, update the proxy data")
                        isResponseReceivedFromOrbot = true
                        updateOrbotProxyData(intent)
                        setOrbotMode()
                    }

                    STATUS_OFF -> {
                        Logger.i(LOG_TAG_VPN, "Orbot is OFF, retry or stop the Orbot")
                        io { waitForOrbot() }
                        unregisterReceiver()
                    }

                    STATUS_STARTING -> {
                        Logger.i(LOG_TAG_VPN, "Orbot is STARTING, update the proxy data")
                        updateOrbotProxyData(intent)
                    }

                    STATUS_STOPPING -> {
                        Logger.i(LOG_TAG_VPN, "Orbot is STOPPING, stop the Proxy")
                        updateOrbotProxyData(intent)
                        stopOrbot(isInteractive = false)
                    }
                }
            }
        }

    /**
     * Updates the shared preference related to Orbot Stop. Notifies the user about the Orbot
     * failure/stop. The notification will be sent if the user not initiated the stop.
     */
    fun stopOrbot(isInteractive: Boolean) {
        if (!isInteractive && selectedProxyType != AppConfig.ProxyType.NONE.name) {
            val notificationManager =
                context.getSystemService(VpnService.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(
                NOTIF_CHANNEL_ID_PROXY_ALERTS,
                ORBOT_SERVICE_ID,
                createNotification().build()
            )
        }
        selectedProxyType = AppConfig.ProxyType.NONE.name
        appConfig.removeAllProxies()
        persistentState.orbotConnectionStatus.postValue(false)
        context.sendBroadcast(getOrbotStopIntent())
        Logger.i(LOG_TAG_VPN, "stop orbot, remove from proxy")
    }

    /**
     * Creates notification for the failure of Orbot. Creates a new notification channel for the
     * Orbot failure update.
     */
    private fun createNotification(): NotificationCompat.Builder {

        val pendingIntent =
            getActivityPendingIntent(
                context,
                Intent(context, AppLockActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
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

        val contentTitle = context.resources.getString(R.string.lbl_action_required)
        val contentText = context.resources.getString(R.string.settings_orbot_notification_content)
        builder
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentTitle(contentTitle)
            .setContentIntent(pendingIntent)
            .setContentText(contentText)
        builder.setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
        builder.color =
            ContextCompat.getColor(context, UIUtils.getAccentColor(persistentState.theme))
        val openIntent = getOrbotOpenIntent()
        val notificationAction: NotificationCompat.Action =
            NotificationCompat.Action(
                0,
                context.resources.getString(R.string.settings_orbot_notification_action),
                openIntent
            )
        builder.addAction(notificationAction)
        builder.setAutoCancel(true)

        // Secret notifications are not shown on the lock screen.  No need for this app to show
        // there.
        // Only available in API >= 21
        builder = builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        return builder
    }

    private fun getOrbotOpenIntent(): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java)
        intent.putExtra(Constants.NOTIFICATION_ACTION, ORBOT_NOTIFICATION_ACTION_TEXT)
        return getBroadcastPendingIntent(
            context,
            ORBOT_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT,
            mutable = false
        )
    }

    private fun setOrbotMode() {
        io {
            Logger.i(LOG_TAG_VPN, "Initiate orbot start with type: $selectedProxyType")

            if (isTypeSocks5() && handleOrbotSocks5Update()) {
                appConfig.addProxy(AppConfig.ProxyType.SOCKS5, AppConfig.ProxyProvider.ORBOT)
            } else if (isTypeHttp() && handleOrbotHttpUpdate()) {
                appConfig.addProxy(AppConfig.ProxyType.HTTP, AppConfig.ProxyProvider.ORBOT)
            } else if (isTypeHttpSocks5() && handleOrbotSocks5Update() && handleOrbotHttpUpdate()) {
                appConfig.addProxy(AppConfig.ProxyType.HTTP_SOCKS5, AppConfig.ProxyProvider.ORBOT)
            } else {
                uiCtx { stopOrbot(isInteractive = true) }
            }
            persistentState.orbotConnectionStatus.postValue(false)
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
        val pMode = ProxyManager.ProxyMode.ORBOT_SOCKS5
        val endpoint = appConfig.getOrbotSocks5Endpoint()
        if (endpoint == null) {
            Logger.w(LOG_TAG_VPN, "Orbot SOCKS5 endpoint not found in database")
            return false
        }
        val id = endpoint.id
        val proxyEndpoint = constructProxy(id, pMode, socks5Ip, socks5Port)
        return if (proxyEndpoint != null) {
            appConfig.updateOrbotProxy(proxyEndpoint)
            true
        } else {
            Logger.w(LOG_TAG_VPN, "Error inserting value in proxy database")
            false
        }
    }

    private suspend fun handleOrbotHttpUpdate(): Boolean {
        if (httpsIp.isNullOrEmpty() || httpsPort == null) {
            Logger.w(LOG_TAG_VPN, "Orbot: httpsIp or httpsPort is null")
            return false
        }

        val pMode = ProxyManager.ProxyMode.ORBOT_HTTP
        // store the http address in proxyIp column of ProxyEndpoint table
        val httpAddress = constructHttpAddress(httpsIp, httpsPort)
        if (httpAddress.isNullOrEmpty()) {
            Logger.w(LOG_TAG_VPN, "Orbot: httpAddress is empty")
            return false
        }

        val endpoint = appConfig.getOrbotHttpEndpoint()
        if (endpoint == null) {
            Logger.w(LOG_TAG_VPN, "Orbot HTTP endpoint not found in database")
            return false
        }
        val id = endpoint.id
        val proxyEndpoint = constructProxy(id, pMode, httpAddress, 0)
        return if (proxyEndpoint != null) {
            appConfig.updateOrbotHttpProxy(proxyEndpoint)
            true
        } else {
            Logger.w(LOG_TAG_VPN, "Orbot: http proxy err ${httpsIp}:${httpsPort}")
            false
        }
    }

    private fun constructHttpAddress(ip: String?, port: Int?): String? {
        if (ip.isNullOrEmpty() || port == null || !isValidPort(port)) {
            Logger.w(LOG_TAG_VPN, "cannot construct http address: $ip:$port")
            return null
        }

        val proxyUrl = StringBuilder()
        // Orbot only supports http proxy
        proxyUrl.append("http://")
        proxyUrl.append(ip)
        proxyUrl.append(":")
        proxyUrl.append(port)
        return URI.create(proxyUrl.toString()).toASCIIString()
    }

    private fun constructProxy(
        id: Int,
        proxyMode: ProxyManager.ProxyMode,
        ip: String?,
        port: Int?
    ): ProxyEndpoint? {
        if (ip.isNullOrEmpty() || port == null || !isValidPort(port)) {
            Logger.w(LOG_TAG_VPN, "cannot construct proxy: $ip:$port")
            return null
        }

        return ProxyEndpoint(
            id,
            ORBOT,
            proxyMode.value,
            proxyType = DEFAULT_PROXY_TYPE,
            ORBOT_PACKAGE_NAME,
            ip,
            port,
            userName = "",
            password = "",
            isSelected = true,
            isCustom = true,
            isUDP = true,
            modifiedDataTime = 0L,
            latency = 0
        )
    }

    /** Get the data from the received intent from the Orbot and assign the values. */
    fun updateOrbotProxyData(data: Intent?) {
        socks5Port = data?.getIntExtra(EXTRA_SOCKS_PROXY_PORT, SOCKS_DEFAULT_PORT)
        httpsPort = data?.getIntExtra(EXTRA_HTTP_PROXY_PORT, HTTP_PROXY_PORT)
        socks5Ip = data?.getStringExtra(EXTRA_SOCKS_PROXY_HOST)
        httpsIp = data?.getStringExtra(EXTRA_HTTP_PROXY_HOST)
        dnsPort = data?.getIntExtra(EXTRA_DNS_PORT, 0)

        Logger.d(
            LOG_TAG_VPN,
            "OrbotHelper: new val socks5:$socks5Ip($socks5Port), http: $httpsIp($httpsPort), dns: $dnsPort"
        )
    }

    /**
     * Create a ScheduledExecutorService which will be executed after 30 sec of Orbot status
     * initiation.
     */
    private suspend fun waitForOrbot() {
        io {
            delay(TimeUnit.SECONDS.toMillis(15L))
            Logger.i(LOG_TAG_VPN, "after timeout, isOrbotUp? $isResponseReceivedFromOrbot")

            // Execute a task in the background thread after 15 sec.
            // If there is no response for the broadcast from the Orbot,
            // then the executor will execute and will disable the Orbot settings.
            // Some cases where the Orbot won't be responding -eg., force stop of application,
            // user disabled auto-start of the application.
            if (isResponseReceivedFromOrbot) {
                retryCount = 3 // reset the retry count
                return@io
            }

            retryCount--
            if (retryCount > 0) {
                Logger.i(LOG_TAG_VPN, "retrying orbot start")
                startOrbot(selectedProxyType)
            } else {
                uiCtx {
                    stopOrbot(isInteractive = false)
                    unregisterReceiver()
                }
            }
        }
    }

    fun unregisterReceiver() {
        try {
            context.unregisterReceiver(orbotStatusReceiver)
        } catch (e: IllegalArgumentException) {
            Logger.w(LOG_TAG_VPN, "orbot unregister not needed: ${e.message}")
        }
    }

    // Throw intent to start the Orbot application.
    fun openOrbotApp() {
        try {
            val launchIntent: Intent? =
                context.packageManager?.getLaunchIntentForPackage(ORBOT_PACKAGE_NAME)
            if (launchIntent != null) { // null pointer check in case package name was not found
                context.startActivity(launchIntent)
            } else {
                openOrbotAppInfo()
            }
        } catch (e: ActivityNotFoundException) {
            Logger.w(LOG_TAG_VPN, "Failure calling app info: ${e.message}", e)
        }
    }

    private fun openOrbotAppInfo() {
        val text = context.getString(R.string.orbot_app_issue)
        Utilities.showToastUiCentered(context, text, Toast.LENGTH_SHORT)
        try {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.fromParts("package", ORBOT_PACKAGE_NAME, null)
            // fix for AndroidRuntimeException: calling startActivity() from outside of an
            // activity context requires the FLAG_ACTIVITY_NEW_TASK flag
            // ref:
            // https://stackoverflow.com/questions/3918517/calling-startactivity-from-outside-of-an-activity-context
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Logger.w(LOG_TAG_VPN, "Failure calling app info: ${e.message}", e)
        }
    }

    private fun io(f: suspend () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }
}
