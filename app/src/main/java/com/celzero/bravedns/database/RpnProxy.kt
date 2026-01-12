package com.celzero.bravedns.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "RpnProxy")
class RpnProxy {
    @PrimaryKey(autoGenerate = true) var id: Int = 0
    var name: String = ""
    var configPath: String = "" // can be empty
    var serverResPath: String = "" // can be empty
    var isActive: Boolean = false
    var isLockdown: Boolean = false
    var createdTs: Long = 0L
    var modifiedTs: Long = 0L
    var misc: String = "" // can be empty
    var tunId: String = "" // id assigned while adding the proxy to the tunnel, can be empty
    var latency: Int = 0
    var lastRefreshTime: Long = 0L // last time the proxy was refreshed

    override fun equals(other: Any?): Boolean {
        if (other !is RpnProxy) return false
        if (id != other.id) return false
        if (name != other.name) return false
        return true
    }

    override fun hashCode(): Int {
        var result = this.id.hashCode()
        result += result * 31 + this.name.hashCode()
        return result
    }

    fun copy(isActive: Boolean): RpnProxy {
        return RpnProxy(
            id = this.id,
            name = this.name,
            configPath = this.configPath,
            serverResPath = this.serverResPath,
            isActive = isActive,
            isLockdown = this.isLockdown,
            createdTs = this.createdTs,
            modifiedTs = this.modifiedTs,
            misc = this.misc,
            tunId = this.tunId,
            latency = this.latency,
            lastRefreshTime = System.currentTimeMillis()
        )
    }

    constructor(
        id: Int,
        name: String,
        configPath: String,
        serverResPath: String,
        isActive: Boolean,
        isLockdown: Boolean,
        createdTs: Long,
        modifiedTs: Long,
        misc: String,
        tunId: String,
        latency: Int,
        lastRefreshTime: Long
    ) {
        // Room auto-increments id when its set to zero.
        // A non-zero id overrides and sets caller-specified id instead.
        this.id = id
        this.name = name
        this.configPath = configPath
        this.serverResPath = serverResPath
        this.isActive = isActive
        this.isLockdown = isLockdown
        this.createdTs = createdTs
        this.modifiedTs = modifiedTs
        this.misc = misc
        this.tunId = tunId
        this.latency = latency
        this.lastRefreshTime = lastRefreshTime
    }
}
