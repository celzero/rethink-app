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
package com.celzero.bravedns.ui.bottomsheet

import androidx.compose.runtime.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.themeColor
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.celzero.bravedns.R
import com.celzero.bravedns.data.RecentRoutedAppRow
import com.celzero.bravedns.database.ConnectionTrackerRepository
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.activity.NetworkLogsActivity
import com.celzero.bravedns.ui.activity.NetworkLogsActivity.Companion.RULES_SEARCH_ID_RPN
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.util.Locale

/**
 * Bottom sheet listing the most recently routed apps for a single RPN proxy
 * with their connection counts, and a per-app pill action to exclude the app
 * from all proxies (AppInfo.isProxyExcluded).
 */
class RecentRoutedAppsBottomSheet : BaseBottomSheetDialogFragment() {

    private var loading by mutableStateOf(true)
    private var apps by mutableStateOf<List<RecentRoutedAppRow>>(emptyList())

    private val persistentState by inject<PersistentState>()
    private val connTrackerRepository by inject<ConnectionTrackerRepository>()

    private var loadJob: Job? = null
    private val excludedUids = mutableStateMapOf<Int, Boolean>()

    companion object {
        const val TAG = "RecentRoutedAppsSheet"

        private const val APPS_LIMIT = 10
        private const val ARG_PROXY_ID = "proxyId"
        private const val ARG_SUBTITLE = "subtitle"

        fun newInstance(proxyId: String, subtitle: String?): RecentRoutedAppsBottomSheet =
            RecentRoutedAppsBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_PROXY_ID, proxyId)
                    putString(ARG_SUBTITLE, subtitle)
                }
            }
    }

    private fun isDarkThemeOn(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES

    override fun getTheme(): Int =
        Themes.getBottomSheetCurrentTheme(isDarkThemeOn(), persistentState.theme)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { RethinkTheme { RecentAppsContent() } }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dialog?.window?.let { window ->
            Themes.applyBottomSheetSystemBarAppearance(window, isDarkThemeOn(), persistentState.theme)
        }

        val proxyId = arguments?.getString(ARG_PROXY_ID).orEmpty()
        loadApps(proxyId)
    }

    override fun onDestroyView() {
        loadJob?.cancel()
        loadJob = null
        apps = emptyList()
        excludedUids.clear()
        super.onDestroyView()
    }

    private fun loadApps(proxyId: String) {
        loading = true
        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val (rows, excluded) = withContext(Dispatchers.IO) {
                try {
                    val result = connTrackerRepository.getRecentRoutedAppsWithCount(proxyId, APPS_LIMIT)
                    result to result.filter { FirewallManager.isAppExcludedFromProxy(it.uid) }.map { it.uid }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w(LOG_TAG_UI, "$TAG: getRecentRoutedAppsWithCount failed: ${e.message}")
                    emptyList<RecentRoutedAppRow>() to emptyList<Int>()
                }
            }
            if (!isAdded) return@launch
            excludedUids.clear()
            excluded.forEach { excludedUids[it] = true }
            apps = rows
            loading = false
        }
    }

    private fun openConnectionLogs(proxyId: String) {
        if (!isAdded || proxyId.isBlank()) return
        val intent = Intent(requireContext(), NetworkLogsActivity::class.java)
        intent.putExtra(Constants.SEARCH_QUERY, RULES_SEARCH_ID_RPN + proxyId)
        startActivity(intent)
    }

    private fun onExcludeToggled(item: RecentRoutedAppRow, excluded: Boolean) {
        if (excluded) excludedUids[item.uid] = true else excludedUids.remove(item.uid)
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            FirewallManager.updateIsProxyExcluded(item.uid, excluded)
        }
        Logger.i(LOG_TAG_UI, "$TAG: proxy-exclude ${item.appName}(${item.uid}) = $excluded")
        Utilities.showToastUiCentered(
            requireContext(),
            getString(
                if (excluded) R.string.recent_routed_app_excluded
                else R.string.recent_routed_app_included,
                item.appName
            ),
            Toast.LENGTH_SHORT
        )
    }

    @Composable
    private fun RecentAppsContent() {
        val context = androidx.compose.ui.platform.LocalContext.current
        LazyColumn(Modifier.fillMaxWidth().background(themeColor(R.attr.background)).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 20.dp)) {
            item { Text(stringResource(R.string.recent_routed_apps_title), style = MaterialTheme.typography.titleLarge) }
            val subtitle = arguments?.getString(ARG_SUBTITLE).orEmpty()
            if (subtitle.isNotBlank()) item { Text(subtitle) }
            if (loading) item { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            else {
                if (apps.isEmpty()) item { Text(stringResource(R.string.recent_routed_apps_empty)) }
                items(apps, key = { it.uid }) { item ->
                    val excluded = excludedUids[item.uid] == true
                    val icon by produceState<android.graphics.drawable.Drawable?>(null, item.packageName, item.appName) {
                        value = withContext(Dispatchers.IO) {
                            runCatching { if (item.packageName.isNotBlank()) Utilities.getIcon(context, item.packageName, item.appName) else null }.getOrNull()
                                ?: Utilities.getDefaultIcon(context)
                        }
                    }
                    val bitmap = remember(icon) { icon?.toBitmap(96, 96)?.asImageBitmap() }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        bitmap?.let { Image(it, null, Modifier.size(40.dp).alpha(if (excluded) .5f else 1f)) }
                        Column(Modifier.weight(1f)) {
                            Text(item.appName, Modifier.alpha(if (excluded) .5f else 1f), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.recent_routed_conn_count, String.format(Locale.US, "%,d", item.count)), style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { onExcludeToggled(item, !excluded) }) {
                            Text(stringResource(if (excluded) R.string.fapps_firewall_filter_excluded else R.string.exclude))
                        }
                    }
                }
                if (apps.isNotEmpty()) item { Text(stringResource(R.string.recent_routed_apps_hint), style = MaterialTheme.typography.bodySmall) }
                item { TextButton(onClick = { openConnectionLogs(arguments?.getString(ARG_PROXY_ID).orEmpty()) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.recent_routed_view_logs)) } }
            }
        }
    }
}
