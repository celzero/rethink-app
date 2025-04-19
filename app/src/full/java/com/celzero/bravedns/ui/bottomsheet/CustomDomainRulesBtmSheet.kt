package com.celzero.bravedns.ui.bottomsheet

import Logger
import Logger.LOG_TAG_UI
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.celzero.bravedns.R
import com.celzero.bravedns.database.CustomDomain
import com.celzero.bravedns.database.WgConfigFilesImmutable
import com.celzero.bravedns.databinding.BottomSheetCustomDomainsBinding
import com.celzero.bravedns.rpnproxy.RegionalWgConf
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.WireguardManager
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

class CustomDomainRulesBtmSheet(private var cd: CustomDomain) :
    BottomSheetDialogFragment(), ProxyCountriesBtmSheet.CountriesDismissListener, WireguardListBtmSheet.WireguardDismissListener {
    private var _binding: BottomSheetCustomDomainsBinding? = null

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
        private const val TAG = "CDRBtmSht"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetCustomDomainsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Logger.v(LOG_TAG_UI, "$TAG, view created for ${cd.domain}")
        init()
        initClickListeners()

        b.chooseProxyCard.setOnClickListener {
            val ctx = requireContext()
            io {
                val v = WireguardManager.getAllMappings()
                if (v.isEmpty()) {
                    Logger.v(LOG_TAG_UI, "$TAG no wireguard configs found")
                    uiCtx {
                        Utilities.showToastUiCentered(
                            ctx,
                            "No country codes found",
                            Toast.LENGTH_SHORT
                        )
                    }
                    return@io
                }
                uiCtx {
                    Logger.v(LOG_TAG_UI, "$TAG show wg list(${v.size} for ${cd.domain}")
                    showWgListBtmSheet(v)
                }
            }
        }

        b.chooseCountryCard.setOnClickListener {
            io {
                val ctrys = RpnProxyManager.getProtonUniqueCC()
                if (ctrys.isEmpty()) {
                    Logger.v(LOG_TAG_UI, "$TAG no country codes found")
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
                    Logger.v(LOG_TAG_UI, "$TAG show countries(${ctrys.size} for ${cd.domain}")
                    showProxyCountriesBtmSheet(ctrys)
                }
            }
        }
    }

    private fun init() {
        val uid = cd.uid
        Logger.v(LOG_TAG_UI, "$TAG, init for ${cd.domain}, uid: $uid")
        val rules = DomainRulesManager.getDomainRule(cd.domain, uid)
        b.customDomainTv.text = cd.domain
        updateStatusUi(
            DomainRulesManager.Status.getStatus(cd.status),
            cd.modifiedTs
        )
        b.customDomainToggleGroup.tag = 1
        updateToggleGroup(rules.id)
    }

    private fun updateStatusUi(status: DomainRulesManager.Status, modifiedTs: Long) {
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
            DomainRulesManager.Status.TRUST -> {
                b.customDomainLastUpdated.text =
                    requireContext().getString(
                        R.string.ci_desc,
                        requireContext().getString(R.string.ci_trust_txt),
                        time
                    )
            }

            DomainRulesManager.Status.BLOCK -> {
                b.customDomainLastUpdated.text =
                    requireContext().getString(
                        R.string.ci_desc,
                        requireContext().getString(R.string.lbl_blocked),
                        time
                    )
            }

            DomainRulesManager.Status.NONE -> {
                b.customDomainLastUpdated.text =
                    requireContext().getString(
                        R.string.ci_desc,
                        requireContext().getString(R.string.cd_no_rule_txt),
                        time
                    )
            }
        }
    }

    private fun initClickListeners() {
        b.customDomainToggleGroup.addOnButtonCheckedListener(domainRulesGroupListener)
    }

    private val domainRulesGroupListener =
        MaterialButtonToggleGroup.OnButtonCheckedListener { group, checkedId, isChecked ->
            val b: MaterialButton = b.customDomainToggleGroup.findViewById(checkedId)

            val statusId = findSelectedRuleByTag(getTag(b.tag))
            // delete button
            if (statusId == null && isChecked) {
                group.clearChecked()
                showDialogForDelete(cd)
                return@OnButtonCheckedListener
            }

            // invalid selection
            if (statusId == null) {
                return@OnButtonCheckedListener
            }

            if (isChecked) {
                // See CustomIpAdapter.kt for the same code (ipRulesGroupListener)
                val hasStatusChanged = cd.status != statusId.id
                if (!hasStatusChanged) {
                    return@OnButtonCheckedListener
                }
                val t = toggleBtnUi(statusId)
                // update toggle button
                selectToggleBtnUi(b, t)
                // change status based on selected btn
                changeDomainStatus(statusId, cd)
            } else {
                unselectToggleBtnUi(b)
            }
        }

    private fun selectToggleBtnUi(b: MaterialButton, toggleBtnUi: ToggleBtnUi) {
        b.setTextColor(toggleBtnUi.txtColor)
        b.backgroundTintList = ColorStateList.valueOf(toggleBtnUi.bgColor)
    }

    private fun changeDomainStatus(id: DomainRulesManager.Status, cd: CustomDomain) {
        io {
            when (id) {
                DomainRulesManager.Status.NONE -> {
                    noRule(cd)
                }

                DomainRulesManager.Status.BLOCK -> {
                    block(cd)
                }

                DomainRulesManager.Status.TRUST -> {
                    whitelist(cd)
                }
            }
        }
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

    private fun unselectToggleBtnUi(b: MaterialButton) {
        b.setTextColor(fetchToggleBtnColors(requireContext(), R.color.defaultToggleBtnTxt))
        b.backgroundTintList =
            ColorStateList.valueOf(
                fetchToggleBtnColors(
                    requireContext(),
                    R.color.defaultToggleBtnBg
                )
            )
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

    private fun toggleBtnUi(id: DomainRulesManager.Status): ToggleBtnUi {
        return when (id) {
            DomainRulesManager.Status.NONE -> {
                ToggleBtnUi(
                    fetchColor(requireContext(), R.attr.chipTextNeutral),
                    fetchColor(requireContext(), R.attr.chipBgColorNeutral)
                )
            }

            DomainRulesManager.Status.BLOCK -> {
                ToggleBtnUi(
                    fetchColor(requireContext(), R.attr.chipTextNegative),
                    fetchColor(requireContext(), R.attr.chipBgColorNegative)
                )
            }

            DomainRulesManager.Status.TRUST -> {
                ToggleBtnUi(
                    fetchColor(requireContext(), R.attr.chipTextPositive),
                    fetchColor(requireContext(), R.attr.chipBgColorPositive)
                )
            }
        }
    }

    private fun updateToggleGroup(id: Int) {
        val fid = findSelectedRuleByTag(id) ?: return

        val t = toggleBtnUi(fid)

        when (id) {
            DomainRulesManager.Status.NONE.id -> {
                b.customDomainToggleGroup.check(b.customDomainTgNoRule.id)
                selectToggleBtnUi(b.customDomainTgNoRule, t)
                unselectToggleBtnUi(b.customDomainTgBlock)
                unselectToggleBtnUi(b.customDomainTgWhitelist)
            }

            DomainRulesManager.Status.BLOCK.id -> {
                b.customDomainToggleGroup.check(b.customDomainTgBlock.id)
                selectToggleBtnUi(b.customDomainTgBlock, t)
                unselectToggleBtnUi(b.customDomainTgNoRule)
                unselectToggleBtnUi(b.customDomainTgWhitelist)
            }

            DomainRulesManager.Status.TRUST.id -> {
                b.customDomainToggleGroup.check(b.customDomainTgWhitelist.id)
                selectToggleBtnUi(b.customDomainTgWhitelist, t)
                unselectToggleBtnUi(b.customDomainTgBlock)
                unselectToggleBtnUi(b.customDomainTgNoRule)
            }
        }
    }

    // each button in the toggle group is associated with tag value.
    // tag values are ids of DomainRulesManager.DomainStatus
    private fun getTag(tag: Any): Int {
        return tag.toString().toIntOrNull() ?: 0
    }

    private fun showWgListBtmSheet(data: List<WgConfigFilesImmutable>) {
        Logger.v(LOG_TAG_UI, "$TAG show wg list(${data.size} for ${cd.domain}")
        val bottomSheetFragment = WireguardListBtmSheet.newInstance(WireguardListBtmSheet.InputType.DOMAIN, cd, data, this)
        bottomSheetFragment.show(
            requireActivity().supportFragmentManager,
            bottomSheetFragment.tag
        )
    }

    private fun showProxyCountriesBtmSheet(data: List<RegionalWgConf>) {
        Logger.v(LOG_TAG_UI, "$TAG show countries(${data.size} for ${cd.domain}")
        val bottomSheetFragment = ProxyCountriesBtmSheet.newInstance(ProxyCountriesBtmSheet.InputType.DOMAIN, cd,  data, this)
        bottomSheetFragment.show(
            requireActivity().supportFragmentManager,
            bottomSheetFragment.tag
        )
    }

    private fun io(f: suspend () -> Unit) {
        (requireContext() as LifecycleOwner).lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }

    override fun onDismissCC(obj: Any?) {
        try {
            val customDomain = obj as CustomDomain
            cd = customDomain
            updateStatusUi(
                DomainRulesManager.Status.getStatus(cd.status),
                cd.modifiedTs
            )
            Logger.i(LOG_TAG_UI, "$TAG onDismissCC: ${cd.domain}, ${cd.proxyCC}")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "$TAG err in onDismissCC ${e.message}", e)
        }
    }

    override fun onDismissWg(obj: Any?) {
        try {
            val customDomain = obj as CustomDomain
            cd = customDomain
            updateStatusUi(
                DomainRulesManager.Status.getStatus(cd.status),
                cd.modifiedTs
            )
            Logger.i(LOG_TAG_UI, "$TAG onDismissWg: ${cd.domain}, ${cd.proxyId}")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "$TAG err in onDismissWg ${e.message}", e)
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        Logger.v(LOG_TAG_UI, "$TAG onDismiss; domain: ${cd.domain}")
    }

}