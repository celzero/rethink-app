package com.celzero.bravedns.database

import androidx.paging.DataSource
import androidx.room.*


@Dao
interface ConnectionTrackerDAO {

    @Update
    fun update(connectionTracker: ConnectionTracker)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(connectionTracker: ConnectionTracker)

    @Delete
    fun delete(connectionTracker: ConnectionTracker)

    @Query("select * from ConnectionTracker order by timeStamp desc")
    fun getConnectionTrackerLiveData() : DataSource.Factory<Int, ConnectionTracker>


    //@Query ("select * from ConnectionTracker where appName like :query order by timeStamp desc")
    @Query("select * from ConnectionTracker where appName like :query or ipAddress like :query order by timeStamp desc")
    fun getConnectionTrackerByName(query : String) : DataSource.Factory<Int,ConnectionTracker>

    @Query("select * from ConnectionTracker where isBlocked = 1 order by timeStamp desc")
    fun getConnectionBlockedConnections(): DataSource.Factory<Int,ConnectionTracker>

    @Query("delete from ConnectionTracker where timeStamp < :date")
    fun deleteOlderData(date : Long)

    @Query("delete from ConnectionTracker")
    fun clearAllData()

}