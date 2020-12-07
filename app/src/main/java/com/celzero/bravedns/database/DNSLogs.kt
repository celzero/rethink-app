/*
Copyright 2020 RethinkDNS and its authors

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

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "DNSLogs")
class DNSLogs {

    /**
     * "CREATE TABLE 'DNSLogs' ('id' INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 'query' TEXT NOT NULL,
     * 'time' INTEGER NOT NULL, 'flag' TEXT NOT NULL, 'resolver' TEXT NOT NULL, latency INTEGER NOT NULL,
     * 'typeName' TEXT NOT NULL, 'isBlocked' INTEGER NOT NULL, 'blockLists' LONGTEXT NOT NULL,
     * 'serverIP' TEXT NOT NULL, 'relayIP' TEXT, 'responseTime' INTEGER NOT NULL, 'response' TEXT NOT NULL,
     *  'status' TEXT NOT NULL,'dnsType' INTEGER NOT NULL) "
     */

    @PrimaryKey(autoGenerate = true)
    var id : Int = 0
    var queryStr : String = ""
    var time : Long = 0L
    var flag : String = ""
    var resolver : String = ""
    var latency : Long  = 0L
    var typeName : String = ""
    var isBlocked : Boolean = false
    var blockLists : String = ""
    var serverIP : String = ""
    var relayIP : String = ""
    var responseTime : Long = 0L
    var response : String = ""
    var status : String = ""
    var dnsType : Int = 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other?.javaClass != javaClass) return false
        other as DNSLogs
        if (id != other.id) return false
        return true
    }

    override fun hashCode(): Int {
        return this.hashCode()
    }

}
