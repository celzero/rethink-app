/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.about

import android.text.Spanned
import android.text.style.URLSpan
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.ui.compose.wireguard.wgAnnotatedText

/** A bounded lazy viewport avoids creating one enormous measured diagnostic Text. */
class DiagnosticTabsState(val tabs: List<Pair<String, CharSequence>>, initialTab: Int = 0, val selectableText: Boolean = false) {
    var selectedTab by mutableIntStateOf(initialTab)
        private set
    var selectionEpoch by mutableIntStateOf(0)
        private set
    fun select(index: Int) { require(index in tabs.indices); selectedTab = index; selectionEpoch++ }
}

@Composable
fun DiagnosticTabsContent(state: DiagnosticTabsState) {
    Column(Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 520.dp)) {
        Row(Modifier.fillMaxWidth()) {
            state.tabs.forEachIndexed { index, (title, _) ->
                Text(title, Modifier.weight(1f).selectable(state.selectedTab == index, role = Role.Tab) { state.select(index) }
                    .alpha(if (state.selectedTab == index) 1f else .45f).padding(vertical = 14.dp, horizontal = 4.dp),
                    textAlign = TextAlign.Center, fontSize = 12.sp, color = themeColor(R.attr.primaryTextColor))
            }
        }
        key(state.selectedTab, state.selectionEpoch) {
            DiagnosticTextLines(state.tabs[state.selectedTab].second, state.selectableText, Modifier.weight(1f))
        }
    }
}

/** Stat row selections mirror the original sorted position copy policy. */
class DiagnosticSelectionState(val lines: List<String>, private val fullCopyText: String) {
    val selectedPositions = mutableStateListOf<Int>()
    fun toggle(position: Int) {
        require(position in lines.indices)
        if (!selectedPositions.remove(position)) selectedPositions.add(position)
    }
    fun textToCopy(): String = if (selectedPositions.isEmpty()) fullCopyText
        else selectedPositions.sorted().joinToString("\n") { lines[it] }
}

@Composable
fun DiagnosticSelectionContent(state: DiagnosticSelectionState) {
    val highlight = themeColor(android.R.attr.colorControlHighlight)
    LazyColumn(Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 520.dp).testTag("diagnostic.lines")) {
        itemsIndexed(state.lines) { position, line ->
            Text(line, Modifier.fillMaxWidth().testTag("diagnostic.line.$position")
                .background(if (position in state.selectedPositions) highlight else Color.Transparent)
                .selectable(position in state.selectedPositions) { state.toggle(position) }.padding(horizontal = 8.dp, vertical = 1.dp),
                fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, color = themeColor(R.attr.primaryTextColor))
        }
    }
}

class DatabaseDumpState {
    var text: CharSequence by mutableStateOf("Select a table to load its dump")
    fun append(value: CharSequence) { text = text.toString() + value }
}

@Composable
fun DatabaseDumpContent(tables: List<String>, dump: DatabaseDumpState, onSelectTable: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 560.dp)) {
        LazyColumn(Modifier.fillMaxWidth().weight(.3f).testTag("database.tables")) {
            itemsIndexed(tables) { index, table ->
                Text(table, Modifier.fillMaxWidth().clickable { onSelectTable(index) }.padding(16.dp), color = themeColor(R.attr.primaryTextColor))
            }
        }
        HorizontalDivider()
        DiagnosticTextLines(dump.text, true, Modifier.weight(.7f).testTag("database.dump"))
    }
}

@Composable
private fun DiagnosticTextLines(text: CharSequence, selectable: Boolean, modifier: Modifier = Modifier) {
    // CharSequence.subSequence retains per-line Android spans from the controller's Proc formatter.
    val lines = remember(text) {
        buildList {
            var start = 0
            text.forEachIndexed { index, c -> if (c == '\n') { add(text.subSequence(start, index)); start = index + 1 } }
            add(text.subSequence(start, text.length))
        }
    }
    val state = rememberLazyListState()
    LazyColumn(modifier.fillMaxWidth(), state = state) {
        itemsIndexed(lines) { _, line ->
            val content: @Composable () -> Unit = {
                Text(wgAnnotatedText(line), Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp),
                    fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, color = themeColor(R.attr.primaryTextColor))
            }
            if (selectable) SelectionContainer { content() } else content()
        }
    }
}

@Composable
fun AboutRichTextContent(text: CharSequence, centered: Boolean = false) {
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(text, linkColor) {
        AnnotatedString.Builder(wgAnnotatedText(text)).apply {
            if (text is Spanned) text.getSpans(0, text.length, URLSpan::class.java).forEach { span ->
                addLink(LinkAnnotation.Url(span.url, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))),
                    text.getSpanStart(span), text.getSpanEnd(span))
            }
        }.toAnnotatedString()
    }
    SelectionContainer {
        Text(annotated, Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
            textAlign = if (centered) TextAlign.Center else TextAlign.Start, color = themeColor(R.attr.primaryTextColor))
    }
}

@Composable
fun AboutProgressContent() {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
        CircularProgressIndicator(Modifier.testTag("diagnostic.progress"))
    }
}
