package com.celzero.bravedns.util

import Logger
import Logger.LOG_GO_LOGGER
import Logger.LOG_TAG_APP
import Logger.LOG_TAG_BUG_REPORT
import Logger.LOG_TAG_VPN
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.celzero.bravedns.R
import com.celzero.bravedns.scheduler.EnhancedBugReport
import com.celzero.bravedns.service.BraveVPNService.Companion.NW_ENGINE_NOTIFICATION_ID
import com.celzero.bravedns.service.GoCrashFileDescriptorReader
import com.celzero.bravedns.service.GoLogFileDescriptorReader
import com.celzero.bravedns.service.GoMemLogConsumer
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.activity.AppLockActivity
import com.celzero.bravedns.util.UIUtils.getAccentColor
import com.celzero.bravedns.util.Utilities.isFdroidFlavour
import com.celzero.firestack.backend.LogConsumer
import com.celzero.firestack.intra.Console
import com.celzero.firestack.intra.Intra
import kotlinx.coroutines.CoroutineScope
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

class GoReportingHandler private constructor(private val scope: CoroutineScope, private val ctx: Context): Console, KoinComponent {
    private val persistentState by inject<PersistentState>()

    companion object {
        // Safe: holds applicationContext only, not Activity/Service context
        @SuppressLint("StaticFieldLeak")
        private var instance: GoReportingHandler? = null
        private const val TAG = "GoRptHandler"
        private const val WARNING_CHANNEL_ID = "warning"

        fun initialize(scope: CoroutineScope, ctx: Context) {
            if (instance != null) {
                Logger.w(LOG_TAG_APP, "$TAG already initialized")
                return
            }

            instance = GoReportingHandler(scope, ctx.applicationContext)

            Logger.i(LOG_TAG_APP, "$TAG initialized successfully")
        }

        fun getInstance(): GoReportingHandler = instance ?: error("GoReportingHandler not initialized")
    }

    init {
        Intra.setupConsole(this)
        val crashFile = getCrashFile()
        if (crashFile == null) {
            Logger.e(LOG_TAG_BUG_REPORT, "$TAG init: failed to create crash file")
        } else {
            Logger.i(LOG_TAG_BUG_REPORT, "$TAG init: path: ${crashFile.absolutePath}")
            Intra.setCrashOutput(crashFile.absolutePath)
        }
    }

    fun getCrashFile(): File? {
        val crashLogFdReader = GoCrashFileDescriptorReader(ctx)
        return crashLogFdReader.start2()
    }

    // should never run in separate go-routine as msg is a bytearray in go and will be
    // released immediately
    override fun log(level: Int, m: String?) {
        val msg = m ?: return

        if (msg.isEmpty()) return

        val l = Logger.LoggerLevel.fromId(level) ?: Logger.LoggerLevel.NONE
        if (l.stacktrace()) {
            // disable crash logging for now
            if (false) Logger.crash(LOG_GO_LOGGER, msg) // write to in-mem db
            if (!isFdroidFlavour()) CrashReporter.recordGoCrash(msg)
            val token = if (isFdroidFlavour()) "fdroid" else persistentState.firebaseUserToken
            EnhancedBugReport.writeLogsToFile(ctx, token, msg)
        } else if (l.user()) {
            showNwEngineNotification(msg)
            // consider all the notifications from go as failure and stop the service
            VpnController.stop("goNotif", ctx, userInitiated = false)
        } else {
            Logger.goLog(msg, l)
        }
    }

    override fun logFD(p0: Long): Boolean {
        Logger.i(LOG_TAG_BUG_REPORT, "$TAG logFD: fd=$p0")
        val goLogFdReader = GoLogFileDescriptorReader(scope)
        val started = goLogFdReader.start(ctx, p0)
        if (!started) Logger.w(LOG_GO_LOGGER, "failed to start go log fd reader for fd=$p0")
        return started
    }

    override fun logMemFD(
        p0: Long,
        p1: Long,
        p2: Long
    ): LogConsumer? {
        Logger.i(LOG_TAG_BUG_REPORT, "$TAG logMemFD: fd=$p0, start=$p1, end=$p2")
        // Return a GoMemLogConsumer that will drain the shared-memory buffer Go points us at.
        // Go will call drain(fd, start, end) on the returned consumer each time new data
        // is available, and onClose() when the writer is done.
        return null//GoMemLogConsumer(ctx, scope)
    }

    private fun showNwEngineNotification(msg: String) {
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
        builder.color = ContextCompat.getColor(ctx, getAccentColor(persistentState.theme))
        notificationManager.notify(NW_ENGINE_NOTIFICATION_ID, builder.build())
        Logger.w(LOG_TAG_VPN, "nw eng notification: $msg")
    }
}
