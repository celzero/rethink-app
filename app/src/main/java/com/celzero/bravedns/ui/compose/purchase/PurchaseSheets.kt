/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.purchase
import com.celzero.bravedns.ui.compose.controls.OriginalButton

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.themeColor

internal class DeviceErrorState {
    var accountId by mutableStateOf("")
    var deviceId by mutableStateOf("")
}
internal class ConflictState {
    var title by mutableStateOf("")
    var description by mutableStateOf("")
    var endpoint by mutableStateOf("")
    var httpCode by mutableStateOf("409")
    var serverMessageVisible by mutableStateOf(false)
    var serverMessage by mutableStateOf("")
    var refundVisible by mutableStateOf(false)
    var refundLabel by mutableStateOf("")
    var inFlight by mutableStateOf(false)
    var refundEnabled by mutableStateOf(true)
    var supportEnabled by mutableStateOf(true)
    var dismissEnabled by mutableStateOf(true)
}
internal class ProcessingStateUi {
    var progress by mutableStateOf(true)
    var iconVisible by mutableStateOf(false)
    var icon by mutableIntStateOf(R.drawable.ic_check_circle)
    var title by mutableStateOf("")
    var message by mutableStateOf("")
    var primaryVisible by mutableStateOf(false)
    var secondaryVisible by mutableStateOf(false)
    var primaryText by mutableStateOf("")
    var secondaryText by mutableStateOf("")
}
@Composable
internal fun PurchaseSheet(title: String, showHandle: Boolean = true, header: @Composable ColumnScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Surface {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (showHandle) Box(Modifier.width(40.dp).height(4.dp).align(Alignment.CenterHorizontally).background(themeColor(R.attr.divider)))
            header()
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}
@Composable internal fun PurchaseDetail(label: String, value: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodyMedium) }
    }
}
@Composable internal fun DeviceErrorContent(state: DeviceErrorState, unregistered: Boolean, onSupport: () -> Unit, onDismiss: () -> Unit) {
    PurchaseSheet(stringResource(if (unregistered) R.string.device_not_registered_title else R.string.device_auth_error_title), header = {
        Icon(painterResource(R.drawable.ic_device_lock), null, Modifier.size(72.dp).align(Alignment.CenterHorizontally), tint = themeColor(R.attr.accentBad))
    }) {
        Text(stringResource(if (unregistered) R.string.device_not_registered_badge else R.string.device_auth_error_http_code))
        Text(stringResource(if (unregistered) R.string.device_not_registered_desc else R.string.device_auth_error_desc))
        PurchaseDetail(stringResource(if (unregistered) R.string.device_not_registered_entitlement_cid_label else R.string.device_auth_error_account_id_label), state.accountId)
        if (unregistered) Text(stringResource(R.string.device_not_registered_master_badge), style = MaterialTheme.typography.labelSmall)
        PurchaseDetail(stringResource(R.string.device_auth_error_device_id_label), state.deviceId)
        Text(stringResource(R.string.device_auth_error_device_id_partial), style = MaterialTheme.typography.labelSmall)
        if (unregistered) {
            Text(stringResource(R.string.device_not_registered_what_this_means), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.device_not_registered_what_this_means_desc))
        }
        OriginalButton(if (unregistered) "bottomsheet_device_not_registered:btn_email_support" else "bottomsheet_device_auth_error:btn_email_support", onClick = onSupport, modifier = Modifier.fillMaxWidth().testTag("device_support")) { Text(stringResource(R.string.device_auth_error_btn_email)) }
        OriginalButton(if (unregistered) "bottomsheet_device_not_registered:btn_dismiss" else "bottomsheet_device_auth_error:btn_dismiss", onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.lbl_cancel)) }
    }
}
@Composable internal fun ConflictContent(state: ConflictState, onRefund: () -> Unit, onSupport: () -> Unit, onDismiss: () -> Unit) {
    PurchaseSheet(state.title, header = {
        Icon(painterResource(R.drawable.ic_filter_error), null, Modifier.size(64.dp).align(Alignment.CenterHorizontally), tint = themeColor(R.attr.accentBad))
    }) {
        Text(state.description)
        PurchaseDetail(stringResource(R.string.parse_error_inet_endpoint), state.endpoint)
        PurchaseDetail(stringResource(R.string.conflict_detail_http_code), state.httpCode)
        if (state.serverMessageVisible) Text(state.serverMessage, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (state.refundVisible) OriginalButton("bottomsheet_purchase_conflict:btn_refund", onRefund, enabled = state.refundEnabled, modifier = Modifier.fillMaxWidth().testTag("conflict_refund")) { Text(state.refundLabel) }
        if (state.inFlight) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).testTag("conflict_progress"))
        OriginalButton("bottomsheet_purchase_conflict:btn_contact_support", onSupport, enabled = state.supportEnabled, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.contact_support_title)) }
        OriginalButton("bottomsheet_purchase_conflict:btn_dismiss", onDismiss, enabled = state.dismissEnabled, modifier = Modifier.fillMaxWidth().testTag("conflict_dismiss")) { Text(stringResource(R.string.lbl_cancel)) }
    }
}
@Composable internal fun ProcessingContent(state: ProcessingStateUi, onPrimary: () -> Unit, onSecondary: () -> Unit) {
    PurchaseSheet(state.title, showHandle = false, header = {
        if (state.progress) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).testTag("purchase_progress"))
        if (state.iconVisible) Icon(painterResource(state.icon), null, Modifier.size(56.dp).align(Alignment.CenterHorizontally))
    }) {
        Text(state.message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (state.primaryVisible) OriginalButton("bottomsheet_purchase_processing:action_button", onPrimary, Modifier.fillMaxWidth().testTag("purchase_primary")) { Text(state.primaryText) }
        if (state.secondaryVisible) OriginalButton("bottomsheet_purchase_processing:secondary_button", onSecondary, Modifier.fillMaxWidth().testTag("purchase_secondary")) { Text(state.secondaryText) }
    }
}
