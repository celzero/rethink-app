/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.rules

import com.celzero.bravedns.ui.compose.controls.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celzero.bravedns.R
import com.celzero.bravedns.database.CustomDomain
import com.celzero.bravedns.database.CustomIp
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.IpRulesManager.IpRuleStatus
import com.celzero.bravedns.ui.compose.settings.SettingsCard
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import java.io.Serializable

@Composable internal fun StoredRuleDetails(item: Serializable, selected: Int, displayed: Int, modified: Long, onStatus: (Int) -> Unit, onDelete: () -> Unit) {
    val domain = item is CustomDomain
    RuleDetailsContent(domain, ruleUid(item), if (item is CustomDomain) item.domain else (item as CustomIp).ipAddress,
        rememberRuleApp(ruleUid(item), domain, true), if (item is CustomIp && item.uid == UID_EVERYBODY) rememberRuleFlag(item) else null,
        selected, displayed, modified, onStatus, onDelete)
}
/** Presentation-only entry point for fake-content tests; no manager/service access. */
@Composable internal fun RuleDetailsContent(domain: Boolean, uid: Int, title: String, app: RuleAppMetadata, flag: String?,
    selected: Int, displayed: Int, modified: Long, onStatus: (Int) -> Unit, onDelete: () -> Unit) {
    var deleteOpen by remember { mutableStateOf(false) }
    Surface {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 16.dp).size(40.dp, 4.dp).alpha(0.5f).background(themeColor(R.attr.border)))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (flag != null) Text(flag, fontSize = 30.sp) else RuleAppIcon(app.icon)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    SelectionContainer { Text(title, fontSize = 16.sp) }
                    Text(app.name, fontSize = 12.sp)
                }
                IconButton({ deleteOpen = true }, Modifier.testTag("rule_detail_delete")) { Icon(painterResource(R.drawable.ic_delete_small), stringResource(R.string.lbl_delete), Modifier.size(24.dp).alpha(if (domain) .8f else 1f), tint = themeColor(R.attr.accentBad)) }
            }
            SettingsCard(Modifier.padding(top = 16.dp), horizontalContentPadding = 12.dp) {
                Column(Modifier.selectableGroup()) {
                    Text(stringResource(if (domain) R.string.dc_custom_block_heading else R.string.lbl_ip_rules), style = MaterialTheme.typography.titleSmall)
                    val options = if (domain) listOf(DomainRulesManager.Status.NONE.id to R.string.ci_no_rule, DomainRulesManager.Status.BLOCK.id to R.string.ci_block, DomainRulesManager.Status.TRUST.id to R.string.ci_trust_rule)
                    else listOf(IpRuleStatus.NONE.id to R.string.ci_no_rule, IpRuleStatus.BLOCK.id to R.string.ci_block,
                        if (uid == UID_EVERYBODY) IpRuleStatus.BYPASS_UNIVERSAL.id to R.string.ci_bypass_universal else IpRuleStatus.TRUST.id to R.string.ci_trust_rule)
                    options.forEach { (status, label) ->
                        Row(Modifier.fillMaxWidth().selectable(selected == status, role = Role.RadioButton, onClick = { onStatus(status) }).padding(vertical = 8.dp).testTag("rule_status_$status"), verticalAlignment = Alignment.CenterVertically) {
                            RuleStatusBadge(domain, status, selected == status, detail = true)
                            Text(stringResource(label), Modifier.weight(1f).padding(horizontal = 12.dp), fontSize = 14.sp)
                            OriginalRadioButton(if (domain) "bottom_sheet_custom_domains:" + when (status) {
                                DomainRulesManager.Status.NONE.id -> "custom_domain_rb_no_rule"
                                DomainRulesManager.Status.BLOCK.id -> "custom_domain_rb_block"
                                else -> "custom_domain_rb_whitelist"
                            } else "bottom_sheet_custom_ips:" + when (status) {
                                IpRuleStatus.NONE.id -> "custom_ip_rb_no_rule"
                                IpRuleStatus.BLOCK.id -> "custom_ip_rb_block"
                                IpRuleStatus.BYPASS_UNIVERSAL.id -> "custom_ip_rb_bypass_univ"
                                else -> "custom_ip_rb_bypass_app"
                            }, selected == status, null)
                        }
                    }
                    Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) { RuleStatusDescription(domain, displayed, modified) }
                }
            }
        }
    }
    if (deleteOpen) RuleConfirmDialog(stringResource(if (domain) R.string.cd_remove_dialog_title else R.string.univ_firewall_dialog_title),
        stringResource(if (domain) R.string.cd_remove_dialog_message else R.string.univ_firewall_dialog_message), stringResource(R.string.lbl_delete),
        { deleteOpen = false; onDelete() }, { deleteOpen = false }, { deleteOpen = false })
}
