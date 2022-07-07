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

import android.content.DialogInterface
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.celzero.bravedns.adapter.AppConnectionAdapter
import com.celzero.bravedns.automaton.IpRulesManager
import com.celzero.bravedns.databinding.BottomSheetAppConnectionsBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Themes.Companion.getBottomsheetCurrentTheme
import com.celzero.bravedns.util.Utilities
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class AppConnectionBottomSheet(private val adapter: AppConnectionAdapter?, val uid: Int,
                               val ipAddress: String,
                               private val ipRuleStatus: IpRulesManager.IpRuleStatus,
                               val position: Int) : BottomSheetDialogFragment() {
    private var _binding: BottomSheetAppConnectionsBinding? = null

    // This property is only valid between onCreateView and onDestroyView.
    private val b get() = _binding!!

    private val persistentState by inject<PersistentState>()
    private var dismissListener: OnBottomSheetDialogFragmentDismiss? = null

    override fun getTheme(): Int = getBottomsheetCurrentTheme(isDarkThemeOn(),
                                                              persistentState.theme)

    interface OnBottomSheetDialogFragmentDismiss {
        fun notifyDataset(position: Int)
    }

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        _binding = BottomSheetAppConnectionsBinding.inflate(inflater, container, false)
        dismissListener = adapter
        return b.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        dismissListener?.notifyDataset(position)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        initializeClickListeners()
    }

    private fun initView() {
        b.bsacHeading.text = ipAddress

        when (ipRuleStatus) {
            IpRulesManager.IpRuleStatus.NONE -> showButtonsForStatusNone()
            IpRulesManager.IpRuleStatus.WHITELIST -> showButtonsForStatusWhitelist()
            IpRulesManager.IpRuleStatus.BLOCK -> showButtonForStatusBlock()
        }
    }

    private fun showButtonForStatusBlock() {
        b.bsacUnblock.visibility = View.VISIBLE
        b.bsacWhitelist.visibility = View.VISIBLE
        //b.bsacBlockAll.visibility = View.VISIBLE
        //b.bsacWhitelistAll.visibility = View.VISIBLE
    }

    private fun showButtonsForStatusWhitelist() {
        b.bsacBlock.visibility = View.VISIBLE
        b.bsacWhitelistRemove.visibility = View.VISIBLE
        //b.bsacBlockAll.visibility = View.VISIBLE
        //b.bsacWhitelistAll.visibility = View.VISIBLE
    }

    private fun showButtonsForStatusNone() {
        b.bsacBlock.visibility = View.VISIBLE
        b.bsacWhitelist.visibility = View.VISIBLE
        //b.bsacBlockAll.visibility = View.VISIBLE
        //b.bsacWhitelistAll.visibility = View.VISIBLE
    }

    private fun initializeClickListeners() {
        b.bsacBlock.setOnClickListener {
            applyRule(uid, ipAddress, IpRulesManager.IpRuleStatus.BLOCK,
                      "Blocking $ipAddress for this apps")
        }

        // introduce this when IP firewall becomes uid-wise
        /*b.bsacBlockAll.setOnClickListener {
            applyRule(IpRulesManager.UID_EVERYBODY, ipAddress, IpRulesManager.IpRuleStatus.BLOCK,
                      "Blocking $ipAddress for all apps")
        }*/

        b.bsacUnblock.setOnClickListener {
            applyRule(uid, ipAddress, IpRulesManager.IpRuleStatus.NONE,
                      "Unblocked $ipAddress for this app")
        }

        b.bsacWhitelist.setOnClickListener {
            applyRule(uid, ipAddress, IpRulesManager.IpRuleStatus.WHITELIST,
                      "Whitelisted $ipAddress for this apps")
        }

        // introduce this when IP firewall becomes uid-wise
        /* b.bsacWhitelistAll.setOnClickListener {
             applyRule(IpRulesManager.UID_EVERYBODY, ipAddress, IpRulesManager.IpRuleStatus.WHITELIST,
                       "Whitelisted $ipAddress for all apps")
         }*/

        b.bsacWhitelistRemove.setOnClickListener {
            applyRule(uid, ipAddress, IpRulesManager.IpRuleStatus.NONE,
                      "Removed $ipAddress from whitelist")
        }
    }

    private fun applyRule(uid: Int, ipAddress: String, status: IpRulesManager.IpRuleStatus,
                          toastMsg: String) {
        io {
            IpRulesManager.addIpRule(uid, ipAddress, status)
        }
        Utilities.showToastUiCentered(requireContext(), toastMsg, Toast.LENGTH_SHORT)
        this.dismiss()
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                f()
            }
        }
    }
}
