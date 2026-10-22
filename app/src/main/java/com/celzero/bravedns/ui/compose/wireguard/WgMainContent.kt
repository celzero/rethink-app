/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.wireguard

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.ui.compose.utilities.utilityImage
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R

@Composable fun WgToggle(node: WgControlState, modifier: Modifier = Modifier, source: String, selected: Boolean = node.isChecked) {
    if (!node.isVisible) return
    com.celzero.bravedns.ui.compose.controls.OriginalButton(source, { node.performClick() }, modifier,
        enabled = node.isEnabled && node.isClickable, selected = selected,
        contentColor = node.textColor?.let(::Color), backgroundTint = node.backgroundTintList,
        segmentCount = if (source.startsWith("activity_wireguard_main:")) 2 else 3,
        segmentIndex = when (source.substringAfter(':')) { "one_wg_toggle_btn", "tb_recent_toggle_btn" -> 0; "wg_general_toggle_btn", "tb_daily_toggle_btn" -> 1; else -> 2 }) {
        Text(node.text.toString())
    }
}

@Composable fun WgMainContent(b: WgMainState) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy((-1).dp, Alignment.CenterHorizontally)) { WgToggle(b.oneWgToggleBtn, source = "activity_wireguard_main:one_wg_toggle_btn", selected = b.oneWgInterfaceList.isVisible); WgToggle(b.wgGeneralToggleBtn, source = "activity_wireguard_main:wg_general_toggle_btn", selected = b.wgGeneralInterfaceList.isVisible) }
                WgText(b.wgWireguardDisclaimer, Modifier.padding(horizontal = 16.dp))
                if (b.wgGeneralInterfaceList.isVisible) b.wgGeneralInterfaceList.adapter?.Content(Modifier.weight(1f))
                if (b.oneWgInterfaceList.isVisible) b.oneWgInterfaceList.adapter?.Content(Modifier.weight(1f))
            }
            if (b.wgEmptyView.isVisible) Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                WgText(b.wgNoConfigText)
                Image(painterResource(R.drawable.illustrations_no_record), null, Modifier.sizeIn(maxWidth = 280.dp, maxHeight = 280.dp))
            }
            Column(Modifier.align(Alignment.BottomEnd).padding(16.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(b.deleteAllFab, b.qrCodeFab, b.importFab, b.createFab).forEach { node ->
                    if (node.isVisible) ExtendedFloatingActionButton(
                        onClick = { node.performClick() },
                        containerColor = if (node === b.deleteAllFab) colorResource(R.color.accentBadLight) else themeColor(R.attr.chipColorBgNormal),
                        contentColor = themeColor(R.attr.primaryTextColor),
                        icon = {
                            // XML uses transparent ADD tint, which preserves the drawable's own colors.
                            utilityImage(when (node) { b.deleteAllFab -> android.R.drawable.ic_menu_delete; b.qrCodeFab -> R.drawable.ic_qr_code_scanner; b.importFab -> R.drawable.ic_import_conf; else -> R.drawable.ic_add }, Modifier.size(24.dp))
                        },
                        text = { Text(node.text.toString()) })
                }
                FloatingActionButton({ b.wgAddFab.performClick() }, Modifier.testTag("wg_add_fab")) {
                    Icon(painterResource(R.drawable.ic_fab_without_border), stringResource(R.string.lbl_create), Modifier.size(24.dp))
                }
            }
        }
    }
}
