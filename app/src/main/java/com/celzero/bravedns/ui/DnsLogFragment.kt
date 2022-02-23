/*
 * Copyright 2020 RethinkDNS and its authors
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

import android.icu.text.CompactDecimalFormat
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.CompoundButton
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.DnsQueryAdapter
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.DnsLogRepository
import com.celzero.bravedns.databinding.ActivityQueryDetailBinding
import com.celzero.bravedns.databinding.QueryListScrollListBinding
import com.celzero.bravedns.glide.GlideApp
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.CustomLinearLayoutManager
import com.celzero.bravedns.util.Utilities.Companion.formatToRelativeTime
import com.celzero.bravedns.viewmodel.DnsLogViewModel
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.util.*

class DnsLogFragment : Fragment(R.layout.activity_query_detail), SearchView.OnQueryTextListener {
    private val b by viewBinding(ActivityQueryDetailBinding::bind)
    private lateinit var includeView: QueryListScrollListBinding

    private var layoutManager: RecyclerView.LayoutManager? = null

    private val viewModel: DnsLogViewModel by viewModel()
    private var filterValue: String = ""
    private var filterType = DnsLogFilter.ALL

    private val dnsLogRepository by inject<DnsLogRepository>()
    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()

    companion object {
        fun newInstance() = DnsLogFragment()
    }

    enum class DnsLogFilter(val id: Int) {
        ALL(0), ALLOWED(1), BLOCKED(2)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
    }

    private fun initView() {
        includeView = b.queryListScrollView

        if (!persistentState.logsEnabled) {
            includeView.queryListLogsDisabledTv.visibility = View.VISIBLE
            includeView.queryListCardViewTop.visibility = View.GONE
            return
        }

        displayPerDnsUi(includeView)
        setupClickListeners(includeView)
        remakeFilterChipsUi()
        observeDnsStats()
        observeDnscryptStatus()
    }

    private fun setupClickListeners(includeView: QueryListScrollListBinding) {
        includeView.queryListSearch.setOnQueryTextListener(this)
        includeView.queryListSearch.setOnClickListener {
            showChipsUi()
            includeView.queryListSearch.requestFocus()
            includeView.queryListSearch.onActionViewExpanded()
        }

        includeView.queryListFilterIcon.setOnClickListener {
            toggleChipsUi()
        }

        includeView.queryListDeleteIcon.setOnClickListener {
            showDnsLogsDeleteDialog()
        }
    }

    private fun displayPerDnsUi(includeView: QueryListScrollListBinding) {
        includeView.queryListLogsDisabledTv.visibility = View.GONE
        includeView.queryListCardViewTop.visibility = View.VISIBLE
        includeView.recyclerQuery.setHasFixedSize(true)
        layoutManager = CustomLinearLayoutManager(requireContext())
        includeView.recyclerQuery.layoutManager = layoutManager

        val recyclerAdapter = DnsQueryAdapter(requireContext(), persistentState.fetchFavIcon)
        viewModel.dnsLogsList.observe(viewLifecycleOwner,
                                      androidx.lifecycle.Observer(recyclerAdapter::submitList))
        includeView.recyclerQuery.adapter = recyclerAdapter

        val scrollListener = object : RecyclerView.OnScrollListener() {

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)

                if (recyclerView.getChildAt(0).tag == null) return

                val tag: Long = recyclerView.getChildAt(0).tag as Long

                if (dy > 0) {
                    includeView.queryListRecyclerScrollHeader.text = formatToRelativeTime(tag)
                    includeView.queryListRecyclerScrollHeader.visibility = View.VISIBLE
                } else {
                    includeView.queryListRecyclerScrollHeader.visibility = View.GONE
                }
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    includeView.queryListRecyclerScrollHeader.visibility = View.GONE
                }
            }
        }
        includeView.recyclerQuery.addOnScrollListener(scrollListener)

    }

    private fun observeDnsStats() {
        persistentState.dnsRequestsCountLiveData.observe(viewLifecycleOwner) {
            val lifeTimeConversion = formatDecimal(it)
            b.totalQueriesTxt.text = getString(R.string.dns_logs_lifetime_queries,
                                               lifeTimeConversion)
        }

        persistentState.dnsBlockedCountLiveData.observe(viewLifecycleOwner) {
            val blocked = formatDecimal(it)
            b.latencyTxt.text = getString(R.string.dns_logs_blocked_queries, blocked)
        }

    }

    private fun formatDecimal(i: Long?): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            CompactDecimalFormat.getInstance(Locale.US,
                                             CompactDecimalFormat.CompactStyle.SHORT).format(i)
        } else {
            i.toString()
        }
    }

    override fun onResume() {
        super.onResume()
        updateConnectedStatus()
    }

    private fun updateConnectedStatus() {
        when (appConfig.getDnsType()) {
            AppConfig.DnsType.DOH -> {
                b.connectedStatusTitleUrl.text = resources.getString(
                    R.string.configure_dns_connected_doh_status)
                b.connectedStatusTitle.text = resources.getString(
                    R.string.configure_dns_connection_name, appConfig.getConnectedDns())
                b.queryListScrollView.recyclerQuery.visibility = View.VISIBLE
                b.queryListScrollView.dnsLogNoLogText.visibility = View.GONE
            }
            AppConfig.DnsType.DNSCRYPT -> {
                b.connectedStatusTitleUrl.text = resources.getString(
                    R.string.configure_dns_connected_dns_crypt_status)
                b.queryListScrollView.recyclerQuery.visibility = View.VISIBLE
                b.queryListScrollView.dnsLogNoLogText.visibility = View.GONE
            }
            AppConfig.DnsType.DNS_PROXY -> {
                b.connectedStatusTitleUrl.text = resources.getString(
                    R.string.configure_dns_connected_dns_proxy_status)
                b.connectedStatusTitle.text = resources.getString(
                    R.string.configure_dns_connection_name, appConfig.getConnectedDns())
                b.queryListScrollView.recyclerQuery.visibility = View.GONE
                if (persistentState.logsEnabled) {
                    b.queryListScrollView.dnsLogNoLogText.visibility = View.VISIBLE
                } else {
                    /* No op */
                    // As of now, the logs are not shown when the DNS mode is in DNSProxy.
                    // So when proxy mode is changed, only onResume() will be called as it
                    // is sharing the same activity. (The proxy mode changes done in the
                    // ConfigureDNSFragment). Other logs enabled states are handled in onViewCreated().
                }
            }
        }
    }

    // FIXME: Create common observer for dns instead of separate observers
    private fun observeDnscryptStatus() {
        appConfig.getDnscryptCountObserver().observe(viewLifecycleOwner) {
            if (appConfig.getDnsType() != AppConfig.DnsType.DNSCRYPT) return@observe

            val connectedCrypt = getString(R.string.configure_dns_crypt, it.toString())
            b.connectedStatusTitle.text = connectedCrypt
        }
    }

    private fun remakeFilterChipsUi() {
        includeView.filterChipGroup.removeAllViews()

        val all = makeChip(DnsLogFilter.ALL.id, getString(R.string.dns_filter_parent_all), true)
        val allowed = makeChip(DnsLogFilter.ALLOWED.id,
                               getString(R.string.dns_filter_parent_allowed), false)
        val blocked = makeChip(ConnectionTrackerFragment.TopLevelFilter.BLOCKED.id,
                               getString(R.string.dns_filter_parent_blocked), false)

        includeView.filterChipGroup.addView(all)
        includeView.filterChipGroup.addView(allowed)
        includeView.filterChipGroup.addView(blocked)
    }

    private fun makeChip(id: Int, label: String, checked: Boolean): Chip {
        val chip = this.layoutInflater.inflate(R.layout.item_chip_filter, b.root, false) as Chip
        chip.tag = id
        chip.text = label
        chip.isChecked = checked

        chip.setOnCheckedChangeListener { button: CompoundButton, isSelected: Boolean ->
            if (isSelected) { // apply filter only when the CompoundButton is selected
                applyFilter(button.tag)
            }
        }

        return chip
    }

    private fun toggleChipsUi() {
        if (includeView.filterChipGroup.isVisible) {
            hideChipsUi()
        } else {
            showChipsUi()
        }
    }

    private fun applyFilter(tag: Any) {
        when (tag) {
            DnsLogFilter.ALL.id -> {
                filterType = DnsLogFilter.ALL
                viewModel.setFilter(filterValue, filterType)
            }
            DnsLogFilter.ALLOWED.id -> {
                filterType = DnsLogFilter.ALLOWED
                viewModel.setFilter(filterValue, filterType)
            }
            DnsLogFilter.BLOCKED.id -> {
                filterType = DnsLogFilter.BLOCKED
                viewModel.setFilter(filterValue, filterType)
            }
        }
    }

    private fun showChipsUi() {
        includeView.filterChipGroup.visibility = View.VISIBLE
    }

    private fun hideChipsUi() {
        includeView.filterChipGroup.visibility = View.GONE
    }

    private fun showDnsLogsDeleteDialog() {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle(R.string.dns_query_clear_logs_title)
        builder.setMessage(R.string.dns_query_clear_logs_message)
        builder.setCancelable(true)
        builder.setPositiveButton(getString(R.string.dns_log_dialog_positive)) { _, _ ->
            go {
                withContext(Dispatchers.IO) {
                    GlideApp.get(requireActivity()).clearDiskCache()
                }
                dnsLogRepository.clearAllData()
            }
        }
        builder.setNegativeButton(getString(R.string.dns_log_dialog_negative)) { _, _ -> }
        builder.create().show()
    }

    override fun onQueryTextSubmit(query: String): Boolean {
        this.filterValue = query
        viewModel.setFilter(filterValue, filterType)
        return true
    }

    override fun onQueryTextChange(query: String): Boolean {
        this.filterValue = query
        viewModel.setFilter(filterValue, filterType)
        return true
    }

    private fun go(f: suspend () -> Unit) {
        lifecycleScope.launch {
            f()
        }
    }
}
