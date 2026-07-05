/*
 * Copyright 2021 RethinkDNS and its authors
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


import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.removeBeginningTrailingCommas
import io.github.aakira.napier.Napier
import kotlin.math.log2


private fun calculatePercentage(c: Double, maxValue: Int): Pair<Int, Int> {
    val value = (log2(c) * 100).toInt()
    val newMaxValue = if (value > maxValue) value else maxValue
    return if (newMaxValue == 0) {
        0 to 0
    } else {
        val percentage = (value * 100 / newMaxValue)
        percentage to newMaxValue
    }
}

@Composable
fun IpRow(
    conn: AppConnection,
    isAsn: Boolean,
    refreshToken: Int,
    onIpClick: (AppConnection) -> Unit
) {
    val countText = conn.count.toString()
    val flagText =
        if (isAsn) {
            val cc = Utilities.getFlag(conn.flag)
            if (cc.isEmpty()) "--" else cc
        } else {
            conn.flag
        }
    val titleText = if (isAsn) conn.appOrDnsName else conn.ipAddress
    val secondaryText =
        if (isAsn) conn.ipAddress else conn.appOrDnsName?.let { beautifyDomainString(it) }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onIpClick(conn) }
                .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = flagText, style = MaterialTheme.typography.titleMedium)
            Column(modifier = Modifier.weight(1f)) {
                Text(text = titleText.orEmpty(), style = MaterialTheme.typography.titleMedium)
                if (!secondaryText.isNullOrEmpty()) {
                    Text(text = secondaryText, style = MaterialTheme.typography.bodySmall)
                }
                if (!isAsn) {
                    IpProgress(conn, refreshToken)
                }
            }
            Text(text = countText, style = MaterialTheme.typography.labelLarge)
        }
        Spacer(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun IpProgress(conn: AppConnection, refresh: Int) {
    if (refresh == Int.MIN_VALUE) {
        return
    }
    val context = LocalContext.current
    val status = IpRulesManager.getMostSpecificRuleMatch(conn.uid, conn.ipAddress)
    val color =
        when (status) {
            IpRulesManager.IpRuleStatus.NONE ->
                MaterialTheme.colorScheme.onSurfaceVariant
            IpRulesManager.IpRuleStatus.BLOCK ->
                MaterialTheme.colorScheme.error
            IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL ->
                MaterialTheme.colorScheme.tertiary
            IpRulesManager.IpRuleStatus.TRUST ->
                MaterialTheme.colorScheme.tertiary
        }    // In a paging/lazy list, this is hard to maintain without a global state.
    // For now, using a local calculation or simplified version.
    val p = (log2(conn.count.toDouble()) * 100).toInt()
    val progress = if (p <= 0) 0.1f else (p / 500f).coerceAtMost(1f)

    LinearProgressIndicator(
        progress = { progress },
        color = color,
        trackColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    )
}

private fun beautifyDomainString(d: String): String {
    return removeBeginningTrailingCommas(d).replace(",,", ",").replace(",", ", ")
}
