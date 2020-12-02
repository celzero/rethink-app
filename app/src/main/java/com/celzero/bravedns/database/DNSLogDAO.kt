package com.celzero.bravedns.database

import androidx.paging.DataSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DNSLogDAO {

    /*@Update
    fun update(connectionTracker: ConnectionTracker)*/

    //@Transaction
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(dnsLogs: DNSLogs)

    @Query("select * from DNSLogs order by time desc")
    fun getDNSLogsLiveData(): DataSource.Factory<Int, DNSLogs>

    @Query("select * from DNSLogs where isBlocked = 1 order by time desc")
    fun getBlockedDNSLogsLiveData() : DataSource.Factory<Int, DNSLogs>

    @Query("select * from DNSLogs where queryStr like :queryString or resolver like :queryString or response like :queryString order by time desc")
    fun getDNSLogsByQueryLiveData(queryString : String) : DataSource.Factory<Int, DNSLogs>

    @Query("delete from DNSLogs")
    fun clearAllData()
}
