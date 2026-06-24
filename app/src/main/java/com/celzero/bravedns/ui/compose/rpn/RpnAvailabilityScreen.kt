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
package com.celzero.bravedns.ui.compose.rpn

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.theme.Dimensions
import com.celzero.bravedns.ui.compose.theme.RethinkLargeTopBar
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RpnAvailabilityScreen(onBackClick: () -> Unit) {
    var options by remember { mutableStateOf<List<String>>(emptyList()) }
    var items by remember { mutableStateOf<List<RpnAvailabilityItem>>(emptyList()) }
    var strength by remember { mutableStateOf(0) }
    var maxStrength by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        options = listOf("WIN-US", "WIN-UK", "WIN-IN", "WIN-DE", "WIN-CA")
        maxStrength = options.size
        items = options.map { RpnAvailabilityItem(it, RpnAvailabilityStatus.Loading) }

        options.forEach { option ->
            items =
                items.map { item ->
                    if (item.name == option) item.copy(status = RpnAvailabilityStatus.Loading)
                    else item
                }
            val res = withContext(Dispatchers.IO) {
                false
            }
            if (res) {
                strength += 1
                items =
                    items.map { item ->
                        if (item.name == option) item.copy(status = RpnAvailabilityStatus.Active)
                        else item
                    }
            } else {
                items =
                    items.map { item ->
                        if (item.name == option) item.copy(status = RpnAvailabilityStatus.Inactive)
                        else item
                    }
            }
            Napier.i("RpnAvailabilityScreen strength: $strength ($res)")
        }
    }

    val progress = if (maxStrength > 0) strength.toFloat() / maxStrength else 0f

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            RethinkLargeTopBar(
                title = stringResource(id = R.string.rpn_availability_title),
                onBackClick = onBackClick,
                scrollBehavior = scrollBehavior
            )
        }
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(paddingValues),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            androidx.compose.material3.Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimensions.screenPaddingHorizontal, vertical = Dimensions.spacingSm),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(Dimensions.cardCornerRadiusLarge),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(Dimensions.spacingLg)) {
                    Text(
                        text = stringResource(id = R.string.rpn_availability_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = stringResource(id = R.string.rpn_availability_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimensions.screenPaddingHorizontal, vertical = Dimensions.spacingLg)
            ) {
                Spacer(modifier = Modifier.height(Dimensions.spacingSm))
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.size(120.dp),
                        strokeWidth = 8.dp
                    )
                    Text(
                        text = "$strength/$maxStrength",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        items.forEachIndexed { index, item ->
                            AvailabilityRow(item)
                            if (index != items.lastIndex) {
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AvailabilityRow(item: RpnAvailabilityItem) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = item.name,
            style = MaterialTheme.typography.bodyMedium
        )
        when (item.status) {
            RpnAvailabilityStatus.Loading -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
            }

            RpnAvailabilityStatus.Active -> {
                Text(
                    text = stringResource(id = R.string.lbl_active),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            RpnAvailabilityStatus.Inactive -> {
                Text(
                    text = stringResource(id = R.string.lbl_inactive),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

private data class RpnAvailabilityItem(
    val name: String,
    val status: RpnAvailabilityStatus
)

private enum class RpnAvailabilityStatus {
    Loading,
    Active,
    Inactive
}
