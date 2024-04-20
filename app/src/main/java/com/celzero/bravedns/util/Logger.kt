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
    const val LOG_TAG_BACKUP_RESTORE = "BackupRestore"
    const val LOG_PROVIDER = "BlocklistProvider"
    const val LOG_TAG_PROXY = "ProxyLogs"
    const val LOG_QR_CODE = "QrCodeFromFileScanner"

    enum class LoggerType(val id: Int) {
        VERBOSE(0),
        DEBUG(1),
        INFO(2),
        WARN(3),
        ERROR(4)
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

    private fun log(tag: String, message: String, type: LoggerType, e: Exception? = null) {
        when (type) {
            LoggerType.VERBOSE -> if (logLevel <= LoggerType.VERBOSE.id) Log.v(tag, message)
            LoggerType.DEBUG -> if (logLevel <= LoggerType.DEBUG.id) Log.d(tag, message)
            LoggerType.INFO -> if (logLevel <= LoggerType.INFO.id) Log.i(tag, message)
            LoggerType.WARN -> if (logLevel <= LoggerType.WARN.id) Log.w(tag, message, e)
            LoggerType.ERROR -> if (logLevel <= LoggerType.ERROR.id) Log.e(tag, message, e)
        }
    }
}
