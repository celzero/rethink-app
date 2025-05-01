package com.celzero.bravedns.ui.bottomsheet

import Logger
import Logger.LOG_TAG_UI
import android.content.DialogInterface
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
import com.celzero.bravedns.database.CustomIp
import com.celzero.bravedns.database.WgConfigFiles
import com.celzero.bravedns.database.WgConfigFilesImmutable
import com.celzero.bravedns.databinding.BottomSheetCustomIpsBinding
import com.celzero.bravedns.rpnproxy.RegionalWgConf
import com.celzero.bravedns.rpnproxy.RpnProxyManager
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class CustomIpRulesBtmSheet(private var ci: CustomIp) :
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
        Logger.v(LOG_TAG_UI, "$TAG: onViewCreated for ${ci.ipAddress}")
        init()
        initClickListeners()

        b.chooseProxyCard.setOnClickListener {
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
                val ctrys = RpnProxyManager.getProtonUniqueCC()
                if (ctrys.isEmpty()) {
                    Logger.w(LOG_TAG_UI, "$TAG No country codes found")
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
                    Logger.v(LOG_TAG_UI, "$TAG show country list(${ctrys.size}) for ${ci.ipAddress}")
                    showProxyCountriesBtmSheet(ctrys)
                }
            }
        }
    }

    private fun init() {
        val uid = ci.uid
        Logger.v(LOG_TAG_UI, "$TAG: init for ${ci.ipAddress}, uid: $uid")
        val rules = IpRulesManager.IpRuleStatus.getStatus(ci.status)
        b.customDomainTv.text = ci.ipAddress
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

    private suspend fun byPassUniversal(ci: CustomIp) {
        Logger.v(LOG_TAG_UI, "$TAG: set ${ci.ipAddress} to bypass universal")
        IpRulesManager.updateBypass(ci)
    }

    private suspend fun byPassAppRule(ci: CustomIp) {
        Logger.v(LOG_TAG_UI, "$TAG: set ${ci.ipAddress} to bypass app")
        IpRulesManager.updateTrust(ci)
    }

    private suspend fun blockIp(ci: CustomIp) {
        Logger.v(LOG_TAG_UI, "$TAG: block ${ci.ipAddress}")
        IpRulesManager.updateBlock(ci)
    }

    private suspend fun noRuleIp(ci: CustomIp) {
        Logger.v(LOG_TAG_UI, "$TAG: no rule for ${ci.ipAddress}")
        IpRulesManager.updateNoRule(ci)
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

    private fun showWgListBtmSheet(data: List<WgConfigFilesImmutable?>) {
        val bottomSheetFragment = WireguardListBtmSheet.newInstance(WireguardListBtmSheet.InputType.IP, ci, data, this)
        bottomSheetFragment.show(
            requireActivity().supportFragmentManager,
            bottomSheetFragment.tag
        )
    }

    private fun showProxyCountriesBtmSheet(data: List<RegionalWgConf>) {
        Logger.v(LOG_TAG_UI, "$TAG: show pcc btm sheet for ${ci.ipAddress}")
        val bottomSheetFragment = ProxyCountriesBtmSheet.newInstance(ProxyCountriesBtmSheet.InputType.IP, ci, data, this)
        bottomSheetFragment.show(
            requireActivity().supportFragmentManager,
            bottomSheetFragment.tag
        )
    }

    override fun onDismissCC(obj: Any?) {
        try {
            val ci = obj as CustomIp
            this.ci = ci
            updateToggleGroup(IpRulesManager.IpRuleStatus.getStatus(ci.status))
            Logger.v(LOG_TAG_UI, "$TAG: onDismissCC: ${ci.ipAddress}, ${ci.proxyCC}")
        } catch (e: Exception) {
            Logger.w(LOG_TAG_UI, "$TAG: err in onDismissCC ${e.message}", e)
        }
    }

    override fun onDismissWg(obj: Any?) {
        try {
            val cip = obj as CustomIp
            ci = cip
            updateToggleGroup(IpRulesManager.IpRuleStatus.getStatus(cip.status))
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