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
package com.celzero.bravedns.ui.compose.statistics

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import com.celzero.bravedns.data.SummaryStatisticsType
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.ui.compose.rememberDrawablePainter
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun SummaryStatisticsType.supportsAppIcon(): Boolean {
    return this == SummaryStatisticsType.MOST_CONNECTED_APPS ||
        this == SummaryStatisticsType.MOST_BLOCKED_APPS
}

@Composable
internal fun rememberStatisticsAppIconPainter(uid: Int): Painter? {
    val context = LocalContext.current
    val drawable by produceState<Drawable?>(initialValue = null, uid) {
        value =
            withContext(Dispatchers.IO) {
                val normalizedUid = FirewallManager.appId(uid, mainUserOnly = true)
                val candidateUids = listOf(uid, normalizedUid).distinct()

                val packageName =
                    candidateUids.firstNotNullOfOrNull { candidate ->
                        FirewallManager.getPackageNameByUid(candidate)
                    } ?: candidateUids.firstNotNullOfOrNull { candidate ->
                        Utilities.getPackageInfoForUid(context, candidate)?.firstOrNull()
                    }

                if (packageName.isNullOrBlank()) {
                    null
                } else {
                    try {
                        context.packageManager.getApplicationIcon(packageName)
                    } catch (_: PackageManager.NameNotFoundException) {
                        null
                    } catch (_: SecurityException) {
                        null
                    }
                }
            }
    }

    return rememberDrawablePainter(drawable)
}
