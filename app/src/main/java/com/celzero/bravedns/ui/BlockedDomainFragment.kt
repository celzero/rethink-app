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
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.UniversalBlockedRulesAdapter
import com.celzero.bravedns.database.BlockedConnectionsRepository
import com.celzero.bravedns.databinding.FragmentBlockedDomainBinding
import com.celzero.bravedns.viewmodel.BlockedConnectionsViewModel
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class BlockedDomainFragment : Fragment(R.layout.fragment_blocked_domain),
                              SearchView.OnQueryTextListener {
    private val b by viewBinding(FragmentBlockedDomainBinding::bind)

    private var recyclerRulesAdapter: UniversalBlockedRulesAdapter? = null
    private var layoutManager: RecyclerView.LayoutManager? = null
    private val viewModel: BlockedConnectionsViewModel by viewModel()

    private val blockedConnectionsRepository by inject<BlockedConnectionsRepository>()

    companion object {
        fun newInstance() = BlockedDomainFragment()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        layoutManager = LinearLayoutManager(requireContext())
        b.recyclerBlockedList.layoutManager = layoutManager
        recyclerRulesAdapter = UniversalBlockedRulesAdapter(requireContext(),
                                                            blockedConnectionsRepository)
        b.recyclerBlockedList.adapter = recyclerRulesAdapter

        viewModel.blockedUnivRulesList.observe(viewLifecycleOwner, androidx.lifecycle.Observer(
            recyclerRulesAdapter!!::submitList))

        b.blockedSearchView.setOnQueryTextListener(this)
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        viewModel.setFilter(query!!)
        return true
    }

    override fun onQueryTextChange(query: String?): Boolean {
        viewModel.setFilter(query!!)
        return true
    }
}
