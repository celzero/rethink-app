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
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

object Logger : KoinComponent {
    private val persistentState by inject<PersistentState>()
    private var logLevel = persistentState.goLoggerLevel

    const val LOG_TAG_APP_UPDATE = "NonStoreAppUpdater"
    const val LOG_TAG_VPN = "VpnLifecycle"
    const val LOG_TAG_CONNECTION = "ConnectivityEvents"
    const val LOG_TAG_DNS = "DnsManager"
    const val LOG_TAG_FIREWALL = "FirewallManager"
    const val LOG_BATCH_LOGGER = "BatchLogger"
    const val LOG_TAG_APP_DB = "AppDatabase"
    const val LOG_TAG_DOWNLOAD = "DownloadManager"
    const val LOG_TAG_UI = "ActivityManager"
    const val LOG_TAG_SCHEDULER = "JobScheduler"
    const val LOG_TAG_BUG_REPORT = "BugReport"
    const val LOG_TAG_BACKUP_RESTORE = "BackupRestore"
    const val LOG_PROVIDER = "BlocklistProvider"
    const val LOG_TAG_PROXY = "ProxyLogs"
    const val LOG_QR_CODE = "QrCodeFromFileScanner"
    const val LOG_GO_LOGGER = "LibLogger"

    // github.com/celzero/firestack/blob/bce8de917fec5e48a41ed1e96c9d942ee0f7996b/intra/log/logger.go#L76
    enum class LoggerType(val id: Long) {
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
            fun fromId(id: Int): LoggerType {
                return when (id) {
                    0 -> VERY_VERBOSE
                    1 -> VERBOSE
                    2 -> DEBUG
                    3 -> INFO
                    4 -> WARN
                    5 -> ERROR
                    6 -> STACKTRACE
                    7 -> USR
                    8 -> NONE
                    else -> NONE
                }
            }
        }

        fun stacktrace(): Boolean {
            return this == STACKTRACE
        }

        fun isLessThan(level: LoggerType): Boolean {
            return this.id < level.id
        }

        fun user(): Boolean {
            return this == USR
        }
    }

    fun vv(tag: String, message: String) {
        log(tag, message, LoggerType.VERY_VERBOSE)
    }

    fun v(tag: String, message: String) {
        log(tag, message, LoggerType.VERBOSE)
    }

    fun d(tag: String, message: String) {
        log(tag, message, LoggerType.DEBUG)
    }

    fun i(tag: String, message: String) {
        log(tag, message, LoggerType.INFO)
    }

    fun w(tag: String, message: String, e: Exception? = null) {
        log(tag, message, LoggerType.WARN, e)
    }

    fun e(tag: String, message: String, e: Exception? = null) {
        log(tag, message, LoggerType.ERROR, e)
    }

    fun crash(tag: String, message: String, e: Exception? = null) {
        log(tag, message, LoggerType.ERROR, e)
    }

    fun enableCrashlytics() {
        // no-op, Crashlytics is not used for website variant
    }

    fun disableCrashlytics() {
        // no-op, Crashlytics is not used for website variant
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

    private fun log(tag: String, msg: String, type: LoggerType, e: Exception? = null) {
        when (type) {
            LoggerType.VERY_VERBOSE -> if (logLevel <= LoggerType.VERY_VERBOSE.id) Log.v(tag, msg)
            LoggerType.VERBOSE -> if (logLevel <= LoggerType.VERBOSE.id) Log.v(tag, msg)
            LoggerType.DEBUG -> if (logLevel <= LoggerType.DEBUG.id) Log.d(tag, msg)
            LoggerType.INFO -> if (logLevel <= LoggerType.INFO.id) Log.i(tag, msg)
            LoggerType.WARN -> if (logLevel <= LoggerType.WARN.id) Log.w(tag, msg, e)
            LoggerType.ERROR -> if (logLevel <= LoggerType.ERROR.id) Log.e(tag, msg, e)
            LoggerType.STACKTRACE -> if (logLevel <= LoggerType.ERROR.id) Log.e(tag, msg, e)
            LoggerType.USR -> {} // Do nothing
            LoggerType.NONE -> {} // Do nothing
        }
        if (type.id >= logLevel) {
            // get the first letter of the level and append it to the tag
            val l = when (type) {
                LoggerType.VERBOSE -> "V"
                LoggerType.DEBUG -> "D"
                LoggerType.INFO -> "I"
                LoggerType.WARN -> "W"
                LoggerType.ERROR -> "E"
                LoggerType.STACKTRACE -> "E"
                else -> "V"
            }
            VpnController.writeConsoleLog("$l $tag: $msg")
        }
    }
}
