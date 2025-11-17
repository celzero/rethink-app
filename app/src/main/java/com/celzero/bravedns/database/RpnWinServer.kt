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
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Locale

/**
 * Room entity for storing RPN WIN server data in database.
 * Maintains persistent storage of server locations, latency, load, and availability.
 * Also serves as the domain model with UI-specific helper methods.
 */
@Entity(
    tableName = "RpnWinServers",
    indices = [
        Index(value = ["countryCode"], unique = false),
        Index(value = ["isActive"], unique = false)
    ]
)
data class RpnWinServer(
    @PrimaryKey
    val id: String,                 // Primary key: "$countryCode-$city-$key"
    val name: String,                // Aggregated location names for a country
    val countryCode: String,         // Country code (e.g., "US")
    val address: String,             // Comma-separated server addresses
    val city: String,                // Primary city
    val key: String,                 // Server key identifier
    val load: Int,                   // Server load (0..100, lower is better)
    val link: Int,                   // Link metric (latency ms)
    val count: Int,                  // Number of endpoints available
    val isActive: Boolean,           // Whether server is currently active
    val lastUpdated: Long = System.currentTimeMillis(), // Timestamp of last update
    @Ignore var isSelected: Boolean = false // UI selection state (not persisted to DB)
) {
    // Secondary constructor for Room (without @Ignore field)
    constructor(
        id: String,
        name: String,
        countryCode: String,
        address: String,
        city: String,
        key: String,
        load: Int,
        link: Int,
        count: Int,
        isActive: Boolean,
        lastUpdated: Long
    ) : this(id, name, countryCode, address, city, key, load, link, count, isActive, lastUpdated, false)

    // Derived presentation fields
    val countryName: String by lazy { countryDisplayName(countryCode) }
    val flagEmoji: String by lazy { flagEmojiFor(countryCode) }
    val serverLocation: String by lazy { if (city.isNotBlank()) city else name }

    fun getBadgeText(): String {
        // Prefer link as latency if provided, else show load as percentage
        return if (link > 0) "${link}ms" else "${load}%"
    }

    fun getQualityLevel(): ServerQuality {
        return if (link > 0) {
            when {
                link < 50 -> ServerQuality.EXCELLENT
                link < 100 -> ServerQuality.GOOD
                link < 200 -> ServerQuality.FAIR
                else -> ServerQuality.POOR
            }
        } else {
            // Use inverse of load if latency not available
            when {
                load < 30 -> ServerQuality.EXCELLENT
                load < 60 -> ServerQuality.GOOD
                load < 80 -> ServerQuality.FAIR
                else -> ServerQuality.POOR
            }
        }
    }

    enum class ServerQuality { EXCELLENT, GOOD, FAIR, POOR }

    private fun flagEmojiFor(cc: String): String {
        if (cc.length != 2) return "\uD83C\uDF10" // globe fallback
        val base = 0x1F1E6 - 'A'.code
        val first = Character.toChars(base + cc[0].uppercaseChar().code)
        val second = Character.toChars(base + cc[1].uppercaseChar().code)
        return String(first) + String(second)
    }

    private fun countryDisplayName(cc: String): String {
        return try { Locale("", cc).displayCountry.ifBlank { cc } } catch (t: Throwable) { cc }
    }
}

