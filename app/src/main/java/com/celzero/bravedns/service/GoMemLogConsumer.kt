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

import Logger
import Logger.LOG_GO_LOGGER
import Logger.LOG_TAG_BUG_REPORT
import Logger.LOG_TAG_VPN
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.system.ErrnoException
import android.system.Os
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.celzero.bravedns.R
import com.celzero.bravedns.scheduler.EnhancedBugReport
import com.celzero.bravedns.service.BraveVPNService.Companion.NW_ENGINE_NOTIFICATION_ID
import com.celzero.bravedns.service.GoMemLogConsumer.Companion.SLOT_SIZE
import com.celzero.bravedns.ui.activity.AppLockActivity
import com.celzero.bravedns.util.Daemons
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils.getAccentColor
import com.celzero.bravedns.util.Utilities
import com.celzero.firestack.backend.LogConsumer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.FileDescriptor
import java.io.FileOutputStream

/**
 * Implements [LogConsumer] to drain Go runtime logs from a shared-memory file descriptor.
 *
 * Go maps a shared-memory ring buffer and calls [drain] with byte offsets [start, end) whenever
 * new log data is available; [onClose] is called when the writer is done.
 *
 * ## Buffer layout (Go contract)
 *
 * The buffer is divided into fixed **800-byte slots**. `start` and `end` are always multiples
 * of [SLOT_SIZE]. Within each slot:
 *
 * ```
 *   slot[0]          → Go log-level character ('Y'/'V'/'D'/'I'/'W'/'E'/'F'/'U')
 *   slot[1 .. NL-1]  → UTF-8 message payload (NL = index of first '\n' in the slot)
 *   slot[NL .. 799]  → garbage padding bytes MUST be ignored
 * ```
 *
 */
class GoMemLogConsumer(
    private val appContext: Context,
    private val scope: CoroutineScope,
) : LogConsumer {

    // Single-thread background dispatcher; all buffer processing is serialised here so
    // prevLogLevel and tombstoneStream need no additional synchronisation.
    private val processor = Daemons.make("goMemLog")

    // Inherited log level for continuation lines that carry no level prefix.
    // Mutated only on [processor].
    private var prevLogLevel: Logger.LoggerLevel = Logger.LoggerLevel.VERY_VERBOSE

    // Tombstone file stream opened lazily on first stacktrace slot, closed in onClose().
    // Accessed only on [processor].
    private var tombstoneStream: BufferedOutputStream? = null

    private var cachedFd: FileDescriptor? = null
    private var cachedFdInt: Int = -1

    companion object {
        private const val TAG = "GoMemLog"
        private const val WARNING_CHANNEL_ID = "warning"

        /**
         * Fixed slot size guaranteed by the Go side.
         * [drain] start/end offsets are always multiples of this value.
         */
        private const val SLOT_SIZE = 800

        /** Safety cap: never read more than this per [drain] call. */
        private const val MAX_DRAIN_BYTES = 512 * 1024

        private val NEWLINE_BYTE: Byte = '\n'.code.toByte()
    }

    /**
     * Called by go-tun when new log data is available in shared memory at [fd][start, end).
     *
     * Copies the bytes via [Os.pread] (non-seeking pread is safe for the ring-buffer pattern),
     * enqueues the raw [ByteArray] on [processor], and returns immediately.
     * Go's goroutine is therefore never blocked by log processing.
     *
     * @return number of bytes consumed; 0 on any error.
     */
    override fun drain(fd: Long, start: Long, end: Long): Long {
        if (end <= start) {
            Logger.vv(LOG_TAG_BUG_REPORT, "$TAG drain: empty range fd=$fd [$start,$end)")
            return 0L
        }

        val rawLength = (end - start).coerceAtMost(MAX_DRAIN_BYTES.toLong()).toInt()
        // Round down to the nearest complete slot, a partial slot must never be processed.
        val length = (rawLength / SLOT_SIZE) * SLOT_SIZE
        if (length == 0) {
            Logger.vv(LOG_TAG_BUG_REPORT, "$TAG drain: no complete slots fd=$fd [$start,$end) rawLen=$rawLength")
            return 0L
        }

        val fileFd = getOrCreateFdWrapper(fd.toInt()) ?: run {
            Logger.w(LOG_TAG_BUG_REPORT, "$TAG drain: failed to wrap fd=$fd")
            return 0L
        }

        val buffer = ByteArray(length)
        return try {
            val bytesRead = Os.pread(fileFd, buffer, 0, length, start)
            if (bytesRead <= 0) {
                Logger.w(LOG_TAG_BUG_REPORT, "$TAG drain: pread returned $bytesRead for fd=$fd")
                return 0L
            }
            scope.launch(processor) { processBuffer(buffer, bytesRead) }
            bytesRead.toLong()
        } catch (e: ErrnoException) {
            Logger.e(LOG_TAG_BUG_REPORT, "$TAG drain: pread errno=${e.errno} fd=$fd ($start,$end): ${e.message}", e)
            0L
        } catch (e: Exception) {
            Logger.e(LOG_TAG_BUG_REPORT, "$TAG drain: error fd=$fd ($start,$end): ${e.message}", e)
            0L
        }
    }

    /**
     * Called by go-tun when the writer is done.
     * Schedules tombstone-stream teardown on [processor] so it executes after all
     * in-flight [drain] work has finished (single-thread dispatcher guarantees FIFO order).
     */
    override fun onClose(): Boolean {
        Logger.d(LOG_TAG_BUG_REPORT, "$TAG onClose: scheduling cleanup")
        scope.launch(processor) { closeTombstoneStream() }
        return false
    }

    /**
     * Iterates over every complete 800-byte slot in [buffer] up to [bytesRead].
     * Trailing bytes that do not form a complete slot are silently discarded
     * guarantees slot alignment so this is only a last-resort safety net.
     */
    private fun processBuffer(buffer: ByteArray, bytesRead: Int) {
        var slotOffset = 0
        while (slotOffset + SLOT_SIZE <= bytesRead) {
            processSlot(buffer, slotOffset)
            slotOffset += SLOT_SIZE
        }
    }

    /**
     * Processes one 800-byte slot starting at [offset] inside [buffer].
     *
     * ```
     *   buffer[offset]             → level char
     *   buffer[offset+1 .. NL-1]   → payload bytes
     *   buffer[NL .. offset+799]   → garbage padding (ignored)
     * ```
     */
    private fun processSlot(buffer: ByteArray, offset: Int) {
        // Byte 0: Go level character.
        val levelChar = (buffer[offset].toInt() and 0xFF).toChar()
        val goLevel = Logger.LoggerLevel.fromChar(levelChar)

        val level: Logger.LoggerLevel
        if (goLevel != null) {
            level = goLevel.toLoggerLevel()
            prevLogLevel = level
        } else {
            // Unrecognised prefix treat as continuation of the previous level.
            level = prevLogLevel
        }

        // Scan for '\n' to find where real content ends; everything after it is padding.
        val slotEnd = offset + SLOT_SIZE
        var newlinePos = slotEnd // default: no '\n' found → treat full slot as content
        for (i in (offset + 1) until slotEnd) {
            if (buffer[i] == NEWLINE_BYTE) {
                newlinePos = i
                break
            }
        }

        // payload = buffer[offset+1, newlinePos)
        val payloadStart = offset + 1
        val payloadLength = newlinePos - payloadStart

        when {
            level.stacktrace() -> {
                // Write level byte + payload bytes directly no String created.
                ensureTombstoneStreamReady()
                writeBytesToTombstone(buffer, offset, newlinePos - offset)
            }
            level.user() -> {
                // Notification API requires a String; unavoidable.
                val msg = if (payloadLength > 0)
                    String(buffer, payloadStart, payloadLength, Charsets.UTF_8)
                else ""
                showNwEngineNotification(msg)
                VpnController.stop("goNotif", appContext, userInitiated = false)
            }
            else -> {
                // String created here, on the processor thread, never inside drain().
                val payload = if (payloadLength > 0)
                    String(buffer, payloadStart, payloadLength, Charsets.UTF_8)
                else ""
                Logger.goLog3(payload, level)
            }
        }
    }

    /**
     * Returns a [FileDescriptor] wrapping the raw integer [fdInt] without taking ownership.
     * The result is cached; a new wrapper is created only if [fdInt] changes between calls.
     * We must NOT close this descriptor
     */
    private fun getOrCreateFdWrapper(fdInt: Int): FileDescriptor? {
        cachedFd?.let { if (cachedFdInt == fdInt) return it }
        val fd = FileDescriptor()
        if (!FdHelper.setFdInt(fd, fdInt, TAG)) return null
        cachedFd = fd
        cachedFdInt = fdInt
        return fd
    }

    /**
     * Opens the tombstone [BufferedOutputStream] lazily on the first stacktrace slot.
     * Subsequent calls are no-ops once [tombstoneStream] is non-null.
     */
    private fun ensureTombstoneStreamReady() {
        if (tombstoneStream != null) return
        val file = EnhancedBugReport.newGoLogFile(appContext) ?: run {
            Logger.e(LOG_TAG_BUG_REPORT, "$TAG ensureTombstoneStreamReady: newGoLogFile returned null")
            return
        }
        try {
            tombstoneStream = BufferedOutputStream(FileOutputStream(file, /* append= */ true))
            Logger.d(LOG_TAG_BUG_REPORT, "$TAG ensureTombstoneStreamReady: ${file.absolutePath}")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_BUG_REPORT, "$TAG ensureTombstoneStreamReady: open failed: ${e.message}", e)
        }
    }

    /**
     * Writes [buffer][offset, offset+length) followed by a newline to the tombstone stream
     * and flushes immediately so data survives a process crash mid-session.
     * No [String] is allocated.
     */
    private fun writeBytesToTombstone(buffer: ByteArray, offset: Int, length: Int) {
        try {
            val bos = tombstoneStream ?: run {
                Logger.e(LOG_TAG_BUG_REPORT, "$TAG writeBytesToTombstone: stream null, bytes dropped")
                return
            }
            bos.write(buffer, offset, length)
            bos.write('\n'.code)
            bos.flush()
        } catch (e: Exception) {
            Logger.e(LOG_TAG_BUG_REPORT, "$TAG writeBytesToTombstone: write failed: ${e.message}", e)
        }
    }

    private fun closeTombstoneStream() {
        try { tombstoneStream?.flush() } catch (_: Exception) {}
        try { tombstoneStream?.close() } catch (_: Exception) {}
        tombstoneStream = null
    }

    private fun showNwEngineNotification(msg: String) {
        if (msg.isEmpty()) {
            Logger.e(LOG_GO_LOGGER, "$TAG empty msg with log level USR")
            return
        }
        val notificationManager =
            appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pendingIntent = Utilities.getActivityPendingIntent(
            appContext,
            Intent(appContext, AppLockActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            mutable = false
        )
        val builder = NotificationCompat.Builder(appContext, WARNING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentTitle(msg)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
        builder.color = ContextCompat.getColor(appContext, getAccentColor(Themes.SYSTEM_DEFAULT.id))
        notificationManager.notify(NW_ENGINE_NOTIFICATION_ID, builder.build())
        Logger.w(LOG_TAG_VPN, "$TAG nw eng notification: $msg")
    }
}

