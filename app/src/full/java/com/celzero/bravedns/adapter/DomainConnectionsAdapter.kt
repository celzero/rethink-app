/*
 * Copyright 2024 RethinkDNS and its authors
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
package com.celzero.bravedns.adapter

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.ui.compose.statistics.StatisticsSummaryItem
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ConnectionRow(dc: AppConnection) {
    val context = LocalContext.current
    val fallbackName = if (dc.appOrDnsName.isNullOrEmpty()) {
        context.getString(R.string.network_log_app_name_unnamed, "(${dc.uid})")
    } else {
        dc.appOrDnsName
    }
    val totalUsageText = if (dc.downloadBytes != null && dc.uploadBytes != null) {
        val download =
            context.getString(
                R.string.symbol_download,
                Utilities.humanReadableByteCount(dc.downloadBytes, true)
            )
        val upload =
            context.getString(
                R.string.symbol_upload,
                Utilities.humanReadableByteCount(dc.uploadBytes, true)
            )
        context.getString(R.string.two_argument, upload, download)
    } else {
        null
    }

    val scope = rememberCoroutineScope()
    var title by remember(dc.uid, dc.appOrDnsName) { mutableStateOf(fallbackName.orEmpty()) }
    var icon by remember(dc.uid) { mutableStateOf(Utilities.getDefaultIcon(context)) }
    var isUnknown by remember(dc.uid) { mutableStateOf(true) }

    LaunchedEffect(dc.uid, dc.appOrDnsName) {
        val resolved =
            withContext(Dispatchers.IO) {
                val appInfo = FirewallManager.getAppInfoByUid(dc.uid)
                val displayName = if (dc.appOrDnsName.isNullOrEmpty()) {
                    appInfo?.appName ?: fallbackName.orEmpty()
                } else {
                    dc.appOrDnsName
                }
                val resolvedIcon =
                    Utilities.getIcon(
                        context,
                        appInfo?.packageName ?: "",
                        appInfo?.appName ?: ""
                    ) ?: Utilities.getDefaultIcon(context)
                Triple(appInfo == null, displayName, resolvedIcon)
            }
        isUnknown = resolved.first
        title = resolved.second
        icon = resolved.third
    }

    val onClick = {
        scope.launch(Dispatchers.IO) {
            if (isUnknown) {
                // Navigate to network logs via HomeScreenActivity
                val intent = Intent(context, HomeScreenActivity::class.java)
                intent.putExtra(HomeScreenActivity.EXTRA_NAV_TARGET, HomeScreenActivity.NAV_TARGET_NETWORK_LOGS)
                intent.putExtra(Constants.SEARCH_QUERY, dc.appOrDnsName)
                withContext(Dispatchers.Main) { context.startActivity(intent) }
            } else {
                val intent = Intent(context, HomeScreenActivity::class.java)
                intent.putExtra(HomeScreenActivity.EXTRA_NAV_TARGET, HomeScreenActivity.NAV_TARGET_APP_INFO)
                intent.putExtra(HomeScreenActivity.EXTRA_APP_INFO_UID, dc.uid)
                withContext(Dispatchers.Main) { context.startActivity(intent) }
            }
        }
        Unit
    }

    StatisticsSummaryItem(
        title = title,
        subtitle = totalUsageText,
        countText = dc.count.toString(),
        iconDrawable = icon,
        flagText = null,
        showProgress = false,
        progress = 0f,
        progressColor = Color.Transparent,
        showIndicator = true,
        onClick = onClick
    )
}
