/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.celzero.bravedns.ui.compose.rpnconfig

import com.celzero.bravedns.ui.compose.controls.*
import com.celzero.bravedns.ui.compose.wireguard.WgSourceText
import com.celzero.bravedns.ui.compose.fields.OriginalTextField
import com.celzero.bravedns.ui.compose.fields.OriginalFieldStyle
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celzero.bravedns.R
import com.celzero.bravedns.data.SsidItem
import com.celzero.bravedns.ui.compose.themeColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun RpnSsidContent(state: RpnSsidState, countryName: String, add: () -> Unit, delete: (SsidItem) -> Unit, cancel: () -> Unit, save: () -> Unit) {
    val context = LocalContext.current
    val card = remember { Animatable(0f) }; val icon = remember { Animatable(0f) }; val rotation = remember { Animatable(0f) }
    val easing = remember { Easing { ((kotlin.math.cos((it + 1) * Math.PI) / 2.0) + .5).toFloat() } }
    LaunchedEffect(Unit) {
        launch { card.animateTo(1f, tween(400, easing = easing)) }
        launch { delay(100); icon.animateTo(1f, tween(500, easing = easing)) }
        launch { delay(100); rotation.animateTo(360f, tween(800, easing = easing)) }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(state.scrollRequest) { if (state.scrollTo >= 0 && state.scrollTo < state.items.size) listState.animateScrollToItem(state.scrollTo) }
    val pause = stringResource(R.string.notification_action_pause_vpn).lowercase().replaceFirstChar { it.uppercase() }
    val connect = stringResource(R.string.lbl_connect).lowercase().replaceFirstChar { it.uppercase() }
    Surface(Modifier.safeDrawingPadding().imePadding().padding(16.dp).graphicsLayer {
        alpha = card.value; scaleX = .9f + .1f * card.value; scaleY = scaleX; translationY = 50f * (1f - card.value)
    }, shape = RoundedCornerShape(24.dp), color = themeColor(com.google.android.material.R.attr.colorSurface)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(56.dp).background(Brush.linearGradient(listOf(Color(0xff9c27b0), Color(0xff2196f3), Color(0xff4caf50))), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.country_ssid_icon), Modifier.size(28.dp).graphicsLayer { scaleX = icon.value; scaleY = icon.value; rotationZ = rotation.value }, tint = Color.White)
                }
                WgSourceText(stringResource(R.string.country_ssid_dialog_title, countryName), "dialog_rpn_ssid:dialog_title", Modifier.weight(1f).padding(start = 16.dp))
            }
            WgSourceText(stringResource(R.string.country_ssid_dialog_description, if (state.equal) connect else pause, countryName,
                stringResource(R.string.lbl_ssid), stringResource(if (state.exact) R.string.wg_ssid_type_exact else R.string.wg_ssid_type_wildcard).lowercase()),
                "dialog_rpn_ssid:description_text_view")
            HorizontalDivider(color = themeColor(R.attr.primaryTextColor).copy(alpha = .1f))
            WgSourceText(stringResource(R.string.country_ssid_add_new), "dialog_rpn_ssid:input_section_title")
            OriginalTextField(state.name, { state.name = it }, Modifier.fillMaxWidth().testTag("rpn.ssid.input"), style = OriginalFieldStyle.RpnSsid, singleLine = true,
                label = { Text(stringResource(R.string.wg_ssid_input_hint, stringResource(R.string.lbl_ssids))) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { add() }))
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column {
                    WgSourceText(stringResource(R.string.wg_ssid_condition_label), "dialog_rpn_ssid:anonymous_14")
                    SsidOption(connect, state.equal, "rpn.ssid.equal") { state.equal = true }
                    SsidOption(pause, !state.equal, "rpn.ssid.notEqual") { state.equal = false }
                }
                Column {
                    WgSourceText(stringResource(R.string.wg_ssid_match_type_label), "dialog_rpn_ssid:anonymous_19")
                    SsidOption(stringResource(R.string.wg_ssid_type_exact), state.exact, "rpn.ssid.exact") { state.exact = true }
                    SsidOption(stringResource(R.string.wg_ssid_type_wildcard), !state.exact, "rpn.ssid.wildcard") { state.exact = false }
                }
            }
            OriginalButton("dialog_rpn_ssid:add_ssid_btn", add, enabled = state.name.isNotBlank(), modifier = Modifier.testTag("rpn.ssid.add")) { Text(stringResource(R.string.wg_ssid_add_button)) }
            HorizontalDivider(color = themeColor(R.attr.primaryTextColor).copy(alpha = .1f))
            WgSourceText(stringResource(R.string.country_ssid_configured_list), "dialog_rpn_ssid:list_section_title")
            if (state.items.isEmpty()) Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(painterResource(R.drawable.ic_info), null, Modifier.size(48.dp).alpha(.2f))
                WgSourceText(stringResource(R.string.country_ssid_empty_state), "dialog_rpn_ssid:anonymous_28", Modifier.padding(top = 12.dp))
            } else LazyColumn(Modifier.heightIn(max = 250.dp), state = listState) {
                itemsIndexed(state.items) { index, item ->
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        WgSourceText(item.name, "item_ssid:ssidNameText", Modifier.weight(1f))
                        WgSourceText(item.type.getDisplayName(context), "item_ssid:ssidTypeText", Modifier.padding(start = 8.dp).padding(4.dp))
                        IconButton({ delete(item) }, Modifier.testTag("rpn.ssid.delete.$index")) { Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.lbl_delete), tint = Color.Unspecified) }
                    }
                }
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OriginalButton("dialog_rpn_ssid:cancel_btn", cancel, Modifier.testTag("rpn.ssid.cancel")) { Text(stringResource(R.string.lbl_cancel)) }
                OriginalButton("dialog_rpn_ssid:save_btn", save, Modifier.testTag("rpn.ssid.save")) { Text(stringResource(R.string.lbl_save)) }
            }
        }
    }
}

@Composable
private fun SsidOption(label: String, selected: Boolean, tag: String, click: () -> Unit) {
    Row(Modifier.testTag(tag).selectable(selected, role = Role.RadioButton, onClick = click), verticalAlignment = Alignment.CenterVertically) { OriginalRadioButton("dialog_rpn_ssid:" + when (tag.substringAfterLast('.')) { "notEqual" -> "radio_not_equal"; else -> "radio_${tag.substringAfterLast('.')}" }, selected, null); WgSourceText(label, "dialog_rpn_ssid:" + when (tag.substringAfterLast('.')) { "notEqual" -> "radio_not_equal"; else -> "radio_${tag.substringAfterLast('.')}" }) }
}
