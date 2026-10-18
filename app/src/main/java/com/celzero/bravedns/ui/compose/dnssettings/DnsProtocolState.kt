package com.celzero.bravedns.ui.compose.dnssettings
import androidx.compose.runtime.*
class DnsProtocolCard {
    var strokeWidth by mutableStateOf(0)
    var strokeColor by mutableStateOf(0)
    var onClick by mutableStateOf<() -> Unit>({})
}
class DnsProtocolState {
    val abbrDnscrypt = DnsControl()
    val abbrDnsproxy = DnsControl()
    val abbrDoh = DnsControl()
    val abbrDot = DnsControl()
    val abbrOdoh = DnsControl()
    val abbrRethinkDns = DnsControl()
    val cardDnscrypt = DnsProtocolCard()
    val cardDnsproxy = DnsProtocolCard()
    val cardDoh = DnsProtocolCard()
    val cardDot = DnsProtocolCard()
    val cardOdoh = DnsProtocolCard()
    val cardRethinkDns = DnsProtocolCard()
    val initialDnscrypt = DnsControl()
    val initialDnsproxy = DnsControl()
    val initialDoh = DnsControl()
    val initialDot = DnsControl()
    val initialOdoh = DnsControl()
    val initialRethinkDns = DnsControl()
}
