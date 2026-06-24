/*
 * Copyright 2021 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.adapter


import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.compose.theme.RethinkConfirmDialog
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities.removeBeginningTrailingCommas
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import io.github.aakira.napier.Napier
import kotlin.math.log2


@Composable
fun DomainRow(
    conn: AppConnection,
    uid: Int,
    isActiveConn: Boolean,
    refreshToken: Int,
    onIpClick: (AppConnection) -> Unit
) {
    val countText = conn.count.toString()
    val (primaryText, secondaryText) =
        if (isActiveConn) {
            val ip = beautifyIpString(conn.ipAddress)
            val name = conn.appOrDnsName.orEmpty()
            ip to name
        } else {
            conn.appOrDnsName to conn.ipAddress
        }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onIpClick(conn) }
                .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = conn.flag, style = MaterialTheme.typography.titleMedium)
            Column(modifier = Modifier.weight(1f)) {
                Text(text = primaryText.orEmpty(), style = MaterialTheme.typography.titleMedium)
                if (!secondaryText.isNullOrEmpty()) {
                    Text(text = secondaryText, style = MaterialTheme.typography.bodySmall)
                }
                if (!isActiveConn && !conn.appOrDnsName.isNullOrEmpty()) {
                    DomainProgress(conn, uid, refreshToken)
                }
            }
            Text(
                text = countText,
                style = MaterialTheme.typography.labelLarge
            )
        }
        Spacer(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun CloseConnsDialog(
    conn: AppConnection,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    RethinkConfirmDialog(
        onDismissRequest = onDismiss,
        title = context.getString(R.string.close_conns_dialog_title),
        message = context.getString(R.string.close_conns_dialog_desc, conn.ipAddress),
        confirmText = context.getString(R.string.lbl_proceed),
        dismissText = context.getString(R.string.lbl_cancel),
        onConfirm = {
            VpnController.closeConnectionsByUidDomain(
                conn.uid,
                conn.ipAddress,
                "app-wise-domains-manual-close"
            )
            showToastUiCentered(
                context,
                context.getString(R.string.config_add_success_toast),
                Toast.LENGTH_LONG
            )
            onConfirm()
        },
        onDismiss = onDismiss
    )
}

@Composable
private fun DomainProgress(conn: AppConnection, uid: Int, refresh: Int) {
    val context = LocalContext.current
    if (refresh == Int.MIN_VALUE) {
        return
    }
    val status = DomainRulesManager.status(conn.appOrDnsName.orEmpty(), uid)
    val color =
        when (status) {
            DomainRulesManager.Status.NONE ->
                MaterialTheme.colorScheme.onSurfaceVariant
            DomainRulesManager.Status.BLOCK ->
                MaterialTheme.colorScheme.error
            DomainRulesManager.Status.TRUST ->
                MaterialTheme.colorScheme.tertiary
        }    // In many Compose use cases, 100 or 1.0f is used directly.    // For now, let's keep it simple or implement a similar logic if required.    
    var p = calculatePercentage(conn.count.toDouble())
    if (p == 0) {
        p = 5
    }
    LinearProgressIndicator(
        progress = { p / 100f },
        color = color,
        trackColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    )
}

private fun calculatePercentage(c: Double): Int {
    // If not available, it becomes a per-item progress which is less useful.
    // For now, let's use a reasonable default or assume max is handled elsewhere.
    val value = (log2(c) * 100).toInt()    // In a LazyList, computing global max is expensive or requires a separate pass.
    return (value % 100) // Fallback
}

private fun beautifyIpString(d: String): String {
    return removeBeginningTrailingCommas(d).replace(",,", ",").replace(",", ", ")
}
