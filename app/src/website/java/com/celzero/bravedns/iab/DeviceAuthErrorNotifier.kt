/*
 * Copyright 2026 RethinkDNS and its authors
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
package com.celzero.bravedns.iab

import Logger
import Logger.LOG_IAB
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.celzero.bravedns.R
import com.celzero.bravedns.iab.DeviceNotRegisteredNotifier.NOTIF_CHANNEL_ID_RPN_ALERTS
import com.celzero.bravedns.ui.NotificationHandlerActivity
import com.celzero.bravedns.util.Constants.Companion.NOTIF_ID_IAB_DEVICE_AUTH_ERROR
import com.celzero.bravedns.util.Constants.Companion.NOTIF_INTENT_EXTRA_IAB_DEVICE_AUTH_ERROR_NAME
import com.celzero.bravedns.util.Constants.Companion.NOTIF_INTENT_EXTRA_IAB_DEVICE_AUTH_ERROR_VALUE
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities

/**
 * Posts a high-priority system notification whenever an API call returns HTTP 401
 * **and no UI observer is attached** to [InAppBillingHandler.serverApiErrorLiveData].
 *
 *
 * ### Channel
 * Reuses [NOTIF_CHANNEL_ID_RPN_ALERTS] ("RPN_Alerts"): the existing high-priority
 * channel already created and registered by DeviceNotRegisteredNotifier.
 */
object DeviceAuthErrorNotifier {

    private const val TAG = "DeviceAuthErrorNotifier"

    // Intent extra keys used by NotificationHandlerActivity to rebuild the Unauthorized401 error.
    const val EXTRA_OPERATION       = "auth_error_operation"
    const val EXTRA_ACCOUNT_ID      = "auth_error_account_id"
    const val EXTRA_DEVICE_ID_PREFIX = "auth_error_device_id_prefix"

    /**
     * Post (or update) the device-auth-error (HTTP 401) notification.
     *
     * @param context Application context.
     * @param error   The [ServerApiError.Unauthorized401] that triggered this call.
     * @param theme   Current app theme value (from [PersistentState.theme]) used to tint the
     *                notification accent color.
     */
    fun notify(context: Context, error: ServerApiError.Unauthorized401, theme: Int) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                    as? NotificationManager ?: run {
                Logger.e(LOG_IAB, "$TAG: NotificationManager unavailable")
                return
            }

            // Build the tap intent; routes through NotificationHandlerActivity so the
            // trampoline logic (pause state, app-lock, etc.) is respected.
            val tapIntent = Intent(context, NotificationHandlerActivity::class.java).apply {
                putExtra(NOTIF_INTENT_EXTRA_IAB_DEVICE_AUTH_ERROR_NAME,
                    NOTIF_INTENT_EXTRA_IAB_DEVICE_AUTH_ERROR_VALUE)
                putExtra(EXTRA_OPERATION,        error.operation.name)
                putExtra(EXTRA_ACCOUNT_ID,       error.accountId)
                putExtra(EXTRA_DEVICE_ID_PREFIX, error.deviceIdPrefix)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            val pendingIntent = Utilities.getActivityPendingIntent(
                context,
                tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT,
                mutable = false
            )

            // Ensure the channel exists (idempotent: safe to call on every notification)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channelName = context.getString(R.string.notif_channel_firewall_alerts)
                val channelDesc = context.getString(R.string.notif_channel_desc_firewall_alerts)
                val channel = NotificationChannel(
                    NOTIF_CHANNEL_ID_RPN_ALERTS,
                    channelName,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = channelDesc }
                notificationManager.createNotificationChannel(channel)
            }

            val title = context.getString(R.string.device_auth_error_notif_title)
            val body  = context.getString(R.string.device_auth_error_notif_body)

            val builder = NotificationCompat.Builder(context, NOTIF_CHANNEL_ID_RPN_ALERTS)
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(pendingIntent)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setColor(ContextCompat.getColor(context, UIUtils.getAccentColor(theme)))
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)

            // Fixed ID → repeated 401s update the same notification, not stack new ones
            notificationManager.notify(
                NOTIF_CHANNEL_ID_RPN_ALERTS,
                NOTIF_ID_IAB_DEVICE_AUTH_ERROR,
                builder.build()
            )

            Logger.i(LOG_IAB, "$TAG: auth-error notification posted for op=${error.operation}")
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: failed to post notification: ${e.message}", e)
        }
    }

    /**
     * Cancels any pending device-auth-error notification.
     * Called by [NotificationHandlerActivity] after successfully handling the 401 so the
     * notification is cleared from the tray.
     */
    fun cancel(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(NOTIF_CHANNEL_ID_RPN_ALERTS, NOTIF_ID_IAB_DEVICE_AUTH_ERROR)
            Logger.d(LOG_IAB, "$TAG: notification cancelled")
        } catch (e: Exception) {
            Logger.w(LOG_IAB, "$TAG: failed to cancel notification: ${e.message}")
        }
    }
}

