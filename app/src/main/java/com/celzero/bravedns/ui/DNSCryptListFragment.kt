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
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.DNSCryptEndpointAdapter
import com.celzero.bravedns.adapter.DNSCryptRelayEndpointAdapter
import com.celzero.bravedns.databinding.FragmentDnsCryptListBinding
import com.celzero.bravedns.viewmodel.DNSCryptEndpointViewModel
import com.celzero.bravedns.viewmodel.DNSCryptRelayEndpointViewModel
import org.koin.android.ext.android.get
import org.koin.androidx.viewmodel.ext.android.viewModel

class DNSCryptListFragment : Fragment(R.layout.fragment_dns_crypt_list) {
    private val b by viewBinding(FragmentDnsCryptListBinding::bind)

    // Dnscrypt UI elements
    private lateinit var dnsCryptRecyclerAdapter: DNSCryptEndpointAdapter
    private var dnsCryptLayoutManager: RecyclerView.LayoutManager? = null
    private val dnsCryptViewModel: DNSCryptEndpointViewModel by viewModel()

    // Dnscrypt relay UI elements
    private lateinit var dnsCryptRelayRecyclerAdapter: DNSCryptRelayEndpointAdapter
    private var dnsCryptRelayLayoutManager: RecyclerView.LayoutManager? = null
    private val dnsCryptRelayViewModel: DNSCryptRelayEndpointViewModel by viewModel()

    companion object {
        fun newInstance() = DNSCryptListFragment()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Dnscrypt init views
        dnsCryptLayoutManager = LinearLayoutManager(requireContext())
        b.recyclerDnsCryptConnections.layoutManager = dnsCryptLayoutManager

        // Dnscrypt relay init views
        dnsCryptRelayLayoutManager = LinearLayoutManager(requireContext())
        b.recyclerDnsCryptRelays.layoutManager = dnsCryptRelayLayoutManager

        dnsCryptRecyclerAdapter = DNSCryptEndpointAdapter(requireContext(), get())
        dnsCryptViewModel.dnsCryptEndpointList.observe(viewLifecycleOwner,
                                                       androidx.lifecycle.Observer(
                                                           dnsCryptRecyclerAdapter::submitList))
        b.recyclerDnsCryptConnections.adapter = dnsCryptRecyclerAdapter

        dnsCryptRelayRecyclerAdapter = DNSCryptRelayEndpointAdapter(requireContext(), get())
        dnsCryptRelayViewModel.dnsCryptRelayEndpointList.observe(viewLifecycleOwner,
                                                                 androidx.lifecycle.Observer(
                                                                     dnsCryptRelayRecyclerAdapter::submitList))
        b.recyclerDnsCryptRelays.adapter = dnsCryptRelayRecyclerAdapter

    }
}
