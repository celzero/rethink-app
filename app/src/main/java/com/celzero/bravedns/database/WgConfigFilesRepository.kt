/*
 * Copyright 2023 RethinkDNS and its authors
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

import androidx.room.Transaction

class WgConfigFilesRepository(private val wgConfigFilesDAO: WgConfigFilesDAO) {

    @Transaction
    suspend fun update(wgConfigFiles: WgConfigFiles) {
        wgConfigFilesDAO.update(wgConfigFiles)
    }

    suspend fun insertAll(wgConfigFiles: List<WgConfigFiles>): LongArray {
        return wgConfigFilesDAO.insertAll(wgConfigFiles)
    }

    suspend fun insert(wgConfigFiles: WgConfigFiles): Long {
        return wgConfigFilesDAO.insert(wgConfigFiles)
    }

    suspend fun getWgConfigs(): List<WgConfigFiles> {
        return wgConfigFilesDAO.getWgConfigs()
    }

    suspend fun getWarpSecWarpConfig(): List<WgConfigFiles> {
        return wgConfigFilesDAO.getWarpSecWarpConfig()
    }

    suspend fun getLastAddedConfigId(): Int {
        return wgConfigFilesDAO.getLastAddedConfigId()
    }

    suspend fun delete(wgConfigFiles: WgConfigFiles) {
        wgConfigFilesDAO.delete(wgConfigFiles)
    }

    suspend fun deleteOnAppRestore(): Int {
        return wgConfigFilesDAO.deleteOnAppRestore()
    }

    suspend fun deleteConfig(id: Int) {
        wgConfigFilesDAO.deleteConfig(id)
    }

    suspend fun updateCatchAllConfig(id: Int, isCatchAll: Boolean) {
        wgConfigFilesDAO.updateCatchAllConfig(id, isCatchAll)
    }

    suspend fun updateMobileConfig(id: Int, isMobile: Boolean) {
        wgConfigFilesDAO.updateMobileConfig(id, isMobile)
    }

    suspend fun updateOneWireGuardConfig(id: Int, owg: Boolean) {
        wgConfigFilesDAO.updateOneWireGuardConfig(id, owg)
    }

    suspend fun isConfigAdded(id: Int): WgConfigFiles? {
        return wgConfigFilesDAO.isConfigAdded(id)
    }

    suspend fun disableConfig(id: Int) {
        wgConfigFilesDAO.disableConfig(id)
    }

    suspend fun updateSsidEnabled(id: Int, enabled: Boolean) {
        wgConfigFilesDAO.updateSsidEnabled(id, enabled)
    }

    suspend fun updateSsids(id: Int, ssids: String) {
        wgConfigFilesDAO.updateSsids(id, ssids)
    }
}
