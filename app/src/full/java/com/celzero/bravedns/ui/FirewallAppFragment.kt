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

import android.content.Context
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
    }

    enum class TopLevelFilter(val id: Int) {
        ALL(0), INSTALLED(1), SYSTEM(2);

        fun getLabel(context: Context): String {
            return when (this) {
                ALL -> {
                    // getLabel is used only to show the filtered details in ui,
                    // no need to show "all" tag.
                    ""
                }
                INSTALLED -> {
                    context.getString(R.string.fapps_filter_parent_installed)
                }
                SYSTEM -> {
                    context.getString(R.string.fapps_filter_parent_system)
                }
            }
        }
    }

    enum class FirewallFilter(val id: Int) {
        ALL(0), ALLOWED(1), BLOCKED(2), BYPASS_UNIVERSAL(3), EXCLUDED(4), LOCKDOWN(5);

        fun getFilter(): Set<Int> {
            return when (this) {
                ALL -> setOf(0, 1, 2, 3, 4, 5)
                ALLOWED -> setOf(0)
                BLOCKED -> setOf(1)
                BYPASS_UNIVERSAL -> setOf(2)
                EXCLUDED -> setOf(3)
                LOCKDOWN -> setOf(4)
            }
        }

        fun getLabel(context: Context): String {
            return when (this) {
                ALL -> context.getString(R.string.fapps_firewall_filter_all)
                ALLOWED -> context.getString(R.string.fapps_firewall_filter_allowed)
                BLOCKED -> context.getString(R.string.fapps_firewall_filter_blocked)
                BYPASS_UNIVERSAL -> context.getString(
                    R.string.fapps_firewall_filter_bypass_universal
                )
                EXCLUDED -> context.getString(R.string.fapps_firewall_filter_excluded)
                LOCKDOWN -> context.getString(R.string.fapps_firewall_filter_lockdown)
            }
        }

        companion object {
            fun filter(id: Int): FirewallFilter {
                return when (id) {
                    ALL.id -> ALL
                    ALLOWED.id -> ALLOWED
                    BLOCKED.id -> BLOCKED
                    BYPASS_UNIVERSAL.id -> BYPASS_UNIVERSAL
                    EXCLUDED.id -> EXCLUDED
                    LOCKDOWN.id -> LOCKDOWN
                    else -> ALL
                }
            }
        }
    }

    class Filters {
        var categoryFilters: MutableSet<String> = mutableSetOf()
        var topLevelFilter = TopLevelFilter.ALL
        var firewallFilter = FirewallFilter.ALL
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
        setFirewallFilter(filters.value?.firewallFilter)
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

            ui {
                appInfoViewModel.setFilter(it)
                b.ffaAppList.smoothScrollToPosition(0)
                updateFilterText(it)
            }
        }
    }

    private fun updateFilterText(filter: Filters) {
        val filterLabel = filter.topLevelFilter.getLabel(requireContext())
        val firewallLabel = filter.firewallFilter.getLabel(requireContext())
        if (filter.categoryFilters.isEmpty()) {
            b.firewallAppLabelTv.text = Utilities.updateHtmlEncodedText(
                getString(
                    R.string.fapps_firewall_filter_desc, firewallLabel.lowercase(),
                    filterLabel
                )
            )
        } else {
            b.firewallAppLabelTv.text = Utilities.updateHtmlEncodedText(
                getString(
                    R.string.fapps_firewall_filter_desc_category, firewallLabel.lowercase(),
                    filterLabel, filter.categoryFilters
                )
            )
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
                    Utilities.showToastUiCentered(
                        requireContext(),
                        getString(R.string.refresh_complete),
                        Toast.LENGTH_SHORT
                    )
                }
            }
        }

        b.ffaToggleAllWifi.setOnClickListener {
            showUpdateUnmeteredForBulkDialog(getBulkTagUnmetered())
        }

        b.ffaToggleAllMobileData.setOnClickListener {
            showUpdateMeteredForBulkDialog(getBulkTagMetered())
        }

        b.ffaAppInfoIcon.setOnClickListener {
            showInfoDialog()
        }
    }

    private fun showUpdateUnmeteredForBulkDialog(isBlock: Boolean) {
        val builder = AlertDialog.Builder(requireContext())
        if (isBlock) {
            builder.setTitle(R.string.fapps_unmetered_block_dialog_title)
            builder.setMessage(R.string.fapps_unmetered_block_dialog_message)
        } else {
            builder.setTitle(R.string.fapps_unmetered_unblock_dialog_title)
            builder.setMessage(R.string.fapps_unmetered_unblock_dialog_message)
        }

        builder.setPositiveButton(getString(R.string.fapps_unmetered_positive)) { _, _ ->
            updateUnmeteredBulk()
        }

        builder.setNegativeButton(getString(R.string.fapps_unmetered_negative)) { _, _ ->
            // no-op
        }

        builder.setCancelable(true)
        builder.create().show()
    }

    private fun showUpdateMeteredForBulkDialog(isBlock: Boolean) {
        val builder = AlertDialog.Builder(requireContext())
        if (isBlock) {
            builder.setTitle(R.string.fapps_metered_block_dialog_title)
            builder.setMessage(R.string.fapps_metered_block_dialog_message)
        } else {
            builder.setTitle(R.string.fapps_metered_unblock_dialog_title)
            builder.setMessage(R.string.fapps_metered_unblock_dialog_message)
        }
        builder.setPositiveButton(getString(R.string.fapps_metered_positive)) { _, _ ->
            updateMeteredBulk()
        }

        builder.setNegativeButton(getString(R.string.fapps_metered_negative)) { _, _ ->
            // no-op
        }

        builder.setCancelable(true)
        builder.create().show()
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

    private fun setFirewallFilter(firewallFilter: FirewallFilter?) {
        if (firewallFilter == null) return

        val view: Chip = b.ffaFirewallChipGroup.findViewWithTag(firewallFilter.id)
        b.ffaFirewallChipGroup.check(view.id)
        colorUpChipIcon(view)
    }

    private fun remakeFirewallChipsUi() {
        b.ffaFirewallChipGroup.removeAllViews()

        val none = makeFirewallChip(
            FirewallFilter.ALL.id,
            getString(R.string.fapps_firewall_filter_all), true
        )
        val allowed = makeFirewallChip(
            FirewallFilter.ALLOWED.id,
            getString(R.string.fapps_firewall_filter_allowed), false
        )
        val blocked = makeFirewallChip(
            FirewallFilter.BLOCKED.id,
            getString(R.string.fapps_firewall_filter_blocked), false
        )
        val bypassUniversal = makeFirewallChip(
            FirewallFilter.BYPASS_UNIVERSAL.id, getString(
                R.string.fapps_firewall_filter_bypass_universal
            ), false
        )
        val excluded = makeFirewallChip(
            FirewallFilter.EXCLUDED.id,
            getString(R.string.fapps_firewall_filter_excluded), false
        )
        val lockdown = makeFirewallChip(
            FirewallFilter.LOCKDOWN.id,
            getString(R.string.fapps_firewall_filter_lockdown), false
        )

        b.ffaFirewallChipGroup.addView(none)
        b.ffaFirewallChipGroup.addView(allowed)
        b.ffaFirewallChipGroup.addView(blocked)
        b.ffaFirewallChipGroup.addView(bypassUniversal)
        b.ffaFirewallChipGroup.addView(excluded)
        b.ffaFirewallChipGroup.addView(lockdown)
    }

    private fun makeFirewallChip(id: Int, label: String, checked: Boolean): Chip {
        val chip = this.layoutInflater.inflate(R.layout.item_chip_filter, b.root, false) as Chip
        chip.tag = id
        chip.text = label
        chip.isChecked = checked

        chip.setOnCheckedChangeListener { button: CompoundButton, isSelected: Boolean ->
            if (isSelected) {
                applyFirewallFilter(button.tag)
                colorUpChipIcon(chip)
            } else {
                // no-op
                // no action needed for checkState: false
            }
        }

        return chip
    }

    private fun applyFirewallFilter(tag: Any) {
        val firewallFilter = FirewallFilter.filter(tag as Int)
        if (filters.value == null) {
            val f = Filters()
            f.firewallFilter = firewallFilter
            filters.postValue(f)
            return
        }

        filters.value?.firewallFilter = firewallFilter
        filters.postValue(filters.value)
    }

    private fun colorUpChipIcon(chip: Chip) {
        val colorFilter = PorterDuffColorFilter(
            ContextCompat.getColor(requireContext(), R.color.primaryText), PorterDuff.Mode.SRC_IN
        )
        chip.checkedIcon?.colorFilter = colorFilter
        chip.chipIcon?.colorFilter = colorFilter
    }

    private fun resetFirewallIcons() {
        b.ffaToggleAllMobileData.setImageResource(R.drawable.ic_firewall_data_on_grey)
        b.ffaToggleAllWifi.setImageResource(R.drawable.ic_firewall_wifi_on_grey)
    }

    private fun updateMeteredBulk() {
        if (getBulkTagMetered()) {
            b.ffaToggleAllMobileData.tag = 1
            b.ffaToggleAllMobileData.setImageResource(R.drawable.ic_firewall_data_off)
            io {
                appInfoViewModel.updateMeteredStatus(true)
            }
            return
        }

        b.ffaToggleAllMobileData.tag = 0
        b.ffaToggleAllMobileData.setImageResource(R.drawable.ic_firewall_data_on)
        io {
            appInfoViewModel.updateMeteredStatus(false)
        }
    }

    private fun updateUnmeteredBulk() {
        if (getBulkTagUnmetered()) {
            b.ffaToggleAllWifi.tag = 1
            b.ffaToggleAllWifi.setImageResource(R.drawable.ic_firewall_wifi_off)
            io {
                appInfoViewModel.updateUnmeteredStatus(true)
            }
            return
        }

        b.ffaToggleAllWifi.tag = 0
        b.ffaToggleAllWifi.setImageResource(R.drawable.ic_firewall_wifi_on)
        io {
            appInfoViewModel.updateUnmeteredStatus(false)
        }
    }

    private fun getBulkTagUnmetered(): Boolean {
        return b.ffaToggleAllWifi.tag == 0
    }

    private fun getBulkTagMetered(): Boolean {
        return b.ffaToggleAllMobileData.tag == 0
    }

    private fun initView() {
        initListAdapter()
        b.ffaSearch.setOnQueryTextListener(this)
        addAnimation()
        remakeFirewallChipsUi()
    }

    private fun initListAdapter() {
        b.ffaAppList.setHasFixedSize(true)
        layoutManager = CustomLinearLayoutManager(requireContext())
        b.ffaAppList.layoutManager = layoutManager
        val recyclerAdapter = FirewallAppListAdapter(requireContext(), viewLifecycleOwner)
        appInfoViewModel.appInfo.observe(viewLifecycleOwner) {
            recyclerAdapter.submitData(viewLifecycleOwner.lifecycle, it)
        }
        b.ffaAppList.adapter = recyclerAdapter

    }

    private fun openFilterBottomSheet() {
        val bottomSheetFragment = FirewallAppFilterBottomSheet()
        bottomSheetFragment.show(requireActivity().supportFragmentManager, bottomSheetFragment.tag)
    }

    private fun addAnimation() {
        animation = RotateAnimation(
            ANIMATION_START_DEGREE, ANIMATION_END_DEGREE,
            Animation.RELATIVE_TO_SELF, ANIMATION_PIVOT_VALUE,
            Animation.RELATIVE_TO_SELF, ANIMATION_PIVOT_VALUE
        )
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

    private fun ui(f: () -> Unit) {
        lifecycleScope.launch(Dispatchers.Main) {
            f()
        }
    }
}
