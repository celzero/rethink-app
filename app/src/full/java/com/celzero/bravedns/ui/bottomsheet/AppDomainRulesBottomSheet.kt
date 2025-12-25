/*
 * Copyright 2024 RethinkDNS and its authors
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
package com.celzero.bravedns.ui.bottomsheet

import Logger
import Logger.LOG_TAG_FIREWALL
import Logger.LOG_TAG_UI
import android.content.DialogInterface
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.AppWiseDomainsAdapter
import com.celzero.bravedns.database.CustomDomain
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.database.WgConfigFilesImmutable
import com.celzero.bravedns.databinding.BottomSheetAppConnectionsBinding
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import com.celzero.bravedns.util.Themes.Companion.getBottomsheetCurrentTheme
import com.celzero.bravedns.util.UIUtils.htmlToSpannedText
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.useTransparentNoDimBackground
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class AppDomainRulesBottomSheet : BottomSheetDialogFragment(), WireguardListBtmSheet.WireguardDismissListener  {
    private var _binding: BottomSheetAppConnectionsBinding? = null

    // This property is only valid between onCreateView and onDestroyView.
    private val b
        get() = _binding!!

    private val persistentState by inject<PersistentState>()
    private val eventLogger by inject<EventLogger>()

    // listener to inform dataset change to the adapter
    private var dismissListener: OnBottomSheetDialogFragmentDismiss? = null
    private var adapter: AppWiseDomainsAdapter? = null
    private var position: Int = -1

    override fun getTheme(): Int =
        getBottomsheetCurrentTheme(isDarkThemeOn(), persistentState.theme)

    private var uid: Int = -1
    private var domain: String = ""
    private var domainRule: DomainRulesManager.Status = DomainRulesManager.Status.NONE
    private var cd: CustomDomain? = null

    companion object {
        const val UID = "UID"
        const val DOMAIN = "DOMAIN"
        private const val TAG = "AppDomainBtmSht"
    }

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    interface OnBottomSheetDialogFragmentDismiss {
        fun notifyDataset(position: Int)
    }

    fun dismissListener(aca: AppWiseDomainsAdapter?, pos: Int) {
        adapter = aca
        position = pos
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
        dialog?.window?.let { window ->
            if (isAtleastQ()) {
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                controller.isAppearanceLightNavigationBars = false
                window.isNavigationBarContrastEnforced = false
            }
        }

        uid = arguments?.getInt(UID) ?: INVALID_UID
        domain = arguments?.getString(DOMAIN) ?: ""

        dismissListener = adapter

        init()
        initializeClickListeners()
        setRulesUi()
    }

    override fun onStart() {
        super.onStart()
        dialog?.useTransparentNoDimBackground()
    }

    private fun init() {
        if (uid == INVALID_UID) {
            this.dismiss()
            return
        }
        io {
            cd = DomainRulesManager.getObj(uid, domain)
            if (cd == null) {
                cd = DomainRulesManager.makeCustomDomain(uid, domain)
            }
        }
        updateAppDetails()
        // making use of the same layout used for ip rules, so changing the text and
        // removing the recycler related changes
        b.bsacIpAddressTv.text = domain
        b.bsacIpRuleTxt.text = htmlToSpannedText(getString(R.string.bsct_block_domain))

        b.bsacDomainRuleTxt.visibility = View.GONE
        b.bsacDomainLl.visibility = View.GONE
    }

    private fun updateAppDetails() {
        if (uid == -1) return

        io {
            val appNames = FirewallManager.getAppNamesByUid(uid)
            if (appNames.isEmpty()) {
                uiCtx { handleNonApp() }
                return@io
            }
            val pkgName = FirewallManager.getPackageNameByAppName(appNames[0])

            val appCount = appNames.count()
            uiCtx {
                if (appCount >= 1) {
                    b.bsacAppName.text =
                        if (appCount >= 2) {
                            getString(
                                R.string.ctbs_app_other_apps,
                                appNames[0],
                                appCount.minus(1).toString()
                            )
                        } else {
                            appNames[0]
                        }
                    if (pkgName == null) return@uiCtx
                    b.bsacAppIcon.setImageDrawable(
                        Utilities.getIcon(requireContext(), pkgName)
                    )
                } else {
                    // apps which are not available in cache are treated as non app.
                    // TODO: check packageManager#getApplicationInfo() for appInfo
                    handleNonApp()
                }
            }
        }
    }

    private fun handleNonApp() {
        b.bsacAppName.visibility = View.GONE
        b.bsacAppIcon.visibility = View.GONE
    }

    private fun setRulesUi() {
        io {
            // no need to send port number for the app info screen
            domainRule = DomainRulesManager.status(domain, uid)
            Logger.d(LOG_TAG_FIREWALL, "$TAG set selection of ip: $domain, ${domainRule.id}")
            uiCtx {
                when (domainRule) {
                    DomainRulesManager.Status.TRUST -> {
                        enableTrustUi()
                    }
                    DomainRulesManager.Status.BLOCK -> {
                        enableBlockUi()
                    }
                    DomainRulesManager.Status.NONE -> {
                        noRuleUi()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (uid == INVALID_UID) {
            this.dismiss()
            return
        }
    }

    private fun initializeClickListeners() {

        b.blockIcon.setOnClickListener {
            if (domainRule == DomainRulesManager.Status.BLOCK) {
                applyDomainRule(DomainRulesManager.Status.NONE)
                noRuleUi()
            } else {
                applyDomainRule(DomainRulesManager.Status.BLOCK)
                enableBlockUi()
            }
        }

        b.trustIcon.setOnClickListener {
            if (domainRule == DomainRulesManager.Status.TRUST) {
                applyDomainRule(DomainRulesManager.Status.NONE)
                noRuleUi()
            } else {
                applyDomainRule(DomainRulesManager.Status.TRUST)
                enableTrustUi()
            }
        }

        b.chooseProxyRl.setOnClickListener {
            val ctx = requireContext()
            val v: MutableList<WgConfigFilesImmutable?> = mutableListOf()
            io {
                v.add(null)
                v.addAll(WireguardManager.getAllMappings())
                if (v.isEmpty()) {
                    Logger.v(LOG_TAG_UI, "$TAG no wireguard configs found")
                    uiCtx {
                        Utilities.showToastUiCentered(
                            ctx,
                            getString(R.string.wireguard_no_config_msg),
                            Toast.LENGTH_SHORT
                        )
                    }
                    return@io
                }
                uiCtx {
                    Logger.v(LOG_TAG_UI, "$TAG show wg list(${v.size} for ${cd?.domain}, $uid")
                    showWgListBtmSheet(v)
                }
            }
        }

    }


    private fun showWgListBtmSheet(data: List<WgConfigFilesImmutable?>) {
        Logger.v(LOG_TAG_UI, "$TAG show wg list(${data.size} for ${cd?.domain}, uid: $uid")
        val bottomSheetFragment = WireguardListBtmSheet.newInstance(WireguardListBtmSheet.InputType.DOMAIN, cd, data, this)
        bottomSheetFragment.show(
            requireActivity().supportFragmentManager,
            bottomSheetFragment.tag
        )
    }

    private fun applyDomainRule(status: DomainRulesManager.Status) {
        Logger.i(LOG_TAG_FIREWALL, "$TAG domain rule for uid: $uid:$domain (${status.name})")
        domainRule = status

        // set port number as null for all the rules applied from this screen
        io {
            DomainRulesManager.changeStatus(
                domain,
                uid,
                "",
                DomainRulesManager.DomainType.DOMAIN,
                status
            )
        }
        logEvent("Domain rule applied: $domain, $uid, ${status.name}")
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        dismissListener?.notifyDataset(position)
    }

    private fun enableTrustUi() {
        b.trustIcon.setImageDrawable(
            ContextCompat.getDrawable(requireContext(), R.drawable.ic_trust_accent)
        )
        b.blockIcon.setImageDrawable(
            ContextCompat.getDrawable(requireContext(), R.drawable.ic_block)
        )
    }

    private fun enableBlockUi() {
        b.trustIcon.setImageDrawable(
            ContextCompat.getDrawable(requireContext(), R.drawable.ic_trust)
        )
        b.blockIcon.setImageDrawable(
            ContextCompat.getDrawable(requireContext(), R.drawable.ic_block_accent)
        )
    }

    private fun noRuleUi() {
        b.trustIcon.setImageDrawable(
            ContextCompat.getDrawable(requireContext(), R.drawable.ic_trust)
        )
        b.blockIcon.setImageDrawable(
            ContextCompat.getDrawable(requireContext(), R.drawable.ic_block)
        )
    }

    private fun logEvent(details: String) {
        eventLogger.log(EventType.FW_RULE_MODIFIED, Severity.LOW, "App domain rule", EventSource.UI, false, details)
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }

    override fun onDismissWg(obj: Any?) {
        try {
            val customDomain = obj as CustomDomain
            cd = customDomain
            setRulesUi()
            Logger.i(LOG_TAG_UI, "$TAG onDismissWg: ${cd?.domain}, $uid, ${cd?.proxyId}")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "$TAG err in onDismissWg ${e.message}", e)
        }
    }
}
