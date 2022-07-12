/*
 * Copyright 2021 RethinkDNS and its authors
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
package com.celzero.bravedns.ui

import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Bundle
import android.view.View
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import android.widget.CompoundButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.FirewallAppListAdapter
import com.celzero.bravedns.database.RefreshDatabase
import com.celzero.bravedns.databinding.FragmentFirewallAppListBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.CustomLinearLayoutManager
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.AppInfoViewModel
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class FirewallAppFragment : Fragment(R.layout.fragment_firewall_app_list),
                            SearchView.OnQueryTextListener {
    private val b by viewBinding(FragmentFirewallAppListBinding::bind)

    private val appInfoViewModel: AppInfoViewModel by viewModel()
    private val persistentState by inject<PersistentState>()
    private val refreshDatabase by inject<RefreshDatabase>()

    private var layoutManager: RecyclerView.LayoutManager? = null

    private lateinit var animation: Animation

    companion object {
        fun newInstance() = FirewallAppFragment()

        val filters = MutableLiveData<Filters>()

        private const val ANIMATION_DURATION = 750L
        private const val ANIMATION_REPEAT_COUNT = -1
        private const val ANIMATION_PIVOT_VALUE = 0.5f
        private const val ANIMATION_START_DEGREE = 0.0f
        private const val ANIMATION_END_DEGREE = 360.0f

        private const val REFRESH_TIMEOUT: Long = 4000
        private const val QUERY_TEXT_TIMEOUT: Long = 600

        // TODO: Find a solution to replace the below query usage
        // ref: https://stackoverflow.com/questions/61055772/android-room-dao-order-by-case-not-working
        private const val ORDER_BY_DEFAULT = " lower(appName)"
        private const val ORDER_BY_BLOCKED = " CASE firewallStatus WHEN 1 THEN 0 WHEN 0 THEN 1 WHEN 2 THEN 2 WHEN 3 THEN 3 WHEN 4 THEN 4 END, lower(appName)"
        private const val ORDER_BY_WHITELISTED = " CASE firewallStatus WHEN 2 THEN 0 WHEN 0 THEN 1 WHEN 1 THEN 2 WHEN 3 THEN 3 WHEN 4 THEN 4 END, lower(appName)"
        private const val ORDER_BY_EXCLUDED = " CASE firewallStatus WHEN 3 THEN 0 WHEN 0 THEN 1 WHEN 1 THEN 2 WHEN 2 THEN 3 WHEN 4 THEN 4 END, lower(appName)"

    }

    enum class TopLevelFilter(val id: Int) {
        ALL(0), INSTALLED(1), SYSTEM(2)
    }

    enum class FirewallFilter(val id: Int) {
        NONE(0), ALLOWED(1), BLOCKED(2), WHITELISTED(3), EXCLUDED(4);

        fun getFilterString(): String {
            return when (this) {
                NONE -> "0,1,2,3,4"
                ALLOWED -> "0"
                BLOCKED -> "1"
                WHITELISTED -> "2"
                EXCLUDED -> "3"
            }
        }

        companion object {
            fun filter(id: Int): FirewallFilter {
                return when (id) {
                    NONE.id -> NONE
                    ALLOWED.id -> ALLOWED
                    BLOCKED.id -> BLOCKED
                    WHITELISTED.id -> WHITELISTED
                    EXCLUDED.id -> EXCLUDED
                    else -> NONE
                }
            }
        }
    }

    enum class SortFilter(val id: Int) {
        NONE(0), BLOCKED(2), WHITELISTED(3), EXCLUDED(4); //RESTRICTED(1),

        companion object {
            fun getSortFilter(id: Int): SortFilter {
                return when (id) {
                    NONE.id -> NONE
                    BLOCKED.id -> BLOCKED
                    WHITELISTED.id -> WHITELISTED
                    EXCLUDED.id -> EXCLUDED
                    else -> NONE
                }
            }
        }

        fun getSortByQuery(): String {
            return when (this) {
                NONE -> ORDER_BY_DEFAULT
                BLOCKED -> ORDER_BY_BLOCKED
                WHITELISTED -> ORDER_BY_WHITELISTED
                EXCLUDED -> ORDER_BY_EXCLUDED
            }
        }
    }

    class Filters {
        var categoryFilters: MutableSet<String> = mutableSetOf()
        var topLevelFilter = TopLevelFilter.ALL
        var firewallFilter = FirewallFilter.NONE
        var sortType = SortFilter.NONE
        var searchString: String = ""
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        initObserver()
        setupClickListener()
    }

    override fun onResume() {
        super.onResume()
        checkVpnLockdownAndAllNetworks()
    }

    private fun checkVpnLockdownAndAllNetworks() {
        if (VpnController.isVpnLockdown()) {
            b.firewallAppLockdownHint.text = getString(R.string.fapps_lockdown_hint)
            b.firewallAppLockdownHint.visibility = View.VISIBLE
            return
        }

        if (persistentState.useMultipleNetworks) {
            b.firewallAppLockdownHint.text = getString(R.string.fapps_all_network_hint)
            b.firewallAppLockdownHint.visibility = View.VISIBLE
            return
        }

        b.firewallAppLockdownHint.visibility = View.GONE
    }

    private fun initObserver() {
        filters.observe(this.viewLifecycleOwner) {
            resetFirewallIcons()

            if (it == null) return@observe

            appInfoViewModel.setFilter(it)
        }
    }

    override fun onDetach() {
        filters.postValue(Filters())
        super.onDetach()
    }

    override fun onQueryTextSubmit(query: String): Boolean {
        addQueryToFilters(query)
        return true
    }

    override fun onQueryTextChange(query: String): Boolean {
        Utilities.delay(QUERY_TEXT_TIMEOUT, lifecycleScope) {
            if (isAdded) {
                addQueryToFilters(query)
            }
        }
        return true
    }

    private fun addQueryToFilters(query: String) {
        if (filters.value == null) {
            val f = Filters()
            f.searchString = query
            filters.postValue(f)
            return
        }

        filters.value?.searchString = query
        filters.postValue(filters.value)
    }

    private fun setupClickListener() {
        b.ffaFilterIcon.setOnClickListener {
            openFilterBottomSheet()
        }

        b.ffaRefreshList.setOnClickListener {
            b.ffaRefreshList.isEnabled = false
            b.ffaRefreshList.animation = animation
            b.ffaRefreshList.startAnimation(animation)
            refreshDatabase()
            Utilities.delay(REFRESH_TIMEOUT, lifecycleScope) {
                if (isAdded) {
                    b.ffaRefreshList.isEnabled = true
                    b.ffaRefreshList.clearAnimation()
                    Utilities.showToastUiCentered(requireContext(),
                                                  getString(R.string.refresh_complete),
                                                  Toast.LENGTH_SHORT)
                }
            }
        }

        b.ffaToggleAllWifi.setOnClickListener {
            updateWifi()
        }

        b.ffaToggleAllMobileData.setOnClickListener {
            updateMobileData()
        }

        b.ffaSortIcon.setOnClickListener {
            toggleSortUi()
        }

        b.ffaAppInfoIcon.setOnClickListener {
            showInfoDialog()
        }
    }

    private fun showInfoDialog() {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle(getString(R.string.fapps_info_dialog_title))
        builder.setMessage(getString(R.string.fapps_info_dialog_message))
        builder.setPositiveButton(getString(R.string.fapps_info_dialog_positive_btn)) { dialog, _ ->
            dialog.dismiss()
        }

        builder.setCancelable(false)
        builder.create().show()
    }

    private fun toggleSortUi() {
        if (b.ffaSortChipLl.isVisible) {
            b.ffaSortChipLl.visibility = View.GONE
        } else {
            b.ffaSortChipLl.visibility = View.VISIBLE
        }
    }

    private fun remakeSortChips() {
        b.ffaSortChipGroup.removeAllViews()

        val none = makeSortChip(SortFilter.NONE.id, getString(R.string.fapps_sort_filter_none),
                                true)
        val blocked = makeSortChip(SortFilter.BLOCKED.id,
                                   getString(R.string.fapps_sort_filter_blocked), false)
        val whitelisted = makeSortChip(SortFilter.WHITELISTED.id,
                                       getString(R.string.fapps_sort_filter_whitelisted), false)
        val excluded = makeSortChip(SortFilter.EXCLUDED.id,
                                    getString(R.string.fapps_sort_filter_excluded), false)

        b.ffaSortChipGroup.addView(none)
        b.ffaSortChipGroup.addView(blocked)
        b.ffaSortChipGroup.addView(whitelisted)
        b.ffaSortChipGroup.addView(excluded)
    }

    private fun makeSortChip(id: Int, label: String, checked: Boolean): Chip {
        val chip = this.layoutInflater.inflate(R.layout.item_chip_filter, b.root, false) as Chip
        chip.tag = id
        chip.text = label
        chip.isChecked = checked

        chip.setOnCheckedChangeListener { button: CompoundButton, isSelected: Boolean ->
            if (isSelected) {
                applySortFilter(button.tag)
                colorUpChipIcon(chip)
            } else {
                // no-op
                // no action needed for checkState: false
            }
        }

        return chip
    }

    private fun colorUpChipIcon(chip: Chip) {
        val colorFilter = PorterDuffColorFilter(
            ContextCompat.getColor(requireContext(), R.color.primaryText), PorterDuff.Mode.SRC_IN)
        chip.checkedIcon?.colorFilter = colorFilter
        chip.chipIcon?.colorFilter = colorFilter
    }

    private fun applySortFilter(tag: Any) {
        if (filters.value == null) {
            val f = Filters()
            f.sortType = SortFilter.getSortFilter(tag as Int)
            filters.postValue(f)
            return
        }

        filters.value?.sortType = SortFilter.getSortFilter(tag as Int)
        filters.postValue(filters.value)
    }

    private fun resetFirewallIcons() {
        b.ffaToggleAllMobileData.setImageResource(R.drawable.ic_firewall_data_on_grey)
        b.ffaToggleAllWifi.setImageResource(R.drawable.ic_firewall_wifi_on_grey)
    }

    private fun updateMobileData() {
        if (b.ffaToggleAllMobileData.tag == 0) {
            b.ffaToggleAllMobileData.tag = 1
            b.ffaToggleAllMobileData.setImageResource(R.drawable.ic_firewall_data_off)
            io {
                appInfoViewModel.updateMobileDataStatus(true)
            }
            return
        }

        b.ffaToggleAllMobileData.tag = 0
        b.ffaToggleAllMobileData.setImageResource(R.drawable.ic_firewall_data_on)
        io {
            appInfoViewModel.updateMobileDataStatus(false)
        }
    }

    private fun updateWifi() {
        if (b.ffaToggleAllWifi.tag == 0) {
            b.ffaToggleAllWifi.tag = 1
            b.ffaToggleAllWifi.setImageResource(R.drawable.ic_firewall_wifi_off)
            io {
                appInfoViewModel.updateWifiStatus(true)
            }
            return
        }

        b.ffaToggleAllWifi.tag = 0
        b.ffaToggleAllWifi.setImageResource(R.drawable.ic_firewall_wifi_on)
        io {
            appInfoViewModel.updateWifiStatus(false)
        }
    }

    private fun initView() {
        initListAdapter()
        addObserver()
        b.ffaSearch.setOnQueryTextListener(this)
        addAnimation()
        remakeSortChips()
    }

    private fun addObserver() {
        filters.observe(viewLifecycleOwner) {
            if (it == null) return@observe

            appInfoViewModel.setFilter(it)
            b.ffaAppList.smoothScrollToPosition(0)
        }
    }

    private fun initListAdapter() {
        b.ffaAppList.setHasFixedSize(true)
        layoutManager = CustomLinearLayoutManager(requireContext())
        b.ffaAppList.layoutManager = layoutManager
        val recyclerAdapter = FirewallAppListAdapter(requireContext(), viewLifecycleOwner,
                                                     persistentState)
        appInfoViewModel.appInfo.observe(viewLifecycleOwner,
                                         androidx.lifecycle.Observer(recyclerAdapter::submitList))
        b.ffaAppList.adapter = recyclerAdapter

    }

    private fun openFilterBottomSheet() {
        val bottomSheetFragment = FirewallAppFilterBottomSheet()
        bottomSheetFragment.show(requireActivity().supportFragmentManager, bottomSheetFragment.tag)
    }

    private fun addAnimation() {
        animation = RotateAnimation(ANIMATION_START_DEGREE, ANIMATION_END_DEGREE,
                                    Animation.RELATIVE_TO_SELF, ANIMATION_PIVOT_VALUE,
                                    Animation.RELATIVE_TO_SELF, ANIMATION_PIVOT_VALUE)
        animation.repeatCount = ANIMATION_REPEAT_COUNT
        animation.duration = ANIMATION_DURATION
    }

    private fun refreshDatabase() {
        io {
            refreshDatabase.refreshAppInfoDatabase()
        }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                f()
            }
        }
    }
}
