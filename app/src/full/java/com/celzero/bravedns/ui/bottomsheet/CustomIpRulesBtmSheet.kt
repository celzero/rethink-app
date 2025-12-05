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
import android.widget.Toast
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.celzero.bravedns.R
import com.celzero.bravedns.database.CustomIp
import com.celzero.bravedns.database.WgConfigFilesImmutable
import com.celzero.bravedns.databinding.BottomSheetCustomIpsBinding
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.service.IpRulesManager.IpRuleStatus
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import com.celzero.bravedns.util.Themes.Companion.getBottomsheetCurrentTheme
import com.celzero.bravedns.util.UIUtils.fetchColor
import com.celzero.bravedns.util.UIUtils.fetchToggleBtnColors
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.useTransparentNoDimBackground
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class CustomIpRulesBtmSheet() :
    BottomSheetDialogFragment(), ProxyCountriesBtmSheet.CountriesDismissListener, WireguardListBtmSheet.WireguardDismissListener {
    private var _binding: BottomSheetCustomIpsBinding? = null

    // This property is only valid between onCreateView and onDestroyView.
    private val b
        get() = _binding!!

    private val persistentState by inject<PersistentState>()

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
        getBottomsheetCurrentTheme(isDarkThemeOn(), persistentState.theme)

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
            if (isAtleastQ()) {
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                controller.isAppearanceLightNavigationBars = false
                window.isNavigationBarContrastEnforced = false
            }
        }
        Logger.v(LOG_TAG_UI, "$TAG: onViewCreated for ${ci.ipAddress}")
        init()
        initClickListeners()
    }

    private fun init() {
        val uid = ci.uid
        io {
            if (uid == UID_EVERYBODY) {
                b.customIpAppNameTv.text = getString(R.string.firewall_act_universal_tab).replaceFirstChar(Char::titlecase)
                b.customIpAppIconIv.visibility = View.GONE
            } else {
                val appNames = FirewallManager.getAppNamesByUid(ci.uid)
                val appName = getAppName(ci.uid, appNames)
                val appInfo = FirewallManager.getAppInfoByUid(ci.uid)
                uiCtx {
                    b.customIpAppNameTv.text = appName
                    displayIcon(
                        Utilities.getIcon(
                            requireContext(),
                            appInfo?.packageName ?: "",
                            appInfo?.appName ?: ""
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
        b.customIpToggleGroup.tag = 1
        updateToggleGroup(rules)
        updateStatusUi(rules, ci.modifiedDateTime)
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
        b.customIpToggleGroup.addOnButtonCheckedListener(ipRulesGroupListener)

        b.customIpDeleteChip.setOnClickListener {
            showDialogForDelete()
        }

       /* b.chooseProxyCard.setOnClickListener {
            val ctx = requireContext()
            var v: MutableList<WgConfigFilesImmutable?> = mutableListOf()
            io {
                v.add(null)
                v.addAll(WireguardManager.getAllMappings())
                if (v.isEmpty() || v.size == 1) {
                    Logger.w(LOG_TAG_UI, "$TAG No Wireguard configs found")
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
                    Logger.v(LOG_TAG_UI, "$TAG show wg list(${v.size}) for ${ci.ipAddress}")
                    showWgListBtmSheet(v)
                }
            }
        }

        b.chooseCountryCard.setOnClickListener {
            io {
                val ctrys = emptyList<String>()//RpnProxyManager.getProtonUniqueCC()
                if (ctrys.isEmpty()) {
                    Logger.w(LOG_TAG_UI, "$TAG No country codes found")
                    uiCtx {
                        Utilities.showToastUiCentered(
                            requireContext(),
                            "No country codes found",
                            Toast.LENGTH_SHORT
                        )
                    }
                    return@io
                }
                uiCtx {
                    Logger.v(
                        LOG_TAG_UI,
                        "$TAG show country list(${ctrys.size}) for ${ci.ipAddress}"
                    )
                    showProxyCountriesBtmSheet(ctrys)
                }
            }
        }*/
    }

    private val ipRulesGroupListener =
        MaterialButtonToggleGroup.OnButtonCheckedListener { group, checkedId, isChecked ->
            val b: MaterialButton = b.customIpToggleGroup.findViewById(checkedId)
            val statusId = findSelectedIpRule(getTag(b.tag))
            if (statusId == null) {
                Logger.i(LOG_TAG_UI, "$TAG: statusId is null")
                return@OnButtonCheckedListener
            }

            if (isChecked) {
                // checked change listener is called multiple times, even for position change
                // so, check if the status has changed or not
                // also see CustomDomainAdapter#domainRulesGroupListener
                val hasStatusChanged = ci.status != statusId.id
                if (hasStatusChanged) {
                    val t = getToggleBtnUiParams(statusId)
                    // update the toggle button
                    selectToggleBtnUi(b, t)

                    changeIpStatus(statusId, ci)
                } else {
                    // no-op
                }
            } else {
                unselectToggleBtnUi(b)
            }
        }

    private fun findSelectedIpRule(ruleId: Int): IpRulesManager.IpRuleStatus? {
        return when (ruleId) {
            IpRuleStatus.NONE.id -> {
                IpRuleStatus.NONE
            }
            IpRuleStatus.BLOCK.id -> {
                IpRuleStatus.BLOCK
            }
            IpRuleStatus.BYPASS_UNIVERSAL.id -> {
                IpRuleStatus.BYPASS_UNIVERSAL
            }
            IpRuleStatus.TRUST.id -> {
                IpRuleStatus.TRUST
            }
            else -> {
                null
            }
        }
    }

    private fun updateStatusUi(status: IpRulesManager.IpRuleStatus, modifiedTs: Long) {
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
            IpRulesManager.IpRuleStatus.TRUST -> {
                b.customIpLastUpdated.text =
                    requireContext().getString(
                        R.string.ci_desc,
                        requireContext().getString(R.string.ci_trust_txt),
                        time
                    )
            }

            IpRulesManager.IpRuleStatus.BLOCK -> {
                b.customIpLastUpdated.text =
                    requireContext().getString(
                        R.string.ci_desc,
                        requireContext().getString(R.string.lbl_blocked),
                        time
                    )
            }

            IpRulesManager.IpRuleStatus.NONE -> {
                b.customIpLastUpdated.text =
                    requireContext().getString(
                        R.string.ci_desc,
                        requireContext().getString(R.string.cd_no_rule_txt),
                        time
                    )
            }
            IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL -> {
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
        return copy
    }

    private suspend fun byPassAppRule(orig: CustomIp): CustomIp {
        Logger.i(LOG_TAG_UI, "$TAG: set ${orig.ipAddress} to bypass app")
        val copy = orig.deepCopy()
        IpRulesManager.updateTrust(copy)
        return copy
    }

    private suspend fun blockIp(orig: CustomIp): CustomIp {
        Logger.i(LOG_TAG_UI, "$TAG: block ${orig.ipAddress}")
        val copy = orig.deepCopy()
        IpRulesManager.updateBlock(copy)
        return copy
    }

    private suspend fun noRuleIp(orig: CustomIp): CustomIp {
        Logger.i(LOG_TAG_UI, "$TAG: no rule for ${orig.ipAddress}")
        val copy = orig.deepCopy()
        IpRulesManager.updateNoRule(copy)
        return copy
    }

    private fun selectToggleBtnUi(btn: MaterialButton, toggleBtnUi: ToggleBtnUi) {
        btn.setTextColor(toggleBtnUi.txtColor)
        btn.backgroundTintList = ColorStateList.valueOf(toggleBtnUi.bgColor)
    }

    private fun unselectToggleBtnUi(btn: MaterialButton) {
        btn.setTextColor(fetchToggleBtnColors(requireContext(), R.color.defaultToggleBtnTxt))
        btn.backgroundTintList =
            ColorStateList.valueOf(
                fetchToggleBtnColors(
                    requireContext(),
                    R.color.defaultToggleBtnBg
                )
            )
    }

    data class ToggleBtnUi(val txtColor: Int, val bgColor: Int)

    private fun getToggleBtnUiParams(id: IpRuleStatus): ToggleBtnUi {
        return when (id) {
            IpRuleStatus.NONE -> {
                ToggleBtnUi(
                    fetchColor(requireContext(), R.attr.chipTextNeutral),
                    fetchColor(requireContext(), R.attr.chipBgColorNeutral)
                )
            }

            IpRuleStatus.BLOCK -> {
                ToggleBtnUi(
                    fetchColor(requireContext(), R.attr.chipTextNegative),
                    fetchColor(requireContext(), R.attr.chipBgColorNegative)
                )
            }

            IpRuleStatus.BYPASS_UNIVERSAL -> {
                ToggleBtnUi(
                    fetchColor(requireContext(), R.attr.chipTextPositive),
                    fetchColor(requireContext(), R.attr.chipBgColorPositive)
                )
            }

            IpRuleStatus.TRUST -> {
                ToggleBtnUi(
                    fetchColor(requireContext(), R.attr.chipTextPositive),
                    fetchColor(requireContext(), R.attr.chipBgColorPositive)
                )
            }
        }
    }

    private fun updateToggleGroup(id: IpRuleStatus) {
        val t = getToggleBtnUiParams(id)

        when (id) {
            IpRuleStatus.NONE -> {
                b.customIpToggleGroup.check(b.customIpTgNoRule.id)
                selectToggleBtnUi(b.customIpTgNoRule, t)
                unselectToggleBtnUi(b.customIpTgBlock)
                unselectToggleBtnUi(b.customIpTgBypassUniv)
                unselectToggleBtnUi(b.customIpTgBypassApp)
            }

            IpRuleStatus.BLOCK -> {
                b.customIpToggleGroup.check(b.customIpTgBlock.id)
                selectToggleBtnUi(b.customIpTgBlock, t)
                unselectToggleBtnUi(b.customIpTgNoRule)
                unselectToggleBtnUi(b.customIpTgBypassUniv)
                unselectToggleBtnUi(b.customIpTgBypassApp)
            }

            IpRuleStatus.BYPASS_UNIVERSAL -> {
                b.customIpToggleGroup.check(b.customIpTgBypassUniv.id)
                selectToggleBtnUi(b.customIpTgBypassUniv, t)
                unselectToggleBtnUi(b.customIpTgBlock)
                unselectToggleBtnUi(b.customIpTgNoRule)
                unselectToggleBtnUi(b.customIpTgBypassApp)
            }

            IpRuleStatus.TRUST -> {
                b.customIpToggleGroup.check(b.customIpTgBypassApp.id)
                selectToggleBtnUi(b.customIpTgBypassApp, t)
                unselectToggleBtnUi(b.customIpTgBlock)
                unselectToggleBtnUi(b.customIpTgNoRule)
                unselectToggleBtnUi(b.customIpTgBypassUniv)
            }
        }
    }

    // each button in the toggle group is associated with tag value.
    // tag values are ids of DomainRulesManager.DomainStatus
    private fun getTag(tag: Any): Int {
        return tag.toString().toIntOrNull() ?: 0
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

    private fun io(f: suspend () -> Unit) {
        (requireContext() as LifecycleOwner).lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }

}