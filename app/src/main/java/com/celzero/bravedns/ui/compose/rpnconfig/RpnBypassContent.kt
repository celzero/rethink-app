/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.celzero.bravedns.ui.compose.rpnconfig

import com.celzero.bravedns.ui.compose.controls.*
import com.celzero.bravedns.ui.compose.fields.OriginalTextField
import com.celzero.bravedns.ui.compose.fields.OriginalFieldStyle
import android.content.Context
import android.graphics.drawable.Drawable
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.ui.compose.wireguard.*

class RpnBypassState(context: Context) {
    val rpnBypassCount = WgControlState(context, textSource = "activity_rpn_bypass_apps:rpn_bypass_count").apply { text = context.getString(R.string.rpn_bypass_apps_none) }
    val rpnBypassRefreshList = WgControlState(context)
    val rpnBypassBulkCheck = WgControlState(context, checkable = true, textSource = "activity_rpn_bypass_apps:rpn_bypass_bulk_check").apply { text = context.getString(R.string.lbl_select_all) }
    val rpnBypassDeselectAllCheck = WgControlState(context, checkable = true, textSource = "activity_rpn_bypass_apps:rpn_bypass_deselect_all_check").apply { text = context.getString(R.string.lbl_unselect_all) }
    var refreshSpinning by mutableStateOf(false)
    var refreshGeneration by mutableIntStateOf(0)
    fun startRefresh() { refreshSpinning = true; refreshGeneration++ }
}

@Composable
fun RpnBypassContent(b: RpnBypassState, query: String, selectedFilter: Int, search: (String) -> Unit, submit: () -> Unit,
                     filter: (Int) -> Unit, apps: @Composable (Modifier) -> Unit) {
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val angle = if (b.refreshSpinning) key(b.refreshGeneration) {
        val transition = rememberInfiniteTransition(label = "bypass refresh")
        transition.animateFloat(0f, 360f, infiniteRepeatable(tween(750, easing = Easing { ((kotlin.math.cos((it + 1) * Math.PI) / 2.0) + .5).toFloat() })), label = "refresh angle").value
    } else 0f
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().imePadding().padding(10.dp)) {
            WgSourceText(stringResource(R.string.fapps_bypass_block_dialog_title), "activity_rpn_bypass_apps:rpn_bypass_heading", Modifier.padding(5.dp))
            WgText(b.rpnBypassCount, Modifier.padding(5.dp).testTag("rpn.bypass.count"))
            Surface(shadowElevation = 4.dp, color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OriginalTextField(query, search, Modifier.weight(1f).focusRequester(searchFocus).testTag("rpn.bypass.search"), singleLine = true,
                        placeholder = { Text(stringResource(R.string.rpn_bypass_search_hint)) }, leadingIcon = { Icon(Icons.Default.Search, null) },
                        style = OriginalFieldStyle.Search,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { if (query.trim { it <= ' ' }.isNotEmpty()) { submit(); keyboard?.hide() } }),
                        trailingIcon = { if (query.isNotEmpty()) IconButton({ search(""); searchFocus.requestFocus(); keyboard?.show() }, Modifier.testTag("rpn.bypass.clear")) {
                            Icon(painterResource(androidx.appcompat.R.drawable.abc_ic_clear_material), stringResource(R.string.lbl_dismiss), tint = themeColor(androidx.appcompat.R.attr.colorControlNormal))
                        } })
                    IconButton({ b.rpnBypassRefreshList.performClick() }, enabled = b.rpnBypassRefreshList.isEnabled, modifier = Modifier.testTag("rpn.bypass.refresh")) {
                        Icon(painterResource(R.drawable.ic_refresh_white), stringResource(R.string.wg_compose_refresh), Modifier.rotate(angle), tint = Color.Unspecified)
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("all" to b.rpnBypassBulkCheck, "none" to b.rpnBypassDeselectAllCheck).forEach { (tag, node) ->
                    Row(Modifier.testTag("rpn.bypass.$tag").toggleable(node.isChecked,
                        enabled = node.isEnabled && node.isClickable, role = Role.Checkbox) { node.performClick() },
                        verticalAlignment = Alignment.CenterVertically) {
                        WgCheck(node, compoundSource = "activity_rpn_bypass_apps:" + if (tag == "all") "rpn_bypass_bulk_check" else "rpn_bypass_deselect_all_check", interactive = false)
                        WgText(node, Modifier.padding(end = 8.dp), interactive = false)
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(R.string.lbl_all, R.string.fapps_firewall_filter_bypass_universal, R.string.lbl_unselected).forEachIndexed { id, label ->
                    OriginalChip("item_chip_filter:(anonymous)", selectedFilter == id, { if (selectedFilter != id) filter(id) }, label = { Text(stringResource(label)) }, modifier = Modifier.testTag("rpn.bypass.filter.$id"))
                }
            }
            apps(Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable
fun RpnBypassAppRow(name: String, packageName: String, excluded: Boolean, icon: Drawable?, toggle: (Boolean) -> Unit) {
    val context = LocalContext.current
    val switch = remember(context, packageName) { WgControlState(context, checkable = true).apply { switchThemeOverlay = R.style.CustomCompoundButtonSwitch } }
    switch.isChecked = excluded
    switch.setOnClickListener { toggle(it.isChecked) }
    Row(Modifier.fillMaxWidth().testTag("rpn.bypass.app.$packageName").clickable { toggle(!excluded) }.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        icon?.let { drawable ->
            val bitmap = remember(drawable) { drawable.toBitmap(48, 48).asImageBitmap() }
            Image(bitmap, null, Modifier.size(36.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { WgSourceText(name, "list_item_rpn_bypass_app:rpn_bypass_app_name"); WgSourceText(packageName, "list_item_rpn_bypass_app:rpn_bypass_app_package") }
        WgCheck(switch, name, switch = true, modifier = Modifier.testTag("rpn.bypass.switch.$packageName"), compoundSource = "list_item_rpn_bypass_app:rpn_bypass_app_switch")
    }
}
