/*
 * Copyright 2024 RethinkDNS and its authors
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
import Logger.LOG_TAG_VPN
import androidx.lifecycle.asFlow
import com.celzero.bravedns.data.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

/**
 * Manages DNS and network log counts.
 * Extracted from BraveVPNService for better separation of concerns.
 */
class LogsCountManager(
    private val appConfig: AppConfig,
    private val scope: CoroutineScope
) {

    companion object {
        private const val TAG = "LogsCount"
    }

    private val _dnsLogsCount = MutableStateFlow(0L)
    val dnsLogsCount: StateFlow<Long> = _dnsLogsCount.asStateFlow()

    private val _networkLogsCount = MutableStateFlow(0L)
    val networkLogsCount: StateFlow<Long> = _networkLogsCount.asStateFlow()

    init {
        observeLogCounts()
    }

    private fun observeLogCounts() {
        appConfig.dnsLogsCount.asFlow()
            .onEach { count ->
                _dnsLogsCount.update { count }
                Logger.d(LOG_TAG_VPN, "$TAG DNS logs count: $count")
            }
            .launchIn(scope)

        appConfig.networkLogsCount.asFlow()
            .onEach { count ->
                _networkLogsCount.update { count }
                Logger.d(LOG_TAG_VPN, "$TAG Network logs count: $count")
            }
            .launchIn(scope)
    }

    fun getDnsLogsCount(): Long {
        return _dnsLogsCount.value
    }

    fun getNetworkLogsCount(): Long {
        return _networkLogsCount.value
    }

    fun getTotalLogsCount(): Long {
        return _dnsLogsCount.value + _networkLogsCount.value
    }
}
