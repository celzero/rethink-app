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
package com.celzero.bravedns.ui.compose.logs

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.paging.compose.LazyPagingItems
import com.celzero.bravedns.R
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.ui.compose.theme.RethinkLargeTopBar
import com.celzero.bravedns.ui.compose.theme.RethinkConfirmDialog
import com.celzero.bravedns.ui.compose.theme.Dimensions
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.AppConnectionsViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
internal fun AppWiseLogsDeleteDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    RethinkConfirmDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.ada_delete_logs_dialog_title),
        message = stringResource(R.string.ada_delete_logs_dialog_desc),
        confirmText = stringResource(R.string.lbl_proceed),
        dismissText = stringResource(R.string.lbl_cancel),
        onConfirm = {
            onDismiss()
            onConfirm()
        },
        onDismiss = onDismiss,
        isConfirmDestructive = true
    )
}

internal data class AppWiseLogsHeader(
    val appName: String,
    val searchHint: String,
    val appIcon: Drawable?,
    val isRethinkApp: Boolean
)

internal suspend fun resolveAppWiseLogsHeader(
    context: Context,
    uid: Int,
    isAsn: Boolean,
    appOtherAppsTemplate: String,
    twoArgumentColonTemplate: String,
    twoArgumentSpaceTemplate: String,
    searchLabel: String,
    serviceProvidersLabel: String,
    universalIpsLabel: String
): AppWiseLogsHeader? {
    if (uid == INVALID_UID) return null

    val info = FirewallManager.getAppInfoByUid(uid) ?: return null
    val packageNames = FirewallManager.getPackageNamesByUid(uid)
    val isRethinkApp = packageNames.any { it == context.packageName }

    val visibleName =
        if (packageNames.size >= 2) {
            String.format(
                appOtherAppsTemplate,
                info.appName,
                (packageNames.size - 1).toString()
            )
        } else {
            info.appName
        }
    val truncated = visibleName.substring(0, visibleName.length.coerceAtMost(10))
    val hint =
        if (isAsn) {
            val txt =
                String.format(twoArgumentSpaceTemplate, searchLabel, serviceProvidersLabel)
            String.format(twoArgumentColonTemplate, truncated, txt)
        } else {
            String.format(twoArgumentColonTemplate, truncated, universalIpsLabel)
        }

    return AppWiseLogsHeader(
        appName = visibleName,
        searchHint = hint,
        appIcon = Utilities.getIcon(context, info.packageName, info.appName),
        isRethinkApp = isRethinkApp
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppWiseLogsScaffold(
    title: String,
    onBackClick: (() -> Unit)? = null,
    content: @Composable (paddingValues: PaddingValues) -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            RethinkLargeTopBar(
                title = title,
                onBackClick = onBackClick,
                scrollBehavior = scrollBehavior
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        content(paddingValues)
    }
}

@Composable
internal fun AppWiseLogsScreenContent(
    title: String,
    searchHint: String,
    appIcon: Drawable?,
    showToggleGroup: Boolean,
    selectedCategory: AppConnectionsViewModel.TimeCategory,
    onCategorySelected: (AppConnectionsViewModel.TimeCategory) -> Unit,
    defaultHintRes: Int,
    showDeleteIcon: Boolean,
    onDeleteClick: () -> Unit,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    queryEnabled: Boolean = true,
    content: @Composable () -> Unit
) {
    Column(modifier = modifier.fillMaxSize()) {
        Surface(
            modifier =
                Modifier.padding(
                    horizontal = Dimensions.screenPaddingHorizontal,
                    vertical = Dimensions.spacingSm
                ),
            shape = RoundedCornerShape(Dimensions.cardCornerRadiusLarge),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 1.dp
        ) {
            Column(
                modifier = Modifier.padding(Dimensions.spacingLg),
                verticalArrangement = Arrangement.spacedBy(Dimensions.spacingXs)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = searchHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Column(
            modifier =
                Modifier.padding(
                    horizontal = Dimensions.screenPaddingHorizontal,
                    vertical = Dimensions.spacingMd
                )
        ) {
            if (showToggleGroup) {
                AppWiseTimeCategoryToggleRow(
                    selectedCategory = selectedCategory,
                    onCategorySelected = onCategorySelected
                )
                Spacer(modifier = Modifier.height(Dimensions.spacingMd))
            }

            AppWiseSearchHeaderRow(
                appIcon = appIcon,
                searchHint = searchHint,
                defaultHintRes = defaultHintRes,
                showDeleteIcon = showDeleteIcon,
                onDeleteClick = onDeleteClick,
                queryEnabled = queryEnabled,
                onQueryChange = onQueryChange
            )
        }

        content()
    }
}

@Composable
internal fun <T : Any> AppWiseLogsPagedList(
    items: LazyPagingItems<T>,
    modifier: Modifier = Modifier,
    row: @Composable (T) -> Unit
) {
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(
                horizontal = Dimensions.screenPaddingHorizontal,
                vertical = Dimensions.spacingSm
            ),
        verticalArrangement = Arrangement.spacedBy(Dimensions.spacingSm)
    ) {
        items(count = items.itemCount) { index ->
            val item = items[index] ?: return@items
            row(item)
        }
    }
}

@Composable
internal fun AppWiseTimeCategoryToggleRow(
    selectedCategory: AppConnectionsViewModel.TimeCategory,
    onCategorySelected: (AppConnectionsViewModel.TimeCategory) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimensions.spacingSm),
        horizontalArrangement = Arrangement.spacedBy(Dimensions.spacingSm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf(
            AppConnectionsViewModel.TimeCategory.ONE_HOUR to
                stringResource(R.string.ci_desc, "1", stringResource(R.string.lbl_hour)),
            AppConnectionsViewModel.TimeCategory.TWENTY_FOUR_HOUR to
                stringResource(R.string.ci_desc, "24", stringResource(R.string.lbl_hour)),
            AppConnectionsViewModel.TimeCategory.SEVEN_DAYS to
                stringResource(R.string.ci_desc, "7", stringResource(R.string.lbl_day))
        ).forEach { (category, label) ->
            TimeCategoryToggleButton(
                label = label,
                selected = selectedCategory == category,
                onClick = { onCategorySelected(category) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun TimeCategoryToggleButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(36.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor =
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
            contentColor =
                if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
        ),
        shape = RoundedCornerShape(Dimensions.buttonCornerRadiusLarge)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(FlowPreview::class)
@Composable
internal fun AppWiseSearchHeaderRow(
    appIcon: Drawable?,
    searchHint: String,
    defaultHintRes: Int,
    showDeleteIcon: Boolean,
    onDeleteClick: () -> Unit,
    onQueryChange: (String) -> Unit,
    queryEnabled: Boolean = true
) {
    val clearSearchContentDescription = stringResource(R.string.cd_clear_search)
    val deleteContentDescription = stringResource(R.string.lbl_delete)
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        snapshotFlow { query }
            .debounce(500L)
            .distinctUntilChanged()
            .collect { value -> onQueryChange(value) }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dimensions.cardCornerRadiusLarge),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Dimensions.spacingSm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .padding(Dimensions.spacingSm)
                    .size(Dimensions.iconSizeMd)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                val bitmap = remember(appIcon) { appIcon?.toBitmap(width = 48, height = 48) }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                enabled = queryEnabled,
                placeholder = {
                    Text(
                        text = searchHint.ifEmpty { stringResource(defaultHintRes) },
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent
                )
            )

            AnimatedVisibility(visible = query.isNotEmpty()) {
                IconButton(onClick = { query = "" }) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = clearSearchContentDescription,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(Dimensions.iconSizeSm)
                    )
                }
            }

            if (showDeleteIcon) {
                IconButton(onClick = onDeleteClick) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = deleteContentDescription,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(Dimensions.iconSizeMd)
                    )
                }
            }
        }
    }
}
