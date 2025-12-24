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
    private val now: Long,
    private val context: android.content.Context
) : PagingSource<Int, AllowedAppInfo>() {

    companion object {
        private const val TAG = "AllowedAppsPagingSource"
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, AllowedAppInfo> {
        return try {
            // Get temp allowed apps from database
            val tempAllowedApps = appInfoRepository.getAllTempAllowedApps(now)

            // Convert to AllowedAppInfo
            val allowedApps = tempAllowedApps.mapNotNull { appInfo ->
                try {
                    val displayName = decorateNameIfSharedUid(appInfo.appName, appInfo.uid)
                    AllowedAppInfo(
                        packageName = appInfo.packageName,
                        appName = displayName,
                        uid = appInfo.uid,
                        allowedAt = appInfo.tempAllowExpiryTime - (15 * 60 * 1000) // Calculate when it was allowed
                    )
                } catch (e: Exception) {
                    Logger.e(TAG, "Error mapping app info: ${e.message}", e)
                    null
                }
            }

            LoadResult.Page(
                data = allowedApps,
                prevKey = null, // Only one page for now
                nextKey = null  // Only one page for now
            )
        } catch (e: Exception) {
            Logger.e(TAG, "Error loading allowed apps: ${e.message}", e)
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, AllowedAppInfo>): Int? {
        return null
    }

    private fun decorateNameIfSharedUid(appName: String, uid: Int): String {
        return try {
            val pkgs = context.packageManager.getPackagesForUid(uid)
            val otherCount = (pkgs?.size ?: 0) - 1
            if (otherCount > 0) "$appName + $otherCount other apps" else appName
        } catch (_: Exception) {
            appName
        }
    }
}
