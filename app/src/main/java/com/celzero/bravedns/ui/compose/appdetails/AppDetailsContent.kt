@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.celzero.bravedns.ui.compose.appdetails

import com.celzero.bravedns.ui.compose.controls.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import com.celzero.bravedns.ui.compose.utilities.utilityTextStyle
import com.celzero.bravedns.ui.compose.utilities.utilityDimensionSp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import com.celzero.bravedns.R
import androidx.cardview.R as CardR
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.ui.compose.wireguard.*
import com.celzero.bravedns.ui.stats.StatsViewMode

enum class AppInsightsSection(val title: Int, val blocked: Boolean = false) {
    ACTIVE_CONNS(R.string.top_active_conns), COUNTRIES(R.string.ssv_most_contacted_countries_heading),
    ASN(R.string.most_contacted_asn), BLOCKED_ASN(R.string.most_blocked_asn,true),
    BLOCKLISTS(R.string.ssv_blocklists_heading,true), DOMAINS(R.string.ssv_most_contacted_domain_heading),
    BLOCKED_DOMAINS(R.string.ssv_most_blocked_domain_heading,true), IPS(R.string.ssv_most_contacted_ips_heading),
    BLOCKED_IPS(R.string.ssv_most_blocked_ips_heading,true)
}
private data class Section(val type:AppInsightsSection,val heading:WgControlState,val rows:AppDetailListState,val more:WgControlState,val insightsMore:WgControlState)
@Composable fun AppDetailsContent(b:AppDetailsState) {
    Surface(color=themeColor(R.attr.background)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(bottom=24.dp).testTag("app_details")) {
            if(b.aadRethinkWarningContainer.isVisible) Column(Modifier.fillMaxWidth().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Icon(painterResource(R.drawable.ic_loopback),null,Modifier.size(56.dp))
                Text(stringResource(R.string.app_info_rethink_warning_title),Modifier.padding(top=24.dp).semantics{heading()},fontSize=18.sp)
                Text(stringResource(R.string.app_info_rethink_warning_desc),Modifier.padding(top=16.dp),fontSize=14.sp,textAlign=TextAlign.Center,lineHeight=20.3.sp)
                OriginalButton("activity_app_details:aad_rethink_warning_proceed", {b.aadRethinkWarningProceed.performClick()}, Modifier.padding(top=32.dp).testTag("app_warning_proceed")) { Text(stringResource(R.string.app_info_rethink_warning_proceed)) }
            }
            if(b.aadAppParentRl.isVisible) {
                Row(Modifier.fillMaxWidth().padding(start=20.dp,end=12.dp,top=20.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    DrawableImage(b.aadAppDetailIcon.drawable,Modifier.size(64.dp))
                    Column(Modifier.weight(1f).padding(start=16.dp)) {
                        Text(b.aadAppDetailName.text.toString(),fontSize=20.sp)
                        SelectionContainer { Text(b.aadPkgName.text.toString(),fontSize=12.sp) }
                        Text(wgAnnotatedText(b.aadFirewallStatus.text),Modifier.padding(top=6.dp),fontSize=14.sp)
                        Text(b.aadDataUsageStatus.text.toString(),fontSize=12.sp)
                    }
                    IconButton({b.aadAppInfoIcon.performClick()}, Modifier.size(40.dp)) { Icon(painterResource(R.drawable.ic_info_white),stringResource(R.string.about_settings_app_info), Modifier.size(24.dp), tint = themeColor(R.attr.primaryLightColorText)) }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text(stringResource(R.string.ada_firewall_title),Modifier.weight(1f).semantics { heading() },fontSize=14.sp)
                    Chip(stringResource(R.string.lbl_notes),b.aadNotesChip, "aad_notes_chip")
                }
                Surface(Modifier.padding(horizontal=8.dp,vertical=12.dp),shape=RoundedCornerShape(16.dp),color=themeColor(CardR.attr.cardBackgroundColor)) {
                    Column {
                        val rules = listOf(
                            Triple(b.aadAppSettingsBlockWifi,R.string.ada_app_unmetered,R.drawable.ic_firewall_wifi_on_grey),
                            Triple(b.aadAppSettingsBlockMd,R.string.ada_app_metered,R.drawable.ic_firewall_data_on_grey),
                            Triple(b.aadAppSettingsIsolate,R.string.ada_app_isolate,R.drawable.ic_firewall_lockdown_off),
                            Triple(b.aadAppSettingsBypassDnsFirewall,R.string.ada_app_bypass_dns_firewall,R.drawable.ic_firewall_bypass_off),
                            Triple(b.aadAppSettingsBypassUniv,R.string.ada_app_bypass_univ,R.drawable.ic_firewall_bypass_off),
                            Triple(b.aadAppSettingsExclude,R.string.ada_app_exclude,R.drawable.ic_firewall_exclude_off))
                        rules.chunked(3).forEachIndexed { row,values -> Row(Modifier.fillMaxWidth()) {
                            values.forEachIndexed { column,(node,label,defaultIcon) ->
                                Column(Modifier.weight(1f).heightIn(min=72.dp).alpha(node.alpha).clickable(enabled=node.isEnabled,role=Role.Button){node.performClick()}.padding(8.dp).testTag("app_rule_${row*3+column}"),horizontalAlignment=Alignment.CenterHorizontally) {
                                    Image(painterResource(node.imageResource?:defaultIcon),null,Modifier.size(24.dp))
                                    Text(stringResource(label),Modifier.padding(top=8.dp),fontSize=12.sp,textAlign=TextAlign.Center)
                                }
                            }
                        } }
                        HorizontalDivider(Modifier.padding(horizontal=16.dp,vertical=4.dp))
                        Row(Modifier.padding(12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            RuleCount(b.aadIpBlockHeader,b.aadIpBlockCard,R.string.lbl_ip_rules,Modifier.weight(1f))
                            RuleCount(b.aadDomainBlockHeader,b.aadDomainBlockCard,R.string.lbl_domain_rules,Modifier.weight(1f))
                        }
                        SwitchRow("exclude_proxy_switch",b.excludeProxyRl,b.excludeProxySwitch,R.string.exclude_apps_from_proxy,R.drawable.ic_proxy_white,if(b.aadProxyDetails.isVisible)b.aadProxyDetails.text.toString() else "")
                        SwitchRow("temp_allow_switch",b.tempAllowRl,b.tempAllowSwitch,R.string.temp_allow_label,R.drawable.ic_firewall_shield,b.tempAllowDesc.text.toString().ifEmpty{stringResource(R.string.temp_allow_desc)})
                    }
                }
                if(b.aadViewModeToggleGroup.isVisible) Row(Modifier.fillMaxWidth().padding(horizontal=20.dp),horizontalArrangement=Arrangement.End) {
                    ModeButton(b.mode==StatsViewMode.LIST,R.drawable.ic_view_list,R.string.cd_stats_switch_to_list){b.onMode(StatsViewMode.LIST)}
                    ModeButton(b.mode==StatsViewMode.INSIGHTS,R.drawable.ic_view_chart,R.string.cd_stats_switch_to_insights){b.onMode(StatsViewMode.INSIGHTS)}
                }
                val sections=listOf(
                    Section(AppInsightsSection.ACTIVE_CONNS,b.aadActiveConnsRl,b.aadActiveConnsRv,b.aadActiveConnsChip,b.aadIaActiveConnsChip),
                    Section(AppInsightsSection.COUNTRIES,b.aadMostContactedCountriesRl,b.aadMostContactedCountriesRv,b.aadCountriesChip,b.aadIaCountriesChip),
                    Section(AppInsightsSection.ASN,b.aadAsnRl,b.aadAsnRv,b.aadAsnChip,b.aadIaAsnChip),
                    Section(AppInsightsSection.BLOCKED_ASN,b.aadBlockedAsnRl,b.aadBlockedAsnRv,b.aadBlockedAsnChip,b.aadIaBlockedAsnChip),
                    Section(AppInsightsSection.BLOCKLISTS,b.aadBlocklistRl,b.aadBlocklistRv,b.aadBlocklistChip,b.aadIaBlocklistChip),
                    Section(AppInsightsSection.DOMAINS,b.aadMostContactedDomainRl,b.aadMostContactedDomainRv,b.aadDomainsChip,b.aadIaDomainsChip),
                    Section(AppInsightsSection.BLOCKED_DOMAINS,b.aadBlockedDomainsRl,b.aadBlockedDomainsRv,b.aadBlockedDomainsChip,b.aadIaBlockedDomainsChip),
                    Section(AppInsightsSection.IPS,b.aadMostContactedIpsRl,b.aadMostContactedIpsRv,b.aadIpsChip,b.aadIaIpsChip),
                    Section(AppInsightsSection.BLOCKED_IPS,b.aadBlockedIpsRl,b.aadBlockedIpsRv,b.aadBlockedIpsChip,b.aadIaBlockedIpsChip))
                sections.forEach { section ->
                    val insights=b.mode==StatsViewMode.INSIGHTS
                    val items=b.insightsSnapshots[section.type].orEmpty()
                    if(if(insights) items.isNotEmpty() else section.heading.isVisible) {
                        FlowRow(Modifier.fillMaxWidth().padding(start=20.dp,end=12.dp,top=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(section.type.title),Modifier.weight(1f).align(Alignment.CenterVertically).semantics{heading()},fontSize=14.sp)
                            if(!insights && section.type==AppInsightsSection.ACTIVE_CONNS) Chip(stringResource(R.string.close_all),b.aadCloseConnsChip, "aad_close_conns_chip")
                            Chip(stringResource(R.string.ssv_see_more),if(insights)section.insightsMore else section.more, if (insights) "aad_ia_active_conns_chip" else "aad_active_conns_chip")
                        }
                    }
                    if(insights) { if(items.isNotEmpty()) AppDetailsInsights(section.type,items) }
                    else section.rows.adapter?.Content()
                }
            }
        }
    }
}
@Composable private fun ModeButton(selected:Boolean,icon:Int,label:Int,onClick:()->Unit) {
    val description = stringResource(label)
    OriginalButton("activity_app_details:" + if (icon == R.drawable.ic_view_list) "aad_view_mode_list_btn" else "aad_view_mode_insights_btn",
        onClick, Modifier.semantics { contentDescription = description }, selected = selected,
        segmentIndex = if (icon == R.drawable.ic_view_list) 0 else 1, segmentCount = 2,
        containerColor = themeColor(if(selected)R.attr.accentGood else R.attr.defaultToggleBtnBg),
        leadingIcon = { Icon(painterResource(icon),null,Modifier.size(24.dp),tint=themeColor(if(selected)R.attr.homeScreenHeaderTextColor else R.attr.defaultToggleBtnTxt)) }) { }

}
@Composable private fun Chip(label:String,node:WgControlState, sourceId:String) {
    // aad_notes_chip is a MaterialCardView, not a Chip: retain its 18dp card silhouette.
    if (sourceId == "aad_notes_chip") {
        Surface({node.performClick()}, shape=RoundedCornerShape(18.dp), color=themeColor(R.attr.homeScreenBtnBackground)) {
            Text(label, Modifier.padding(horizontal=14.dp, vertical=8.dp), color=themeColor(R.attr.primaryTextColor),
                style=com.celzero.bravedns.ui.compose.utilities.utilityTextStyle(size=12f, family="sans-serif-medium"))
        }
        return
    }
    OriginalButton("activity_app_details:$sourceId", {node.performClick()}, enabled = node.isEnabled && node.isClickable,
        contentColor = node.textColor?.let { androidx.compose.ui.graphics.Color(it) }, backgroundTint = node.backgroundTintList) { Text(label) }
}

@Composable private fun RuleCount(count:WgControlState,node:WgControlState,label:Int,modifier:Modifier) {
    Surface({node.performClick()},modifier,shape=RoundedCornerShape(12.dp),color=themeColor(R.attr.homeScreenBtnBackground)) {
        Column(Modifier.padding(10.dp),horizontalAlignment=Alignment.CenterHorizontally) {
            Text(count.text.toString(), color=themeColor(R.attr.accentGood),
                style=utilityTextStyle(size=utilityDimensionSp(LocalContext.current,R.dimen.extra_large_font_text_view),weight=android.graphics.Typeface.BOLD))
            Text(stringResource(label), Modifier.padding(top=2.dp), color=themeColor(R.attr.primaryLightColorText),
                style=utilityTextStyle(size=utilityDimensionSp(LocalContext.current,R.dimen.default_font_text_view)), textAlign=TextAlign.Center)
        }
    }
}
@Composable private fun SwitchRow(sourceId:String,row:WgControlState,switch:WgControlState,title:Int,icon:Int,desc:String) {
    Row(Modifier.fillMaxWidth().clickable(role=Role.Switch){row.performClick()}.semantics{toggleableState=if(switch.isChecked)androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off}.padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
        Icon(painterResource(icon),null,Modifier.padding(end=12.dp).size(24.dp))
        Column(Modifier.weight(1f)){Text(stringResource(title),fontSize=14.sp);if(desc.isNotEmpty())Text(desc,fontSize=12.sp)}
        OriginalSwitch("activity_app_details:$sourceId",switch.isChecked,onCheckedChange=null)
    }
}
