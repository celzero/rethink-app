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
import androidx.room.Update
import androidx.room.Transaction

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

    // query to get apps for pager adapter
    @Query(
        "select * from ProxyApplicationMapping where appName like :appName order by lower(appName)"
    )
    fun getAllAppsMapping(appName: String): PagingSource<Int, ProxyApplicationMapping>

    @Query(
        "select * from ProxyApplicationMapping where appName like :appName and proxyId = :proxyId order by lower(appName)"
    )
    fun getSelectedAppsMapping(
        appName: String,
        proxyId: String
    ): PagingSource<Int, ProxyApplicationMapping>

    @Query(
        "select * from ProxyApplicationMapping where appName like :appName and proxyId != :proxyId order by lower(appName)"
    )
    fun getUnSelectedAppsMapping(
        appName: String,
        proxyId: String
    ): PagingSource<Int, ProxyApplicationMapping>

    @Query("select count(packageName) from ProxyApplicationMapping where proxyId = :id")
    fun getAppCountById(id: String): Int

    @Query("select count(packageName) from ProxyApplicationMapping where proxyId = :id")
    fun getAppCountByIdLiveData(id: String): LiveData<Int>

    @Query(
        "update ProxyApplicationMapping set proxyId = :cfgId, proxyName = :cfgName where uid = :uid"
    )
    fun updateProxyIdForApp(uid: Int, cfgId: String, cfgName: String)

    @Query("update ProxyApplicationMapping set proxyId = '', proxyName = '' where proxyId = :cfgId")
    fun removeAllAppsForProxy(cfgId: String)

    @Query("update ProxyApplicationMapping set proxyId = '', proxyName = '' where proxyId = 'wg%'")
    fun removeAllWgProxies()

    @Query("update ProxyApplicationMapping set proxyId = :cfgId, proxyName = :cfgName")
    fun updateProxyForAllApps(cfgId: String, cfgName: String = "")

    @Query("update ProxyApplicationMapping set proxyName = :proxyName where proxyId = :proxyId")
    fun updateProxyNameForProxyId(proxyId: String, proxyName: String)

    @Query(
        "update ProxyApplicationMapping set proxyId = :cfgId, proxyName = :cfgName where proxyId = ''"
    )
    fun updateProxyForUnselectedApps(cfgId: String, cfgName: String = "")

    @Query("update ProxyApplicationMapping set uid = :uid where packageName = :packageName")
    fun updateUidForApp(uid: Int, packageName: String)

    @Transaction
    fun tombstoneApp(oldUid: Int, newUid: Int) {
        // Only apply the logic bellow, if the uid is from a work profile
        if (newUid >= 1_000_000) {
            // If a record with the 'newUid' already exists, delete it first
            // This prevents an application crash: database constraint
            deleteMappingByUid(newUid)
        }
        
        // Now that the slot is empty, move the 'oldUid' records to 'newUid'
        updateUidByOldUid(oldUid, newUid)
    }

    @Query("delete from ProxyApplicationMapping where uid = :uid")
    fun deleteMappingByUid(uid: Int)

    // 'ignore' provides an extra layer of safety
    @Query("update or ignore ProxyApplicationMapping set uid = :newUid where uid = :oldUid")
    fun updateUidByOldUid(oldUid: Int, newUid: Int)
}
