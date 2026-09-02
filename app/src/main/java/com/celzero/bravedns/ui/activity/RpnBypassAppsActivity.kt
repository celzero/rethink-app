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
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import android.widget.CompoundButton
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import by.kirich1409.viewbindingdelegate.viewBinding
import com.bumptech.glide.Glide
import com.celzero.bravedns.R
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.AppInfoRepository
import com.celzero.bravedns.databinding.ActivityRpnBypassAppsBinding
import com.celzero.bravedns.databinding.ListItemRpnBypassAppBinding
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
class RpnBypassAppsActivity : BaseActivity(R.layout.activity_rpn_bypass_apps),
    SearchView.OnQueryTextListener {

    private val b by viewBinding(ActivityRpnBypassAppsBinding::bind)
    private val persistentState by inject<PersistentState>()
    private val appInfoRepository by inject<AppInfoRepository>()

    private lateinit var adapter: BypassAppsAdapter

    /** All tracked apps, loaded once from the DB. */
    private val allApps = mutableListOf<AppInfo>()

    /** Subset of [allApps] matching the current chip filter + search query. */
    private val filteredApps = mutableListOf<AppInfo>()

    private var filterType = Filter.ALL_APPS
    private var searchText = ""

    /** true while a bulk bypass/clear is being persisted; guards against re-entry. */
    @Volatile
    private var bulkOpInProgress = false

    private var loadJob: Job? = null

    private lateinit var animation: Animation

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

        handleFrostEffectIfNeeded(persistentState.theme)

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars =
                Themes.isActivityLightTheme(isDarkThemeOn(), persistentState.theme)
            window.isNavigationBarContrastEnforced = false
        }

        addAnimation()

        adapter = BypassAppsAdapter()
        b.rpnBypassRecyclerView.layoutManager = LinearLayoutManager(this)
        b.rpnBypassRecyclerView.adapter = adapter

        remakeChipsUi()
        setupClickListeners()
        loadApps()
    }

    private fun addAnimation() {
        animation =
            RotateAnimation(
                ANIMATION_START_DEGREE,
                ANIMATION_END_DEGREE,
                Animation.RELATIVE_TO_SELF,
                ANIMATION_PIVOT_VALUE,
                Animation.RELATIVE_TO_SELF,
                ANIMATION_PIVOT_VALUE
            )
        animation.repeatCount = ANIMATION_REPEAT_COUNT
        animation.duration = ANIMATION_DURATION
    }

    override fun onDestroy() {
        loadJob?.cancel()
        loadJob = null
        super.onDestroy()
    }

    private fun remakeChipsUi() {
        b.rpnBypassChipGroup.removeAllViews()

        b.rpnBypassChipGroup.addView(makeFilterChip(Filter.ALL_APPS.id, getString(R.string.lbl_all), true))
        b.rpnBypassChipGroup.addView(makeFilterChip(Filter.BYPASSED.id, getString(R.string.fapps_firewall_filter_bypass_universal), false))
        b.rpnBypassChipGroup.addView(makeFilterChip(Filter.NOT_BYPASSED.id, getString(R.string.lbl_unselected), false))
    }

    private fun makeFilterChip(id: Int, label: String, checked: Boolean): Chip {
        val chip = layoutInflater.inflate(R.layout.item_chip_filter, b.root, false) as Chip
        chip.tag = id
        chip.text = label
        chip.isChecked = checked
        chip.setOnCheckedChangeListener { button: CompoundButton, isSelected: Boolean ->
            if (isSelected) {
                filterType = Filter.entries.firstOrNull { it.id == button.tag } ?: Filter.ALL_APPS
                applyFilter()
                colorUpChipIcon(chip)
            }
        }
        return chip
    }

    private fun colorUpChipIcon(chip: Chip) {
        val colorFilter = PorterDuffColorFilter(
            ContextCompat.getColor(this, R.color.primaryText),
            PorterDuff.Mode.SRC_IN
        )
        chip.checkedIcon?.colorFilter = colorFilter
        chip.chipIcon?.colorFilter = colorFilter
    }

    private fun setupClickListeners() {
        b.rpnBypassSearchView.setOnQueryTextListener(this)
        b.rpnBypassSearchView.setOnCloseListener {
            searchText = ""
            applyFilter()
            false
        }

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
            b.rpnBypassRefreshList.animation = animation
            b.rpnBypassRefreshList.startAnimation(animation)
            loadApps()
            Utilities.delay(REFRESH_TIMEOUT, lifecycleScope) {
                if (!this.isFinishing && !this.isDestroyed) {
                    b.rpnBypassRefreshList.isEnabled = true
                    b.rpnBypassRefreshList.clearAnimation()
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
                allApps.addAll(apps.sortedBy { it.appName.lowercase() })
                b.rpnBypassRefreshList.isEnabled = true
                applyFilter()
            }
        }
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

    inner class BypassAppsAdapter : RecyclerView.Adapter<BypassAppsAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ListItemRpnBypassAppBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val itemBinding = ListItemRpnBypassAppBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = filteredApps[position]
            with(holder.binding) {
                val iconDrawable = Utilities.getIcon(root.context, app.packageName, app.appName)
                Glide.with(root.context)
                    .load(iconDrawable)
                    .error(Utilities.getDefaultIcon(root.context))
                    .into(rpnBypassAppIcon)

                rpnBypassAppName.text = app.appName
                rpnBypassAppPackage.text = app.packageName

                rpnBypassAppSwitch.isEnabled = true
                rpnBypassAppSwitch.setOnCheckedChangeListener(null)
                rpnBypassAppSwitch.isChecked = app.isProxyExcluded
                rpnBypassAppSwitch.setOnCheckedChangeListener { _, isChecked ->
                    onAppToggled(app, isChecked)
                }
                root.setOnClickListener { rpnBypassAppSwitch.isChecked = !rpnBypassAppSwitch.isChecked }
            }
        }

        override fun getItemCount(): Int = filteredApps.size
    }

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
