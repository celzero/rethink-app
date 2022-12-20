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
import androidx.room.Transaction
import com.celzero.bravedns.data.FileTag

class RethinkLocalFileTagRepository(private val rethinkLocalFileTagDao: RethinkLocalFileTagDao) {

    @Transaction
    suspend fun update(fileTag: RethinkLocalFileTag) {
        rethinkLocalFileTagDao.update(fileTag)
    }

    fun contentUpdate(fileTag: RethinkLocalFileTag): Int {
        return rethinkLocalFileTagDao.update(fileTag)
    }

    fun contentInsert(fileTag: RethinkLocalFileTag): Long {
        return rethinkLocalFileTagDao.insert(fileTag)
    }

    fun contentDelete(id: Int): Int {
        return rethinkLocalFileTagDao.contentDelete(id)
    }

    fun updateAll(fileTags: List<RethinkLocalFileTag>) {
        rethinkLocalFileTagDao.updateAll(fileTags)
    }

    suspend fun insertAll(fileTags: List<RethinkLocalFileTag>): LongArray {
        return rethinkLocalFileTagDao.insertAll(fileTags)
    }

    // fixme: removed suspend for testing, add it back
    fun updateTags(list: Set<Int>, isSelected: Int) {
        rethinkLocalFileTagDao.updateTags(list, isSelected)
    }

    suspend fun fileTags(): List<FileTag> {
        return rethinkLocalFileTagDao.fileTags()
    }

    suspend fun clearSelectedTags() {
        return rethinkLocalFileTagDao.clearSelectedTags()
    }

    suspend fun getSelectedTags(): List<Int> {
        return rethinkLocalFileTagDao.getSelectedTags()
    }

    suspend fun deleteAll() {
        return rethinkLocalFileTagDao.deleteAll()
    }

    fun contentGetFileTags(): Cursor {
        return rethinkLocalFileTagDao.getFileTags()
    }

    fun contentGetSelectedFileTags(): Cursor {
        return rethinkLocalFileTagDao.getFileTags()
    }

    fun contentGetAllFileTags(): Cursor {
        return rethinkLocalFileTagDao.getFileTags()
    }

    fun contentGetFileTagById(id: Int): Cursor {
        return rethinkLocalFileTagDao.getFileTags()
    }
}
