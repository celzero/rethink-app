/*
 * Copyright 2024 RethinkDNS and its authors
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
package com.celzero.bravedns.service

import Logger
import Logger.LOG_TAG_VPN
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.celzero.bravedns.R
import com.celzero.bravedns.receiver.NotificationActionReceiver
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.util.NotificationActionType
import com.celzero.bravedns.util.Utilities.isAtleastO
import com.celzero.bravedns.util.Utilities.isAtleastU
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages all VPN notification operations.
 * Extracted from BraveVPNService for better separation of concerns.
 */
class VpnNotificationManager(
    private val context: Context,
    private val persistentState: PersistentState
) {
    companion object {
        const val SERVICE_ID = 1
        const val MEMORY_NOTIFICATION_ID = 29001
        const val NW_ENGINE_NOTIFICATION_ID = 29002
        const val NOTIF_ID_ACCESSIBILITY_FAILURE = 104

        private const val MAIN_CHANNEL_ID = "vpn"
        private const val WARNING_CHANNEL_ID = "warning"

        private const val TAG = "VpnNotifMgr"
    }

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    
    private val _connectionState = MutableStateFlow(BraveVPNService.State.NEW)
    val connectionState: StateFlow<BraveVPNService.State> = _connectionState.asStateFlow()

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (!isAtleastO()) return

        val mainChannel = NotificationChannel(
            MAIN_CHANNEL_ID,
            context.getString(R.string.notif_channel_vpn_notification),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notif_channel_desc_vpn_notification)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(mainChannel)

        val warningChannel = NotificationChannel(
            WARNING_CHANNEL_ID,
            context.getString(R.string.notif_channel_vpn_failure),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notif_channel_desc_vpn_failure)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(warningChannel)
    }

    fun updateConnectionState(state: BraveVPNService.State) {
        _connectionState.value = state
    }

    fun buildNotification(): Notification {
        return NotificationCompat.Builder(context, MAIN_CHANNEL_ID).apply {
            setSmallIcon(R.drawable.ic_notification_icon)
            setContentTitle(context.getString(R.string.app_name))
            setContentText(getNotificationContentText())
            setOngoing(true)
            setOnlyAlertOnce(true)
            setShowWhen(false)
            priority = NotificationCompat.PRIORITY_LOW
            color = ContextCompat.getColor(context, getAccentColor())
            setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            
            setContentIntent(createContentIntent())
            
            setStyle(NotificationCompat.BigTextStyle().bigText(getNotificationContentText()))
        }.build()
    }

    private fun getNotificationContentText(): String {
        return when (_connectionState.value) {
            BraveVPNService.State.WORKING -> context.getString(R.string.hybrid_mode_notification_title)
            BraveVPNService.State.PAUSED -> context.getString(R.string.pause_mode_notification_title)
            BraveVPNService.State.NEW -> context.getString(R.string.lbl_starting)
            else -> context.getString(R.string.hybrid_mode_notification_title)
        }
    }

    private fun createContentIntent(): PendingIntent {
        val intent = Intent(context, HomeScreenActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun showNotification() {
        notificationManager.notify(SERVICE_ID, buildNotification())
    }

    fun dismissNotification(id: Int = SERVICE_ID) {
        notificationManager.cancel(id)
    }

    fun startForegroundSafely(service: android.app.Service): Boolean {
        return try {
            if (isAtleastU()) {
                ServiceCompat.startForeground(
                    service,
                    SERVICE_ID,
                    buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                service.startForeground(SERVICE_ID, buildNotification())
            }
            true
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG startForeground failed", e)
            false
        }
    }

    private fun getAccentColor(): Int {
        return com.celzero.bravedns.util.UIUtils.getAccentColor(persistentState.theme)
    }
}
