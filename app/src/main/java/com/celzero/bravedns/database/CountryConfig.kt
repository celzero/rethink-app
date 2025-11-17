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
package com.celzero.bravedns.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity representing country-level configuration properties
 * Similar to WireGuard configuration but operates on country codes
 */
@Entity(tableName = "CountryConfig")
data class CountryConfig(
    @PrimaryKey
    val cc: String, // Country code (e.g., "US", "GB", "IN")

    val catchAll: Boolean = false, // Use this country for all connections

    val lockdown: Boolean = false, // Force connection through this country only

    val mobileOnly: Boolean = false, // Use only on mobile data

    val ssidBased: Boolean = false, // Use based on specific WiFi SSIDs

    val lastModified: Long = System.currentTimeMillis(), // Last update timestamp

    val enabled: Boolean = true, // Whether this country config is active

    val priority: Int = 0 // Priority for selection (higher = preferred)
) {
    companion object {
        const val TAG = "CountryConfig"
    }

    /**
     * Check if this country can be used for the current connection
     */
    fun canBeUsed(
        isMobileData: Boolean,
        currentSsid: String?,
        preferredCcs: List<String>
    ): Boolean {
        if (!enabled) return false

        // If catchAll is enabled, always use
        if (catchAll) return true

        // If lockdown, must explicitly match
        if (lockdown) return preferredCcs.contains(cc)

        // Check mobile-only restriction
        if (mobileOnly && !isMobileData) return false

        // Check SSID-based routing (would need SSID mapping - future enhancement)
        if (ssidBased && currentSsid == null) return false

        return true
    }

    /**
     * Check if any exclusion rule applies
     */
    fun hasExclusionRules(): Boolean {
        return lockdown || mobileOnly || ssidBased
    }

    /**
     * Check if this is a priority country
     */
    fun isPriority(): Boolean {
        return catchAll || lockdown || priority > 0
    }
}

