/*
 * Copyright 2025 RethinkDNS and its authors
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
package com.celzero.bravedns.adapter

import com.celzero.bravedns.database.CountryConfig
import com.celzero.bravedns.wireguard.Config

/**
 * Sealed class representing different types of hop items
 * Used by GenericHopAdapter to support both WireGuard and RPN hopping
 */
sealed class HopItem {
    abstract fun getId(): Int
    abstract fun getName(): String
    abstract fun isActive(): Boolean

    /**
     * WireGuard configuration hop item
     */
    data class WireGuardHop(val config: Config, val active: Boolean) : HopItem() {
        override fun getId(): Int = config.getId()
        override fun getName(): String = config.getName()
        override fun isActive(): Boolean = active
    }

    /**
     * RPN proxy hop item (country-based)
     */
    data class RpnProxyHop(val countryConfig: CountryConfig, val active: Boolean) : HopItem() {
        override fun getId(): Int = countryConfig.cc.hashCode()
        override fun getName(): String = countryConfig.cc
        override fun isActive(): Boolean = active
    }
}

