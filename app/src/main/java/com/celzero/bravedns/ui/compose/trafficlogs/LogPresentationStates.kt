/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.trafficlogs
import android.content.Context
import android.view.View
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import com.celzero.bravedns.R

class ConnectionRowState(context: Context) {
    val connLatencyTxt = LogControlState()
    val connectionAppIcon = LogControlState()
    val connectionAppName = LogControlState()
    val connectionDataUsage = LogControlState()
    val connectionDelay = LogControlState()
    val connectionDomain = LogControlState()
    val connectionDuration = LogControlState()
    val connectionFlag = LogControlState()
    val connectionIpAddress = LogControlState()
    val connectionParentLayout = LogControlState()
    val connectionResponseTime = LogControlState()
    val connectionStatusIndicator = LogControlState()
    val connectionSummaryLl = LogControlState()
    val root = LogControlState()
    init { connectionAppIcon.drawable = ContextCompat.getDrawable(context, R.drawable.default_app_icon) }
}

class DnsRowState(context: Context) {
    val dnsAppIcon = LogControlState()
    val dnsAppName = LogControlState()
    val dnsFavIcon = LogControlState()
    val dnsFlag = LogControlState()
    val dnsIps = LogControlState()
    val dnsLatency = LogControlState()
    val dnsParentLayout = LogControlState()
    val dnsQuery = LogControlState()
    val dnsQueryType = LogControlState()
    val dnsStatusIndicator = LogControlState()
    val dnsSummaryLl = LogControlState()
    val dnsTypeName = LogControlState()
    val dnsUnicodeHint = LogControlState()
    val dnsWallTime = LogControlState()
    val root = LogControlState()
    init { dnsFavIcon.visibility = View.GONE }
}

class ConnectionDetailsState(context: Context) {
    val bsConnAppRuleHeading = LogControlState()
    val bsConnAppRuleState = LogControlState()
    val bsConnBlockedRule2HeaderLl = LogControlState()
    val bsConnConnDownload = LogControlState()
    val bsConnConnDuration = LogControlState()
    val bsConnConnType = LogControlState()
    val bsConnConnTypeSecondary = LogControlState()
    val bsConnConnUpload = LogControlState()
    val bsConnConnectionFlag = LogControlState()
    val bsConnConnectionTypeHeading = LogControlState()
    val bsConnDnsCacheText = LogControlState()
    val bsConnDomainRuleAddress = LogControlState()
    val bsConnDomainRuleState = LogControlState()
    val bsConnDomainRuleSwitch = LogControlState()
    val bsConnIpRuleAddress = LogControlState()
    val bsConnIpRuleState = LogControlState()
    val bsConnIpRuleSwitch = LogControlState()
    val bsConnRuleRowApp = LogControlState()
    val bsConnRuleRowDomain = LogControlState()
    val bsConnRuleRowIp = LogControlState()
    val bsConnSummaryDetailLl = LogControlState()
    val bsConnTrackAppIcon = LogControlState()
    val bsConnTrackAppInfo = LogControlState()
    val bsConnTrackAppInfoIcon = LogControlState()
    val bsConnTrackAppInfoTxt = LogControlState()
    val bsConnTrackAppName = LogControlState()
    val bsConnTrackAppNameHeader = LogControlState()
    val bsConnTrackPortDetailChip = LogControlState()
    val bsConnUnknownAppCheck = LogControlState(checkable = true)
    val connectionMessage = LogControlState()
    val root = LogControlState()
    var infoDialog by mutableStateOf<LogInfoDialogState?>(null)
    init {
        bsConnRuleRowDomain.visibility = View.GONE; bsConnBlockedRule2HeaderLl.visibility = View.GONE; bsConnConnTypeSecondary.visibility = View.GONE; bsConnDnsCacheText.visibility = View.GONE
        bsConnTrackAppIcon.drawable = ContextCompat.getDrawable(context, R.drawable.default_app_icon)
        bsConnAppRuleHeading.text = context.getString(R.string.lbl_app_rules)
        listOf(bsConnAppRuleState, bsConnIpRuleState, bsConnDomainRuleState).forEach { it.text = context.getString(R.string.bsdl_rule_status_none) }
    }
}

class DnsDetailsState(context: Context) {
    val bsdlAppRuleState = LogControlState()
    val bsdlAppRuleSwitch = LogControlState()
    val bsdlGlobalRuleState = LogControlState()
    val bsdlGlobalRuleSwitch = LogControlState()
    val bsdlRuleAppTitle = LogControlState()
    val bsdlRuleRowApp = LogControlState()
    val bsdlRuleRowGlobal = LogControlState()
    val bsdlRulesDomainLabel = LogControlState()
    val dnsAppIcon = LogControlState()
    val dnsAppName = LogControlState()
    val dnsAppNameHeader = LogControlState()
    val dnsBlockBlockedDesc = LogControlState()
    val dnsBlockBlocklistInfo = LogControlState()
    val dnsBlockBlocklistInfoArrow = LogControlState()
    val dnsBlockBlocklistInfoIcon = LogControlState()
    val dnsBlockBlocklistInfoTxt = LogControlState()
    val dnsBlockConnectionFlag = LogControlState()
    val dnsBlockFavIcon = LogControlState()
    val dnsBlockHeaderContainer = LogControlState()
    val dnsBlockHeaderFlag = LogControlState()
    val dnsBlockInfoDetails = LogControlState()
    val dnsBlockInfoHeader = LogControlState()
    val dnsBlockIpAddress = LogControlState()
    val dnsBlockIpLatency = LogControlState()
    val dnsBlockUrl = LogControlState()
    val dnsBlockedTarget = LogControlState()
    val dnsBlockedTargetHeader = LogControlState()
    val dnsMessage = LogControlState()
    val dnsRecordTypeChip = LogControlState()
    val dnsRegion = LogControlState()
    val dnsRegionHeader = LogControlState()
    val root = LogControlState()
    var infoDialog by mutableStateOf<LogInfoDialogState?>(null)
    init {
        bsdlRuleRowApp.visibility = View.GONE; dnsBlockBlocklistInfo.visibility = View.GONE; dnsBlockFavIcon.visibility = View.GONE; dnsBlockHeaderFlag.visibility = View.GONE; dnsBlockInfoHeader.visibility = View.GONE
        dnsAppIcon.drawable = ContextCompat.getDrawable(context, R.drawable.default_app_icon)
        bsdlRuleAppTitle.text = context.getString(R.string.bsdl_rule_scope_app_title)
        listOf(bsdlAppRuleState, bsdlGlobalRuleState).forEach { it.text = context.getString(R.string.bsdl_rule_status_none) }
    }
}

