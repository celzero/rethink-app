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
        Index(value = ["isActive"], unique = false),
        Index(value = ["isFavourite"], unique = false)
    ]
)
data class CountryConfig(
    @PrimaryKey
    val id: String, // Unique server id: "cc-city-key" (or similar)

    val cc: String, // Country code for config-level operations (e.g., "US")

    // Server / location properties (previously RpnWinServer)
    val name: String = "", // Aggregated location names for a country
    val address: String = "", // Comma-separated server addresses
    val city: String = "", // Primary city
    val key: String = "", // Server key identifier
    val load: Int = 0, // Server load (0..100, lower is better)
    val link: Int = 0, // Link metric (latency ms)
    val count: Int = 0, // Number of endpoints available
    val premium: Boolean = false, // Whether server is premium
    var isActive: Boolean = true, // Whether server is currently active
    var isEnabled: Boolean = false, // Whether server is chosen by user

    // Country-level configuration flags
    var catchAll: Boolean = false, // Use this country for all connections
    var lockdown: Boolean = false, // Force connection through this country only or block
    var mobileOnly: Boolean = false,// Use only on mobile data
    var ssidBased: Boolean = false, // SSID-based connection enabled (use with ssids field)

    val priority: Int = 0, // Priority for selection (higher = preferred)

    // SSID configuration (JSON string of SSID items, see SsidItem.kt)
    val ssids: String = "", // JSON array of SsidItem when ssidBased is true

    val lastModified: Long = System.currentTimeMillis(), // Last update timestamp

    val selectionCount: Int = 0, // Number of times this country has been selected by the user

    var isFavourite: Boolean = false, // Whether this country is marked as a favourite by the user
    var hopEnabled: Boolean = false // Whether to always hop
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
        parcel.readByte() != 0.toByte(),
        parcel.readByte() != 0.toByte(),
        parcel.readInt(),
        parcel.readString() ?: "",
        parcel.readLong(),
        parcel.readInt(),
        parcel.readByte() != 0.toByte(),
        parcel.readByte() != 0.toByte()
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
        parcel.writeByte(if (premium) 1 else 0)
        parcel.writeByte(if (isActive) 1 else 0)
        parcel.writeByte(if (isEnabled) 1 else 0)
        parcel.writeByte(if (catchAll) 1 else 0)
        parcel.writeByte(if (lockdown) 1 else 0)
        parcel.writeByte(if (mobileOnly) 1 else 0)
        parcel.writeByte(if (ssidBased) 1 else 0)
        parcel.writeInt(priority)
        parcel.writeString(ssids)
        parcel.writeLong(lastModified)
        parcel.writeInt(selectionCount)
        parcel.writeByte(if (isFavourite) 1 else 0)
        parcel.writeByte(if (hopEnabled) 1 else 0)
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

    val countryName: String by lazy { countryDisplayName(cc) }
    val flagEmoji: String by lazy { flagEmojiFor(cc) }
    val serverLocation: String by lazy { city.ifBlank { name } }

    private fun flagEmojiFor(cc: String): String {
        if (cc.length != 2) return "\uD83C\uDF10" // globe fallback
        val base = 0x1F1E6 - 'A'.code
        val first = Character.toChars(base + cc[0].uppercaseChar().code)
        val second = Character.toChars(base + cc[1].uppercaseChar().code)
        return String(first) + String(second)
    }

    private fun countryDisplayName(cc: String): String {
        return try { Locale("", cc).displayCountry.ifBlank { cc } } catch (_: Throwable) { cc }
    }
}
