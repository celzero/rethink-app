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
package com.celzero.bravedns.ui.compose.database

import android.database.Cursor
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.ui.compose.theme.Dimensions
import com.celzero.bravedns.ui.compose.theme.RethinkListItem
import com.celzero.bravedns.ui.compose.theme.RethinkSearchField
import com.celzero.bravedns.ui.compose.theme.RethinkTopBar
import com.celzero.bravedns.ui.compose.theme.cardPositionFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class DatabaseTablePreview(
    val table: String,
    val rowCount: Int,
    val columnCount: Int,
    val dumpPreview: String,
    val isTruncated: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatabaseScreen(
    onBackClick: () -> Unit,
    appDatabase: AppDatabase
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var tables by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedTable by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<DatabaseTablePreview?>(null) }
    var loadingPreview by remember { mutableStateOf(false) }
    var loadingCopy by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val filteredTables = remember(tables, query) {
        val q = query.trim()
        if (q.isEmpty()) tables else tables.filter { it.contains(q, ignoreCase = true) }
    }

    fun loadDatabaseTables() {
        scope.launch(Dispatchers.IO) {
            val db = appDatabase.openHelper.readableDatabase
            val cursor = db.query("SELECT name FROM sqlite_master WHERE type='table'")
            val tableList = mutableListOf<String>()
            while (cursor.moveToNext()) {
                val tableName = cursor.getString(0)
                if (tableName != "android_metadata" && tableName != "room_master_table") {
                    tableList.add(tableName)
                }
            }
            cursor.close()
            withContext(Dispatchers.Main) {
                tables = tableList
                if (selectedTable == null && tableList.isNotEmpty()) {
                    selectedTable = tableList.first()
                }
                isLoading = false
            }
        }
    }

    fun refreshSelection() {
        val table = selectedTable ?: return
        loadingPreview = true
        errorText = null
        scope.launch(Dispatchers.IO) {
            runCatching { loadTablePreview(appDatabase, table) }
                .onSuccess {
                    withContext(Dispatchers.Main) {
                        preview = it
                        loadingPreview = false
                    }
                }
                .onFailure {
                    withContext(Dispatchers.Main) {
                        errorText = it.message ?: context.getString(R.string.blocklist_update_check_failure)
                        loadingPreview = false
                    }
                }
        }
    }

    fun copySelectionToClipboard() {
        val table = selectedTable ?: return
        loadingCopy = true
        scope.launch(Dispatchers.IO) {
            val fullDump = buildTableDump(appDatabase, table)
            withContext(Dispatchers.Main) {
                copyToClipboard(context, "db_dump", fullDump)
                loadingCopy = false
            }
        }
    }

    LaunchedEffect(Unit) {
        loadDatabaseTables()
    }

    LaunchedEffect(selectedTable) {
        val table = selectedTable ?: return@LaunchedEffect
        if (preview?.table != table || preview?.rowCount == -1) {
            refreshSelection()
        }
    }

    val navBarBottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            RethinkTopBar(
                title = stringResource(id = R.string.title_database_dump),
                onBackClick = onBackClick,
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(
                        enabled = selectedTable != null && preview != null && !loadingCopy,
                        onClick = { copySelectionToClipboard() }
                    ) {
                        if (loadingCopy) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.ContentCopy,
                                contentDescription = stringResource(id = R.string.database_inspector_copy_full)
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = Dimensions.screenPaddingHorizontal)
                .padding(bottom = navBarBottomPadding),
            verticalArrangement = Arrangement.spacedBy(Dimensions.spacingSm)
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val isWide = maxWidth >= 860.dp
                DatabaseControlsDeck {
                    RethinkSearchField(
                        query = query,
                        onQueryChange = { query = it },
                        placeholder = stringResource(R.string.database_inspector_search_hint),
                        modifier = Modifier.fillMaxWidth(),
                        onClearQuery = { query = "" },
                        shape = RoundedCornerShape(Dimensions.cornerRadiusMdLg),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                    if (!isWide) {
                        DatabaseInlineTableSelector(
                            tables = filteredTables,
                            selectedTable = selectedTable,
                            onSelect = { selectedTable = it }
                        )
                    }
                }
            }

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    val isWide = maxWidth >= 860.dp
                    if (isWide) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Dimensions.spacingMd)) {
                            DatabaseTableListPane(
                                tables = filteredTables,
                                selectedTable = selectedTable,
                                totalCount = tables.size,
                                modifier = Modifier.widthIn(min = 300.dp, max = 380.dp),
                                onSelect = { selectedTable = it }
                            )
                            DatabaseTableDetailPane(
                                preview = preview,
                                loadingPreview = loadingPreview,
                                loadingCopy = loadingCopy,
                                errorText = errorText,
                                selectedTable = selectedTable,
                                modifier = Modifier.weight(1f),
                                onRefresh = { refreshSelection() },
                                onCopy = { copySelectionToClipboard() }
                            )
                        }
                    } else {
                        DatabaseTableDetailPane(
                            preview = preview,
                            loadingPreview = loadingPreview,
                            loadingCopy = loadingCopy,
                            errorText = errorText,
                            selectedTable = selectedTable,
                            modifier = Modifier.fillMaxSize(),
                            onRefresh = { refreshSelection() },
                            onCopy = { copySelectionToClipboard() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DatabaseInlineTableSelector(
    tables: List<String>,
    selectedTable: String?,
    onSelect: (String) -> Unit
) {
    if (tables.isEmpty()) {
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = Dimensions.spacingXs),
        horizontalArrangement = Arrangement.spacedBy(Dimensions.spacingSm)
    ) {
        tables.forEachIndexed { index, table ->
            val isSelected = selectedTable == table
            DatabaseTableToggle(
                table = table,
                index = index,
                lastIndex = tables.lastIndex,
                selected = isSelected,
                onSelect = onSelect
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DatabaseTableToggle(
    table: String,
    index: Int,
    lastIndex: Int,
    selected: Boolean,
    onSelect: (String) -> Unit
) {
    ToggleButton(
        checked = selected,
        onCheckedChange = { checked ->
            if (checked && !selected) onSelect(table)
        },
        shapes =
            when (index) {
                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
            },
        colors = ToggleButtonDefaults.toggleButtonColors(
            checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.94f),
            checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.82f),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        border = null,
        modifier = Modifier
            .widthIn(max = 220.dp)
            .semantics { role = Role.RadioButton }
    ) {
        Text(
            text = table,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}

@Composable
private fun DatabaseControlsDeck(
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimensions.spacingXs)
    ) {
        content()
    }
}

@Composable
private fun DatabaseMetaChip(
    text: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(Dimensions.cornerRadiusPill),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun DatabaseTableListPane(
    tables: List<String>,
    selectedTable: String?,
    totalCount: Int,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        shape = RoundedCornerShape(Dimensions.cornerRadius2xl),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
    ) {
        if (tables.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.database_inspector_no_tables),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimensions.spacingMd, vertical = Dimensions.spacingSm),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Dimensions.spacingSm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Storage,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = stringResource(R.string.database_inspector_tables_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    DatabaseMetaChip(text = "${tables.size}/$totalCount")
                }

                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f))

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = Dimensions.spacingSm, vertical = Dimensions.spacingXs),
                    verticalArrangement = Arrangement.spacedBy(Dimensions.spacingGridTile)
                ) {
                    itemsIndexed(tables) { index, table ->
                        val isSelected = selectedTable == table
                        RethinkListItem(
                            headline = table,
                            leadingIconPainter = painterResource(id = R.drawable.ic_backup),
                            leadingIconTint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            leadingIconContainerColor =
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                                else MaterialTheme.colorScheme.surfaceContainerHighest,
                            position = cardPositionFor(index = index, lastIndex = tables.lastIndex),
                            highlighted = isSelected,
                            showTrailingChevron = false,
                            trailing = if (isSelected) {
                                {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_tick),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            } else {
                                null
                            },
                            onClick = { onSelect(table) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DatabaseTableDetailPane(
    preview: DatabaseTablePreview?,
    loadingPreview: Boolean,
    loadingCopy: Boolean,
    errorText: String?,
    selectedTable: String?,
    modifier: Modifier = Modifier,
    onRefresh: () -> Unit,
    onCopy: () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        shape = RoundedCornerShape(Dimensions.cornerRadius2xl),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(Dimensions.spacingMd),
            verticalArrangement = Arrangement.spacedBy(Dimensions.spacingSm)
        ) {
            if (preview == null && !loadingPreview) {
                return@Column
            }

            if (preview != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = preview.table,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Dimensions.spacingXs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onRefresh, enabled = !loadingPreview) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = stringResource(R.string.database_inspector_refresh)
                            )
                        }
                        TextButton(
                            onClick = onCopy,
                            enabled = selectedTable != null && !loadingCopy
                        ) {
                            Text(
                                text = if (loadingCopy) {
                                    stringResource(R.string.database_inspector_copying)
                                } else {
                                    stringResource(R.string.database_inspector_copy_full)
                                }
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(Dimensions.spacingSm)) {
                    DatabaseMetaChip(text = stringResource(R.string.database_inspector_rows, preview.rowCount.toString()))
                    DatabaseMetaChip(text = stringResource(R.string.database_inspector_columns, preview.columnCount.toString()))
                }

                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f))
            }

            if (!errorText.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(Dimensions.cornerRadiusMd),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                ) {
                    Text(
                        text = errorText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = Dimensions.spacingMd, vertical = Dimensions.spacingSm)
                    )
                }
            }

            if (loadingPreview) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                val text = preview?.dumpPreview.orEmpty()
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(Dimensions.spacingSm)
                    ) {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                        )
                        if (preview?.isTruncated == true) {
                            androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(Dimensions.spacingSm))
                            Text(
                                text = stringResource(R.string.database_inspector_preview_truncated),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun loadTablePreview(appDatabase: AppDatabase, table: String, maxRows: Int = 140): DatabaseTablePreview {
    val rowCount = getTableRowCount(appDatabase, table)
    val columnCount = getTableColumnCount(appDatabase, table)
    val preview = buildTableDump(appDatabase, table, maxRows = maxRows)
    return DatabaseTablePreview(
        table = table,
        rowCount = rowCount,
        columnCount = columnCount,
        dumpPreview = preview,
        isTruncated = rowCount > maxRows
    )
}

private fun getTableRowCount(appDatabase: AppDatabase, table: String): Int {
    val safeTable = table.replace("`", "``")
    val db = appDatabase.openHelper.readableDatabase
    val cursor = db.query("SELECT COUNT(*) FROM `$safeTable`")
    val count = if (cursor.moveToFirst()) cursor.getInt(0) else 0
    cursor.close()
    return count
}

private fun getTableColumnCount(appDatabase: AppDatabase, table: String): Int {
    val safeTable = table.replace("`", "``")
    val db = appDatabase.openHelper.readableDatabase
    val cursor = db.query("SELECT * FROM `$safeTable` LIMIT 1")
    val count = cursor.columnCount
    cursor.close()
    return count
}

private fun buildTableDump(appDatabase: AppDatabase, table: String, maxRows: Int? = null): String {
    val safeTable = table.replace("`", "``")
    val db = appDatabase.openHelper.readableDatabase
    val cursor = db.query("SELECT * FROM `$safeTable`")
    val columnNames = cursor.columnNames
    val result = StringBuilder()
    result.append("Table: $table\n")
    result.append(columnNames.joinToString(separator = "\t"))
    result.append("\n")
    var rowCount = 0
    var isTruncated = false
    while (cursor.moveToNext()) {
        if (maxRows != null && rowCount >= maxRows) {
            isTruncated = true
            break
        }
        for (i in columnNames.indices) {
            result.append(cursorValueAsText(cursor, i)).append("\t")
        }
        result.append("\n")
        rowCount++
    }
    cursor.close()
    if (isTruncated) {
        result.append("…\n")
    }
    return result.toString()
}

private fun cursorValueAsText(cursor: Cursor, index: Int): String {
    return when (cursor.getType(index)) {
        Cursor.FIELD_TYPE_NULL -> "null"
        Cursor.FIELD_TYPE_BLOB -> {
            val size = cursor.getBlob(index)?.size ?: 0
            "[blob:$size]"
        }
        else -> cursor.getString(index).orEmpty()
    }
}

private fun copyToClipboard(context: android.content.Context, label: String, text: String) {
    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    val clip = android.content.ClipData.newPlainText(label, text)
    clipboard.setPrimaryClip(clip)
    android.widget.Toast.makeText(context, context.getString(R.string.copied_clipboard), android.widget.Toast.LENGTH_SHORT).show()
}
