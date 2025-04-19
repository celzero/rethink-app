package com.celzero.bravedns.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "WgHopMap")
class WgHopMap {
    @PrimaryKey(autoGenerate = true)
    var id: Int = 0
    var src: String = ""
    var via: String = ""
    var isActive: Boolean = false
    var status: String = "" // last known status from tunnel

    override fun toString(): String {
        return "WgHopMap(id=$id, src='$src', via='$via', isActive=$isActive, status='$status')"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WgHopMap) return false

        if (id != other.id) return false
        if (src != other.src) return false
        if (via != other.via) return false
        if (isActive != other.isActive) return false
        if (status != other.status) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + src.hashCode()
        result = 31 * result + via.hashCode()
        result = 31 * result + isActive.hashCode()
        result = 31 * result + status.hashCode()
        return result
    }

    constructor(id: Int, src: String, via: String, isActive: Boolean, status: String) {
        this.id = id
        this.src = src
        this.via = via
        this.isActive = isActive
        this.status = status
    }
}