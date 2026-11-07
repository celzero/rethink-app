/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.rpnconfig

import android.os.Bundle
import androidx.compose.runtime.*
import com.celzero.bravedns.data.SsidItem

/** RPN's original validation, deliberately distinct from WireGuard's SSID editor. */
class RpnSsidState {
    val items = mutableStateListOf<SsidItem>()
    var name by mutableStateOf("")
    var equal by mutableStateOf(true)
    var exact by mutableStateOf(true)
    var scrollTo by mutableIntStateOf(-1)
    var scrollRequest by mutableIntStateOf(0)
    enum class AddResult { EMPTY, DUPLICATE, ADDED }
    fun add(): AddResult {
        val ssidName = name.trim()
        if (ssidName.isEmpty()) return AddResult.EMPTY
        if (items.any { it.name.equals(ssidName, ignoreCase = true) }) return AddResult.DUPLICATE
        val ssidType = when {
            equal && exact -> SsidItem.SsidType.EQUAL_EXACT
            equal && !exact -> SsidItem.SsidType.EQUAL_WILDCARD
            !equal && exact -> SsidItem.SsidType.NOTEQUAL_EXACT
            else -> SsidItem.SsidType.NOTEQUAL_WILDCARD
        }
        items.add(SsidItem(ssidName, ssidType))
        name = ""
        scrollTo = items.size - 1
        scrollRequest++
        return AddResult.ADDED
    }
    fun save(bundle: Bundle) {
        bundle.putString("rpn.ssid.name", name)
        bundle.putBoolean("rpn.ssid.equal", equal)
        bundle.putBoolean("rpn.ssid.exact", exact)
    }
    fun restore(bundle: Bundle?) {
        if (bundle == null) return
        name = bundle.getString("rpn.ssid.name", "").orEmpty()
        equal = bundle.getBoolean("rpn.ssid.equal", true)
        exact = bundle.getBoolean("rpn.ssid.exact", true)
    }
}
