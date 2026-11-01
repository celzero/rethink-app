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

class CustomIpRulesBtmSheet : BaseBottomSheetDialogFragment(), ProxyCountriesBtmSheet.CountriesDismissListener, WireguardListBtmSheet.WireguardDismissListener {
    private val persistentState by inject<PersistentState>()
    private val eventLogger by inject<EventLogger>()
    private lateinit var ci: CustomIp
    private var selectedStatus by mutableStateOf(0)
    private var displayedStatus by mutableStateOf(0)
    private var modified by mutableStateOf(0L)
    private var revision by mutableStateOf(0)
    companion object {
        private const val TAG = "CIRBtmSht"
        private const val ARG_CUSTOM_IP = "custom_ip"
        fun newInstance(customIp: CustomIp) = CustomIpRulesBtmSheet().apply {
            arguments = Bundle().apply { putSerializable(ARG_CUSTOM_IP, customIp) }
        }
    }
    private fun isDarkThemeOn() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    override fun getTheme() = Themes.getBottomSheetCurrentTheme(isDarkThemeOn(), persistentState.theme)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val item = arguments?.getSerializable(ARG_CUSTOM_IP) as? CustomIp
        if (item == null) { dismiss(); return }
        ci = item
        selectedStatus = IpRuleStatus.getStatus(ci.status).id
        displayedStatus = ci.status
        modified = ci.modifiedDateTime
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View = ComposeView(inflater.context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { RethinkTheme { if (::ci.isInitialized) {
            revision
            StoredRuleDetails(ci, selectedStatus, displayedStatus, modified, { handleIpRuleClick(IpRuleStatus.getStatus(it)) }, ::deleteConfirmed)
        } } }
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dialog?.window?.let { Themes.applyBottomSheetSystemBarAppearance(it, isDarkThemeOn(), persistentState.theme) }
    }
    private fun handleIpRuleClick(status: IpRuleStatus) {
        if (ci.status == status.id) return
        changeIpStatus(status, ci)
    }
    private fun changeIpStatus(id: IpRuleStatus, customIp: CustomIp) {
        io {
            val updated = when (id) {
                IpRuleStatus.NONE -> noRuleIp(customIp)
                IpRuleStatus.BLOCK -> blockIp(customIp)
                IpRuleStatus.BYPASS_UNIVERSAL -> byPassUniversal(customIp)
                IpRuleStatus.TRUST -> byPassAppRule(customIp)
            }
            ci = updated
            uiCtx { selectedStatus = id.id; displayedStatus = id.id; modified = ci.modifiedDateTime; revision++ }
        }
    }
    private suspend fun byPassUniversal(orig: CustomIp): CustomIp {
        Logger.i(LOG_TAG_UI, "$TAG: set ${orig.ipAddress} to bypass universal")
        val copy = orig.deepCopy()
        // status and modified time will be set in updateRule
        IpRulesManager.updateBypass(copy)
        logEvent("Set IP ${copy.ipAddress} to bypass universal")
        return copy
    }

    private suspend fun byPassAppRule(orig: CustomIp): CustomIp {
        Logger.i(LOG_TAG_UI, "$TAG: set ${orig.ipAddress} to bypass app")
        val copy = orig.deepCopy()
        IpRulesManager.updateTrust(copy)
        logEvent("Set IP ${copy.ipAddress} to trust")
        return copy
    }

    private suspend fun blockIp(orig: CustomIp): CustomIp {
        Logger.i(LOG_TAG_UI, "$TAG: block ${orig.ipAddress}")
        val copy = orig.deepCopy()
        IpRulesManager.updateBlock(copy)
        logEvent("Blocked IP ${copy.ipAddress}")
        return copy
    }

    private suspend fun noRuleIp(orig: CustomIp): CustomIp {
        Logger.i(LOG_TAG_UI, "$TAG: no rule for ${orig.ipAddress}")
        val copy = orig.deepCopy()
        IpRulesManager.updateNoRule(copy)
        logEvent("Set no rule for IP ${copy.ipAddress}")
        return copy
    }
    private fun deleteConfirmed() {
        io { IpRulesManager.removeIpRule(ci.uid, ci.ipAddress, ci.port) }
        Utilities.showToastUiCentered(requireContext(), getString(R.string.univ_ip_delete_individual_toast, ci.ipAddress), Toast.LENGTH_SHORT)
        dismiss()
        logEvent("Deleted custom IP rule for ${ci.ipAddress}")

    }
    override fun onDismissCC(obj: Any?) { updateFromPicker(obj) }
    override fun onDismissWg(obj: Any?) { updateFromPicker(obj) }
    private fun updateFromPicker(obj: Any?) {
        try {
            ci = obj as CustomIp
            selectedStatus = IpRuleStatus.getStatus(ci.status).id
            displayedStatus = IpRuleStatus.getStatus(ci.status).id
            modified = ci.modifiedDateTime
            revision++
        } catch (e: Exception) { Logger.w(LOG_TAG_UI, "$TAG: error updating from picker: ${e.message}", e) }
    }
    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (::ci.isInitialized) Logger.v(LOG_TAG_UI, "$TAG: onDismiss; rule: ${ci.ipAddress}")
    }
    private fun logEvent(details: String) { eventLogger.log(EventType.FW_RULE_MODIFIED, Severity.LOW, "Custom IP", EventSource.UI, false, details) }
    private fun io(f: suspend () -> Unit) { requireActivity().lifecycleScope.launch(Dispatchers.IO) { f() } }
    private suspend fun uiCtx(f: suspend () -> Unit) { withContext(Dispatchers.Main) { if (isAdded && view != null) f() } }
}
