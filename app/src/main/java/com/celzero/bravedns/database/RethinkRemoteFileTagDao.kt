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
import com.celzero.bravedns.automaton.RethinkBlocklistManager
import com.celzero.bravedns.data.FileTag

@Dao
interface RethinkRemoteFileTagDao {

    @Update
    fun update(fileTag: RethinkRemoteFileTag)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(fileTag: RethinkRemoteFileTag)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertReplace(fileTag: RethinkRemoteFileTag)

    @Delete
    fun delete(fileTag: RethinkRemoteFileTag)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(fileTag: List<RethinkRemoteFileTag>): LongArray

    @Query("select * from RethinkRemoteFileTag order by `group`")
    fun getRemoteFileTags(): PagingSource<Int, RethinkRemoteFileTag>

    @Query(
        "select * from RethinkRemoteFileTag where `group` in (:group) and subg in (:subg) and (vname like :query or `group` like :query or subg like :query) order by `group`")
    fun getRemoteFileTags(query: String, group: Set<String>, subg: Set<String>): PagingSource<Int, RethinkRemoteFileTag>

    @Query(
        "select * from RethinkRemoteFileTag where `group` in (:group) and (vname like :query or `group` like :query or subg like :query) order by `group`")
    fun getRemoteFileTagsGroup(query: String, group: Set<String>): PagingSource<Int, RethinkRemoteFileTag>

    @Query(
        "select * from RethinkRemoteFileTag where subg in (:subg) and (vname like :query or `group` like :query or subg like :query) order by `group`")
    fun getRemoteFileTagsSubg(query: String, subg: Set<String>): PagingSource<Int, RethinkRemoteFileTag>

    @Query("select * from RethinkRemoteFileTag order by `group`")
    fun getAllTags(): List<FileTag>

    @Transaction
    @Query(
        "select * from RethinkRemoteFileTag where (vname like :input or `group` like :input or subg like :input) order by `group`")
    fun getRemoteFileTagsWithFilter(input: String): PagingSource<Int, RethinkRemoteFileTag>

    @Query("Update RethinkRemoteFileTag set isSelected = :isSelected where value in (:list) ")
    fun updateSelectedTags(list: Set<Int>, isSelected: Int)

    @Query("Update RethinkRemoteFileTag set isSelected = :isSelected where value = :value")
    fun updateSelectedTag(value: Int, isSelected: Int)

    @Query("select value, simpleTagId from RethinkRemoteFileTag order by simpleTagId")
    fun getSimpleViewTags(): List<RethinkBlocklistManager.SimpleViewMapping>

    @Query("Update RethinkRemoteFileTag set isSelected = 0")
    fun clearSelectedTags()
}
