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

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface WgHopMapDao {

    @Update fun update(map: WgHopMap)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(maps: List<WgHopMap>): LongArray

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(map: WgHopMap): Long

    @Delete fun delete(map: WgHopMap)

    @Query("delete from WgHopMap where id = :id") fun deleteById(id: Int)

    @Query("select * from WgHopMap") fun getAll(): List<WgHopMap>
}
