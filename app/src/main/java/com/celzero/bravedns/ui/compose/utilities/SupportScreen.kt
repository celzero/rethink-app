/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.utilities

import com.celzero.bravedns.ui.compose.controls.*
import com.celzero.bravedns.ui.compose.fields.OriginalTextField
import com.celzero.bravedns.ui.compose.fields.OriginalFieldStyle
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.lerp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.themeColor

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SupportScreen(ui: SupportUiState) {
    val density = LocalDensity.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(ui.keyboardRequest) { if (ui.keyboardRequest > 0) { focus.clearFocus(); keyboard?.hide() } }
    val range = with(density) { 124.dp.toPx() }
    var collapsed by rememberSaveable { mutableFloatStateOf(0f) }
    fun consume(delta: Float): Float { val old = collapsed; collapsed = (old - delta).coerceIn(0f, range); return old - collapsed }
    suspend fun snap() { val target = if (collapsed < range / 2) 0f else range; animate(collapsed, target, animationSpec = tween(220)) { value, _ -> collapsed = value } }
    val connection = remember(range) { object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset = if (available.y < 0) Offset(0f, consume(available.y)) else Offset.Zero
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) = Offset(0f, consume(available.y))
        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity { snap(); return Velocity.Zero }
    } }
    val fraction = (collapsed / range).coerceIn(0f, 1f)
    Surface(Modifier.fillMaxSize(), color = themeColor(R.attr.background)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().nestedScroll(connection)) {
            Box(Modifier.fillMaxWidth().height(with(density) { (180.dp.toPx() - collapsed).toDp() }).utilityHeroBackground()
                .testTag("support.header").draggable(rememberDraggableState { consume(it) }, Orientation.Vertical, onDragStopped = { snap() })) {
                Text(ui.headerTitle, Modifier.align(Alignment.BottomStart).padding(start = (24-8*fraction).dp, bottom = (48-32*fraction).dp).semantics { heading() },
                    color = themeColor(R.attr.primaryTextColor), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = lerp(utilityTextStyle(com.google.android.material.R.style.TextAppearance_Material3_HeadlineMedium),
                        utilityTextStyle(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge), fraction))
                if (fraction < 1) UtilityText(ui.tvHeroSubtitle, Modifier.align(Alignment.BottomStart).padding(start = 24.dp, end = 24.dp, bottom = 18.dp).alpha(.7f*(1-fraction)))
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp).testTag("support.scroll")) {
                SupportSection(R.string.support_section_describe)
                Card(Modifier.fillMaxWidth().padding(bottom = 20.dp), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = themeColor(androidx.cardview.R.attr.cardBackgroundColor))) {
                    Column(Modifier.padding(16.dp)) {
                        SupportLabel(R.string.support_issue_category_label, 13f, secondary = true, modifier = Modifier.padding(bottom = 8.dp))
                        FlowRow(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ui.categories.filter { it.isVisible }.forEach { chip -> OriginalChip("activity_customer_support:chip_payment", chip.isChecked, { ui.selectCategory(chip) }, label = { Text(chip.text.toString(), style = utilityTextStyle(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)) }, modifier = Modifier.testTag(chip.tag)) }
                        }
                        OriginalTextField(ui.etDescription.text.toString(), { ui.etDescription.text = utilityLengthLimitedInput(ui.etDescription.text.toString(), it, ui.etDescription.maxLength) },
                            Modifier.fillMaxWidth().testTag(ui.etDescription.tag), style = OriginalFieldStyle.Support, minLines = 5, maxLines = 10,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences),
                            label = { Text(ui.tilDescription.hint.toString()) }, isError = ui.tilDescription.error != null, errorMessage = ui.tilDescription.error?.toString(),
                            supportingText = {
                                Row(Modifier.fillMaxWidth()) {
                                    Text(ui.tilDescription.error?.toString().orEmpty(), Modifier.weight(1f))
                                    Text(stringResource(com.google.android.material.R.string.character_counter_pattern, ui.etDescription.text.length, 1000),
                                        Modifier.testTag("support.description.counter"),
                                        color = themeColor(if (ui.etDescription.text.length > 1000) androidx.appcompat.R.attr.colorError else R.attr.primaryLightColorText),
                                        style = utilityTextStyle(size = 12f))
                                }
                            },
                            textStyle = utilityTextStyle(size = 14f).copy(color = themeColor(R.attr.primaryTextColor)))
                    }
                }
                SupportSection(R.string.support_section_attachments)
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = themeColor(androidx.cardview.R.attr.cardBackgroundColor))) {
                    Column(Modifier.padding(16.dp)) {
                        SupportLabel(R.string.support_attachments_desc, 13f, secondary = true, lineExtraDp = 3f, modifier = Modifier.padding(bottom = 12.dp))
                        AttachmentRow(ui.switchAttachStatus, R.string.support_attach_sub_status, R.string.support_attach_sub_status_desc, null)
                        HorizontalDivider(color = themeColor(R.attr.divider))
                        AttachmentRow(ui.switchAttachStats, R.string.support_attach_vpn_stats, R.string.support_attach_vpn_stats_desc, 14f)
                        HorizontalDivider(color = themeColor(R.attr.divider))
                        AttachmentRow(ui.switchAttachProcInfo, R.string.support_attach_proc_info, R.string.support_attach_proc_info_desc, 14f)
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            Surface(color = themeColor(com.google.android.material.R.attr.colorSurface), shadowElevation = 8.dp) {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    OriginalButton("activity_customer_support:btn_send_email", { ui.btnSendEmail.performClick() }, enabled = ui.btnSendEmail.isEnabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(ui.btnSendEmail.tag)) {
                        Text(ui.btnSendEmail.text.toString(), style = utilityTextStyle(size = 16f), color = themeColor(R.attr.accentGood))
                    }
                    SupportLabel(R.string.support_privacy_note, 10f, secondary = true, lineExtraDp = 3f, centered = true, modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp, bottom = 4.dp).alpha(.8f))
                    if (ui.layoutLoading.isVisible) Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp).testTag("support.loading"))
                        SupportLabel(R.string.support_loading_data, 14f, secondary = true, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}
@Composable private fun SupportSection(id: Int) { Text(stringResource(id), Modifier.padding(start = 4.dp, bottom = 8.dp).semantics { heading() }, color = themeColor(androidx.appcompat.R.attr.colorPrimary), style = utilityTextStyle(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall, weight = android.graphics.Typeface.BOLD, spacing = .08f)) }
@Composable private fun SupportLabel(id: Int, size: Float?, secondary: Boolean = false, bold: Boolean = false, modifier: Modifier = Modifier, family: String? = null, lineExtraDp: Float? = null, centered: Boolean = false) { Text(stringResource(id), modifier, textAlign = if(centered) androidx.compose.ui.text.style.TextAlign.Center else androidx.compose.ui.text.style.TextAlign.Start, color = themeColor(if (secondary) R.attr.primaryLightColorText else R.attr.primaryTextColor), style = utilityTextStyle(size = size, family = family, weight = if (bold) android.graphics.Typeface.BOLD else null, lineExtraDp = lineExtraDp)) }
@Composable private fun AttachmentRow(state: UtilityControl, title: Int, description: Int, size: Float?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            SupportLabel(title, size, bold = true, family = if(size != null) "sans-serif-medium" else null)
            SupportLabel(description, 12f, secondary = true, modifier = Modifier.padding(top = 2.dp))
        }
        val label = stringResource(title)
        OriginalSwitch(state.tag.replace('.', ':'), state.isChecked, { state.isChecked = it }, modifier = Modifier.testTag(state.tag).semantics { contentDescription = label })
    }
}
