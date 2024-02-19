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

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface LocalBlocklistPacksMapDao {
    @Update fun update(map: LocalBlocklistPacksMap)

    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insert(map: LocalBlocklistPacksMap)

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertReplace(map: LocalBlocklistPacksMap)

    @Query("DELETE FROM LocalBlocklistPacksMap") fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(maps: List<LocalBlocklistPacksMap>): LongArray

    @Query(
        "select * from LocalBlocklistPacksMap l INNER JOIN (SELECT pack, MIN(level) level FROM LocalBlocklistPacksMap where pack not in ('dead','ignore') GROUP BY pack) l1 ON l1.pack = l.pack Where l1.level = l.level ORDER BY l.`group` DESC"
    )
    fun getTags(): PagingSource<Int, LocalBlocklistPacksMap>
}
