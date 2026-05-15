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
import com.celzero.bravedns.rpnproxy.RpnProxyManager.AUTO_SERVER_ID

class CountryConfigRepository(private val countryConfigDAO: CountryConfigDAO) {

    companion object {
        private const val TAG = "CountryConfigRepo"
    }

    suspend fun getAllConfigs(): List<CountryConfig> {
        return countryConfigDAO.getAllConfigs()
    }

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

    suspend fun updateSsidBased(cc: String, value: Boolean) {
        countryConfigDAO.updateSsidBased(cc, value)
    }

    suspend fun getCount(): Int {
        return countryConfigDAO.getCount()
    }

    suspend fun getById(id: String): CountryConfig? {
        return countryConfigDAO.getById(id)
    }

    suspend fun syncServers(newServers: List<CountryConfig>): Int {
        try {
            Logger.i(LOG_TAG_PROXY, "$TAG.syncServers: syncing ${newServers.size} servers from API")
            val existingServers = countryConfigDAO.getAllConfigs()
            val existingIds = existingServers.map { it.id }.toSet()
            val newIds = newServers.map { it.id }.toSet()

            // AUTO server ID (protected from deletion)
            val autoServerId = "AUTO"

            // Filter out AUTO server from deletion candidates
            val idsToRemove = existingIds.filter { it !in newIds && it != autoServerId }
            if (idsToRemove.isNotEmpty()) {
                val toDelete = existingServers.filter { idsToRemove.contains(it.id) }
                toDelete.forEach {
                    countryConfigDAO.delete(it)
                }
                Logger.i(LOG_TAG_PROXY, "$TAG.syncServers: removing ${idsToRemove.size} obsolete servers (AUTO protected)")
            }
            val idsToAdd = newIds.filter { it !in existingIds }
            if (idsToAdd.isNotEmpty()) {
                val toAdd = newServers.filter { idsToAdd.contains(it.id) }
                countryConfigDAO.insertAll(toAdd)
                Logger.i(LOG_TAG_PROXY, "$TAG.syncServers: adding ${idsToAdd.size} new servers")
            }
            val idsToUpdate = existingIds.intersect(newIds)
            if (idsToUpdate.isNotEmpty()) {
                val toUpdate = newServers.filter { idsToUpdate.contains(it.id) }
                toUpdate.forEach {
                    countryConfigDAO.updateServer(it.id, it.name, it.address, it.city, it.key, it.load, it.link, it.count, it.isActive)
                    Logger.i(LOG_TAG_PROXY, "$TAG.syncServers: updating server ${it.id}")
                }
            }
            Logger.i(LOG_TAG_PROXY, "$TAG.syncServers: completed - added=${idsToAdd.size}, updated=${idsToUpdate.size}, removed=${idsToRemove.size}")
            return newServers.size
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG.syncServers: error syncing servers: ${e.message}", e)
            throw e
        }
    }

    suspend fun updateSsids(cc: String, ssids: String) {
        countryConfigDAO.updateSsids(cc, ssids)
        Logger.d(LOG_TAG_PROXY, "$TAG.updateSsids: $cc, ssids length=${ssids.length}")
    }

    suspend fun incrementSelectionCount(key: String) {
        if (key.isBlank() || key == AUTO_SERVER_ID) return
        countryConfigDAO.incrementSelectionCount(key)
        Logger.d(LOG_TAG_PROXY, "$TAG.incrementSelectionCount: key=$key")
    }

    suspend fun updateFavourite(cc: String, isFavourite: Boolean) {
        countryConfigDAO.updateFavouriteByCountryCode(cc, isFavourite)
        Logger.d(LOG_TAG_PROXY, "$TAG.updateFavourite: cc=$cc, isFavourite=$isFavourite")
    }

    suspend fun getTopFrequentCcs(limit: Int = 5): List<String> {
        return countryConfigDAO.getTopFrequentCcs(limit)
    }

    /**
     * Atomically clears all user-specific state on every server:
     *  - deselects all non-AUTO servers (isEnabled = false)
     *  - removes the favourite mark from all servers (isFavourite = false)
     *  - resets the selection count to 0 on all non-AUTO servers
     *
     * Must only be called from [com.celzero.bravedns.rpnproxy.RpnProxyManager.resetAndRefetchRpn].
     */
    suspend fun resetUserSelections() {
        countryConfigDAO.resetAllIsEnabled()
        countryConfigDAO.resetAllIsFavourite()
        countryConfigDAO.resetAllSelectionCounts()
        Logger.i(LOG_TAG_PROXY, "$TAG.resetUserSelections: cleared all selections, favourites, and counts")
    }
}
