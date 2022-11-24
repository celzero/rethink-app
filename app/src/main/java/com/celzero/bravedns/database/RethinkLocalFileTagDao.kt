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
import com.celzero.bravedns.automaton.RethinkBlocklistManager
import com.celzero.bravedns.data.FileTag

@Dao
interface RethinkLocalFileTagDao {

    @Update fun update(fileTag: RethinkLocalFileTag)

    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insert(fileTag: RethinkLocalFileTag)

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertReplace(fileTag: RethinkLocalFileTag)

    @Delete fun delete(fileTag: RethinkLocalFileTag)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(fileTags: List<RethinkLocalFileTag>): LongArray

    @Query("Update RethinkLocalFileTag set isSelected = :isSelected where value in (:list) ")
    fun updateTags(list: Set<Int>, isSelected: Int)

    @Query("Update RethinkLocalFileTag set isSelected = :isSelected where value = :value")
    fun updateSelectedTag(value: Int, isSelected: Int)

    @Query("select value, uname, vname, `group`, subg, url as urls, show, entries, pack, simpleTagId, isSelected  from RethinkLocalFileTag") fun getAllTags(): List<FileTag>

    @Query("select * from RethinkLocalFileTag order by `group`")
    fun getLocalFileTags(): PagingSource<Int, RethinkLocalFileTag>

    @Query(
        "select * from RethinkLocalFileTag where isSelected in (:selected) and entries > 0 and `group` in (:group) and subg in (:subg) and (vname like :query or `group` like :query or subg like :query) order by `group`"
    )
    fun getLocalFileTags(
        query: String,
        selected: Set<Int>,
        group: Set<String>,
        subg: Set<String>
    ): PagingSource<Int, RethinkLocalFileTag>

    @Query(
        "select * from RethinkLocalFileTag where isSelected in (:selected) and entries > 0 and `group` in (:group) and (vname like :query or `group` like :query or subg like :query) order by `group`"
    )
    fun getLocalFileTagsGroup(
        query: String,
        selected: Set<Int>,
        group: Set<String>
    ): PagingSource<Int, RethinkLocalFileTag>

    @Query(
        "select * from RethinkLocalFileTag where isSelected in (:selected) and entries > 0 and  subg in (:subg) and (vname like :query or `group` like :query or subg like :query) order by `group`"
    )
    fun getLocalFileTagsSubg(
        query: String,
        selected: Set<Int>,
        subg: Set<String>
    ): PagingSource<Int, RethinkLocalFileTag>

    @Query(
        "select * from RethinkLocalFileTag where isSelected in (:selected) and entries > 0 and (vname like :input or `group` like :input or subg like :input) order by `group`"
    )
    fun getLocalFileTagsWithFilter(
        input: String,
        selected: Set<Int>
    ): PagingSource<Int, RethinkLocalFileTag>

    @Query("Update RethinkLocalFileTag set isSelected = 0") fun clearSelectedTags()

    @Query("select  value, uname, vname, `group`, subg, url as urls, show, entries, pack, simpleTagId, isSelected from RethinkLocalFileTag") fun fileTags(): List<FileTag>

    @Query("select value from RethinkLocalFileTag where isSelected = 1")
    fun getSelectedTags(): List<Int>

    @RawQuery fun checkpoint(supportSQLiteQuery: SupportSQLiteQuery): Int
}
