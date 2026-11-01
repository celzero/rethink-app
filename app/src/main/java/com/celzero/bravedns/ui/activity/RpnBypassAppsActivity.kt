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
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.widget.SearchView
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.celzero.bravedns.R
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.AppInfoRepository
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.util.AndroidUidConfig
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastO
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import com.celzero.bravedns.ui.compose.wireguard.WgAlertDialogBuilder as MaterialAlertDialogBuilder
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.rpnconfig.RpnBypassState
import com.celzero.bravedns.ui.compose.rpnconfig.RpnBypassContent
import com.celzero.bravedns.ui.compose.rpnconfig.RpnBypassAppRow
import androidx.compose.runtime.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

/**
 * Lists all tracked apps and lets the user mark them as **excluded from RPN proxies**
 * (bypass). Toggling an app calls [FirewallManager.updateIsProxyExcluded]; excluded
 * apps skip Rethink Proxy servers and use the direct connection.
 *
 * UI mirrors [WgIncludeAppsActivity]: search bar, All / Bypassed / Not bypassed chips
 * and bulk select / deselect actions.
 */
class RpnBypassAppsActivity : BaseActivity(),
    SearchView.OnQueryTextListener {

    private val b by lazy { RpnBypassState(this) }
    private val persistentState by inject<PersistentState>()
    private val appInfoRepository by inject<AppInfoRepository>()

    private lateinit var adapter: BypassAppsAdapter

    /** All tracked apps, loaded once from the DB. */
    private val allApps = mutableListOf<AppInfo>()

    /** Subset of [allApps] matching the current chip filter + search query. */
    private val filteredApps = mutableListOf<AppInfo>()

    private var filterType by mutableStateOf(Filter.ALL_APPS)
    private var searchText by mutableStateOf("")

    /** true while a bulk bypass/clear is being persisted; guards against re-entry. */
    @Volatile
    private var bulkOpInProgress = false

    private var loadJob: Job? = null


    private enum class Filter(val id: Int) {
        ALL_APPS(0), BYPASSED(1), NOT_BYPASSED(2)
    }

    companion object {
        private const val TAG = "RpnBypassAppsActivity"
        private const val REFRESH_TIMEOUT: Long = 4000
        private const val ANIMATION_DURATION = 750L
        private const val ANIMATION_REPEAT_COUNT = -1
        private const val ANIMATION_PIVOT_VALUE = 0.5f
        private const val ANIMATION_START_DEGREE = 0.0f
        private const val ANIMATION_END_DEGREE = 360.0f
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)
        b.root.applySystemBarEdgeInsets()

        handleFrostEffectIfNeeded(persistentState.theme)

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars =
                Themes.isActivityLightTheme(isDarkThemeOn(), persistentState.theme)
            window.isNavigationBarContrastEnforced = false
        }

        adapter = BypassAppsAdapter()
        setContentView(ComposeView(this).apply {
            id = android.R.id.primary
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                RethinkTheme {
                    RpnBypassContent(b, searchText, filterType.id,
                        search = { onQueryTextChange(it) }, submit = { onQueryTextSubmit(searchText) },
                        filter = { id -> filterType = Filter.entries.first { it.id == id }; applyFilter() },
                        apps = { modifier -> adapter.Content(modifier) })
                }
            }
        })
        setupClickListeners()
        loadApps()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("rpn.bypass.query", searchText)
        // Generated-ID chips and controller filter were not persisted by the baseline.
        outState.putBoolean("rpn.bypass.bulk", b.rpnBypassBulkCheck.isChecked)
        outState.putBoolean("rpn.bypass.none", b.rpnBypassDeselectAllCheck.isChecked)
        super.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        val restoredQuery = savedInstanceState.getString("rpn.bypass.query", "").orEmpty()
        // SearchAutoComplete restored before bulk controls; suppress unchanged query events.
        if (restoredQuery != searchText) onQueryTextChange(restoredQuery)
        b.rpnBypassBulkCheck.isChecked = savedInstanceState.getBoolean("rpn.bypass.bulk")
        b.rpnBypassDeselectAllCheck.isChecked = savedInstanceState.getBoolean("rpn.bypass.none")
    }

    override fun onDestroy() {
        loadJob?.cancel()
        loadJob = null
        super.onDestroy()
    }

    private fun setupClickListeners() {
        b.rpnBypassBulkCheck.setOnClickListener {
            if (bulkOpInProgress) return@setOnClickListener
            confirmBulkAction(include = true)
        }
        b.rpnBypassDeselectAllCheck.setOnClickListener {
            if (bulkOpInProgress) return@setOnClickListener
            confirmBulkAction(include = false)
        }

        b.rpnBypassRefreshList.setOnClickListener {
            b.rpnBypassRefreshList.isEnabled = false
            b.startRefresh()
            loadApps()
            Utilities.delay(REFRESH_TIMEOUT, lifecycleScope) {
                if (!this.isFinishing && !this.isDestroyed) {
                    b.rpnBypassRefreshList.isEnabled = true
                    b.refreshSpinning = false
                }
            }
        }
    }

    private fun loadApps() {
        loadJob?.cancel()
        loadJob = io {
            val apps = try {
                    appInfoRepository.getAppInfo()
                } catch (e: Exception) {
                    Logger.w(LOG_TAG_UI, "$TAG.loadApps failed: ${e.message}")
                    emptyList()
                }
            uiCtx {
                allApps.clear()
                allApps.addAll(
                    apps.sortedWith(
                        compareBy(
                            { categoryRank(it) },
                            { appTypeRank(it) },
                            { it.appName.lowercase() }
                        )
                    )
                )
                b.rpnBypassRefreshList.isEnabled = true
                applyFilter()
            }
        }
    }

    /**
     * Groups apps for display order: user-installed apps first, then system
     * components (system UIDs outside the app range), then system apps.
     * Matches the classification used by RefreshDatabase.
     */
    private fun appTypeRank(app: AppInfo): Int {
        return when {
            !app.isSystemApp -> 0
            !AndroidUidConfig.isUidAppRange(app.uid) -> 1
            else -> 2
        }
    }

    /**
     * Localized titles of the priority categories, in display order: video,
     * audio, game, maps, social, image. All other categories ("the rest")
     * rank after these. Titles are resolved through [ApplicationInfo.getCategoryTitle]
     */
    private val prioritizedCategoryTitles: List<String> by lazy {
        if (isAtleastO()) {
            listOf(
                ApplicationInfo.CATEGORY_VIDEO,
                ApplicationInfo.CATEGORY_AUDIO,
                ApplicationInfo.CATEGORY_GAME,
                ApplicationInfo.CATEGORY_MAPS,
                ApplicationInfo.CATEGORY_SOCIAL,
                ApplicationInfo.CATEGORY_IMAGE
            ).map {
                ApplicationInfo.getCategoryTitle(this, it)?.toString()?.replace("_", " ").orEmpty()
            }
        } else {
            emptyList()
        }
    }

    private fun categoryRank(app: AppInfo): Int {
        val cat = app.appCategory.trim()
        val idx = prioritizedCategoryTitles.indexOfFirst { it.equals(cat, ignoreCase = true) }
        return if (idx >= 0) idx else prioritizedCategoryTitles.size
    }

    private fun applyFilter() {
        if (isFinishing || isDestroyed) return

        val q = searchText.trim().lowercase()
        val list = allApps.filter { app ->
            val matchesQuery = q.isEmpty() ||
                app.appName.lowercase().contains(q) ||
                app.packageName.lowercase().contains(q) ||
                app.uid.toString().contains(q)
            val matchesFilter = when (filterType) {
                Filter.ALL_APPS -> true
                Filter.BYPASSED -> app.isProxyExcluded
                Filter.NOT_BYPASSED -> !app.isProxyExcluded
            }
            matchesQuery && matchesFilter
        }
        filteredApps.clear()
        filteredApps.addAll(list)
        adapter.notifyDataSetChanged()
        updateCountLabel()
        syncBulkToggleState()
    }

    private fun updateCountLabel() {
        if (isFinishing || isDestroyed) return
        val bypassed = allApps.count { it.isProxyExcluded }
        b.rpnBypassCount.text = if (bypassed == 0) {
            getString(R.string.rpn_bypass_apps_none)
        } else {
            getString(R.string.rpn_bypass_apps_count, bypassed)
        }
    }

    /** Reflects the persisted bulk state on the bulk checkboxes; purely visual. */
    private fun syncBulkToggleState() {
        if (bulkOpInProgress) return
        if (isFinishing || isDestroyed) return
        val bypassed = allApps.count { it.isProxyExcluded }
        b.rpnBypassBulkCheck.isChecked = allApps.isNotEmpty() && bypassed >= allApps.size
        b.rpnBypassDeselectAllCheck.isChecked = allApps.isNotEmpty() && bypassed == 0
    }

    private fun confirmBulkAction(include: Boolean) {
        val builder = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
        if (include) {
            builder.setTitle(getString(R.string.rpn_bypass_all_dialog_title))
            builder.setMessage(getString(R.string.rpn_bypass_all_dialog_desc))
        } else {
            builder.setTitle(getString(R.string.rpn_bypass_none_dialog_title))
            builder.setMessage(getString(R.string.rpn_bypass_none_dialog_desc))
        }
        builder.setCancelable(true)
        builder.setPositiveButton(
            if (include) getString(R.string.rpn_bypass_positive) else getString(R.string.exclude)
        ) { _, _ -> performBulkAction(include) }
        builder.setNegativeButton(getString(R.string.lbl_cancel), null)
        builder.create().show()
    }

    private fun performBulkAction(include: Boolean) {
        if (bulkOpInProgress) return
        bulkOpInProgress = true
        setBulkControlsEnabled(false)

        io {
            try {
                allApps.forEach { app ->
                    if (app.isProxyExcluded != include) {
                        FirewallManager.updateIsProxyExcluded(app.uid, include)
                        app.isProxyExcluded = include
                    }
                }
            } catch (e: Exception) {
                Logger.e(LOG_TAG_UI, "$TAG.performBulkAction failed: ${e.message}", e)
            }
            bulkOpInProgress = false
            uiCtx {
                setBulkControlsEnabled(true)
                updateCountLabel()
                syncBulkToggleState()
                applyFilter()
            }
        }
    }

    private fun setBulkControlsEnabled(enabled: Boolean) {
        b.rpnBypassBulkCheck.isEnabled = enabled
        b.rpnBypassBulkCheck.alpha = if (enabled) 1.0f else 0.5f
        b.rpnBypassDeselectAllCheck.isEnabled = enabled
        b.rpnBypassDeselectAllCheck.alpha = if (enabled) 1.0f else 0.5f
    }

    private fun onAppToggled(app: AppInfo, isExcluded: Boolean) {
        app.isProxyExcluded = isExcluded
        io {
            FirewallManager.updateIsProxyExcluded(app.uid, isExcluded)
            Logger.i(LOG_TAG_UI, "$TAG: proxy-exclude ${app.packageName}(${app.uid}) = $isExcluded")
        }
        updateCountLabel()
        syncBulkToggleState()
        // re-apply so chip filters (Bypassed / Not bypassed) stay accurate
        applyFilter()
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        searchText = query.orEmpty()
        applyFilter()
        return true
    }

    override fun onQueryTextChange(query: String?): Boolean {
        searchText = query.orEmpty()
        applyFilter()
        return true
    }

    /** Observable presenter only; all loading/filtering/mutation remains above. */
    inner class BypassAppsAdapter {
        private var rows by mutableStateOf<List<BypassRow>>(emptyList())
        fun notifyDataSetChanged() { rows = filteredApps.map { BypassRow(it, it.isProxyExcluded) } }
        @Composable fun Content(modifier: Modifier) {
            LazyColumn(modifier) {
                items(rows) { row ->
                    val icon = remember(row.app.packageName, row.app.appName) {
                        Utilities.getIcon(this@RpnBypassAppsActivity, row.app.packageName, row.app.appName)
                            ?: Utilities.getDefaultIcon(this@RpnBypassAppsActivity)
                    }
                    RpnBypassAppRow(row.app.appName, row.app.packageName, row.excluded, icon) { excluded -> onAppToggled(row.app, excluded) }
                }
            }
        }
    }

    private data class BypassRow(val app: AppInfo, val excluded: Boolean)

    private fun io(f: suspend () -> Unit): Job {
        return lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) {
            if (!isFinishing && !isDestroyed) {
                f()
            }
        }
    }
}
