/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.about

import com.celzero.bravedns.ui.compose.controls.*

import android.content.Context
import android.os.Bundle
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.ui.compose.controls.OriginalButton
import com.celzero.bravedns.ui.compose.wireguard.WgControlState

/** Changed-only single-selection events in MaterialButtonToggleGroup order. */
class DiagnosticToggleGroup(initialId: Int) {
    var checkedButtonId by mutableIntStateOf(initialId)
        private set
    private var listener: ((DiagnosticToggleGroup, Int, Boolean) -> Unit)? = null
    fun check(id: Int) {
        if (checkedButtonId == id) return
        val old = checkedButtonId
        checkedButtonId = id
        listener?.invoke(this, old, false)
        listener?.invoke(this, id, true)
    }
    fun addOnButtonCheckedListener(value: (DiagnosticToggleGroup, Int, Boolean) -> Unit) { listener = value }
}

data class DnsRecordRowState(val name: String, val description: String, val selected: Boolean, val locked: Boolean, val onSelect: () -> Unit)

class DnsRecordTypesState(context: Context) {
    val drbsModeToggleGroup = DiagnosticToggleGroup(R.id.drbs_auto_mode_btn)
    val drbsAutoModeBtn = WgControlState(context).apply { text = context.getText(R.string.settings_ip_text_ipv46) }
    val drbsManualModeBtn = WgControlState(context).apply { text = context.getText(R.string.lbl_manual) }
    val drbsManualSection = WgControlState()
    var animateMode by mutableStateOf(false)
    var rows: List<DnsRecordRowState> by mutableStateOf(emptyList())
    fun saveMode(out: Bundle) { out.putInt("compose.dnsrecords.mode", drbsModeToggleGroup.checkedButtonId) }
    fun restoreMode(saved: Bundle) { if (saved.containsKey("compose.dnsrecords.mode")) drbsModeToggleGroup.check(saved.getInt("compose.dnsrecords.mode")) }
}

@Composable
fun DnsRecordTypesContent(b: DnsRecordTypesState) {
    Surface(color = themeColor(R.attr.background)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 10.dp).padding(bottom = 40.dp)) {
            DiagnosticSheetHandle()
            Text(stringResource(R.string.cd_allowed_dns_record_types_heading), Modifier.padding(horizontal = 25.dp, vertical = 10.dp), fontSize = 16.sp, color = themeColor(R.attr.primaryLightColorText))
            Text(stringResource(R.string.cd_allowed_dns_record_types_desc), Modifier.padding(horizontal = 25.dp).padding(bottom = 10.dp), fontSize = 12.sp, color = themeColor(R.attr.primaryLightColorText))
            Row(Modifier.fillMaxWidth().padding(horizontal = 25.dp).padding(top = 5.dp, bottom = 15.dp).selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy((-1).dp)) {
                listOf(R.id.drbs_auto_mode_btn to b.drbsAutoModeBtn, R.id.drbs_manual_mode_btn to b.drbsManualModeBtn).forEach { (id, node) ->
                    val selected = b.drbsModeToggleGroup.checkedButtonId == id
                    val automatic = id == R.id.drbs_auto_mode_btn
                    OriginalButton(
                        source = "bottom_sheet_dns_record_types:" + if (automatic) "drbs_auto_mode_btn" else "drbs_manual_mode_btn",
                        onClick = { b.drbsModeToggleGroup.check(id) },
                        modifier = Modifier.weight(1f).testTag(if (automatic) "dnsrecords.auto" else "dnsrecords.manual"),
                        enabled = node.isEnabled && node.isClickable,
                        selected = selected, segmentIndex = if (automatic) 0 else 1, segmentCount = 2,
                        contentColor = node.textColor?.let(::Color), backgroundTint = node.backgroundTintList,
                    ) {
                        Text(node.text.toString(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
            val alpha by animateFloatAsState(b.drbsManualSection.alpha, animationSpec = if (b.animateMode) tween(200) else snap(), label = "DNS manual section")
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp).alpha(alpha).testTag("dnsrecords.list"), contentPadding = PaddingValues(bottom = 15.dp)) {
                items(b.rows, key = { it.name }) { row -> DnsRecordTypeRow(row) }
            }
        }
    }
}

@Composable
fun DnsRecordTypeRow(row: DnsRecordRowState) {
    Row(Modifier.fillMaxWidth().alpha(if (row.locked) .6f else 1f)
        .toggleable(row.selected, enabled = !row.locked, role = Role.Checkbox) { row.onSelect() }.testTag("dnsrecords.${row.name}")
        .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OriginalCheckbox("item_dns_record_type:item_dns_record_type_checkbox", row.selected, null, enabled = !row.locked)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(row.name, fontSize = 14.sp, color = themeColor(R.attr.primaryTextColor))
            Text(row.description, fontSize = 12.sp, color = themeColor(R.attr.primaryLightColorText))
        }
    }
}
