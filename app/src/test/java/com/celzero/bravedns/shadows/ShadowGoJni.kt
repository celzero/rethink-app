/*
 * Copyright 2024 RethinkDNS and its authors
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
package com.celzero.bravedns.shadows

import com.celzero.firestack.backend.DNSOpts
import com.celzero.firestack.backend.DNSSummary
import com.celzero.firestack.intra.Mark
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@Implements(DNSSummary::class)
class ShadowDNSSummary {
    companion object {
        @Implementation
        @JvmStatic
        fun __staticInitializer__() {}

        @Implementation
        @JvmStatic
        fun __New(): Int = 0
    }
}

@Implements(DNSOpts::class)
class ShadowDNSOpts {
    companion object {
        @Implementation
        @JvmStatic
        fun __staticInitializer__() {}

        @Implementation
        @JvmStatic
        fun __New(): Int = 0
    }

    // gomobile binds fields via native getters/setters; back them with a plain map so tests
    // can construct real DNSOpts instances and assert on their values
    private val props = mutableMapOf<String, Any?>()

    @Implementation
    fun getUID(): String = props["uid"] as? String ?: ""

    @Implementation
    fun setUID(uid: String?) {
        props["uid"] = uid
    }

    @Implementation
    fun getIPCSV(): String = props["ipcsv"] as? String ?: ""

    @Implementation
    fun setIPCSV(ipcsv: String?) {
        props["ipcsv"] = ipcsv
    }

    @Implementation
    fun getTIDCSV(): String = props["tidcsv"] as? String ?: ""

    @Implementation
    fun setTIDCSV(tidcsv: String?) {
        props["tidcsv"] = tidcsv
    }

    @Implementation
    fun getTIDSECCSV(): String = props["tidseccsv"] as? String ?: ""

    @Implementation
    fun setTIDSECCSV(tidseccsv: String?) {
        props["tidseccsv"] = tidseccsv
    }

    @Implementation
    fun getNOBLOCK(): Boolean = props["noblock"] as? Boolean ?: false

    @Implementation
    fun setNOBLOCK(noblock: Boolean) {
        props["noblock"] = noblock
    }

    @Implementation
    fun incRefnum(): Int = 0

    @Implementation
    override fun toString(): String = "DNSOpts{$props}"

    @Implementation
    override fun hashCode(): Int = props.hashCode()

    @Implementation
    override fun equals(other: Any?): Boolean = other is ShadowDNSOpts && other.props == props
}

@Implements(Mark::class)
class ShadowMark {
    companion object {
        @Implementation
        @JvmStatic
        fun __staticInitializer__() {}

        @Implementation
        @JvmStatic
        fun __New(): Int = 0
    }
}
