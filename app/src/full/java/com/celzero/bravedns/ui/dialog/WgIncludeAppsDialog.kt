/*
 * Copyright 2023 RethinkDNS and its authors
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
package com.celzero.bravedns.ui.dialog

import Logger
import Logger.LOG_TAG_PROXY
import android.app.Activity
import android.app.Dialog
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import android.widget.CompoundButton
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.database.RefreshDatabase
import com.celzero.bravedns.databinding.DialogWgAppsBinding
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.ProxyAppsMappingViewModel
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class WgIncludeAppsDialog(
    private var activity: Activity,
    internal var adapter: RecyclerView.Adapter<*>,
    var viewModel: ProxyAppsMappingViewModel,
    themeID: Int,
    private val proxyId: String,
    private val proxyName: String
) : Dialog(activity, themeID), SearchView.OnQueryTextListener, KoinComponent {

    private lateinit var b: DialogWgAppsBinding

    private lateinit var animation: Animation
    private val refreshDatabase by inject<RefreshDatabase>()
    private var filterType: TopLevelFilter = TopLevelFilter.ALL_APPS
    private var searchText = ""

    companion object {
        private const val ANIMATION_DURATION = 750L
        private const val ANIMATION_REPEAT_COUNT = -1
        private const val ANIMATION_PIVOT_VALUE = 0.5f
        private const val ANIMATION_START_DEGREE = 0.0f
        private const val ANIMATION_END_DEGREE = 360.0f

        private const val REFRESH_TIMEOUT: Long = 4000
    }

    enum class TopLevelFilter(val id: Int) {
        ALL_APPS(0),
        SELECTED_APPS(1),
        UNSELECTED_APPS(2);

        fun getLabelId(): Int {
            return when (this) {
                ALL_APPS -> R.string.lbl_all
                SELECTED_APPS -> R.string.rt_filter_parent_selected
                UNSELECTED_APPS -> R.string.lbl_unselected
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestWindowFeature(Window.FEATURE_NO_TITLE)
        b = DialogWgAppsBinding.inflate(layoutInflater)
        setContentView(b.root)
        setCancelable(false)
        addAnimation()
        remakeFirewallChipsUi()
        observeApps()
        initializeValues()
        initializeClickListeners()
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
        window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )

        val layoutManager = LinearLayoutManager(activity)
        b.wgIncludeAppRecyclerViewDialog.layoutManager = layoutManager
        b.wgIncludeAppRecyclerViewDialog.adapter = adapter
    }

    private fun observeApps() {
        // observe DB-backed count so heading stays in sync as mappings change
        viewModel.getAppCountById(proxyId).observe(activity as LifecycleOwner) { count ->
            val safeCount = count ?: 0
            b.wgIncludeAppDialogHeading.text =
                activity.getString(R.string.add_remove_apps, safeCount.toString())
        }
    }

    private fun remakeFirewallChipsUi() {
        b.wgIncludeAppDialogChipGroup.removeAllViews()

        val all =
            makeFirewallChip(
                TopLevelFilter.ALL_APPS.id,
                activity.getString(TopLevelFilter.ALL_APPS.getLabelId()),
                true
            )

        val selected =
            makeFirewallChip(
                TopLevelFilter.SELECTED_APPS.id,
                activity.getString(TopLevelFilter.SELECTED_APPS.getLabelId()),
                false
            )

        val unselected =
            makeFirewallChip(
                TopLevelFilter.UNSELECTED_APPS.id,
                activity.getString(TopLevelFilter.UNSELECTED_APPS.getLabelId()),
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
                ContextCompat.getColor(activity, R.color.primaryText),
                PorterDuff.Mode.SRC_IN
            )
        chip.checkedIcon?.colorFilter = colorFilter
        chip.chipIcon?.colorFilter = colorFilter
    }

    private fun applyFilter(tag: Any) {
        when (tag as Int) {
            TopLevelFilter.ALL_APPS.id -> {
                filterType = TopLevelFilter.ALL_APPS
                viewModel.setFilter(searchText, filterType, proxyId)
            }
            TopLevelFilter.SELECTED_APPS.id -> {
                filterType = TopLevelFilter.SELECTED_APPS
                viewModel.setFilter(searchText, filterType, proxyId)
            }
            TopLevelFilter.UNSELECTED_APPS.id -> {
                filterType = TopLevelFilter.UNSELECTED_APPS
                viewModel.setFilter(searchText, filterType, proxyId)
            }
        }
    }

    private fun initializeClickListeners() {
        b.wgIncludeAppDialogOkButton.setOnClickListener {
            clearSearch()
            dismiss()
        }

        b.wgIncludeAppDialogSearchView.setOnQueryTextListener(this)

        b.wgIncludeAppDialogSearchView.setOnCloseListener {
            clearSearch()
            false
        }

        b.wgIncludeAppSelectAllCheckbox.setOnClickListener {
            showDialog(b.wgIncludeAppSelectAllCheckbox.isChecked)
        }

        b.wgRemainingAppsBtn.setOnClickListener { showConfirmationDialog() }

        b.wgIncludeAppSelectAllCheckbox.setOnCheckedChangeListener(null)

        b.wgRefreshList.setOnClickListener {
            b.wgRefreshList.isEnabled = false
            b.wgRefreshList.animation = animation
            b.wgRefreshList.startAnimation(animation)
            refreshDatabase()
            val l = activity as LifecycleOwner
            Utilities.delay(REFRESH_TIMEOUT, l.lifecycleScope) {
                if (this.isShowing) {
                    b.wgRefreshList.isEnabled = true
                    b.wgRefreshList.clearAnimation()
                    Utilities.showToastUiCentered(
                        context,
                        context.getString(R.string.refresh_complete),
                        Toast.LENGTH_SHORT
                    )
                }
            }
        }
    }

    private fun refreshDatabase() {
        io { refreshDatabase.refresh(RefreshDatabase.ACTION_REFRESH_INTERACTIVE) }
    }

    private fun clearSearch() {
        viewModel.setFilter("", TopLevelFilter.ALL_APPS, proxyId)
    }

    private fun showDialog(toAdd: Boolean) {
        val builder = MaterialAlertDialogBuilder(context, R.style.App_Dialog_NoDim)
        if (toAdd) {
            builder.setTitle(context.getString(R.string.include_all_app_wg_dialog_title))
            builder.setMessage(context.getString(R.string.include_all_app_wg_dialog_desc))
        } else {
            builder.setTitle(context.getString(R.string.exclude_all_app_wg_dialog_title))
            builder.setMessage(context.getString(R.string.exclude_all_app_wg_dialog_desc))
        }
        builder.setCancelable(true)
        builder.setPositiveButton(
            if (toAdd) context.getString(R.string.lbl_include)
            else context.getString(R.string.exclude)
        ) { _, _ ->
            io {
                if (toAdd) {
                    Logger.i(LOG_TAG_PROXY, "Adding all apps to proxy $proxyId, $proxyName")
                    ProxyManager.setProxyIdForAllApps(proxyId, proxyName)
                } else {
                    Logger.i(LOG_TAG_PROXY, "Removing all apps from proxy $proxyId, $proxyName")
                    ProxyManager.setNoProxyForAllAppsForProxy(proxyId)
                }
                // re-apply current filter to force Paging source reload and UI refresh
                withContext(Dispatchers.Main) {
                    viewModel.setFilter(searchText, filterType, proxyId)
                }
            }
        }

        builder.setNegativeButton(context.getString(R.string.lbl_cancel)) { _, _ ->
            b.wgIncludeAppSelectAllCheckbox.isChecked = !toAdd
        }

        builder.create().show()
    }

    private fun showConfirmationDialog() {
        val builder = MaterialAlertDialogBuilder(context, R.style.App_Dialog_NoDim)
        builder.setTitle(context.getString(R.string.remaining_apps_dialog_title))
        builder.setMessage(context.getString(R.string.remaining_apps_dialog_desc))
        builder.setCancelable(true)
        builder.setPositiveButton(context.getString(R.string.lbl_include)) { _, _ ->
            io {
                Logger.i(LOG_TAG_PROXY, "Adding remaining apps to proxy $proxyId, $proxyName")
                ProxyManager.setProxyIdForUnselectedApps(proxyId, proxyName)
                // refresh paging / adapter after bulk add
                withContext(Dispatchers.Main) {
                    viewModel.setFilter(searchText, filterType, proxyId)
                }
            }
        }

        builder.setNegativeButton(context.getString(R.string.lbl_cancel)) { _, _ ->
            // no-op
        }

        builder.create().show()
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
        (activity as LifecycleOwner).lifecycleScope.launch(Dispatchers.IO) { f() }
    }
}
