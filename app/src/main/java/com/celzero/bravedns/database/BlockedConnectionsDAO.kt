package com.celzero.bravedns.database

import androidx.room.*


@Dao
interface BlockedConnectionsDAO {

    @Update
    fun update(blockedConnections: BlockedConnections)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(blockedConnections: BlockedConnections)

    @Delete
    fun delete(blockedConnections: BlockedConnections)

    @Query("select uid, * from BlockedConnections order by uid")
    fun getBlockedConnections() : List<BlockedConnections>

    @Query ("select uid,* from BlockedConnections where uid like :uid")
    fun getBlockedConnectionsByUID(uid: Int ) :List< BlockedConnections>

    @Query ("delete from BlockedConnections where uid = :uid")
    fun clearFirewallRules(uid : Int)

}