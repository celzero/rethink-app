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

import androidx.lifecycle.LiveData
import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
interface ProxyApplicationMappingDAO {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(wgMapping: ProxyApplicationMapping): Long

    @Update fun update(wgMapping: ProxyApplicationMapping)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(wgMapping: List<ProxyApplicationMapping>): LongArray

    @Delete fun delete(wgMapping: ProxyApplicationMapping)

    @Query("delete from ProxyApplicationMapping where uid = :uid and packageName = :packageName")
    fun deleteApp(uid: Int, packageName: String)

    @Query("delete from ProxyApplicationMapping where packageName = :packageName")
    fun deleteAppByPkgName(packageName: String)

    @Query("delete from ProxyApplicationMapping") fun deleteAll()

    @Query("select * from ProxyApplicationMapping")
    fun getWgAppMapping(): List<ProxyApplicationMapping>

    // query to get apps for pager adapter: distinct apps ordered by name
    @Query("SELECT * FROM ProxyApplicationMapping WHERE rowid IN ( SELECT MIN(rowid) FROM ProxyApplicationMapping WHERE appName LIKE :appName AND (proxyId = :proxyId or proxyId = '') GROUP BY uid, packageName) ORDER BY lower(appName)")
    fun getAllAppsMapping(appName: String, proxyId: String): PagingSource<Int, ProxyApplicationMapping>

    @Query("SELECT * FROM ProxyApplicationMapping WHERE rowid IN (SELECT MIN(rowid) FROM ProxyApplicationMapping WHERE appName LIKE :appName AND proxyId = :proxyId GROUP BY uid, packageName) ORDER BY lower(appName)")
    fun getSelectedAppsMapping(appName: String, proxyId: String): PagingSource<Int, ProxyApplicationMapping>

    @Query("SELECT * FROM ProxyApplicationMapping WHERE rowid IN ( SELECT MIN(rowid) FROM ProxyApplicationMapping WHERE appName LIKE :appName AND uid NOT IN (SELECT uid FROM ProxyApplicationMapping WHERE proxyId = :proxyId) GROUP BY uid, packageName) ORDER BY lower(appName)")
    fun getUnSelectedAppsMapping(appName: String, proxyId: String): PagingSource<Int, ProxyApplicationMapping>

    @Query("select count(packageName) from ProxyApplicationMapping where proxyId = :id")
    fun getAppCountById(id: String): Int

    @Query("select count(packageName) from ProxyApplicationMapping where proxyId = :id")
    fun getAppCountByIdLiveData(id: String): LiveData<Int>

    @Query("select count(packageName) from ProxyApplicationMapping where proxyId = :id")
    fun getSelectedAppsCountLiveData(id: String): LiveData<Int>

    // unselected: apps in mapping table that are not using this proxyId
    @Query("select count(packageName) from ProxyApplicationMapping where proxyId != :id")
    fun getUnselectedAppsCountLiveData(id: String): LiveData<Int>

    @Query("update ProxyApplicationMapping set proxyName = :proxyName where proxyId = :proxyId")
    fun updateProxyNameForProxyId(proxyId: String, proxyName: String)

    @Transaction
    fun updateUidForApp(oldUid: Int, newUid: Int, packageName: String) {
        deleteConflictingMappingsForUidUpdate(newUid, packageName, oldUid)
        updateUidForAppInternal(oldUid, newUid, packageName)
    }

    @Query("delete from ProxyApplicationMapping where uid = :newUid and packageName = :packageName and proxyId in (select proxyId from ProxyApplicationMapping where uid = :oldUid and packageName = :packageName)")
    fun deleteConflictingMappingsForUidUpdate(newUid: Int, packageName: String, oldUid: Int)

    @Query("update ProxyApplicationMapping set uid = :newUid where packageName = :packageName and uid = :oldUid")
    fun updateUidForAppInternal(oldUid: Int, newUid: Int, packageName: String)

    @Transaction
    fun tombstoneApp(oldUid: Int, newUid: Int) {
        deleteConflictingMappingsForTombstone(newUid, oldUid)
        tombstoneAppInternal(oldUid, newUid)
    }

    @Query("delete from ProxyApplicationMapping where uid = :newUid and exists (select 1 from ProxyApplicationMapping as pam2 where pam2.uid = :oldUid and pam2.packageName = ProxyApplicationMapping.packageName and pam2.proxyId = ProxyApplicationMapping.proxyId)")
    fun deleteConflictingMappingsForTombstone(newUid: Int, oldUid: Int)

    @Query("update ProxyApplicationMapping set uid = :newUid where uid = :oldUid")
    fun tombstoneAppInternal(oldUid: Int, newUid: Int)

    @Query("select * from ProxyApplicationMapping where uid = :uid and packageName = :packageName")
    fun getProxiesForApp(uid: Int, packageName: String): List<ProxyApplicationMapping>

    @Query("select proxyId from ProxyApplicationMapping where uid = :uid and packageName = :packageName")
    fun getProxyIdsForApp(uid: Int, packageName: String): List<String>

    @Query("select * from ProxyApplicationMapping where proxyId = :proxyId")
    fun getAppsForProxy(proxyId: String): List<ProxyApplicationMapping>

    @Query("delete from ProxyApplicationMapping where uid = :uid and packageName = :packageName and proxyId = :proxyId")
    fun deleteMapping(uid: Int, packageName: String, proxyId: String)
}
