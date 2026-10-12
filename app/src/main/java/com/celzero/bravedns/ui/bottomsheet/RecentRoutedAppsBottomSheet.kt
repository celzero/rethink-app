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

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.celzero.bravedns.R
import com.celzero.bravedns.data.RecentRoutedAppRow
import com.celzero.bravedns.database.ConnectionTrackerRepository
import com.celzero.bravedns.databinding.BottomsheetRecentRoutedAppsBinding
import com.celzero.bravedns.databinding.ListItemRecentRoutedAppBinding
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

    private var _binding: BottomsheetRecentRoutedAppsBinding? = null
    private val b
        get() = checkNotNull(_binding) { "Binding accessed outside of view lifecycle" }

    private val persistentState by inject<PersistentState>()
    private val connTrackerRepository by inject<ConnectionTrackerRepository>()

    private var loadJob: Job? = null
    private val excludedUids = mutableSetOf<Int>()

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
        _binding = BottomsheetRecentRoutedAppsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dialog?.window?.let { window ->
            Themes.applyBottomSheetSystemBarAppearance(window, isDarkThemeOn(), persistentState.theme)
        }

        val proxyId = arguments?.getString(ARG_PROXY_ID).orEmpty()
        b.recentRoutedAppsSubtitle.isVisible =
            !arguments?.getString(ARG_SUBTITLE).isNullOrBlank()
        b.recentRoutedAppsSubtitle.text = arguments?.getString(ARG_SUBTITLE).orEmpty()

        b.recentRoutedViewLogs.setOnClickListener { openConnectionLogs(proxyId) }

        loadApps(proxyId)
    }

    override fun onDestroyView() {
        loadJob?.cancel()
        loadJob = null
        _binding = null
        super.onDestroyView()
    }

    private fun loadApps(proxyId: String) {
        showLoadingState()

        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                try {
                    val rows = connTrackerRepository.getRecentRoutedAppsWithCount(proxyId, APPS_LIMIT)
                    excludedUids.addAll(rows.filter { FirewallManager.isAppExcludedFromProxy(it.uid) }.map { it.uid })
                    rows
                } catch (e: Exception) {
                    Logger.w(LOG_TAG_UI, "$TAG: getRecentRoutedAppsWithCount failed: ${e.message}")
                    emptyList()
                }
            }

            if (!isAdded) return@launch
            showContentState(apps.isNotEmpty())
            if (apps.isEmpty()) return@launch

            b.recentRoutedAppsList.layoutManager = LinearLayoutManager(requireContext())
            b.recentRoutedAppsList.adapter = AppsAdapter(apps)
            b.recentRoutedAppsList.itemAnimator = null
        }
    }

    private fun showLoadingState() {
        b.recentRoutedAppsProgress.isVisible = true
        b.recentRoutedAppsList.isVisible = false
        b.recentRoutedAppsEmpty.isVisible = false
        b.recentRoutedAppsHint.isVisible = false
        b.recentRoutedViewLogs.isVisible = false
    }

    private fun showContentState(hasApps: Boolean) {
        b.recentRoutedAppsProgress.isVisible = false
        b.recentRoutedAppsList.isVisible = hasApps
        b.recentRoutedAppsEmpty.isVisible = !hasApps
        b.recentRoutedAppsHint.isVisible = hasApps
        b.recentRoutedViewLogs.isVisible = true
    }

    private fun openConnectionLogs(proxyId: String) {
        if (!isAdded || proxyId.isBlank()) return
        val intent = Intent(requireContext(), NetworkLogsActivity::class.java)
        intent.putExtra(Constants.SEARCH_QUERY, RULES_SEARCH_ID_RPN + proxyId)
        startActivity(intent)
    }

    private fun onExcludeToggled(item: RecentRoutedAppRow, excluded: Boolean) {
        if (excluded) excludedUids.add(item.uid) else excludedUids.remove(item.uid)
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

    inner class AppsAdapter(private val items: List<RecentRoutedAppRow>) :
        RecyclerView.Adapter<AppsAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ListItemRecentRoutedAppBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val itemBinding = ListItemRecentRoutedAppBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            with(holder.binding) {
                recentRoutedAppName.text = item.appName

                recentRoutedAppConnCount.text = root.context.getString(
                    R.string.recent_routed_conn_count,
                    String.format(Locale.US, "%,d", item.count)
                )

                val iconDrawable = if (item.packageName.isNotBlank()) {
                    runCatching {
                        Utilities.getIcon(root.context, item.packageName, item.appName)
                    }.getOrNull()
                } else null
                Glide.with(root.context)
                    .load(iconDrawable)
                    .error(Utilities.getDefaultIcon(root.context))
                    .into(recentRoutedAppIcon)

                renderExcludeState(holder, excludedUids.contains(item.uid))
                recentRoutedAppExclude.setOnClickListener {
                    val newState = !excludedUids.contains(item.uid)
                    onExcludeToggled(item, newState)
                    renderExcludeState(holder, newState)
                }
            }
        }

        private fun renderExcludeState(holder: ViewHolder, excluded: Boolean) {
            val ctx = holder.binding.root.context
            with(holder.binding) {
                val btn = recentRoutedAppExclude
                btn.text = ctx.getString(
                    if (excluded) R.string.fapps_firewall_filter_excluded
                    else R.string.exclude
                )
                btn.contentDescription = btn.text
                val contentAlpha = if (excluded) 0.5f else 1f
                recentRoutedAppName.alpha = contentAlpha
                recentRoutedAppIcon.alpha = contentAlpha
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
