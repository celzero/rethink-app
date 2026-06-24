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
package com.celzero.bravedns.ui.compose.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.TextStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RethinkTopBarLazyColumnScreen(
    title: String,
    subtitle: String? = null,
    onBackClick: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.background,
    topBarContainerColor: Color = MaterialTheme.colorScheme.surface,
    topBarScrolledContainerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    topBarTitleTextStyle: TextStyle = MaterialTheme.typography.titleLarge,
    listState: LazyListState? = null,
    contentPadding: PaddingValues =
        PaddingValues(
            start = Dimensions.screenPaddingHorizontal,
            end = Dimensions.screenPaddingHorizontal,
            top = Dimensions.spacingSm,
            bottom = Dimensions.spacing3xl
        ),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(Dimensions.spacingLg),
    topBarActions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val effectiveListState = listState ?: rememberLazyListState()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = containerColor,
        topBar = {
            RethinkLargeTopBar(
                title = title,
                subtitle = subtitle,
                onBackClick = onBackClick,
                scrollBehavior = scrollBehavior,
                containerColor = topBarContainerColor,
                scrolledContainerColor = topBarScrolledContainerColor,
                titleTextStyle = topBarTitleTextStyle,
                actions = topBarActions
            )
        }
    ) { paddingValues ->
        LazyColumn(
            state = effectiveListState,
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentPadding = contentPadding,
            verticalArrangement = verticalArrangement,
            content = content
        )
    }
}
