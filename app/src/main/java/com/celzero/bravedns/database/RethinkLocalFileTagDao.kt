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

import android.database.Cursor
import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.celzero.bravedns.data.FileTag

@Dao
interface RethinkLocalFileTagDao {

    @Update fun update(fileTag: RethinkLocalFileTag): Int

    @Update fun updateAll(fileTags: List<RethinkLocalFileTag>)

    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insert(fileTag: RethinkLocalFileTag): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertReplace(fileTag: RethinkLocalFileTag)

    @Delete fun delete(fileTag: RethinkLocalFileTag)

    @Query("delete from RethinkLocalFileTag where value = :id") fun contentDelete(id: Int): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(fileTags: List<RethinkLocalFileTag>): LongArray

    @Query("Update RethinkLocalFileTag set isSelected = :isSelected where value in (:list) ")
    fun updateTags(list: Set<Int>, isSelected: Int)

    @Query("Update RethinkLocalFileTag set isSelected = :isSelected where value = :value")
    fun updateSelectedTag(value: Int, isSelected: Int)

    @Query(
        "select value, uname, vname, `group`, subg, url as urls, show, entries, pack, level, simpleTagId, isSelected  from RethinkLocalFileTag"
    )
    fun getAllTags(): List<FileTag>

    @Query(
        "select * from RethinkLocalFileTag where case when isSelected = 1 then pack like '%%' else (pack not like '%dead%' and pack not like '%ignore%') end order by `group`  desc"
    )
    fun getLocalFileTags(): PagingSource<Int, RethinkLocalFileTag>

    @Query(
        "select * from RethinkLocalFileTag where isSelected in (:selected) and subg in (:subg) and (vname like :query or `group` like :query or subg like :query) and (pack not like '%dead%' and pack not like '%ignore%') order by `group`  desc"
    )
    fun getLocalFileTags(
        query: String,
        selected: Set<Int>,
        subg: Set<String>
    ): PagingSource<Int, RethinkLocalFileTag>

    @Query(
        "select * from RethinkLocalFileTag where isSelected in (:selected) and (vname like :query or `group` like :query or subg like :query) and (pack not like '%dead%' and pack not like '%ignore%') order by `group`  desc"
    )
    fun getLocalFileTagsGroup(
        query: String,
        selected: Set<Int>
    ): PagingSource<Int, RethinkLocalFileTag>

    @Query(
        "select * from RethinkLocalFileTag where isSelected in (:selected) and subg in (:subg) and (vname like :query or `group` like :query or subg like :query) and case when isSelected = 1 then pack like '%%' else (pack not like '%dead%' and pack not like '%ignore%') end order by `group` desc"
    )
    fun getLocalFileTagsSubg(
        query: String,
        selected: Set<Int>,
        subg: Set<String>
    ): PagingSource<Int, RethinkLocalFileTag>

    @Query(
        "select * from RethinkLocalFileTag where isSelected in (:selected) and (vname like :input or `group` like :input or subg like :input) and case when isSelected = 1 then pack like '%%' else (pack not like '%dead%' and pack not like '%ignore%') end order by `group` desc"
    )
    fun getLocalFileTagsWithFilter(
        input: String,
        selected: Set<Int>
    ): PagingSource<Int, RethinkLocalFileTag>

    @Query("Update RethinkLocalFileTag set isSelected = 0") fun clearSelectedTags()

    @Query(
        "select  value, uname, vname, `group`, subg, url as urls, show, entries, pack, level, simpleTagId, isSelected from RethinkLocalFileTag"
    )
    fun fileTags(): List<FileTag>

    @Query("select value from RethinkLocalFileTag where isSelected = 1")
    fun getSelectedTags(): List<Int>

    @Query("delete from RethinkLocalFileTag") fun deleteAll()

    @Query("select * from RethinkLocalFileTag order by `group`") fun getFileTags(): Cursor
}
