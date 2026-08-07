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
package com.celzero.bravedns.util

import com.celzero.bravedns.util.Logger.LOG_TAG_FIREWALL
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import com.celzero.bravedns.R
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.activity.BubbleActivity

/**
 * BubbleHelper - Implements Android's official Bubble API
 *
 * Based on: https://developer.android.com/develop/ui/views/notifications/bubbles
 *
 * This helper creates proper bubble notifications using NotificationCompat.BubbleMetadata
 * instead of custom overlay windows. Bubbles are a system-supported feature that allows
 * content to appear in floating windows over other apps.
 *
 * Requirements:
 * - Android 10 (API 29) or higher
 * - User must enable bubbles in system settings
 * - Notification channel must allow bubbles
 * - App must create a conversation-style notification
 */
object BubbleHelper {
    // Bump the channel id to escape persisted OEM/user channel state where canBubble remains false.
    // Once a NotificationChannel exists, many attributes and the effective canBubble are controlled
    // by the system/user and can get stuck. A new channel id gives a fresh start.
    private const val BUBBLE_CHANNEL_ID = "firewall_bubble_channel"
    private const val BUBBLE_NOTIFICATION_ID = 1234
    private const val BUBBLE_SHORTCUT_ID = "firewall_bubble_shortcut"

    /**
     * Check if bubbles are supported on this device
     */
    fun areBubblesSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    /**
     * Check if the user has allowed bubbles for this app
     * Note: This is different from overlay permission
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun areBubblesAllowed(context: Context): Boolean {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        var bubblesAllowed = true

        // Check if bubbles are enabled globally (API 31+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            @Suppress("DEPRECATION")
            if (!notificationManager.areBubblesAllowed()) {
                Logger.w(LOG_TAG_FIREWALL, "bubbles are not allowed globally")
                bubblesAllowed = false
            }
        }

        // Check if the notification channel allows bubbles
        val channel = notificationManager.getNotificationChannel(BUBBLE_CHANNEL_ID)
        if (channel != null) {
            if (!channel.canBubble()) {
                Logger.w(LOG_TAG_FIREWALL, "bubble channel does not allow bubbles")
                bubblesAllowed = false
            }
        }

        return bubblesAllowed
    }

    /**
     * Create the notification channel for bubbles.
     *
     * Important:
     * - If this channel already exists, Android will ignore most property changes.
     * - So we must create it correctly the first time, and if it exists but can't bubble,
     *   we should *guide the user* to settings instead of trying to delete/recreate.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun createBubbleNotificationChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val name = context.getString(R.string.firewall_bubble_channel_name)
        val description = context.getString(R.string.firewall_bubble_channel_desc)

        val existing = nm.getNotificationChannel(BUBBLE_CHANNEL_ID)
        if (existing != null) {
            try {
                existing.setAllowBubbles(true)
                existing.setShowBadge(true)
                nm.createNotificationChannel(existing)
            } catch (e: Exception) {
                Logger.w(LOG_TAG_FIREWALL, "unable to update existing bubble channel: ${e.message}")
            }

            nm.getNotificationChannel(BUBBLE_CHANNEL_ID)?.let { refreshed ->
                Logger.i(
                    LOG_TAG_FIREWALL,
                    "bubble channel exists: importance=${refreshed.importance}, canBubble=${refreshed.canBubble()}, showBadge=${refreshed.canShowBadge()}"
                )
            }
            return
        }

        val channel = NotificationChannel(
            BUBBLE_CHANNEL_ID,
            name,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            this.description = description
            setAllowBubbles(true)
            setShowBadge(true)
        }

        nm.createNotificationChannel(channel)
        Logger.i(LOG_TAG_FIREWALL, "bubble notification channel created")

        nm.getNotificationChannel(BUBBLE_CHANNEL_ID)?.let { created ->
            Logger.i(
                LOG_TAG_FIREWALL,
                "bubble channel after create: importance=${created.importance}, canBubble=${created.canBubble()}, showBadge=${created.canShowBadge()}"
            )
        }
    }

    /**
     * Create a long-lived shortcut for the bubble
     * Required for proper bubble functionality on Android 11+
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun createBubbleShortcut(context: Context) {
        if (isBubbleShortcutExists(context)) {
            Logger.v(LOG_TAG_FIREWALL, "bubble shortcut already exists")
            return
        }

        val shortcutIntent = Intent(context, BubbleActivity::class.java).apply {
            action = Intent.ACTION_VIEW
        }

        // Use ShortcutInfoCompat for better compatibility
        val shortcut = ShortcutInfoCompat.Builder(context, BUBBLE_SHORTCUT_ID)
            .setShortLabel(context.getString(R.string.firewall_bubble_title))
            .setLongLabel(context.getString(R.string.firewall_bubble_title))
            .setIsConversation()
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_firewall_bubble))
            .setIntent(shortcutIntent)
            .setPerson(
                androidx.core.app.Person.Builder()
                    .setName(context.getString(R.string.firewall_bubble_title))
                    .setIcon(IconCompat.createWithResource(context, R.drawable.ic_firewall_bubble))
                    .build()
            )
            .setLongLived(true) // Required for bubbles
            .build()

        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
        Logger.i(LOG_TAG_FIREWALL, "bubble shortcut created")
    }

    private fun isBubbleShortcutExists(context: Context): Boolean {
        return try {
            ShortcutManagerCompat.getDynamicShortcuts(context).any { it.id == BUBBLE_SHORTCUT_ID }
        } catch (e: Exception) {
            Logger.w(LOG_TAG_FIREWALL, "unable to query dynamic shortcuts: ${e.message}")
            false
        }
    }

    fun isNotificationPermissionGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun buildBubbleDeletePendingIntent(context: Context): PendingIntent {
        return PendingIntent.getBroadcast(
            context,
            4,
            Intent(context, com.celzero.bravedns.receiver.BubbleDismissReceiver::class.java).apply {
                action = com.celzero.bravedns.receiver.BubbleDismissReceiver.ACTION_BUBBLE_DISMISSED
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Show the bubble notification.
     *
     * Bubble is always backed by a notification.
     * We always call BubbleMetadata#setSuppressNotification(true); the system then
     * hides the shade entry iff it is actually rendering the bubble, and surfaces
     * the notification in the shade as a fallback prompt only when it cannot
     * bubble (e.g. permission missing, channel disallowed).
     *
     * To avoid shade-entry flicker on VPN restart / observer ticks, this method
     * short-circuits and returns true when the bubble notification is already
     * active and the system is currently eligible to bubble.
     *
     * @return true if the bubble is eligible / live (system allows bubbles),
     *         false otherwise. When false, the notification that was just posted
     *         serves as the fallback permission prompt and stays visible until
     *         the user grants permission (which is detected on the next call,
     *         e.g. on VPN restart or toggle change).
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun showBubble(context: Context, persistentState: PersistentState? = null): Boolean {
        if (!areBubblesSupported()) {
            Logger.w(LOG_TAG_FIREWALL, "Bubbles not supported on this Android version")
            persistentState?.let {
                if (it.firewallBubbleEnabled) {
                    it.firewallBubbleEnabled = false
                    Logger.i(LOG_TAG_FIREWALL, "Bubble feature disabled in settings (not supported)")
                }
            }
            return false
        }

        // Android 13+ requires the POST_NOTIFICATIONS permission to show any notification,
        // including bubbles. The UI is responsible for requesting the permission; here we
        // just refuse to post if it is missing.
        if (!isNotificationPermissionGranted(context)) {
            Logger.w(LOG_TAG_FIREWALL, "Notification permission not granted; cannot show bubble")
            return false
        }

        // if a bubble notification is already live AND we are currently eligible, do not re-post.
        // Re-posting on every VPN restart / observer tick causes the shade entry to flicker back
        // into view on some OEMs even when the bubble is functioning correctly. The existing live
        // notification will continue to be updated by updateBubble(); no need to overwrite it here
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val alreadyActive = try {
            nm.activeNotifications.any { it.id == BUBBLE_NOTIFICATION_ID }
        } catch (e: Exception) {
            Logger.w(LOG_TAG_FIREWALL, "unable to query active notifications: ${e.message}")
            false
        }
        if (alreadyActive && isBubbleEligible(context)) {
            Logger.i(LOG_TAG_FIREWALL, "bubble already active and eligible; skipping re-post")
            return true
        }

        createBubbleNotificationChannel(context)
        createBubbleShortcut(context)

        val eligible = isBubbleEligible(context)
        logBubbleEligibility(context)

        val bubbleIntent = Intent(context, BubbleActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val bubblePendingIntent = PendingIntent.getActivity(
            context,
            0,
            bubbleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val disableIntent = PendingIntent.getBroadcast(
            context,
            2,
            Intent(context, com.celzero.bravedns.receiver.BubbleDismissReceiver::class.java).apply {
                action = com.celzero.bravedns.receiver.BubbleDismissReceiver.ACTION_BUBBLE_DISABLE
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val deleteIntent = buildBubbleDeletePendingIntent(context)

        // ALWAYS request suppression and let the platform decide. Per the Bubble API
        // contract, when setSuppressNotification(true) is set the system hides the shade
        // entry iff it is actually rendering the bubble; if it cannot render the bubble
        // (e.g. permission missing, channel disallowed), the notification surfaces in the
        // shade as the fallback prompt. Coupling suppression to our own `eligible` check
        // is both redundant and brittle: any OEM / channel-state quirk that makes
        // `canBubble()` or `importance` report falsey would otherwise leak the shade
        // entry alongside a perfectly functional bubble.
        // On Android 10 the platform ignores suppression, so a shade entry is unavoidable
        // there; we still make it dismissible via the delete intent.
        val bubbleData = NotificationCompat.BubbleMetadata.Builder(
            bubblePendingIntent,
            bubbleIcon(context)
        )
            .setDesiredHeight(600)
            .setAutoExpandBubble(false)
            .setSuppressNotification(true)
            .build()

        val messagingStyle = NotificationCompat.MessagingStyle(
            androidx.core.app.Person.Builder()
                .setName(context.getString(R.string.app_name))
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_firewall_bubble))
                .build()
        ).setConversationTitle(context.getString(R.string.firewall_bubble_title))

        // Use a different message text when the notification is a fallback prompt
        // rather than an actual bubble.
        val messageText = if (eligible) {
            context.getString(R.string.firewall_bubble_text)
        } else {
            val channel = nm.getNotificationChannel(BUBBLE_CHANNEL_ID)
            if (channel != null && channel.canBubble() && channel.importance < NotificationManager.IMPORTANCE_HIGH) {
                context.getString(R.string.firewall_bubble_enable_importance_prompt_text)
            } else {
                context.getString(R.string.firewall_bubble_enable_prompt_text)
            }
        }

        messagingStyle.addMessage(
            messageText,
            System.currentTimeMillis(),
            androidx.core.app.Person.Builder().setName(context.getString(R.string.app_name)).build()
        )

        val builder = NotificationCompat.Builder(context, BUBBLE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentTitle(context.getString(R.string.firewall_bubble_title))
            .setContentText(messageText)
            .setStyle(messagingStyle)
            .setShortcutId(BUBBLE_SHORTCUT_ID)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setShowWhen(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setBubbleMetadata(bubbleData)
            .setDeleteIntent(deleteIntent) // Add delete intent to both cases to handle dismissal

        if (eligible) {
            // Actual bubble: content opens BubbleActivity, swipe/delete disables feature.
            builder.setContentIntent(bubblePendingIntent)
            builder.addAction(0, context.getString(R.string.firewall_bubble_action_disable), disableIntent)
        } else {
            // Fallback prompt: content + "Enable bubbles" action open settings.
            val enableBubblesPendingIntent = buildEnableBubblesPendingIntent(context)
            builder.setContentIntent(enableBubblesPendingIntent)
            builder.addAction(
                0,
                context.getString(R.string.firewall_bubble_action_enable_bubbles),
                enableBubblesPendingIntent
            )
            // Also add a explicit dismiss action that turns off the toggle
            builder.addAction(
                0,
                context.getString(R.string.firewall_bubble_action_dismiss),
                disableIntent
            )
        }

        try {
            nm.notify(BUBBLE_NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            Logger.e(LOG_TAG_FIREWALL, "err posting bubble notification: ${e.message}", e)
            return false
        }

        Logger.i(LOG_TAG_FIREWALL, "bubble notification posted (eligible?$eligible)")
        return eligible
    }

    /**
     * Update the bubble notification with current blocked apps count.
     *
     * This is called by the VPN service while the bubble is active. If the bubble
     * notification is no longer active (e.g. the user swiped it away) or if the
     * system has become ineligible, we stop updating rather than resurrecting a
     * stale notification.
     *
     * @param persistentState Optional PersistentState to disable bubble if not supported
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun updateBubble(
        context: Context,
        blockedCount: Int,
        persistentState: PersistentState? = null
    ) {
        try {
            if (!areBubblesSupported()) {
                Logger.w(LOG_TAG_FIREWALL, "bubbles not supported")
                persistentState?.let {
                    if (it.firewallBubbleEnabled) {
                        it.firewallBubbleEnabled = false
                        Logger.i(LOG_TAG_FIREWALL, "bubble feature disabled in settings (not supported)")
                    }
                }
                return
            }

            if (!isNotificationPermissionGranted(context)) {
                Logger.w(LOG_TAG_FIREWALL, "notification permission not granted; cannot update bubble")
                return
            }

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Check if the notification is still active before updating.
            // If the user has dismissed the bubble, we should not resurrect it here.
            val isActive = nm.activeNotifications.any { it.id == BUBBLE_NOTIFICATION_ID }
            if (!isActive) {
                Logger.v(LOG_TAG_FIREWALL, "bubble notification no longer active; skipping update")
                return
            }

            if (!isBubbleEligible(context)) {
                Logger.w(LOG_TAG_FIREWALL, "bubble no longer eligible; stopping updates")
                dismissBubble(context)
                return
            }

            val bubbleIntent = Intent(context, BubbleActivity::class.java).apply {
                action = Intent.ACTION_VIEW
            }

            val bubblePendingIntent = PendingIntent.getActivity(
                context,
                0,
                bubbleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

            val disableIntent = PendingIntent.getBroadcast(
                context,
                2,
                Intent(context, com.celzero.bravedns.receiver.BubbleDismissReceiver::class.java).apply {
                    action = com.celzero.bravedns.receiver.BubbleDismissReceiver.ACTION_BUBBLE_DISABLE
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val bubbleData = NotificationCompat.BubbleMetadata.Builder(
                bubblePendingIntent,
                bubbleIcon(context)
            )
                .setDesiredHeight(600)
                .setAutoExpandBubble(false)
                .setSuppressNotification(true)
                .build()

            val messagingStyle = NotificationCompat.MessagingStyle(
                androidx.core.app.Person.Builder()
                    .setName(context.getString(R.string.app_name))
                    .setIcon(IconCompat.createWithResource(context, R.drawable.ic_firewall_bubble))
                    .build()
            ).setConversationTitle(context.getString(R.string.firewall_bubble_title))

            // Keep message stable; do not show inflated counts.
            val message = if (blockedCount > 0) {
                "Recently blocked: $blockedCount"
            } else {
                context.getString(R.string.firewall_bubble_text)
            }

            messagingStyle.addMessage(
                message,
                System.currentTimeMillis(),
                androidx.core.app.Person.Builder().setName(context.getString(R.string.app_name)).build()
            )

            val notification = NotificationCompat.Builder(context, BUBBLE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentTitle(context.getString(R.string.firewall_bubble_title))
                .setContentText(message)
                .setStyle(messagingStyle)
                .setBubbleMetadata(bubbleData)
                .setShortcutId(BUBBLE_SHORTCUT_ID)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setShowWhen(true)
                .setContentIntent(bubblePendingIntent)
                .setAutoCancel(false)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOnlyAlertOnce(true)
                .setDeleteIntent(buildBubbleDeletePendingIntent(context))
                .addAction(0, context.getString(R.string.firewall_bubble_action_disable), disableIntent)
                .build()

            nm.notify(BUBBLE_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_FIREWALL, "err updating bubble: ${e.message}")
        }
    }


    /**
     * Dismiss the bubble notification
     */
    fun dismissBubble(context: Context) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(BUBBLE_NOTIFICATION_ID)
        Logger.i(LOG_TAG_FIREWALL, "Bubble dismissed")
    }

    /**
     * Reset bubble state.
     *
     * NOTE: We intentionally delete the channel here because NotificationChannel importance/
     * bubble-allowance are immutable once created. If the channel was ever created with
     * bubbles disabled (or downgraded by the user/OEM), the only deterministic way to get back
     * to a known-good configuration is for the user to disable the feature (this reset) and
     * then re-enable it, which will recreate the channel.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun resetBubbleState(context: Context) {
        try {
            dismissBubble(context)

            // Remove the dynamic shortcut
            ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(BUBBLE_SHORTCUT_ID))
            Logger.i(LOG_TAG_FIREWALL, "bubble shortcut removed")

            // Do NOT delete the channel here.
            // Deleting and recreating channels makes OEM Settings UIs flaky and can hide "Allow bubbles"
            // again. Also, users may have explicitly enabled bubbles on the channel; deleting loses that.

            Logger.i(LOG_TAG_FIREWALL, "bubble state reset")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_FIREWALL, "err resetting bubble state: ${e.message}")
        }
    }

    /**
     * Bubble eligibility is NOT the same as "supported".
     * For deterministic behavior we treat bubble as eligible only when:
     * - API >= 29
     * - channel exists and can bubble
     * - (API 31+) bubbles are allowed globally
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun isBubbleEligible(context: Context): Boolean {
        if (!areBubblesSupported()) return false
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            @Suppress("DEPRECATION")
            if (!nm.areBubblesAllowed()) return false
        }

        val channel = nm.getNotificationChannel(BUBBLE_CHANNEL_ID) ?: return false

        // NOTE: importance is immutable after creation;
        // canBubble is user/OEM controlled and must be respected.
        return channel.canBubble() && channel.importance >= NotificationManager.IMPORTANCE_HIGH
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun logBubbleEligibility(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = nm.getNotificationChannel(BUBBLE_CHANNEL_ID)

        val globalAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            @Suppress("DEPRECATION")
            nm.areBubblesAllowed()
        } else {
            null
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Logger.i(LOG_TAG_FIREWALL, "Bubble eligibility: globalAllowed=$globalAllowed")
        }

        if (channel == null) {
            Logger.w(LOG_TAG_FIREWALL, "Bubble eligibility: channel is null")
            return
        }

        Logger.i(
            LOG_TAG_FIREWALL,
            "bubble eligibility: channelId=$BUBBLE_CHANNEL_ID, importance=${channel.importance}, canBubble=${channel.canBubble()}, showBadge=${channel.canShowBadge()}"
        )

        if (channel.importance < NotificationManager.IMPORTANCE_HIGH) {
            Logger.w(LOG_TAG_FIREWALL, "bubble ineligible: channel importance too low")
        }
        if (!channel.canBubble()) {
            Logger.w(LOG_TAG_FIREWALL, "bubble ineligible: channel bubbles disabled")
            Logger.w(
                LOG_TAG_FIREWALL,
                "to enable: settings → notifications → ${context.getString(R.string.firewall_bubble_channel_name)} → allow bubbles"
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && globalAllowed == false) {
            Logger.w(LOG_TAG_FIREWALL, "bubble ineligible: bubbles disabled globally")
        }
    }

    /**
     * NOTE: Do NOT start settings Activities from a background context/service.
     * That violates modern background launch limits and causes "random" navigation.
     * Instead, we surface a notification action to take the user to the right settings.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun buildEnableBubblesPendingIntent(context: Context): PendingIntent {
        val intent = buildEnableBubblesIntent(context).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return PendingIntent.getActivity(
            context,
            3,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Opens the system settings page responsible for blocking bubbles.
     *
     * On Android 12+:
     *   - If bubbles are globally disabled → open ACTION_APP_NOTIFICATION_BUBBLE_SETTINGS
     *   - If the channel doesn't allow bubbles or importance < HIGH → open channel settings
     * On Android 10/11 → always channel settings (the only surface with bubble toggle + importance)
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun buildEnableBubblesIntent(context: Context): Intent {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = nm.getNotificationChannel(BUBBLE_CHANNEL_ID)

        // Android 12+: if global bubbles are disabled, open the per-app bubble settings page.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            @Suppress("DEPRECATION")
            if (!nm.areBubblesAllowed()) {
                return Intent(Settings.ACTION_APP_NOTIFICATION_BUBBLE_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        }

        // If the channel doesn't allow bubbles or its importance is too low,
        // open the channel notification settings (has both "Allow bubbles" and importance).
        if (channel == null || !channel.canBubble() || channel.importance < NotificationManager.IMPORTANCE_HIGH) {
            return Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                putExtra(Settings.EXTRA_CHANNEL_ID, BUBBLE_CHANNEL_ID)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        // Fallback (shouldn't reach here if called when ineligible).
        return Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, BUBBLE_CHANNEL_ID)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun bubbleIcon(context: Context): IconCompat {
        // System warning: bubbles prefer TYPE_URI or TYPE_URI_ADAPTIVE_BITMAP.
        // Use an adaptive icon bitmap when possible.
        return try {
            val drawable = androidx.appcompat.content.res.AppCompatResources.getDrawable(
                context,
                R.drawable.ic_firewall_bubble
            )
            if (drawable != null) {
                val bmp = drawable.toBitmap(
                    width = 128,
                    height = 128,
                    config = android.graphics.Bitmap.Config.ARGB_8888
                )
                IconCompat.createWithAdaptiveBitmap(bmp)
            } else {
                IconCompat.createWithResource(context, R.drawable.ic_firewall_bubble)
            }
        } catch (e: Exception) {
            Logger.w(LOG_TAG_FIREWALL, "err creating adaptive bubble icon: ${e.message}")
            IconCompat.createWithResource(context, R.drawable.ic_firewall_bubble)
        }
    }
}
