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
import com.celzero.bravedns.database.CustomDomain
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.database.WgConfigFilesImmutable
import com.celzero.bravedns.databinding.BottomSheetCustomDomainsBinding
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Themes.Companion.getBottomSheetCurrentTheme
import com.celzero.bravedns.util.UIUtils.fetchColor
import com.celzero.bravedns.util.UIUtils.fetchToggleBtnColors
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.useTransparentNoDimBackground
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class CustomDomainRulesBtmSheet :
    BottomSheetDialogFragment(), ProxyCountriesBtmSheet.CountriesDismissListener, WireguardListBtmSheet.WireguardDismissListener {
    private var _binding: BottomSheetCustomDomainsBinding? = null

    private val b
        get() = checkNotNull(_binding)
        { "Binding accessed outside of view lifecycle" }

    private val persistentState by inject<PersistentState>()
    private val eventLogger by inject<EventLogger>()

    private lateinit var cd: CustomDomain

    override fun getTheme(): Int =
        getBottomSheetCurrentTheme(isDarkThemeOn(), persistentState.theme)

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
    }

    companion object {
        private const val TAG = "CDRBtmSht"
        private const val ARG_CUSTOM_DOMAIN = "custom_domain"

        fun newInstance(customDomain: CustomDomain): CustomDomainRulesBtmSheet {
            val fragment = CustomDomainRulesBtmSheet()
            val args = Bundle()
            args.putSerializable(ARG_CUSTOM_DOMAIN, customDomain)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetCustomDomainsBinding.inflate(inflater, container, false)
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

        // Retrieve CustomDomain from arguments
        @Suppress("DEPRECATION")
        cd = arguments?.getSerializable(ARG_CUSTOM_DOMAIN) as? CustomDomain ?: run {
            Logger.e(LOG_TAG_UI, "$TAG CustomDomain not found in arguments, dismissing")
            dismiss()
            return
        }

        dialog?.window?.let { window ->
            Themes.applyBottomSheetSystemBarAppearance(window, isDarkThemeOn(), persistentState.theme)
        }

        Logger.v(LOG_TAG_UI, "$TAG, view created for ${cd.domain}")
        init()
        initClickListeners()
    }

    private fun init() {
        val uid = cd.uid
        io {
            if (uid == UID_EVERYBODY) {
                b.customDomainAppNameTv.text =
                    getString(R.string.firewall_act_universal_tab).replaceFirstChar(Char::titlecase)
            } else {
                val appNames = FirewallManager.getAppNamesByUid(cd.uid)
                val appName = getAppName(cd.uid, appNames)
                val appInfo = FirewallManager.getAppInfoByUid(cd.uid)
                uiCtx {
                    b.customDomainAppNameTv.text = appName
                    displayIcon(
                        Utilities.getIcon(
                            requireContext(),
                            appInfo?.packageName.orEmpty(),
                            appInfo?.appName.orEmpty()
                        ),
                        b.customDomainAppIconIv
                    )
                }
            }
        }
        Logger.v(LOG_TAG_UI, "$TAG, init for ${cd.domain}, uid: $uid")
        val rules = DomainRulesManager.getDomainRule(cd.domain, uid)
        b.customDomainTv.text = cd.domain
        updateStatusUi(
            DomainRulesManager.Status.getStatus(cd.status),
            cd.modifiedTs
        )
        updateToggleGroup(rules.id)
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
        b.customDomainTgNoRule.setOnClickListener { handleDomainRuleClick(DomainRulesManager.Status.NONE) }
        b.customDomainTgBlock.setOnClickListener { handleDomainRuleClick(DomainRulesManager.Status.BLOCK) }
        b.customDomainTgWhitelist.setOnClickListener { handleDomainRuleClick(DomainRulesManager.Status.TRUST) }

        b.customDomainDeleteChip.setOnClickListener {
            showDialogForDelete()
        }
    }

    private fun handleDomainRuleClick(status: DomainRulesManager.Status) {
        if (cd.status == status.id) return // no change
        changeDomainStatus(status, cd)
    }

    private fun changeDomainStatus(id: DomainRulesManager.Status, cd: CustomDomain) {
        io {
            when (id) {
                DomainRulesManager.Status.NONE -> noRule(cd)
                DomainRulesManager.Status.BLOCK -> block(cd)
                DomainRulesManager.Status.TRUST -> whitelist(cd)
            }
            uiCtx {
                updateToggleGroup(id.id)
                updateStatusUi(id, this.cd.modifiedTs)
            }
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

    data class ToggleBtnUi(val txtColor: Int, val bgColor: Int)

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

    private fun updateToggleGroup(id: Int) {
        val fid = findSelectedRuleByTag(id) ?: return
        val t = toggleBtnUi(fid)

        // Unselect all rows first
        unselectRowUi(b.customDomainBadgeNoRule, b.customDomainRbNoRule)
        unselectRowUi(b.customDomainBadgeBlock, b.customDomainRbBlock)
        unselectRowUi(b.customDomainBadgeWhitelist, b.customDomainRbWhitelist)

        // Select the active row
        when (id) {
            DomainRulesManager.Status.NONE.id -> selectRowUi(b.customDomainBadgeNoRule, b.customDomainRbNoRule, t)
            DomainRulesManager.Status.BLOCK.id -> selectRowUi(b.customDomainBadgeBlock, b.customDomainRbBlock, t)
            DomainRulesManager.Status.TRUST.id -> selectRowUi(b.customDomainBadgeWhitelist, b.customDomainRbWhitelist, t)
        }
    }

    private fun findSelectedRuleByTag(ruleId: Int): DomainRulesManager.Status? {
        return when (ruleId) {
            DomainRulesManager.Status.NONE.id -> DomainRulesManager.Status.NONE
            DomainRulesManager.Status.TRUST.id -> DomainRulesManager.Status.TRUST
            DomainRulesManager.Status.BLOCK.id -> DomainRulesManager.Status.BLOCK
            else -> null
        }
    }

    private fun showDialogForDelete() {
        val builder = MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
        builder.setTitle(R.string.cd_remove_dialog_title)
        builder.setMessage(R.string.cd_remove_dialog_message)
        builder.setCancelable(true)
        builder.setPositiveButton(requireContext().getString(R.string.lbl_delete)) { _, _ ->
            io { DomainRulesManager.deleteDomain(cd) }
            Utilities.showToastUiCentered(
                requireContext(),
                requireContext().getString(R.string.cd_toast_deleted),
                Toast.LENGTH_SHORT
            )
            logEvent("Deleted custom domain rule for ${cd.domain}")
            dismiss()
        }

        builder.setNegativeButton(requireContext().getString(R.string.lbl_cancel)) { _, _ ->
            // no-op
        }
        builder.create().show()
    }

    private fun showWgListBtmSheet(data: List<WgConfigFilesImmutable?>) {
        Logger.v(LOG_TAG_UI, "$TAG show wg list(${data.size} for ${cd.domain}")
        val bottomSheetFragment = WireguardListBtmSheet.newInstance(WireguardListBtmSheet.InputType.DOMAIN, cd, data, this)
        bottomSheetFragment.show(
            requireActivity().supportFragmentManager,
            bottomSheetFragment.tag
        )
    }

    private fun showProxyCountriesBtmSheet(data: List<String>) {
        Logger.v(LOG_TAG_UI, "$TAG show countries(${data.size} for ${cd.domain}")
        val bottomSheetFragment = ProxyCountriesBtmSheet.newInstance(ProxyCountriesBtmSheet.InputType.DOMAIN, cd, data, this)
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

    private fun logEvent(details: String) {
        eventLogger.log(EventType.FW_RULE_MODIFIED, Severity.LOW, "Custom Domain", EventSource.UI, false, details)
    }

}
