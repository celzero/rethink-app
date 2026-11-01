/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.celzero.bravedns.ui.compose.rules

import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextOverflow
import androidx.appcompat.content.res.AppCompatResources
import com.celzero.bravedns.ui.compose.utilities.utilityTextStyle
import androidx.compose.ui.graphics.Color
import com.celzero.bravedns.ui.compose.controls.*
import com.celzero.bravedns.ui.compose.fields.OriginalTextField
import com.celzero.bravedns.ui.compose.fields.OriginalFieldStyle
import android.content.Intent
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.database.CustomDomain
import com.celzero.bravedns.database.CustomIp
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.ui.activity.CustomRulesActivity
import com.celzero.bravedns.ui.compose.settings.NoDimDialogWindow
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Utilities
import inet.ipaddr.IPAddressString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Serializable

@Composable
internal fun <T : Serializable> RulesPage(domain: Boolean, uid: Int, rules: CustomRulesActivity.RULES,
    source: LiveData<PagingData<T>>, count: LiveData<Int>, onQuery: (String) -> Unit,
    logger: EventLogger, active: Boolean = true, onDetails: (T) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val state = rememberSaveable(saver = RulesPageState.Saver) { RulesPageState() }
    val actions = remember(context, owner, domain, uid, rules, state, logger) { CustomRuleActions(context, owner, logger, domain, uid, rules, state) }
    val rows = remember(source, owner) { source.asFlow().flowWithLifecycle(owner.lifecycle, Lifecycle.State.STARTED) }.collectAsLazyPagingItems()
    val size by remember(count, owner) { count.asFlow().flowWithLifecycle(owner.lifecycle, Lifecycle.State.STARTED) }.collectAsState(-1)
    val list = rememberLazyListState()
    val appSpecific = rules == CustomRulesActivity.RULES.APP_SPECIFIC_RULES
    var name by remember(uid) { mutableStateOf<String?>(null) }
    LaunchedEffect(uid, appSpecific) { if (appSpecific) name = withContext(Dispatchers.IO) { FirewallManager.getAppNameByUid(uid) } }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val queryCallback by rememberUpdatedState(onQuery)
    DisposableEffect(owner, active, state, focus, keyboard) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeRulesSearch(state, queryCallback) { focus.clearFocus(); keyboard?.hide() }
        }
        if (active) owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (DEBUG && uri != null) { state.parsedImport = null; state.importBlock = true; state.importUri = uri.toString() }
    }
    LaunchedEffect(state.importUri) { state.importUri?.let { actions.parseImport(it) } }
    val baseHint = stringResource(if (domain) R.string.search_custom_domains else R.string.search_universal_ips)
    val hint = name?.takeIf { domain || it.isNotEmpty() }?.let { stringResource(R.string.two_argument_colon, it.take(10), baseHint) } ?: baseHint
    Surface(Modifier.fillMaxSize(), color = themeColor(R.attr.background)) {
        Box(Modifier.fillMaxSize().imePadding()) {
            if (size == 0) Column(Modifier.align(Alignment.Center).verticalScroll(rememberScrollState()).padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(if (domain) R.string.cd_no_rules_text else R.string.ci_no_rules_text), fontSize = 18.sp)
                Image(painterResource(R.drawable.illustrations_no_record), null, Modifier.width(220.dp).padding(top = 30.dp))
            } else Column(Modifier.fillMaxSize()) {
                OriginalCardSurface(Modifier.fillMaxWidth().padding(4.dp), cornerRadius = 16.dp) {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                        OriginalTextField(state.query, { updateRulesSearch(state, it, onQuery) }, Modifier.weight(.9f).testTag("rules_search"), style = OriginalFieldStyle.Search, textStyle = androidx.compose.ui.text.TextStyle(fontSize = if (name != null) 14.sp else TextUnit.Unspecified),
                            singleLine = true, placeholder = { Text(hint, fontSize = if (name != null) 14.sp else TextUnit.Unspecified) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submitRulesSearch(state, onQuery) }),
                            trailingIcon = { if (state.query.isNotEmpty()) IconButton({ updateRulesSearch(state, "", onQuery) }) { Icon(painterResource(androidx.appcompat.R.drawable.abc_ic_clear_material), stringResource(R.string.fapps_filter_clear_btn), tint = themeColor(androidx.appcompat.R.attr.colorControlNormal)) } })
                        Box(Modifier.weight(.1f).fillMaxHeight().clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null, role = Role.Button) { state.deleteOpen = true }
                            .testTag("rules_delete").padding(7.dp), contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.lbl_delete), tint = Color.Unspecified)
                        }
                    }
                }
                LazyColumn(Modifier.fillMaxSize().testTag("rules_list"), state = list, contentPadding = PaddingValues(bottom = 150.dp)) {
                    items(rows.itemCount, key = rows.itemKey { ruleKey(it) }) { index ->
                        val item = rows[index]
                        if (item == null) Spacer(Modifier.height(72.dp)) else {
                            val itemUid = ruleUid(item)
                            val header = showRuleHeader(!appSpecific, index, if (index > 0) rows.peek(index - 1)?.let(::ruleUid) else null, itemUid)
                            if (header) RuleAppHeader(itemUid, domain) {
                                context.startActivity(Intent(context, CustomRulesActivity::class.java).apply {
                                    putExtra(Constants.VIEW_PAGER_SCREEN_TO_LOAD, if (domain) CustomRulesActivity.Tabs.DOMAIN_RULES.screen else CustomRulesActivity.Tabs.IP_RULES.screen)
                                    putExtra(Constants.INTENT_UID, itemUid)
                                })
                            }
                            CustomRuleRow(item, state.selectionMode, state.selected.contains(item), header,
                                { if (state.selectionMode) state.toggleSelection(item) else onDetails(item) },
                                { state.longSelect(item) }, { state.selectionMode = true; state.toggleSelection(item) },
                                { state.openEditor(item) }, { onDetails(item) })
                        }
                    }
                }
            }
            Column(Modifier.align(Alignment.BottomEnd).padding(30.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (DEBUG) SmallFloatingActionButton({ picker.launch(arrayOf("text/plain")) }, Modifier.testTag("rules_import")) { Icon(painterResource(R.drawable.ic_import_conf), stringResource(R.string.import_rules_fab_desc)) }
                if (appSpecific) SmallFloatingActionButton({ state.openEditor() }, Modifier.testTag("rules_add")) { Icon(painterResource(R.drawable.ic_fab_without_border), stringResource(R.string.lbl_create), Modifier.size(24.dp)) }
            }
        }
    }
    if (state.editorOpen) RuleEditor(domain, (state.editing as? CustomIp)?.uid ?: uid, state, actions::submit)
    if (state.deleteOpen) RuleConfirmDialog(stringResource(if (domain) R.string.univ_delete_domain_dialog_title else R.string.univ_delete_firewall_dialog_title),
        stringResource(if (domain) R.string.univ_delete_domain_dialog_message else R.string.univ_delete_firewall_dialog_message), stringResource(R.string.univ_ip_delete_dialog_positive),
        actions::deleteConfirmed, { state.deleteOpen = false; state.clearSelection() }, { state.deleteOpen = false })
    if (DEBUG) state.parsedImport?.let { parsed ->
        RuleDialog(stringResource(R.string.import_rules_dialog_title), state::dismissImport, sourceLayout = true) {
            RuleImportRow(stringResource(R.string.import_rules_file_label), parsed.fileName, fileName = true)
            RuleImportRow(stringResource(R.string.import_rules_valid_label), parsed.valid.size.toString())
            RuleImportRow(stringResource(R.string.import_rules_ignored_label), parsed.invalidCount.toString())
            Text(stringResource(R.string.import_rules_action_label), Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 12.dp),
                style = utilityTextStyle(size = 12f), color = themeColor(R.attr.primaryLightColorText))
            Column(Modifier.padding(horizontal = 10.dp).selectableGroup()) {
                RuleRadioOption("dialog_import_confirm:dic_block_radio", stringResource(R.string.ci_block), state.importBlock) { state.importBlock = true }
                RuleRadioOption("dialog_import_confirm:dic_allow_radio", stringResource(if (!domain && uid == Constants.UID_EVERYBODY) R.string.bypass_universal else R.string.ci_trust_rule), !state.importBlock) { state.importBlock = false }
            }
            RuleButtons(Modifier.padding(top = 10.dp)) {
                OriginalButton("dialog_import_confirm:dic_cancel_btn", state::dismissImport) { Text(stringResource(R.string.lbl_cancel)) }
                OriginalButton("dialog_import_confirm:dic_import_btn", actions::importConfirmed, Modifier.testTag("rules_import_confirm")) { Text(stringResource(R.string.import_rules_btn)) }
            }
        }
    }
    state.importSummary?.let { summary -> RuleDialog(stringResource(R.string.import_rules_complete_title), { state.importSummary = null }) {
        Text(summary)
        RuleButtons { OriginalButton("dialog_info_rules_layout:info_rules_dialog_ok_btn", { state.importSummary = null }) { Text(stringResource(R.string.fapps_info_dialog_positive_btn)) } }
    } }
}

@Composable
internal fun RuleEditor(domain: Boolean, uid: Int, state: RulesPageState, onSubmit: (Boolean) -> Unit) {
    RuleDialog(stringResource(if (domain) R.string.cd_dialog_title else R.string.ci_dialog_title), { state.editorOpen = false }, sourceLayout = true) {
        Text(stringResource(if (domain) R.string.cd_dialog_desc else R.string.ci_dialog_desc),
            Modifier.fillMaxWidth().padding(start = 5.dp).padding(5.dp).testTag("rule_editor_description"),
            style = utilityTextStyle(size = 11f), color = themeColor(R.attr.primaryLightColorText))
        if (domain) FlowRow(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            OriginalChip("dialog_add_custom_domain:dacd_domain_chip", state.domainType == DomainRulesManager.DomainType.DOMAIN, { state.domainType = DomainRulesManager.DomainType.DOMAIN }, { Text(stringResource(R.string.lbl_domain), fontSize = 12.sp) }, Modifier.padding(3.dp))
            OriginalChip("dialog_add_custom_domain:dacd_wildcard_chip", state.domainType == DomainRulesManager.DomainType.WILDCARD, { state.domainType = DomainRulesManager.DomainType.WILDCARD }, { Text(stringResource(R.string.lbl_wildcard), fontSize = 12.sp) }, Modifier.padding(3.dp))
        }
        val hint = if (domain) stringResource(R.string.cd_dialog_edittext_hint, stringResource(if (state.domainType == DomainRulesManager.DomainType.WILDCARD) R.string.lbl_wildcard else R.string.lbl_domain)) else stringResource(R.string.ci_dialog_edittext_hint)
        OriginalTextField(state.input, {
            state.input = it
            if (domain) state.domainType = if (it.startsWith("*") || it.startsWith(".")) DomainRulesManager.DomainType.WILDCARD else DomainRulesManager.DomainType.DOMAIN else state.error = null
        }, Modifier.fillMaxWidth().padding(horizontal = if (domain) 0.dp else 20.dp).testTag("rule_input"), style = OriginalFieldStyle.Rule, label = { Text(hint) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), isError = state.error != null, errorMessage = state.error?.let { stringResource(it) })
        // The failure view has only a top constraint; its bottom margin is unconnected.
        state.error?.let { Text(stringResource(it), Modifier.fillMaxWidth().padding(start = 10.dp, top = 10.dp, end = 10.dp).padding(5.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }.testTag("rule_error"), color = themeColor(androidx.appcompat.R.attr.colorError),
            style = utilityTextStyle(androidx.appcompat.R.style.TextAppearance_AppCompat_Body1, size = 12f)) }
        RuleButtons(Modifier.padding(top = 10.dp)) {
            OriginalButton(if (domain) "dialog_add_custom_domain:dacd_cancel_btn" else "dialog_add_custom_ip:daci_cancel_btn", { state.editorOpen = false }, Modifier.testTag("rule_editor_done")) { Text(stringResource(R.string.fapps_info_dialog_positive_btn)) }
            OriginalButton(if (domain) "dialog_add_custom_domain:dacd_trust_btn" else "dialog_add_custom_ip:daci_trust_btn", { onSubmit(false) }, Modifier.padding(start = if (domain) 10.dp else 0.dp).testTag("rule_allow")) { Text(stringResource(if (!domain && uid == Constants.UID_EVERYBODY) R.string.bypass_universal else R.string.ci_trust_rule)) }
            OriginalButton(if (domain) "dialog_add_custom_domain:dacd_block_btn" else "dialog_add_custom_ip:daci_block_btn", { onSubmit(true) }, Modifier.padding(start = if (domain) 10.dp else 0.dp).testTag("rule_block")) { Text(stringResource(R.string.block)) }
        }
    }
}
/** Custom-layout content insets are separate from the still-unverified dialog window shell. */
@Composable internal fun RuleDialog(title: String, onDismiss: () -> Unit, sourceLayout: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        NoDimDialogWindow()
        Surface(Modifier.padding(16.dp).widthIn(max = 560.dp).fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
            color = if (sourceLayout) themeColor(R.attr.background) else MaterialTheme.colorScheme.surface) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(if (sourceLayout) 10.dp else 20.dp),
                verticalArrangement = if (sourceLayout) Arrangement.Top else Arrangement.spacedBy(10.dp)) {
                if (sourceLayout) Text(title, Modifier.fillMaxWidth().padding(10.dp).testTag("rule_editor_title"),
                    style = utilityTextStyle(androidx.appcompat.R.style.TextAppearance_AppCompat_Subhead, size = 14f), color = themeColor(R.attr.primaryTextColor))
                else Text(title, style = MaterialTheme.typography.titleLarge, fontSize = 20.sp)
                content()
            }
        }
    }
}
@Composable internal fun RuleButtons(modifier: Modifier = Modifier, content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, content = content)
}
@Composable private fun RuleImportRow(label: String, value: String, fileName: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 4.dp)) {
        Text(label, style = utilityTextStyle(size = 12f), color = themeColor(R.attr.primaryLightColorText))
        Text(value, Modifier.then(if (fileName) Modifier.weight(1f) else Modifier).padding(start = 8.dp),
            style = utilityTextStyle(size = 12f), color = themeColor(R.attr.primaryTextColor),
            maxLines = if (fileName) 1 else Int.MAX_VALUE, overflow = if (fileName) TextOverflow.MiddleEllipsis else TextOverflow.Clip)
    }
}
@Composable internal fun RuleConfirmDialog(title: String, message: String, confirm: String, onConfirm: () -> Unit, onCancel: () -> Unit, onDismiss: () -> Unit) {
    RuleDialog(title, onDismiss) { Text(message); RuleButtons {
        OriginalButton("@dialog_negative", onCancel, Modifier.testTag("rule_delete_cancel")) { Text(stringResource(R.string.lbl_cancel)) }
        OriginalButton("@dialog_positive", onConfirm, Modifier.testTag("rule_delete_confirm")) { Text(confirm) }
    } }
}
@Composable internal fun RuleRadioOption(compoundSource: String, label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OriginalRadioButton(compoundSource, selected, null, indicatorOnly = true)
        Text(label, Modifier.weight(1f), style = utilityTextStyle(size = 14f), color = themeColor(R.attr.primaryTextColor))
    }
}
internal data class RuleAppMetadata(val name: String = "", val icon: ImageBitmap? = null)
@Composable internal fun rememberRuleApp(uid: Int, domain: Boolean, sheet: Boolean = false): RuleAppMetadata {
    val context = LocalContext.current
    val resources = LocalResources.current
    val configuration = LocalConfiguration.current
    var result by remember(uid, domain, sheet, context, configuration) { mutableStateOf(RuleAppMetadata()) }
    LaunchedEffect(uid, domain, sheet, context, configuration) { result = withContext(Dispatchers.IO) {
        if (sheet && uid == Constants.UID_EVERYBODY) return@withContext RuleAppMetadata(resources.getString(R.string.firewall_act_universal_tab).replaceFirstChar(Char::titlecase), if (domain) Utilities.getDefaultIcon(context)?.toBitmap()?.asImageBitmap() else null)
        val names = FirewallManager.getAppNamesByUid(uid)
        val info = FirewallManager.getAppInfoByUid(uid)
        val label = when {
            uid == Constants.UID_EVERYBODY -> resources.getString(R.string.firewall_act_universal_tab).replaceFirstChar(Char::titlecase)
            names.isEmpty() -> if (domain && !sheet) resources.getString(R.string.network_log_app_name_unnamed, "($uid)") else resources.getString(R.string.network_log_app_name_unknown) + " ($uid)"
            names.size >= 2 -> resources.getString(R.string.ctbs_app_other_apps, names[0], (names.size - 1).toString())
            else -> names[0]
        }
        RuleAppMetadata(label, (Utilities.getIcon(context, info?.packageName.orEmpty(), info?.appName.orEmpty()) ?: Utilities.getDefaultIcon(context))?.toBitmap()?.asImageBitmap())
    } }
    return result
}
@Composable internal fun RuleAppIcon(icon: ImageBitmap?) {
    if (icon != null) Image(icon, stringResource(R.string.apps_icon_content_desc), Modifier.size(40.dp))
    else Image(painterResource(R.drawable.default_app_icon), stringResource(R.string.apps_icon_content_desc), Modifier.size(40.dp))
}
@Composable internal fun RuleAppHeader(uid: Int, domain: Boolean, onMore: () -> Unit) {
    val app = rememberRuleApp(uid, domain)
    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        RuleAppIcon(app.icon); Text(app.name, Modifier.weight(1f).padding(horizontal = 8.dp), fontSize = 16.sp)
        OriginalButton(if (domain) "list_item_custom_all_domain:custom_domain_see_more_chip" else "list_item_custom_all_ip:custom_ip_see_more_chip", onMore) { Text(stringResource(R.string.ssv_see_more)) }
    }
}
@Composable internal fun rememberRuleFlag(ip: CustomIp): String {
    val context = LocalContext.current
    var flag by remember(ip.ipAddress, ip.wildcard) { mutableStateOf("") }
    LaunchedEffect(ip.ipAddress, ip.wildcard) { if (!ip.wildcard) flag = withContext(Dispatchers.IO) {
        val inet = try { IPAddressString(ip.ipAddress).hostAddress.toInetAddress() } catch (_: Exception) { null }
        Utilities.getFlag(Utilities.getCountryCode(inet, context))
    } }
    return flag
}
@Composable internal fun CustomRuleRow(item: Serializable, selecting: Boolean, selected: Boolean, header: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit, onCheck: () -> Unit, onEdit: () -> Unit, onDetails: () -> Unit) {
    val domain = item is CustomDomain
    val status = if (item is CustomDomain) item.status else (item as CustomIp).status
    val modified = if (item is CustomDomain) item.modifiedTs else (item as CustomIp).modifiedDateTime
    val title = if (item is CustomDomain) item.domain else (item as CustomIp).let { stringResource(R.string.ci_ip_label, it.ipAddress, it.port.toString()) }
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(5.dp), verticalAlignment = Alignment.CenterVertically) {
        if (selecting) { val label = stringResource(R.string.compose_rule_select, title); OriginalCheckbox(if (domain) { if (header) "list_item_custom_all_domain:custom_domain_checkbox" else "list_item_custom_domain:custom_domain_checkbox" } else { if (header) "list_item_custom_all_ip:custom_ip_checkbox" else "list_item_custom_ip:custom_ip_checkbox" }, selected, { onCheck() }, Modifier.semantics { contentDescription = label }) }
        if (item is CustomIp) Text(rememberRuleFlag(item), Modifier.padding(end = 8.dp), fontSize = 30.sp)
        Column(Modifier.weight(1f)) { Text(title, fontSize = 16.sp); RuleStatusDescription(domain, status, modified, if (domain && !header) R.string.ci_trust_rule else R.string.ci_trust_txt, true) }
        RuleStatusBadge(domain, status, modifier = Modifier.padding(start = 5.dp, end = 10.dp))
        IconButton(onEdit) { Icon(painterResource(R.drawable.ic_edit_icon_grey), stringResource(R.string.compose_rule_edit), Modifier.size(24.dp).alpha(.5f), tint = Color.Unspecified) }
        IconButton(onDetails) { Icon(painterResource(R.drawable.ic_arrow_down_small), stringResource(R.string.compose_rule_details), Modifier.alpha(.5f), tint = Color.Unspecified) }
    }
}
@Composable internal fun RuleStatusDescription(domain: Boolean, status: Int, modified: Long, trust: Int = R.string.ci_trust_txt, italic: Boolean = false) {
    val label = stringResource(ruleStatusLabel(domain, status, trust))
    val time = DateUtils.getRelativeTimeSpanString(modified, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
    val text = stringResource(R.string.ci_desc, if (italic) "<i>$label</i>" else label, time)
    Text(if (italic) AnnotatedString.fromHtml(text) else AnnotatedString(text), fontSize = if (italic) 11.sp else 12.sp)
}
internal fun ruleStatusLabel(domain: Boolean, status: Int, trust: Int): Int = if (domain) when (DomainRulesManager.Status.getStatus(status)) {
    DomainRulesManager.Status.NONE -> R.string.cd_no_rule_txt
    DomainRulesManager.Status.BLOCK -> R.string.lbl_blocked
    DomainRulesManager.Status.TRUST -> trust
} else when (IpRulesManager.IpRuleStatus.getStatus(status)) {
    IpRulesManager.IpRuleStatus.NONE -> R.string.ci_no_rule_txt
    IpRulesManager.IpRuleStatus.BLOCK -> R.string.lbl_blocked
    IpRulesManager.IpRuleStatus.TRUST -> trust
    IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL -> R.string.ci_bypass_universal_txt
}
@Composable internal fun RuleStatusBadge(domain: Boolean, status: Int, active: Boolean = true,
    detail: Boolean = false, modifier: Modifier = Modifier) {
    val none = if (domain) status == DomainRulesManager.Status.NONE.id else status == IpRulesManager.IpRuleStatus.NONE.id
    val block = if (domain) status == DomainRulesManager.Status.BLOCK.id else status == IpRulesManager.IpRuleStatus.BLOCK.id
    val label = when { none -> if (domain) R.string.cd_no_rule_initial else R.string.ci_no_rule_initial; block -> if (domain) R.string.cd_blocked_initial else R.string.ci_blocked_initial; !domain && status == IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL.id -> R.string.ci_bypass_universal_initial; else -> R.string.ci_trust_initial }
    val fg = if (!active) colorResource(R.color.defaultToggleBtnTxt) else themeColor(when { none -> R.attr.chipTextNeutral; block -> R.attr.chipTextNegative; else -> R.attr.chipTextPositive })
    val bg = if (!active) colorResource(R.color.defaultToggleBtnBg) else themeColor(when { none -> R.attr.chipBgColorNeutral; block -> R.attr.chipBgColorNegative; else -> R.attr.chipBgColorPositive })
    val context = LocalContext.current
    val frame = remember(context, bg) { ruleStatusBadgeFrame(context, bg.toArgb()) }
    // rectangle_border_background is a 16dp-radius, .75dp stroke with no solid fill.
    // backgroundTintList in both source adapters/sheets recolors that stroke only.
    Box(modifier.then(if (detail) Modifier.size(32.dp) else Modifier.widthIn(min = 25.dp))
        .drawBehind { frame.setBounds(0, 0, size.width.toInt(), size.height.toInt()); drawIntoCanvas { frame.draw(it.nativeCanvas) } }
        .then(if (detail) Modifier else Modifier.padding(5.dp)), contentAlignment = Alignment.Center) {
        Text(stringResource(label), color = fg,
            style = utilityTextStyle(size = if (detail && !domain && status == IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL.id) 11f else 12f,
                weight = if (detail) android.graphics.Typeface.BOLD else null))
    }
}
internal fun ruleStatusBadgeFrame(context: android.content.Context, tint: Int): android.graphics.drawable.Drawable =
    requireNotNull(AppCompatResources.getDrawable(context, R.drawable.rectangle_border_background)).mutate().apply { setTint(tint) }
