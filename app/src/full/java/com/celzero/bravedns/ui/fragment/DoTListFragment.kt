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
import com.celzero.bravedns.adapter.DoTEndpointAdapter
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.DoTEndpoint
import com.celzero.bravedns.databinding.DialogSetCustomDohBinding
import com.celzero.bravedns.databinding.FragmentDotListBinding
import com.celzero.bravedns.viewmodel.DoTEndpointViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class DoTListFragment : Fragment(R.layout.fragment_dot_list) {
    private val b by viewBinding(FragmentDotListBinding::bind)

    private val appConfig by inject<AppConfig>()

    private var layoutManager: RecyclerView.LayoutManager? = null
    private var adapter: DoTEndpointAdapter? = null
    private val viewModel: DoTEndpointViewModel by viewModel()

    companion object {
        fun newInstance() = DoTListFragment()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        initClickListeners()
    }

    private fun initView() {
        layoutManager = LinearLayoutManager(requireContext())
        b.recyclerDot.layoutManager = layoutManager

        adapter = DoTEndpointAdapter(requireContext(), viewLifecycleOwner, get())
        viewModel.dohEndpointList.observe(viewLifecycleOwner) {
            adapter!!.submitData(viewLifecycleOwner.lifecycle, it)
        }
        b.recyclerDot.adapter = adapter
    }

    private fun initClickListeners() {
        b.dotFabAdd.setOnClickListener { showAddDialog() }
    }

    private fun showAddDialog() {
        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setTitle(getString(R.string.cd_custom_doh_dialog_title))
        val dialogBinding = DialogSetCustomDohBinding.inflate(layoutInflater)
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
        val customURL = dialogBinding.dialogCustomUrlEditText
        val progressBar = dialogBinding.dialogCustomUrlLoading
        val errorTxt = dialogBinding.dialogCustomUrlFailureText
        val checkBox = dialogBinding.dialogSecureCheckbox

        heading.text = getString(R.string.lbl_add) + " " + getString(R.string.lbl_dot)
        // fetch the count from repository and increment by 1 to show the
        // next doh name in the dialog
        io {
            val nextIndex = appConfig.getDoTCount().plus(1)
            uiCtx {
                customName.setText(
                    getString(R.string.lbl_dot) + nextIndex.toString(),
                    TextView.BufferType.EDITABLE
                )
            }
        }

        customName.setText(getString(R.string.lbl_dot), TextView.BufferType.EDITABLE)
        applyURLBtn.setOnClickListener {
            val url = customURL.text.toString()
            val name = customName.text.toString()
            val isSecure = !checkBox.isChecked

            insert(name, url, isSecure)
            dialog.dismiss()
        }

        cancelURLBtn.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun insert(name: String, url: String, isSecure: Boolean) {
        io {
            var dotName: String = name
            if (name.isBlank()) {
                dotName = url
            }
            val endpoint =
                DoTEndpoint(
                    id = 0,
                    dotName,
                    url,
                    desc = "",
                    isSelected = false,
                    isCustom = true,
                    isSecure = isSecure,
                    modifiedDataTime = 0,
                    latency = 0
                )
            appConfig.insertDoTEndpoint(endpoint)
        }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch { withContext(Dispatchers.IO) { f() } }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }
}
