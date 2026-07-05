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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.database.ConsoleLog
import com.celzero.bravedns.util.Constants.Companion.TIME_FORMAT_1
import com.celzero.bravedns.util.Utilities

@Composable
fun ConsoleLogRow(log: ConsoleLog, isDebug: Boolean = DEBUG) {
    val context = LocalContext.current
    val logLevel = log.message.firstOrNull() ?: 'V'
    val colorRes =
        when (logLevel) {
            'I' -> R.attr.defaultToggleBtnTxt
            'W' -> R.attr.firewallWhiteListToggleBtnTxt
            'E' -> R.attr.firewallBlockToggleBtnTxt
            else -> R.attr.primaryLightColorText
        }
    val logColor =
        when (colorRes) {
            R.attr.defaultToggleBtnTxt -> MaterialTheme.colorScheme.onSurfaceVariant
            R.attr.firewallWhiteListToggleBtnTxt -> MaterialTheme.colorScheme.tertiary
            R.attr.firewallBlockToggleBtnTxt -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    val timestamp =
        if (isDebug) {
            "${log.id}\n${Utilities.convertLongToTime(log.timestamp, TIME_FORMAT_1)}"
        } else {
            Utilities.convertLongToTime(log.timestamp, TIME_FORMAT_1)
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 5.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = timestamp,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = log.message,
            style = MaterialTheme.typography.bodySmall,
            color = logColor,
            modifier = Modifier.weight(1f)
        )
    }
}
