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

import androidx.room.Transaction
import com.celzero.bravedns.data.FileTag

class RethinkRemoteFileTagRepository(private val rethinkRemoteFileTagDao: RethinkRemoteFileTagDao) {

    @Transaction
    suspend fun update(fileTag: RethinkRemoteFileTag) {
        rethinkRemoteFileTagDao.update(fileTag)
    }

    suspend fun insertAll(fileTags: List<RethinkRemoteFileTag>): LongArray {
        return rethinkRemoteFileTagDao.insertAll(fileTags)
    }

    suspend fun updateTags(list: Set<Int>, isSelected: Int) {
        rethinkRemoteFileTagDao.updateTags(list, isSelected)
    }

    suspend fun fileTags(): List<FileTag> {
        return rethinkRemoteFileTagDao.fileTags()
    }

    suspend fun clearSelectedTags() {
        rethinkRemoteFileTagDao.clearSelectedTags()
    }

    suspend fun getSelectedTags(): List<Int> {
        return rethinkRemoteFileTagDao.getSelectedTags()
    }

    suspend fun deleteAll() {
        rethinkRemoteFileTagDao.deleteAll()
    }
}
