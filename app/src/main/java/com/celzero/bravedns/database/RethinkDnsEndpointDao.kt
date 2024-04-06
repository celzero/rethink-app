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
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.celzero.bravedns.database.RethinkDnsEndpoint.Companion.RETHINK_DEFAULT
import com.celzero.bravedns.database.RethinkDnsEndpoint.Companion.RETHINK_PLUS
import com.celzero.bravedns.util.Constants.Companion.MISSING_UID

@Dao
interface RethinkDnsEndpointDao {

    @Update fun update(rethinkDnsEndpoint: RethinkDnsEndpoint)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(rethinkDnsEndpoint: RethinkDnsEndpoint)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertReplace(rethinkDnsEndpoint: RethinkDnsEndpoint)

    @Query(
        "Update RethinkDnsEndpoint set url = :url, blocklistCount = :count where name = :name and uid = $MISSING_UID"
    )
    fun updateEndpoint(name: String, url: String, count: Int)

    @Delete fun delete(rethinkDnsEndpoint: RethinkDnsEndpoint)

    @Query("update RethinkDnsEndpoint set isActive = 0 where isActive = 1 and uid = $MISSING_UID")
    fun removeConnectionStatus()

    @Query("update RethinkDnsEndpoint set isActive = 0 where uid = :uid")
    fun removeAppWiseDns(uid: Int)

    @Transaction
    @Query("select * from RethinkDnsEndpoint where uid =  $MISSING_UID order by isActive desc")
    fun getRethinkEndpoints(): PagingSource<Int, RethinkDnsEndpoint>

    @Transaction
    @Query("select * from RethinkDnsEndpoint order by isActive desc")
    fun getAllRethinkEndpoints(): PagingSource<Int, RethinkDnsEndpoint>

    @Query("select isActive from RethinkDnsEndpoint where uid = :uid")
    fun isAppWiseDnsEnabled(uid: Int): Boolean?

    @Transaction
    @Query(
        "select * from RethinkDnsEndpoint where name like :query or url like :query and uid = $MISSING_UID order by isActive desc"
    )
    fun getRethinkEndpointsByName(query: String): PagingSource<Int, RethinkDnsEndpoint>

    @Query("select * from RethinkDnsEndpoint where isActive = 1 and uid = $MISSING_UID LIMIT 1")
    fun getConnectedEndpoint(): RethinkDnsEndpoint?

    @Query("update RethinkDnsEndpoint set isActive = 1 where uid = $MISSING_UID and name = :conn")
    fun updateConnectionDefault(conn: String = RETHINK_DEFAULT)

    @Query("select count(*) from RethinkDnsEndpoint") fun getCount(): Int

    @Query("select * from RethinkDnsEndpoint where name = :plus and uid = $MISSING_UID")
    fun getRethinkPlusEndpoint(plus: String = RETHINK_PLUS): RethinkDnsEndpoint?

    @Query("update RethinkDnsEndpoint set isActive = 1 where uid = $MISSING_UID and name = :plus")
    fun setRethinkPlus(plus: String = RETHINK_PLUS)

    // TODO: remove this method post v054 versions
    @Query(
        "update RethinkDnsEndpoint set blocklistCount = :count where uid = $MISSING_UID and name = :plus"
    )
    fun updatePlusBlocklistCount(count: Int, plus: String = RETHINK_PLUS)

    @Query("update RethinkDnsEndpoint set url = REPLACE(url, 'sky', 'max')") fun switchToMax()

    @Query("update RethinkDnsEndpoint set url = REPLACE(url, 'max', 'sky')") fun switchToSky()
}
