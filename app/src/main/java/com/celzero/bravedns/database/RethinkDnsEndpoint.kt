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
import com.celzero.bravedns.util.Constants

@Entity(primaryKeys = ["name", "url", "uid"], tableName = "RethinkDnsEndpoint")
class RethinkDnsEndpoint// Room auto-increments id when its set to zero.
// A non-zero id overrides and sets caller-specified id instead.
    (
    var name: String,
    var url: String,
    var uid: Int,
    var desc: String,
    var isActive: Boolean,
    var isCustom: Boolean,
    var latency: Int,
    blocklistCount: Int?,
    modifiedDataTime: Long
) {
    var blocklistCount: Int = 0
    var modifiedDataTime: Long = Constants.INIT_TIME_MS

    override fun equals(other: Any?): Boolean {
        if (other !is RethinkDnsEndpoint) return false
        if (name != other.name) return false
        if (url != other.url) return false
        if (uid != other.uid) return false
        return true
    }

    override fun hashCode(): Int {
        var result = 0
        result += result * 31 + this.name.hashCode()
        result += result * 31 + this.url.hashCode()
        result += result * 31 + this.uid.hashCode()
        return result
    }

    companion object {
        const val RETHINK_DEFAULT: String = "RDNS Default"
        const val RETHINK_PLUS: String = "RDNS Plus"
    }

    init {
        this.blocklistCount = blocklistCount ?: 0
        if (modifiedDataTime != Constants.INIT_TIME_MS) this.modifiedDataTime = modifiedDataTime
        else this.modifiedDataTime = System.currentTimeMillis()
    }

    fun isEditable(context: Context): Boolean {
        return this.name == context.getString(R.string.rdns_plus)
    }
}
