/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.purchase
import com.celzero.bravedns.ui.compose.controls.OriginalButton

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.rpn.RpnSignature
import com.celzero.bravedns.ui.compose.rpn.RpnSignatureState

internal class EntitlementRowState {
    var label by mutableStateOf("")
    var value by mutableStateOf("")
    var divider by mutableStateOf(true)
}
internal class EntitlementUiState {
    val rowRpnActive = EntitlementRowState()
    val rowStatus = EntitlementRowState()
    val rowCid = EntitlementRowState()
    val rowDid = EntitlementRowState()
    val rowWho = EntitlementRowState()
    val rowExpiry = EntitlementRowState()
    val rowProvider = EntitlementRowState()
    val rowToken = EntitlementRowState()
    val rowAllowRestore = EntitlementRowState()
    val rowTest = EntitlementRowState()
    val rows = listOf(rowRpnActive, rowStatus, rowCid, rowDid, rowWho, rowExpiry, rowProvider, rowToken, rowAllowRestore, rowTest)
    val signature = RpnSignatureState()
    var restoreVisible by mutableStateOf(false)
    var restoreEnabled by mutableStateOf(true)
    var restoreAlpha by mutableFloatStateOf(1f)
    var restoreLabel by mutableStateOf<String?>(null)
}
@Composable internal fun EntitlementContent(state: EntitlementUiState, onRestore: () -> Unit, onDismiss: () -> Unit) {
    PurchaseSheet("Entitlement Details") {
        state.rows.forEach { row -> PurchaseDetail(row.label, row.value); if (row.divider) HorizontalDivider() }
        if (state.restoreVisible) {
            Text(stringResource(R.string.rpn_restore_confirm_message))
            OriginalButton("bottomsheet_entitlement_detail:btn_restore", onRestore, enabled = state.restoreEnabled, modifier = Modifier.fillMaxWidth().alpha(state.restoreAlpha).testTag("entitlement_restore")) {
                Text(state.restoreLabel ?: stringResource(R.string.rpn_restore_confirm_title))
            }
        }
        RpnSignature(state.signature, Modifier.fillMaxWidth())
        OriginalButton("bottomsheet_entitlement_detail:btn_dismiss", onDismiss, Modifier.fillMaxWidth()) { Text(stringResource(R.string.lbl_dismiss)) }
    }
}
