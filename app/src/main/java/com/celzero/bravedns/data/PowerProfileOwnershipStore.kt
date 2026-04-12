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
    val ips: List<String>,
    val appDomains: List<PowerProfileOwnedAppDomainRule> = emptyList(),
    val appIps: List<PowerProfileOwnedAppIpRule> = emptyList(),
    val appFirewalls: List<PowerProfileOwnedAppFirewallRule> = emptyList(),
    val localBlocklistTagIds: List<Int> = emptyList()
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("domains", JSONArray(domains))
            put("ips", JSONArray(ips))
            put(
                "appDomains",
                JSONArray().apply {
                    appDomains.forEach {
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
                "appIps",
                JSONArray().apply {
                    appIps.forEach {
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
                "appFirewalls",
                JSONArray().apply {
                    appFirewalls.forEach {
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
            put("localBlocklistTagIds", JSONArray(localBlocklistTagIds))
        }
    }

    companion object {
        fun empty() = PowerProfileOwnedRules(emptyList(), emptyList())

        fun fromJson(raw: String): PowerProfileOwnedRules {
            return try {
                val json = JSONObject(raw)
                val domainsJson = json.optJSONArray("domains")
                val ipsJson = json.optJSONArray("ips")
                val appDomainsJson = json.optJSONArray("appDomains")
                val appIpsJson = json.optJSONArray("appIps")
                val appFirewallsJson = json.optJSONArray("appFirewalls")
                val localBlocklistTagIdsJson = json.optJSONArray("localBlocklistTagIds")
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
                val localBlocklistTagIds =
                    buildList {
                        if (localBlocklistTagIdsJson != null) {
                            for (index in 0 until localBlocklistTagIdsJson.length()) {
                                val value = localBlocklistTagIdsJson.optInt(index, Int.MIN_VALUE)
                                if (value != Int.MIN_VALUE) add(value)
                            }
                        }
                    }
                val appDomains =
                    buildList {
                        if (appDomainsJson != null) {
                            for (index in 0 until appDomainsJson.length()) {
                                val value = appDomainsJson.optJSONObject(index) ?: continue
                                val packageName = value.optString("packageName", "").trim()
                                val domain = value.optString("domain", "").trim().lowercase()
                                if (packageName.isNotEmpty() && domain.isNotEmpty()) {
                                    add(PowerProfileOwnedAppDomainRule(packageName, domain))
                                }
                            }
                        }
                    }
                val appIps =
                    buildList {
                        if (appIpsJson != null) {
                            for (index in 0 until appIpsJson.length()) {
                                val value = appIpsJson.optJSONObject(index) ?: continue
                                val packageName = value.optString("packageName", "").trim()
                                val ipAddress = value.optString("ipAddress", "").trim().lowercase()
                                val port = value.optInt("port", Int.MIN_VALUE)
                                if (packageName.isNotEmpty() && ipAddress.isNotEmpty() && port != Int.MIN_VALUE) {
                                    add(PowerProfileOwnedAppIpRule(packageName, ipAddress, port))
                                }
                            }
                        }
                    }
                val appFirewalls =
                    buildList {
                        if (appFirewallsJson != null) {
                            for (index in 0 until appFirewallsJson.length()) {
                                val value = appFirewallsJson.optJSONObject(index) ?: continue
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
                PowerProfileOwnedRules(
                    domains = domains,
                    ips = ips,
                    appDomains = appDomains,
                    appIps = appIps,
                    appFirewalls = appFirewalls,
                    localBlocklistTagIds = localBlocklistTagIds
                )
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
        val appDomains = linkedMapOf<String, PowerProfileOwnedAppDomainRule>()
        val appIps = linkedMapOf<String, PowerProfileOwnedAppIpRule>()
        val appFirewalls = linkedMapOf<String, PowerProfileOwnedAppFirewallRule>()
        val localBlocklistTagIds = linkedSetOf<Int>()
        profileIds.forEach { profileId ->
            val ownedRules = read(context, profileId)
            domains.addAll(ownedRules.domains)
            ips.addAll(ownedRules.ips)
            ownedRules.appDomains.forEach { appDomains[it.key()] = it }
            ownedRules.appIps.forEach { appIps[it.key()] = it }
            ownedRules.appFirewalls.forEach { appFirewalls[it.key()] = it }
            localBlocklistTagIds.addAll(ownedRules.localBlocklistTagIds)
        }
        return PowerProfileOwnedRules(
            domains = domains.toList(),
            ips = ips.toList(),
            appDomains = appDomains.values.toList(),
            appIps = appIps.values.toList(),
            appFirewalls = appFirewalls.values.toList(),
            localBlocklistTagIds = localBlocklistTagIds.toList()
        )
    }

    private fun profileDirectory(context: Context): File {
        return File(context.filesDir, DIRECTORY_NAME)
    }

    private fun profileFile(context: Context, profileId: String): File {
        return File(profileDirectory(context), "$profileId.json")
    }
}
