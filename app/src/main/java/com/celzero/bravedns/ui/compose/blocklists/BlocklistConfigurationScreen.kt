package com.celzero.bravedns.ui.compose.blocklists

import com.celzero.bravedns.ui.compose.controls.*
import com.celzero.bravedns.ui.compose.fields.OriginalTextField
import com.celzero.bravedns.ui.compose.fields.OriginalFieldStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.flowWithLifecycle
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.settings.NoDimDialogWindow
import com.celzero.bravedns.ui.compose.settings.SettingsCard
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.ui.compose.utilities.resolveUtilityStyle
import com.celzero.bravedns.ui.compose.utilities.utilityTextStyle
import kotlinx.coroutines.flow.Flow

@Composable fun BlocklistConfigurationScreen(b: BlocklistConfigurationState, packs: Flow<PagingData<BlocklistRow>>, advanced: Flow<PagingData<BlocklistRow>>, owner: LifecycleOwner, onTab: (Boolean) -> Unit, onQueryChange: (String) -> Boolean, onQuerySubmit: (String) -> Boolean, onFilter: (Any) -> Unit) {
    val packFlow = remember(packs, owner) { packs.flowWithLifecycle(owner.lifecycle) }
    val tagFlow = remember(advanced, owner) { advanced.flowWithLifecycle(owner.lifecycle) }
    val packRows = packFlow.collectAsLazyPagingItems()
    val tagRows = tagFlow.collectAsLazyPagingItems()
    val packScroll = rememberLazyListState()
    val tagScroll = rememberLazyListState()
    var previousLoad by remember { mutableStateOf<LoadState?>(null) }
    LaunchedEffect(tagRows.loadState.refresh) {
        val current = tagRows.loadState.refresh
        if (previousLoad is LoadState.Loading && current is LoadState.NotLoading) tagScroll.scrollToItem(0)
        previousLoad = current
    }
    Surface(color = themeColor(R.attr.background)) {
        // This full-screen Fragment owns the host system-bar insets. Keep the selector and
        // fixed action bar outside status/navigation bars while only the list scrolls.
        Box(Modifier.fillMaxSize().safeDrawingPadding().testTag("blocklist-content")) {
            if (b.lbConfigureLayout.visible) Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().selectableGroup().testTag("blocklist-tabs"), horizontalArrangement = Arrangement.spacedBy(-dimensionResource(R.dimen.default_stroke_width), Alignment.CenterHorizontally)) {
                    TabButton(stringResource(R.string.rt_list_simple_btn_txt), !b.selectedAdvanced, "simple") { onTab(false) }
                    TabButton(stringResource(R.string.lbl_advanced), b.selectedAdvanced, "advanced") { onTab(true) }
                }
                if (b.showAdvanced) {
                    val keyboard = LocalSoftwareKeyboardController.current
                    SettingsCard(Modifier.padding(8.dp), cornerRadius = 16.dp, contentPadding = 0.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OriginalTextField(b.query, { value -> b.query = value; onQueryChange(value) }, Modifier.weight(1f).testTag("blocklist-query"), placeholder = { Text(stringResource(R.string.search_rethinkplus_file_tag)) }, singleLine = true,
                                style = OriginalFieldStyle.Search,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { if (b.query.isNotBlank() && !onQuerySubmit(b.query)) keyboard?.hide() }),
                                trailingIcon = if (b.query.isNotEmpty()) ({ IconButton(onClick = { b.query = ""; onQueryChange("") }) { Icon(painterResource(androidx.appcompat.R.drawable.abc_ic_clear_material), stringResource(R.string.lbl_cancel), tint = themeColor(androidx.appcompat.R.attr.colorControlNormal)) } }) else null)
                            IconButton(onClick = b.lbAdvSearchFilterIcon.onClick, modifier = Modifier.testTag("blocklist-filter")) { Icon(painterResource(R.drawable.ic_filter), stringResource(R.string.rt_filter_hint), tint = Color.Unspecified) }
                        }
                        FlowRow(Modifier.padding(horizontal = 10.dp, vertical = 5.dp)) {
                            listOf(0 to R.string.lbl_all, 1 to R.string.rt_filter_parent_selected).forEach { (id, label) ->
                                OriginalChip("item_chip_filter:(anonymous)", selected = b.selectionFilter == id, onClick = { if (b.selectionFilter != id) { b.selectionFilter = id; onFilter(id) } }, label = { Text(stringResource(label)) }, modifier = Modifier.padding(3.dp).testTag("blocklist-selection-$id"))
                            }
                        }
                    }
                    Text(AnnotatedString.fromHtml(b.lbAdvancedFilterLabelTv.text.ifEmpty { stringResource(R.string.rt_filter_hint) }), Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = themeColor(R.attr.primaryTextColor))
                }
                val rows = if (b.showAdvanced) tagRows else packRows
                val scroll = if (b.showAdvanced) tagScroll else packScroll
                Box(Modifier.weight(1f)) {
                    LazyColumn(Modifier.fillMaxSize().testTag("blocklist-list"), state = scroll) {
                        items(rows.itemCount) { index -> rows[index]?.let { row ->
                            val previousGroup = if (index > 0) rows.peek(index - 1)?.group else null
                            BlocklistSelectionRow(row, index == 0 || previousGroup != row.group, b.selectedTags)
                        } }
                    }
                    if (scroll.isScrollInProgress) rows.peek(scroll.firstVisibleItemIndex)?.let { row ->
                        Text(row.groupName, Modifier.align(Alignment.TopCenter).border(1.dp, themeColor(R.attr.border), RoundedCornerShape(100.dp)).padding(horizontal = 10.dp, vertical = 5.dp), color = themeColor(R.attr.primaryTextColor))
                    }
                }
                HorizontalDivider(color = themeColor(R.attr.border))
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("blocklist-actions"), horizontalArrangement = Arrangement.spacedBy(1.dp), verticalAlignment = Alignment.CenterVertically) {
                    OriginalButton("fragment_rethink_blocklist:lb_blocklist_cancel_btn", onClick = b.lbBlocklistCancelBtn.onClick, Modifier.weight(.4f).testTag("blocklist-cancel")) { Text(stringResource(R.string.notif_dialog_pause_dialog_negative), color = themeColor(R.attr.primaryLightColorText)) }
                    OriginalButton("fragment_rethink_blocklist:lb_blocklist_apply_btn", onClick = b.lbBlocklistApplyBtn.onClick, Modifier.weight(.6f).testTag("blocklist-apply")) { Text(b.lbBlocklistApplyBtn.text, color = themeColor(R.attr.accentGood)) }
                }
            }
            if (b.lbDownloadLayout.visible) Column(Modifier.align(Alignment.Center).verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(painterResource(R.drawable.illustrations_no_record), null, Modifier.height(180.dp).padding(horizontal = 30.dp, vertical = 20.dp), tint = Color.Unspecified)
                Text(stringResource(R.string.rt_download_desc), textAlign = TextAlign.Center, color = themeColor(R.attr.primaryTextColor))
                Row(Modifier.padding(top = 60.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (b.lbDownloadProgress.visible) {
                        if (b.lbDownloadProgress.isIndeterminate) CircularProgressIndicator(Modifier.size(32.dp))
                        else CircularProgressIndicator(progress = { b.lbDownloadProgress.progress / 100f }, modifier = Modifier.size(32.dp))
                    }
                    OriginalButton("fragment_rethink_blocklist:lb_download_btn", onClick = b.lbDownloadBtn.onClick, enabled = b.lbDownloadBtn.isEnabled && b.lbDownloadBtn.isClickable, modifier = Modifier.testTag("blocklist-download")) { Text(b.lbDownloadBtn.text.ifEmpty { stringResource(R.string.rt_download) }, color = themeColor(R.attr.chipTextColor)) }
                }
                OriginalButton("fragment_rethink_blocklist:lb_cancel_download_btn", onClick = b.lbCancelDownloadBtn.onClick, modifier = Modifier.testTag("blocklist-cancel-download")) { Text(stringResource(R.string.lbl_cancel), color = themeColor(R.attr.primaryTextColor)) }
            }
            if (b.lbDownloadProgressRemote.visible) CircularProgressIndicator(Modifier.align(Alignment.TopStart))
        }
    }
    b.prompt?.let { prompt ->
        NoDimDialogWindow(onDismissRequest = { b.prompt = null }, properties = DialogProperties()) {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = themeColor(R.attr.background)) {
                Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
                    Text(prompt.title, style = MaterialTheme.typography.headlineSmall, color = themeColor(R.attr.primaryTextColor))
                    Text(prompt.message, Modifier.padding(vertical = 16.dp), color = themeColor(R.attr.primaryTextColor))
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        prompt.neutral?.let { OriginalButton("@dialog_neutral", onClick = { b.prompt = null; prompt.neutralAction() }, modifier = Modifier.testTag("blocklist-dialog-neutral")) { Text(it, color = themeColor(R.attr.secondaryTextColor)) } }
                        OriginalButton("@dialog_negative", onClick = { b.prompt = null; prompt.cancel() }, modifier = Modifier.testTag("blocklist-dialog-negative")) { Text(prompt.negative, color = themeColor(R.attr.secondaryTextColor)) }
                        OriginalButton("@dialog_positive", onClick = { b.prompt = null; prompt.confirm() }, modifier = Modifier.testTag("blocklist-dialog-positive")) { Text(prompt.positive, color = themeColor(androidx.appcompat.R.attr.colorPrimary)) }
                    }
                }
            }
        }
    }
}

@Composable private fun TabButton(label: String, selected: Boolean, key: String, onClick: () -> Unit) {
    OriginalButton("fragment_rethink_blocklist:" + if (key == "simple") "lb_simple_toggle_btn" else "lb_adv_toggle_btn", onClick, Modifier.widthIn(min = 125.dp).testTag("blocklist-tab-$key"), selected = selected, segmentIndex = if (key == "simple") 0 else 1, segmentCount = 2, grouped = true, containerColor = themeColor(if (selected) R.attr.accentGood else R.attr.defaultToggleBtnBg), contentColor = themeColor(if (selected) R.attr.homeScreenHeaderTextColor else R.attr.primaryTextColor)) { Text(label) }
}

@Composable internal fun BlocklistSelectionRow(row: BlocklistRow, showGroup: Boolean, selectedTags: Set<Int>) {
    val persisted = row.selected()
    var checked by remember(row.key, persisted, selectedTags) { mutableStateOf(persisted) }
    val select: (Boolean) -> Unit = { checked = it; row.onSelected(it) }
    Column {
        if (showGroup) Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(row.groupName, Modifier.fillMaxWidth().semantics { heading() }, textAlign = TextAlign.Center, color = themeColor(R.attr.accentBad), style = utilityTextStyle(resolveUtilityStyle(LocalContext.current, com.google.android.material.R.attr.textAppearanceTitleLarge), weight = android.graphics.Typeface.BOLD))
            Text(row.groupDescription, Modifier.fillMaxWidth().padding(top = 4.dp).alpha(.75f), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = themeColor(R.attr.primaryLightColorText))
        }
        SettingsCard(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), cornerRadius = 16.dp, contentPadding = 0.dp,
            styleAttribute = com.google.android.material.R.attr.materialCardViewElevatedStyle,
            containerColor = blocklistCardColor(checked)) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).testTag("blocklist-row-${row.key}").semantics { toggleableState = if (checked) ToggleableState.On else ToggleableState.Off }.clickable(role = Role.Checkbox) { select(!checked) }, verticalAlignment = Alignment.CenterVertically) {
                val color = when (row.level) { 0 -> R.attr.firewallNoRuleToggleBtnBg; 1 -> R.attr.firewallWhiteListToggleBtnTxt; 2 -> R.attr.firewallBlockToggleBtnTxt; else -> if (row.entries == null) R.attr.accentGood else R.attr.background }
                Box(Modifier.width(6.dp).fillMaxHeight().background(themeColor(color)))
                Column(Modifier.weight(1f).padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 8.dp)) {
                    Text(row.label, style = MaterialTheme.typography.titleMedium, color = themeColor(R.attr.primaryTextColor), maxLines = if (row.entries == null) 1 else 2, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.padding(top = if (row.entries == null) 4.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(row.description, Modifier.weight(1f).alpha(.7f), style = MaterialTheme.typography.bodySmall, color = themeColor(R.attr.primaryTextColor), maxLines = if (row.entries == null) 2 else 1, overflow = TextOverflow.Ellipsis)
                        row.entries?.let { entries -> OriginalChip("list_item_rethink_blocklist_adv:crp_desc_entries_tv", selected = false, selectable = false, onClick = { row.onEntries?.invoke() }, label = { Text(entries) }, modifier = Modifier.padding(start = 8.dp).alpha(.8f).testTag("blocklist-entries-${row.key}")) }
                    }
                }
                OriginalCheckbox(if (row.entries == null) "list_item_rethink_blocklist_simple:crp_check_box" else "list_item_rethink_blocklist_adv:crp_check_box", checked, select, Modifier.padding(end = 12.dp).testTag("blocklist-check-${row.key}"))
            }
        }
    }
}
