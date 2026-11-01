/*
 * Copyright 2026 RethinkDNS and its authors
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
package com.celzero.bravedns.ui.activity

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.adapter.DecisionStepRow
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.service.ConnectionSimulator
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities.isAtleastQ
import inet.ipaddr.IPAddressString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class ConnectionCheckActivity : BaseActivity() {
    private val persistentState by inject<PersistentState>()
    private var apps by mutableStateOf<List<AppInfo>>(emptyList())
    private var loading by mutableStateOf(false)
    private var result by mutableStateOf<ConnectionSimulator.SimulationResult?>(null)
    private var simulationError by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)
        if (isAtleastQ()) {
            WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars =
                Themes.isActivityLightTheme(isDarkThemeOn(), persistentState.theme)
            window.isNavigationBarContrastEnforced = false
        }
        // Keep the upstream production guard even for explicit activity launches.
        if (!DEBUG) {
            finish()
            return
        }
        FirewallManager.getApplistObserver().observe(this) { values ->
            apps = values.distinctBy { it.uid }.sortedBy { it.appName.lowercase() }
        }
        setContent { RethinkTheme { ConnectionCheckContent() } }
    }

    private fun Context.isDarkThemeOn(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    @Composable
    private fun ConnectionCheckContent() {
        var domain by rememberSaveable { mutableStateOf("") }
        var ip by rememberSaveable { mutableStateOf("") }
        var selectedUid by rememberSaveable { mutableStateOf<Int?>(null) }
        var showApps by rememberSaveable { mutableStateOf(false) }
        var appSearch by rememberSaveable { mutableStateOf("") }
        var domainError by rememberSaveable { mutableStateOf(false) }
        var ipError by rememberSaveable { mutableStateOf(false) }
        val focus = LocalFocusManager.current
        val selectedLabel = apps.firstOrNull { it.uid == selectedUid }?.let {
            "${it.appName} (${it.packageName})"
        } ?: selectedUid?.let { "Unknown app (uid: $it)" } ?: UNKNOWN_APP_LABEL
        Surface(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { Text(stringResource(R.string.connection_check_title), style = MaterialTheme.typography.headlineSmall) }
                item { Text(stringResource(R.string.connection_check_subtitle), style = MaterialTheme.typography.bodySmall) }
                item {
                    OutlinedTextField(domain, { domain = it; domainError = false }, Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.connection_check_domain_hint)) }, singleLine = true,
                        isError = domainError, supportingText = { if (domainError) Text(stringResource(R.string.connection_check_err_domain)) })
                }
                item {
                    OutlinedTextField(ip, { ip = it; ipError = false }, Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.connection_check_ip_hint)) }, singleLine = true,
                        isError = ipError, supportingText = { if (ipError) Text(stringResource(R.string.connection_check_err_ip)) })
                }
                item {
                    OutlinedButton(onClick = { showApps = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("${stringResource(R.string.connection_check_app_hint)}: $selectedLabel")
                    }
                }
                item {
                    Button(onClick = {
                        domainError = domain.isBlank()
                        val address = ip.trim().ifEmpty { null }
                        ipError = address != null && runCatching { IPAddressString(address).address }.getOrNull() == null
                        if (!domainError && !ipError) {
                            focus.clearFocus()
                            runSimulation(ConnectionSimulator.SimulationInput(domain.trim(), address, selectedUid))
                        }
                    }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(if (loading) R.string.connection_check_running else R.string.connection_check_simulate))
                    }
                }
                if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                simulationError?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
                result?.let { current ->
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(painterResource(if (current.blocked) R.drawable.ic_cross_accent else R.drawable.ic_tick), null,
                                tint = themeColor(if (current.blocked) R.attr.accentBad else R.attr.accentGood))
                            Text(stringResource(if (current.blocked) R.string.connection_check_verdict_blocked else R.string.connection_check_verdict_allowed),
                                style = MaterialTheme.typography.titleLarge)
                        }
                    }
                    item { Text(current.detailLines.joinToString("\n") { "• $it" }) }
                    item { Text(stringResource(R.string.connection_check_config_heading), style = MaterialTheme.typography.titleMedium) }
                    item { Text(current.configLines.joinToString("\n") { "• $it" }) }
                    item { Text(stringResource(R.string.connection_check_steps_heading), style = MaterialTheme.typography.titleMedium) }
                    items(current.steps) { DecisionStepRow(it) }
                }
            }
        }
        if (showApps) AlertDialog(
            onDismissRequest = { showApps = false },
            title = { Text(stringResource(R.string.connection_check_app_hint)) },
            text = {
                Column {
                    OutlinedTextField(appSearch, { appSearch = it }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(stringResource(R.string.connection_check_app_hint)) })
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        item { Text(UNKNOWN_APP_LABEL, Modifier.fillMaxWidth().clickable { selectedUid = null; showApps = false }.padding(12.dp)) }
                        items(apps.filter { it.appName.contains(appSearch, true) || it.packageName.contains(appSearch, true) }, key = { it.uid }) { app ->
                            Text("${app.appName} (${app.packageName})", Modifier.fillMaxWidth().clickable {
                                selectedUid = app.uid; showApps = false
                            }.padding(12.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showApps = false }) { Text(stringResource(android.R.string.cancel)) } }
        )
    }

    private fun runSimulation(input: ConnectionSimulator.SimulationInput) {
        if (loading) return
        loading = true
        result = null
        simulationError = null
        lifecycleScope.launch {
            try {
                val computed = withContext(Dispatchers.IO) { ConnectionSimulator.simulate(input) }
                if (!isFinishing && !isDestroyed) result = computed
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!isFinishing && !isDestroyed) simulationError = error.localizedMessage ?: error.javaClass.simpleName
            } finally {
                loading = false
            }
        }
    }

    companion object {
        private const val UNKNOWN_APP_LABEL = "Unknown app (no uid)"
    }
}
