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
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface WgApplicationMappingDAO {

    @Update fun update(wgMapping: WgApplicationMapping)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(wgMapping: List<WgApplicationMapping>): LongArray

    @Query("select * from WgApplicationMapping") fun getWgAppMapping(): List<WgApplicationMapping>

    // query to get apps for pager adapter
    @Query("select * from WgApplicationMapping order by lower(appName)")
    fun getAppsMapping(): PagingSource<Int, WgApplicationMapping>

    @Query("select count(packageName) from WgApplicationMapping where wgInterfaceId = :id")
    fun getAppCountById(id: Int): Int

    @Query("select count(packageName) from WgApplicationMapping where wgInterfaceId = :id")
    fun getAppCountByIdLiveData(id: Int): LiveData<Int>

    @Query(
        "update WgApplicationMapping set wgInterfaceId = :cfgId, wgInterfaceName = :cfgName where uid = :uid"
    )
    fun updateInterfaceIdForApp(uid: Int, cfgId: Int, cfgName: String)

    @Query(
        "update WgApplicationMapping set wgInterfaceId = -1, wgInterfaceName = '' where wgInterfaceId = :cfgId"
    )
    fun removeAllAppsFromInterface(cfgId: Int)

    @Query("update WgApplicationMapping set wgInterfaceId = :cfgId, wgInterfaceName = :cfgName")
    fun updateAllAppsForInterface(cfgId: Int, cfgName: String = "")
}
