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
import kotlinx.coroutines.flow.Flow

/**
 * Repository for RPN WIN server data operations.
 * Handles database operations directly with RpnWinServerEntity.
 */
class RpnWinServerRepository(private val dao: RpnWinServerDAO) {

    companion object {
        private const val TAG = "RpnWinServerRepo"
    }

    fun getAllServersFlow(): Flow<List<RpnWinServer>> {
        return dao.getAllServersFlow()
    }

    suspend fun getAllServers(): List<RpnWinServer> {
        return dao.getAllServers()
    }

    suspend fun getActiveServers(): List<RpnWinServer> {
        return dao.getActiveServers()
    }

    suspend fun getServerById(serverId: String): RpnWinServer? {
        return dao.getServerById(serverId)
    }

    suspend fun getServersByCountryCode(cc: String): List<RpnWinServer> {
        return dao.getServersByCountryCode(cc)
    }

    suspend fun getServerCount(): Int {
        return dao.getServerCount()
    }

    suspend fun upsertServer(server: RpnWinServer) {
        dao.insertServer(server)
        Logger.d(LOG_TAG_PROXY, "$TAG.upsertServer: ${server.id}")
    }

    suspend fun upsertServers(servers: List<RpnWinServer>) {
        dao.insertServers(servers)
        Logger.d(LOG_TAG_PROXY, "$TAG.upsertServers: inserted/updated ${servers.size} servers")
    }

    suspend fun deleteServer(serverId: String) {
        dao.deleteServerById(serverId)
        Logger.d(LOG_TAG_PROXY, "$TAG.deleteServer: $serverId")
    }

    suspend fun deleteServers(serverIds: List<String>) {
        dao.deleteServersByIds(serverIds)
        Logger.d(LOG_TAG_PROXY, "$TAG.deleteServers: deleted ${serverIds.size} servers")
    }

    suspend fun deleteAllServers() {
        dao.deleteAllServers()
        Logger.d(LOG_TAG_PROXY, "$TAG.deleteAllServers: all servers deleted")
    }

    suspend fun updateServerMetrics(serverId: String, load: Int, link: Int) {
        dao.updateServerMetrics(serverId, load, link, System.currentTimeMillis())
        Logger.v(LOG_TAG_PROXY, "$TAG.updateServerMetrics: $serverId, load=$load, link=$link")
    }

    suspend fun syncServers(newServers: List<RpnWinServer>): Int {
        try {
            Logger.i(LOG_TAG_PROXY, "$TAG.syncServers: syncing ${newServers.size} servers from API")
            val existingServers = dao.getAllServers()
            val existingIds = existingServers.map { it.id }.toSet()
            val newIds = newServers.map { it.id }.toSet()
            val idsToRemove = existingIds - newIds
            if (idsToRemove.isNotEmpty()) {
                Logger.i(LOG_TAG_PROXY, "$TAG.syncServers: removing ${idsToRemove.size} obsolete servers")
                dao.deleteServersByIds(idsToRemove.toList())
            }
            dao.insertServers(newServers)
            val addedCount = (newIds - existingIds).size
            val updatedCount = newIds.intersect(existingIds).size
            Logger.i(LOG_TAG_PROXY, "$TAG.syncServers: completed - added=$addedCount, updated=$updatedCount, removed=${idsToRemove.size}")
            return newServers.size
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG.syncServers: error syncing servers: ${e.message}", e)
            throw e
        }
    }
}
