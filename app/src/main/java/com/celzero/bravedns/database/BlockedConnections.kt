package com.celzero.bravedns.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "BlockedConnections")
class BlockedConnections {

    @PrimaryKey(autoGenerate = true)
    var id: Int = 0
    var uid: Int = 0
    var ipAddress: String? = null
    var port: Int = 0
    var protocol: String ?= null

}