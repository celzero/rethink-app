/*
 * Copyright 2025 RethinkDNS and its authors
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


import Logger
import Logger.LOG_TAG_UI
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.service.ProxyManager.ID_WG_BASE
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.wireguard.Config
import com.celzero.bravedns.wireguard.WgHopManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "HopAdapter"

@Composable
fun HopRow(
    context: Context,
    srcId: Int,
    config: Config,
    isActive: Boolean,
    selectedId: Int,
    onSelectedIdChange: (Int) -> Unit
) {
    var isChecked by remember { mutableStateOf(config.getId() == selectedId) }
    var inProgress by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var chips by remember { mutableStateOf(HopChips()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(config.getId(), selectedId) {
        isChecked = config.getId() == selectedId
    }

    LaunchedEffect(config.getId(), selectedId) {
        statusText = computeStatusText(context, srcId, config, selectedId)
        chips = computeChips(context, config)
    }

    val strokeColor =
        if (isChecked && isActive) {
            MaterialTheme.colorScheme.tertiary
        } else if (isChecked) {
            MaterialTheme.colorScheme.error
        } else {
            Color.Transparent
        }
    val strokeWidth = if (isChecked) 2.dp else 0.dp

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .clickable(enabled = !inProgress) {
                    scope.launch {
                        inProgress = true
                        val targetChecked = !isChecked
                        val res =
                            handleHop(
                                context = context,
                                srcId = srcId,
                                config = config,
                                isChecked = targetChecked,
                                isActive = isActive,
                                selectedId = selectedId,
                                onSelectedIdChange = onSelectedIdChange
                            )
                        if (res.first) {
                            isChecked = targetChecked
                            statusText = computeStatusText(context, srcId, config, selectedId)
                        } else {
                            isChecked = false
                        }
                        inProgress = false
                    }
                },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = if (strokeWidth > 0.dp) BorderStroke(strokeWidth, strokeColor) else null
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = config.getName() + " (" + config.getId() + ")",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(text = statusText, style = MaterialTheme.typography.bodySmall)
                }
                Checkbox(
                    checked = isChecked,
                    onCheckedChange = { checked ->
                        if (inProgress) return@Checkbox
                        scope.launch {
                            inProgress = true
                            val res =
                                handleHop(
                                    context = context,
                                    srcId = srcId,
                                    config = config,
                                    isChecked = checked,
                                    isActive = isActive,
                                    selectedId = selectedId,
                                    onSelectedIdChange = onSelectedIdChange
                                )
                            if (res.first) {
                                isChecked = checked
                                statusText = computeStatusText(context, srcId, config, selectedId)
                            } else {
                                isChecked = false
                            }
                            inProgress = false
                        }
                    }
                )
            }

            if (chips.hasAny()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    if (chips.ipv4) HopChip(text = context.getString(R.string.settings_ip_text_ipv4))
                    if (chips.ipv6) HopChip(text = context.getString(R.string.settings_ip_text_ipv6))
                    if (chips.splitTunnel) HopChip(text = context.getString(R.string.lbl_split))
                    if (chips.amnezia) HopChip(text = context.getString(R.string.lbl_amnezia))
                    if (chips.hopSrc) HopChip(text = context.getString(R.string.lbl_hopping))
                    if (chips.hopping) HopChip(text = context.getString(R.string.cd_dns_crypt_relay_heading))
                    if (chips.properties.isNotEmpty()) HopChip(text = chips.properties)
                }
            }

            if (inProgress) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun HopChip(text: String) {
    AssistChip(onClick = {}, label = { Text(text = text) })
}

private data class HopChips(
    val ipv4: Boolean = false,
    val ipv6: Boolean = false,
    val splitTunnel: Boolean = false,
    val amnezia: Boolean = false,
    val hopSrc: Boolean = false,
    val hopping: Boolean = false,
    val properties: String = ""
) {
    fun hasAny(): Boolean {
        return ipv4 || ipv6 || splitTunnel || amnezia || hopSrc || hopping || properties.isNotEmpty()
    }
}

private suspend fun computeStatusText(
    context: Context,
    srcId: Int,
    config: Config,
    selectedId: Int
): String {
    val map = WireguardManager.getConfigFilesById(config.getId())
    if (map == null) return context.getString(R.string.config_invalid_desc)
    if (selectedId == config.getId()) {
        val srcConfig = WireguardManager.getConfigById(srcId)
        if (srcConfig == null) return context.getString(R.string.lbl_inactive)
        val src = ID_WG_BASE + srcConfig.getId()
        val hop = ID_WG_BASE + config.getId()
        val statusPair = VpnController.hopStatus(src, hop)
        return if (statusPair.first != null) {
            context.getString(UIUtils.getProxyStatusStringRes(statusPair.first))
        } else {
            statusPair.second
        }
    }
    return if (map.isActive) context.getString(R.string.lbl_active) else context.getString(R.string.lbl_inactive)
}

private suspend fun computeChips(context: Context, config: Config): HopChips {
    return withContext(Dispatchers.IO) {
        val id = ID_WG_BASE + config.getId()
        val pair = VpnController.getSupportedIpVersion(id)
        val isSplitTunnel =
            if (config.getPeers()?.isNotEmpty() == true) {
                VpnController.isSplitTunnelProxy(id, pair)
            } else {
                false
            }
        val hopSrc = WgHopManager.getMapBySrc(id).isNotEmpty()
        val hopping = WgHopManager.isAlreadyHop(id)
        val properties = buildString {
            val mapping = WireguardManager.getConfigFilesById(config.getId())
            if (mapping != null) {
                if (mapping.isCatchAll) append(context.getString(R.string.symbol_lightening))
                if (mapping.useOnlyOnMetered) append(context.getString(R.string.symbol_mobile))
                if (mapping.ssidEnabled) append(context.getString(R.string.symbol_id))
            }
        }
        val amnezia = config.getInterface()?.isAmnezia() == true
        HopChips(
            ipv4 = pair.first,
            ipv6 = pair.second,
            splitTunnel = isSplitTunnel,
            amnezia = amnezia,
            hopSrc = hopSrc,
            hopping = hopping,
            properties = properties
        )
    }
}

private suspend fun handleHop(
    context: Context,
    srcId: Int,
    config: Config,
    isChecked: Boolean,
    isActive: Boolean,
    selectedId: Int,
    onSelectedIdChange: (Int) -> Unit
): Pair<Boolean, String> {
    val srcConfig = WireguardManager.getConfigById(srcId)
    val mapping = WireguardManager.getConfigFilesById(config.getId())
    if (srcConfig == null || mapping == null) {
        Logger.i(LOG_TAG_UI, "$TAG; source config($srcId) not found to hop")
        uiCtx { Utilities.showToastUiCentered(context, context.getString(R.string.config_invalid_desc), Toast.LENGTH_LONG) }
        return false to context.getString(R.string.config_invalid_desc)
    }

    if (mapping.useOnlyOnMetered || mapping.ssidEnabled) {
        uiCtx {
            Utilities.showToastUiCentered(
                context,
                context.getString(R.string.hop_error_toast_msg_3),
                Toast.LENGTH_LONG
            )
        }
        return false to context.getString(R.string.hop_error_toast_msg_3)
    }

    Logger.d(LOG_TAG_UI, "$TAG; init, hop: ${srcConfig.getId()} -> ${config.getId()}, isChecked? $isChecked")
    val src = ID_WG_BASE + srcConfig.getId()
    val hop = ID_WG_BASE + config.getId()
    val currMap = WgHopManager.getMapBySrc(src)
    if (currMap.isNotEmpty()) {
        var res = false
        currMap.forEach {
            if (it.hop != hop && it.hop.isNotEmpty()) {
                val id = it.hop.substring(ID_WG_BASE.length).toIntOrNull() ?: return@forEach
                res = WgHopManager.removeHop(srcConfig.getId(), id).first
            }
        }
        if (res) {
            onSelectedIdChange(-1)
        }
    }
    delay(2000)
    if (isChecked) {
        val hopTestRes = VpnController.testHop(src, hop)
        if (!hopTestRes.first) {
            uiCtx {
                Utilities.showToastUiCentered(
                    context,
                    hopTestRes.second ?: context.getString(R.string.unknown_error),
                    Toast.LENGTH_LONG
                )
            }
            return false to (hopTestRes.second ?: context.getString(R.string.unknown_error))
        }
    }

    val res = if (!isChecked) {
        onSelectedIdChange(-1)
        WgHopManager.removeHop(srcConfig.getId(), config.getId())
    } else {
        onSelectedIdChange(config.getId())
        WgHopManager.hop(srcConfig.getId(), config.getId())
    }
    uiCtx {
        Utilities.showToastUiCentered(context, res.second, Toast.LENGTH_LONG)
    }
    return res
}

private suspend fun uiCtx(f: suspend () -> Unit) {
    withContext(Dispatchers.Main) { f() }
}
