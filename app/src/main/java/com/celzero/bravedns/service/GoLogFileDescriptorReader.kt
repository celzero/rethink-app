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
package com.celzero.bravedns.service

import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_GO_LOGGER
import com.celzero.bravedns.util.Logger.LOG_TAG_BUG_REPORT
import com.celzero.bravedns.util.Logger.LOG_TAG_VPN
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.celzero.bravedns.R
import com.celzero.bravedns.service.BraveVPNService.Companion.NW_ENGINE_NOTIFICATION_ID
import com.celzero.bravedns.ui.activity.AppLockActivity
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils.getAccentColor
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader

/**
 * Reads Go runtime logs from a file descriptor, line by line.
 *
 * The first byte of each line is the Go log level (see [Logger.LoggerLevel.fromChar]);
 * the remainder is the message payload.  Stacktrace-level lines are dropped (crash traces
 * persist via the Console channel); all other lines are forwarded to the in-process
 * logger.
 *
 * Owns a dup() of the Go-provided fd (via [FdHelper.duplicate]) so closing this reader
 * never closes Go's write end.
 */
class GoLogFileDescriptorReader(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private var appContext: Context? = null

    @Volatile private var prevLogLevel: Logger.LoggerLevel = Logger.LoggerLevel.INFO

    companion object {
        private const val TAG = "GoLogFd"
        private const val WARNING_CHANNEL_ID = "warning"

    }

    fun start(context: Context?, fd: Long): Boolean {
        Logger.d(LOG_TAG_BUG_REPORT, "$TAG start: fd=$fd")
        val ctx = try {
            context?.applicationContext ?: context
        } catch (_: Exception) {
            context
        } ?: run {
            Logger.w(LOG_TAG_BUG_REPORT, "$TAG start: missing app context")
            return false
        }
        if (fd <= 0L) {
            Logger.w(LOG_TAG_BUG_REPORT, "$TAG start: invalid fd=$fd")
            return false
        }
        appContext = ctx

        val ownedFd = FdHelper.duplicate(fd, TAG) ?: return false
        Logger.d(LOG_TAG_BUG_REPORT, "$TAG start: fd duplicated, launching read loop")

        scope.launch(CoroutineName("goLogFd") + dispatcher) {
            readLoop(ownedFd)
        }
        return true
    }

    private fun readLoop(pfd: ParcelFileDescriptor) {
        Logger.d(LOG_TAG_BUG_REPORT, "$TAG readLoop: started")
        val reader = BufferedReader(InputStreamReader(FileInputStream(pfd.fileDescriptor)))
        try {
            var line = reader.readLine()
            while (line != null) {
                dispatchLine(line)
                line = reader.readLine()
            }
            // readLine() returning null means the write-end of the pipe was closed (EOF).
            Logger.d(LOG_TAG_BUG_REPORT, "$TAG readLoop: EOF reached")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_BUG_REPORT, "$TAG readLoop: error: ${e.message}", e)
        } finally {
            try { reader.close() } catch (_: Exception) {}
            try { pfd.close() } catch (_: Exception) {}
            Logger.d(LOG_TAG_BUG_REPORT, "$TAG readLoop: cleaned up")
        }
    }

    private fun dispatchLine(line: String) {
        if (line.isEmpty()) return

        val goLevel = Logger.LoggerLevel.fromChar(line[0])
        val level: Logger.LoggerLevel
        val payload: String

        if (goLevel != null) {
            level = goLevel.toLoggerLevel()
            prevLogLevel = level
            payload = if (line.length > 1) line.substring(1) else ""
        } else {
            // first char is not a known level prefix (maybe continuous stacktrace line)
            // use the level of the previous line so multi-line messages stay grouped.
            level = prevLogLevel
            payload = line
        }

        if (level.stacktrace()) {
            // crash traces are persisted via the Console channel (GoReportingHandler
            // writes gocrash_ files); drop the line here to avoid duplicate reports
        } else if (level.user()) {
            val ctx = appContext
            if (ctx == null) {
                Logger.w(LOG_TAG_BUG_REPORT, "$TAG dispatchLine: missing app context")
                return
            }
            showNwEngineNotification(ctx, line)
            // consider all the notifications from go as failure and stop the service
            VpnController.stop("goNotif", ctx, userInitiated = false)
        } else {
            Logger.goLog2(payload, level)
        }
    }

    private fun showNwEngineNotification(ctx: Context, msg: String) {
        if (msg.isEmpty()) {
            Logger.e(LOG_GO_LOGGER, "empty msg with log level set as user")
            return
        }

        val notificationManager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pendingIntent =
            Utilities.getActivityPendingIntent(
                ctx,
                Intent(ctx, AppLockActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                mutable = false
            )
        val builder =
            NotificationCompat.Builder(ctx, WARNING_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentTitle(msg)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
        builder.color = ContextCompat.getColor(ctx, getAccentColor(Themes.SYSTEM_DEFAULT.id))
        notificationManager.notify(NW_ENGINE_NOTIFICATION_ID, builder.build())
        Logger.w(LOG_TAG_VPN, "nw eng notification: $msg")
    }
}
