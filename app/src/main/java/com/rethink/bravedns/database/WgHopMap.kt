package com.rethinkdns.retrixed.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "WgHopMap")
class WgHopMap {
    @PrimaryKey(autoGenerate = true)
    var id: Int = 0
    var src: String = ""
    var hop: String = ""
    var isActive: Boolean = false
    var status: String = "" // last known status from tunnel

    override fun toString(): String {
        return "WgHopMap(id=$id, src='$src', hop='$hop', isActive=$isActive, status='$status')"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WgHopMap) return false

        if (id != other.id) return false
        if (src != other.src) return false
        if (hop != other.hop) return false
        if (isActive != other.isActive) return false
        if (status != other.status) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + src.hashCode()
        result = 31 * result + hop.hashCode()
        result = 31 * result + isActive.hashCode()
        result = 31 * result + status.hashCode()
        return result
    }

    constructor(id: Int, src: String, hop: String, isActive: Boolean, status: String) {
        this.id = id
        this.src = src
        this.hop = hop
        this.isActive = isActive
        this.status = status
    }
}