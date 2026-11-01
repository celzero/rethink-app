/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.dnssettings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Observable presentation state, never an Android View. Dispatch mirrors CompoundButton. */
class DnsControl {
    var text by mutableStateOf("")
    var color by mutableStateOf<Int?>(null)
    var isEnabled by mutableStateOf(true)
    var isClickable by mutableStateOf(true)
    var visible by mutableStateOf(true)
    var alpha by mutableStateOf(1f)
    var onClick by mutableStateOf<() -> Unit>({})
    var onCheckedChange: ((DnsControl, Boolean) -> Unit)? = null
    private var checkedValue by mutableStateOf(false)
    private var broadcasting = false
    var isChecked: Boolean
        get() = checkedValue
        set(value) {
            if (checkedValue == value) return
            checkedValue = value
            if (broadcasting) return
            broadcasting = true
            try { onCheckedChange?.invoke(this, value) } finally { broadcasting = false }
        }
}

class DnsSettingsState {
    var refreshing by mutableStateOf(false)
    var restorePrompt by mutableStateOf(false)
    var systemDns by mutableStateOf<String?>(null)
    val connectedStatusTitle = DnsControl()
    val connectedStatusTitleUrl = DnsControl()
    val customDnsRb = DnsControl()
    val dcAlgRl = DnsControl()
    val dcAlgSwitch = DnsControl()
    val dcAllowedRecordTypesDesc = DnsControl()
    val dcAllowedRecordTypesRl = DnsControl()
    val dcBlockFreeDnsDesc = DnsControl()
    val dcBlockFreeDnsRl = DnsControl()
    val dcBlockHeadingRl = DnsControl()
    val dcBlockUnknownSwitch = DnsControl()
    val dcCheckUpdateRl = DnsControl()
    val dcCheckUpdateSwitch = DnsControl()
    val dcDownloaderRl = DnsControl()
    val dcDownloaderSwitch = DnsControl()
    val dcEnableCacheRl = DnsControl()
    val dcEnableCacheSwitch = DnsControl()
    val dcFaviconRl = DnsControl()
    val dcFaviconSwitch = DnsControl()
    val dcLocalBlocklistCount = DnsControl()
    val dcLocalBlocklistDesc = DnsControl()
    val dcLocalBlocklistImg = DnsControl()
    val dcLocalBlocklistRl = DnsControl()
    val dcPreventDnsLeaksRl = DnsControl()
    val dcPreventDnsLeaksSwitch = DnsControl()
    val dcProxyDnsRl = DnsControl()
    val dcProxyDnsSwitch = DnsControl()
    val dcRefresh = DnsControl()
    val dcRestoreDefaults = DnsControl()
    val dcSplitDnsDesc = DnsControl()
    val dcSplitDnsIcon = DnsControl()
    val dcSplitDnsRl = DnsControl()
    val dcSplitDnsSwitch = DnsControl()
    val dcSplitDnsTxt = DnsControl()
    val dcCaptivePortalRl = DnsControl()
    val dcCaptivePortalSwitch = DnsControl()
    val dcUndelegatedDomainsRl = DnsControl()
    val dcUndelegatedDomainsSwitch = DnsControl()
    val dividerLocalBlocklist = DnsControl()
    val dividerSplitDns = DnsControl()
    val networkDnsInfo = DnsControl()
    val networkDnsRb = DnsControl()
    val rethinkPlusDnsRb = DnsControl()
    val smartDnsRb = DnsControl()
    // Stable widgets restore in XML hierarchy order, after listeners are attached.
    val restoreOrder get() = listOf(networkDnsRb, customDnsRb, rethinkPlusDnsRb, smartDnsRb,
        dcDownloaderSwitch, dcCheckUpdateSwitch, dcAlgSwitch, dcSplitDnsSwitch, dcFaviconSwitch,
        dcEnableCacheSwitch, dcProxyDnsSwitch, dcPreventDnsLeaksSwitch, dcBlockUnknownSwitch,
        dcUndelegatedDomainsSwitch, dcCaptivePortalSwitch)
}
