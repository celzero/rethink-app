/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.celzero.bravedns.ui.compose.trafficlogs

import com.celzero.bravedns.ui.compose.controls.*
import android.view.View
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.database.ConsoleLog
import com.celzero.bravedns.ui.compose.NoDimDialogWindow
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.util.Constants.Companion.TIME_FORMAT_1
import com.celzero.bravedns.util.Utilities

class ConsoleLogsState {
    val logs = LogScreenState()
    val pauseIcon = LogControlState().apply { imageResource = R.drawable.ic_pause }
    val consoleLogInfoText = LogControlState()
    val fabShareLog = LogControlState()
    val consoleLogShare = LogControlState()
    val consoleLogDelete = LogControlState()
    val searchFilterIcon = LogControlState()
    val consoleLogProgressBar = LogControlState().apply { visibility = View.GONE }
    var filterOpen by mutableStateOf(false)
    var level by mutableIntStateOf(0)
    var fileTrace by mutableStateOf(false)
}
internal fun consoleLevelColorAttribute(message: String) = when (message.firstOrNull()) {
    'I' -> R.attr.defaultToggleBtnTxt
    'W' -> R.attr.firewallWhiteListToggleBtnTxt
    'E' -> R.attr.accentBad
    else -> R.attr.primaryLightColorText
}
@Composable fun ConsoleLogRow(log: ConsoleLog) {
    // The original skipped binding empty messages. Preserve a blank slot, not a new timestamp.
    val timestamp = if (log.message.isEmpty()) "" else Utilities.convertLongToTime(log.timestamp, TIME_FORMAT_1)
    val size = logTextSize(R.dimen.default_font_text_view)
    Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 5.dp, top = 5.5.dp, bottom = 5.5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (DEBUG && log.message.isNotEmpty()) "${kotlin.math.abs(log.id)}\n$timestamp" else timestamp, fontFamily = logFont("serif-monospace"), fontSize = size, color = themeColor(R.attr.primaryLightColorText))
        SelectionContainer(Modifier.weight(1f).padding(start = 10.dp).alpha(.8f)) { Text(log.message, maxLines = 10, overflow = TextOverflow.Ellipsis, fontSize = size, color = themeColor(consoleLevelColorAttribute(log.message))) }
    }
}
@Composable fun ConsoleLogsScreen(b: ConsoleLogsState, presenter: LogPagingPresenter<ConsoleLog>, onLevel: (Int) -> Unit, onFileTrace: (Boolean) -> Unit) {
    Box(Modifier.fillMaxSize().background(themeColor(R.attr.background)).safeDrawingPadding()) {
        Column(Modifier.fillMaxSize()) {
            LogSearchBar(b.logs, stringResource(R.string.lbl_search), boxed = false, actions = {
                if (b.pauseIcon.isVisible) LogAction(b.pauseIcon.imageResource ?: R.drawable.ic_pause, stringResource(if (b.pauseIcon.imageResource == R.drawable.ic_pause) R.string.notification_action_pause_vpn else R.string.notification_action_resume_vpn), { b.pauseIcon.performClick() }, modifier = Modifier.testTag("console-pause"))
                LogAction(R.drawable.ic_filter, stringResource(R.string.fapps_filter_filter_heading), { b.searchFilterIcon.performClick() }, modifier = Modifier.testTag("console-filter"))
                LogAction(R.drawable.ic_share, stringResource(R.string.about_mail_bugreport_share_title), { b.consoleLogShare.performClick() }, modifier = Modifier.testTag("console-share"))
                LogAction(R.drawable.ic_delete, stringResource(R.string.lbl_delete), { b.consoleLogDelete.performClick() }, modifier = Modifier.testTag("console-delete"))
            })
            if (b.consoleLogProgressBar.isVisible) LinearProgressIndicator(Modifier.fillMaxWidth().padding(2.dp), color = themeColor(R.attr.secondaryTextColor))
            Text(b.consoleLogInfoText.text?.toString()?.ifEmpty { null } ?: stringResource(R.string.console_log_desc), Modifier.padding(horizontal = 10.dp, vertical = 5.dp), fontFamily = FontFamily.Monospace, fontSize = logTextSize(R.dimen.small_font_text_view))
            LogList(b.logs, presenter, Modifier.weight(1f), timestamp = { it.timestamp }, fastScroll = false, showHeader = false, prefetch = false)
        }
        ExtendedFloatingActionButton(onClick = { b.fabShareLog.performClick() }, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp).testTag("console-email"), containerColor = themeColor(R.attr.buttonBackground), contentColor = themeColor(R.attr.homeScreenHeaderTextColor)) { Text(b.fabShareLog.text?.toString().orEmpty()) }
    }
    if (b.filterOpen) ConsoleLevelDialog(b.level, b.fileTrace, onLevel, onFileTrace) { b.filterOpen = false }
}
@Composable fun ConsoleLevelDialog(level: Int, fileTrace: Boolean, onLevel: (Int) -> Unit, onFileTrace: (Boolean) -> Unit, dismiss: () -> Unit) {
    val options = listOf(R.string.settings_gologger_dialog_option_0, R.string.settings_gologger_dialog_option_1, R.string.settings_gologger_dialog_option_2, R.string.settings_gologger_dialog_option_3, R.string.settings_gologger_dialog_option_4, R.string.settings_gologger_dialog_option_5, R.string.settings_gologger_dialog_option_6, R.string.settings_gologger_dialog_option_7)
    Dialog(dismiss) {
        NoDimDialogWindow()
        Surface(shape = MaterialTheme.shapes.extraLarge, color = themeColor(R.attr.colorSurfaceContainerHigh)) {
            Column(Modifier.padding(vertical = 24.dp)) {
                Text(stringResource(R.string.console_log_title), Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.headlineSmall)
                LazyColumn(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * .4f).dp).padding(top = 16.dp)) {
                    items(options.size) { index -> Row(Modifier.fillMaxWidth().clickable { onLevel(index) }.padding(horizontal = 16.dp).testTag("console-level-$index"), verticalAlignment = Alignment.CenterVertically) { RadioButton(index == level, null); Text(stringResource(options[index]), Modifier.padding(start = 8.dp)) } }
                }
                Row(Modifier.fillMaxWidth().clickable { onFileTrace(!fileTrace) }.padding(horizontal = 20.dp, vertical = 8.dp).testTag("console-file-trace"), verticalAlignment = Alignment.CenterVertically) { Checkbox(fileTrace, null); Text(stringResource(R.string.console_log_include_file_trace), Modifier.padding(start = 8.dp)) }
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    OriginalButton("@dialog_negative", dismiss) { Text(stringResource(R.string.lbl_cancel)) }
                    OriginalButton("@dialog_positive", dismiss) { Text(stringResource(R.string.fapps_info_dialog_positive_btn)) }
                }
            }
        }
    }
}
