package com.celzero.bravedns.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ConnectionTracker")
class ConnectionTracker {
    @PrimaryKey(autoGenerate = true)
    var id : Int = 0
    var appName : String ?= null
    var uid : Int = 0
    var ipAddress: String ?= null
    var port : Int = 0
    var protocol : Int = 0
    var isBlocked : Boolean = false
    var flag : String? = null
    var timeStamp : Long = 0L

    override fun equals(other: Any?): Boolean{
        if (this === other) return true
        if (other?.javaClass != javaClass) return false
        other as ConnectionTracker
        if (id != other.id) return false
        return true
    }

    override fun hashCode(): Int{
        return this.hashCode()
    }

}