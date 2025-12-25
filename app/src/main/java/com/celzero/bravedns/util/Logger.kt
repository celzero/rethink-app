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
import android.util.Log
import com.celzero.bravedns.database.ConsoleLog
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

object Logger : KoinComponent {
    private val persistentState by inject<PersistentState>()

    private var _logLevel: Long? = null
    private var logLevel: Long
        get() {
            if (_logLevel == null) {
                _logLevel = try {
                    persistentState.goLoggerLevel
                } catch (e: Exception) {
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
    const val LOG_TAG_APP_OPS = "AppOpsService"
    const val LOG_IAB = "InAppBilling"
    const val LOG_FIREBASE = "FirebaseErrorReporting"
    const val LOG_TAG_APP = "ExceptionHandler"

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
            fun fromId(id: Int): LoggerLevel {
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
                    else -> NONE
                }
            }
        }

        fun stacktrace(): Boolean {
            return this == STACKTRACE
        }

        fun isLessThan(level: LoggerLevel): Boolean {
            return this.id < level.id
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
        dbWrite(LOG_GO_LOGGER, message, type)
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
        val l = when (level) {
            LoggerLevel.VERY_VERBOSE -> "Y"
            LoggerLevel.VERBOSE -> "V"
            LoggerLevel.DEBUG -> "D"
            LoggerLevel.INFO -> "I"
            LoggerLevel.WARN -> "W"
            LoggerLevel.ERROR -> "E"
            LoggerLevel.STACKTRACE -> "E"
            else -> "V"
        }

        val formattedMsg = if (tag == LOG_GO_LOGGER) {
            "$l $tag: $msg"
        } else {
            if (e != null) {
                "$l $tag: $msg\n${Log.getStackTraceString(e)}"
            } else {
                "$l $tag: $msg"
            }
        }

        // write to the database
        try {
            val c = ConsoleLog(0, formattedMsg, level.id, now)
            VpnController.writeConsoleLog(c)
        } catch (_: Exception) { }
    }
}
