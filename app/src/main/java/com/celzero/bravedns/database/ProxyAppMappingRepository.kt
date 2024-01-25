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

class ProxyAppMappingRepository(
    private val proxyApplicationMappingDAO: ProxyApplicationMappingDAO
) {

    suspend fun update(wgMapping: ProxyApplicationMapping) {
        proxyApplicationMappingDAO.update(wgMapping)
    }

    suspend fun insert(wgMapping: ProxyApplicationMapping): Long {
        return proxyApplicationMappingDAO.insert(wgMapping)
    }

    suspend fun insertAll(wgMapping: List<ProxyApplicationMapping>): LongArray {
        return proxyApplicationMappingDAO.insertAll(wgMapping)
    }

    suspend fun delete(wgMapping: ProxyApplicationMapping) {
        proxyApplicationMappingDAO.deleteByPackageName(wgMapping.uid, wgMapping.packageName)
    }

    suspend fun deleteAll() {
        proxyApplicationMappingDAO.deleteAll()
    }

    suspend fun getApps(): List<ProxyApplicationMapping> {
        return proxyApplicationMappingDAO.getWgAppMapping() ?: emptyList()
    }

    suspend fun updateProxyIdForApp(uid: Int, proxyId: String, proxyName: String) {
        proxyApplicationMappingDAO.updateProxyIdForApp(uid, proxyId, proxyName)
    }

    suspend fun removeAllAppsForProxy(proxyId: String) {
        proxyApplicationMappingDAO.removeAllAppsForProxy(proxyId)
    }

    suspend fun removeAllWgProxies() {
        proxyApplicationMappingDAO.removeAllWgProxies()
    }

    suspend fun updateProxyForAllApps(proxyId: String, proxyName: String) {
        proxyApplicationMappingDAO.updateProxyForAllApps(proxyId, proxyName)
    }

    suspend fun updateProxyForUnselectedApps(proxyId: String, proxyName: String) {
        return proxyApplicationMappingDAO.updateProxyForUnselectedApps(proxyId, proxyName)
    }
}
