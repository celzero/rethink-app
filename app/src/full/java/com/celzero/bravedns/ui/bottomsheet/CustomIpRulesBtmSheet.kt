package com.celzero.bravedns.ui.bottomsheet

import Logger
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.celzero.bravedns.R
import com.celzero.bravedns.database.CustomDomain
import com.celzero.bravedns.database.CustomIp
import com.celzero.bravedns.databinding.BottomSheetCustomIpsBinding
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import com.celzero.bravedns.util.Themes.Companion.getBottomsheetCurrentTheme
import com.celzero.bravedns.util.UIUtils.fetchColor
import com.celzero.bravedns.util.UIUtils.fetchToggleBtnColors
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.wireguard.Config
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class CustomIpRulesBtmSheet(private var customIp: CustomIp) :
    BottomSheetDialogFragment(), ProxyCountriesBtmSheet.CountriesDismissListener, WireguardListBtmSheet.WireguardDismissListener {
    private var _binding: BottomSheetCustomIpsBinding? = null

    // This property is only valid between onCreateView and onDestroyView.
    private val b
        get() = _binding!!

    private val persistentState by inject<PersistentState>()

    override fun getTheme(): Int =
        getBottomsheetCurrentTheme(isDarkThemeOn(), persistentState.theme)

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
    }

    companion object {
        private const val TAG = "CIRBtmSht"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetCustomIpsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        init()
        initClickListeners()

        b.chooseProxyCard.setOnClickListener {
            val ctx = requireContext()
            io {
                val v = WireguardManager.getAllConfigs()
                if (v.isEmpty()) {
                    uiCtx {
                        Utilities.showToastUiCentered(
                            ctx,
                            "No ProtonVPN country codes found",
                            Toast.LENGTH_SHORT
                        )
                    }
                    return@io
                }
                uiCtx {
                    //showRecyclerBottomSheet(names, ctx)
                    showWgListBtmSheet(v)
                    Logger.d("TEST", "Button 1 clicked")
                }
            }
        }

        b.chooseCountryCard.setOnClickListener {
            io {
                val ctrys = RpnProxyManager.getProtonUniqueCC()
                if (ctrys.isEmpty()) {
                    uiCtx {
                        Utilities.showToastUiCentered(
                            requireContext(),
                            "No ProtonVPN country codes found",
                            Toast.LENGTH_SHORT
                        )
                    }
                    return@io
                }
                uiCtx {
                    //showRecyclerBottomSheet(ctrys, requireContext())
                    showProxyCountriesBtmSheet(ctrys)
                    Logger.d("TEST", "Button 2 clicked")
                }
            }
        }
    }

    private fun init() {
        val uid = customIp.uid
        Logger.d("TEST", "UID: $uid, Domain: $customIp.domain")
        val rules = IpRulesManager.IpRuleStatus.getStatus(customIp.status)
        b.customDomainTv.text = customIp.ipAddress
        showBypassUi(uid)
        b.customIpToggleGroup.tag = 1
        updateToggleGroup(rules)
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
    }

    private val ipRulesGroupListener =
        MaterialButtonToggleGroup.OnButtonCheckedListener { group, checkedId, isChecked ->
            val b: MaterialButton = b.customIpToggleGroup.findViewById(checkedId)
            val statusId = findSelectedIpRule(getTag(b.tag))
            if (statusId == null) {
                Logger.d(TAG, "Status id is null")
                return@OnButtonCheckedListener
            }

            if (isChecked) {
                // checked change listener is called multiple times, even for position change
                // so, check if the status has changed or not
                // also see CustomDomainAdapter#domainRulesGroupListener
                val hasStatusChanged = customIp.status != statusId.id
                if (hasStatusChanged) {
                    val t = getToggleBtnUiParams(statusId)
                    // update the toggle button
                    selectToggleBtnUi(b, t)

                    changeIpStatus(statusId, customIp)
                } else {
                    // no-op
                }
            } else {
                unselectToggleBtnUi(b)
            }
        }

    private fun findSelectedIpRule(ruleId: Int): IpRulesManager.IpRuleStatus? {
        return when (ruleId) {
            IpRulesManager.IpRuleStatus.NONE.id -> {
                IpRulesManager.IpRuleStatus.NONE
            }

            IpRulesManager.IpRuleStatus.BLOCK.id -> {
                IpRulesManager.IpRuleStatus.BLOCK
            }

            IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL.id -> {
                IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL
            }

            IpRulesManager.IpRuleStatus.TRUST.id -> {
                IpRulesManager.IpRuleStatus.TRUST
            }

            else -> {
                null
            }
        }
    }

    private fun changeIpStatus(id: IpRulesManager.IpRuleStatus, customIp: CustomIp) {
        io {
            when (id) {
                IpRulesManager.IpRuleStatus.NONE -> {
                    noRuleIp(customIp)
                }

                IpRulesManager.IpRuleStatus.BLOCK -> {
                    blockIp(customIp)
                }

                IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL -> {
                    byPassUniversal(customIp)
                }

                IpRulesManager.IpRuleStatus.TRUST -> {
                    byPassAppRule(customIp)
                }
            }
        }
    }

    private suspend fun byPassUniversal(customIp: CustomIp) {
        IpRulesManager.updateBypass(customIp)
    }

    private suspend fun byPassAppRule(customIp: CustomIp) {
        IpRulesManager.updateTrust(customIp)
    }

    private suspend fun blockIp(customIp: CustomIp) {
        IpRulesManager.updateBlock(customIp)
    }

    private suspend fun noRuleIp(customIp: CustomIp) {
        IpRulesManager.updateNoRule(customIp)
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

    private suspend fun whitelist(cd: CustomDomain) {
        DomainRulesManager.trust(cd)
    }

    private suspend fun block(cd: CustomDomain) {
        DomainRulesManager.block(cd)
    }

    private suspend fun noRule(cd: CustomDomain) {
        DomainRulesManager.noRule(cd)
    }

    data class ToggleBtnUi(val txtColor: Int, val bgColor: Int)

    private fun showDialogForDelete(customDomain: CustomDomain) {
        val builder = MaterialAlertDialogBuilder(requireContext())
        builder.setTitle(R.string.cd_remove_dialog_title)
        builder.setMessage(R.string.cd_remove_dialog_message)
        builder.setCancelable(true)
        builder.setPositiveButton(requireContext().getString(R.string.lbl_delete)) { _, _ ->
            io { DomainRulesManager.deleteDomain(customDomain) }
            Utilities.showToastUiCentered(
                requireContext(),
                requireContext().getString(R.string.cd_toast_deleted),
                Toast.LENGTH_SHORT
            )
        }

        builder.setNegativeButton(requireContext().getString(R.string.lbl_cancel)) { _, _ ->
            // no-op
        }
        builder.create().show()
    }

    private fun findSelectedRuleByTag(ruleId: Int): DomainRulesManager.Status? {
        return when (ruleId) {
            DomainRulesManager.Status.NONE.id -> {
                DomainRulesManager.Status.NONE
            }

            DomainRulesManager.Status.TRUST.id -> {
                DomainRulesManager.Status.TRUST
            }

            DomainRulesManager.Status.BLOCK.id -> {
                DomainRulesManager.Status.BLOCK
            }

            else -> {
                null
            }
        }
    }

    private fun getToggleBtnUiParams(id: IpRulesManager.IpRuleStatus): ToggleBtnUi {
        return when (id) {
            IpRulesManager.IpRuleStatus.NONE -> {
                ToggleBtnUi(
                    fetchColor(requireContext(), R.attr.chipTextNeutral),
                    fetchColor(requireContext(), R.attr.chipBgColorNeutral)
                )
            }

            IpRulesManager.IpRuleStatus.BLOCK -> {
                ToggleBtnUi(
                    fetchColor(requireContext(), R.attr.chipTextNegative),
                    fetchColor(requireContext(), R.attr.chipBgColorNegative)
                )
            }

            IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL -> {
                ToggleBtnUi(
                    fetchColor(requireContext(), R.attr.chipTextPositive),
                    fetchColor(requireContext(), R.attr.chipBgColorPositive)
                )
            }

            IpRulesManager.IpRuleStatus.TRUST -> {
                ToggleBtnUi(
                    fetchColor(requireContext(), R.attr.chipTextPositive),
                    fetchColor(requireContext(), R.attr.chipBgColorPositive)
                )
            }
        }
    }

    private fun updateToggleGroup(id: IpRulesManager.IpRuleStatus) {
        val t = getToggleBtnUiParams(id)

        when (id) {
            IpRulesManager.IpRuleStatus.NONE -> {
                b.customIpToggleGroup.check(b.customIpTgNoRule.id)
                selectToggleBtnUi(b.customIpTgNoRule, t)
                unselectToggleBtnUi(b.customIpTgBlock)
                unselectToggleBtnUi(b.customIpTgBypassUniv)
                unselectToggleBtnUi(b.customIpTgBypassApp)
            }

            IpRulesManager.IpRuleStatus.BLOCK -> {
                b.customIpToggleGroup.check(b.customIpTgBlock.id)
                selectToggleBtnUi(b.customIpTgBlock, t)
                unselectToggleBtnUi(b.customIpTgNoRule)
                unselectToggleBtnUi(b.customIpTgBypassUniv)
                unselectToggleBtnUi(b.customIpTgBypassApp)
            }

            IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL -> {
                b.customIpToggleGroup.check(b.customIpTgBypassUniv.id)
                selectToggleBtnUi(b.customIpTgBypassUniv, t)
                unselectToggleBtnUi(b.customIpTgBlock)
                unselectToggleBtnUi(b.customIpTgNoRule)
                unselectToggleBtnUi(b.customIpTgBypassApp)
            }

            IpRulesManager.IpRuleStatus.TRUST -> {
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

    private fun showWgListBtmSheet(data: List<Config>) {
        val bottomSheetFragment =
            WireguardListBtmSheet(WireguardListBtmSheet.InputType.IP, customIp, data, this)
        bottomSheetFragment.show(
            requireActivity().supportFragmentManager,
            bottomSheetFragment.tag
        )
    }

    private fun showProxyCountriesBtmSheet(data: List<String>) {
        Logger.i("TEST", "showProxyCountriesBtmSheet: ${customIp.ipAddress}, ${customIp.proxyCC}")
        val bottomSheetFragment = ProxyCountriesBtmSheet(ProxyCountriesBtmSheet.InputType.IP, customIp, data, this)
        bottomSheetFragment.show(
            requireActivity().supportFragmentManager,
            bottomSheetFragment.tag
        )
    }

    override fun onDismissCC(obj: Any?) {
        try {
            val ci = obj as CustomIp
            customIp = ci
            updateToggleGroup(IpRulesManager.IpRuleStatus.getStatus(ci.status))
            Logger.i("TEST", "onDismissCC: ${ci.ipAddress},  ${ci.proxyCC}")
        } catch (e: Exception) {
            Logger.e("TEST", "err in onDismiss", e)
        }
    }

    override fun onDismissWg(obj: Any?) {
        try {
            val ci = obj as CustomIp
            customIp = ci
            updateToggleGroup(IpRulesManager.IpRuleStatus.getStatus(ci.status))
            Logger.i("TEST", "onDismissWg: ${ci.ipAddress},  ${ci.proxyCC}")
        } catch (e: Exception) {
            Logger.e("TEST", "err in onDismiss", e)
        }
    }

    private fun io(f: suspend () -> Unit) {
        (requireContext() as LifecycleOwner).lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }

}