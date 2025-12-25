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

@Dao
interface WgConfigFilesDAO {

    @Update fun update(wgConfigFiles: WgConfigFiles)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(wgConfigFiles: List<WgConfigFiles>): LongArray

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insert(wgConfigFiles: WgConfigFiles): Long

    @Query(
        "select * from WgConfigFiles order by isActive desc"
    )
    fun getWgConfigsLiveData(): PagingSource<Int, WgConfigFiles>

    @Query(
        "select * from WgConfigFiles order by isActive desc"
    )
    fun getWgConfigs(): List<WgConfigFiles>

    // TODO: should remove this query post v055o
    // sometimes the db update does not delete the entry, so adding this as precaution
    @Query("select * from WgConfigFiles where id in (0, 1)")
    fun getWarpSecWarpConfig(): List<WgConfigFiles>

    @Query("select max(id) from WgConfigFiles") fun getLastAddedConfigId(): Int

    @Delete fun delete(wgConfigFiles: WgConfigFiles)

    @Query("delete from WgConfigFiles")
    fun deleteOnAppRestore(): Int

    @Query("delete from WgConfigFiles where id = :id") fun deleteConfig(id: Int)

    @Query("update WgConfigFiles set isCatchAll = :isCatchAll, oneWireGuard = 0 where id = :id")
    fun updateCatchAllConfig(id: Int, isCatchAll: Boolean)

    @Query("update WgConfigFiles set useOnlyOnMetered = :isMobile where id = :id")
    fun updateMobileConfig(id: Int, isMobile: Boolean)

    @Query("update WgConfigFiles set oneWireGuard = :oneWireGuard where id = :id")
    fun updateOneWireGuardConfig(id: Int, oneWireGuard: Boolean)

    @Query("update WgConfigFiles set ssidEnabled = :enabled where id = :id")
    fun updateSsidEnabled(id: Int, enabled: Boolean)

    @Query("update WgConfigFiles set ssids = :ssids where id = :id")
    fun updateSsids(id: Int, ssids: String)

    @Query("select * from WgConfigFiles where id = :id") fun isConfigAdded(id: Int): WgConfigFiles?

    @Query("select count(id) from WgConfigFiles")
    fun getConfigCount(): LiveData<Int>

    @Query("update WgConfigFiles set isActive = 0, oneWireGuard = 0 where id = :id")
    fun disableConfig(id: Int)

}
