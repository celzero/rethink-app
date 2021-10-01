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

import android.os.Bundle
import android.view.View
import android.widget.CompoundButton
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.CustomDomainAdapter
import com.celzero.bravedns.automaton.CustomDomainManager
import com.celzero.bravedns.databinding.FragmentCustomDomainBinding
import com.celzero.bravedns.viewmodel.CustomDomainViewModel
import com.google.android.material.chip.Chip
import org.koin.androidx.viewmodel.ext.android.viewModel

class CustomDomainFragment : Fragment(R.layout.fragment_custom_domain),
                             SearchView.OnQueryTextListener {
    private val b by viewBinding(FragmentCustomDomainBinding::bind)

    private var recyclerRulesAdapter: CustomDomainAdapter? = null
    private var layoutManager: RecyclerView.LayoutManager? = null
    private val viewModel: CustomDomainViewModel by viewModel()

    private var filterType = CustomDomainManager.CustomDomainStatus.NONE
    private var filterQuery: String = ""

    companion object {
        fun newInstance() = CustomDomainFragment()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        layoutManager = LinearLayoutManager(requireContext())
        b.cdfRecycler.layoutManager = layoutManager
        recyclerRulesAdapter = CustomDomainAdapter(requireContext(), viewLifecycleOwner)
        b.cdfRecycler.adapter = recyclerRulesAdapter

        viewModel.blockedUnivRulesList.observe(viewLifecycleOwner, androidx.lifecycle.Observer(
            recyclerRulesAdapter!!::submitList))

        b.cdfSearchFilterIcon.setOnClickListener {
            toggleChipsUi()
        }

        b.cdfSearchView.setOnClickListener {
            showChipsUi()
        }

        b.cdfSearchView.setOnQueryTextListener(this)
        remakeParentFilterChipsUi()
    }

    private fun toggleChipsUi() {
        if (b.cdfFilterChipGroup.isVisible) {
            hideChipsUi()
        } else {
            showChipsUi()
        }
    }

    private fun hideChipsUi() {
        b.cdfFilterChipGroup.visibility = View.GONE
    }

    private fun showChipsUi() {
        b.cdfFilterChipGroup.visibility = View.VISIBLE
    }

    override fun onQueryTextSubmit(query: String): Boolean {
        filterQuery = query
        viewModel.setFilter(query, filterType)
        return true
    }

    override fun onQueryTextChange(query: String): Boolean {
        filterQuery = query
        viewModel.setFilter(query, filterType)
        return true
    }

    private fun remakeParentFilterChipsUi() {
        b.cdfFilterChipGroup.removeAllViews()

        val all = makeParentChip(CustomDomainManager.CustomDomainStatus.NONE.statusId,
                                         getString(R.string.cb_filter_all), true)
        val allowed = makeParentChip(CustomDomainManager.CustomDomainStatus.WHITELIST.statusId,
                                     getString(R.string.cb_filter_allowed), false)
        val blocked = makeParentChip(CustomDomainManager.CustomDomainStatus.BLOCKLIST.statusId,
                                     getString(R.string.cb_filter_blocked), false)

        b.cdfFilterChipGroup.addView(all)
        b.cdfFilterChipGroup.addView(allowed)
        b.cdfFilterChipGroup.addView(blocked)
    }

    private fun makeParentChip(id: Int, label: String, checked: Boolean): Chip {
        val chip = this.layoutInflater.inflate(R.layout.item_chip_filter, null, false) as Chip
        chip.tag = id
        chip.text = label
        chip.isChecked = checked

        chip.setOnCheckedChangeListener { button: CompoundButton, isSelected: Boolean ->
            if (isSelected) { // apply filter only when the CompoundButton is selected
                applyParentFilter(button.tag)
            }
        }

        return chip
    }

    private fun applyParentFilter(tag: Any) {
        when (tag) {
            CustomDomainManager.CustomDomainStatus.NONE.statusId -> {
                filterType = CustomDomainManager.CustomDomainStatus.NONE
                viewModel.setFilter(filterQuery, filterType)
            }
            CustomDomainManager.CustomDomainStatus.WHITELIST.statusId -> {
                filterType = CustomDomainManager.CustomDomainStatus.WHITELIST
                viewModel.setFilter(filterQuery, filterType)
            }
            CustomDomainManager.CustomDomainStatus.BLOCKLIST.statusId -> {
                filterType = CustomDomainManager.CustomDomainStatus.BLOCKLIST
                viewModel.setFilter(filterQuery,  filterType)
            }
        }
    }
}
