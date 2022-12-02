/*
 * Copyright 2020 RethinkDNS and its authors
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

import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.IpRulesManager
import com.celzero.bravedns.databinding.BottomSheetAppConnectionsBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import com.celzero.bravedns.util.Constants.Companion.UNSPECIFIED_PORT
import com.celzero.bravedns.util.Themes.Companion.getBottomsheetCurrentTheme
import com.celzero.bravedns.util.Utilities
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class AppConnectionBottomSheet : BottomSheetDialogFragment() {
    private var _binding: BottomSheetAppConnectionsBinding? = null

    // This property is only valid between onCreateView and onDestroyView.
    private val b
        get() = _binding!!

    private val persistentState by inject<PersistentState>()

    override fun getTheme(): Int =
        getBottomsheetCurrentTheme(isDarkThemeOn(), persistentState.theme)

    private var uid: Int = -1
    private var ipAddress: String = ""
    private var port: Int = UNSPECIFIED_PORT
    private var ipRuleStatus: IpRulesManager.IpRuleStatus = IpRulesManager.IpRuleStatus.NONE

    companion object {
        const val UID = "UID"
        const val IPADDRESS = "IPADDRESS"
        const val PORT = "PORT"
        const val IPRULESTATUS = "IPRULESTATUS"
    }

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetAppConnectionsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        uid = arguments?.getInt(UID) ?: INVALID_UID
        ipAddress = arguments?.getString(IPADDRESS) ?: ""
        port = arguments?.getInt(PORT) ?: UNSPECIFIED_PORT
        val status = arguments?.getInt(IPRULESTATUS) ?: IpRulesManager.IpRuleStatus.NONE.id
        ipRuleStatus = IpRulesManager.IpRuleStatus.getStatus(status)

        initView()
        initializeClickListeners()
    }

    private fun initView() {
        if (uid == INVALID_UID) {
            this.dismiss()
            return
        }
        b.bsacHeading.text = ipAddress

        when (ipRuleStatus) {
            IpRulesManager.IpRuleStatus.NONE -> showButtonsForStatusNone()
            IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL -> {
                // no-op, bypass universal rules don't apply in app specific list
            }
            IpRulesManager.IpRuleStatus.BYPASS_APP_RULES -> showByPassAppRulesUi()
            IpRulesManager.IpRuleStatus.BLOCK -> showButtonForStatusBlock()
        }
    }

    override fun onResume() {
        super.onResume()
        if (uid == INVALID_UID) {
            this.dismiss()
            return
        }
    }

    private fun showButtonForStatusBlock() {
        b.bsacUnblock.visibility = View.VISIBLE
        b.bsacBypassAppRules.visibility = View.VISIBLE
    }

    private fun showByPassAppRulesUi() {
        b.bsacBlock.visibility = View.VISIBLE
        b.bsacGopassAppRules.visibility = View.VISIBLE
    }

    private fun showButtonsForStatusNone() {
        b.bsacBlock.visibility = View.VISIBLE
        b.bsacBypassAppRules.visibility = View.VISIBLE
    }

    private fun initializeClickListeners() {
        b.bsacBlock.setOnClickListener {
            applyRule(
                uid,
                ipAddress,
                UNSPECIFIED_PORT,
                IpRulesManager.IpRuleStatus.BLOCK,
                getString(R.string.bsac_block_toast, ipAddress)
            )
        }

        b.bsacUnblock.setOnClickListener {
            applyRule(
                uid,
                ipAddress,
                UNSPECIFIED_PORT,
                IpRulesManager.IpRuleStatus.NONE,
                getString(R.string.bsac_unblock_toast, ipAddress)
            )
        }

        b.bsacBypassAppRules.setOnClickListener {
            applyRule(
                uid,
                ipAddress,
                UNSPECIFIED_PORT,
                IpRulesManager.IpRuleStatus.BYPASS_APP_RULES,
                getString(R.string.bsac_whitelist_toast, ipAddress)
            )
        }

        b.bsacGopassAppRules.setOnClickListener {
            applyRule(
                uid,
                ipAddress,
                UNSPECIFIED_PORT,
                IpRulesManager.IpRuleStatus.NONE,
                getString(R.string.bsac_whitelist_remove_toast, ipAddress)
            )
        }
    }

    private fun applyRule(
        uid: Int,
        ipAddress: String,
        port: Int,
        status: IpRulesManager.IpRuleStatus,
        toastMsg: String
    ) {
        io { IpRulesManager.addIpRule(uid, ipAddress, port, status) }
        Utilities.showToastUiCentered(requireContext(), toastMsg, Toast.LENGTH_SHORT)
        this.dismiss()
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch { withContext(Dispatchers.IO) { f() } }
    }
}
