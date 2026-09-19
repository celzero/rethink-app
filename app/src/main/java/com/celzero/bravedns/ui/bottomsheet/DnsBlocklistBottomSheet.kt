/*
Copyright 2020 RethinkDNS and its authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.celzero.bravedns.ui.bottomsheet

import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_DNS
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Spanned
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.target.CustomViewTarget
import com.bumptech.glide.request.transition.DrawableCrossFadeFactory
import com.bumptech.glide.request.transition.Transition
import com.celzero.bravedns.R
import com.celzero.bravedns.database.DnsLog
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.databinding.BottomSheetDnsLogBinding
import com.celzero.bravedns.databinding.DialogInfoRulesLayoutBinding
import com.celzero.bravedns.glide.FavIconDownloader
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.FirewallRuleset
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.activity.DomainConnectionsActivity
import com.celzero.bravedns.ui.custom.RuleStateSwitch
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.fetchColor
import com.celzero.bravedns.util.UIUtils.htmlToSpannedText
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.getIcon
import com.celzero.bravedns.viewmodel.DomainConnectionsViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.common.collect.HashMultimap
import com.google.common.collect.Multimap
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

class DnsBlocklistBottomSheet : BaseBottomSheetDialogFragment() {
    private var _binding: BottomSheetDnsLogBinding? = null

    private val b
        get() = checkNotNull(_binding)
        { "Binding accessed outside of view lifecycle" }

    private var log: DnsLog? = null

    // display name of the app shown in the app-scope rule card caption; resolved
    // asynchronously alongside the rule
    private var appRuleScopeName: String? = null

    private val persistentState by inject<PersistentState>()
    private val eventLogger by inject<EventLogger>()

    override fun getTheme(): Int =
        Themes.getBottomSheetCurrentTheme(isDarkThemeOn(), persistentState.theme)

    companion object {
        const val INSTANCE_STATE_DNSLOGS = "DNSLOGS"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetDnsLogBinding.inflate(inflater, container, false)
        return b.root
    }

    // enum to represent the status of the domain in the chip (ui)
    enum class BlockType(val id: Int) {
        ALLOWED(0),
        BLOCKED(1),
        MAYBE_BLOCKED(2),
        NONE(3)
    }

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dialog?.window?.let { window ->
            Themes.applyBottomSheetSystemBarAppearance(window, isDarkThemeOn(), persistentState.theme)
        }

        val data = arguments?.getString(INSTANCE_STATE_DNSLOGS)
        log = Gson().fromJson(data, DnsLog::class.java)

        if (log == null) {
            Logger.w(LOG_TAG_DNS, "Transaction detail missing, dismiss the dialog")
            this.dismiss()
            return
        }

        // Setup basic UI immediately (lightweight operations)
        b.dnsBlockUrl.text = log?.queryStr.orEmpty()
        b.dnsBlockIpAddress.text = getResponseIp()
        b.dnsBlockConnectionFlag.text = log?.flag.orEmpty()
        b.dnsBlockIpLatency.text = getString(R.string.dns_btm_latency_ms, log?.ttl?.toString().orEmpty())
        if (log?.blockedTarget?.isEmpty() == true) {
            b.dnsBlockedTargetHeader.visibility = View.GONE
        } else {
            b.dnsBlockedTarget.text = log?.blockedTarget
            b.dnsBlockedTargetHeader.visibility = View.VISIBLE
        }
        if (Logger.LoggerLevel.fromId(persistentState.goLoggerLevel.toInt())
                ?.isLessThanOrEqualTo(Logger.LoggerLevel.DEBUG) == true
        ) {
            b.dnsMessage.text = "${log?.msg}; ${log?.proxyId}; ${log?.relayIP}"
        } else {
            b.dnsMessage.text = log?.msg.orEmpty()
        }

        val region = log?.region.orEmpty()
        if (region.isNotEmpty()) {
            b.dnsRegionHeader.visibility = View.VISIBLE
            b.dnsRegion.text = region
        } else {
            b.dnsRegionHeader.visibility = View.GONE
        }

        // Setup click listeners immediately (no heavy work)
        setupClickListeners()

        // Update app details (already uses background thread)
        updateAppDetails(log)

        // Set up app-specific domain rule option (visible only if an app is associated)
        setupAppSpecificDomainRule()

        // Defer heavy operations to prevent ANR
        // This allows the UI to render immediately while heavy operations run after first frame
        view.post {
            // The sheet may be dismissed before this runnable is dispatched;
            // b throws once the view lifecycle has ended.
            if (!isAdded || _binding == null) return@post
            displayRecordTypeChip()
            displayDnsTransactionDetails()
            updateRulesUi(log?.queryStr.orEmpty())
        }

        // Defer favicon loading even more (lowest priority, can be slow)
        lifecycleScope.launch {
            kotlinx.coroutines.delay(150.milliseconds) // Let basic UI settle first
            if (!isAdded || _binding == null) return@launch
            displayFavIcon()
        }
    }

    private fun updateAppDetails(log: DnsLog?) {
        if (log == null) {
            b.dnsAppNameHeader.visibility = View.GONE
            return
        }

        if (log.appName.isNotEmpty() && log.packageName.isNotEmpty()) {
            b.dnsAppNameHeader.visibility = View.VISIBLE
            b.dnsAppName.text = requireContext().getString(R.string.two_argument_parenthesis, log.appName, log.uid.toString())
            b.dnsAppIcon.setImageDrawable(getIcon(requireContext(), log.packageName, log.appName))
            return
        }

        io {
            val appNames = FirewallManager.getAppNamesByUid(log.uid)
            if (appNames.isEmpty()) {
                uiCtx {
                    b.dnsAppNameHeader.visibility = View.GONE
                }
                return@io
            }
            val pkgName = FirewallManager.getPackageNameByAppName(appNames[0])

            val appCount = appNames.count()
            uiCtx {
                if (appCount >= 1) {
                    b.dnsAppName.text =
                        if (appCount >= 2) {
                            requireContext().getString(R.string.two_argument_parenthesis, getString(
                                R.string.ctbs_app_other_apps,
                                appNames[0],
                                appCount.minus(1).toString()
                            ), log.uid.toString())
                        } else {
                            requireContext().getString(R.string.two_argument_parenthesis, appNames[0], log.uid.toString())
                        }
                    if (pkgName == null) return@uiCtx
                    b.dnsAppIcon.setImageDrawable(
                        getIcon(requireContext(), pkgName, log.appName)
                    )
                } else {
                    // apps which are not available in cache are treated as non app.
                    // TODO: check packageManager#getApplicationInfo() for appInfo
                    b.dnsAppNameHeader.visibility = View.GONE
                }
            }
        }
    }

    private fun getResponseIp(): String {
        val ips = log?.response?.split(",") ?: return ""
        return ips.firstOrNull() ?: ""
    }

    private fun updateRulesUi(domain: String) {
        val d = domain.dropLastWhile { it == '.' }.lowercase()
        val status = DomainRulesManager.getDomainRule(d, Constants.UID_EVERYBODY)
        // the card's tiny header names the domain both rules apply to
        b.bsdlRulesDomainLabel.text = d
        renderGlobalRuleState(status)
    }

    private fun displayRecordTypeChip() {
        if (log == null) {
            Logger.w(LOG_TAG_DNS, "Transaction detail missing, no need to update chips")
            return
        }

        if (log?.typeName.orEmpty().isEmpty()) {
            b.dnsRecordTypeChip.visibility = View.GONE
            return
        }

        b.dnsRecordTypeChip.visibility = View.VISIBLE
        b.dnsRecordTypeChip.text = log?.typeName.orEmpty()
    }

    private fun setupClickListeners() {

        b.dnsBlockHeaderContainer.setOnClickListener {
            log?.queryStr?.let { query -> startDomainConnectionsActivity(query) }
        }

        b.dnsBlockUrl.setOnClickListener {
            log?.queryStr?.let { query -> startDomainConnectionsActivity(query) }
        }

        // one rules card, one row per scope; tapping a row cycles its domain
        // rule no rule → block → trust → no rule, no dialog in between
        b.bsdlRuleRowApp.setOnClickListener { cycleAppRule() }
        b.bsdlRuleRowGlobal.setOnClickListener { cycleGlobalRule() }
    }

    /**
     * Tap-to-cycle for the app-scoped domain rule: no rule → block → trust → no
     * rule. The rule lookup is an in-memory trie read and safe on main; the DB
     * write itself is dispatched to io inside applyAppRuleStatus.
     */
    private fun cycleAppRule() {
        val currentLog = log ?: return
        val uid = currentLog.uid
        val current = DomainRulesManager.getDomainRule(currentLog.queryStr, uid)
        val next = nextDomainRuleStatus(current)
        Logger.i(
            LOG_TAG_DNS,
            "cycle app domain-rule for $uid, ${currentLog.queryStr}: ${current.name} -> ${next.name}"
        )
        // gate the row while the write below is pending so an overlapping tap
        // cannot read a stale rule; re-enabled on every exit path
        b.bsdlRuleRowApp.isEnabled = false
        applyAppRuleStatus(next, uid)
    }

    /** Tap-to-cycle for the all-apps domain rule: no rule → block → trust → no rule. */
    private fun cycleGlobalRule() {
        val currentLog = log ?: return
        val current =
            DomainRulesManager.getDomainRule(currentLog.queryStr, Constants.UID_EVERYBODY)
        val next = nextDomainRuleStatus(current)
        Logger.i(
            LOG_TAG_DNS,
            "cycle global domain-rule for ${currentLog.queryStr}: ${current.name} -> ${next.name}"
        )
        // gate the row while the write below is pending so an overlapping tap
        // cannot read a stale rule; re-enabled on every exit path
        b.bsdlRuleRowGlobal.isEnabled = false
        applyDnsRuleStatus(next)
    }

    private fun nextDomainRuleStatus(current: DomainRulesManager.Status): DomainRulesManager.Status {
        return when (current) {
            DomainRulesManager.Status.NONE -> DomainRulesManager.Status.BLOCK
            DomainRulesManager.Status.BLOCK -> DomainRulesManager.Status.TRUST
            DomainRulesManager.Status.TRUST -> DomainRulesManager.Status.NONE
        }
    }

    /** Re-renders the trailing state of the app-scoped rule row. */
    private fun applyAppRuleStatus(status: DomainRulesManager.Status, uid: Int) {
        // no need to apply rule, if prev selection and current selection are same
        val currentLog = log ?: run {
            b.bsdlRuleRowApp.isEnabled = true
            return
        }
        if (DomainRulesManager.getDomainRule(currentLog.queryStr, uid) == status) {
            renderAppRuleState(status)
            b.bsdlRuleRowApp.isEnabled = true
            return
        }
        val previous = DomainRulesManager.getDomainRule(currentLog.queryStr, uid)
        io {
            try {
                DomainRulesManager.changeStatus(
                    currentLog.queryStr,
                    uid,
                    currentLog.responseIps,
                    DomainRulesManager.DomainType.DOMAIN,
                    status
                )
                logEvent("DNS app domain rule change", "${currentLog.queryStr} to ${status.name}")
                uiCtx { renderAppRuleState(status) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(
                    LOG_TAG_DNS,
                    "app domain rule change failed for ${currentLog.queryStr}: ${e.message}"
                )
                // the write did not persist; fall back to the previously persisted state
                uiCtx { renderAppRuleState(previous) }
            } finally {
                uiCtx { b.bsdlRuleRowApp.isEnabled = true }
            }
        }
    }

    /** Re-renders the trailing state of the all-apps rule row. */
    private fun applyDnsRuleStatus(status: DomainRulesManager.Status) {
        // no need to apply rule, if prev selection and current selection are same
        val currentLog = log ?: run {
            b.bsdlRuleRowGlobal.isEnabled = true
            return
        }
        if (
            DomainRulesManager.getDomainRule(currentLog.queryStr, Constants.UID_EVERYBODY) ==
            status
        ) {
            renderGlobalRuleState(status)
            b.bsdlRuleRowGlobal.isEnabled = true
            return
        }
        val previous =
            DomainRulesManager.getDomainRule(currentLog.queryStr, Constants.UID_EVERYBODY)
        io {
            try {
                DomainRulesManager.changeStatus(
                    currentLog.queryStr,
                    Constants.UID_EVERYBODY,
                    currentLog.responseIps,
                    DomainRulesManager.DomainType.DOMAIN,
                    status
                )
                logEvent("DNS domain rule change", "${currentLog.queryStr} to ${status.name}")
                uiCtx { renderGlobalRuleState(status) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(
                    LOG_TAG_DNS,
                    "global domain rule change failed for ${currentLog.queryStr}: ${e.message}"
                )
                // the write did not persist; fall back to the previously persisted state
                uiCtx { renderGlobalRuleState(previous) }
            } finally {
                uiCtx { b.bsdlRuleRowGlobal.isEnabled = true }
            }
        }
    }

    // trailing state text of the rule rows; the color comes from statusColor()
    private fun statusText(status: DomainRulesManager.Status): String {
        return when (status) {
            DomainRulesManager.Status.NONE -> getString(R.string.bsdl_rule_status_none)
            DomainRulesManager.Status.BLOCK -> getString(R.string.bsdl_rule_status_blocked)
            DomainRulesManager.Status.TRUST -> getString(R.string.bsdl_rule_status_trusted)
        }
    }

    // color discipline: red is reserved for "blocked"; every other state stays
    // neutral so color carries meaning
    private fun statusColor(status: DomainRulesManager.Status): Int {
        return when (status) {
            DomainRulesManager.Status.BLOCK ->
                fetchColor(requireContext(), R.attr.chipTextNegative)
            else -> fetchColor(requireContext(), R.attr.primaryLightColorText)
        }
    }

    // pill glyph beside the trailing state text; blocked points left with a
    // cross, trusted points right with a tick, no rule stays dim
    private fun domainRuleSwitchState(status: DomainRulesManager.Status): RuleStateSwitch.SwitchState {
        return when (status) {
            DomainRulesManager.Status.BLOCK -> RuleStateSwitch.SwitchState.OFF
            DomainRulesManager.Status.TRUST -> RuleStateSwitch.SwitchState.ON
            DomainRulesManager.Status.NONE -> RuleStateSwitch.SwitchState.NEUTRAL
        }
    }

    private fun renderGlobalRuleState(status: DomainRulesManager.Status) {
        b.bsdlGlobalRuleState.text = statusText(status)
        b.bsdlGlobalRuleState.setTextColor(statusColor(status))
        b.bsdlGlobalRuleSwitch.setRuleState(domainRuleSwitchState(status))
    }

    private fun renderAppRuleState(status: DomainRulesManager.Status) {
        b.bsdlAppRuleState.text = statusText(status)
        b.bsdlAppRuleState.setTextColor(statusColor(status))
        b.bsdlAppRuleSwitch.setRuleState(domainRuleSwitchState(status))
    }

    /**
     * Sets up the app-specific domain rule row. The row is shown only when the
     * DNS request originates from a real app (i.e. the uid resolves to an app in
     * the FirewallManager cache). Rules added here are persisted against the app's uid and
     * therefore appear under the app's CustomDomainFragment (app-specific rules) tab.
     */
    private fun setupAppSpecificDomainRule() {
        val currentLog = log ?: return
        val uid = currentLog.uid
        // skip if the uid does not belong to a real app
        if (uid == Constants.INVALID_UID || uid == Constants.UID_EVERYBODY) return

        io {
            val hasApp = FirewallManager.hasUid(uid)
            if (!hasApp) return@io

            val domain = currentLog.queryStr.dropLastWhile { it == '.' }.lowercase()
            val status = DomainRulesManager.getDomainRule(domain, uid)

            val appNames = FirewallManager.getAppNamesByUid(uid)
            // when several apps share the uid, mirror the header's "App +n" naming
            val displayName =
                if (appNames.count() >= 2) {
                    getString(R.string.ctbs_app_other_apps, appNames[0], appNames.count().minus(1).toString())
                } else {
                    appNames.firstOrNull() ?: currentLog.appName
                }

            uiCtx {
                if (_binding == null) return@uiCtx

                appRuleScopeName = displayName
                // the row's title names the app the rule is scoped to; the
                // domain lives in the card's tiny header above the rows
                b.bsdlRuleAppTitle.text =
                    getString(R.string.bsdl_rule_scope_app_title_fmt, displayName)
                renderAppRuleState(status)

                b.bsdlRuleRowApp.visibility = View.VISIBLE
            }
        }
    }

    private fun displayDnsTransactionDetails() {
        val currentLog = log
        if (currentLog == null) {
            Logger.w(LOG_TAG_DNS, "Transaction detail missing, no need to update ui")
            return
        }

        displayDescription()

        // show the exact rule that blocked/allowed this query, when recorded during the
        // upstream-answer evaluation; fall back to the generic chips otherwise
        if (handleFilterReasonChip()) return

        handleBlocklistChip()
    }

    /**
     * Renders the blocklist chip with the firewall rule recorded for this query (see
     * DnsLog#blockedReason), similar to the conn-track sheet's rule chip. Returns true
     * when the reason chip was shown, false when no specific rule is available and the
     * caller should fall back to the generic blocklist / response-ips chips.
     */
    private fun handleFilterReasonChip(): Boolean {
        val currentLog = log ?: return false

        val rule = FirewallRuleset.getFirewallRule(currentLog.blockedReason) ?: return false
        val blocked = FirewallRuleset.ground(rule)

        // a block rule recorded on a resolved query does not explain the verdict;
        // fall back to the generic blocklist info instead
        if (blocked && !currentLog.isBlocked) return false

        b.dnsBlockBlocklistInfo.visibility = View.VISIBLE
        renderBlocklistRow(
            if (blocked) BlockType.BLOCKED else BlockType.ALLOWED,
            FirewallRuleset.getRulesIcon(rule.id)
        )
        b.dnsBlockBlocklistInfoTxt.text = getString(rule.title)
        b.dnsBlockBlocklistInfo.setOnClickListener { showFilterReasonDialog(rule) }
        // the row opens a dialog, so keep the trailing arrow
        b.dnsBlockBlocklistInfoArrow.visibility = View.VISIBLE
        return true
    }

    // explanation dialog for the filter-reason chip, mirrors the conn-track sheet's
    // firewall-rules dialog (title, description, icon)
    private fun showFilterReasonDialog(rule: FirewallRuleset) {
        val dialogBinding = DialogInfoRulesLayoutBinding.inflate(layoutInflater)
        val builder = MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim).setView(dialogBinding.root)
        val lp = WindowManager.LayoutParams()
        val dialog = builder.create()
        dialog.show()
        lp.copyFrom(dialog.window?.attributes)
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT

        dialog.setCancelable(true)
        dialog.window?.attributes = lp
        // keep the dialog within the app's max width on expanded windows (foldables/tablets)
        UIUtils.capDialogWidth(dialog)

        dialogBinding.infoRulesDialogRulesTitle.text = getString(rule.title)
        dialogBinding.infoRulesDialogRulesDesc.text = htmlToSpannedText(getString(rule.desc))
        dialogBinding.infoRulesDialogRulesIcon.visibility = View.VISIBLE
        dialogBinding.infoRulesDialogRulesIcon.setImageDrawable(
            ContextCompat.getDrawable(requireContext(), FirewallRuleset.getRulesIcon(rule.id))
        )

        dialogBinding.infoRulesDialogCancelImg.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun handleBlocklistChip() {
        val currentLog = log
        if (currentLog == null) {
            Logger.w(LOG_TAG_DNS, "Transaction detail missing, no need to update chips")
            return
        }

        // matched blocklists: tap for the per-list breakdown
        if (currentLog.hasBlocklists()) {
            b.dnsBlockBlocklistInfo.visibility = View.VISIBLE
            val type =
                if (currentLog.isBlocked) BlockType.BLOCKED else BlockType.MAYBE_BLOCKED
            renderBlocklistRow(type, FirewallRuleset.getRulesIcon(currentLog.blockedReason))
            showBlocklistChip()
            return
        }

        // no-answer (unresolved / nx-domain) queries
        if (currentLog.unansweredQuery()) {
            b.dnsBlockBlocklistInfo.visibility = View.VISIBLE
            renderBlocklistRow(BlockType.NONE, FirewallRuleset.getRulesIcon(currentLog.blockedReason))
            b.dnsBlockBlocklistInfoTxt.text = getString(R.string.dns_btm_sheet_chip_no_answer)
            b.dnsBlockBlocklistInfoArrow.visibility = View.GONE
            return
        }

        // blocked with no blocklist attribution (e.g. default-block rules)
        if (currentLog.isBlocked) {
            b.dnsBlockBlocklistInfo.visibility = View.VISIBLE
            renderBlocklistRow(BlockType.BLOCKED, FirewallRuleset.getRulesIcon(currentLog.blockedReason))
            b.dnsBlockBlocklistInfoTxt.text = getString(R.string.lbl_blocked)
            b.dnsBlockBlocklistInfoArrow.visibility = View.GONE
            return
        }

        // upstream flagged but the query resolved (verdict line already says so);
        // nothing conclusive to show here
        b.dnsBlockBlocklistInfo.visibility = View.GONE
    }

    private fun showBlocklistChip() {
        val group: Multimap<String, String> = HashMultimap.create()

        log?.getBlocklists()?.forEach {
            val items = it.split(":")
            if (items.count() <= 1) return@forEach

            group.put(items[0], items[1])
        }

        val groupCount = group.keys().distinct().count()
        if (groupCount > 1) {
            b.dnsBlockBlocklistInfoTxt.text = "${group.keys().firstOrNull()} +${groupCount - 1}"
        } else {
            b.dnsBlockBlocklistInfoTxt.text = group.keys().firstOrNull()
        }

        b.dnsBlockBlocklistInfo.setOnClickListener { showBlocklistDialog(group) }
        // the row opens a dialog, so keep the trailing arrow
        b.dnsBlockBlocklistInfoArrow.visibility = View.VISIBLE
    }

    // verdict coloring for the blocklist/rule info row, mirrors the conn-track
    // sheet: the icon and the text share the verdict color
    private fun renderBlocklistRow(type: BlockType, iconRes: Int) {
        val colorAttr =
            when (type) {
                BlockType.BLOCKED, BlockType.NONE -> R.attr.chipTextNegative
                BlockType.ALLOWED -> R.attr.chipTextPositive
                BlockType.MAYBE_BLOCKED -> R.attr.chipTextNeutral
            }
        val color = fetchColor(requireContext(), colorAttr)
        b.dnsBlockBlocklistInfoTxt.setTextColor(color)
        b.dnsBlockBlocklistInfoIcon.setImageDrawable(
            ContextCompat.getDrawable(requireContext(), iconRes)
        )
        b.dnsBlockBlocklistInfoIcon.colorFilter =
            PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
    }

    private fun startDomainConnectionsActivity(domain: String) {
        val intent = Intent(requireContext(), DomainConnectionsActivity::class.java)
        intent.putExtra(DomainConnectionsActivity.INTENT_EXTRA_TYPE, DomainConnectionsActivity.InputType.DOMAIN.type)
        intent.putExtra(DomainConnectionsActivity.INTENT_EXTRA_DOMAIN, domain)
        intent.putExtra(DomainConnectionsActivity.INTENT_EXTRA_TIME_CATEGORY, DomainConnectionsViewModel.TimeCategory.SEVEN_DAYS.value)
        intent.putExtra(DomainConnectionsActivity.INTENT_EXTRA_IS_BLOCKED, log?.isBlocked ?: false)
        requireContext().startActivity(intent)
        this.dismiss()
    }

    private fun showBlocklistDialog(groupNames: Multimap<String, String>) {
        val dialogBinding = DialogInfoRulesLayoutBinding.inflate(layoutInflater)
        val builder = MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim).setView(dialogBinding.root)
        val dialog = builder.create()
        dialog.setCancelable(true)
        dialogBinding.infoRulesDialogRulesDesc.text = formatText(groupNames)
        dialogBinding.infoRulesDialogRulesTitle.visibility = View.GONE

        dialogBinding.infoRulesDialogCancelImg.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun formatText(groupNames: Multimap<String, String>): Spanned {
        var text = ""
        groupNames.keys().distinct().forEach {
            val heading =
                it.replaceFirstChar { a ->
                    if (a.isLowerCase()) a.titlecase(Locale.getDefault()) else a.toString()
                }
            text +=
                getString(
                    R.string.dns_btm_sheet_dialog_message,
                    heading,
                    groupNames.get(it).count().toString(),
                    TextUtils.join(", ", groupNames.get(it))
                )
        }
        text = text.replace(",", ", ")
        return htmlToSpannedText(text)
    }

    private fun displayDescription() {
        val currentLog = log
        if (currentLog == null) {
            Logger.w(LOG_TAG_DNS, "Transaction detail missing, no need to update ui")
            return
        }

        val uptime =
            DateUtils.getRelativeTimeSpanString(
                    currentLog.time,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE
                )
                .toString()
        if (currentLog.isBlocked) {
            showBlockedState(uptime)
        } else {
            showResolvedState(uptime)
        }
    }

    private fun showResolvedState(uptime: String) {
        val currentLog = log
        if (currentLog == null) {
            Logger.w(LOG_TAG_DNS, "Transaction detail missing, no need to update ui")
            return
        }

        if (currentLog.isCached) {
            val resolver = if (currentLog.serverIP.isEmpty()) {
                getString(R.string.lbl_cache)
            } else {
                "${getString(R.string.lbl_cache)} (${currentLog.resolverId}:${currentLog.serverIP})"
            }
            b.dnsBlockBlockedDesc.text = getString(R.string.dns_btm_resolved_doh_no_server, uptime)
            showResolverDetails(resolver)
            return
        }

        if (currentLog.isAnonymized()) { // anonymized queries answered by dns-crypt / proxies
            val p = currentLog.relayIP.ifEmpty {
                currentLog.proxyId
            }
            b.dnsBlockBlockedDesc.text = getString(R.string.dns_btm_resolved_anonymously, uptime)
            showResolverDetails(getString(R.string.dns_btm_info_via, currentLog.serverIP, p))
        } else if (currentLog.isLocallyAnswered()) { // usually happens when there is a network failure
            b.dnsBlockBlockedDesc.text = getString(R.string.dns_btm_resolved_doh_no_server, uptime)
            showResolverDetails(null)
        } else {
            b.dnsBlockBlockedDesc.text = getString(R.string.dns_btm_resolved_doh_no_server, uptime)
            showResolverDetails(currentLog.serverIP)
        }
    }

    private fun showBlockedState(uptime: String) {
        val currentLog = log
        if (currentLog == null) {
            Logger.w(LOG_TAG_DNS, "Transaction detail missing, no need to update ui")
            return
        }

        b.dnsBlockBlockedDesc.text = getString(R.string.dns_btm_blocked, uptime)
        if (currentLog.isLocallyAnswered()) { // usually true when query blocked by on-device blocklists
            showResolverDetails(getString(R.string.dns_btm_info_on_device))
        } else {
            showResolverDetails(currentLog.serverIP)
        }
    }

    // the resolver section below response; hidden when there is nothing to show
    private fun showResolverDetails(detail: String?) {
        if (detail.isNullOrEmpty()) {
            b.dnsBlockInfoHeader.visibility = View.GONE
            return
        }
        b.dnsBlockInfoDetails.text = detail
        b.dnsBlockInfoHeader.visibility = View.VISIBLE
    }

    // the header slot shows the favicon when one is available; otherwise the
    // resolver's flag emoji takes its place so the slot is never empty
    private fun showHeaderFlag() {
        val currentLog = log
        if (_binding == null || currentLog == null) return

        if (currentLog.flag.isEmpty()) {
            b.dnsBlockHeaderFlag.visibility = View.GONE
            return
        }
        b.dnsBlockHeaderFlag.text = currentLog.flag
        b.dnsBlockHeaderFlag.visibility = View.VISIBLE
    }

    private fun hideHeaderFlag() {
        if (_binding == null) return

        b.dnsBlockHeaderFlag.visibility = View.GONE
    }

    private fun displayFavIcon() {
        if (!isAdded) return

        val currentLog = log
        if (currentLog == null) {
            Logger.w(LOG_TAG_DNS, "Transaction detail missing, no need to update ui")
            return
        }

        if (!persistentState.fetchFavIcon || currentLog.groundedQuery()) {
            // no favicon will be loaded; the flag takes the header slot instead
            showHeaderFlag()
            return
        }

        val trim = currentLog.queryStr.dropLastWhile { it == '.' }

        // no need to check in glide cache if the value is available in failed cache
        if (FavIconDownloader.isUrlAvailableInFailedCache(trim) != null) {
            b.dnsBlockFavIcon.visibility = View.GONE
            showHeaderFlag()
        } else {
            // Glide will cache the icons against the urls. To extract the fav icon from the
            // cache, first verify that the cache is available with the next dns url.
            // If it is not available then glide will throw an error, do the duckduckgo
            // url check in that case.
            lookupForImageNextDns(trim)
        }
    }

    // FIXME: the glide app code to fetch the image from the cache is repeated in
    // both lookupForImageNextDns() and lookupForImageDuckduckgo().
    // come up with common method to handle this
    private fun lookupForImageNextDns(query: String) {
        val url = FavIconDownloader.constructFavIcoUrlNextDns(query)
        val duckduckgoUrl = FavIconDownloader.constructFavUrlDuckDuckGo(query)
        val duckduckgoDomainURL = FavIconDownloader.getDomainUrlFromFdqnDuckduckgo(query)
        try {
            Logger.d(LOG_TAG_DNS, "Glide, TransactionViewHolder lookupForImageNextDns :$url")
            val factory = DrawableCrossFadeFactory.Builder().setCrossFadeEnabled(true).build()
            var request = Glide.with(requireContext().applicationContext)
                .load(url)
                .onlyRetrieveFromCache(true)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .timeout(2000) // Prevent hanging - fail fast if cache lookup is slow
                .transition(DrawableTransitionOptions.withCrossFade(factory))

            val errorRequest = lookupForImageDuckduckgo(duckduckgoUrl, duckduckgoDomainURL)
            if (errorRequest != null) {
                request = request.error(errorRequest)
            }

            request.into(
                    object : CustomViewTarget<ImageView, Drawable>(b.dnsBlockFavIcon) {
                        override fun onLoadFailed(errorDrawable: Drawable?) {
                            // the application-scoped Glide request can deliver
                            // after onDestroyView() cleared the binding while
                            // the fragment is still added; `b` would throw
                            if (_binding == null) return

                            b.dnsBlockFavIcon.visibility = View.GONE
                            showHeaderFlag()
                        }

                        override fun onResourceReady(
                            resource: Drawable,
                            transition: Transition<in Drawable>?
                        ) {
                            Logger.d(
                                LOG_TAG_DNS,
                                "Glide - CustomViewTarget onResourceReady() nextdns: $url"
                            )
                            if (_binding == null) return

                            b.dnsBlockFavIcon.visibility = View.VISIBLE
                            b.dnsBlockFavIcon.setImageDrawable(resource)
                            hideHeaderFlag()
                        }

                        override fun onResourceCleared(placeholder: Drawable?) {
                            if (_binding == null) return

                            b.dnsBlockFavIcon.visibility = View.GONE
                            showHeaderFlag()
                        }
                    }
                )
        } catch (e: Exception) {
            Logger.d(LOG_TAG_DNS, "Glide - TransactionViewHolder Exception() -${e.message}")
            lookupForImageDuckduckgo(duckduckgoUrl, duckduckgoDomainURL)?.into(
                object : CustomViewTarget<ImageView, Drawable>(b.dnsBlockFavIcon) {
                    override fun onLoadFailed(errorDrawable: Drawable?) {
                        if (_binding == null) return

                        b.dnsBlockFavIcon.visibility = View.GONE
                        showHeaderFlag()
                    }

                    override fun onResourceReady(
                        resource: Drawable,
                        transition: Transition<in Drawable>?
                    ) {
                        Logger.d(
                            LOG_TAG_DNS,
                            "Glide - CustomViewTarget onResourceReady() duckduckgo: $url"
                        )
                        if (_binding == null) return

                        b.dnsBlockFavIcon.visibility = View.VISIBLE
                        b.dnsBlockFavIcon.setImageDrawable(resource)
                        hideHeaderFlag()
                    }

                    override fun onResourceCleared(placeholder: Drawable?) {
                        if (_binding == null) return

                        b.dnsBlockFavIcon.visibility = View.GONE
                        showHeaderFlag()
                    }
                }
            )
        }
    }

    private fun lookupForImageDuckduckgo(url: String, domainUrl: String): RequestBuilder<Drawable>? {
        return try {
            Logger.d(
                LOG_TAG_DNS,
                "Glide - TransactionViewHolder lookupForImageDuckduckgo: $url, $domainUrl"
            )
            val factory = DrawableCrossFadeFactory.Builder().setCrossFadeEnabled(true).build()
            Glide.with(requireContext().applicationContext)
                .load(url)
                .onlyRetrieveFromCache(true)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .timeout(2000) // Prevent hanging - fail fast if cache lookup is slow
                .error(
                    Glide.with(requireContext().applicationContext)
                        .load(domainUrl)
                        .onlyRetrieveFromCache(true)
                        .timeout(2000)
                )
                .transition(DrawableTransitionOptions.withCrossFade(factory))
        } catch (e: Exception) {
            null
        }
    }

    private fun logEvent(msg: String, details: String) {
        eventLogger.log(EventType.FW_RULE_MODIFIED, Severity.LOW, msg, EventSource.UI, false, details)
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) {
            if (isAdded && view != null) {
                f()
            }
        }
    }
}
