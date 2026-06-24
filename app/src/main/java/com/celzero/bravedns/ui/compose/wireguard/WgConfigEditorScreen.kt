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
package com.celzero.bravedns.ui.compose.wireguard

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.Keep
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.util.UIUtils.clipboardCopy
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.tos
import com.celzero.bravedns.ui.compose.theme.Dimensions
import com.celzero.bravedns.ui.compose.theme.PrimaryButton
import com.celzero.bravedns.ui.compose.theme.RethinkLargeTopBar
import com.celzero.bravedns.ui.compose.theme.SecondaryButton
import com.celzero.bravedns.wireguard.WgInterface
import com.celzero.bravedns.wireguard.util.ErrorMessages
import com.celzero.firestack.backend.Backend
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val CLIPBOARD_PUBLIC_KEY_LBL = "Public Key"
private const val DEFAULT_MTU = "-1"

// when dns is set to auto, the default dns is set to 1.1.1.1. this differs from official
// wireguard for android, because rethink requires a dns to be set in "Simple" mode
private const val DEFAULT_DNS = "1.1.1.1"
private const val DEFAULT_LISTEN_PORT = "0"

@Keep
enum class WgType(val value: Int) {
    DEFAULT(0),
    ONE_WG(1);

    fun isOneWg() = this == ONE_WG

    fun isDefault() = this == DEFAULT

    companion object {
        fun fromInt(value: Int): WgType {
            return entries.firstOrNull { it.value == value } ?: DEFAULT
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WgConfigEditorScreen(
    configId: Int,
    wgType: WgType,
    persistentState: PersistentState,
    onBackClick: () -> Unit,
    onSaveSuccess: () -> Unit
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val scope = rememberCoroutineScope()
    val publicKeyCopyToast = stringResource(R.string.public_key_copy_toast_msg)
    val configAddSuccessToast = stringResource(R.string.config_add_success_toast)

    var interfaceName by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf("") }
    var publicKey by remember { mutableStateOf("") }
    var addresses by remember { mutableStateOf("") }
    var listenPort by remember { mutableStateOf("") }
    var dnsServers by remember { mutableStateOf("") }
    var mtu by remember { mutableStateOf("") }
    var amzProps by remember { mutableStateOf("") }
    var showListenPortState by remember { mutableStateOf(false) }

    fun showListenPort(wgIface: WgInterface?): Boolean {
        val isPresent =
            wgIface?.listenPort?.isPresent == true && wgIface.listenPort.get() != 1
        val byType = wgType.isOneWg() || (!persistentState.randomizeListenPort && wgType.isDefault())
        return isPresent && byType
    }

    // Load config on first composition
    LaunchedEffect(configId) {
        withContext(Dispatchers.IO) {
            val cfg = WireguardManager.getConfigById(configId)
            val iface = cfg?.getInterface()

            withContext(Dispatchers.Main) {
                interfaceName = cfg?.getName().orEmpty()
                privateKey = iface?.getKeyPair()?.getPrivateKey()?.base64()?.tos().orEmpty()
                publicKey = iface?.getKeyPair()?.getPublicKey()?.base64()?.tos().orEmpty()

                var dns = iface?.dnsServers?.joinToString { it.hostAddress ?: "" }
                val searchDomains = iface?.dnsSearchDomains?.joinToString { it }
                dns = if (!searchDomains.isNullOrEmpty()) {
                    "$dns,$searchDomains"
                } else {
                    dns
                }
                dnsServers = dns.orEmpty()

                addresses = if (iface?.getAddresses()?.isEmpty() != true) {
                    iface?.getAddresses()?.joinToString { it.toString() }.orEmpty()
                } else {
                    ""
                }

                showListenPortState = showListenPort(iface)
                listenPort = if (showListenPortState) {
                    iface?.listenPort?.get()?.toString().orEmpty()
                } else {
                    ""
                }

                mtu = if (iface?.mtu?.isPresent == true) {
                    iface.mtu.get().toString()
                } else {
                    ""
                }

                amzProps = if (iface?.isAmnezia() == true) {
                    iface.getAmzProps().orEmpty()
                } else {
                    ""
                }
            }
        }
    }

    fun generateKeys() {
        val key = Backend.newWgPrivateKey()
        privateKey = key.base64().toString()
        publicKey = key.mult().base64().toString()
    }

    fun copyPublicKey() {
        clipboardCopy(context, publicKey, CLIPBOARD_PUBLIC_KEY_LBL)
        Utilities.showToastUiCentered(
            context,
            publicKeyCopyToast,
            Toast.LENGTH_SHORT
        )
    }

    fun saveConfig() {
        val name = interfaceName
        val addr = addresses
        val mtuValue = mtu.ifEmpty { DEFAULT_MTU }
        val listenPortValue = listenPort.ifEmpty { DEFAULT_LISTEN_PORT }
        val dns = dnsServers.ifEmpty { DEFAULT_DNS }
        val privateKeyValue = privateKey

        scope.launch(Dispatchers.IO) {
            try {
                val newWgInterface = WgInterface.Builder()
                    .parsePrivateKey(privateKeyValue)
                    .parseAddresses(addr)
                    .parseListenPort(listenPortValue)
                    .parseDnsServers(dns)
                    .parseMtu(mtuValue)
                    .build()

                val result = WireguardManager.addOrUpdateInterface(configId, name, newWgInterface)
                if (result != null) {
                    withContext(Dispatchers.Main) {
                        Utilities.showToastUiCentered(
                            context,
                            configAddSuccessToast,
                            Toast.LENGTH_LONG
                        )
                        onSaveSuccess()
                    }
                }
            } catch (e: Throwable) {
                val error = ErrorMessages[context, e]
                Napier.e("err while parsing wg interface: $error", e)
                withContext(Dispatchers.Main) {
                    Utilities.showToastUiCentered(context, error, Toast.LENGTH_LONG)
                }
            }
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val density = LocalDensity.current
    val editorFieldShape = RoundedCornerShape(Dimensions.cornerRadiusLg)
    val imeBottomInset = with(density) { WindowInsets.ime.getBottom(density).toDp() }
    val navBottomInset = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    val actionBarBottomInset = when {
        imeBottomInset > 0.dp -> imeBottomInset
        navBottomInset > 0.dp -> navBottomInset
        else -> 48.dp
    }
    DisposableEffect(activity) {
        val window = activity?.window
        val previousSoftInputMode = window?.attributes?.softInputMode
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        onDispose {
            if (previousSoftInputMode != null) {
                window.setSoftInputMode(previousSoftInputMode)
            }
        }
    }

    Scaffold(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            RethinkLargeTopBar(
                title = stringResource(R.string.lbl_configure),
                onBackClick = onBackClick,
                scrollBehavior = scrollBehavior
            )
        },
        bottomBar = {
            EditorActionsBar(
                bottomInset = actionBarBottomInset,
                onCancelClick = onBackClick,
                onSaveClick = { saveConfig() }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = Dimensions.screenPaddingHorizontal,
                end = Dimensions.screenPaddingHorizontal,
                top = Dimensions.spacingSm,
                bottom = Dimensions.spacingXl
            ),
            verticalArrangement = Arrangement.spacedBy(Dimensions.spacingMd)
        ) {
            item {
                EditorSection(
                    title = stringResource(R.string.lbl_configure)
                ) {
                    OutlinedTextField(
                        value = interfaceName,
                        onValueChange = { interfaceName = it },
                        label = { Text(stringResource(R.string.cd_dns_crypt_dialog_name)) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = editorFieldShape,
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = addresses,
                        onValueChange = { addresses = it },
                        label = { Text(stringResource(R.string.lbl_addresses)) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = editorFieldShape,
                        minLines = 2
                    )

                    OutlinedTextField(
                        value = dnsServers,
                        onValueChange = { dnsServers = it },
                        label = { Text(stringResource(R.string.lbl_dns_servers)) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = editorFieldShape,
                        minLines = 2
                    )
                }
            }
            item { EditorSectionDivider() }

            item {
                EditorSection(
                    title = stringResource(R.string.setup_wireguard)
                ) {
                    OutlinedTextField(
                        value = privateKey,
                        onValueChange = { privateKey = it },
                        label = { Text(stringResource(R.string.lbl_private_key)) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = editorFieldShape,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        trailingIcon = {
                            IconButton(onClick = { generateKeys() }) {
                                Icon(
                                    imageVector = Icons.Filled.Refresh,
                                    contentDescription = stringResource(id = R.string.cd_generate_keys)
                                )
                            }
                        }
                    )

                    OutlinedTextField(
                        value = publicKey,
                        onValueChange = { },
                        label = { Text(stringResource(R.string.lbl_public_key)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = publicKey.isNotEmpty()) {
                                copyPublicKey()
                            },
                        shape = editorFieldShape,
                        readOnly = true,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        trailingIcon = {
                            IconButton(onClick = { copyPublicKey() }, enabled = publicKey.isNotEmpty()) {
                                Icon(
                                    imageVector = Icons.Filled.ContentCopy,
                                    contentDescription = stringResource(id = R.string.cd_copy_public_key)
                                )
                            }
                        }
                    )
                }
            }
            item { EditorSectionDivider() }

            item {
                EditorSection(
                    title = stringResource(R.string.lbl_network)
                ) {
                    if (showListenPortState) {
                        OutlinedTextField(
                            value = listenPort,
                            onValueChange = { listenPort = it },
                            label = { Text(stringResource(R.string.lbl_listen_port)) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = editorFieldShape,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }

                    OutlinedTextField(
                        value = mtu,
                        onValueChange = { mtu = it },
                        label = { Text(stringResource(R.string.lbl_mtu)) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = editorFieldShape,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            }

            if (amzProps.isNotEmpty()) {
                item { EditorSectionDivider() }
                item {
                    EditorSection(
                        title = stringResource(R.string.lbl_advanced)
                    ) {
                        Text(
                            text = amzProps,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

@Composable
private fun EditorSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimensions.spacingMd)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        content()
    }
}

@Composable
private fun EditorSectionDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    )
}

@Composable
private fun EditorActionsBar(
    bottomInset: Dp,
    onCancelClick: () -> Unit,
    onSaveClick: () -> Unit
) {
    val actionBottomPadding = Dimensions.spacingSm + bottomInset

    Surface(
        modifier = Modifier
            .fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
        shadowElevation = 8.dp
    ) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = Dimensions.screenPaddingHorizontal,
                        end = Dimensions.screenPaddingHorizontal,
                        top = Dimensions.spacingSm,
                        bottom = actionBottomPadding
                    ),
                horizontalArrangement = Arrangement.spacedBy(Dimensions.spacingMd)
            ) {
                SecondaryButton(
                    text = stringResource(R.string.lbl_cancel),
                    onClick = onCancelClick,
                    modifier = Modifier.weight(1f)
                )
                PrimaryButton(
                    text = stringResource(R.string.lbl_save),
                    onClick = onSaveClick,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
