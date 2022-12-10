/*
Copyright 2022 RethinkDNS and its authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package com.celzero.bravedns.database

import androidx.paging.PagingSource
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteQuery
import com.celzero.bravedns.data.FileTag

@Dao
interface RethinkRemoteFileTagDao {

    @Update fun update(fileTag: RethinkRemoteFileTag)

    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insert(fileTag: RethinkRemoteFileTag)

    @Delete fun delete(fileTag: RethinkRemoteFileTag)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(fileTag: List<RethinkRemoteFileTag>): LongArray

    @Query("Update RethinkRemoteFileTag set isSelected = :isSelected where value in (:list) ")
    fun updateTags(list: Set<Int>, isSelected: Int)

    @Query(
        "select value, uname, vname, `group`, subg, url as urls, show, entries, pack, simpleTagId, isSelected from RethinkRemoteFileTag"
    )
    fun fileTags(): List<FileTag>

    @Query("Update RethinkRemoteFileTag set isSelected = 0") fun clearSelectedTags()

    @Query("select value from RethinkRemoteFileTag where isSelected = 1")
    fun getSelectedTags(): List<Int>

    @Query("delete from RethinkRemoteFileTag") fun deleteAll()

    @RawQuery fun checkpoint(supportSQLiteQuery: SupportSQLiteQuery): Int
}
