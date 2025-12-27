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

import Logger
import Logger.LOG_TAG_FIREWALL
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
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
    private const val BUBBLE_CHANNEL_ID = "firewall_bubble_channel_v2"
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
                Logger.w(LOG_TAG_FIREWALL, "Bubbles are not allowed globally")
                bubblesAllowed = false
            }
        }

        // Check if the notification channel allows bubbles
        val channel = notificationManager.getNotificationChannel(BUBBLE_CHANNEL_ID)
        if (channel != null) {
            if (!channel.canBubble()) {
                Logger.w(LOG_TAG_FIREWALL, "Bubble channel does not allow bubbles")
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
                Logger.w(LOG_TAG_FIREWALL, "Unable to update existing bubble channel: ${e.message}")
            }

            nm.getNotificationChannel(BUBBLE_CHANNEL_ID)?.let { refreshed ->
                Logger.i(
                    LOG_TAG_FIREWALL,
                    "Bubble channel exists: importance=${refreshed.importance}, canBubble=${refreshed.canBubble()}, showBadge=${refreshed.canShowBadge()}"
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
        Logger.i(LOG_TAG_FIREWALL, "Bubble notification channel created")

        nm.getNotificationChannel(BUBBLE_CHANNEL_ID)?.let { created ->
            Logger.i(
                LOG_TAG_FIREWALL,
                "Bubble channel after create: importance=${created.importance}, canBubble=${created.canBubble()}, showBadge=${created.canShowBadge()}"
            )
        }
    }

    /**
     * Create a long-lived shortcut for the bubble
     * Required for proper bubble functionality on Android 11+
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun createBubbleShortcut(context: Context) {
        val shortcutIntent = Intent(context, BubbleActivity::class.java).apply {
            action = Intent.ACTION_VIEW
        }

        // Use ShortcutInfoCompat for better compatibility
        val shortcut = ShortcutInfoCompat.Builder(context, BUBBLE_SHORTCUT_ID)
            .setShortLabel(context.getString(R.string.firewall_bubble_title))
            .setLongLabel(context.getString(R.string.firewall_bubble))
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
        Logger.i(LOG_TAG_FIREWALL, "Bubble shortcut created")
    }

    /**
     * Show the bubble notification.
     *
     * IMPORTANT (platform contract): A bubble is always backed by a notification.
     * If you don't want to see anything in the notification shade, use
     * BubbleMetadata#setSuppressNotification(true).
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

        val bubbleData = NotificationCompat.BubbleMetadata.Builder(
            bubblePendingIntent,
            bubbleIcon(context)
        )
            .setDesiredHeight(600)
            .setAutoExpandBubble(false)
            // Hide the notification in the shade when bubble is available.
            .setSuppressNotification(true)
            .build()

        val messagingStyle = NotificationCompat.MessagingStyle(
            androidx.core.app.Person.Builder()
                .setName(context.getString(R.string.app_name))
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_firewall_bubble))
                .build()
        ).setConversationTitle(context.getString(R.string.firewall_bubble_title))

        messagingStyle.addMessage(
            context.getString(R.string.firewall_bubble_text),
            System.currentTimeMillis(),
            androidx.core.app.Person.Builder().setName(context.getString(R.string.app_name)).build()
        )

        val builder = NotificationCompat.Builder(context, BUBBLE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentTitle(context.getString(R.string.firewall_bubble_title))
            .setContentText(context.getString(R.string.firewall_bubble_text))
            .setStyle(messagingStyle)
            .setShortcutId(BUBBLE_SHORTCUT_ID)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setShowWhen(true)
            .setContentIntent(bubblePendingIntent)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "Disable", disableIntent)
            .setBubbleMetadata(bubbleData)

        // If not eligible, the system will not bubble. Provide a direct route to the correct
        // settings surface. We do NOT want a normal notification in that state, but without
        // an on-screen affordance the user can't fix it. This action is the minimal compliant UX.
        if (!eligible) {
            builder.addAction(0, "Enable bubbles", buildEnableBubblesPendingIntent(context))
            // Do NOT suppress the notification if it can't bubble, otherwise user sees nothing
            // and can't reach settings.
            val bubbleDataUnsuppressed = NotificationCompat.BubbleMetadata.Builder(
                bubblePendingIntent,
                bubbleIcon(context)
            )
                .setDesiredHeight(600)
                .setAutoExpandBubble(false)
                .setSuppressNotification(false)
                .build()
            builder.setBubbleMetadata(bubbleDataUnsuppressed)
        }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(BUBBLE_NOTIFICATION_ID, builder.build())

        Logger.i(LOG_TAG_FIREWALL, "Bubble notification posted (eligible=$eligible)")
        return eligible
    }

    /**
     * Update the bubble notification with current blocked apps count
     * This updates the notification message to show current blocked apps count
     *
     * @param persistentState Optional PersistentState to disable bubble if not allowed
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun updateBubble(
        context: Context,
        blockedCount: Int,
        persistentState: PersistentState? = null
    ) {
        try {
            if (!areBubblesSupported()) {
                Logger.w(LOG_TAG_FIREWALL, "Bubbles not supported")
                persistentState?.let {
                    if (it.firewallBubbleEnabled) {
                        it.firewallBubbleEnabled = false
                        Logger.i(LOG_TAG_FIREWALL, "Bubble feature disabled in settings (not supported)")
                    }
                }
                return
            }

            // Do not auto-disable the feature if bubbles are not allowed.
            // User explicitly wants bubble-only UX; if system disallows, we just can't show it.
            // Keep the toggle state unchanged.

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
                .addAction(0, "Disable", disableIntent)
                .build()

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(BUBBLE_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_FIREWALL, "Error updating bubble: ${e.message}", e)
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
            Logger.i(LOG_TAG_FIREWALL, "Bubble shortcut removed")

            // Do NOT delete the channel here.
            // Deleting and recreating channels makes OEM Settings UIs flaky and can hide "Allow bubbles"
            // again. Also, users may have explicitly enabled bubbles on the channel; deleting loses that.

            Logger.i(LOG_TAG_FIREWALL, "Bubble state reset")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_FIREWALL, "Error resetting bubble state: ${e.message}", e)
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
            "Bubble eligibility: channelId=$BUBBLE_CHANNEL_ID, importance=${channel.importance}, canBubble=${channel.canBubble()}, showBadge=${channel.canShowBadge()}"
        )

        if (channel.importance < NotificationManager.IMPORTANCE_HIGH) {
            Logger.w(LOG_TAG_FIREWALL, "Bubble ineligible: channel importance too low")
        }
        if (!channel.canBubble()) {
            Logger.w(LOG_TAG_FIREWALL, "Bubble ineligible: channel bubbles disabled")
            Logger.w(
                LOG_TAG_FIREWALL,
                "To enable: Settings → Notifications → ${context.getString(R.string.firewall_bubble_channel_name)} → Allow bubbles"
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && globalAllowed == false) {
            Logger.w(LOG_TAG_FIREWALL, "Bubble ineligible: bubbles disabled globally")
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun buildBubbleSettingsIntent(context: Context): Intent {
        // Prefer the system's dedicated bubble settings surface when available.
        // On Android 12+ this takes the user to: App notifications -> Bubbles.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val bubbleIntent = Intent(Settings.ACTION_APP_NOTIFICATION_BUBBLE_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (bubbleIntent.resolveActivity(context.packageManager) != null) {
                return bubbleIntent
            }
        }

        // Android 10/11: best available is channel settings; bubble toggle lives there.
        return Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, BUBBLE_CHANNEL_ID)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * NOTE: Do NOT start settings Activities from a background context/service.
     * That violates modern background launch limits and causes "random" navigation.
     * Instead, we surface a notification action to take the user to the right settings.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun buildEnableBubblesPendingIntent(context: Context): PendingIntent {
        val intent = buildBubbleSettingsIntent(context).apply {
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
     * Intent to open the best available system UI to enable bubbles.
     * - Android 12+: App notification bubble settings (global per-app bubble toggle)
     * - Android 10/11: Channel notification settings (channel-level "Allow bubbles")
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun buildEnableBubblesIntent(context: Context): Intent {
        return buildBubbleSettingsIntent(context)
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
            Logger.w(LOG_TAG_FIREWALL, "Failed to create adaptive bubble icon: ${e.message}")
            IconCompat.createWithResource(context, R.drawable.ic_firewall_bubble)
        }
    }
}
