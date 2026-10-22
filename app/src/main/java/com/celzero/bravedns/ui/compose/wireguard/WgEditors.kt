/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.wireguard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R

@Composable private fun WgEditorPair(first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
    if (LocalDensity.current.fontScale >= 1.5f) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { first(Modifier); second(Modifier) }
    else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { first(Modifier.weight(.7f)); second(Modifier.weight(.3f)) }
}

@Composable fun WgEditorContent(b: WgEditorState) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            WgText(b.interfaceTitle)
            WgField(b.interfaceNameText, b.interfaceNameLayout.hint)
            WgField(b.privateKeyText, b.privateKeyTextLayout.hint, trailing = {
                IconButton({ b.privateKeyTextLayout.onEndIconClick?.invoke() }) { Icon(painterResource(R.drawable.ic_refresh_white), stringResource(R.string.wg_compose_generate_key), tint = com.celzero.bravedns.ui.compose.themeColor(R.attr.colorOnSurfaceVariant)) }
            })
            Box {
                WgField(b.publicKeyText, b.publicKeyLabelLayout.hint, expandedHint = false, readOnly = true, maxLines = 1)
                Box(Modifier.matchParentSize().clickable { b.publicKeyText.performClick() })
            }
            WgEditorPair({ WgField(b.addressesLabelText, b.addressesLabelLayout.hint, it) }, { WgField(b.listenPortText, b.listenPortLabelLayout.hint, it, expandedHint = false, textAlign = androidx.compose.ui.text.style.TextAlign.Center, keyboard = KeyboardType.Number) })
            WgEditorPair({ WgField(b.dnsServersText, b.dnsServersLabelLayout.hint, it, expandedHint = false) }, { WgField(b.mtuText, b.mtuLabelLayout.hint, it, expandedHint = false, textAlign = androidx.compose.ui.text.style.TextAlign.Center, keyboard = KeyboardType.Number) })
            WgText(b.amzProps)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { WgAction(b.dismissBtn, source = "activity_wg_config_editor:dismiss_btn"); WgAction(b.saveTunnel, source = "activity_wg_config_editor:save_tunnel") }
        }
    }
}

@Composable fun WgPeerContent(b: WgPeerState) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            WgText(b.peerTitle)
            WgField(b.peerPublicKey, b.publicKeyLabelLayout.hint)
            WgField(b.peerPresharedKey, b.preSharedKeyLabelLayout.hint, expandedHint = false)
            WgField(b.peerPersistentKeepAlive, b.persistentKeepaliveLabelLayout.hint, expandedHint = false, keyboard = KeyboardType.Number)
            WgText(b.keepAliveHint)
            WgField(b.peerEndpoint, b.endpointLabelLayout.hint)
            WgField(b.peerAllowedIps, b.allowedIpsLabelLayout.hint, maxLines = 12)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                WgAction(b.customDialogDismissButton, source = "dialog_wg_add_peer:custom_dialog_dismiss_button")
                WgAction(b.customDialogOkButton, source = "dialog_wg_add_peer:custom_dialog_ok_button")
                if (b.customDialogOkProgress.isVisible) CircularProgressIndicator(Modifier.size(24.dp))
            }
        }
    }
}
