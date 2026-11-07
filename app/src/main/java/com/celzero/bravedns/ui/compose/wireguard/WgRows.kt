/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.celzero.bravedns.ui.compose.wireguard

import com.celzero.bravedns.ui.compose.controls.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R

@Composable private fun WgChips(group: WgControlState, source: String, vararg nodes: WgControlState) {
    if (!group.isVisible) return
    val ids = if (source == "list_item_wg_hop") listOf("chip_ipv4", "chip_ipv6", "chip_split_tunnel", "chip_amnezia", "chip_hop_src", "chip_hopping", "chip_properties")
        else listOf("protocol_info_chip_ipv4", "protocol_info_chip_ipv6", "chip_split_tunnel", "chip_amnezia", "chip_hop_src", "chip_hopping", "chip_properties")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        nodes.forEachIndexed { index, node -> if (node.isVisible) {
            com.celzero.bravedns.ui.compose.controls.OriginalLabel("$source:${ids[index]}", Modifier.alpha(node.alpha),
                selected = node.isChecked, contentColor = node.textColor?.let(::Color),
                containerColor = node.backgroundColor?.let(::Color), backgroundTint = node.backgroundTintList) {
                Text(wgAnnotatedText(node.text))
            }
        } }
    }
}

@Composable fun WgConfigRow(b: WgConfigRowState) {
    WgCard(b.interfaceDetailCard, contentPadding = PaddingValues(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { WgText(b.interfaceFlagText); WgText(b.interfaceNameText, Modifier.weight(1f)); WgText(b.interfaceIdText) }
                WgChips(b.protocolInfoChipGroup, "list_item_wg_general_interface", b.protocolInfoChipIpv4, b.protocolInfoChipIpv6, b.chipSplitTunnel, b.chipAmnezia, b.chipHopSrc, b.chipHopping, b.chipProperties)
                WgText(b.interfaceStatus)
                WgText(b.interfaceAppsCount)
                if (b.interfaceActiveLayout.isVisible) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    WgText(b.interfaceActiveUptime, Modifier.weight(1f).padding(start = 3.dp, end = 5.dp).padding(5.dp))
                    WgText(b.interfaceActiveRxTx, Modifier.padding(5.dp))
                }
            }
            WgCheck(b.interfaceSwitch, b.interfaceNameText.text.toString(), switch = true, compoundSource = "list_item_wg_general_interface:interface_switch")
        }
    }
}

@Composable fun WgOneConfigRow(b: WgOneConfigRowState) {
    WgCard(b.interfaceDetailCard, contentPadding = PaddingValues(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { WgText(b.interfaceFlagText); WgText(b.interfaceNameText, Modifier.weight(1f)); WgText(b.interfaceIdText) }
                WgChips(b.protocolInfoChipGroup, "list_item_wg_one_interface", b.protocolInfoChipIpv4, b.protocolInfoChipIpv6, b.chipSplitTunnel)
                WgText(b.interfaceStatus)
                WgText(b.interfaceAppsCount)
                if (b.interfaceActiveLayout.isVisible) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    WgText(b.interfaceActiveUptime, Modifier.weight(1f).padding(start = 3.dp, end = 5.dp).padding(5.dp))
                    WgText(b.interfaceActiveRxTx, Modifier.padding(5.dp))
                }
            }
            WgCheck(b.oneWgCheck, b.interfaceNameText.text.toString(), compoundSource = "list_item_wg_one_interface:one_wg_check")
        }
    }
}

@Composable fun WgAppRow(b: WgAppRowState) {
    WgCard(b.wgIncludeCard, Modifier.padding(horizontal = 6.dp),
        contentPadding = PaddingValues(start = 10.dp, top = 10.dp, end = 6.dp, bottom = 10.dp)) {
        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            WgIcon(b.wgIncludeAppListApkIconIv)
            Column(Modifier.weight(1f)) { WgText(b.wgIncludeAppListApkLabelTv); WgText(b.wgIncludeAppAppDescTv) }
            WgCheck(b.wgIncludeAppListCheckbox, b.wgIncludeAppListApkLabelTv.text.toString(), compoundSource = "list_item_wg_include_apps:wg_include_app_list_checkbox")
        }
    }
}

@Composable fun WgHopRow(b: WgHopRowState) {
    WgCard(b.wgHopListCard, contentPadding = PaddingValues(start = 5.dp, top = 7.dp, bottom = 7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WgText(b.wgHopListNameTv); WgText(b.wgHopListDescTv)
                WgChips(b.chipGroup, "list_item_wg_hop", b.chipIpv4, b.chipIpv6, b.chipSplitTunnel, b.chipAmnezia, b.chipHopSrc, b.chipHopping, b.chipProperties)
            }
            WgCheck(b.wgHopListCheckbox, b.wgHopListNameTv.text.toString(), compoundSource = "list_item_wg_hop:wg_hop_list_checkbox")
        }
    }
    WgProgress(b.wgHopListProgress, Modifier.fillMaxWidth().padding(horizontal = 10.dp))
}

@Composable fun WgPeerRow(b: WgPeerRowState) {
    WgCard(contentPadding = PaddingValues(start = 15.dp, top = 15.dp, end = 15.dp, bottom = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WgText(b.peerTitle, Modifier.weight(1f))
            WgIcon(b.peerEdit, stringResource(R.string.rt_edit_dialog_positive))
            WgIcon(b.peerDelete, stringResource(R.string.lbl_delete))
        }
        WgText(b.publicKeyLabel); WgText(b.publicKeyText)
        WgText(b.preSharedKeyLabel); WgText(b.preSharedKeyText)
        WgText(b.allowedIpsLabel); WgText(b.allowedIpsText)
        WgText(b.endpointLabel); WgText(b.endpointText)
        WgText(b.persistentKeepaliveLabel); WgText(b.persistentKeepaliveText)
    }
}

@Composable fun WgStatsRow(b: WgStatsRowState) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            WgIcon(b.ssIcon); WgText(b.ssFlag)
            Column(Modifier.weight(1f)) { WgText(b.ssName); WgText(b.ssDataUsage); WgProgress(b.ssProgress, Modifier.fillMaxWidth().padding(top = 6.dp)) }
            WgText(b.ssCount)
        }
    }
}
