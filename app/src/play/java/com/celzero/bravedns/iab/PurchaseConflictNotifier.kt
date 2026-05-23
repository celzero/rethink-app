/*
 * Copyright 2025 RethinkDNS and its authors
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
import com.celzero.bravedns.util.Constants.Companion.NOTIF_ID_IAB_CONFLICT
import com.celzero.bravedns.util.Constants.Companion.NOTIF_INTENT_EXTRA_IAB_CONFLICT_NAME
import com.celzero.bravedns.util.Constants.Companion.NOTIF_INTENT_EXTRA_IAB_CONFLICT_VALUE
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities

/**
 * Posts a high-priority system notification whenever an [ITcpProxy] call returns HTTP 409
 * **and no UI observer is attached** to [InAppBillingHandler.serverApiErrorLiveData].
 *
 * This covers two scenarios where the active screen cannot show [PurchaseConflictBottomSheet]:
 *  - The app is in the background (e.g., triggered by [SubscriptionCheckWorker]).
 *  - The app is in the foreground but the [ManageSubscriptionFragment] is not visible
 *    (e.g. the 409 comes from an [acknowledgePurchase] call while the purchase flow screen
 *    is shown instead of the manage screen).
 *
 * Tapping the notification launches [NotificationHandlerActivity] which trampolines to
 * [ManageSubscriptionFragment]. That fragment's [setupServerErrorObserver] re-posts the
 * [ServerApiError.Conflict409] from [InAppBillingHandler.serverApiErrorLiveData] so the
 * [PurchaseConflictBottomSheet] opens automatically, using the same UI path as when the
 * fragment is already on screen.
 *
 * ### Channel
 * Reuses [NOTIF_CHANNEL_ID_RPN_ALERTS] ("RPN_Alerts"): the existing high-priority
 * channel already created and registered by DeviceNotRegisteredNotifier.
 * No new channel is needed.
 *
 * ### Deduplication
 * Uses fixed notification ID [NOTIF_ID_IAB_CONFLICT] so repeated 409s update the same
 * notification rather than stacking multiple alerts.
 */
object PurchaseConflictNotifier {

    private const val TAG = "ConflictNotifier"

    /**
     * Post (or update) the 409 conflict notification.
     *
     * Safe to call from any thread / coroutine context, [NotificationManager.notify] is
     * thread-safe.
     *
     * @param context Application context.
     * @param error   The [ServerApiError.Conflict409] that triggered this call.
     * @param theme   Current app theme value (from [PersistentState.theme]) used to tint the
     *                notification accent color, matching the existing notification style.
     */
    fun notify(context: Context, error: ServerApiError.Conflict409, theme: Int) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as? NotificationManager ?: run {
                Logger.e(LOG_IAB, "$TAG: NotificationManager unavailable")
                return
            }

            // Build the tap intent, routes through NotificationHandlerActivity so the
            // trampoline logic (pause state, app-lock, etc.) is respected.
            val tapIntent = Intent(context, NotificationHandlerActivity::class.java).apply {
                putExtra(NOTIF_INTENT_EXTRA_IAB_CONFLICT_NAME, NOTIF_INTENT_EXTRA_IAB_CONFLICT_VALUE)
                // Pass the conflict details so the trampoline can re-post the LiveData event.
                putExtra(EXTRA_ENDPOINT,       error.endpoint)
                putExtra(EXTRA_OPERATION,      error.operation.name)
                putExtra(EXTRA_SERVER_MSG,     error.serverMessage)
                putExtra(EXTRA_ACCOUNT_ID,     error.accountId)
                putExtra(EXTRA_PURCHASE_TOKEN, error.purchaseToken)
                putExtra(EXTRA_SKU,            error.sku)
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
                val channelName = context.getString(R.string.notif_channel_rpn)
                val channelDesc = context.getString(R.string.notif_channel_desc_rpn)
                val channel = NotificationChannel(
                    NOTIF_CHANNEL_ID_RPN_ALERTS,
                    channelName,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = channelDesc }
                notificationManager.createNotificationChannel(channel)
            }

            val title = context.getString(R.string.conflict_notif_title)
            val body  = context.getString(R.string.conflict_notif_body)

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

            // Fixed ID → repeated 409s update the same notification, not stack new ones
            notificationManager.notify(NOTIF_CHANNEL_ID_RPN_ALERTS, NOTIF_ID_IAB_CONFLICT, builder.build())

            Logger.i(LOG_IAB, "$TAG: conflict notification posted for op=${error.operation}, endpoint=${error.endpoint}")
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: failed to post conflict notification: ${e.message}", e)
        }
    }

    /**
     * Cancels any pending conflict notification.
     * Called by [ManageSubscriptionFragment] after successfully handling the 409 so the
     * notification is cleared from the tray.
     */
    fun cancel(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(NOTIF_CHANNEL_ID_RPN_ALERTS, NOTIF_ID_IAB_CONFLICT)
            Logger.d(LOG_IAB, "$TAG: conflict notification cancelled")
        } catch (e: Exception) {
            Logger.w(LOG_IAB, "$TAG: failed to cancel conflict notification: ${e.message}")
        }
    }

    // Intent extra keys: mirrors PurchaseConflictBottomSheet.Companion ARG_* keys
    // so the trampoline can rebuild the Conflict409 without re-querying the server.
    const val EXTRA_ENDPOINT = "conflict_endpoint"
    const val EXTRA_OPERATION = "conflict_operation"
    const val EXTRA_SERVER_MSG = "conflict_server_msg"
    const val EXTRA_ACCOUNT_ID = "conflict_account_id"
    const val EXTRA_PURCHASE_TOKEN = "conflict_purchase_token"
    const val EXTRA_SKU = "conflict_sku"
}

