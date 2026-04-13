/*
 * Copyright 2026 RethinkDNS and its authors
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

data class SavedPowerProfile(
    val id: String,
    val name: String,
    val note: String,
    val createdAt: Long,
    val protectionStatus: String,
    val engineMode: String
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("note", note)
            put("createdAt", createdAt)
            put("protectionStatus", protectionStatus)
            put("engineMode", engineMode)
        }
    }

    companion object {
        fun fromJson(json: JSONObject?): SavedPowerProfile? {
            if (json == null) return null

            val id = json.optString("id", "").trim()
            val name = json.optString("name", "").trim()
            val note = json.optString("note", "").trim()
            val createdAt = json.optLong("createdAt", 0L)
            val protectionStatus = json.optString("protectionStatus", "").trim()
            val engineMode = json.optString("engineMode", "").trim()

            if (id.isEmpty() || name.isEmpty() || createdAt <= 0L) return null

            return SavedPowerProfile(
                id = id,
                name = name,
                note = note,
                createdAt = createdAt,
                protectionStatus = protectionStatus,
                engineMode = engineMode
            )
        }

        fun parseStorageList(storageString: String): List<SavedPowerProfile> {
            if (storageString.isBlank()) return emptyList()

            return try {
                val jsonArray = JSONArray(storageString)
                (0 until jsonArray.length()).mapNotNull { index ->
                    fromJson(jsonArray.optJSONObject(index))
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun toStorageList(profiles: List<SavedPowerProfile>): String {
            val jsonArray = JSONArray()
            profiles.forEach { jsonArray.put(it.toJson()) }
            return jsonArray.toString()
        }
    }
}
