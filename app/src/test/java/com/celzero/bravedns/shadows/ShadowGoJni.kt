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
