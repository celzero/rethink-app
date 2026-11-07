/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.celzero.bravedns.ui.compose.trafficlogs

import androidx.compose.ui.graphics.Color
import com.celzero.bravedns.ui.compose.controls.*
import com.celzero.bravedns.ui.compose.fields.OriginalTextField
import com.celzero.bravedns.ui.compose.fields.OriginalFieldStyle
import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.layout.NestedPrefetchScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.*
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.util.UIUtils.formatToRelativeTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** SearchView text delivery contract, kept separate from generic text presentation. */
class LogSearchState {
    var query by mutableStateOf("")
        private set
    private var oldQuery = ""
    var listener: ((String) -> Unit)? = null
    var submitListener: ((String) -> Unit)? = null
    private var expanded = false
    fun setQuery(value: String, submit: Boolean = false) {
        query = value
        if (value != oldQuery) listener?.invoke(value)
        oldQuery = value // SearchView updates mOldQueryText even before a listener is attached.
        if (submit && value.trim().isNotEmpty()) submitListener?.invoke(value)
    }
    fun submit() { if (query.trim().isNotEmpty()) submitListener?.invoke(query) }
    fun expand() { if (expanded) return; expanded = true; setQuery("") }
}
class LogScreenState {
    val search = LogSearchState()
    var showSearch by mutableStateOf(true)
    var showList by mutableStateOf(true)
    var message: String? by mutableStateOf(null)
    var showParentFilters by mutableStateOf(false)
    var showChildFilters by mutableStateOf(false)
    var resumeGeneration by mutableIntStateOf(0)
}
data class LogFilterChip(val key: String, val label: String, val selected: Boolean, val icon: Int? = null)

@Composable fun LogFilterChips(chips: List<LogFilterChip>, singleLine: Boolean = false, onClick: (LogFilterChip) -> Unit) {
    @Composable fun Chip(chip: LogFilterChip) {
        OriginalChip("item_chip_filter:(anonymous)", chip.selected, { onClick(chip) }, { Text(chip.label, fontSize = logTextSize(R.dimen.default_font_text_view)) }, leadingIcon = when { chip.icon != null -> ({ Icon(painterResource(chip.icon), null, Modifier.size(15.dp)) }); chip.selected -> ({ Icon(Icons.Default.Check, null, Modifier.size(15.dp)) }); else -> null }, modifier = Modifier.padding(3.dp).testTag("log-filter-${chip.key}"))
    }
    if (singleLine) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp)) { chips.forEach { Chip(it) } }
    else FlowRow(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 2.dp)) { chips.forEach { Chip(it) } }
}

@Composable fun LogSearchBar(state: LogScreenState, hint: String, onSearchFocus: () -> Unit = {}, boxed: Boolean = true,
    filters: @Composable () -> Unit = {}, actions: @Composable RowScope.() -> Unit) {
    val focus = LocalFocusManager.current
    val editorFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val context = LocalContext.current
    LaunchedEffect(state.resumeGeneration) {
        focus.clearFocus(force = true)
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(view)
    }
    @Composable fun Content() {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OriginalTextField(state.search.query, { state.search.setQuery(it) }, Modifier.weight(1f).focusRequester(editorFocus).testTag("log-search"),
                singleLine = true, placeholder = { Text(hint) }, leadingIcon = {
                    // The baseline's already-visible SearchView editor never invokes the root
                    // click listener on focus. Only the root/search affordance expands it and
                    // clears the initial query; tapping or refocusing the editor preserves it.
                    IconButton({ onSearchFocus(); editorFocus.requestFocus(); keyboard?.show() }, Modifier.testTag("log-search-open")) {
                        Icon(painterResource(androidx.appcompat.R.drawable.abc_ic_search_api_material), stringResource(R.string.lbl_search), tint = themeColor(androidx.appcompat.R.attr.colorControlNormal))
                    }
                },
                style = OriginalFieldStyle.Search,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { state.search.submit() }),
                trailingIcon = { if (state.search.query.isNotEmpty()) IconButton({ state.search.setQuery("") }) { Icon(painterResource(androidx.appcompat.R.drawable.abc_ic_clear_material), stringResource(R.string.fapps_filter_clear_btn), tint = themeColor(androidx.appcompat.R.attr.colorControlNormal)) } })
            actions()
        }
        filters()
    }
    if (state.showSearch) {
        if (boxed) OriginalCardSurface(Modifier.fillMaxWidth().padding(5.dp).testTag("log-search-card"),
            cornerRadius = 16.dp, contentColor = themeColor(R.attr.primaryTextColor)) { Content() }
        else Column(Modifier.fillMaxWidth()) { Content() }
    }
}
@Composable fun LogAction(icon: Int, description: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    IconButton(onClick, modifier, enabled) { Icon(painterResource(icon), description, Modifier.size(24.dp), tint = Color.Unspecified) }
}
@Composable fun <T: Any> LogList(state: LogScreenState, presenter: LogPagingPresenter<T>, modifier: Modifier = Modifier,
    timestamp: (T) -> Long, headerThrottleMillis: Long = 0L, fastScroll: Boolean = true, showHeader: Boolean = true,
    prefetch: Boolean = true, thumbColorAttribute: Int = R.attr.chipColorBgNormal, empty: @Composable (() -> Unit)? = null) {
    val strategy = remember(prefetch) { if (prefetch) LazyListPrefetchStrategy() else NoLogPrefetch }
    val list = rememberLazyListState(prefetchStrategy = strategy)
    val context = LocalContext.current
    var header by remember { mutableStateOf("") }
    var updated by remember { mutableLongStateOf(0L) }
    var headerVisible by remember { mutableStateOf(false) }
    var previouslyScrolling by remember { mutableStateOf(false) }
    val firstTimestamp = presenter.peek(list.firstVisibleItemIndex)?.let(timestamp)
    LaunchedEffect(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, firstTimestamp) {
        val now = System.currentTimeMillis()
        if (showHeader && now - updated >= headerThrottleMillis && firstTimestamp != null) {
            updated = now; header = formatToRelativeTime(context, firstTimestamp); headerVisible = true
        }
    }
    LaunchedEffect(list.isScrollInProgress) {
        // RecyclerView hides only on an actual transition to IDLE, not on first layout.
        if (previouslyScrolling && !list.isScrollInProgress) headerVisible = false
        previouslyScrolling = list.isScrollInProgress
    }
    Box(modifier.fillMaxWidth()) {
        if (state.showList) LazyColumn(Modifier.fillMaxSize().testTag("log-list"), state = list, contentPadding = PaddingValues(bottom = 10.dp)) {
            items(presenter.itemCount, key = { presenter.keyAt(it) }) { presenter.RowAt(it) }
        }
        state.message?.let { Text(it, Modifier.align(Alignment.TopCenter).padding(24.dp), color = themeColor(R.attr.primaryTextColor)) }
        if (!state.showList) empty?.invoke()
        if (state.showList && fastScroll) LogFastScroller(list, Modifier.align(Alignment.CenterEnd), thumbColorAttribute)
        if (showHeader && headerVisible && header.isNotEmpty()) Text(header, Modifier.align(Alignment.TopCenter).padding(top = 10.dp).border(1.dp, themeColor(R.attr.border), RoundedCornerShape(100.dp)).padding(horizontal = 10.dp, vertical = 5.dp), color = themeColor(R.attr.primaryTextColor), fontSize = logTextSize(R.dimen.default_font_text_view))
    }
}
private object NoLogPrefetch : LazyListPrefetchStrategy {
    override fun LazyListPrefetchScope.onScroll(delta: Float, layoutInfo: LazyListLayoutInfo) = Unit
    override fun LazyListPrefetchScope.onVisibleItemsUpdated(layoutInfo: LazyListLayoutInfo) = Unit
    override fun NestedPrefetchScope.onNestedPrefetch(firstVisibleItemIndex: Int) = Unit
}
@Composable private fun LogFastScroller(list: LazyListState, modifier: Modifier, color: Int) {
    val scope = rememberCoroutineScope()
    val count = list.layoutInfo.totalItemsCount
    val visible = list.layoutInfo.visibleItemsInfo.size
    var shown by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, list.isScrollInProgress, count, dragging) {
        shown = count > visible
        if (!list.isScrollInProgress && !dragging) { delay(1500); shown = false }
    }
    if (shown && count > visible) BoxWithConstraints(modifier.fillMaxHeight().width(24.dp)) {
        val density = LocalDensity.current
        val height = with(density) { maxHeight.toPx() }
        val thumb = (height * visible.coerceAtLeast(1) / count).coerceAtLeast(with(density) { 48.dp.toPx() }).coerceAtMost(height)
        val range = (height - thumb).coerceAtLeast(1f)
        val maxIndex = (count - visible).coerceAtLeast(1)
        val top = range * list.firstVisibleItemIndex / maxIndex
        val currentTop by rememberUpdatedState(top)
        var dragTop by remember { mutableFloatStateOf(0f) }
        Box(Modifier.align(Alignment.TopEnd).offset { IntOffset(0, top.roundToInt()) }.fillMaxWidth().height(with(density) { thumb.toDp() }).semantics {
            progressBarRangeInfo = ProgressBarRangeInfo(list.firstVisibleItemIndex.toFloat(), 0f..maxIndex.toFloat())
            setProgress { target -> scope.launch { list.scrollToItem(target.roundToInt().coerceIn(0, maxIndex)) }; true }
        }.pointerInput(count, range) {
            detectVerticalDragGestures(onDragStart = { dragging = true; dragTop = currentTop }, onDragEnd = { dragging = false }, onDragCancel = { dragging = false }) { change, amount ->
                change.consume(); dragTop = (dragTop + amount).coerceIn(0f, range)
                scope.launch { list.scrollToItem((dragTop / range * maxIndex).roundToInt().coerceIn(0, maxIndex)) }
            }
        }) { Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(4.dp).background(themeColor(color))) }
    }
}
