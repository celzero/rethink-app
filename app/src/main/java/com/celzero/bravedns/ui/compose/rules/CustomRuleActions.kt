/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.rules

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.database.*
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.ui.activity.CustomRulesActivity
import com.celzero.bravedns.ui.fragment.RulesImportHelper
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.*

internal class CustomRuleActions(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val logger: EventLogger,
    private val domain: Boolean,
    private val uid: Int,
    private val rules: CustomRulesActivity.RULES,
    private val state: RulesPageState,
    private val port: CustomRulePort = ManagerCustomRulePort(context),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main
) {
    fun submit(block: Boolean) { if (domain) submitDomain(block) else submitIp(block) }
    private fun submitIp(block: Boolean) {
        val previous = state.editing as? CustomIp
        val status = ipActionStatus(block, previous?.uid ?: uid)
        val input = state.input
        owner.lifecycleScope.launch(mainDispatcher) {
            val value = port.normalize(input)
            val (ip, number) = withContext(ioDispatcher) { port.parseIp(value) }
            if (ip == null || value.isEmpty()) { state.error = R.string.ci_dialog_error_invalid_ip; return@launch }
            if (!port.isCidrEnforceable(ip)) { state.error = R.string.ci_dialog_error_invalid_cidr; return@launch }
            if (previous == null) {
                state.input = ""; state.error = null
                io { port.addIp(uid, ip, number, status) }
                toast(R.string.ci_dialog_added_success)
                log("Added IP rule: $ip, Port: $number, Status: $status, UID: $uid")
            } else {
                io { port.replaceIp(previous, ip, number, status) }
                log("Updated Custom IP rule: Prev[$previous], New[IP: $ip, Port: $number, Status: $status]")
            }
        }
    }
    private fun submitDomain(block: Boolean) {
        state.error = null
        val previous = state.editing as? CustomDomain
        val type = state.domainType
        val status = if (block) DomainRulesManager.Status.BLOCK else DomainRulesManager.Status.TRUST
        val input = if (previous == null) state.input else state.input.trim()
        val host = port.extractHost(input)
        if (host == null) { state.error = R.string.cd_dialog_error_invalid_domain; return }
        when (type) {
            DomainRulesManager.DomainType.WILDCARD -> if (!port.isWildcard(host)) { state.error = R.string.cd_dialog_error_invalid_wildcard; return }
            DomainRulesManager.DomainType.DOMAIN -> if (!port.isDomain(host)) { state.error = R.string.cd_dialog_error_invalid_domain; return }
        }
        val normalized = port.normalize(host)
        if (previous == null) {
            io { port.addDomain(normalized, status, type, uid) }
            toast(R.string.cd_toast_added)
            log("Added domain: $normalized, Type: $type, Status: $status, UID: $uid")
        } else io {
            port.replaceDomain(normalized, status, type, previous)
            withContext(mainDispatcher) { toast(R.string.cd_toast_edit) }
            log("Custom domain insert/update $normalized, status: $status")
        }
    }
    fun deleteConfirmed() {
        state.deleteOpen = false
        io {
            val selected = state.selected.toList()
            if (domain) {
                val items = selected.filterIsInstance<CustomDomain>()
                if (items.isNotEmpty()) {
                    withContext(mainDispatcher) { state.clearSelection() }
                    port.deleteDomains(items)
                    log("Deleted domains: $items, Rule: $rules, UID: $uid")
                } else if (rules == CustomRulesActivity.RULES.APP_SPECIFIC_RULES) {
                    port.deleteDomainsByUid(uid); log("Deleted all domains for UID: $uid")
                } else { port.deleteAllDomains(); log("Deleted all custom domain rules") }
            } else {
                val items = selected.filterIsInstance<CustomIp>()
                if (items.isNotEmpty()) {
                    port.deleteIps(items)
                    withContext(mainDispatcher) { state.clearSelection() }
                    log("Deleted IP rules: $items")
                } else if (rules == CustomRulesActivity.RULES.APP_SPECIFIC_RULES) {
                    port.deleteIpsByUid(uid); log("Deleted all IP rules for UID: $uid")
                } else { port.deleteAllIps(); log("Deleted all IP rules for all apps") }
            }
        }
        toast(if (domain) R.string.cd_deleted_toast else R.string.univ_ip_delete_toast_success)
    }
    suspend fun parseImport(uri: String) {
        if (!DEBUG) return
        val parsed = withContext(ioDispatcher) { port.parseImport(Uri.parse(uri), if (domain) RulesImportHelper.ImportType.DOMAIN else RulesImportHelper.ImportType.IP) }
        if (state.importUri != uri) return
        if (parsed == null || parsed.valid.isEmpty()) {
            toast(if (parsed == null) R.string.import_rules_error_unreadable else R.string.import_rules_error_empty)
            state.dismissImport()
        } else state.parsedImport = parsed
    }
    fun importConfirmed() {
        if (!DEBUG) return
        val parsed = state.parsedImport ?: return
        val block = state.importBlock
        state.dismissImport()
        io {
            val summary = if (domain) port.importRules(parsed.valid, RulesImportHelper.ImportType.DOMAIN, uid,
                domainStatus = if (block) DomainRulesManager.Status.BLOCK else DomainRulesManager.Status.TRUST)
            else port.importRules(parsed.valid, RulesImportHelper.ImportType.IP, uid, ipStatus = ipActionStatus(block, uid))
            withContext(mainDispatcher) { state.importSummary = context.getString(R.string.import_rules_summary, summary.imported, summary.duplicates, summary.invalid) }
            log("Import complete: imported=${summary.imported}, duplicates=${summary.duplicates}, invalid=${summary.invalid}, uid=$uid")
        }
    }
    private fun toast(id: Int) = Utilities.showToastUiCentered(context, context.getString(id), Toast.LENGTH_SHORT)
    private fun log(details: String) = logger.log(EventType.FW_RULE_MODIFIED, Severity.LOW, if (domain) "Custom Domain" else "Custom IP", EventSource.UI, false, details)
    private fun io(block: suspend () -> Unit) { owner.lifecycleScope.launch(ioDispatcher) { block() } }
}
internal fun ipActionStatus(block: Boolean, uid: Int): IpRulesManager.IpRuleStatus = when {
    block -> IpRulesManager.IpRuleStatus.BLOCK
    uid == UID_EVERYBODY -> IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL
    else -> IpRulesManager.IpRuleStatus.TRUST
}
