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

import android.database.Cursor
import androidx.lifecycle.LiveData
import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.celzero.bravedns.util.Constants

@Dao
interface CustomDomainDAO {

    @Update fun update(customDomain: CustomDomain): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insert(customDomain: CustomDomain): Long

    @Delete fun delete(customDomain: CustomDomain)

    @Delete fun deleteAll(customDomains: List<CustomDomain>)

    @Transaction
    @Query("select * from CustomDomain order by modifiedTs desc")
    fun getAllDomains(): List<CustomDomain>

    @Transaction
    @Query(
        "select * from CustomDomain where uid = :uid and domain like :query order by modifiedTs desc"
    )
    fun getDomainsLiveData(
        uid: Int = Constants.UID_EVERYBODY,
        query: String
    ): PagingSource<Int, CustomDomain>

    @Query("select count(*) from CustomDomain where uid = :uid")
    fun getAppWiseDomainRulesCount(uid: Int): LiveData<Int>

    @Query("select * from CustomDomain where uid = :uid order by modifiedTs desc")
    fun getDomainsByUID(uid: Int): List<CustomDomain>

    @Transaction
    fun updateUid(uid: Int, newUid: Int) {
        // Use INSERT OR REPLACE to handle conflicts properly
        // First, insert all entries from oldUid with newUid (this will replace any existing conflicts)
        insertOrReplaceWithNewUid(uid, newUid)
        // Then delete the original entries
        deleteRulesByUid(uid)
    }

    @Query("""
        INSERT OR REPLACE INTO CustomDomain (domain, uid, ips, status, type, proxyId, proxyCC, modifiedTs, deletedTs, version)
        SELECT domain, :newUid, ips, status, type, proxyId, proxyCC, modifiedTs, deletedTs, version 
        FROM CustomDomain WHERE uid = :oldUid
    """)
    fun insertOrReplaceWithNewUid(oldUid: Int, newUid: Int)

    @Query("select count(*) from CustomDomain where uid != ${Constants.UID_EVERYBODY}")
    fun getAllDomainRulesCount(): LiveData<Int>

    @Query("delete from CustomDomain where uid = :uid") fun deleteRulesByUid(uid: Int)

    @Query("delete from CustomDomain") fun deleteAllRules()

    @Query("select * from CustomDomain where status in (1,2) order by modifiedTs desc")
    fun getRulesCursor(): Cursor

    @Query("delete from CustomDomain where domain = :domain and uid = :uid")
    fun deleteDomain(domain: String, uid: Int): Int

    @Query("update CustomDomain set status = :status where :clause")
    fun cpUpdate(status: Int, clause: String): Int

    @androidx.room.RewriteQueriesToDropUnusedColumns
    @Query(
        "SELECT * FROM (SELECT *, (SELECT COUNT(*) FROM CustomDomain cd2 WHERE cd2.uid = cd1.uid AND cd2.rowid <= cd1.rowid) row_num FROM CustomDomain cd1 WHERE uid != ${Constants.UID_EVERYBODY} AND domain LIKE :query) WHERE row_num <= 5 ORDER BY uid, row_num"
    )
    fun getAllDomainRules(query: String): PagingSource<Int, CustomDomain>

    @Query("SELECT * FROM CustomDomain WHERE uid = :uid AND domain = :domain")
    fun getCustomDomain(uid: Int, domain: String): CustomDomain?

    @Query("SELECT COUNT(*) FROM CustomDomain")
    fun getCustomDomainCount(): Int

    @Query("SELECT COUNT(*) FROM CustomDomain WHERE proxyCC = :cc")
    fun getRulesCountByCC(cc: String): Int
}
