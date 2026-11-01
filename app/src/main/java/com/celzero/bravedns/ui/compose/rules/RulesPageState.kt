/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.rules

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import com.celzero.bravedns.database.CustomDomain
import com.celzero.bravedns.database.CustomIp
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.ui.fragment.RulesImportHelper
import java.io.Serializable

/** Only the baseline search field is saveable. Ordinary dialog/selection state is transient. */
internal class RulesPageState {
    var query by mutableStateOf("")
    internal var lastQueryText: String? = null
    var editorOpen by mutableStateOf(false)
    var editing by mutableStateOf<Serializable?>(null)
    var input by mutableStateOf("")
    var domainType by mutableStateOf(DomainRulesManager.DomainType.DOMAIN)
    var error by mutableStateOf<Int?>(null)
    var selectionMode by mutableStateOf(false)
    var selected by mutableStateOf<List<Serializable>>(emptyList())
    var deleteOpen by mutableStateOf(false)
    var importUri by mutableStateOf<String?>(null)
    var parsedImport by mutableStateOf<RulesImportHelper.ParsedFile?>(null)
    var importBlock by mutableStateOf(true)
    var importSummary by mutableStateOf<String?>(null)

    fun openEditor(item: Serializable? = null) {
        editing = item
        input = when (item) {
            is CustomIp -> if (item.port != 0) IpRulesManager.joinIpNetPort(item.ipAddress, item.port) else item.ipAddress
            is CustomDomain -> item.domain
            else -> ""
        }
        domainType = if (item is CustomDomain) DomainRulesManager.DomainType.getType(item.type) else DomainRulesManager.DomainType.DOMAIN
        error = null
        editorOpen = true
    }
    fun longSelect(item: Serializable) {
        selectionMode = true
        if (!selected.contains(item)) selected = selected + item
    }
    fun toggleSelection(item: Serializable) {
        selected = if (selected.contains(item)) selected.filterNot { it == item } else selected + item
    }
    fun clearSelection() { selected = emptyList(); selectionMode = false }
    fun dismissImport() { importUri = null; parsedImport = null }
    companion object {
        val Saver = Saver<RulesPageState, String>(save = { it.query }, restore = { RulesPageState().apply { query = it } })
    }
}
internal fun showRuleHeader(all: Boolean, index: Int, previousUid: Int?, uid: Int) = all && (index == 0 || previousUid != uid)
internal fun ruleUid(item: Serializable): Int = when (item) {
    is CustomIp -> item.uid
    is CustomDomain -> item.uid
    else -> error("Unknown rule")
}
internal fun ruleKey(item: Serializable): String = when (item) {
    is CustomIp -> "ip:${item.uid}:${item.ipAddress}:${item.port}:${item.protocol}"
    is CustomDomain -> "domain:${item.uid}:${item.domain}"
    else -> error("Unknown rule")
}
internal fun updateRulesSearch(state: RulesPageState, value: String, onQuery: (String) -> Unit) {
    state.query = value
    if (state.lastQueryText != value) onQuery(value)
    state.lastQueryText = value
}
internal fun resumeRulesSearch(state: RulesPageState, onQuery: (String) -> Unit, clearFocus: () -> Unit) {
    updateRulesSearch(state, "", onQuery)
    clearFocus()
}
internal fun submitRulesSearch(state: RulesPageState, onQuery: (String) -> Unit) {
    if (state.query.trim { it <= ' ' }.isNotEmpty()) onQuery(state.query)
}
