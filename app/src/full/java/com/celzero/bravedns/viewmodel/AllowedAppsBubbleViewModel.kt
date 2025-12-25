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
import com.celzero.bravedns.data.AllowedAppInfo
import com.celzero.bravedns.database.AppInfoRepository


/**
 * Custom PagingSource that wraps AppInfoRepository's temp allowed apps query
 * and converts AppInfo to AllowedAppInfo
 */
class AllowedAppsBubbleViewModel(
    private val appInfoRepository: AppInfoRepository,
    private val now: Long
) : PagingSource<Int, AllowedAppInfo>() {

    companion object {
        private const val TAG = "AllowedAppsPagingSource"
        private const val TEMP_ALLOW_MS = 15 * 60 * 1000L
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, AllowedAppInfo> {
        return try {
            // DB returns one row per package; collapse to one row per UID.
            val tempAllowedApps = appInfoRepository.getAllTempAllowedApps(now)

            val byUid = tempAllowedApps.groupBy { it.uid }

            val allowedApps = byUid.values.mapNotNull { appsForUid ->
                val primary = appsForUid.firstOrNull() ?: return@mapNotNull null

                // If multiple packages share this UID, show "App + N other apps".
                val otherCount = (appsForUid.size - 1).coerceAtLeast(0)
                val displayName =
                    if (otherCount > 0) "${primary.appName} + $otherCount other apps" else primary.appName

                // tempAllowExpiryTime is stored per row; for UID aggregation, take the max.
                val expiry = appsForUid.maxOfOrNull { it.tempAllowExpiryTime } ?: primary.tempAllowExpiryTime

                AllowedAppInfo(
                    packageName = primary.packageName,
                    appName = displayName,
                    uid = primary.uid,
                    allowedAt = expiry - TEMP_ALLOW_MS,
                    otherAppsCount = otherCount
                )
            }.sortedByDescending { it.allowedAt }

            LoadResult.Page(
                data = allowedApps,
                prevKey = null,
                nextKey = null
            )
        } catch (e: Exception) {
            Logger.e(TAG, "Error loading allowed apps: ${e.message}", e)
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, AllowedAppInfo>): Int? = null
}
