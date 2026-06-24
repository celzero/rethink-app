/*
 * Copyright 2024 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.ui.bottomsheet


import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.celzero.bravedns.R
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.ui.compose.rememberDrawablePainter
import com.celzero.bravedns.ui.compose.theme.Dimensions
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AppIpBtmSht"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppIpRulesSheet(
    uid: Int,
    ipAddress: String,
    domains: String,
    eventLogger: EventLogger,
    onDismiss: () -> Unit,
    onUpdated: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var ipRule by remember { mutableStateOf(IpRulesManager.IpRuleStatus.NONE) }
    var appNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var appIcon by remember { mutableStateOf<Drawable?>(null) }
    val domainList = remember(domains) { domains.split(",").map { it.trim() }.filter { it.isNotEmpty() } }
    val domainRules = remember { mutableStateMapOf<String, DomainRulesManager.Status>() }

    LaunchedEffect(uid, ipAddress, domains) {
        val (names, icon) = withContext(Dispatchers.IO) { fetchRuleSheetAppIdentity(context, uid) }
        appNames = names
        appIcon = icon
        ipRule = withContext(Dispatchers.IO) {
            IpRulesManager.getMostSpecificRuleMatch(uid, ipAddress)
        }
        val statuses =
            withContext(Dispatchers.IO) {
                domainList.associateWith { DomainRulesManager.getDomainRule(it, uid) }
            }
        domainRules.clear()
        domainRules.putAll(statuses)
    }

    RuleSheetModal(onDismissRequest = onDismiss) {
        val appName = formatRuleSheetAppName(context, appNames)
        RuleSheetLayout(bottomPadding = RuleSheetBottomPaddingWithActions) {
            RuleSheetAppHeader(appName = appName, appIcon = appIcon)

            RuleSheetSectionTitle(
                text = stringResource(R.string.bsct_block_ip),
            )

            RuleSheetTrustBlockRow(
                value = ipAddress,
                isTrustSelected = ipRule == IpRulesManager.IpRuleStatus.TRUST,
                isBlockSelected = ipRule == IpRulesManager.IpRuleStatus.BLOCK,
                onTrustClick = {
                    val target =
                        if (ipRule == IpRulesManager.IpRuleStatus.TRUST) {
                            IpRulesManager.IpRuleStatus.NONE
                        } else {
                            IpRulesManager.IpRuleStatus.TRUST
                        }
                    applyIpRule(
                        uid,
                        ipAddress,
                        target,
                        scope,
                        eventLogger,
                        onUpdated
                    ) { ipRule = it }
                },
                onBlockClick = {
                    val target =
                        if (ipRule == IpRulesManager.IpRuleStatus.BLOCK) {
                            IpRulesManager.IpRuleStatus.NONE
                        } else {
                            IpRulesManager.IpRuleStatus.BLOCK
                        }
                    applyIpRule(
                        uid,
                        ipAddress,
                        target,
                        scope,
                        eventLogger,
                        onUpdated
                    ) { ipRule = it }
                }
            )

            if (domainList.isNotEmpty()) {
                RuleSheetSectionTitle(
                    text = stringResource(R.string.bsct_block_domain),
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Dimensions.screenPaddingHorizontal)
                ) {
                    items(domainList, key = { it }) { domain ->
                        val status = domainRules[domain] ?: DomainRulesManager.Status.NONE
                        DomainRuleRow(
                            domain = domain,
                            status = status,
                            onUpdate = { newStatus ->
                                domainRules[domain] = newStatus
                                applyDomainRule(domain, uid, newStatus, scope)
                            }
                        )
                    }
                }
            }

            RuleSheetSupportingText(
                text = stringResource(R.string.bsac_title_desc),
            )
        }
    }
}

@Composable
private fun DomainRuleRow(
    domain: String,
    status: DomainRulesManager.Status,
    onUpdate: (DomainRulesManager.Status) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Dimensions.spacingSm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = domain,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        TrustBlockToggleStrip(
            isTrustSelected = status == DomainRulesManager.Status.TRUST,
            isBlockSelected = status == DomainRulesManager.Status.BLOCK,
            onTrustClick = {
                if (status == DomainRulesManager.Status.TRUST) {
                    onUpdate(DomainRulesManager.Status.NONE)
                } else {
                    onUpdate(DomainRulesManager.Status.TRUST)
                }
            },
            onBlockClick = {
                if (status == DomainRulesManager.Status.BLOCK) {
                    onUpdate(DomainRulesManager.Status.NONE)
                } else {
                    onUpdate(DomainRulesManager.Status.BLOCK)
                }
            },
            iconSize = Dimensions.iconSizeMd,
            spacingBefore = Dimensions.spacingSmMd,
            spacingBetween = Dimensions.spacingSmMd
        )
    }
}

private fun applyDomainRule(
    domain: String,
    uid: Int,
    status: DomainRulesManager.Status,
    scope: CoroutineScope
) {
    scope.launch(Dispatchers.IO) {
        DomainRulesManager.addDomainRule(
            domain.trim(),
            status,
            DomainRulesManager.DomainType.DOMAIN,
            uid
        )
    }
}

private fun applyIpRule(
    uid: Int,
    ipAddress: String,
    status: IpRulesManager.IpRuleStatus,
    scope: CoroutineScope,
    eventLogger: EventLogger,
    onUpdated: () -> Unit,
    onSetStatus: (IpRulesManager.IpRuleStatus) -> Unit
) {
    onSetStatus(status)
    val details = "IP Rule set to ${status.name} for IP: $ipAddress, UID: $uid"
    logFirewallRuleChange(eventLogger, "Custom IP", details)
    scope.launch(Dispatchers.IO) {
        val ipPair = IpRulesManager.getIpNetPort(ipAddress)
        val ip = ipPair.first ?: run {
            Napier.w("$TAG invalid ip for $ipAddress")
            return@launch
        }
        IpRulesManager.addIpRule(uid, ip, null, status, proxyId = "", proxyCC = "")
        withContext(Dispatchers.Main) { onUpdated() }
    }
}
