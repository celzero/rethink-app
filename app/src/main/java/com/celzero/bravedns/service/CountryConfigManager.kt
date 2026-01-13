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
package com.celzero.bravedns.service

import Logger
import Logger.LOG_TAG_VPN
import com.celzero.bravedns.database.CountryConfig
import com.celzero.bravedns.database.CountryConfigRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

object CountryConfigManager : KoinComponent {

    private const val TAG = "CountryConfigMgr"

    private val db: CountryConfigRepository by inject()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val mutex = Mutex()

    private val _allConfigsFlow = MutableStateFlow<List<CountryConfig>>(emptyList())
    val allConfigsFlow: StateFlow<List<CountryConfig>> = _allConfigsFlow.asStateFlow()

    private val _catchAllConfigsFlow = MutableStateFlow<List<CountryConfig>>(emptyList())
    val catchAllConfigsFlow: StateFlow<List<CountryConfig>> = _catchAllConfigsFlow.asStateFlow()

    private val _lockdownConfigsFlow = MutableStateFlow<List<CountryConfig>>(emptyList())
    val lockdownConfigsFlow: StateFlow<List<CountryConfig>> = _lockdownConfigsFlow.asStateFlow()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    fun initialize() {
        if (_isInitialized.value) {
            Logger.d(LOG_TAG_VPN, "$TAG: Already initialized")
            return
        }

        try {
            scope.launch {
                db.getAllConfigsFlow().collect { configs ->
                    _allConfigsFlow.value = configs
                }
            }

            scope.launch {
                db.getCatchAllConfigsFlow().collect { configs ->
                    _catchAllConfigsFlow.value = configs
                }
            }

            scope.launch {
                db.getLockdownConfigsFlow().collect { configs ->
                    _lockdownConfigsFlow.value = configs
                }
            }

            _isInitialized.value = true
            Logger.i(LOG_TAG_VPN, "$TAG: Initialized successfully")

        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG: Initialization failed: ${e.message}", e)
        }
    }

    suspend fun getConfig(cc: String): CountryConfig? = withContext(Dispatchers.IO) {
        try {
            db.getConfig(cc)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG: getConfig($cc) failed: ${e.message}", e)
            null
        }
    }

    fun getConfigFlow(cc: String): Flow<CountryConfig?> {
        return db.getConfigFlow(cc)
    }

    suspend fun getAllConfigs(): List<CountryConfig> = withContext(Dispatchers.IO) {
        try {
            db.getAllConfigs()
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG: getAllConfigs failed: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun getEnabledConfigs(): List<CountryConfig> = withContext(Dispatchers.IO) {
        try {
            db.getEnabledConfigs()
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG: getEnabledConfigs failed: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun getConfigsByPriority(): List<CountryConfig> = withContext(Dispatchers.IO) {
        try {
            db.getConfigsByPriority()
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG: getConfigsByPriority failed: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun getCatchAllCountries(): List<String> = withContext(Dispatchers.IO) {
        try {
            db.getCatchAllCountryCodes()
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG: getCatchAllCountries failed: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun getLockdownCountries(): List<String> = withContext(Dispatchers.IO) {
        try {
            db.getLockdownCountryCodes()
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG: getLockdownCountries failed: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun getMobileOnlyCountries(): List<String> = withContext(Dispatchers.IO) {
        try {
            db.getMobileOnlyCountryCodes()
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG: getMobileOnlyCountries failed: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun getSsidBasedCountries(): List<String> = withContext(Dispatchers.IO) {
        try {
            db.getSsidBasedCountryCodes()
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG: getSsidBasedCountries failed: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun canCountryBeUsed(
        cc: String,
        isMobileData: Boolean = false,
        currentSsid: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val config = getConfig(cc) ?: return@withContext true
            val preferredCcs = getLockdownCountries()
            config.canBeUsed(isMobileData, currentSsid, preferredCcs)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG: canCountryBeUsed($cc) failed: ${e.message}", e)
            true
        }
    }

    suspend fun getEligibleCountries(
        isMobileData: Boolean = false,
        currentSsid: String? = null
    ): List<String> = withContext(Dispatchers.IO) {
        try {
            val allConfigs = getEnabledConfigs()
            val preferredCcs = getLockdownCountries()
            allConfigs
                .filter { it.canBeUsed(isMobileData, currentSsid, preferredCcs) }
                .sortedByDescending { it.priority }
                .map { it.cc }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG: getEligibleCountries failed: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun getBestCountry(
        isMobileData: Boolean = false,
        currentSsid: String? = null
    ): String? = withContext(Dispatchers.IO) {
        try {
            val eligible = getEligibleCountries(isMobileData, currentSsid)
            eligible.firstOrNull()
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG: getBestCountry failed: ${e.message}", e)
            null
        }
    }

    suspend fun upsertConfig(config: CountryConfig): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                db.insert(config.copy(lastModified = System.currentTimeMillis()))
                Logger.d(LOG_TAG_VPN, "$TAG: Upserted config for ${config.cc}")
                true
            } catch (e: Exception) {
                Logger.e(LOG_TAG_VPN, "$TAG: upsertConfig(${config.cc}) failed: ${e.message}", e)
                false
            }
        }
    }

    suspend fun upsertConfigs(configs: List<CountryConfig>): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val updated = configs.map { it.copy(lastModified = System.currentTimeMillis()) }
                db.insertAll(updated)
                Logger.d(LOG_TAG_VPN, "$TAG: Upserted ${configs.size} configs")
                true
            } catch (e: Exception) {
                Logger.e(LOG_TAG_VPN, "$TAG: upsertConfigs failed: ${e.message}", e)
                false
            }
        }
    }

    suspend fun deleteConfig(cc: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                db.deleteByCountryCode(cc)
                Logger.d(LOG_TAG_VPN, "$TAG: Deleted config for $cc")
                true
            } catch (e: Exception) {
                Logger.e(LOG_TAG_VPN, "$TAG: deleteConfig($cc) failed: ${e.message}", e)
                false
            }
        }
    }

    suspend fun updateCatchAll(cc: String, value: Boolean): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                if (value) {
                    db.clearAllCatchAll()
                }
                db.updateCatchAll(cc, value)
                Logger.d(LOG_TAG_VPN, "$TAG: Updated catchAll for $cc = $value")
                true
            } catch (e: Exception) {
                Logger.e(LOG_TAG_VPN, "$TAG: updateCatchAll($cc, $value) failed: ${e.message}", e)
                false
            }
        }
    }

    suspend fun updateLockdown(cc: String, value: Boolean): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                db.updateLockdown(cc, value)
                Logger.d(LOG_TAG_VPN, "$TAG: Updated lockdown for $cc = $value")
                true
            } catch (e: Exception) {
                Logger.e(LOG_TAG_VPN, "$TAG: updateLockdown($cc, $value) failed: ${e.message}", e)
                false
            }
        }
    }

    suspend fun updateMobileOnly(cc: String, value: Boolean): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                db.updateMobileOnly(cc, value)
                Logger.d(LOG_TAG_VPN, "$TAG: Updated mobileOnly for $cc = $value")
                true
            } catch (e: Exception) {
                Logger.e(LOG_TAG_VPN, "$TAG: updateMobileOnly($cc, $value) failed: ${e.message}", e)
                false
            }
        }
    }

    suspend fun updateSsidBased(cc: String, value: Boolean): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                db.updateSsidBased(cc, value)
                Logger.d(LOG_TAG_VPN, "$TAG: Updated ssidBased for $cc = $value")
                true
            } catch (e: Exception) {
                Logger.e(LOG_TAG_VPN, "$TAG: updateSsidBased($cc, $value) failed: ${e.message}", e)
                false
            }
        }
    }

    suspend fun updateEnabled(cc: String, value: Boolean): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                db.updateEnabled(cc, value)
                Logger.d(LOG_TAG_VPN, "$TAG: Updated enabled for $cc = $value")
                true
            } catch (e: Exception) {
                Logger.e(LOG_TAG_VPN, "$TAG: updateEnabled($cc, $value) failed: ${e.message}", e)
                false
            }
        }
    }

    suspend fun updatePriority(cc: String, priority: Int): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                db.updatePriority(cc, priority)
                Logger.d(LOG_TAG_VPN, "$TAG: Updated priority for $cc = $priority")
                true
            } catch (e: Exception) {
                Logger.e(LOG_TAG_VPN, "$TAG: updatePriority($cc, $priority) failed: ${e.message}", e)
                false
            }
        }
    }

    suspend fun clearAllCatchAll(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                db.clearAllCatchAll()
                Logger.d(LOG_TAG_VPN, "$TAG: Cleared all catchAll settings")
                true
            } catch (e: Exception) {
                Logger.e(LOG_TAG_VPN, "$TAG: clearAllCatchAll failed: ${e.message}", e)
                false
            }
        }
    }

    suspend fun clearAllLockdown(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                db.clearAllLockdown()
                Logger.d(LOG_TAG_VPN, "$TAG: Cleared all lockdown settings")
                true
            } catch (e: Exception) {
                Logger.e(LOG_TAG_VPN, "$TAG: clearAllLockdown failed: ${e.message}", e)
                false
            }
        }
    }

    suspend fun deleteAllConfigs(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                db.deleteAll()
                Logger.d(LOG_TAG_VPN, "$TAG: Deleted all configs")
                true
            } catch (e: Exception) {
                Logger.e(LOG_TAG_VPN, "$TAG: deleteAllConfigs failed: ${e.message}", e)
                false
            }
        }
    }

    suspend fun exists(cc: String): Boolean = withContext(Dispatchers.IO) {
        try {
            db.exists(cc)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG: exists($cc) failed: ${e.message}", e)
            false
        }
    }

    suspend fun isEnabled(cc: String): Boolean = withContext(Dispatchers.IO) {
        try {
            db.isEnabled(cc)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG: isEnabled($cc) failed: ${e.message}", e)
            false
        }
    }

    suspend fun getCount(): Int = withContext(Dispatchers.IO) {
        try {
            db.getCount()
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG: getCount failed: ${e.message}", e)
            0
        }
    }

    suspend fun getEnabledCount(): Int = withContext(Dispatchers.IO) {
        try {
            db.getEnabledCount()
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG: getEnabledCount failed: ${e.message}", e)
            0
        }
    }
}

