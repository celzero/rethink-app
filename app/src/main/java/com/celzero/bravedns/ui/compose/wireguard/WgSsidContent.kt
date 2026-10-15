/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.celzero.bravedns.ui.compose.wireguard

import com.celzero.bravedns.ui.compose.controls.*

import androidx.compose.ui.graphics.Color
import com.celzero.bravedns.ui.compose.fields.OriginalTextField
import com.celzero.bravedns.ui.compose.fields.OriginalFieldStyle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.data.SsidItem
import com.celzero.bravedns.ui.compose.themeColor
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

class WgSsidPresenter(private val items: MutableList<SsidItem>, val onDelete: (SsidItem) -> Unit) {
    private var revision by mutableIntStateOf(0)
    fun notifyDataSetChanged() { revision++ }
    fun getSsidItems(): List<SsidItem> { revision; return items.toList() }
    fun addSsidItem(item: SsidItem) {
        if (items.none { it.name == item.name && it.type == item.type }) { items.add(item); revision++ }
    }
    fun removeSsidItem(item: SsidItem) {
        val index = items.indexOf(item)
        if (index != -1) { items.removeAt(index); revision++ }
    }
}

@Composable private fun WgRadio(sourceId: String, node: WgControlState) {
    Row(Modifier.selectable(selected = node.isChecked, role = Role.RadioButton, enabled = node.isEnabled && node.isClickable) { node.isChecked = true }, verticalAlignment = Alignment.CenterVertically) {
        OriginalRadioButton("dialog_wg_ssid:$sourceId", node.isChecked, null, enabled = node.isEnabled && node.isClickable, indicatorOnly = true)
        WgText(node)
    }
}

@Composable fun WgSsidContent(b: WgSsidState, presenter: WgSsidPresenter?) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val done = b.ssidEditText.onEditorDone
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            WgSourceText(stringResource(R.string.wg_setting_ssid_title), "dialog_wg_ssid:anonymous_3")
            WgText(b.descriptionTextView)
            if (b.ssidRecyclerView.isVisible) presenter?.getSsidItems()?.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { WgSourceText(item.name, "item_ssid:ssidNameText"); WgSourceText(item.type.getDisplayName(context), "item_ssid:ssidTypeText", Modifier.padding(4.dp)) }
                    IconButton({ presenter.onDelete(item) }) { Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.lbl_delete), tint = Color.Unspecified) }
                }
            }
            WgText(b.emptyStateView)
            WgText(b.ssidConditionHeading)
            FlowRow { WgRadio("radioEqual", b.radioEqual); WgRadio("radioNotEqual", b.radioNotEqual) }
            WgSourceText(stringResource(R.string.lbl_criteria), "dialog_wg_ssid:anonymous_12")
            FlowRow { WgRadio("radioExact", b.radioExact); WgRadio("radioWildcard", b.radioWildcard) }
            WgField(b.ssidEditText, b.ssidTextInputLayout.hint, fieldStyle = OriginalFieldStyle.WgSsid, onDone = { keyboard?.hide(); scope.launch { yield(); focus.clearFocus(); done?.invoke() } })
            WgAction(b.addSsidBtn, source = "dialog_wg_ssid:addSsidBtn")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { WgAction(b.cancelBtn, source = "dialog_wg_ssid:cancelBtn"); WgAction(b.saveBtn, source = "dialog_wg_ssid:saveBtn") }
        }
    }
}
