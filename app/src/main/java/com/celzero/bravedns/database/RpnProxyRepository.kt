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

import androidx.room.Transaction

class RpnProxyRepository(private val dao: RpnProxyDao) {

    @Transaction
    suspend fun update(rpn: RpnProxy) {
        dao.update(rpn)
    }

    suspend fun insertAll(rpns: List<RpnProxy>): LongArray {
        return dao.insertAll(rpns)
    }

    suspend fun insert(rpn: RpnProxy): Long {
        return dao.insert(rpn)
    }

    suspend fun delete(wgConfigFiles: RpnProxy) {
        dao.delete(wgConfigFiles)
    }

    suspend fun getProxyById(id: Int): RpnProxy? {
        return dao.getProxyById(id)
    }

    suspend fun getAllProxies(): List<RpnProxy> {
        return dao.getAllProxies()
    }

    suspend fun deleteConfig(id: Int) {
        dao.deleteById(id)
    }
}
