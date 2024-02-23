/*
 * Copyright 2022 RethinkDNS and its authors
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
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.celzero.bravedns.data.FileTag

@Dao
interface RethinkRemoteFileTagDao {

    @Update fun update(fileTag: RethinkRemoteFileTag)

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insert(fileTag: RethinkRemoteFileTag)

    @Delete fun delete(fileTag: RethinkRemoteFileTag)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(fileTag: List<RethinkRemoteFileTag>): LongArray

    @Query(
        "select * from RethinkRemoteFileTag where case when isSelected = 1 then pack like '%%' else (pack not like '%dead%' and pack not like '%ignore%') end order by `group` desc"
    )
    fun getRemoteFileTags(): PagingSource<Int, RethinkRemoteFileTag>

    @Query(
        "select * from RethinkRemoteFileTag where isSelected in (:selected) and subg in (:subg) and (vname like :query or `group` like :query or subg like :query) and (pack not like '%dead%' and pack not like '%ignore%') order by `group` desc"
    )
    fun getRemoteFileTags(
        query: String,
        selected: Set<Int>,
        subg: Set<String>
    ): PagingSource<Int, RethinkRemoteFileTag>

    @Query(
        "select * from RethinkRemoteFileTag where isSelected in (:selected) and (vname like :query or `group` like :query or subg like :query) and (pack not like '%dead%' and pack not like '%ignore%') order by `group` desc"
    )
    fun getRemoteFileTagsGroup(
        query: String,
        selected: Set<Int>
    ): PagingSource<Int, RethinkRemoteFileTag>

    @Query(
        "select * from RethinkRemoteFileTag where isSelected in (:selected) and subg in (:subg) and (vname like :query or `group` like :query or subg like :query) and case when isSelected = 1 then pack like '%%' else (pack not like '%dead%' and pack not like '%ignore%') end order by `group` desc"
    )
    fun getRemoteFileTagsSubg(
        query: String,
        selected: Set<Int>,
        subg: Set<String>
    ): PagingSource<Int, RethinkRemoteFileTag>

    @Query(
        "select value, uname, vname, `group`, subg, url as urls, show, entries, pack, level, simpleTagId, isSelected from RethinkRemoteFileTag order by `group` desc"
    )
    fun getAllTags(): List<FileTag>

    @Transaction
    @Query(
        "select * from RethinkRemoteFileTag where isSelected in (:selected) and (vname like :input or `group` like :input or subg like :input) and case when isSelected = 1 then pack like '%%' else (pack not like '%dead%' and pack not like '%ignore%') end order by `group` desc"
    )
    fun getRemoteFileTagsWithFilter(
        input: String,
        selected: Set<Int>
    ): PagingSource<Int, RethinkRemoteFileTag>

    @Query("Update RethinkRemoteFileTag set isSelected = :isSelected where value in (:list) ")
    fun updateTags(list: Set<Int>, isSelected: Int)

    @Query("Update RethinkRemoteFileTag set isSelected = :isSelected where value = :value")
    fun updateSelectedTag(value: Int, isSelected: Int)

    @Query(
        "select value, uname, vname, `group`, subg, url as urls, show, entries, pack, level, simpleTagId, isSelected from RethinkRemoteFileTag"
    )
    fun fileTags(): List<FileTag>

    @Query("Update RethinkRemoteFileTag set isSelected = 0") fun clearSelectedTags()

    @Query("select value from RethinkRemoteFileTag where isSelected = 1")
    fun getSelectedTags(): List<Int>

    @Query("delete from RethinkRemoteFileTag") fun deleteAll()
}
