/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.purchase
import com.celzero.bravedns.ui.compose.controls.OriginalButton
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.NoDimDialogWindow
import com.celzero.bravedns.ui.compose.rpn.*
import com.celzero.bravedns.ui.compose.themeColor

internal class ManagePurchaseState {
    val dolphinSignature = RpnSignatureState()
    val tvManageStatusText = RpnControlState()
    val tvHeroWho = RpnControlState()
    val tvManagePlanName = RpnControlState()
    val tvManagePurchasedDate = RpnControlState()
    val tvManageToken = RpnControlState()
    val rowOrderHistory = RpnControlState()
    val rowManageOnPlay = RpnControlState()
    val dividerManagePlay = RpnControlState()
    val rowReportBillingIssue = RpnControlState()
    val rowRequestRefund = RpnControlState()
    val rowCancelPurchase = RpnControlState()
    val dividerRefund = RpnControlState()
    val tvEndingPlanHeader = RpnControlState()
    val cardEndingPlan = RpnControlState()
    val loadingOverlay = RpnControlState().apply { isVisible = false }
    val tvLoadingMessage = RpnControlState()
    val tvLoadingSubMessage = RpnControlState()
    var operationStep by mutableIntStateOf(0)
    var confirmCancel by mutableStateOf<Boolean?>(null)
}
@Composable private fun PurchaseActionRow(state: RpnControlState, title: Int, description: Int, tag: String) {
    if (!state.isVisible) return
    Row(Modifier.fillMaxWidth().alpha(state.alpha).testTag(tag)
        .semantics { if (!state.isEnabled) disabled(); state.contentDescription?.let { contentDescription = it.toString() } }
        .clickable(enabled = state.isEnabled, role = Role.Button) { state.performClick() }.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(painterResource(when (tag) { "manage_cancel" -> R.drawable.ic_cross; "manage_support" -> R.drawable.ic_info_white; else -> R.drawable.ic_rethink_plus }), null, Modifier.size(24.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(painterResource(R.drawable.ic_right_arrow_white), null, Modifier.size(20.dp))
    }
}
@Composable internal fun ManagePurchaseContent(state: ManagePurchaseState, onConfirm: (Boolean) -> Unit) {
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp).padding(bottom = 176.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.rpnHeroBackground().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.rpn_overhauled_current_plan_header), style = MaterialTheme.typography.labelLarge)
                    RpnText(state.tvManageStatusText)
                    RpnText(state.tvManagePlanName, style = MaterialTheme.typography.headlineSmall)
                    RpnText(state.tvManagePurchasedDate)
                    RpnText(state.tvManageToken, style = MaterialTheme.typography.bodySmall)
                    if (state.tvHeroWho.isVisible) Text(state.tvHeroWho.text.toString(), Modifier.clickable { state.tvHeroWho.performClick() })
                }
            }
            Text(stringResource(R.string.rpn_overhauled_plan_details_header), Modifier.semantics { heading() })
            Card {
                PurchaseActionRow(state.rowOrderHistory, R.string.server_order_title, R.string.server_order_desc, "manage_history")
                if (state.dividerManagePlay.isVisible) HorizontalDivider()
                PurchaseActionRow(state.rowManageOnPlay, R.string.rpn_overhauled_manage_google_play_title, R.string.rpn_overhauled_manage_google_play_desc, "manage_play")
            }
            if (state.tvEndingPlanHeader.isVisible) Text(stringResource(R.string.rpn_overhauled_ending_plan_header), Modifier.semantics { heading() })
            if (state.cardEndingPlan.isVisible) Card {
                PurchaseActionRow(state.rowRequestRefund, R.string.rpn_overhauled_request_refund_title, R.string.rpn_overhauled_request_refund_desc, "manage_refund")
                if (state.dividerRefund.isVisible) HorizontalDivider()
                PurchaseActionRow(state.rowCancelPurchase, R.string.cancel_subscription, R.string.rpn_overhauled_cancel_purchase_desc, "manage_cancel")
            }
            Text(stringResource(R.string.about_connect), Modifier.semantics { heading() })
            Card { PurchaseActionRow(state.rowReportBillingIssue, R.string.contact_support_title, R.string.rpn_report_desc, "manage_support") }
        }
        val atEnd = scroll.maxValue - scroll.value <= scroll.viewportSize * .25f
        val signatureAlpha by animateFloatAsState(if (atEnd) 1f else 0f, tween(200), label = "signatureReveal")
        if (signatureAlpha > 0f) RpnSignature(state.dolphinSignature, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp).alpha(signatureAlpha))
        if (state.loadingOverlay.isVisible) Surface(Modifier.matchParentSize().clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {}, color = MaterialTheme.colorScheme.surface.copy(alpha = .97f)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp), verticalArrangement = Arrangement.spacedBy(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                RpnText(state.tvLoadingMessage, style = MaterialTheme.typography.titleLarge)
                RpnText(state.tvLoadingSubMessage)
                listOf(R.string.progress_step_validating, R.string.progress_step_contacting_server, R.string.progress_step_updating, R.string.progress_step_refreshing).forEachIndexed { index, label ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(painterResource(R.drawable.ic_tick), null, tint = themeColor(if (index <= state.operationStep) R.attr.accentGood else R.attr.primaryTextColor))
                        Text(stringResource(label), Modifier.alpha(if (index <= state.operationStep) 1f else .5f))
                    }
                }
            }
        }
    }
    state.confirmCancel?.let { isCancel ->
        AlertDialog(onDismissRequest = { state.confirmCancel = null },
            title = { NoDimDialogWindow(); Text(stringResource(if (isCancel) R.string.confirm_cancel_title else R.string.confirm_revoke_title)) },
            text = { Text(stringResource(if (isCancel) R.string.confirm_cancel_message else R.string.confirm_revoke_message)) },
            confirmButton = { OriginalButton("@dialog_positive", onClick = { onConfirm(isCancel); state.confirmCancel = null }, modifier = Modifier.testTag("manage_confirm")) { Text(stringResource(if (isCancel) R.string.cancel_subscription else R.string.revoke_subscription)) } },
            dismissButton = { OriginalButton("@dialog_negative", onClick = { state.confirmCancel = null }, modifier = Modifier.testTag("manage_confirm_cancel")) { Text(stringResource(R.string.lbl_cancel)) } })
    }
}
