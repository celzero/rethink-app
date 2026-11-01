/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.about

import com.celzero.bravedns.ui.compose.controls.*
import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.ui.compose.wireguard.WgControlState

/** File-free row model. Metadata and side effects are supplied exclusively by the controller. */
class BugReportRowState(val key: String, val name: String, val details: String, val icon: Int,
    val checkbox: WgControlState, val onOpen: () -> Unit, val onDelete: () -> Unit)

class BugReportFilesState(context: Context) {
    val brbsSelectAllCheckbox = WgControlState(context, checkable = true).apply { isChecked = true }
    val brbsSelectAllText = WgControlState(context).apply { text = context.getText(R.string.lbl_select_all) }
    val brbsProcInfoCheckbox = WgControlState(context, checkable = true).apply { isChecked = true }
    val brbsSendButton = WgControlState(context).apply { text = context.getText(R.string.about_bug_no_log_dialog_positive_btn) }
    val brbsProgressLayout = WgControlState().apply { visibility = View.GONE }
    val brbsProgressText = WgControlState(context).apply { text = context.getText(R.string.collecting_logs_progress_text) }
    val brbsTotalSize = WgControlState()
    var rows: List<BugReportRowState> by mutableStateOf(emptyList())
    fun saveChecks(out: Bundle) {
        out.putBoolean("compose.bugreport.all", brbsSelectAllCheckbox.isChecked)
        out.putBoolean("compose.bugreport.proc", brbsProcInfoCheckbox.isChecked)
    }
    fun restoreChecks(saved: Bundle) {
        if (saved.containsKey("compose.bugreport.proc")) brbsProcInfoCheckbox.isChecked = saved.getBoolean("compose.bugreport.proc")
        if (saved.containsKey("compose.bugreport.all")) brbsSelectAllCheckbox.isChecked = saved.getBoolean("compose.bugreport.all")
    }
}

@Composable
fun BugReportFilesContent(b: BugReportFilesState) {
    Surface(color = themeColor(R.attr.background)) {
        Column(Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 10.dp).padding(bottom = 20.dp)) {
            DiagnosticSheetHandle()
            Text(stringResource(R.string.about_bug_report), Modifier.padding(horizontal = 25.dp, vertical = 10.dp), fontWeight = FontWeight.Bold, fontSize = 16.sp, color = themeColor(R.attr.primaryLightColorText))
            Text(stringResource(R.string.bug_report_files_desc), Modifier.padding(horizontal = 25.dp).padding(bottom = 10.dp), fontSize = 12.sp, color = themeColor(R.attr.primaryLightColorText))
            Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                DiagnosticCheckbox(b.brbsProcInfoCheckbox, stringResource(R.string.bug_report_include_proc_info), "bugreport.proc")
                Text(stringResource(R.string.bug_report_include_proc_info), Modifier.padding(start = 8.dp), fontSize = 12.sp, color = themeColor(R.attr.primaryTextColor))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                DiagnosticCheckbox(b.brbsSelectAllCheckbox, b.brbsSelectAllText.text.toString(), "bugreport.all")
                Text(b.brbsSelectAllText.text.toString(), Modifier.weight(1f).clickable { b.brbsSelectAllText.performClick() }.padding(start = 8.dp), fontWeight = FontWeight.Bold, fontSize = 12.sp, color = themeColor(R.attr.primaryTextColor))
                Text(b.brbsTotalSize.text.toString(), Modifier.padding(end = 8.dp), fontSize = 10.sp, color = themeColor(R.attr.primaryTextColor))
            }
            HorizontalDivider(Modifier.padding(horizontal = 15.dp), color = themeColor(R.attr.border))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(Modifier.fillMaxSize().testTag("bugreport.files"), contentPadding = PaddingValues(bottom = 12.dp)) {
                    items(b.rows, key = { it.key }) { row -> BugReportFileRow(row) }
                }
                if (b.brbsProgressLayout.isVisible) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(b.brbsProgressText.text.toString(), Modifier.padding(top = 10.dp), fontSize = 12.sp, color = themeColor(R.attr.secondaryTextColor))
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 15.dp), color = themeColor(R.attr.border))
            OriginalButton("bottom_sheet_bug_report_files:brbs_send_button", { b.brbsSendButton.performClick() }, Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 10.dp).heightIn(min = 48.dp).alpha(b.brbsSendButton.alpha).testTag("bugreport.send"), enabled = b.brbsSendButton.isEnabled) {
                Text(b.brbsSendButton.text.toString())
            }
        }
    }
}

@Composable
fun BugReportFileRow(row: BugReportRowState) {
    Row(Modifier.fillMaxWidth().testTag("bugreport.file.${row.key}").toggleable(row.checkbox.isChecked, role = Role.Checkbox) { row.checkbox.performClick() }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        OriginalCheckbox("item_bug_report_file:item_file_checkbox", row.checkbox.isChecked, null)
        Icon(painterResource(row.icon), null, Modifier.padding(start = 8.dp, end = 12.dp).size(32.dp), tint = themeColor(R.attr.accentGood))
        Column(Modifier.weight(1f)) {
            Text(row.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = themeColor(R.attr.primaryTextColor))
            Text(row.details, Modifier.padding(top = 2.dp), fontSize = 10.sp, color = themeColor(R.attr.primaryLightColorText))
        }
        IconButton(row.onOpen, Modifier.size(40.dp).testTag("bugreport.open.${row.key}")) { Icon(painterResource(R.drawable.ic_visibility), stringResource(R.string.about_bug_report), Modifier.size(20.dp), tint = themeColor(R.attr.primaryTextColor)) }
        IconButton(row.onDelete, Modifier.size(40.dp).testTag("bugreport.delete.${row.key}")) { Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.lbl_delete), Modifier.size(20.dp), tint = themeColor(R.attr.accentBad)) }
    }
}

@Composable
internal fun DiagnosticSheetHandle() {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Spacer(Modifier.padding(top = 10.dp, bottom = 20.dp).size(60.dp, 3.dp).background(themeColor(R.attr.border)))
    }
}

@Composable
private fun DiagnosticCheckbox(node: WgControlState, label: String, tag: String) {
    OriginalCheckbox("bottom_sheet_bug_report_files:" + if (tag == "bugreport.proc") "brbs_proc_info_checkbox" else "brbs_select_all_checkbox", node.isChecked, { node.performClick() }, Modifier.testTag(tag).semantics { contentDescription = label }, enabled = node.isEnabled)
}

@Composable
internal fun diagnosticCheckboxColors() = CheckboxDefaults.colors(checkedColor = themeColor(R.attr.accentGood), uncheckedColor = themeColor(R.attr.accentGood))
