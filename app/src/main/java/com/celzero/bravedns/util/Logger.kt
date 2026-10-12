/*
 * Copyright 2021 RethinkDNS and its authors
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

import android.app.Application
import android.util.Log
import com.celzero.bravedns.database.ConsoleLog
import com.celzero.bravedns.database.RpnLog
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object Logger : KoinComponent {
    private val persistentState by inject<PersistentState>()
    private val application by inject<Application>()

    private var _logLevel: Long? = null
    private var logLevel: Long
        get() {
            if (_logLevel == null) {
                _logLevel = try {
                    persistentState.goLoggerLevel
                } catch (_: Exception) {
                    // Fallback for tests or when Koin is not initialized
                    LoggerLevel.ERROR.id
                }
            }
            return _logLevel!!
        }
        set(value) {
            _logLevel = value
        }

    var uiLogLevel = LoggerLevel.ERROR.id

    const val LOG_TAG_APP_UPDATE = "NonStoreAppUpdater"
    const val LOG_TAG_VPN = "RethinkDnsVpn"
    const val LOG_TAG_CONNECTION = "ConnectivityEvents"
    const val LOG_TAG_DNS = "DnsManager"
    const val LOG_TAG_FIREWALL = "FirewallManager"
    const val LOG_BATCH_LOGGER = "BatchLogger"
    const val LOG_TAG_APP_DB = "AppDatabase"
    const val LOG_TAG_DOWNLOAD = "DownloadManager"
    const val LOG_TAG_UI = "RethinkUI"
    const val LOG_TAG_SCHEDULER = "JobScheduler"
    const val LOG_TAG_BUG_REPORT = "BugReport"
    const val LOG_TAG_BACKUP_RESTORE = "BackupRestore"
    const val LOG_PROVIDER = "BlocklistProvider"
    const val LOG_TAG_PROXY = "ProxyLogs"
    const val LOG_QR_CODE = "QrCodeFromFileScanner"
    const val LOG_GO_LOGGER = "GoLog"
    const val LOG_GO_LOGGER_V2 = "GoLogV2"
    const val LOG_GO_LOGGER_V1 = "GoLogV1"
    const val LOG_TAG_APP_OPS = "AppOpsService"
    const val LOG_IAB = "InAppBilling"
    const val LOG_FIREBASE = "FirebaseErrorReporting"
    const val LOG_TAG_APP = "ExceptionHandler"
    const val LOG_OKHTTP = "OkHttp"

    const val WIRELOG_FILE_NAME = "wirelogs.txt"
    const val WIRELOG_MAX_SIZE_BYTES = 3 * 1024 * 1024L // 3 MB
    const val WIRELOG_FOLDER_NAME = "logs"

    // github.com/celzero/firestack/blob/bce8de917f/intra/log/logger.go#L76
    enum class LoggerLevel(val id: Long) {
        // the order of the levels is important, do not change it, add new levels at the end
        VERY_VERBOSE(0),
        VERBOSE(1),
        DEBUG(2),
        INFO(3),
        WARN(4),
        ERROR(5),
        STACKTRACE(6),
        USR(7),
        NONE(8);

        companion object {
            fun fromId(id: Int): LoggerLevel? {
                return when (id.toLong()) {
                    VERY_VERBOSE.id -> VERY_VERBOSE
                    VERBOSE.id -> VERBOSE
                    DEBUG.id -> DEBUG
                    INFO.id -> INFO
                    WARN.id -> WARN
                    ERROR.id -> ERROR
                    STACKTRACE.id -> STACKTRACE
                    USR.id -> USR
                    NONE.id -> NONE
                    else -> null
                }
            }

            /**
             * Maps the single-character log-level prefix written by the Go runtime
             * to a [LoggerLevel].  The Go side encodes the level as a single ASCII
             * character rather than a numeric id:
             *
             *   'Y' → VERY_VERBOSE
             *   'V' → VERBOSE
             *   'D' → DEBUG
             *   'I' → INFO
             *   'W' → WARN
             *   'E' → ERROR
             *   'F' → STACKTRACE  (Fatal/stacktrace)
             *   'U' → USR
             *   ' ' → NONE
             *
             * Returns `null` for any unrecognised character so callers can fall back
             * to the previous known level.
             */
            fun fromChar(c: Char): LoggerLevel? {
                return when (c) {
                    'Y' -> VERY_VERBOSE
                    'V' -> VERBOSE
                    'D' -> DEBUG
                    'I' -> INFO
                    'W' -> WARN
                    'E' -> ERROR
                    'F' -> STACKTRACE
                    'U' -> USR
                    // NONE won't be sent from Go, commenting it from usage
                    // using NONE will result in switching the log levels as it has ' ' as the char
                    // below is the e.g., from stacktrace logs
                    /*  ns.forwarder.deliverPackets [11] ns: tun: forwarder: deliverPackets rand10pc [gobind@v20260304225152-87c7a25]
                        go log: unknown level char 'n' using ERROR
                         (#0) -- ideally the space here switching levels to NONE but its actually STACKTRACE
                        <===>
                        go log: unknown level char '<' using NONE*/
                    // ' ' -> NONE
                    else -> null
                }
            }
        }

        fun toLoggerLevel(): LoggerLevel {
            return when (this) {
                VERY_VERBOSE -> VERY_VERBOSE
                VERBOSE -> VERBOSE
                DEBUG -> DEBUG
                INFO -> INFO
                WARN -> WARN
                ERROR -> ERROR
                STACKTRACE -> STACKTRACE
                USR -> USR
                NONE -> NONE
            }
        }

        fun stacktrace(): Boolean {
            return this == STACKTRACE
        }

        fun isLessThanOrEqualTo(level: LoggerLevel): Boolean {
            return this.id <= level.id
        }

        fun user(): Boolean {
            return this == USR
        }
    }

    fun vv(tag: String, message: String) {
        log(tag, message, LoggerLevel.VERY_VERBOSE)
    }

    fun v(tag: String, message: String) {
        log(tag, message, LoggerLevel.VERBOSE)
    }

    fun d(tag: String, message: String) {
        log(tag, message, LoggerLevel.DEBUG)
    }

    fun i(tag: String, message: String) {
        log(tag, message, LoggerLevel.INFO)
    }

    fun w(tag: String, message: String, e: Exception? = null) {
        log(tag, message, LoggerLevel.WARN, e)
    }

    fun e(tag: String, message: String, e: Exception? = null) {
        log(tag, message, LoggerLevel.ERROR, e)
    }

    fun crash(tag: String, message: String, e: Exception? = null) {
        log(tag, message, LoggerLevel.ERROR, e)
    }

    fun updateConfigLevel(level: Long) {
        logLevel = level
    }

    fun throwableToException(throwable: Throwable): Exception {
        return if (throwable is Exception) {
            throwable
        } else {
            Exception(throwable)
        }
    }

    fun goLog(message: String, type: LoggerLevel) {
        // no need to log the go logs, add it to the database
        dbWrite(LOG_GO_LOGGER_V1, message, type)
    }

    fun goLog2(message: String, type: LoggerLevel) {
        // no need to log the go logs, add it to the database
        dbWrite(LOG_GO_LOGGER_V2, message, type)
    }

    fun makeGoConsoleLog(message: String, type: LoggerLevel, isRpn: Boolean): ConsoleLog? {
        if (isRpn) return null
        // uiLogLevel is user selected log level to display in the UI, so if the log
        // level is less than the user selected log level, do not write to the database.
        if (uiLogLevel > type.id) return null

        val now = System.currentTimeMillis()
        val formattedMsg = "${levelChar(type)} $LOG_GO_LOGGER: $message"
        return ConsoleLog(0, formattedMsg, type.id, now)
    }

    fun makeGoRpnLog(message: String, type: LoggerLevel, isRpn: Boolean): RpnLog? {
        if (!isRpn) return null
        // uiLogLevel gate applies to RPN logs as well.
        if (uiLogLevel > type.id) return null

        val now = System.currentTimeMillis()
        val formattedMsg = "${levelChar(type)} $LOG_GO_LOGGER: $message"
        return RpnLog(0, formattedMsg, type.id, now)
    }

    /**
     * Single-character prefix for a log line, matching the level chars written by the
     * Go runtime (see [LoggerLevel.fromChar]). Centralised so [dbWrite] and the
     * batch-oriented [makeGoConsoleLog]/[makeGoRpnLog] never drift apart.
     */
    private fun levelChar(level: LoggerLevel): String = when (level) {
        LoggerLevel.VERY_VERBOSE -> "Y"
        LoggerLevel.VERBOSE -> "V"
        LoggerLevel.DEBUG -> "D"
        LoggerLevel.INFO -> "I"
        LoggerLevel.WARN -> "W"
        LoggerLevel.ERROR -> "E"
        LoggerLevel.STACKTRACE -> "F"
        else -> "V"
    }

    // classify each unique tag once and reuse the verdict for every subsequent log line carrying it
    private val tagIsRpnCache = ConcurrentHashMap<String, Boolean>()

    private fun isRpnTag(tag: String): Boolean =
        tagIsRpnCache.getOrPut(tag) { tag.contains("rpn", ignoreCase = true) }

    private fun isGoLogTag(tag: String): Boolean =
        tag == LOG_GO_LOGGER || tag == LOG_GO_LOGGER_V1 || tag == LOG_GO_LOGGER_V2

    /**
     * Single-pass, case-insensitive scan of a [String] for the "rpn" needle. Unlike
     * two `String.contains`. Comparisons are ASCII-only so it is locale-stable.
     */
    private fun isRpnText(message: String): Boolean {
        for (i in message.indices) {
            if (message[i] == 'r' || message[i] == 'R') {
                if (matchesIgnoreCaseAt(message, i, "rpn")) return true
            }
        }
        return false
    }

    private fun matchesIgnoreCaseAt(message: String, start: Int, needle: String): Boolean {
        if (start + needle.length > message.length) return false
        for (j in needle.indices) {
            val a = message[start + j].code
            val b = needle[j].code
            // needle chars are ASCII letters, so `a xor b == 0x20` is exactly the
            // ASCII upper/lower case pair; no locale-dependent folding needed
            if (a != b && (a xor b) != 0x20) return false
        }
        return true
    }

    /**
     * Case-insensitive scan of `buffer[start, end)` for the "rpn" needle, matching
     * [isRpnText] semantics on raw bytes. Single pass with first-char-triggered
     * lookahead; ASCII-only case folding means UTF-8 multibyte sequences
     * (bytes >= 0x80) can never match.
     */
    fun isRpnPayload(buffer: ByteArray, start: Int, end: Int): Boolean {
        var i = start
        while (i < end) {
            val b = buffer[i].toInt() and 0xFF
            if (b == 'r'.code || b == 'R'.code) {
                if (matchesIgnoreCaseAt(buffer, i, end, "rpn")) return true
            }
            i++
        }
        return false
    }

    private fun matchesIgnoreCaseAt(buffer: ByteArray, start: Int, end: Int, needle: String): Boolean {
        if (start + needle.length > end) return false
        for (j in needle.indices) {
            val a = buffer[start + j].toInt() and 0xFF
            val b = needle[j].code
            if (a != b && (a xor b) != 0x20) return false
        }
        return true
    }

    fun isRpnLog(tag: String, message: String): Boolean {
        return isRpnTag(tag) ||
            tag == LOG_IAB ||
            tag == LOG_TAG_PROXY ||
            (isGoLogTag(tag) && isRpnText(message))
    }

    suspend fun wireLog(message: String) {
        try {
            val logsDir = File(application.filesDir, WIRELOG_FOLDER_NAME).apply {
                if (!exists()) mkdirs()
            }

            val logFile = File(logsDir, WIRELOG_FILE_NAME)

            if (logFile.exists() && logFile.length() > WIRELOG_MAX_SIZE_BYTES) {
                logFile.delete()
            }

            logFile.appendText("$message\n")
        } catch (_: Exception) { }
    }

    fun log(tag: String, msg: String, type: LoggerLevel, e: Exception? = null) {
        when (type) {
            LoggerLevel.VERY_VERBOSE -> if (logLevel <= LoggerLevel.VERY_VERBOSE.id) Log.v(tag, msg)
            LoggerLevel.VERBOSE -> if (logLevel <= LoggerLevel.VERBOSE.id) Log.v(tag, msg)
            LoggerLevel.DEBUG -> if (logLevel <= LoggerLevel.DEBUG.id) Log.d(tag, msg)
            LoggerLevel.INFO -> if (logLevel <= LoggerLevel.INFO.id) Log.i(tag, msg)
            LoggerLevel.WARN -> if (logLevel <= LoggerLevel.WARN.id) Log.w(tag, msg, e)
            LoggerLevel.ERROR -> if (logLevel <= LoggerLevel.ERROR.id) Log.e(tag, msg, e)
            LoggerLevel.STACKTRACE -> if (logLevel <= LoggerLevel.ERROR.id) Log.e(tag, msg, e)
            LoggerLevel.USR -> {} // Do nothing
            LoggerLevel.NONE -> {} // Do nothing
        }
        dbWrite(tag, msg, type, e)
    }

    private fun dbWrite(tag: String, msg: String, level: LoggerLevel, e: Exception? = null) {
        // uiLogLevel is user selected log level to display in the UI, so if the log level is less
        // than the user selected log level, do not write to the database
        // this is different from the logger level set in MiscSettings screen
        if (uiLogLevel > level.id) return

        val now = System.currentTimeMillis()
        val l = levelChar(level)

        val formattedMsg = if (tag == LOG_GO_LOGGER) {
            "$l $tag: $msg"
        } else {
            if (e != null) {
                "$l $tag: $msg\n${Log.getStackTraceString(e)}"
            } else {
                "$l $tag: $msg"
            }
        }

        // RPN-related logs at any level are written to their own in-memory database,
        // everything else goes to the console log database
        try {
            if (isRpnLog(tag, msg)) {
                VpnController.writeRpnLog(RpnLog(0, formattedMsg, level.id, now))
            } else {
                VpnController.writeConsoleLog(ConsoleLog(0, formattedMsg, level.id, now))
            }
        } catch (_: Exception) { }
    }
}
