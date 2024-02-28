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

import android.util.Log
import com.celzero.bravedns.service.PersistentState
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class Logger: KoinComponent {
    private val persistentState by inject<PersistentState>()
    private var configuredLogLevel = persistentState.goLoggerLevel

    companion object {
        const val LOG_TAG_APP_UPDATE = "NonStoreAppUpdater"
        const val LOG_TAG_FIREWALL_LOG = "FirewallLogs"
        const val LOG_TAG_DNS_LOG = "DnsLogs"
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
    }

    enum class LoggerType(val id: Int) {
        VERBOSE(0),
        INFO(1),
        DEBUG(2),
        WARN(3),
        ERROR(4)
    }

    fun log(logger: String, message: String, type: LoggerType, e: Exception? = null) {
        when (type) {
            LoggerType.VERBOSE ->
                if (configuredLogLevel <= LoggerType.VERBOSE.id) Log.v(logger, message, e)
            LoggerType.INFO ->
                if (configuredLogLevel <= LoggerType.INFO.id) Log.i(logger, message, e)
            LoggerType.DEBUG ->
                if (configuredLogLevel <= LoggerType.DEBUG.id) Log.d(logger, message, e)
            LoggerType.WARN ->
                if (configuredLogLevel <= LoggerType.WARN.id) Log.w(logger, message, e)
            LoggerType.ERROR ->
                if (configuredLogLevel <= LoggerType.ERROR.id) Log.e(logger, message, e)
        }
    }
}
