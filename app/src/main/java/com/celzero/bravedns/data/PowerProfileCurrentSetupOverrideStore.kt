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

data class PowerProfileCurrentSetupOverrides(
    val disabledDomains: List<String> = emptyList(),
    val disabledIps: List<String> = emptyList(),
    val disabledAppDomains: List<PowerProfileOwnedAppDomainRule> = emptyList(),
    val disabledAppIps: List<PowerProfileOwnedAppIpRule> = emptyList(),
    val disabledAppFirewalls: List<PowerProfileOwnedAppFirewallRule> = emptyList(),
    val disabledLocalBlocklistTagIds: List<Int> = emptyList()
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("disabledDomains", JSONArray(disabledDomains))
            put("disabledIps", JSONArray(disabledIps))
            put(
                "disabledAppDomains",
                JSONArray().apply {
                    disabledAppDomains.forEach {
                        put(
                            JSONObject().apply {
                                put("packageName", it.packageName)
                                put("domain", it.domain)
                            }
                        )
                    }
                }
            )
            put(
                "disabledAppIps",
                JSONArray().apply {
                    disabledAppIps.forEach {
                        put(
                            JSONObject().apply {
                                put("packageName", it.packageName)
                                put("ipAddress", it.ipAddress)
                                put("port", it.port)
                            }
                        )
                    }
                }
            )
            put(
                "disabledAppFirewalls",
                JSONArray().apply {
                    disabledAppFirewalls.forEach {
                        put(
                            JSONObject().apply {
                                put("packageName", it.packageName)
                                put("firewallStatus", it.firewallStatus)
                                put("connectionStatus", it.connectionStatus)
                            }
                        )
                    }
                }
            )
            put("disabledLocalBlocklistTagIds", JSONArray(disabledLocalBlocklistTagIds))
        }
    }

    companion object {
        fun fromJson(raw: String): PowerProfileCurrentSetupOverrides {
            return try {
                val json = JSONObject(raw)
                val disabledDomainsJson = json.optJSONArray("disabledDomains")
                val disabledIpsJson = json.optJSONArray("disabledIps")
                val disabledAppDomainsJson = json.optJSONArray("disabledAppDomains")
                val disabledAppIpsJson = json.optJSONArray("disabledAppIps")
                val disabledAppFirewallsJson = json.optJSONArray("disabledAppFirewalls")
                val disabledJson = json.optJSONArray("disabledLocalBlocklistTagIds")
                val disabledDomains =
                    buildList {
                        if (disabledDomainsJson != null) {
                            for (index in 0 until disabledDomainsJson.length()) {
                                val value = disabledDomainsJson.optString(index, "").trim().lowercase()
                                if (value.isNotEmpty()) add(value)
                            }
                        }
                    }
                val disabledIps =
                    buildList {
                        if (disabledIpsJson != null) {
                            for (index in 0 until disabledIpsJson.length()) {
                                val value = disabledIpsJson.optString(index, "").trim().lowercase()
                                if (value.isNotEmpty()) add(value)
                            }
                        }
                    }
                val disabledAppDomains =
                    buildList {
                        if (disabledAppDomainsJson != null) {
                            for (index in 0 until disabledAppDomainsJson.length()) {
                                val value = disabledAppDomainsJson.optJSONObject(index) ?: continue
                                val packageName = value.optString("packageName", "").trim()
                                val domain = value.optString("domain", "").trim().lowercase()
                                if (packageName.isNotEmpty() && domain.isNotEmpty()) {
                                    add(PowerProfileOwnedAppDomainRule(packageName, domain))
                                }
                            }
                        }
                    }
                val disabledAppIps =
                    buildList {
                        if (disabledAppIpsJson != null) {
                            for (index in 0 until disabledAppIpsJson.length()) {
                                val value = disabledAppIpsJson.optJSONObject(index) ?: continue
                                val packageName = value.optString("packageName", "").trim()
                                val ipAddress = value.optString("ipAddress", "").trim().lowercase()
                                val port = value.optInt("port", Int.MIN_VALUE)
                                if (packageName.isNotEmpty() && ipAddress.isNotEmpty() && port != Int.MIN_VALUE) {
                                    add(PowerProfileOwnedAppIpRule(packageName, ipAddress, port))
                                }
                            }
                        }
                    }
                val disabledAppFirewalls =
                    buildList {
                        if (disabledAppFirewallsJson != null) {
                            for (index in 0 until disabledAppFirewallsJson.length()) {
                                val value = disabledAppFirewallsJson.optJSONObject(index) ?: continue
                                val packageName = value.optString("packageName", "").trim()
                                val firewallStatus = value.optInt("firewallStatus", Int.MIN_VALUE)
                                val connectionStatus =
                                    value.optInt("connectionStatus", Int.MIN_VALUE)
                                if (
                                    packageName.isNotEmpty() &&
                                        firewallStatus != Int.MIN_VALUE &&
                                        connectionStatus != Int.MIN_VALUE
                                ) {
                                    add(
                                        PowerProfileOwnedAppFirewallRule(
                                            packageName = packageName,
                                            firewallStatus = firewallStatus,
                                            connectionStatus = connectionStatus
                                        )
                                    )
                                }
                            }
                        }
                    }
                val disabled =
                    buildList {
                        if (disabledJson != null) {
                            for (index in 0 until disabledJson.length()) {
                                val value = disabledJson.optInt(index, Int.MIN_VALUE)
                                if (value != Int.MIN_VALUE) add(value)
                            }
                        }
                    }
                PowerProfileCurrentSetupOverrides(
                    disabledDomains = disabledDomains,
                    disabledIps = disabledIps,
                    disabledAppDomains = disabledAppDomains,
                    disabledAppIps = disabledAppIps,
                    disabledAppFirewalls = disabledAppFirewalls,
                    disabledLocalBlocklistTagIds = disabled
                )
            } catch (_: Exception) {
                PowerProfileCurrentSetupOverrides()
            }
        }
    }
}

object PowerProfileCurrentSetupOverrideStore {
    private const val FILE_NAME = "power-profile-current-setup-overrides.json"

    fun read(context: Context): PowerProfileCurrentSetupOverrides {
        val file = overridesFile(context)
        if (!file.exists()) return PowerProfileCurrentSetupOverrides()
        return PowerProfileCurrentSetupOverrides.fromJson(file.readText())
    }

    fun write(context: Context, overrides: PowerProfileCurrentSetupOverrides) {
        val file = overridesFile(context)
        file.parentFile?.mkdirs()
        file.writeText(overrides.toJson().toString())
    }

    fun clear(context: Context) {
        overridesFile(context).delete()
    }

    private fun overridesFile(context: Context): File {
        return File(context.filesDir, FILE_NAME)
    }
}
