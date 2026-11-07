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
package com.celzero.bravedns.adapter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.service.ConnectionSimulator
import com.celzero.bravedns.service.FirewallRuleset
import com.celzero.bravedns.ui.compose.themeColor

/** Native Compose presentation of the unchanged simulator decision chain. */
@Composable
fun DecisionStepRow(step: ConnectionSimulator.DecisionStep) {
    val rule = step.rule
    val icon = rule?.let { FirewallRuleset.getRulesIcon(it.id) } ?: R.drawable.ic_whats_new
    val color = when {
        rule == null -> R.attr.secondaryTextColor
        FirewallRuleset.ground(rule) -> R.attr.accentBad
        else -> R.attr.accentGood
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(icon), null, Modifier.size(24.dp), tint = themeColor(color))
        Text(step.text, Modifier.weight(1f))
    }
}
