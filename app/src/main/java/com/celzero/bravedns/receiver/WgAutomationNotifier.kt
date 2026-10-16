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

package com.celzero.bravedns.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.celzero.bravedns.R
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_VPN
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastO

/**
 * Surfaces wireguard automation commands that could not be carried out (see
 * [WgCommands]) as a single, always-replaced notification on its own channel.
 *
 * Design constraints:
 *  - At most one notification is ever visible for this module: every post uses
 *    the same [NOTIF_TAG] + [NOTIF_ID], so a new failure summary replaces the
 *    previous one instead of piling up.
 *  - A fully successful command clears the notification, so a stale error never
 *    outlives the problem it described.
 *  - Tapping the notification opens the app and dismisses it (autoCancel).
 *  - If notifications (or this channel) are blocked by the user, the post is
 *    silently skipped and only logged - never crashes, never retries.
 */
object WgAutomationNotifier {

    private const val TAG = "WgAutoNotif"
    private const val CHANNEL_ID = "wg_automation_alerts"

    // single fixed identity: NotificationManager replaces (and never stacks)
    // notifications posted with the same (tag, id) key
    internal const val NOTIF_TAG = "wg_automation"
    internal const val NOTIF_ID = 4242

    /**
     * Posts (or clears) the automation-failure notification for one received
     * broadcast. [action] is the raw intent action; [total] the number of ids in
     * the broadcast; [failures] the per-config failures reported by [WgCommands].
     */
    fun onCommandResult(context: Context, action: String?, total: Int, failures: List<WgCommands.Failure>) {
        if (failures.isEmpty()) {
            // everything went through; drop any stale failure notification
            clear(context)
            return
        }

        val nm = notificationManager(context) ?: run {
            Logger.w(LOG_TAG_VPN, "$TAG: no notification service, skipping failure alert")
            return
        }
        if (!canPost(context, nm)) {
            Logger.i(
                LOG_TAG_VPN,
                "$TAG: notifications or the $CHANNEL_ID channel are blocked, skipping failure alert"
            )
            return
        }

        val actionLabel = actionLabel(context, action)
        if (actionLabel == null) {
            // unknown action - nothing to report
            Logger.w(LOG_TAG_VPN, "$TAG: unknown wg action: $action, skipping alert")
            return
        }
        // plural selection keys on the total: "1 of 3 configs" is plural
        val summary = context.resources.getQuantityString(
            R.plurals.wg_automation_notif_summary, total, actionLabel, failures.size, total
        )

        val contentIntent = launchAppIntent(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentTitle(context.getString(R.string.wg_automation_notif_title))
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(buildDetails(context, summary, failures)))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
        if (contentIntent != null) {
            builder.setContentIntent(contentIntent)
        }

        runCatching { nm.notify(NOTIF_TAG, NOTIF_ID, builder.build()) }.onFailure {
            Logger.w(LOG_TAG_VPN, "$TAG: failed to post notification: ${it.message}")
        }
        Logger.i(
            LOG_TAG_VPN,
            "$TAG: posted wg automation failure alert, action: $action, failures: $failures"
        )
    }

    /** Removes the failure notification, e.g. when a later command succeeds. */
    fun clear(context: Context) {
        val nm = notificationManager(context) ?: return
        nm.cancel(NOTIF_TAG, NOTIF_ID)
    }

    /**
     * True when notifications are enabled for the app and this channel is not
     * blocked (importance NONE means the user silenced the channel).
     */
    private fun canPost(context: Context, nm: NotificationManager): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        // channel APIs below are only reached on API 26+
        if (!isAtleastO()) return true

        var channel = nm.getNotificationChannel(CHANNEL_ID)
        if (channel == null) {
            // first post (or the user deleted the channel); recreate it
            val name: CharSequence = context.getString(R.string.notif_channel_wg_automation)
            val description: String = context.getString(R.string.notif_channel_desc_wg_automation)
            val created = NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_DEFAULT)
            created.description = description
            nm.createNotificationChannel(created)
            channel = nm.getNotificationChannel(CHANNEL_ID)
        }
        return channel?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun notificationManager(context: Context): NotificationManager? {
        return context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    }

    private fun actionLabel(context: Context, action: String?): String? {
        return when (action) {
            VpnControlReceiver.ACTION_WG_START -> context.getString(R.string.wg_automation_action_start)
            VpnControlReceiver.ACTION_WG_STOP -> context.getString(R.string.wg_automation_action_stop)
            VpnControlReceiver.ACTION_WG_PAUSE -> context.getString(R.string.wg_automation_action_pause)
            VpnControlReceiver.ACTION_WG_RESUME -> context.getString(R.string.wg_automation_action_resume)
            else -> null
        }
    }

    /**
     * Compact summary in the collapsed view, per-config reasons expanded. The
     * list is capped so a large batch never turns the notification into a wall
     * of text.
     */
    private fun buildDetails(
        context: Context,
        summary: String,
        failures: List<WgCommands.Failure>
    ): String {
        val sb = StringBuilder(summary)
        val shown = failures.take(MAX_SHOWN_FAILURES)
        shown.forEach { f ->
            sb.append("\n")
            sb.append(
                context.getString(
                    R.string.wg_automation_notif_item, f.id, context.getString(f.error.msgRes)
                )
            )
        }
        val remaining = failures.size - shown.size
        if (remaining > 0) {
            sb.append("\n")
            sb.append(context.getString(R.string.wg_automation_notif_more, remaining))
        }
        return sb.toString()
    }

    private fun launchAppIntent(context: Context): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return Utilities.getActivityPendingIntent(
            context,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT,
            mutable = false
        )
    }

    private const val MAX_SHOWN_FAILURES = 4
}
