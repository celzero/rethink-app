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
package com.celzero.bravedns.ui.fragment

import android.content.res.Resources
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.SmartDnsEndpointAdapter
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.databinding.FragmentSmartDnsListBinding
import com.celzero.bravedns.util.RecyclerViewSpacingDecoration
import com.celzero.bravedns.viewmodel.SmartDnsEndpointViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class SmartDnsListFragment : Fragment(R.layout.fragment_smart_dns_list) {
    private val b by viewBinding(FragmentSmartDnsListBinding::bind)

    private val appConfig by inject<AppConfig>()
    private val viewModel: SmartDnsEndpointViewModel by viewModel()

    private var layoutManager: RecyclerView.LayoutManager? = null
    private var smartDnsAdapter: SmartDnsEndpointAdapter? = null

    companion object {
        fun newInstance() = SmartDnsListFragment()

        private val dpToPx: Float by lazy {
            Resources.getSystem().displayMetrics.density
        }

        private val spacing4dp: Int by lazy { (4 * dpToPx).toInt() }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        applyEdgeToEdge()
    }

    private fun initView() {
        layoutManager = LinearLayoutManager(requireContext())
        b.recyclerSmartDnsList.layoutManager = layoutManager
        b.recyclerSmartDnsList.addItemDecoration(
            RecyclerViewSpacingDecoration(spacing4dp, spacing4dp)
        )

        smartDnsAdapter =
            SmartDnsEndpointAdapter(requireContext()) { appConfig.isSmartDnsEnabled() }
        smartDnsAdapter?.onEndpointSelected = { endpoint ->
            io { appConfig.enableSmartDns(endpoint.id) }
        }
        b.recyclerSmartDnsList.adapter = smartDnsAdapter

        viewModel.smartDnsEndpointList.observe(viewLifecycleOwner) {
            smartDnsAdapter?.submitList(it)
            b.smartDnsEmptyState.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun applyEdgeToEdge() {
        ViewCompat.setOnApplyWindowInsetsListener(b.recyclerSmartDnsList) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                v.paddingLeft,
                v.paddingTop,
                v.paddingRight,
                v.paddingBottom + systemBars.bottom
            )
            insets
        }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) {
            if (isAdded && view != null) {
                f()
            }
        }
    }
}
