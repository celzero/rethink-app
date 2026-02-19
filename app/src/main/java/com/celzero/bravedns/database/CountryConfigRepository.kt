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
package com.celzero.bravedns.database

import Logger
import Logger.LOG_TAG_PROXY
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

class CountryConfigRepository(private val countryConfigDAO: CountryConfigDAO) {

    companion object {
        private const val TAG = "CountryConfigRepo"
    }

    suspend fun getConfig(cc: String): CountryConfig? {
        return countryConfigDAO.getConfig(cc)
    }

    fun getConfigFlow(cc: String): Flow<CountryConfig?> {
        return countryConfigDAO.getConfigFlow(cc)
    }

    suspend fun getAllConfigs(): List<CountryConfig> {
        return countryConfigDAO.getAllConfigs()
    }

    fun getAllConfigsFlow(): Flow<List<CountryConfig>> {
        return countryConfigDAO.getAllConfigsFlow()
    }

    suspend fun getEnabledConfigs(): List<CountryConfig> {
        return countryConfigDAO.getEnabledConfigs()
    }

    fun getEnabledConfigsFlow(): Flow<List<CountryConfig>> {
        return countryConfigDAO.getEnabledConfigsFlow()
    }

    suspend fun getConfigsByPriority(): List<CountryConfig> {
        return countryConfigDAO.getConfigsByPriority()
    }

    fun getConfigsByPriorityFlow(): Flow<List<CountryConfig>> {
        return countryConfigDAO.getConfigsByPriorityFlow()
    }

    // Property-specific queries

    suspend fun getCatchAllConfigs(): List<CountryConfig> {
        return countryConfigDAO.getCatchAllConfigs()
    }

    fun getCatchAllConfigsFlow(): Flow<List<CountryConfig>> {
        return countryConfigDAO.getCatchAllConfigsFlow()
    }

    suspend fun getLockdownConfigs(): List<CountryConfig> {
        return countryConfigDAO.getLockdownConfigs()
    }

    fun getLockdownConfigsFlow(): Flow<List<CountryConfig>> {
        return countryConfigDAO.getLockdownConfigsFlow()
    }

    suspend fun getMobileOnlyConfigs(): List<CountryConfig> {
        return countryConfigDAO.getMobileOnlyConfigs()
    }

    fun getMobileOnlyConfigsFlow(): Flow<List<CountryConfig>> {
        return countryConfigDAO.getMobileOnlyConfigsFlow()
    }

    suspend fun getSsidBasedConfigs(): List<CountryConfig> {
        return countryConfigDAO.getSsidBasedConfigs()
    }

    fun getSsidBasedConfigsFlow(): Flow<List<CountryConfig>> {
        return countryConfigDAO.getSsidBasedConfigsFlow()
    }

    suspend fun getCatchAllCountryCodes(): List<String> {
        return countryConfigDAO.getCatchAllCountryCodes()
    }

    suspend fun getLockdownCountryCodes(): List<String> {
        return countryConfigDAO.getLockdownCountryCodes()
    }

    suspend fun getMobileOnlyCountryCodes(): List<String> {
        return countryConfigDAO.getMobileOnlyCountryCodes()
    }

    suspend fun getSsidBasedCountryCodes(): List<String> {
        return countryConfigDAO.getSsidBasedCountryCodes()
    }

    // WRITE operations

    @Transaction
    suspend fun insert(config: CountryConfig) {
        countryConfigDAO.insert(config)
    }

    @Transaction
    suspend fun insertAll(configs: List<CountryConfig>) {
        countryConfigDAO.insertAll(configs)
    }

    @Transaction
    suspend fun update(config: CountryConfig) {
        countryConfigDAO.update(config)
    }

    @Transaction
    suspend fun delete(config: CountryConfig) {
        countryConfigDAO.delete(config)
    }

    suspend fun deleteByCountryCode(cc: String) {
        countryConfigDAO.deleteByCountryCode(cc)
    }

    suspend fun deleteAll() {
        countryConfigDAO.deleteAll()
    }

    // Property updates

    suspend fun updateCatchAll(cc: String, value: Boolean) {
        countryConfigDAO.updateCatchAll(cc, value)
    }

    suspend fun updateLockdown(cc: String, value: Boolean) {
        countryConfigDAO.updateLockdown(cc, value)
    }

    suspend fun updateMobileOnly(cc: String, value: Boolean) {
        countryConfigDAO.updateMobileOnly(cc, value)
    }

    suspend fun updateSsidBased(cc: String, value: Boolean) {
        countryConfigDAO.updateSsidBased(cc, value)
    }

    suspend fun updateEnabled(cc: String, value: Boolean) {
        countryConfigDAO.updateEnabled(cc, value)
    }

    suspend fun updatePriority(cc: String, priority: Int) {
        countryConfigDAO.updatePriority(cc, priority)
    }

    // Batch operations

    suspend fun clearAllCatchAll() {
        countryConfigDAO.clearAllCatchAll()
    }

    suspend fun clearAllLockdown() {
        countryConfigDAO.clearAllLockdown()
    }

    // Utility methods

    suspend fun exists(cc: String): Boolean {
        return countryConfigDAO.exists(cc)
    }

    suspend fun isEnabled(cc: String): Boolean {
        return countryConfigDAO.isEnabled(cc)
    }

    suspend fun getCount(): Int {
        return countryConfigDAO.getCount()
    }

    suspend fun getEnabledCount(): Int {
        return countryConfigDAO.getEnabledCount()
    }

    suspend fun getCatchAllCount(): Int {
        return countryConfigDAO.getCatchAllCount()
    }

    suspend fun getLockdownCount(): Int {
        return countryConfigDAO.getLockdownCount()
    }

    // ===== Server-specific operations (merged from RpnWinServerRepository) =====

    suspend fun getServerById(serverId: String): CountryConfig? {
        return countryConfigDAO.getAllConfigs().firstOrNull { it.id == serverId }
    }

    suspend fun getServersByCountryCode(cc: String): List<CountryConfig> {
        return countryConfigDAO.getAllConfigs().filter { it.cc == cc }
    }

    suspend fun getActiveServers(): List<CountryConfig> {
        return countryConfigDAO.getEnabledConfigs()
    }

    suspend fun getServerCount(): Int {
        return countryConfigDAO.getCount()
    }

    suspend fun upsertServer(server: CountryConfig) {
        countryConfigDAO.insert(server)
        Logger.d(LOG_TAG_PROXY, "$TAG.upsertServer: ${server.id}")
    }

    suspend fun upsertServers(servers: List<CountryConfig>) {
        countryConfigDAO.insertAll(servers)
        Logger.d(LOG_TAG_PROXY, "$TAG.upsertServers: inserted/updated ${servers.size} servers")
    }

    suspend fun deleteServer(serverId: String) {
        countryConfigDAO.getAllConfigs().firstOrNull { it.id == serverId }?.let {
            countryConfigDAO.delete(it)
            Logger.d(LOG_TAG_PROXY, "$TAG.deleteServer: $serverId")
        }
    }

    suspend fun deleteServers(serverIds: List<String>) {
        val all = countryConfigDAO.getAllConfigs()
        val toDelete = all.filter { serverIds.contains(it.id) }
        toDelete.forEach { countryConfigDAO.delete(it) }
        Logger.d(LOG_TAG_PROXY, "$TAG.deleteServers: deleted ${toDelete.size} servers")
    }

    suspend fun deleteAllServers() {
        countryConfigDAO.deleteAll()
        Logger.d(LOG_TAG_PROXY, "$TAG.deleteAllServers: all servers deleted")
    }

    suspend fun updateServerMetrics(serverId: String, load: Int, link: Int) {
        val all = countryConfigDAO.getAllConfigs()
        val curr = all.firstOrNull { it.id == serverId } ?: return
        val updated = curr.copy(load = load, link = link, lastModified = System.currentTimeMillis())
        countryConfigDAO.insert(updated)
        Logger.v(LOG_TAG_PROXY, "$TAG.updateServerMetrics: $serverId, load=$load, link=$link")
    }

    suspend fun syncServers(newServers: List<CountryConfig>): Int {
        try {
            Logger.i(LOG_TAG_PROXY, "$TAG.syncServers: syncing ${newServers.size} servers from API")
            val existingServers = countryConfigDAO.getAllConfigs()
            val existingIds = existingServers.map { it.id }.toSet()
            val newIds = newServers.map { it.id }.toSet()
            val idsToRemove = existingIds - newIds
            if (idsToRemove.isNotEmpty()) {
                val toDelete = existingServers.filter { idsToRemove.contains(it.id) }
                toDelete.forEach { countryConfigDAO.delete(it) }
                Logger.i(LOG_TAG_PROXY, "$TAG.syncServers: removing ${idsToRemove.size} obsolete servers")
            }
            countryConfigDAO.insertAll(newServers)
            val addedCount = (newIds - existingIds).size
            val updatedCount = newIds.intersect(existingIds).size
            Logger.i(LOG_TAG_PROXY, "$TAG.syncServers: completed - added=$addedCount, updated=$updatedCount, removed=${idsToRemove.size}")
            return newServers.size
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG.syncServers: error syncing servers: ${e.message}", e)
            throw e
        }
    }

    // ===== SSID-related operations =====

    suspend fun updateSsids(cc: String, ssids: String) {
        countryConfigDAO.updateSsids(cc, ssids)
        Logger.d(LOG_TAG_PROXY, "$TAG.updateSsids: $cc, ssids length=${ssids.length}")
    }

    suspend fun getSsidEnabledConfigs(): List<CountryConfig> {
        return countryConfigDAO.getSsidEnabledConfigs()
    }

    fun getSsidEnabledConfigsFlow(): Flow<List<CountryConfig>> {
        return countryConfigDAO.getSsidEnabledConfigsFlow()
    }
}