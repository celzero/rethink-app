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

    suspend fun update(pam: ProxyApplicationMapping) {
        proxyApplicationMappingDAO.update(pam)
    }

    suspend fun insert(pam: ProxyApplicationMapping): Long {
        return proxyApplicationMappingDAO.insert(pam)
    }

    suspend fun insertAll(pams: List<ProxyApplicationMapping>): LongArray {
        return proxyApplicationMappingDAO.insertAll(pams)
    }

    suspend fun deleteApp(uid: Int, packageName: String) {
        proxyApplicationMappingDAO.deleteApp(uid, packageName)
    }

    suspend fun deleteAll() {
        proxyApplicationMappingDAO.deleteAll()
    }

    suspend fun deleteAppByPkgName(packageName: String) {
        proxyApplicationMappingDAO.deleteAppByPkgName(packageName)
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

    suspend fun updateProxyNameForProxyId(proxyId: String, proxyName: String) {
        proxyApplicationMappingDAO.updateProxyNameForProxyId(proxyId, proxyName)
    }

    suspend fun updateProxyForUnselectedApps(proxyId: String, proxyName: String) {
        return proxyApplicationMappingDAO.updateProxyForUnselectedApps(proxyId, proxyName)
    }

    suspend fun updateUidForApp(uid: Int, packageName: String) {
        proxyApplicationMappingDAO.updateUidForApp(uid, packageName)
    }

    suspend fun tombstoneApp(oldUid: Int, newUid: Int) {
        try {
            proxyApplicationMappingDAO.tombstoneApp(oldUid, newUid)
        } catch (_: Exception) {
            // catch the exception to avoid crash
        }
    }

    suspend fun getProxiesForApp(uid: Int, packageName: String): List<ProxyApplicationMapping> {
        return proxyApplicationMappingDAO.getProxiesForApp(uid, packageName)
    }

    suspend fun getProxyIdsForApp(uid: Int, packageName: String): List<String> {
        return proxyApplicationMappingDAO.getProxyIdsForApp(uid, packageName)
    }

    suspend fun getAppsForProxy(proxyId: String): List<ProxyApplicationMapping> {
        return proxyApplicationMappingDAO.getAppsForProxy(proxyId)
    }

    suspend fun deleteMapping(uid: Int, packageName: String, proxyId: String) {
        proxyApplicationMappingDAO.deleteMapping(uid, packageName, proxyId)
    }
}