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
import com.celzero.bravedns.util.Constants

@Dao
interface CustomDomainDAO {

    @Update fun update(customDomain: CustomDomain)

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insert(customDomain: CustomDomain)

    @Delete fun delete(customDomain: CustomDomain)

    @Transaction
    @Query("select * from CustomDomain order by modifiedTs desc")
    fun getAllDomains(): List<CustomDomain>

    @Transaction
    @Query("select * from CustomDomain where uid = :uid and domain like :query order by modifiedTs desc")
    fun getDomainsLiveData(uid: Int = Constants.UID_EVERYBODY, query: String): PagingSource<Int, CustomDomain>

    @Query("select count(*) from CustomDomain where uid = :uid")
    fun getAppWiseDomainRulesCount(uid: Int): LiveData<Int>

    @Query("delete from CustomDomain where uid = :uid")
    fun deleteIpRulesByUid(uid: Int)

    @RawQuery fun checkpoint(supportSQLiteQuery: SupportSQLiteQuery): Int
}
