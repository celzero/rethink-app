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

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class PowerProfileOwnedRules(
    val domains: List<String>,
    val ips: List<String>
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("domains", JSONArray(domains))
            put("ips", JSONArray(ips))
        }
    }

    companion object {
        fun empty() = PowerProfileOwnedRules(emptyList(), emptyList())

        fun fromJson(raw: String): PowerProfileOwnedRules {
            return try {
                val json = JSONObject(raw)
                val domainsJson = json.optJSONArray("domains")
                val ipsJson = json.optJSONArray("ips")
                val domains =
                    buildList {
                        if (domainsJson != null) {
                            for (index in 0 until domainsJson.length()) {
                                val value = domainsJson.optString(index, "").trim().lowercase()
                                if (value.isNotEmpty()) add(value)
                            }
                        }
                    }
                val ips =
                    buildList {
                        if (ipsJson != null) {
                            for (index in 0 until ipsJson.length()) {
                                val value = ipsJson.optString(index, "").trim().lowercase()
                                if (value.isNotEmpty()) add(value)
                            }
                        }
                    }
                PowerProfileOwnedRules(domains, ips)
            } catch (_: Exception) {
                empty()
            }
        }
    }
}

object PowerProfileOwnershipStore {
    private const val DIRECTORY_NAME = "power-profile-ownership"

    fun read(context: Context, profileId: String): PowerProfileOwnedRules {
        val file = profileFile(context, profileId)
        if (!file.exists()) return PowerProfileOwnedRules.empty()
        return PowerProfileOwnedRules.fromJson(file.readText())
    }

    fun write(context: Context, profileId: String, ownedRules: PowerProfileOwnedRules) {
        val directory = profileDirectory(context)
        if (!directory.exists()) directory.mkdirs()
        profileFile(context, profileId).writeText(ownedRules.toJson().toString())
    }

    fun delete(context: Context, profileId: String) {
        profileFile(context, profileId).delete()
    }

    fun aggregateOwnership(context: Context, profileIds: List<String>): PowerProfileOwnedRules {
        val domains = linkedSetOf<String>()
        val ips = linkedSetOf<String>()
        profileIds.forEach { profileId ->
            val ownedRules = read(context, profileId)
            domains.addAll(ownedRules.domains)
            ips.addAll(ownedRules.ips)
        }
        return PowerProfileOwnedRules(domains.toList(), ips.toList())
    }

    private fun profileDirectory(context: Context): File {
        return File(context.filesDir, DIRECTORY_NAME)
    }

    private fun profileFile(context: Context, profileId: String): File {
        return File(profileDirectory(context), "$profileId.json")
    }
}

