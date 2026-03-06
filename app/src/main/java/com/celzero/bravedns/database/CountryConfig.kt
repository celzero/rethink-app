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

import android.os.Parcel
import android.os.Parcelable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Locale

@Entity(
    tableName = "CountryConfig",
    indices = [
        Index(value = ["cc"], unique = false),
        Index(value = ["isActive"], unique = false)
    ]
)
data class CountryConfig(
    @PrimaryKey
    val id: String,                 // Unique server id: "cc-city-key" (or similar)

    val cc: String,                 // Country code for config-level operations (e.g., "US")

    // Server / location properties (previously RpnWinServer)
    val name: String = "",         // Aggregated location names for a country
    val address: String = "",      // Comma-separated server addresses
    val city: String = "",         // Primary city
    val key: String = "",          // Server key identifier
    val load: Int = 0,              // Server load (0..100, lower is better)
    val link: Int = 0,              // Link metric (latency ms)
    val count: Int = 0,             // Number of endpoints available
    var isActive: Boolean = true,   // Whether server is currently active

    // Country-level configuration flags
    var catchAll: Boolean = false,  // Use this country for all connections
    var lockdown: Boolean = false,  // Force connection through this country only
    var mobileOnly: Boolean = false,// Use only on mobile data
    var ssidBased: Boolean = false, // SSID-based connection enabled (use with ssids field)

    val priority: Int = 0,          // Priority for selection (higher = preferred)

    // SSID configuration (JSON string of SSID items, see SsidItem.kt)
    val ssids: String = "",         // JSON array of SsidItem when ssidBased is true

    val lastModified: Long = System.currentTimeMillis() // Last update timestamp
) : Parcelable {

    constructor(parcel: Parcel) : this(
        parcel.readString()!!,
        parcel.readString()!!,
        parcel.readString()!!,
        parcel.readString()!!,
        parcel.readString()!!,
        parcel.readString()!!,
        parcel.readInt(),
        parcel.readInt(),
        parcel.readInt(),
        parcel.readByte() != 0.toByte(),
        parcel.readByte() != 0.toByte(),
        parcel.readByte() != 0.toByte(),
        parcel.readByte() != 0.toByte(),
        parcel.readByte() != 0.toByte(),
        parcel.readInt(),
        parcel.readString() ?: "",
        parcel.readLong()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(id)
        parcel.writeString(cc)
        parcel.writeString(name)
        parcel.writeString(address)
        parcel.writeString(city)
        parcel.writeString(key)
        parcel.writeInt(load)
        parcel.writeInt(link)
        parcel.writeInt(count)
        parcel.writeByte(if (isActive) 1 else 0)
        parcel.writeByte(if (catchAll) 1 else 0)
        parcel.writeByte(if (lockdown) 1 else 0)
        parcel.writeByte(if (mobileOnly) 1 else 0)
        parcel.writeByte(if (ssidBased) 1 else 0)
        parcel.writeInt(priority)
        parcel.writeString(ssids)
        parcel.writeLong(lastModified)
    }

    override fun describeContents(): Int = 0

    companion object {
        const val TAG = "CountryConfig"

        @JvmField
        val CREATOR = object : Parcelable.Creator<CountryConfig> {
            override fun createFromParcel(parcel: Parcel): CountryConfig {
                return CountryConfig(parcel)
            }

            override fun newArray(size: Int): Array<CountryConfig?> {
                return arrayOfNulls(size)
            }
        }
    }


    // ---- RpnWinServer-style helpers ----

    val countryName: String by lazy { countryDisplayName(cc) }
    val flagEmoji: String by lazy { flagEmojiFor(cc) }
    val serverLocation: String by lazy { city.ifBlank { name } }

    fun getBadgeText(): String {
        return if (link > 0) "${link}ms" else "${load}%"
    }

    enum class ServerQuality { EXCELLENT, GOOD, FAIR, POOR }

    fun getQualityLevel(): ServerQuality {
        return if (link > 0) {
            when {
                link < 50 -> ServerQuality.EXCELLENT
                link < 100 -> ServerQuality.GOOD
                link < 200 -> ServerQuality.FAIR
                else -> ServerQuality.POOR
            }
        } else {
            when {
                load < 30 -> ServerQuality.EXCELLENT
                load < 60 -> ServerQuality.GOOD
                load < 80 -> ServerQuality.FAIR
                else -> ServerQuality.POOR
            }
        }
    }

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

    // ---- CountryConfig-style helpers ----

    /**
     * Check if this country can be used for the current connection
     */
    fun canBeUsed(
        isMobileData: Boolean,
        currentSsid: String?,
        preferredCcs: List<String>
    ): Boolean {
        if (!isActive) return false

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
