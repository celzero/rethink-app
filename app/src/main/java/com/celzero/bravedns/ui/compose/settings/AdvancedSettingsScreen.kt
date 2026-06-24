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
package com.celzero.bravedns.ui.compose.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.compose.theme.Dimensions
import com.celzero.bravedns.ui.compose.theme.RethinkAnimatedSection
import com.celzero.bravedns.ui.compose.theme.RethinkListItem
import com.celzero.bravedns.ui.compose.theme.RethinkLargeTopBar
import com.celzero.bravedns.ui.compose.theme.cardPositionFor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSettingsScreen(
    persistentState: PersistentState,
    onBackClick: (() -> Unit)? = null
) {
    if (!DEBUG) {
        return
    }

    var experimentalEnabled by remember { mutableStateOf(persistentState.nwEngExperimentalFeatures) }
    var autoDialEnabled by remember { mutableStateOf(persistentState.autoDialsParallel) }
    var panicEnabled by remember { mutableStateOf(persistentState.panicRandom) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            RethinkLargeTopBar(
                title = stringResource(id = R.string.lbl_advanced),
                subtitle = stringResource(id = R.string.adv_set_experimental_desc),
                onBackClick = onBackClick,
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        LazyColumn(
            modifier =
                Modifier
                    .padding(padding)
                    .fillMaxSize(),
            contentPadding =
                PaddingValues(
                    start = Dimensions.screenPaddingHorizontal,
                    end = Dimensions.screenPaddingHorizontal,
                    top = Dimensions.spacingMd,
                    bottom = Dimensions.spacing3xl
                ),
            verticalArrangement = Arrangement.spacedBy(Dimensions.spacingLg)
        ) {
            item {
                    RethinkAnimatedSection(index = 0) {
                    Column {
                        val entries = 3
                        RethinkListItem(
                            headline = stringResource(id = R.string.adv_set_experimental_title),
                            leadingIcon = Icons.Filled.Build,
                            position = cardPositionFor(index = 0, lastIndex = entries - 1),
                            onClick = {
                                experimentalEnabled = !experimentalEnabled
                                persistentState.nwEngExperimentalFeatures = experimentalEnabled
                            },
                            trailing = {
                                Switch(
                                    checked = experimentalEnabled,
                                    onCheckedChange = {
                                        experimentalEnabled = it
                                        persistentState.nwEngExperimentalFeatures = it
                                    }
                                )
                            }
                        )

                        RethinkListItem(
                            headline = stringResource(id = R.string.set_auto_dial_title),
                            supporting = stringResource(id = R.string.set_auto_dial_desc),
                            leadingIcon = Icons.Filled.Tune,
                            position = cardPositionFor(index = 1, lastIndex = entries - 1),
                            onClick = {
                                autoDialEnabled = !autoDialEnabled
                                persistentState.autoDialsParallel = autoDialEnabled
                            },
                            trailing = {
                                Switch(
                                    checked = autoDialEnabled,
                                    onCheckedChange = {
                                        autoDialEnabled = it
                                        persistentState.autoDialsParallel = it
                                    }
                                )
                            }
                        )

                        RethinkListItem(
                            headline = "Random panic",
                            supporting = "Debug-only chaos mode for tunnel reliability testing.",
                            leadingIcon = Icons.Filled.Warning,
                            position = cardPositionFor(index = 2, lastIndex = entries - 1),
                            onClick = {
                                panicEnabled = !panicEnabled
                                persistentState.panicRandom = panicEnabled
                            },
                            trailing = {
                                Switch(
                                    checked = panicEnabled,
                                    onCheckedChange = {
                                        panicEnabled = it
                                        persistentState.panicRandom = it
                                    }
                                )
                            }
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(Dimensions.spacingSm))
            }
        }
    }
}
