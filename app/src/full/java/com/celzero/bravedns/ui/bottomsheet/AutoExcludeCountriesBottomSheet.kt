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

import Logger
import Logger.LOG_TAG_UI
import android.content.res.Configuration
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.databinding.BottomsheetAutoExcludeCountriesBinding
import com.celzero.bravedns.databinding.ListItemExcludeCountryBinding
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.rpnproxy.RpnProxyManager.AUTO_SERVER_ID
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.bottomsheet.AutoExcludeCountriesBottomSheet.Companion.MIN_AVAILABLE
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Themes.Companion.getBottomSheetCurrentTheme
import com.celzero.bravedns.util.Utilities
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

/**
 * Bottom sheet that lets the user select which countries should be **excluded** from AUTO
 * server selection in RPN.
 *
 * The user must leave at least [MIN_AVAILABLE] countries unexcluded so AUTO can always pick
 * a server.  Attempting to exclude more shows a toast and keeps the item unchecked.
 *
 * On DONE the current exclusion set is persisted in [PersistentState.rpnAutoExcludedCcs] and
 * delivered to the caller via [OnExcludeCountriesChangedListener.onExcludeCountriesChanged].
 */
class AutoExcludeCountriesBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomsheetAutoExcludeCountriesBinding? = null
    private val b
        get() = checkNotNull(_binding) { "Binding accessed outside of view lifecycle" }

    private val persistentState by inject<PersistentState>()

    /** All unique countries derived from the server list, sorted by name. */
    private val allItems = mutableListOf<CountryItem>()

    /** Subset of [allItems] currently visible (filtered by search query). */
    private val filteredItems = mutableListOf<CountryItem>()

    private lateinit var adapter: ExcludeCountryAdapter

    private var loadJob: Job? = null

    /**
     * CSV snapshot of [PersistentState.rpnAutoExcludedCcs] captured the moment the view is
     * created. Used by [onDismiss] to detect whether the exclusion list actually changed so
     * the listener is not fired needlessly on a swipe-away with no edits.
     */
    private var snapshotExcludedCcs: String = ""

    /**
     * Becomes true once [loadCountries] has fully populated [allItems]. Prevents [onDismiss]
     * from firing the callback with an empty list when the user swipes away before the
     * country list finishes loading.
     */
    private var listLoaded: Boolean = false

    /**
     * Set to true by [onDoneTapped] after it has already persisted and fired the callback so
     * that [onDismiss] does not double-fire.
     */
    private var doneHandled: Boolean = false

    companion object {
        const val TAG = "AutoExcludeCountriesBtmSht"

        /**
         * Minimum number of countries that must remain **un-excluded** so AUTO can always
         * find a server to use.
         */
        val MIN_AVAILABLE = if (DEBUG) 1 else 5

        fun newInstance(): AutoExcludeCountriesBottomSheet = AutoExcludeCountriesBottomSheet()
    }

    /**
     * Callback fired when the user confirms the exclusion selection via DONE.
     * [excludedCcCsv] is a comma-separated string of excluded country codes (e.g. "US,DE,FR"),
     * or an empty string if no countries are excluded.
     */
    interface OnExcludeCountriesChangedListener {
        fun onExcludeCountriesChanged(excludedCcCsv: String)
    }

    private var listener: OnExcludeCountriesChangedListener? = null

    /** Attach the result listener; call before [show]. */
    fun setOnExcludeCountriesChangedListener(l: OnExcludeCountriesChangedListener) {
        listener = l
    }

    data class CountryItem(
        val cc: String,
        val countryName: String,
        val flagEmoji: String,
        var isExcluded: Boolean
    )

    private fun isDarkThemeOn(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES

    override fun getTheme(): Int =
        getBottomSheetCurrentTheme(isDarkThemeOn(), persistentState.theme)


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.BottomSheetDialogTheme)
        isCancelable = true
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomsheetAutoExcludeCountriesBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Keep navigation bar transparent / matching app theme on Q+
        dialog?.window?.let { window ->
            Themes.applyBottomSheetSystemBarAppearance(window, isDarkThemeOn(), persistentState.theme)
        }

        // Expand the sheet fully so the list is always visible
        dialog?.setOnShowListener {
            val sheet = dialog?.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            ) ?: return@setOnShowListener
            val behavior = BottomSheetBehavior.from(sheet)
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
        }

        // Snapshot the persisted exclusion CSV before any user edits so onDismiss can
        // detect a real change (mirrors the snapshot pattern in ServerSettingsBottomSheet).
        snapshotExcludedCcs = persistentState.rpnAutoExcludedCcs

        setupRecyclerView()
        setupSearch()
        setupButtons()
        loadCountries()
    }

    override fun onDestroyView() {
        loadJob?.cancel()
        loadJob = null
        listener = null
        _binding = null
        super.onDestroyView()
    }

    /**
     * fires listener callback if the user dismissed the sheet (swipe / back press) **without**
     * tapping DONE but the exclusion list actually changed since the sheet was opened.
     */
    override fun onDismiss(dialog: android.content.DialogInterface) {
        if (!doneHandled && listLoaded) {
            val currentCsv = allItems
                .filter { it.isExcluded }
                .joinToString(",") { it.cc }

            if (currentCsv != snapshotExcludedCcs) {
                persistentState.rpnAutoExcludedCcs = currentCsv
                listener?.onExcludeCountriesChanged(currentCsv)
                Logger.i(LOG_TAG_UI, "$TAG.onDismiss: excluded CCs:$currentCsv")
            }
        }
        super.onDismiss(dialog)
    }

    private fun setupRecyclerView() {
        adapter = ExcludeCountryAdapter()
        b.rvCountries.layoutManager = LinearLayoutManager(requireContext())
        b.rvCountries.adapter = adapter
        b.rvCountries.itemAnimator = null  // disable default item animations for snappy toggling
    }

    private fun setupSearch() {
        b.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyFilter(s?.toString() ?: "")
                updateSearchClearVisibility(s?.isNotEmpty() == true)
            }
        })

        b.btnClearSearch.setOnClickListener {
            b.etSearch.text?.clear()
        }
    }

    private fun updateSearchClearVisibility(show: Boolean) {
        if (!isAdded) return
        if (show && !b.btnClearSearch.isVisible) {
            b.btnClearSearch.visibility = View.VISIBLE
            b.btnClearSearch.animate().alpha(1f).setDuration(150).start()
        } else if (!show && b.btnClearSearch.isVisible) {
            b.btnClearSearch.animate().alpha(0f).setDuration(150)
                .withEndAction { if (isAdded) b.btnClearSearch.visibility = View.INVISIBLE }
                .start()
        }
    }

    private fun setupButtons() {
        b.btnDone.setOnClickListener { onDoneTapped() }
    }

    private fun loadCountries() {
        showLoadingState()

        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val servers = withContext(Dispatchers.IO) {
                try {
                    RpnProxyManager.getWinServers()
                } catch (_: Exception) {
                    emptyList()
                }
            }

            // exclude AUTO, sort alphabetically by country name
            val uniqueCountries = servers
                .filter { it.id != AUTO_SERVER_ID && it.isActive }
                .distinctBy { it.cc }
                .sortedBy { it.countryName.lowercase() }

            val storedExcluded = try {
                persistentState.rpnAutoExcludedCcs
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
            } catch (_: Exception) {
                emptySet()
            }

            val items = uniqueCountries.map { cfg ->
                CountryItem(
                    cc = cfg.cc,
                    countryName = cfg.countryName,
                    flagEmoji = cfg.flagEmoji,
                    isExcluded = cfg.cc in storedExcluded
                )
            }

            if (!isAdded) return@launch
            allItems.clear()
            allItems.addAll(items)
            if (allItems.isEmpty()) {
                showEmptyState()
            } else {
                listLoaded = true
                showListState()
                applyFilter("")
                updateMinInfoBanner()
                updateActionRow()
            }
        }
    }

    private fun applyFilter(query: String) {
        val q = query.trim().lowercase()
        val newList = if (q.isEmpty()) allItems.toList()
                      else allItems.filter { item ->
                          item.countryName.lowercase().contains(q) || item.cc.lowercase().contains(q)
                      }
        filteredItems.clear()
        filteredItems.addAll(newList)
        adapter.notifyDataSetChanged() // safe: full list replacement after filter
    }

    private fun showLoadingState() {
        b.loadingState.isVisible = true
        b.tvEmpty.isVisible = false
        b.rvCountries.isVisible = false
        b.actionRow.isVisible = false
    }

    private fun showEmptyState() {
        b.loadingState.isVisible = false
        b.tvEmpty.isVisible = true
        b.rvCountries.isVisible = false
        b.actionRow.isVisible = false
    }

    private fun showListState() {
        b.loadingState.isVisible = false
        b.tvEmpty.isVisible = false
        b.rvCountries.isVisible = true

        // Animate list in from slightly below
        b.rvCountries.alpha = 0f
        b.rvCountries.translationY = 16f
        b.rvCountries.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(280)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        b.actionRow.isVisible = true
    }

    private fun updateMinInfoBanner() {
        if (!isAdded) return
        b.tvMinInfo.text = getString(R.string.auto_exclude_cc_min_info, MIN_AVAILABLE)
    }

    private fun updateActionRow() {
        if (!isAdded) return
        val excludedCount = allItems.count { it.isExcluded }

        b.tvSelectedCount.text = if (excludedCount == 0) {
            getString(R.string.auto_exclude_cc_none)
        } else {
            getString(R.string.auto_exclude_cc_count, excludedCount)
        }
    }

    private fun onItemToggled(item: CountryItem) {
        if (!item.isExcluded) {
            // User wants to EXCLUDE this country — check the limit
            val currentlyExcluded = allItems.count { it.isExcluded }
            val maxAllowed = allItems.size - MIN_AVAILABLE
            if (maxAllowed <= 0 || currentlyExcluded >= maxAllowed) {
                showToast(getString(R.string.auto_exclude_cc_limit_reached, MIN_AVAILABLE))
                return
            }
            item.isExcluded = true
        } else {
            // User wants to UN-exclude
            item.isExcluded = false
        }
        // refresh all visible rows so alpha is correct for all items
        adapter.notifyDataSetChanged()
        updateActionRow()
    }

    private fun onDoneTapped() {
        val excludedCsv = allItems
            .filter { it.isExcluded }
            .joinToString(",") { it.cc }

        // mark handled first so onDismiss does not re-fire.
        doneHandled = true

        persistentState.rpnAutoExcludedCcs = excludedCsv
        Logger.i(LOG_TAG_UI, "$TAG.onDoneTapped: excluded CCs = $excludedCsv")

        listener?.onExcludeCountriesChanged(excludedCsv)

        dismiss()
    }

    private fun showToast(msg: String) {
        if (isAdded) Utilities.showToastUiCentered(requireContext(), msg, Toast.LENGTH_SHORT)
    }

    // note: this adapter is not optimized for large lists since it calls notifyDataSetChanged()
    // on every toggle, but the country list is small enough that this is not a problem, and
    // it keeps the code simpler.
    inner class ExcludeCountryAdapter :
        RecyclerView.Adapter<ExcludeCountryAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ListItemExcludeCountryBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val itemBinding =
                ListItemExcludeCountryBinding.inflate(inflater, parent, false)
            return ViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = filteredItems[position]
            with(holder.binding) {
                tvFlag.text = item.flagEmoji
                tvCountryName.text = item.countryName
                tvCountryCode.text = item.cc

                // set checkbox state without triggering any listener
                cbExclude.setOnCheckedChangeListener(null)
                cbExclude.isChecked = item.isExcluded

                val excludedCount = allItems.count { it.isExcluded }
                val maxAllowed = allItems.size - MIN_AVAILABLE
                val limitReached = maxAllowed in 1..excludedCount
                val canToggle = item.isExcluded || !limitReached
                root.alpha = if (canToggle) 1f else 0.45f

                root.setOnClickListener { onItemToggled(item) }
            }
        }

        override fun getItemCount(): Int = filteredItems.size
    }
}





