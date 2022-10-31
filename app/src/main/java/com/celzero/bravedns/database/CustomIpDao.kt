/*
 * Copyright 2021 RethinkDNS and its authors
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

import androidx.lifecycle.LiveData
import androidx.paging.PagingSource
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteQuery
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY

@Dao
interface CustomIpDao {

    @Update
    fun update(customIp: CustomIp)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(customIp: CustomIp)

    @Delete
    fun delete(customIp: CustomIp)

    @Transaction
    @Query("select * from CustomIp order by uid")
    fun getCustomIpRules(): List<CustomIp>

    @Query("select * from CustomIp where ipAddress = :ipAddress and uid = :uid and port = :port")
    fun getCustomIpDetail(uid: Int, ipAddress: String, port: Int): CustomIp?

    @Transaction
    @Query("select uid,* from CustomIp where uid = :uid and isActive = 1")
    fun getBlockedConnectionsByUID(uid: Int): List<CustomIp>

    @Query("delete from CustomIp where uid = :uid")
    fun clearIpRuleByUid(uid: Int)

    @Query(
        "select * from CustomIp where isActive = 1 and uid = $UID_EVERYBODY order by modifiedDateTime desc")
    fun getUnivBlockedConnectionsLiveData(): PagingSource<Int, CustomIp>

    @Query(
        "select * from CustomIp where ipAddress like :query and uid = $UID_EVERYBODY and  isActive = 1 order by modifiedDateTime desc")
    fun getUnivBlockedConnectionsByIP(query: String): PagingSource<Int, CustomIp>

    @Query(
        "delete from CustomIp where ipAddress = :ipAddress and uid = $UID_EVERYBODY and port = :port")
    fun deleteIPRulesUniversal(ipAddress: String, port: Int)

    @Transaction
    @Query("delete from CustomIp where ipAddress = :ipAddress and uid = :uid and port = :port")
    fun deleteIPRulesForUID(uid: Int, ipAddress: String, port: Int)

    @Query("delete from CustomIp where uid = $UID_EVERYBODY")
    fun deleteAllIPRulesUniversal()

    @Query("select count(*) from CustomIp where uid = $UID_EVERYBODY LIMIT 10000")
    fun getBlockedConnectionsCount(): Int

    @Query("select count(*) from CustomIp where uid = $UID_EVERYBODY LIMIT 10000")
    fun getCustomIpsLiveData(): LiveData<Int>

    @Query("select count(*) from CustomIp where uid = :uid")
    fun getBlockedConnectionCountForUid(uid: Int): LiveData<Int>

    @Query(
        "select * from CustomIp where uid = :uid and isActive = 1 order by modifiedDateTime desc")
    fun getAppWiseCustomIp(uid: Int): PagingSource<Int, CustomIp>

    @Query(
        "select * from CustomIp where ipAddress like :query and uid = :uid and  isActive = 1 order by modifiedDateTime desc")
    fun getAppWiseCustomIp(query: String, uid: Int): PagingSource<Int, CustomIp>

    @RawQuery
    fun checkpoint(supportSQLiteQuery: SupportSQLiteQuery): Int
}
