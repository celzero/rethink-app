package com.celzero.bravedns.ui.bottomsheet

import Logger
import Logger.LOG_TAG_UI
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.Toast
import androidx.appcompat.widget.AppCompatTextView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.celzero.bravedns.R
import com.celzero.bravedns.database.CustomIp
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.database.WgConfigFilesImmutable
import com.celzero.bravedns.databinding.BottomSheetCustomIpsBinding
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.service.IpRulesManager.IpRuleStatus
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Themes.Companion.getBottomSheetCurrentTheme
import com.celzero.bravedns.util.UIUtils.fetchColor
import com.celzero.bravedns.util.UIUtils.fetchToggleBtnColors
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.getCountryCode
import com.celzero.bravedns.util.Utilities.getFlag
import com.celzero.bravedns.util.useTransparentNoDimBackground
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import inet.ipaddr.IPAddressString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class CustomIpRulesBtmSheet :
    BottomSheetDialogFragment(), ProxyCountriesBtmSheet.CountriesDismissListener, WireguardListBtmSheet.WireguardDismissListener {
    private var _binding: BottomSheetCustomIpsBinding? = null

    private val b
        get() = checkNotNull(_binding)
        { "Binding accessed outside of view lifecycle" }

    private val persistentState by inject<PersistentState>()
    private val eventLogger by inject<EventLogger>()
    private lateinit var ci: CustomIp

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
    }

    companion object {
        private const val TAG = "CIRBtmSht"
        private const val ARG_CUSTOM_IP = "custom_ip"

        fun newInstance(customIp: CustomIp): CustomIpRulesBtmSheet {
            val fragment = CustomIpRulesBtmSheet()
            val args = Bundle()
            args.putSerializable(ARG_CUSTOM_IP, customIp)
            fragment.arguments = args
            return fragment
        }
    }

    override fun getTheme(): Int =
        getBottomSheetCurrentTheme(isDarkThemeOn(), persistentState.theme)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetCustomIpsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onStart() {
        super.onStart()
        dialog?.useTransparentNoDimBackground()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Retrieve CustomIp from arguments
        ci = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            arguments?.getSerializable(ARG_CUSTOM_IP, CustomIp::class.java)
        } else {
            @Suppress("DEPRECATION")
            arguments?.getSerializable(ARG_CUSTOM_IP) as? CustomIp
        } ?: run {
            Logger.e(LOG_TAG_UI, "$TAG: CustomIp not found in arguments, dismissing")
            dismiss()
            return
        }

        dialog?.window?.let { window ->
            Themes.applyBottomSheetSystemBarAppearance(window, isDarkThemeOn(), persistentState.theme)
        }
        Logger.v(LOG_TAG_UI, "$TAG: onViewCreated for ${ci.ipAddress}")
        init()
        initClickListeners()
    }

    private fun init() {
        val uid = ci.uid
        io {
            if (uid == UID_EVERYBODY) {
                uiCtx {
                    b.customIpAppNameTv.text =
                        getString(R.string.firewall_act_universal_tab).replaceFirstChar(Char::titlecase)
                    b.customIpAppIconCv.visibility = View.GONE
                    updateFlagIfAvailable(ci)
                }
            } else {
                val appNames = FirewallManager.getAppNamesByUid(ci.uid)
                val appName = getAppName(ci.uid, appNames)
                val appInfo = FirewallManager.getAppInfoByUid(ci.uid)
                uiCtx {
                    b.customIpAppIconCv.visibility = View.VISIBLE
                    b.customIpFlagTv.visibility = View.GONE
                    b.customIpAppNameTv.text = appName
                    displayIcon(
                        Utilities.getIcon(
                            requireContext(),
                            appInfo?.packageName.orEmpty(),
                            appInfo?.appName.orEmpty()
                        ),
                        b.customIpAppIconIv
                    )
                }
            }
        }
        Logger.v(LOG_TAG_UI, "$TAG: init for ${ci.ipAddress}, uid: $uid")
        val rules = IpRuleStatus.getStatus(ci.status)
        b.customIpTv.text = ci.ipAddress
        showBypassUi(uid)
        updateToggleGroup(rules)
        updateStatusUi(rules, ci.modifiedDateTime)
    }

    private fun updateFlagIfAvailable(ip: CustomIp) {
        if (ip.wildcard) return

        val inetAddr = try {
            IPAddressString(ip.ipAddress).hostAddress.toInetAddress()
        } catch (_: Exception) {
            null // invalid ip
        }

        b.customIpFlagTv.visibility = View.VISIBLE
        b.customIpFlagTv.text = getFlag(getCountryCode(inetAddr, requireContext()))
    }

    private fun getAppName(uid: Int, appNames: List<String>): String {
        if (uid == UID_EVERYBODY) {
            return getString(R.string.firewall_act_universal_tab)
                .replaceFirstChar(Char::titlecase)
        }

        if (appNames.isEmpty()) {
            return getString(R.string.network_log_app_name_unknown) + " ($uid)"
        }

        val packageCount = appNames.count()
        return if (packageCount >= 2) {
            getString(
                R.string.ctbs_app_other_apps,
                appNames[0],
                packageCount.minus(1).toString()
            )
        } else {
            appNames[0]
        }
    }

    private fun displayIcon(drawable: Drawable?, mIconImageView: ImageView) {
        Glide.with(requireContext())
            .load(drawable)
            .error(Utilities.getDefaultIcon(requireContext()))
            .into(mIconImageView)
    }

    private fun showBypassUi(uid: Int) {
        if (uid == UID_EVERYBODY) {
            b.customIpTgBypassUniv.visibility = View.VISIBLE
            b.customIpTgBypassApp.visibility = View.GONE
        } else {
            b.customIpTgBypassUniv.visibility = View.GONE
            b.customIpTgBypassApp.visibility = View.VISIBLE
        }
    }

    private fun initClickListeners() {
        b.customIpTgNoRule.setOnClickListener { handleIpRuleClick(IpRuleStatus.NONE) }
        b.customIpTgBlock.setOnClickListener { handleIpRuleClick(IpRuleStatus.BLOCK) }
        b.customIpTgBypassUniv.setOnClickListener { handleIpRuleClick(IpRuleStatus.BYPASS_UNIVERSAL) }
        b.customIpTgBypassApp.setOnClickListener { handleIpRuleClick(IpRuleStatus.TRUST) }

        b.customIpDeleteChip.setOnClickListener {
            showDialogForDelete()
        }
    }

    private fun handleIpRuleClick(status: IpRuleStatus) {
        if (ci.status == status.id) return // no change
        changeIpStatus(status, ci)
    }

    private fun updateStatusUi(status: IpRuleStatus, modifiedTs: Long) {
        val now = System.currentTimeMillis()
        val uptime = System.currentTimeMillis() - modifiedTs
        // returns a string describing 'time' as a time relative to 'now'
        val time =
            DateUtils.getRelativeTimeSpanString(
                now - uptime,
                now,
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            )
        when (status) {
            IpRuleStatus.TRUST -> {
                b.customIpLastUpdated.text =
                    requireContext().getString(
                        R.string.ci_desc,
                        requireContext().getString(R.string.ci_trust_txt),
                        time
                    )
            }

            IpRuleStatus.BLOCK -> {
                b.customIpLastUpdated.text =
                    requireContext().getString(
                        R.string.ci_desc,
                        requireContext().getString(R.string.lbl_blocked),
                        time
                    )
            }

            IpRuleStatus.NONE -> {
                b.customIpLastUpdated.text =
                    requireContext().getString(
                        R.string.ci_desc,
                        requireContext().getString(R.string.cd_no_rule_txt),
                        time
                    )
            }
            IpRuleStatus.BYPASS_UNIVERSAL -> {
                b.customIpLastUpdated.text =
                    requireContext().getString(
                        R.string.ci_desc,
                        requireContext().getString(R.string.ci_bypass_universal_txt),
                        time
                    )
            }
        }
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
            uiCtx {
                updateToggleGroup(id)
                updateStatusUi(id, ci.modifiedDateTime)
                Logger.v(LOG_TAG_UI, "${TAG}: changeIpStatus: ${ci.ipAddress}, status: ${id.name}")
            }
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

    data class ToggleBtnUi(val txtColor: Int, val bgColor: Int)

    private fun getToggleBtnUiParams(id: IpRuleStatus): ToggleBtnUi {
        return when (id) {
            IpRuleStatus.NONE -> ToggleBtnUi(
                fetchColor(requireContext(), R.attr.chipTextNeutral),
                fetchColor(requireContext(), R.attr.chipBgColorNeutral)
            )
            IpRuleStatus.BLOCK -> ToggleBtnUi(
                fetchColor(requireContext(), R.attr.chipTextNegative),
                fetchColor(requireContext(), R.attr.chipBgColorNegative)
            )
            IpRuleStatus.BYPASS_UNIVERSAL -> ToggleBtnUi(
                fetchColor(requireContext(), R.attr.chipTextPositive),
                fetchColor(requireContext(), R.attr.chipBgColorPositive)
            )
            IpRuleStatus.TRUST -> ToggleBtnUi(
                fetchColor(requireContext(), R.attr.chipTextPositive),
                fetchColor(requireContext(), R.attr.chipBgColorPositive)
            )
        }
    }

    private fun selectRowUi(badge: AppCompatTextView, rb: RadioButton, t: ToggleBtnUi) {
        badge.setTextColor(t.txtColor)
        badge.backgroundTintList = ColorStateList.valueOf(t.bgColor)
        rb.isChecked = true
    }

    private fun unselectRowUi(badge: AppCompatTextView, rb: RadioButton) {
        badge.setTextColor(fetchToggleBtnColors(requireContext(), R.color.defaultToggleBtnTxt))
        badge.backgroundTintList = ColorStateList.valueOf(
            fetchToggleBtnColors(requireContext(), R.color.defaultToggleBtnBg)
        )
        rb.isChecked = false
    }

    private fun updateToggleGroup(id: IpRuleStatus) {
        val t = getToggleBtnUiParams(id)

        // Unselect all rows first
        unselectRowUi(b.customIpBadgeNoRule, b.customIpRbNoRule)
        unselectRowUi(b.customIpBadgeBlock, b.customIpRbBlock)
        unselectRowUi(b.customIpBadgeBypassUniv, b.customIpRbBypassUniv)
        unselectRowUi(b.customIpBadgeBypassApp, b.customIpRbBypassApp)

        // Select the active row
        when (id) {
            IpRuleStatus.NONE -> selectRowUi(b.customIpBadgeNoRule, b.customIpRbNoRule, t)
            IpRuleStatus.BLOCK -> selectRowUi(b.customIpBadgeBlock, b.customIpRbBlock, t)
            IpRuleStatus.BYPASS_UNIVERSAL -> selectRowUi(b.customIpBadgeBypassUniv, b.customIpRbBypassUniv, t)
            IpRuleStatus.TRUST -> selectRowUi(b.customIpBadgeBypassApp, b.customIpRbBypassApp, t)
        }
    }

    private fun showWgListBtmSheet(data: List<WgConfigFilesImmutable?>) {
        val bottomSheetFragment = WireguardListBtmSheet.newInstance(WireguardListBtmSheet.InputType.IP, ci, data, this)
        bottomSheetFragment.show(
            requireActivity().supportFragmentManager,
            bottomSheetFragment.tag
        )
    }

    private fun showProxyCountriesBtmSheet(data: List<String>) {
        Logger.v(LOG_TAG_UI, "$TAG: show pcc btm sheet for ${ci.ipAddress}")
        val bottomSheetFragment = ProxyCountriesBtmSheet.newInstance(ProxyCountriesBtmSheet.InputType.IP, ci, data, this)
        bottomSheetFragment.show(
            requireActivity().supportFragmentManager,
            bottomSheetFragment.tag
        )
    }

    private fun showDialogForDelete() {
        val builder = MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
        builder.setTitle(R.string.univ_firewall_dialog_title)
        builder.setMessage(R.string.univ_firewall_dialog_message)
        builder.setCancelable(true)
        builder.setPositiveButton(requireContext().getString(R.string.lbl_delete)) { _, _ ->
            io { IpRulesManager.removeIpRule(ci.uid, ci.ipAddress, ci.port) }
            Utilities.showToastUiCentered(
                requireContext(),
                requireContext().getString(R.string.univ_ip_delete_individual_toast, ci.ipAddress),
                Toast.LENGTH_SHORT
            )
            dismiss()
            logEvent("Deleted custom IP rule for ${ci.ipAddress}")
        }

        builder.setNegativeButton(requireContext().getString(R.string.lbl_cancel)) { _, _ ->
            updateToggleGroup(IpRuleStatus.getStatus(ci.status))
        }

        builder.create().show()
    }


    override fun onDismissCC(obj: Any?) {
        try {
            val cip = obj as CustomIp
            this.ci = cip
            val status = IpRuleStatus.getStatus(cip.status)
            updateToggleGroup(status)
            updateStatusUi(status, cip.modifiedDateTime)
            Logger.v(LOG_TAG_UI, "$TAG: onDismissCC: ${cip.ipAddress}, ${cip.proxyCC}")
        } catch (e: Exception) {
            Logger.w(LOG_TAG_UI, "$TAG: err in onDismissCC ${e.message}", e)
        }
    }

    override fun onDismissWg(obj: Any?) {
        try {
            val cip = obj as CustomIp
            ci = cip
            val status = IpRuleStatus.getStatus(cip.status)
            updateToggleGroup(status)
            updateStatusUi(status, cip.modifiedDateTime)
            Logger.v(LOG_TAG_UI, "$TAG: onDismissWg: ${cip.ipAddress}, ${cip.proxyCC}")
        } catch (e: Exception) {
            Logger.w(LOG_TAG_UI, "$TAG: err in onDismissWg ${e.message}", e)
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        Logger.v(LOG_TAG_UI, "$TAG: onDismiss; ip: ${ci.ipAddress}")
    }

    private fun logEvent(details: String) {
        eventLogger.log(EventType.FW_RULE_MODIFIED, Severity.LOW, "Custom IP", EventSource.UI, false, details)
    }

    private fun io(f: suspend () -> Unit) {
        (requireContext() as LifecycleOwner).lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }

}
