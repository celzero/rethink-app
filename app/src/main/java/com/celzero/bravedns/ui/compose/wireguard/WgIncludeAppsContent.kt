/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.celzero.bravedns.ui.compose.wireguard

import com.celzero.bravedns.ui.compose.controls.*
import com.celzero.bravedns.ui.compose.fields.OriginalTextField
import com.celzero.bravedns.ui.compose.fields.OriginalFieldStyle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R

@Composable fun WgIncludeAppsContent(b: WgIncludeAppsState, onSubmit: (String) -> Boolean) {
    val requester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            WgText(b.wgIncludeAppDialogHeading, Modifier.padding(16.dp))
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OriginalTextField(b.wgIncludeAppDialogSearchView.text.toString(), { b.wgIncludeAppDialogSearchView.setText(it) },
                    Modifier.weight(1f).focusRequester(requester), singleLine = true,
                    style = OriginalFieldStyle.Search, placeholder = { Text(stringResource(R.string.search_proxy_add_apps)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        val query = b.wgIncludeAppDialogSearchView.text.toString()
                        // SearchView uses String.trim(), which trims ASCII <= U+0020.
                        if (query.trim { it <= ' ' }.isNotEmpty()) { onSubmit(query); keyboard?.hide() }
                    }), trailingIcon = {
                        if (b.wgIncludeAppDialogSearchView.text.isNotEmpty()) IconButton({
                            b.wgIncludeAppDialogSearchView.setText("")
                            requester.requestFocus(); keyboard?.show()
                        }) { Icon(painterResource(androidx.appcompat.R.drawable.abc_ic_clear_material), stringResource(R.string.fapps_filter_clear_btn), tint = com.celzero.bravedns.ui.compose.themeColor(androidx.appcompat.R.attr.colorControlNormal)) }
                    })
                if (b.wgRefreshList.isIndeterminate) CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp))
                else WgIcon(b.wgRefreshList, stringResource(R.string.wg_compose_refresh))
            }
            FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                b.filters.forEach { chip -> OriginalChip("item_chip_filter:(anonymous)", chip.isChecked, { chip.isChecked = true }, label = { Text(chip.text.toString()) }) }
            }
            FlowRow(Modifier.padding(horizontal = 12.dp)) {
                listOf(b.wgIncludeAppBulkCheck, b.wgIncludeAppDeselectAllCheck).forEach { node ->
                    Row(Modifier.toggleable(node.isChecked, enabled = node.isEnabled && node.isClickable,
                        role = Role.Checkbox) { node.performClick() }, verticalAlignment = Alignment.CenterVertically) {
                        WgCheck(node, compoundSource = "dialog_wg_apps:" + if (node === b.wgIncludeAppBulkCheck) "wg_include_app_bulk_check" else "wg_include_app_deselect_all_check", interactive = false)
                        WgText(node, interactive = false)
                    }
                }
            }
            b.wgIncludeAppRecyclerViewDialog.adapter?.Content(Modifier.weight(1f))
        }
    }
}
