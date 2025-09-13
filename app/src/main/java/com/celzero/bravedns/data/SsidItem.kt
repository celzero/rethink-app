/*
 * Copyright 2023 RethinkDNS and its authors
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
package com.celzero.bravedns.data

data class SsidItem(
    val name: String,
    val type: SsidType
) {
    enum class SsidType(val id: String, val displayName: String) {
        STRING("string", "String"),
        WILDCARD("wildcard", "Wildcard");

        companion object {
            fun fromIdentifier(identifier: String): SsidType {
                return entries.find { it.id == identifier } ?: STRING
            }
        }
    }

    fun toStorageString(): String {
        return "$name##${type.id}"
    }

    companion object {
        fun fromStorageString(storage: String): SsidItem? {
            val parts = storage.split("##")
            if (parts.size != 2) return null

            val name = parts[0].trim()
            val type = SsidType.fromIdentifier(parts[1].trim())

            if (name.isEmpty()) return null

            return SsidItem(name, type)
        }

        fun parseStorageList(storageString: String): List<SsidItem> {
            if (storageString.isBlank()) return emptyList()

            return storageString.split(',', '\n', '\r')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { fromStorageString(it) }
                .distinct()
        }

        fun toStorageList(ssidItems: List<SsidItem>): String {
            return ssidItems.joinToString(",") { it.toStorageString() }
        }
    }
}
