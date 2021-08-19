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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.DNSQueryAdapter
import com.celzero.bravedns.data.AppMode
import com.celzero.bravedns.database.DNSLogDAO
import com.celzero.bravedns.databinding.ActivityQueryDetailBinding
import com.celzero.bravedns.databinding.QueryListScrollListBinding
import com.celzero.bravedns.glide.GlideApp
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Constants.Companion.PREF_DNS_MODE_DNSCRYPT
import com.celzero.bravedns.util.Constants.Companion.PREF_DNS_MODE_DOH
import com.celzero.bravedns.viewmodel.DNSLogViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.util.*

class DNSLogFragment : Fragment(R.layout.activity_query_detail), SearchView.OnQueryTextListener {
    private val b by viewBinding(ActivityQueryDetailBinding::bind)

    private var layoutManager: RecyclerView.LayoutManager? = null

    private val viewModel: DNSLogViewModel by viewModel()
    private var checkedItem = 1
    private var filterValue: String = ""

    private val dnsLogDAO by inject<DNSLogDAO>()
    private val persistentState by inject<PersistentState>()
    private val appMode by inject<AppMode>()

    companion object {
        fun newInstance() = DNSLogFragment()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
    }

    private fun initView() {
        val includeView = b.queryListScrollList

        if (!persistentState.logsEnabled) {
            includeView.queryListLogsDisabledTv.visibility = View.VISIBLE
            includeView.queryListCardViewTop.visibility = View.GONE
            return
        }

        displayPerDnsUi(includeView)
        setupClickListeners(includeView)
    }

    private fun setupClickListeners(includeView: QueryListScrollListBinding) {
        includeView.queryListSearch.setOnQueryTextListener(this)
        includeView.queryListSearch.setOnClickListener {
            includeView.queryListSearch.requestFocus()
            includeView.queryListSearch.onActionViewExpanded()
        }

        includeView.queryListFilterIcon.setOnClickListener {
            showDnsLogsFilterDialog()
        }

        includeView.queryListDeleteIcon.setOnClickListener {
            showDnsLogsDeleteDialog()
        }
    }

    private fun displayPerDnsUi(includeView: QueryListScrollListBinding) {
        includeView.queryListLogsDisabledTv.visibility = View.GONE
        includeView.queryListCardViewTop.visibility = View.VISIBLE
        includeView.recyclerQuery.setHasFixedSize(true)
        layoutManager = LinearLayoutManager(requireContext())
        includeView.recyclerQuery.layoutManager = layoutManager

        val recyclerAdapter = DNSQueryAdapter(requireContext(), persistentState.fetchFavIcon)
        viewModel.dnsLogsList.observe(viewLifecycleOwner,
                                      androidx.lifecycle.Observer(recyclerAdapter::submitList))
        includeView.recyclerQuery.adapter = recyclerAdapter
    }

    private fun observeDnsStats() {
        persistentState.dnsRequestsCountLiveData.observe(viewLifecycleOwner, {
            val lifeTimeConversion = formatDecimal(it)
            b.totalQueriesTxt.text = getString(R.string.dns_logs_lifetime_queries,
                                               lifeTimeConversion)
        })

        persistentState.dnsBlockedCountLiveData.observe(viewLifecycleOwner, {
            val blocked = formatDecimal(it)
            b.latencyTxt.text = getString(R.string.dns_logs_blocked_queries, blocked)
        })

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
        observeDnsStats()
    }

    private fun updateConnectedStatus() {
        val dnsType = appMode.getDnsType()
        if (dnsType == PREF_DNS_MODE_DOH) {
            b.connectedStatusTitleUrl.text = resources.getString(
                R.string.configure_dns_connected_doh_status)
            b.connectedStatusTitle.text = resources.getString(
                R.string.configure_dns_connection_name, appMode.getConnectedDNS())
            b.queryListScrollList.recyclerQuery.visibility = View.VISIBLE
            b.queryListScrollList.dnsLogNoLogText.visibility = View.GONE
        } else if (dnsType == PREF_DNS_MODE_DNSCRYPT) {
            observeCryptStatus()
            b.connectedStatusTitleUrl.text = resources.getString(
                R.string.configure_dns_connected_dns_crypt_status)
            b.queryListScrollList.recyclerQuery.visibility = View.VISIBLE
            b.queryListScrollList.dnsLogNoLogText.visibility = View.GONE
        } else {
            b.connectedStatusTitleUrl.text = resources.getString(
                R.string.configure_dns_connected_dns_proxy_status)
            b.connectedStatusTitle.text = resources.getString(
                R.string.configure_dns_connection_name, appMode.getConnectedDNS())
            b.queryListScrollList.recyclerQuery.visibility = View.GONE
            if (persistentState.logsEnabled) {
                b.queryListScrollList.dnsLogNoLogText.visibility = View.VISIBLE
            } else {
                /* No op */
                // As of now, the logs are not shown when the DNS mode is in DNSProxy.
                // So when pxoxy mode is changed, only onResume() will be called as it
                // is sharing the same activity. (The proxy mode changes done in the
                // ConfigureDNSFragment). Other logs enabled states are handled in onViewCreated().
            }
        }
    }

    private fun observeCryptStatus() {
        appMode.getDnscryptCountObserver().observe(viewLifecycleOwner, {
            val connectedCrypt = getString(R.string.configure_dns_crypt, it.toString())
            b.connectedStatusTitle.text = connectedCrypt
        })
    }

    private fun showDnsLogsFilterDialog() {
        val items = arrayOf(getString(R.string.filter_dns_blocked_connections),
                            getString(R.string.filter_dns_all_connections))

        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle(getString(R.string.dns_log_dialog_title))

        // Single-choice items (initialized with checked item)
        builder.setSingleChoiceItems(items, checkedItem) { dialog, which ->
            // Respond to item chosen
            // FIXME - Remove the isFilter constants with introduction of new filter options.
            filterValue = if (which == 0) ":isFilter"
            else ""
            checkedItem = which
            viewModel.setFilterBlocked(filterValue)
            dialog.dismiss()
        }
        builder.show()
    }


    private fun showDnsLogsDeleteDialog() {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle(R.string.dns_query_clear_logs_title)
        builder.setMessage(R.string.dns_query_clear_logs_message)
        builder.setCancelable(true)
        builder.setPositiveButton(getString(R.string.dns_log_dialog_positive)) { _, _ ->
            GlobalScope.launch(Dispatchers.IO) {
                GlideApp.get(requireActivity()).clearDiskCache()
                dnsLogDAO.clearAllData()
            }
        }
        builder.setNegativeButton(getString(R.string.dns_log_dialog_negative)) { _, _ -> }
        builder.create().show()
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        viewModel.setFilter(query, filterValue)
        return true
    }

    override fun onQueryTextChange(query: String?): Boolean {
        viewModel.setFilter(query, filterValue)
        return true
    }
}
