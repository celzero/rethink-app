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

import Logger
import Logger.LOG_TAG_PROXY
import android.database.sqlite.SQLiteConstraintException

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

    suspend fun updateProxyNameForProxyId(proxyId: String, proxyName: String) {
        proxyApplicationMappingDAO.updateProxyNameForProxyId(proxyId, proxyName)
    }

    suspend fun updateUidForApp(oldUid: Int, newUid: Int, packageName: String) {
        try {
            proxyApplicationMappingDAO.updateUidForApp(oldUid, newUid, packageName)
        } catch (_: SQLiteConstraintException) {
            Logger.w(LOG_TAG_PROXY, "updateUidForApp constraint violation; old=$oldUid -> new=$newUid pkg=$packageName; attempting delete+insert fallback")
            val existing = proxyApplicationMappingDAO.getProxiesForApp(oldUid, packageName)
            proxyApplicationMappingDAO.deleteApp(oldUid, packageName)
            existing.forEach { mapping ->
                mapping.uid = newUid
                proxyApplicationMappingDAO.insert(mapping)
            }
        }
    }

    suspend fun tombstoneApp(oldUid: Int, newUid: Int) {
        try {
            proxyApplicationMappingDAO.tombstoneApp(oldUid, newUid)
        } catch (_: SQLiteConstraintException) {
            Logger.w(LOG_TAG_PROXY, "tombstoneApp constraint violation; oldUid=$oldUid -> newUid=$newUid; skipping tombstone")
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
