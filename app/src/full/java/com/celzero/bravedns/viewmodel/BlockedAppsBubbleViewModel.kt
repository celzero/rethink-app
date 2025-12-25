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
package com.celzero.bravedns.viewmodel

import Logger
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.celzero.bravedns.data.BlockedAppInfo
import com.celzero.bravedns.database.AppInfoRepository
import com.celzero.bravedns.database.ConnectionTrackerDAO
import com.celzero.bravedns.database.DnsLogDAO
import com.celzero.bravedns.service.FirewallManager

/**
 * PagingSource for the Bubble blocked-apps list.
 *
 * Consolidates blocked apps from:
 * - ConnectionTracker (firewall / IP connections)
 * - DnsLogs (DNS blocks)
 *
 * De-duplicates by uid, sums counts, and uses the latest blocked timestamp.
 *
 * IMPORTANT: Do not use PackageManager here; use FirewallManager (in-memory appInfos) as the
 * canonical source for app labels and uid->package aggregation.
 */
class BlockedAppsBubbleViewModel(
    private val connectionTrackerDAO: ConnectionTrackerDAO,
    private val dnsLogDAO: DnsLogDAO,
    private val appInfoRepository: AppInfoRepository,
    private val sinceTime: Long,
    private val tempAllowedUids: Set<Int>
) : PagingSource<Int, BlockedAppInfo>() {

    companion object {
        private const val TAG = "BlockedAppsPagingSource"
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, BlockedAppInfo> {
        return try {
            val now = System.currentTimeMillis()
            val liveTempAllowedUids = try {
                appInfoRepository.getAllTempAllowedApps(now).map { it.uid }.toSet()
            } catch (_: Exception) {
                tempAllowedUids
            }

            // Fetch recent blocked from both sources.
            val connBlocked = connectionTrackerDAO.getRecentlyBlockedApps(sinceTime)
            val dnsBlocked = runCatching { dnsLogDAO.getRecentlyBlockedDnsApps(sinceTime) }
                .getOrElse {
                    Logger.w(TAG, "dns blocked query failed: ${it.message}")
                    emptyList()
                }

            // Merge by UID. Sum counts, take max(lastBlocked).
            val merged = LinkedHashMap<Int, Pair<Long, Int>>()
            fun merge(uid: Int, last: Long, count: Int) {
                val prev = merged[uid]
                if (prev == null) {
                    merged[uid] = last to count
                } else {
                    merged[uid] = maxOf(prev.first, last) to (prev.second + count)
                }
            }

            connBlocked.forEach { merge(it.uid, it.lastBlocked, it.count) }
            dnsBlocked.forEach { merge(it.uid, it.lastBlocked, it.count) }

            val blockedApps = merged
                .entries
                .sortedByDescending { it.value.first }
                .take(10)
                .filter { !liveTempAllowedUids.contains(it.key) }
                .map { (uid, meta) ->
                    val (lastBlocked, count) = meta
                    val packageNames = FirewallManager.getPackageNamesByUid(uid)
                    val appNames = FirewallManager.getAppNamesByUid(uid)

                    val otherCount = (packageNames.size - 1).coerceAtLeast(0)
                    val baseName = appNames.firstOrNull() ?: "Unknown"
                    val appName = if (otherCount > 0) "$baseName + $otherCount other apps" else baseName

                    BlockedAppInfo(
                        packageName = packageNames.firstOrNull() ?: "Unknown" ,
                        appName = appName,
                        uid = uid,
                        count = count,
                        lastBlocked = lastBlocked
                    )
                }

            LoadResult.Page(
                data = blockedApps,
                prevKey = null,
                nextKey = null
            )
        } catch (e: Exception) {
            Logger.e(TAG, "Error loading blocked apps: ${e.message}", e)
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, BlockedAppInfo>): Int? = null
}
