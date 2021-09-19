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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.DoHEndpointAdapter
import com.celzero.bravedns.databinding.FragmentDohListBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.viewmodel.DoHEndpointViewModel
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class DOHListFragment : Fragment(R.layout.fragment_doh_list) {
    private val b by viewBinding(FragmentDohListBinding::bind)

    //Doh UI elements
    private var layoutManager: RecyclerView.LayoutManager? = null
    private var dohRecyclerAdapter: DoHEndpointAdapter? = null
    private val viewModel: DoHEndpointViewModel by viewModel()
    private val persistentState by inject<PersistentState>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        return super.onCreateView(inflater, container, savedInstanceState)
    }

    companion object {
        fun newInstance() = DOHListFragment()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        layoutManager = LinearLayoutManager(requireContext())
        b.recyclerDohConnections.layoutManager = layoutManager

        dohRecyclerAdapter = DoHEndpointAdapter(requireContext(), viewLifecycleOwner, get())
        viewModel.dohEndpointList.observe(viewLifecycleOwner, androidx.lifecycle.Observer(
            dohRecyclerAdapter!!::submitList))
        b.recyclerDohConnections.adapter = dohRecyclerAdapter
    }

}
