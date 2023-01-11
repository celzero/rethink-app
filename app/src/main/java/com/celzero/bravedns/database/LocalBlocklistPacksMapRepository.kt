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

class LocalBlocklistPacksMapRepository(private val localMapDao: LocalBlocklistPacksMapDao) {

    suspend fun update(map: LocalBlocklistPacksMap) {
        localMapDao.update(map)
    }

    suspend fun insert(map: LocalBlocklistPacksMap) {
        localMapDao.insert(map)
    }

    suspend fun insertWithReplace(map: LocalBlocklistPacksMap) {
        localMapDao.insertReplace(map)
    }

    suspend fun insertAll(maps: List<LocalBlocklistPacksMap>) {
        localMapDao.insertAll(maps)
    }

    suspend fun deleteAll() {
        localMapDao.deleteAll()
    }
}
