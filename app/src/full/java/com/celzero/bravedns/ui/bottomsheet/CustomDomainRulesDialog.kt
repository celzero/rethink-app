package com.celzero.bravedns.ui.bottomsheet


import android.graphics.drawable.Drawable
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.database.CustomDomain
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import com.celzero.bravedns.util.Utilities
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "CDRDialog"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomDomainRulesSheet(
    customDomain: CustomDomain,
    eventLogger: EventLogger,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var appNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var appIcon by remember { mutableStateOf<Drawable?>(null) }
    var status by remember { mutableStateOf(DomainRulesManager.Status.NONE) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(customDomain.uid, customDomain.domain) {
        val uid = customDomain.uid
        if (uid != UID_EVERYBODY) {
            val (names, icon) = withContext(Dispatchers.IO) { fetchRuleSheetAppIdentity(context, uid) }
            appNames = names
            appIcon = icon
        } else {
            appNames = emptyList()
            appIcon = null
        }

        val rules = DomainRulesManager.getDomainRule(customDomain.domain, uid)
        status = rules
    }

    RuleSheetModal(onDismissRequest = onDismiss) {
        val appName = formatCustomRuleSheetAppName(context, customDomain.uid, appNames)

        val now = System.currentTimeMillis()
        val time =
            DateUtils.getRelativeTimeSpanString(
                customDomain.modifiedTs,
                now,
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            )
        val statusLabel =
            when (status) {
                DomainRulesManager.Status.TRUST -> stringResource(R.string.ci_trust_txt)
                DomainRulesManager.Status.BLOCK -> stringResource(R.string.lbl_blocked)
                DomainRulesManager.Status.NONE -> stringResource(R.string.cd_no_rule_txt)
            }
        val statusText = stringResource(R.string.ci_desc, statusLabel, time)
        val deletedToast = stringResource(R.string.cd_toast_deleted)
        val chipColors = rememberRuleSheetChipColors()

        RuleSheetLayout(bottomPadding = RuleSheetBottomPaddingCompact) {
            RuleSheetDeleteAction(onClick = { showDeleteDialog = true })

            RuleSheetAppHeader(appName = appName, appIcon = appIcon)

            RuleSheetSectionTitle(
                text = stringResource(R.string.lbl_domain),
            )

            RuleSheetSelectionValue(text = customDomain.domain)

            RuleSheetSupportingText(
                text = statusText,
            )

            RuleSheetChipOptionsRow(
                options =
                    listOf(
                        RuleSheetChipOption(
                            label = stringResource(R.string.ci_no_rule),
                            selected = status == DomainRulesManager.Status.NONE,
                            selectedText = chipColors.neutralText,
                            selectedContainer = chipColors.neutralBg,
                            onClick = {
                                updateRule(
                                    customDomain,
                                    DomainRulesManager.Status.NONE,
                                    scope,
                                    eventLogger
                                ) { newStatus ->
                                    status = newStatus
                                }
                            }
                        ),
                        RuleSheetChipOption(
                            label = stringResource(R.string.ci_block),
                            selected = status == DomainRulesManager.Status.BLOCK,
                            selectedText = chipColors.negativeText,
                            selectedContainer = chipColors.negativeBg,
                            onClick = {
                                updateRule(
                                    customDomain,
                                    DomainRulesManager.Status.BLOCK,
                                    scope,
                                    eventLogger
                                ) { newStatus ->
                                    status = newStatus
                                }
                            }
                        ),
                        RuleSheetChipOption(
                            label = stringResource(R.string.ci_trust_rule),
                            selected = status == DomainRulesManager.Status.TRUST,
                            selectedText = chipColors.positiveText,
                            selectedContainer = chipColors.positiveBg,
                            onClick = {
                                updateRule(
                                    customDomain,
                                    DomainRulesManager.Status.TRUST,
                                    scope,
                                    eventLogger
                                ) { newStatus ->
                                    status = newStatus
                                }
                            }
                        )
                    )
            )
        }

        if (showDeleteDialog) {
            RuleSheetDeleteDialog(
                title = stringResource(R.string.cd_remove_dialog_title),
                message = stringResource(R.string.cd_remove_dialog_message),
                onDismiss = { showDeleteDialog = false },
                onConfirm = {
                    showDeleteDialog = false
                    scope.launch(Dispatchers.IO) {
                        DomainRulesManager.deleteDomain(customDomain)
                        withContext(Dispatchers.Main) {
                            Utilities.showToastUiCentered(
                                context,
                                deletedToast,
                                Toast.LENGTH_SHORT
                            )
                        }
                    }
                    logEvent(
                        eventLogger,
                        "Deleted custom domain rule for ${customDomain.domain}"
                    )
                    onDeleted()
                    onDismiss()
                },
            )
        }
    }
}

private fun updateRule(
    customDomain: CustomDomain,
    rule: DomainRulesManager.Status,
    scope: kotlinx.coroutines.CoroutineScope,
    eventLogger: EventLogger,
    onUpdated: (DomainRulesManager.Status) -> Unit
) {
    launchRuleMutation(scope, mutation = {
        when (rule) {
            DomainRulesManager.Status.NONE -> DomainRulesManager.noRule(customDomain)
            DomainRulesManager.Status.BLOCK -> DomainRulesManager.block(customDomain)
            DomainRulesManager.Status.TRUST -> DomainRulesManager.trust(customDomain)
        }
        val status = DomainRulesManager.Status.getStatus(customDomain.status)
        logEvent(eventLogger, "Domain rule for ${customDomain.domain} set to ${status.name}")
        status
    }, onUpdated = onUpdated)
}

private fun logEvent(eventLogger: EventLogger, details: String) {
    logFirewallRuleChange(eventLogger, "Custom Domain", details, TAG)
}
