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
import androidx.room.*
import com.celzero.bravedns.util.Constants

@Dao
interface CustomDomainDAO {

    @Update fun update(customDomain: CustomDomain): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insert(customDomain: CustomDomain): Long

    @Delete fun delete(customDomain: CustomDomain)

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

    @Query("delete from CustomDomain where uid = :uid") fun deleteRulesByUid(uid: Int)

    @Query("select * from CustomDomain where status in (1,2) order by modifiedTs desc")
    fun getRulesCursor(): Cursor

    @Query("delete from CustomDomain where domain = :domain and uid = :uid")
    fun deleteDomain(domain: String, uid: Int): Int

    @Query("update CustomDomain set status = :status where :clause")
    fun cpUpdate(status: Int, clause: String): Int

}
