/*
Copyright 2022 RethinkDNS and its authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.celzero.bravedns.ui

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.adapter.RethinkEndpointAdapter
import com.celzero.bravedns.databinding.BottomSheetRethinkListBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.viewmodel.RethinkEndpointViewModel
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class RethinkListBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetRethinkListBinding? = null

    // This property is only valid between onCreateView and onDestroyView.
    private val b
        get() = _binding!!
    private val persistentState by inject<PersistentState>()

    private var layoutManager: RecyclerView.LayoutManager? = null
    private var recyclerAdapter: RethinkEndpointAdapter? = null
    private val viewModel: RethinkEndpointViewModel by viewModel()

    private var filter: Int = 1

    override fun getTheme(): Int =
        Themes.getBottomsheetCurrentTheme(isDarkThemeOn(), persistentState.theme)

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetRethinkListBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        initClickListeners()
    }

    private fun initView() {
        layoutManager = LinearLayoutManager(requireContext())
        b.bsrRethinkListRecycler.layoutManager = layoutManager

        recyclerAdapter = RethinkEndpointAdapter(requireContext(), viewLifecycleOwner, get())
        viewModel.setFilter(filter)
        viewModel.rethinkEndpointList.observe(viewLifecycleOwner) {
            recyclerAdapter!!.submitData(viewLifecycleOwner.lifecycle, it)
        }
        b.bsrRethinkListRecycler.adapter = recyclerAdapter
    }

    private fun initClickListeners() {
        b.bsrConfigure.setOnClickListener { openRethinkBasicActivity() }
    }

    private fun openRethinkBasicActivity() {
        val intent = Intent(requireContext(), ConfigureRethinkBasicActivity::class.java)
        intent.putExtra(
            ConfigureRethinkBasicActivity.INTENT,
            ConfigureRethinkBasicActivity.FragmentLoader.REMOTE.ordinal
        )
        startActivity(intent)
    }
}
