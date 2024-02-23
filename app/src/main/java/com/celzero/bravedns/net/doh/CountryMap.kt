/*
Copyright 2018 Jigsaw Operations LLC

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
package com.celzero.bravedns.net.doh

import android.content.res.AssetManager
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.nio.charset.StandardCharsets

/**
 * Map IP addresses to country codes, using a fixed-width sorted database. Lookups are performed
 * using a binary search. The database requires about 5 MB of RAM, 2 MB compressed in the APK. A
 * carefully designed tree-based representation could probably save a factor of 4. Note that this
 * class is not used by the service, so it should only contribute to RAM usage when the UI is
 * visible.
 */
class CountryMap(assetManager: AssetManager) {
    private val v4db: ByteArray
    private val v6db: ByteArray

    init {
        v4db = read(assetManager.open("dbip.v4"))
        v6db = read(assetManager.open("dbip.v6"))
    }

    fun getCountryCode(address: InetAddress?): String? {
        if (address == null) return null
        val key = address.address
        val db = if (key.size == 4) v4db else v6db
        val recordSize = key.size + COUNTRY_SIZE
        var low = 0
        var high = db.size / recordSize
        while (high - low > 1) {
            val mid = (low + high) / 2
            val position = mid * recordSize
            if (lessEqual(db, position, key)) {
                low = mid
            } else {
                high = mid
            }
        }
        val position = low * recordSize + key.size
        val countryCode = db.copyOfRange(position, position + COUNTRY_SIZE)
        return String(countryCode, StandardCharsets.UTF_8)
    }

    companion object {
        // Number of bytes used to store each country string.
        private const val COUNTRY_SIZE = 2

        @Throws(IOException::class)
        private fun read(input: InputStream): ByteArray {
            val buffer = ByteArrayOutputStream()
            var n: Int
            val temp = ByteArray(4096)
            while (input.read(temp, 0, temp.size).also { n = it } != -1) {
                buffer.write(temp, 0, n)
            }
            return buffer.toByteArray()
        }

        /**
         * Compares two arrays of equal length. The first is an entry in a database, specified by
         * the index of its first byte. The second is a standalone array.
         *
         * @return The lexicographic comparison of the two arrays.
         */
        private fun lessEqual(db: ByteArray, position: Int, key: ByteArray): Boolean {
            for (i in key.indices) {
                val ai = db[position + i].toInt() and 0xFF
                val bi = key[i].toInt() and 0xFF
                if (ai < bi) {
                    return true
                }
                if (ai > bi) {
                    return false
                }
            }
            return true
        }
    }
}
