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
import android.content.Context
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.celzero.bravedns.data.BlockedAppInfo
import com.celzero.bravedns.database.AppInfoRepository
import com.celzero.bravedns.database.ConnectionTrackerDAO


/**
 * Custom PagingSource that wraps ConnectionTrackerDAO's blocked apps query
 * and converts BlockedAppResult to BlockedAppInfo with app metadata
 */
class BlockedAppsBubbleViewModel(
    private val connectionTrackerDAO: ConnectionTrackerDAO,
    private val appInfoRepository: AppInfoRepository,
    private val context: Context,
    private val sinceTime: Long,
    private val tempAllowedUids: Set<Int>
) : PagingSource<Int, BlockedAppInfo>() {

    companion object {
        private const val TAG = "BlockedAppsPagingSource"
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, BlockedAppInfo> {
        return try {
            // Always compute fresh temp-allowed uids so the blocked list updates immediately
            // after user taps "Allow" from the bubble.
            val now = System.currentTimeMillis()
            val liveTempAllowedUids = try {
                appInfoRepository.getAllTempAllowedApps(now).map { it.uid }.toSet()
            } catch (_: Exception) {
                // fallback to constructor provided set if repo query fails
                tempAllowedUids
            }

            // Get blocked app results from database
            val blockedResults = connectionTrackerDAO.getRecentlyBlockedApps(sinceTime)

            // Filter out temp allowed apps and convert to BlockedAppInfo
            val blockedApps = blockedResults
                .filter { !liveTempAllowedUids.contains(it.uid) }
                .mapNotNull { result ->
                    try {
                        val appInfo = appInfoRepository.getAppInfoByUid(result.uid)
                        val packageName = appInfo?.packageName ?: "Unknown"
                        val appName = appInfo?.appName ?: getAppNameFromUid(result.uid)

                        BlockedAppInfo(
                            packageName = packageName,
                            appName = appName,
                            uid = result.uid,
                            count = result.count,
                            lastBlocked = result.lastBlocked
                        )
                    } catch (e: Exception) {
                        Logger.e(TAG, "Error getting app info for uid ${result.uid}: ${e.message}", e)
                        null
                    }
                }

            LoadResult.Page(
                data = blockedApps,
                prevKey = null, // Only one page for now
                nextKey = null  // Only one page for now
            )
        } catch (e: Exception) {
            Logger.e(TAG, "Error loading blocked apps: ${e.message}", e)
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, BlockedAppInfo>): Int? {
        return null
    }

    private fun getAppNameFromUid(uid: Int): String {
        return try {
            val packages = context.packageManager.getPackagesForUid(uid)
            if (!packages.isNullOrEmpty()) {
                val packageName = packages[0]
                val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
                context.packageManager.getApplicationLabel(appInfo).toString()
            } else {
                "UID: $uid"
            }
        } catch (e: Exception) {
            Logger.e(TAG, "err getting app name for uid $uid: ${e.message}", e)
            "UID: $uid"
        }
    }
}
