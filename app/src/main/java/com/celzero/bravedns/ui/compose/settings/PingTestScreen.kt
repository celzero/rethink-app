/*
 * Copyright 2024 RethinkDNS and its authors
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
package com.celzero.bravedns.ui.compose.settings

import androidx.compose.foundation.layout.Arrangement
import com.celzero.bravedns.ui.compose.theme.Dimensions
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.service.VpnController
import com.celzero.firestack.backend.Backend
import com.celzero.bravedns.ui.compose.theme.RethinkListGroup
import com.celzero.bravedns.ui.compose.theme.RethinkConfirmDialog
import com.celzero.bravedns.ui.compose.theme.RethinkLargeTopBar
import com.celzero.bravedns.ui.compose.theme.SectionHeader
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PING_IP1 = "1.1.1.1:53"
private const val PING_IP2 = "8.8.8.8:53"
private const val PING_IP3 = "216.239.32.27:443"
private const val PING_HOST1 = "cloudflare.com:443"
private const val PING_HOST2 = "google.com:443"
private const val PING_HOST3 = "brave.com:443"
private const val STRENGTH_MAX = 5
private const val TAG = "PingUi"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PingTestScreen(
    onBackClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var ip1 by remember { mutableStateOf(PING_IP1) }
    var ip2 by remember { mutableStateOf(PING_IP2) }
    var ip3 by remember { mutableStateOf(PING_IP3) }
    var host1 by remember { mutableStateOf(PING_HOST1) }
    var host2 by remember { mutableStateOf(PING_HOST2) }
    var host3 by remember { mutableStateOf(PING_HOST3) }

    var ip1Status by remember { mutableStateOf<PingStatus>(PingStatus.Idle) }
    var ip2Status by remember { mutableStateOf<PingStatus>(PingStatus.Idle) }
    var ip3Status by remember { mutableStateOf<PingStatus>(PingStatus.Idle) }
    var host1Status by remember { mutableStateOf<PingStatus>(PingStatus.Idle) }
    var host2Status by remember { mutableStateOf<PingStatus>(PingStatus.Idle) }
    var host3Status by remember { mutableStateOf<PingStatus>(PingStatus.Idle) }

    var strength by remember { mutableStateOf<Int?>(null) }
    val showStartVpnDialog = remember { !VpnController.hasTunnel() }

    // Cache for proxy status
    val proxiesStatus = remember { mutableListOf<Boolean>() }

    suspend fun getProxiesStatus(csv: String): List<Boolean> {
        if (proxiesStatus.isNotEmpty()) return proxiesStatus

        return withContext(Dispatchers.IO) {
            val warp = VpnController.isProxyReachable(Backend.RpnWin, csv)
            val amz = VpnController.isProxyReachable(Backend.RpnWin, csv)
            val win = VpnController.isProxyReachable(Backend.RpnWin, csv)
            val se = VpnController.isProxyReachable(Backend.RpnSE, csv)
            val w64 = VpnController.isProxyReachable(Backend.Rpn64, csv)
            Napier.d("$TAG proxies reachable: $warp, $amz $win, $se, $w64")

            val status = listOf(warp, amz, win, se, w64)
            proxiesStatus.clear()
            proxiesStatus.addAll(status)
            status
        }
    }

    suspend fun isReachable(csv: String): Boolean {
        val status = getProxiesStatus(csv)
        // Check if any proxy is reachable
        val reachable = status.any { it }
        Napier.d("$TAG ip $csv reachable: $reachable")
        return reachable
    }

    suspend fun calculateStrength(csv: String): Int {
        val status = getProxiesStatus(csv)
        // Count how many are true
        val strengthVal = status.count { it }
        Napier.i("$TAG strength: $strengthVal ($status)")
        return strengthVal
    }

    fun performPing() {
        scope.launch {
            try {
                proxiesStatus.clear() // Clear cache for new test
                Napier.v("$TAG initiating ping test")
                ip1Status = PingStatus.Loading
                ip2Status = PingStatus.Loading
                ip3Status = PingStatus.Loading
                host1Status = PingStatus.Loading
                host2Status = PingStatus.Loading
                host3Status = PingStatus.Loading
                strength = null

                val ip1Local = ip1
                val ip2Local = ip2
                val ip3Local = ip3
                val host1Local = host1
                val host2Local = host2
                val host3Local = host3

                // Run reachable checks sequentially in IO as per original
                val validI1 = isReachable(ip1Local)
                val validI2 = isReachable(ip2Local)
                val validI3 = isReachable(ip3Local)
                val validH1 = isReachable(host1Local)
                val validH2 = isReachable(host2Local)
                val validH3 = isReachable(host3Local)

                ip1Status = PingStatus.Result(validI1)
                ip2Status = PingStatus.Result(validI2)
                ip3Status = PingStatus.Result(validI3)
                host1Status = PingStatus.Result(validH1)
                host2Status = PingStatus.Result(validH2)
                host3Status = PingStatus.Result(validH3)

                val strengthValue = calculateStrength(ip3Local)
                strength = strengthValue.coerceIn(1, STRENGTH_MAX)
            } catch (e: Exception) {
                Napier.e("$TAG err isReachable: ${e.message}", e)
                ip1Status = PingStatus.Result(false)
                ip2Status = PingStatus.Result(false)
                ip3Status = PingStatus.Result(false)
                host1Status = PingStatus.Result(false)
                host2Status = PingStatus.Result(false)
                host3Status = PingStatus.Result(false)
            }
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            RethinkLargeTopBar(
                title = stringResource(R.string.settings_connectivity_checks),
                subtitle = stringResource(R.string.settings_connectivity_checks_desc),
                onBackClick = onBackClick,
                scrollBehavior = scrollBehavior
            )
        }
    ) { paddingValues ->
        if (showStartVpnDialog) {
            RethinkConfirmDialog(
                onDismissRequest = {},
                title = stringResource(R.string.vpn_not_active_dialog_title),
                message = stringResource(R.string.vpn_not_active_dialog_desc),
                confirmText = stringResource(R.string.lbl_dismiss),
                onConfirm = { onBackClick?.invoke() }
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(
                start = Dimensions.screenPaddingHorizontal,
                end = Dimensions.screenPaddingHorizontal,
                top = Dimensions.spacingSm,
                bottom = Dimensions.spacing3xl
            ),
            verticalArrangement = Arrangement.spacedBy(Dimensions.spacingLg)
        ) {
            item {
                SectionHeader(title = stringResource(R.string.ping_ip_port_title))
                RethinkListGroup {
                    Column(
                        modifier = Modifier.padding(
                            horizontal = Dimensions.cardPadding,
                            vertical = Dimensions.spacingSm
                        )
                    ) {
                        PingField(
                            value = ip1,
                            readOnly = true,
                            status = ip1Status
                        )
                        PingField(
                            value = ip2,
                            readOnly = true,
                            status = ip2Status
                        )
                        PingField(
                            value = ip3,
                            readOnly = false,
                            status = ip3Status,
                            onValueChange = { ip3 = it }
                        )
                    }
                }
            }

            item {
                SectionHeader(title = stringResource(R.string.ping_host_port_title))
                RethinkListGroup {
                    Column(
                        modifier = Modifier.padding(
                            horizontal = Dimensions.cardPadding,
                            vertical = Dimensions.spacingSm
                        )
                    ) {
                        PingField(
                            value = host1,
                            readOnly = true,
                            status = host1Status
                        )
                        PingField(
                            value = host2,
                            readOnly = true,
                            status = host2Status
                        )
                        PingField(
                            value = host3,
                            readOnly = false,
                            status = host3Status,
                            onValueChange = { host3 = it }
                        )
                    }
                }
            }

            item {
                RethinkListGroup {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Dimensions.cardPadding, vertical = Dimensions.spacingSm),
                        horizontalArrangement = Arrangement.spacedBy(Dimensions.spacingMd)
                    ) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { onBackClick?.invoke() }
                        ) {
                            Text(text = stringResource(R.string.lbl_cancel))
                        }
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = { performPing() }
                        ) {
                            Text(text = stringResource(R.string.lbl_test))
                        }
                    }
                }
            }

            strength?.let { value ->
                val progress = value.toFloat() / STRENGTH_MAX.toFloat()
                item {
                    SectionHeader(title = stringResource(R.string.ping_strength_title))
                    RethinkListGroup {
                        Column(
                            modifier = Modifier.padding(
                                horizontal = Dimensions.cardPadding,
                                vertical = Dimensions.spacingLg
                            ),
                            verticalArrangement = Arrangement.spacedBy(Dimensions.spacingMd)
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.two_argument,
                                    value.toString(),
                                    STRENGTH_MAX.toString()
                                ),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PingField(
    value: String,
    readOnly: Boolean,
    status: PingStatus,
    onValueChange: (String) -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            readOnly = readOnly,
            modifier = Modifier.weight(1f),
            singleLine = true
        )
        Spacer(modifier = Modifier.width(8.dp))
        when (status) {
            PingStatus.Loading -> {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }

            is PingStatus.Result -> {
                val icon =
                    if (status.success) R.drawable.ic_tick else R.drawable.ic_cross_accent
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            }

            PingStatus.Idle -> Unit
        }
    }
}

private sealed class PingStatus {
    data object Idle : PingStatus()
    data object Loading : PingStatus()
    data class Result(val success: Boolean) : PingStatus()
}
