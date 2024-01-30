/*
 * Copyright 2022 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.database

import android.content.Context
import androidx.room.Entity
import com.celzero.bravedns.R

@Entity(primaryKeys = ["name", "url", "uid"], tableName = "RethinkDnsEndpoint")
class RethinkDnsEndpoint(
    var name: String,
    var url: String,
    var uid: Int,
    var desc: String,
    var isActive: Boolean,
    var isCustom: Boolean,
    var latency: Int,
    var blocklistCount: Int,
    var modifiedDataTime: Long
) {

    override fun equals(other: Any?): Boolean {
        if (other !is RethinkDnsEndpoint) return false
        if (name != other.name) return false
        if (url != other.url) return false
        return uid == other.uid
    }

    override fun hashCode(): Int {
        var result = this.name.hashCode()
        result += result * 31 + this.url.hashCode()
        result += result * 31 + this.uid.hashCode()
        return result
    }

    companion object {
        const val RETHINK_DEFAULT: String = "RDNS Default"
        const val RETHINK_PLUS: String = "RDNS Plus"
    }

    init {
        this.modifiedDataTime = System.currentTimeMillis()
    }

    fun isEditable(context: Context): Boolean {
        return this.name == context.getString(R.string.rdns_plus)
    }
}
