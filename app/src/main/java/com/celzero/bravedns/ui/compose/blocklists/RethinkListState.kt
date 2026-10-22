package com.celzero.bravedns.ui.compose.blocklists
import androidx.compose.runtime.*
import com.celzero.bravedns.ui.compose.dnssettings.DnsControl
class RethinkChipState {
    var text by mutableStateOf("")
    var visible by mutableStateOf(true)
    var isEnabled by mutableStateOf(true)
    var isChipIconVisible by mutableStateOf(false)
    var onClick by mutableStateOf<() -> Unit>({})
}
class RethinkListState {
    val bslbCheckUpdateBtn = RethinkChipState()
    val bslbRedownloadBtn = RethinkChipState()
    val bslbUpdateAvailableBtn = RethinkChipState()
    val dohFabAddServerIcon = DnsControl().apply { visible = false }
    val frlDesc = DnsControl()
    val lbVersion = DnsControl()
    val radioMax = DnsControl()
    val radioSky = DnsControl()
}
