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
import com.celzero.bravedns.service.FirewallManager.NOTIF_CHANNEL_ID_FIREWALL_ALERTS
import com.celzero.bravedns.ui.NotificationHandlerActivity
import com.celzero.bravedns.util.Constants.Companion.NOTIF_ID_IAB_DEVICE_NOT_REGISTERED
import com.celzero.bravedns.util.Constants.Companion.NOTIF_INTENT_EXTRA_IAB_DEVICE_NOT_REGISTERED_NAME
import com.celzero.bravedns.util.Constants.Companion.NOTIF_INTENT_EXTRA_IAB_DEVICE_NOT_REGISTERED_VALUE
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities

/**
 * Posts a high-priority system notification when device registration fails because
 * the authoritative CID (from the entitlement payload or purchase) could not be
 * linked to a DID on the Rethink server.
 *
 * Tapping the notification launches [NotificationHandlerActivity] which trampolines
 * to [ManagePurchaseFragment].
 *
 * ### Channel
 * Uses [NOTIF_CHANNEL_ID_RPN_ALERTS]
 */
object DeviceNotRegisteredNotifier {

    private const val TAG = "DeviceNotRegisteredNotifier"

    // intent used by NotificationHandlerActivity to rebuild the error.
    const val EXTRA_ENTITLEMENT_CID = "dnr_entitlement_cid"
    const val EXTRA_STORED_CID = "dnr_stored_cid"
    const val EXTRA_DEVICE_ID_PREFIX = "dnr_device_id_prefix"
    const val NOTIF_CHANNEL_ID_RPN_ALERTS = "RPN_Alerts"

    /**
     * Post (or update) the device-not-registered notification.
     *
     * @param context Application context.
     * @param error The [ServerApiError.DeviceNotRegistered] that triggered this call.
     * @param theme Current app theme
     */
    fun notify(context: Context, error: ServerApiError.DeviceNotRegistered, theme: Int) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as? NotificationManager ?: run {
                Logger.e(LOG_IAB, "$TAG: NotificationManager unavailable")
                return
            }

            val tapIntent = Intent(context, NotificationHandlerActivity::class.java).apply {
                putExtra(NOTIF_INTENT_EXTRA_IAB_DEVICE_NOT_REGISTERED_NAME,
                    NOTIF_INTENT_EXTRA_IAB_DEVICE_NOT_REGISTERED_VALUE)
                putExtra(EXTRA_ENTITLEMENT_CID,  error.entitlementCid)
                putExtra(EXTRA_STORED_CID,        error.storedCid)
                putExtra(EXTRA_DEVICE_ID_PREFIX,  error.deviceIdPrefix)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            val pendingIntent = Utilities.getActivityPendingIntent(
                context,
                tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT,
                mutable = false
            )

            // Ensure the notification channel exists (idempotent).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channelName = context.getString(R.string.notif_channel_rpn)
                val channelDesc = context.getString(R.string.notif_channel_desc_rpn)
                val channel = NotificationChannel(
                    NOTIF_CHANNEL_ID_RPN_ALERTS,
                    channelName,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = channelDesc }
                notificationManager.createNotificationChannel(channel)
            }

            val title = context.getString(R.string.device_not_registered_notif_title)
            val body  = context.getString(R.string.device_not_registered_notif_body)

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

            // repeated failures update the same notification, not stack new ones.
            notificationManager.notify(
                NOTIF_CHANNEL_ID_RPN_ALERTS,
                NOTIF_ID_IAB_DEVICE_NOT_REGISTERED,
                builder.build()
            )

            Logger.i(LOG_IAB, "$TAG: device-not-registered notification posted " +
                "(entitlementCid=${error.entitlementCid.take(8)})")
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: failed to post notification: ${e.message}", e)
        }
    }

    /**
     * Cancels any pending device-not-registered notification.
     * Called by [ManagePurchaseFragment] after the bottom sheet is shown.
     */
    fun cancel(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(NOTIF_CHANNEL_ID_RPN_ALERTS, NOTIF_ID_IAB_DEVICE_NOT_REGISTERED)
            Logger.d(LOG_IAB, "$TAG: notification cancelled")
        } catch (e: Exception) {
            Logger.w(LOG_IAB, "$TAG: failed to cancel notification: ${e.message}")
        }
    }
}
