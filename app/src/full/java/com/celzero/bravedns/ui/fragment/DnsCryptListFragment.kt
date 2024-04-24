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

import Logger
import android.content.res.Configuration
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
import com.celzero.bravedns.adapter.DnsCryptEndpointAdapter
import com.celzero.bravedns.adapter.DnsCryptRelayEndpointAdapter
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.DnsCryptEndpoint
import com.celzero.bravedns.database.DnsCryptRelayEndpoint
import com.celzero.bravedns.databinding.DialogSetDnsCryptBinding
import com.celzero.bravedns.databinding.FragmentDnsCryptListBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.dialog.DnsCryptRelaysDialog
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.viewmodel.DnsCryptEndpointViewModel
import com.celzero.bravedns.viewmodel.DnsCryptRelayEndpointViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class DnsCryptListFragment : Fragment(R.layout.fragment_dns_crypt_list) {
    private val b by viewBinding(FragmentDnsCryptListBinding::bind)
    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()

    // Dnscrypt UI elements
    private lateinit var dnsCryptRecyclerAdapter: DnsCryptEndpointAdapter
    private var dnsCryptLayoutManager: RecyclerView.LayoutManager? = null
    private val dnsCryptViewModel: DnsCryptEndpointViewModel by viewModel()

    // Dnscrypt relay adapter and viewModel
    private lateinit var dnsCryptRelayRecyclerAdapter: DnsCryptRelayEndpointAdapter
    private val dnsCryptRelayViewModel: DnsCryptRelayEndpointViewModel by viewModel()

    companion object {
        fun newInstance() = DnsCryptListFragment()
    }

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initView()
        initClickListeners()
    }

    private fun initView() {
        dnsCryptLayoutManager = LinearLayoutManager(requireContext())
        b.recyclerDnsCryptConnections.layoutManager = dnsCryptLayoutManager

        dnsCryptRecyclerAdapter = DnsCryptEndpointAdapter(requireContext(), get())
        dnsCryptViewModel.dnsCryptEndpointList.observe(viewLifecycleOwner) {
            dnsCryptRecyclerAdapter.submitData(viewLifecycleOwner.lifecycle, it)
        }
        b.recyclerDnsCryptConnections.adapter = dnsCryptRecyclerAdapter
    }

    private fun initClickListeners() {
        b.addRelayBtn.setOnClickListener { openDnsCryptRelaysDialog() }

        // see CustomIpFragment#setupClickListeners#bringToFront()
        b.dohFabAddServerIcon.bringToFront()
        b.dohFabAddServerIcon.setOnClickListener { showAddDnsCryptDialog() }
    }

    private fun openDnsCryptRelaysDialog() {
        dnsCryptRelayRecyclerAdapter =
            DnsCryptRelayEndpointAdapter(requireContext(), viewLifecycleOwner, get())
        dnsCryptRelayViewModel.dnsCryptRelayEndpointList.observe(viewLifecycleOwner) {
            dnsCryptRelayRecyclerAdapter.submitData(viewLifecycleOwner.lifecycle, it)
        }

        val themeId = Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme)
        val customDialog =
            DnsCryptRelaysDialog(requireActivity(), dnsCryptRelayRecyclerAdapter, themeId)

        if (!isAdded && requireActivity().isFinishing) {
            Logger.w(Logger.LOG_TAG_UI, "DnsCrypt relay, Fragment not added to activity")
            return
        }

        customDialog.show()
    }

    private fun showAddDnsCryptDialog() {
        val dialogBinding = DialogSetDnsCryptBinding.inflate(layoutInflater)
        val builder = MaterialAlertDialogBuilder(requireContext()).setView(dialogBinding.root)

        val lp = WindowManager.LayoutParams()
        val dialog = builder.create()
        dialog.show()
        lp.copyFrom(dialog.window?.attributes)
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT

        dialog.setCancelable(true)
        dialog.window?.attributes = lp

        val radioServer = dialogBinding.dialogDnsCryptRadioServer
        val radioRelay = dialogBinding.dialogDnsCryptRadioRelay
        val applyURLBtn = dialogBinding.dialogDnsCryptOkBtn
        val cancelURLBtn = dialogBinding.dialogDnsCryptCancelBtn
        val cryptNameEditText = dialogBinding.dialogDnsCryptName
        val cryptURLEditText = dialogBinding.dialogDnsCryptUrl
        val cryptDescEditText = dialogBinding.dialogDnsCryptDesc
        val errorText = dialogBinding.dialogDnsCryptErrorTxt

        radioServer.isChecked = true
        var dnscryptNextIndex = 0
        var relayNextIndex = 0

        // Fetch the count from repository and increment by 1 to show the
        // next doh name in the dialog
        io {
            dnscryptNextIndex = appConfig.getDnscryptCount().plus(1)
            relayNextIndex = appConfig.getDnscryptRelayCount().plus(1)
            uiCtx {
                cryptNameEditText.setText(
                    getString(R.string.cd_dns_crypt_name, dnscryptNextIndex.toString()),
                    TextView.BufferType.EDITABLE
                )
            }
        }

        cryptNameEditText.setText(
            getString(R.string.cd_dns_crypt_name_default),
            TextView.BufferType.EDITABLE
        )

        radioServer.setOnClickListener {
            cryptNameEditText.setText(
                getString(R.string.cd_dns_crypt_name, dnscryptNextIndex.toString()),
                TextView.BufferType.EDITABLE
            )
        }

        radioRelay.setOnClickListener {
            cryptNameEditText.setText(
                getString(R.string.cd_dns_crypt_relay_name, relayNextIndex.toString()),
                TextView.BufferType.EDITABLE
            )
        }

        applyURLBtn.setOnClickListener {
            var isValid = true
            val name: String = cryptNameEditText.text.toString()
            val urlStamp = cryptURLEditText.text.toString()
            val desc = cryptDescEditText.text.toString()

            val mode =
                if (radioServer.isChecked) {
                    0 // Selected radio button - DNS Crypt
                } else {
                    1 // Selected radio button - DNS Crypt Relay
                }
            if (urlStamp.isBlank()) {
                isValid = false
                errorText.text = getString(R.string.cd_dns_crypt_error_text_1)
            }

            if (isValid) {
                // Do the DNS Crypt setting there
                if (mode == 0) {
                    insertDNSCryptServer(name, urlStamp, desc)
                } else if (mode == 1) {
                    insertDNSCryptRelay(name, urlStamp, desc)
                }
                dialog.dismiss()
            }
        }

        cancelURLBtn.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun insertDNSCryptRelay(name: String, urlStamp: String, desc: String) {
        io {
            var serverName = name
            if (serverName.isBlank()) {
                serverName = urlStamp
            }
            val dnsCryptRelayEndpoint =
                DnsCryptRelayEndpoint(
                    id = 0,
                    serverName,
                    urlStamp,
                    desc,
                    isSelected = false,
                    isCustom = true,
                    modifiedDataTime = 0L,
                    latency = 0
                )
            appConfig.insertDnscryptRelayEndpoint(dnsCryptRelayEndpoint)
        }
    }

    private fun insertDNSCryptServer(name: String, urlStamp: String, desc: String) {
        io {
            var serverName = name
            if (serverName.isBlank()) {
                serverName = urlStamp
            }

            val dnsCryptEndpoint =
                DnsCryptEndpoint(
                    id = 0,
                    serverName,
                    urlStamp,
                    desc,
                    isSelected = false,
                    isCustom = true,
                    modifiedDataTime = 0L,
                    latency = 0
                )
            appConfig.insertDnscryptEndpoint(dnsCryptEndpoint)
        }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }
}
