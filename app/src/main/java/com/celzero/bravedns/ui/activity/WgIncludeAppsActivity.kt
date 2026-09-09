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
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Bundle
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import android.widget.CompoundButton
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.WgIncludeAppsAdapter
import com.celzero.bravedns.database.RefreshDatabase
import com.celzero.bravedns.databinding.DialogWgAppsBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_PROXY
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.ProxyAppsMappingViewModel
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class WgIncludeAppsActivity : BaseActivity(R.layout.dialog_wg_apps),
    SearchView.OnQueryTextListener {

    private val b by viewBinding(DialogWgAppsBinding::bind)
    private val persistentState by inject<PersistentState>()
    private val refreshDatabase by inject<RefreshDatabase>()
    private val viewModel: ProxyAppsMappingViewModel by viewModel()

    private lateinit var appsAdapter: WgIncludeAppsAdapter
    private lateinit var animation: Animation

    private var proxyId: String = ""
    private var proxyName: String = ""
    private var filterType: ProxyAppsMappingViewModel.TopLevelFilter =
        ProxyAppsMappingViewModel.TopLevelFilter.ALL_APPS
    private var searchText = ""

    /** true while a bulk include/remove is being persisted; guards against re-entry. */
    @Volatile
    private var bulkOpInProgress = false

    companion object {
        private const val ANIMATION_DURATION = 750L
        private const val ANIMATION_REPEAT_COUNT = -1
        private const val ANIMATION_PIVOT_VALUE = 0.5f
        private const val ANIMATION_START_DEGREE = 0.0f
        private const val ANIMATION_END_DEGREE = 360.0f

        private const val REFRESH_TIMEOUT: Long = 4000

        private const val INTENT_EXTRA_PROXY_ID = "proxy_id"
        private const val INTENT_EXTRA_PROXY_NAME = "proxy_name"

        fun newIntent(context: Context, proxyId: String, proxyName: String): Intent {
            val intent = Intent(context, WgIncludeAppsActivity::class.java)
            intent.putExtra(INTENT_EXTRA_PROXY_ID, proxyId)
            intent.putExtra(INTENT_EXTRA_PROXY_NAME, proxyName)
            return intent
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)

        proxyId = intent.getStringExtra(INTENT_EXTRA_PROXY_ID) ?: ""
        proxyName = intent.getStringExtra(INTENT_EXTRA_PROXY_NAME) ?: ""
        if (proxyId.isBlank()) {
            Logger.e(LOG_TAG_PROXY, "WgIncludeAppsActivity started without a proxyId, finishing")
            finish()
            return
        }

        addAnimation()
        remakeFirewallChipsUi()
        initializeValues()
        observeApps()
        initializeClickListeners()
        syncBulkToggleState()
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
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

    private fun initializeValues() {
        appsAdapter =
            WgIncludeAppsAdapter(this, proxyId, proxyName, onAppModified = { onAppModified() })
        viewModel.apps.observe(this) { appsAdapter.submitData(lifecycle, it) }

        val layoutManager = LinearLayoutManager(this)
        b.wgIncludeAppRecyclerViewDialog.layoutManager = layoutManager
        b.wgIncludeAppRecyclerViewDialog.adapter = appsAdapter
    }

    private fun observeApps() {
        // observe DB-backed count so heading stays in sync as mappings change
        viewModel.getAppCountById(proxyId).observe(this) { count ->
            val safeCount = count ?: 0
            b.wgIncludeAppDialogHeading.text = getString(R.string.add_remove_apps, safeCount.toString())
        }
    }

    private fun remakeFirewallChipsUi() {
        b.wgIncludeAppDialogChipGroup.removeAllViews()

        val all =
            makeFirewallChip(
                ProxyAppsMappingViewModel.TopLevelFilter.ALL_APPS.id,
                getString(ProxyAppsMappingViewModel.TopLevelFilter.ALL_APPS.getLabelId()),
                true
            )

        val selected =
            makeFirewallChip(
                ProxyAppsMappingViewModel.TopLevelFilter.SELECTED_APPS.id,
                getString(ProxyAppsMappingViewModel.TopLevelFilter.SELECTED_APPS.getLabelId()),
                false
            )

        val unselected =
            makeFirewallChip(
                ProxyAppsMappingViewModel.TopLevelFilter.UNSELECTED_APPS.id,
                getString(ProxyAppsMappingViewModel.TopLevelFilter.UNSELECTED_APPS.getLabelId()),
                false
            )

        b.wgIncludeAppDialogChipGroup.addView(all)
        b.wgIncludeAppDialogChipGroup.addView(selected)
        b.wgIncludeAppDialogChipGroup.addView(unselected)
    }

    private fun makeFirewallChip(id: Int, label: String, checked: Boolean): Chip {
        val chip = this.layoutInflater.inflate(R.layout.item_chip_filter, b.root, false) as Chip
        chip.tag = id
        chip.text = label
        chip.isChecked = checked

        chip.setOnCheckedChangeListener { button: CompoundButton, isSelected: Boolean ->
            if (isSelected) {
                applyFilter(button.tag)
                colorUpChipIcon(chip)
            } else {
                // no-op
                // no action needed for checkState: false
            }
        }

        return chip
    }

    private fun colorUpChipIcon(chip: Chip) {
        val colorFilter =
            PorterDuffColorFilter(
                ContextCompat.getColor(this, R.color.primaryText),
                PorterDuff.Mode.SRC_IN
            )
        chip.checkedIcon?.colorFilter = colorFilter
        chip.chipIcon?.colorFilter = colorFilter
    }

    private fun applyFilter(tag: Any) {
        when (tag as Int) {
            ProxyAppsMappingViewModel.TopLevelFilter.ALL_APPS.id -> {
                filterType = ProxyAppsMappingViewModel.TopLevelFilter.ALL_APPS
                viewModel.setFilter(searchText, filterType, proxyId)
            }
            ProxyAppsMappingViewModel.TopLevelFilter.SELECTED_APPS.id -> {
                filterType = ProxyAppsMappingViewModel.TopLevelFilter.SELECTED_APPS
                viewModel.setFilter(searchText, filterType, proxyId)
            }
            ProxyAppsMappingViewModel.TopLevelFilter.UNSELECTED_APPS.id -> {
                filterType = ProxyAppsMappingViewModel.TopLevelFilter.UNSELECTED_APPS
                viewModel.setFilter(searchText, filterType, proxyId)
            }
        }
    }

    private fun initializeClickListeners() {
        b.wgIncludeAppDialogSearchView.setOnQueryTextListener(this)

        b.wgIncludeAppDialogSearchView.setOnCloseListener {
            clearSearch()
            false
        }

        // Both bulk actions are always visible. Each acts immediately on click
        // (after a confirmation); the checked visual state is owned by
        // syncBulkToggleState(), not by user taps.
        b.wgIncludeAppBulkCheck.setOnClickListener {
            if (bulkOpInProgress) return@setOnClickListener
            confirmBulkAction(include = true)
        }

        b.wgIncludeAppDeselectAllCheck.setOnClickListener {
            if (bulkOpInProgress) return@setOnClickListener
            confirmBulkAction(include = false)
        }

        b.wgRefreshList.setOnClickListener {
            b.wgRefreshList.isEnabled = false
            b.wgRefreshList.animation = animation
            b.wgRefreshList.startAnimation(animation)
            refreshDatabase()
            Utilities.delay(REFRESH_TIMEOUT, lifecycleScope) {
                if (!isDestroyed && !isFinishing) {
                    b.wgRefreshList.isEnabled = true
                    b.wgRefreshList.clearAnimation()
                    Utilities.showToastUiCentered(
                        this@WgIncludeAppsActivity,
                        getString(R.string.refresh_complete),
                        Toast.LENGTH_SHORT
                    )
                }
            }
        }
    }

    private fun refreshDatabase() {
        io { refreshDatabase.refresh(RefreshDatabase.ACTION_REFRESH_INTERACTIVE) }
    }

    private fun refreshPagingAdapter() {
        viewModel.setFilter(searchText, filterType, proxyId)
        appsAdapter.refresh()
    }

    private fun clearSearch() {
        viewModel.setFilter("", ProxyAppsMappingViewModel.TopLevelFilter.ALL_APPS, proxyId)
    }

    /**
     * Reflects the persisted bulk state on the checkboxes:
     * all apps included → "select all" checked; no apps included → "deselect all" checked.
     * Purely visual, never triggers actions.
     */
    private fun syncBulkToggleState() {
        io {
            val selected = ProxyManager.getAppCountForProxy(proxyId)
            val total = ProxyManager.trackedApps().size
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                if (total > 0 && selected >= total) {
                    b.wgIncludeAppBulkCheck.isChecked = true
                    b.wgIncludeAppDeselectAllCheck.isChecked = false
                } else if (total > 0 && selected == 0) {
                    b.wgIncludeAppBulkCheck.isChecked = false
                    b.wgIncludeAppDeselectAllCheck.isChecked = true
                } else {
                    b.wgIncludeAppBulkCheck.isChecked = false
                    b.wgIncludeAppDeselectAllCheck.isChecked = false
                }
            }
        }
    }

    private fun setBulkControlsEnabled(enabled: Boolean) {
        b.wgIncludeAppBulkCheck.isEnabled = enabled
        b.wgIncludeAppBulkCheck.alpha = if (enabled) 1.0f else 0.5f
        b.wgIncludeAppDeselectAllCheck.isEnabled = enabled
        b.wgIncludeAppDeselectAllCheck.alpha = if (enabled) 1.0f else 0.5f
    }

    private fun confirmBulkAction(include: Boolean) {
        val builder = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
        if (include) {
            builder.setTitle(getString(R.string.include_all_app_wg_dialog_title))
            builder.setMessage(getString(R.string.include_all_app_wg_dialog_desc))
        } else {
            builder.setTitle(getString(R.string.exclude_all_app_wg_dialog_title))
            builder.setMessage(getString(R.string.exclude_all_app_wg_dialog_desc))
        }
        builder.setCancelable(true)
        builder.setPositiveButton(
            if (include) getString(R.string.lbl_include) else getString(R.string.exclude)
        ) { _, _ ->
            performBulkAction(include)
        }

        builder.setNegativeButton(getString(R.string.lbl_cancel)) { _, _ ->
            // nothing was changed: revert the checkbox state
            syncBulkToggleState()
        }

        builder.setOnCancelListener {
            // dismissed outside buttons: revert the checkbox state
            syncBulkToggleState()
        }

        builder.create().show()
    }

    private fun performBulkAction(include: Boolean) {
        if (bulkOpInProgress) return
        bulkOpInProgress = true
        setBulkControlsEnabled(false)

        io {
            try {
                if (include) {
                    Logger.i(LOG_TAG_PROXY, "Adding all apps to proxy $proxyId, $proxyName")
                    ProxyManager.setProxyIdForAllApps(proxyId, proxyName)
                } else {
                    Logger.i(LOG_TAG_PROXY, "Removing all apps from proxy $proxyId, $proxyName")
                    ProxyManager.setNoProxyForAllAppsForProxy(proxyId)
                }
            } catch (e: Exception) {
                Logger.e(LOG_TAG_PROXY, "bulk action failed for $proxyId: ${e.message}", e)
            }

            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) {
                    bulkOpInProgress = false
                    return@withContext
                }
                bulkOpInProgress = false
                setBulkControlsEnabled(true)

                if (!include) {
                    // a bulk remove-all changes routing intent; inform the caller
                    onAppModified()
                }

                // selection now mirrors the confirmed state
                syncBulkToggleState()

                // re-apply current filter to force Paging source reload and UI refresh
                refreshPagingAdapter()
            }
        }
    }

    /**
     * Invoked after any individual app inclusion/exclusion (via the adapter) or a bulk
     * deselect-all. Marks the result so the caller can react (e.g. turn off catch-all)
     * once this activity finishes.
     */
    private fun onAppModified() {
        setResult(RESULT_OK)
    }

    override fun onQueryTextSubmit(query: String): Boolean {
        searchText = query
        viewModel.setFilter(query, filterType, proxyId)
        return true
    }

    override fun onQueryTextChange(query: String): Boolean {
        searchText = query
        viewModel.setFilter(query, filterType, proxyId)
        return true
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }
}
