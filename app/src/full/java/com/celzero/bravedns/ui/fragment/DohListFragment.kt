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
package com.celzero.bravedns.ui.fragment

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.DohEndpointAdapter
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.DoHEndpoint
import com.celzero.bravedns.databinding.DialogSetCustomDohBinding
import com.celzero.bravedns.databinding.FragmentDohListBinding
import com.celzero.bravedns.viewmodel.DoHEndpointViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.net.MalformedURLException
import java.net.URL

class DohListFragment : Fragment(R.layout.fragment_doh_list) {
    private val b by viewBinding(FragmentDohListBinding::bind)

    private val appConfig by inject<AppConfig>()

    // Doh UI elements
    private var layoutManager: RecyclerView.LayoutManager? = null
    private var dohRecyclerAdapter: DohEndpointAdapter? = null
    private val viewModel: DoHEndpointViewModel by viewModel()

    companion object {
        fun newInstance() = DohListFragment()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        initClickListeners()
    }

    private fun initView() {
        layoutManager = LinearLayoutManager(requireContext())
        b.recyclerDohConnections.layoutManager = layoutManager

        dohRecyclerAdapter = DohEndpointAdapter(requireContext(), get())
        viewModel.dohEndpointList.observe(viewLifecycleOwner) {
            dohRecyclerAdapter!!.submitData(viewLifecycleOwner.lifecycle, it)
        }
        b.recyclerDohConnections.adapter = dohRecyclerAdapter
    }

    private fun initClickListeners() {
        // see CustomIpFragment#setupClickListeners#bringToFront()
        b.dohFabAddServerIcon.bringToFront()
        b.dohFabAddServerIcon.setOnClickListener { showAddCustomDohDialog() }
    }

    /**
     * Shows dialog for custom DNS endpoint configuration If entered DNS end point is valid, then
     * the DNS queries are forwarded to that end point else, it will revert back to default end
     * point
     */
    private fun showAddCustomDohDialog() {
        val dialogBinding = DialogSetCustomDohBinding.inflate(layoutInflater)
        val builder = MaterialAlertDialogBuilder(requireContext()).setView(dialogBinding.root)
        val lp = WindowManager.LayoutParams()
        val dialog = builder.create()
        dialog.show()
        lp.copyFrom(dialog.window?.attributes)
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT

        dialog.setCancelable(true)
        dialog.window?.attributes = lp

        val heading = dialogBinding.dialogCustomUrlTop
        val applyURLBtn = dialogBinding.dialogCustomUrlOkBtn
        val cancelURLBtn = dialogBinding.dialogCustomUrlCancelBtn
        val customName = dialogBinding.dialogCustomNameEditText
        val customURL = dialogBinding.dialogCustomUrlEditText
        val progressBar = dialogBinding.dialogCustomUrlLoading
        val errorTxt = dialogBinding.dialogCustomUrlFailureText
        val checkBox = dialogBinding.dialogSecureCheckbox

        heading.text = getString(R.string.cd_doh_dialog_heading)

        // fetch the count from repository and increment by 1 to show the
        // next doh name in the dialog
        io {
            val nextIndex = appConfig.getDohCount().plus(1)
            uiCtx {
                customName.setText(
                    getString(R.string.cd_custom_doh_url_name, nextIndex.toString()),
                    TextView.BufferType.EDITABLE
                )
            }
        }

        customName.setText(
            getString(R.string.cd_custom_doh_url_name_default),
            TextView.BufferType.EDITABLE
        )
        applyURLBtn.setOnClickListener {
            val url = customURL.text.toString()
            val name = customName.text.toString()
            val isSecure = !checkBox.isChecked

            if (checkUrl(url)) {
                insertDoHEndpoint(name, url, isSecure)
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

    private fun insertDoHEndpoint(name: String, url: String, isSecure: Boolean) {
        io {
            var dohName: String = name
            if (name.isBlank()) {
                dohName = url
            }
            val doHEndpoint =
                DoHEndpoint(
                    id = 0,
                    dohName,
                    url,
                    dohExplanation = "",
                    isSelected = false,
                    isCustom = true,
                    isSecure = isSecure,
                    modifiedDataTime = 0,
                    latency = 0
                )
            appConfig.insertDohEndpoint(doHEndpoint)
        }
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

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }
}
