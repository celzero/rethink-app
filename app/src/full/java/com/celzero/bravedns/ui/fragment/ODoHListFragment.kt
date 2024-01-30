/*
 * Copyright 2023 RethinkDNS and its authors
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

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.ODoHEndpointAdapter
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.ODoHEndpoint
import com.celzero.bravedns.databinding.DialogSetCustomOdohBinding
import com.celzero.bravedns.databinding.FragmentOdohListBinding
import com.celzero.bravedns.viewmodel.ODoHEndpointViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.net.MalformedURLException
import java.net.URL

class ODoHListFragment : Fragment(R.layout.fragment_odoh_list) {
    private val b by viewBinding(FragmentOdohListBinding::bind)

    private val appConfig by inject<AppConfig>()

    private var layoutManager: RecyclerView.LayoutManager? = null
    private var adapter: ODoHEndpointAdapter? = null
    private val viewModel: ODoHEndpointViewModel by viewModel()

    companion object {
        fun newInstance() = ODoHListFragment()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        initClickListeners()
    }

    private fun initView() {
        layoutManager = LinearLayoutManager(requireContext())
        b.recyclerOdoh.layoutManager = layoutManager

        adapter = ODoHEndpointAdapter(requireContext(), viewLifecycleOwner, get())
        viewModel.dohEndpointList.observe(viewLifecycleOwner) {
            adapter!!.submitData(viewLifecycleOwner.lifecycle, it)
        }
        b.recyclerOdoh.adapter = adapter
    }

    private fun initClickListeners() {
        b.odohFabAdd.setOnClickListener { showAddDialog() }
    }

    private fun showAddDialog() {
        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setTitle(getString(R.string.cd_custom_doh_dialog_title))
        val dialogBinding = DialogSetCustomOdohBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        val lp = WindowManager.LayoutParams()
        lp.copyFrom(dialog.window?.attributes)
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        dialog.show()
        dialog.setCancelable(true)
        dialog.window?.attributes = lp

        val heading = dialogBinding.dialogCustomUrlTop
        val applyURLBtn = dialogBinding.dialogCustomUrlOkBtn
        val cancelURLBtn = dialogBinding.dialogCustomUrlCancelBtn
        val customName = dialogBinding.dialogCustomNameEditText
        val customProxy = dialogBinding.dialogCustomProxyEditText
        val customResolver = dialogBinding.dialogCustomResolverEditText
        val progressBar = dialogBinding.dialogCustomUrlLoading
        val errorTxt = dialogBinding.dialogCustomUrlFailureText

        heading.text = getString(R.string.lbl_add) + " " + getString(R.string.lbl_odoh)
        // fetch the count from repository and increment by 1 to show the
        // next doh name in the dialog
        io {
            val nextIndex = appConfig.getODoHCount().plus(1)
            uiCtx {
                customName.setText(
                    getString(R.string.lbl_odoh) + nextIndex.toString(),
                    TextView.BufferType.EDITABLE
                )
            }
        }

        customName.setText(getString(R.string.lbl_odoh), TextView.BufferType.EDITABLE)
        applyURLBtn.setOnClickListener {
            val proxy = customProxy.text.toString()
            val resolver = customResolver.text.toString()
            val name = customName.text.toString()

            // check if the url is valid for resolver, proxy is optional
            if (checkUrl(resolver)) {
                insert(name, proxy, resolver)
                dialog.dismiss()
            } else {
                errorTxt.text = resources.getString(R.string.custom_url_error_invalid_url)
                errorTxt.visibility = View.VISIBLE
                cancelURLBtn.visibility = View.VISIBLE
                applyURLBtn.visibility = View.VISIBLE
                progressBar.visibility = View.INVISIBLE
            }
        }

        cancelURLBtn.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    // Check that the URL is a plausible DOH server: https with a domain, a path (at least "/"),
    // and no query parameters or fragment.
    private fun checkUrl(url: String): Boolean {
        return try {
            val parsed = URL(url)
            parsed.protocol == "https" &&
                parsed.host.isNotEmpty() &&
                parsed.path.isNotEmpty() &&
                parsed.query == null &&
                parsed.ref == null
        } catch (e: MalformedURLException) {
            false
        }
    }

    private fun insert(name: String, proxy: String, resolver: String) {
        io {
            var odohName: String = name
            if (name.isBlank()) {
                odohName = resolver
            }
            val endpoint =
                ODoHEndpoint(
                    id = 0,
                    odohName,
                    proxy,
                    resolver,
                    proxyIps = "",
                    desc = "",
                    isSelected = false,
                    isCustom = true,
                    modifiedDataTime = 0,
                    latency = 0
                )
            appConfig.insertODoHEndpoint(endpoint)
        }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch { withContext(Dispatchers.IO) { f() } }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }
}
