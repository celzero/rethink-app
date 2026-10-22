package com.celzero.bravedns.ui.compose.dnssettings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.ui.compose.settings.SettingsCard

private data class ProtocolItem(val key: String, val card: DnsProtocolCard, val title: Int, val description: Int, val titleState: DnsControl, val descriptionState: DnsControl, val dots: List<Int>)

@Composable fun DnsProtocolGrid(b: DnsProtocolState) {
    val items = listOf(
        ProtocolItem("cardDoh", b.cardDoh, R.string.cd_custom_doh_url_name_default, R.string.dc_doh, b.initialDoh, b.abbrDoh, listOf(R.attr.chipTextNeutral,R.attr.chipTextPositive)),
        ProtocolItem("cardDot", b.cardDot, R.string.lbl_dot, R.string.lbl_dot_abbr, b.initialDot, b.abbrDot, listOf(R.attr.chipTextNeutral,R.attr.chipTextPositive)),
        ProtocolItem("cardDnscrypt", b.cardDnscrypt, R.string.lbl_dc, R.string.lbl_dc_abbr, b.initialDnscrypt, b.abbrDnscrypt, listOf(R.attr.chipTextNeutral,R.attr.chipTextPositive,R.attr.accentGood)),
        ProtocolItem("cardDnsproxy", b.cardDnsproxy, R.string.lbl_dp, R.string.lbl_dp_abbr, b.initialDnsproxy, b.abbrDnsproxy, listOf(R.attr.chipTextNegative)),
        ProtocolItem("cardOdoh", b.cardOdoh, R.string.lbl_odoh, R.string.lbl_odoh_abbr, b.initialOdoh, b.abbrOdoh, listOf(R.attr.chipTextNeutral,R.attr.chipTextPositive,R.attr.accentGood)),
        ProtocolItem("cardRethinkDns", b.cardRethinkDns, R.string.lbl_rdns, R.string.dc_rethink_dns_radio, b.initialRethinkDns, b.abbrRethinkDns, listOf(R.attr.chipTextNegative,R.attr.chipTextNeutral,R.attr.chipTextPositive)),
    )
    val columns = if (LocalConfiguration.current.screenWidthDp >= 480) 3 else 2
    Surface(color = themeColor(R.attr.background)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 16.dp)) {
            Spacer(Modifier.height(10.dp))
            items.chunked(columns).forEach { line ->
                Row(Modifier.fillMaxWidth()) {
                    line.forEach { item ->
                        Box(Modifier.weight(1f).padding(8.dp)) {
                            SettingsCard(Modifier.aspectRatio(1f), contentPadding = 0.dp) {
                                val border = with(LocalDensity.current) { item.card.strokeWidth.toDp() }
                                Box(Modifier.fillMaxSize().then(if (item.card.strokeWidth > 0) Modifier.border(border, Color(item.card.strokeColor), RoundedCornerShape(8.dp)) else Modifier).testTag("dns-protocol-${item.key}").clickable(onClick = item.card.onClick).padding(12.dp), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        BasicText(stringResource(item.title), Modifier.fillMaxWidth().padding(top = 8.dp), style = TextStyle(color = item.titleState.color?.let(::Color) ?: themeColor(R.attr.primaryTextColor), textAlign = TextAlign.Center), maxLines = 1, overflow = TextOverflow.Ellipsis, autoSize = TextAutoSize.StepBased(14.sp, 18.sp, 1.sp))
                                        BasicText(stringResource(item.description), Modifier.fillMaxWidth().padding(top = 4.dp), style = TextStyle(color = item.descriptionState.color?.let(::Color) ?: themeColor(R.attr.primaryLightColorText), textAlign = TextAlign.Center), maxLines = 2, overflow = TextOverflow.Ellipsis, autoSize = TextAutoSize.StepBased(12.sp, 16.sp, 1.sp))
                                        Row(Modifier.padding(top = 8.dp, bottom = 4.dp)) { item.dots.forEach { Dot(it) } }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            FlowRow(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.Center) {
                listOf(R.string.lbl_fast to R.attr.chipTextNegative, R.string.lbl_private to R.attr.chipTextNeutral, R.string.lbl_secure to R.attr.chipTextPositive, R.string.lbl_anonymous to R.attr.accentGood).forEach { (label, color) ->
                    Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) { Dot(color); Text(stringResource(label), color = themeColor(R.attr.primaryTextColor), fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable private fun Dot(attribute: Int) { Box(Modifier.padding(2.dp).size(10.dp).background(themeColor(attribute), CircleShape)) }
