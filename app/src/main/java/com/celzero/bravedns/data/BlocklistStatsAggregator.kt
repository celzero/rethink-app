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

import com.celzero.bravedns.database.BlocklistAttribution
import com.celzero.bravedns.database.BlocklistComboCount

/**
 * Turns raw `name:tag` combination rows from the log tables into per-blocklist
 * totals. SQLite cannot split the CSV token list portably across all supported
 * API levels, so the split happens here, on demand, only while the stats screen
 * is visible; the source tables stay untouched.
 *
 * A blocked query attributed to multiple lists contributes its count to every
 * matching list, mirroring how the DNS log bottom sheet presents matched
 * blocklists.
 */
object BlocklistStatsAggregator {

    /** Aggregated per-blocklist usage for one time window. */
    data class BlocklistStat(
        val name: String,
        val count: Int,
        val tagCount: Int
    )

    /**
     * Sums blocked-query counts per blocklist name. Source is DnsLogs only:
     * firewall-level echoes of the same DNS block live in ConnectionTracker
     * (Rule #2G), so including them would double-count every block.
     * Names are grouped case-insensitively ("Privacy" and "privacy" are one
     * entry; the first-seen casing is displayed). Tokens without a `name:tag`
     * shape are skipped, same as the log bottom sheets do. Result is ordered
     * by blocked count, descending.
     *
     * A blocked query attributed to multiple lists contributes its count to
     * every matching list — but only once per list, even when several tags of
     * the same list matched. Without that, the per-list total would exceed the
     * sum of the per-app/per-domain breakdowns, which count each blocked
     * query exactly once.
     *
     * When [tagFilter] is non-empty, only tokens whose tag is in the filter
     * contribute; tokens without any tag are dropped. An empty filter keeps
     * every token.
     */
    fun aggregate(
        combos: List<BlocklistComboCount>,
        tagFilter: Set<String> = emptySet()
    ): List<BlocklistStat> {
        val filterKeys = tagFilter.mapTo(mutableSetOf()) { it.lowercase() }
        val countByKey = HashMap<String, Int>()
        val displayByKey = HashMap<String, String>()
        val tagsByKey = HashMap<String, MutableSet<String>>()

        combos.forEach { entry ->
            // collect the distinct list names found in this row's CSV first,
            // so the row's count is added once per name regardless of how many
            // of its tags matched
            val namesInRow = LinkedHashMap<String, MutableSet<String>>()
            val displayInRow = HashMap<String, String>()
            entry.combo.split(",").forEach { rawToken ->
                val token = rawToken.trim()
                if (token.isEmpty()) return@forEach
                val colon = token.indexOf(':')
                val name = if (colon >= 0) token.substring(0, colon) else token
                if (name.isEmpty()) return@forEach
                val tag = if (colon >= 0) token.substring(colon + 1).lowercase() else ""
                if (filterKeys.isNotEmpty() && tag !in filterKeys) return@forEach
                val key = name.lowercase()
                displayInRow.putIfAbsent(key, name)
                namesInRow.getOrPut(key) { mutableSetOf() }
                // count distinct tags, not distinct tokens: the same list can
                // appear under different name casings ("Privacy:x", "privacy:x")
                if (tag.isNotEmpty()) namesInRow.getValue(key).add(tag)
            }
            namesInRow.forEach { (key, tags) ->
                displayByKey.putIfAbsent(key, displayInRow[key] ?: key)
                countByKey[key] = (countByKey[key] ?: 0) + entry.count
                if (tags.isNotEmpty()) {
                    tagsByKey.getOrPut(key) { mutableSetOf() }.addAll(tags)
                }
            }
        }

        return countByKey.map { (key, count) ->
            BlocklistStat(displayByKey[key] ?: key, count, tagsByKey[key]?.size ?: 1)
        }.sortedByDescending { it.count }
    }

    /**
     * Projects aggregated stats into the shape the stats sections render.
     * `flag` carries the tag count so rows can show a "n lists" subtitle
     * without a dedicated model.
     */
    fun toAppConnections(stats: List<BlocklistStat>): List<AppConnection> {
        return stats.map {
            AppConnection(
                uid = -1,
                ipAddress = "",
                port = 0,
                count = it.count,
                flag = it.tagCount.toString(),
                blocked = true,
                appOrDnsName = it.name
            )
        }
    }

    /**
     * Escapes LIKE wildcards in a blocklist name before it is embedded into a
     * token-matching pattern, so names containing `%`/`_`/`\` match literally.
     */
    fun escapeForLike(name: String): String {
        return name.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }

    /**
     * Extracts the distinct tag values (`name:tag` tokens) attributed to one
     * blocklist name from raw combo strings, ordered by how many blocked
     * queries each tag accounts for. Both name and tag match case-insensitively
     * and tags dedupe case-insensitively, so the chip count always equals the
     * "n lists" hint computed by [aggregate].
     */
    fun tagsForBlocklist(combos: List<String>, name: String): List<String> {
        val countByTagKey = HashMap<String, Int>()
        val displayByTagKey = HashMap<String, String>()
        combos.forEach { entry ->
            entry.split(",").forEach { rawToken ->
                val token = rawToken.trim()
                val colon = token.indexOf(':')
                if (colon <= 0) return@forEach
                if (colon != name.length) return@forEach
                if (!token.regionMatches(0, name, 0, name.length, ignoreCase = true)) {
                    return@forEach
                }
                val tag = token.substring(colon + 1)
                if (tag.isEmpty()) return@forEach
                val tagKey = tag.lowercase()
                displayByTagKey.putIfAbsent(tagKey, tag)
                countByTagKey[tagKey] = (countByTagKey[tagKey] ?: 0) + 1
            }
        }
        return countByTagKey.entries
            .sortedByDescending { it.value }
            .map { displayByTagKey[it.key] ?: it.key }
    }

    /**
     * Builds the LIKE pattern that matches a blocklist's tokens inside the
     * comma-delimited `blockLists` column. Without a tag the pattern matches
     * any `name:*` token; with a tag it matches exactly `name:tag`. Parts are
     * escaped so `%`/`_`/`\` in names and tags match literally.
     */
    fun tokenLikePattern(name: String, tag: String? = null): String {
        val n = escapeForLike(name)
        return if (tag.isNullOrEmpty()) {
            "%,$n:%"
        } else {
            "%,$n:${escapeForLike(tag)},%"
        }
    }

    /**
     * True when the row's `blockLists` CSV contains a `name:tag` token whose
     * tag is one of [tags] (name and tag compared case-insensitively, same
     * semantics as the SQL LIKE used for the initial fetch).
     */
    private fun BlocklistAttribution.matches(name: String, tags: Set<String>): Boolean {
        // chip labels arrive in their original casing; match on lowercase keys
        val tagKeys = tags.mapTo(mutableSetOf()) { it.lowercase() }
        blockLists.split(",").forEach { rawToken ->
            val token = rawToken.trim()
            val colon = token.indexOf(':')
            if (colon <= 0) return@forEach
            if (colon != name.length) return@forEach
            if (!token.regionMatches(0, name, 0, name.length, ignoreCase = true)) {
                return@forEach
            }
            if (token.substring(colon + 1).lowercase() in tagKeys) return true
        }
        return false
    }

    /**
     * Per-app blocked counts from raw attribution rows, restricted to the
     * selected tag filter. Counts are aggregated here (one row per blocked
     * query) so app totals always add up to the blocklist total.
     */
    fun appBreakdown(
        rows: List<BlocklistAttribution>,
        name: String,
        tags: Set<String>
    ): List<AppConnection> {
        val countByUid = HashMap<Int, Int>()
        val nameByUid = HashMap<Int, String>()
        rows.forEach { row ->
            if (!row.matches(name, tags)) return@forEach
            countByUid[row.uid] = (countByUid[row.uid] ?: 0) + 1
            nameByUid.putIfAbsent(row.uid, row.appName)
        }
        return countByUid.map { (uid, count) ->
            AppConnection(
                uid = uid,
                ipAddress = "",
                port = 0,
                count = count,
                flag = "",
                blocked = true,
                appOrDnsName = nameByUid[uid]
            )
        }.sortedByDescending { it.count }
    }

    /**
     * Per-domain blocked counts for one app from raw attribution rows,
     * restricted to the selected tag filter.
     */
    fun domainBreakdown(
        rows: List<BlocklistAttribution>,
        name: String,
        tags: Set<String>,
        uid: Int
    ): List<AppConnection> {
        val countByDomain = HashMap<String, Int>()
        rows.forEach { row ->
            if (row.uid != uid) return@forEach
            if (!row.matches(name, tags)) return@forEach
            // mirrors the SQL RTRIM(queryStr, '.') used by the other sections
            val domain = row.queryStr.trimEnd('.')
            if (domain.isEmpty()) return@forEach
            countByDomain[domain] = (countByDomain[domain] ?: 0) + 1
        }
        return countByDomain.map { (domain, count) ->
            AppConnection(
                uid = -1,
                ipAddress = "",
                port = 0,
                count = count,
                flag = "",
                blocked = true,
                appOrDnsName = domain
            )
        }.sortedByDescending { it.count }
    }
}
