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
package com.celzero.bravedns.data

import org.json.JSONArray
import org.json.JSONObject

data class SsidItem(
    val name: String,
    val type: SsidType
) {
    // [{"name":"pgdd","type":"equal_wildcard"},{"name":"hhjhy","type":"equal_exact"},{"name":"test","type":"notequal_exact"}]
    enum class SsidType(val id: String, val displayName: String, val isEqual: Boolean, val isExact: Boolean) {
        EQUAL_EXACT("equal_exact", "Equal - Exact", true, true),
        EQUAL_WILDCARD("equal_wildcard", "Equal - Wildcard", true, false),
        NOTEQUAL_EXACT("notequal_exact", "Not Equal - Exact", false, true),
        NOTEQUAL_WILDCARD("notequal_wildcard", "Not Equal - Wildcard", false, false);

        companion object {
            fun fromIdentifier(identifier: String): SsidType {
                return when (identifier) {
                    "equal_exact" -> EQUAL_EXACT
                    "equal_wildcard" -> EQUAL_WILDCARD
                    "notequal_exact" -> NOTEQUAL_EXACT
                    "notequal_wildcard" -> NOTEQUAL_WILDCARD
                    // Legacy support for old format
                    "exact" -> EQUAL_EXACT
                    "wildcard" -> EQUAL_WILDCARD
                    "notequal" -> NOTEQUAL_EXACT
                    else -> EQUAL_WILDCARD
                }
            }
        }
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("name", name)
            put("type", type.id)
        }
    }

    companion object {

        fun fromJson(json: JSONObject): SsidItem? {
            val name = json.optString("name", "").trim()
            val typeId = json.optString("type", "").trim()

            if (name.isEmpty()) return null
            val type = SsidType.fromIdentifier(typeId)

            return SsidItem(name, type)
        }

        fun parseStorageList(storageString: String): List<SsidItem> {
            if (storageString.isBlank()) return emptyList()

            return try {
                val jsonArray = JSONArray(storageString)
                (0 until jsonArray.length()).mapNotNull { index ->
                    fromJson(jsonArray.optJSONObject(index))
                }.distinct()
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun toStorageList(ssidItems: List<SsidItem>): String {
            val jsonArray = JSONArray()
            ssidItems.forEach { jsonArray.put(it.toJson()) }
            return jsonArray.toString()
        }
    }
}
