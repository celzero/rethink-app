/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.bottomsheet
import android.content.DialogInterface
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import com.celzero.bravedns.R
import com.celzero.bravedns.database.*
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.service.IpRulesManager.IpRuleStatus
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.rules.StoredRuleDetails
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.*
import org.koin.android.ext.android.inject

class CustomDomainRulesBtmSheet : BaseBottomSheetDialogFragment(), ProxyCountriesBtmSheet.CountriesDismissListener, WireguardListBtmSheet.WireguardDismissListener {
    private val persistentState by inject<PersistentState>()
    private val eventLogger by inject<EventLogger>()
    private lateinit var cd: CustomDomain
    private var selectedStatus by mutableStateOf(0)
    private var displayedStatus by mutableStateOf(0)
    private var modified by mutableStateOf(0L)
    private var revision by mutableStateOf(0)
    companion object {
        private const val TAG = "CDRBtmSht"
        private const val ARG_CUSTOM_DOMAIN = "custom_domain"
        fun newInstance(customDomain: CustomDomain) = CustomDomainRulesBtmSheet().apply {
            arguments = Bundle().apply { putSerializable(ARG_CUSTOM_DOMAIN, customDomain) }
        }
    }
    private fun isDarkThemeOn() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    override fun getTheme() = Themes.getBottomSheetCurrentTheme(isDarkThemeOn(), persistentState.theme)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val item = arguments?.getSerializable(ARG_CUSTOM_DOMAIN) as? CustomDomain
        if (item == null) { dismiss(); return }
        cd = item
        selectedStatus = DomainRulesManager.getDomainRule(cd.domain, cd.uid).id
        displayedStatus = cd.status
        modified = cd.modifiedTs
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View = ComposeView(inflater.context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { RethinkTheme { if (::cd.isInitialized) {
            revision
            StoredRuleDetails(cd, selectedStatus, displayedStatus, modified, { handleDomainRuleClick(DomainRulesManager.Status.getStatus(it)) }, ::deleteConfirmed)
        } } }
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dialog?.window?.let { Themes.applyBottomSheetSystemBarAppearance(it, isDarkThemeOn(), persistentState.theme) }
    }
    private fun handleDomainRuleClick(status: DomainRulesManager.Status) {
        if (cd.status == status.id) return
        changeDomainStatus(status, cd)
    }
    private fun changeDomainStatus(id: DomainRulesManager.Status, cd: CustomDomain) {
        io {
            when (id) {
                DomainRulesManager.Status.NONE -> noRule(cd)
                DomainRulesManager.Status.BLOCK -> block(cd)
                DomainRulesManager.Status.TRUST -> whitelist(cd)
            }
            uiCtx { selectedStatus = id.id; displayedStatus = id.id; modified = this.cd.modifiedTs; revision++ }
        }
    }
    private suspend fun whitelist(cd: CustomDomain) {
        DomainRulesManager.trust(cd)
        logEvent("Whitelisted custom domain rule for ${cd.domain}")
    }

    private suspend fun block(cd: CustomDomain) {
        DomainRulesManager.block(cd)
        logEvent("Blocked custom domain rule for ${cd.domain}")
    }

    private suspend fun noRule(cd: CustomDomain) {
        DomainRulesManager.noRule(cd)
        logEvent("Domain rule for ${cd.domain} is set to no rule")
    }
    private fun deleteConfirmed() {
        io { DomainRulesManager.deleteDomain(cd) }
        Utilities.showToastUiCentered(requireContext(), getString(R.string.cd_toast_deleted), Toast.LENGTH_SHORT)

        logEvent("Deleted custom domain rule for ${cd.domain}")
        dismiss()
    }
    override fun onDismissCC(obj: Any?) { updateFromPicker(obj) }
    override fun onDismissWg(obj: Any?) { updateFromPicker(obj) }
    private fun updateFromPicker(obj: Any?) {
        try {
            cd = obj as CustomDomain
            // Domain picker callbacks only updated the description.
            displayedStatus = DomainRulesManager.Status.getStatus(cd.status).id
            modified = cd.modifiedTs
            revision++
        } catch (e: Exception) { Logger.w(LOG_TAG_UI, "$TAG: error updating from picker: ${e.message}", e) }
    }
    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (::cd.isInitialized) Logger.v(LOG_TAG_UI, "$TAG: onDismiss; rule: ${cd.domain}")
    }
    private fun logEvent(details: String) { eventLogger.log(EventType.FW_RULE_MODIFIED, Severity.LOW, "Custom Domain", EventSource.UI, false, details) }
    private fun io(f: suspend () -> Unit) { requireActivity().lifecycleScope.launch(Dispatchers.IO) { f() } }
    private suspend fun uiCtx(f: suspend () -> Unit) { withContext(Dispatchers.Main) { if (isAdded && view != null) f() } }
}
