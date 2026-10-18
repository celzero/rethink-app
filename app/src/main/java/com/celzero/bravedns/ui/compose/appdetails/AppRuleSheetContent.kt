package com.celzero.bravedns.ui.compose.appdetails
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.wireguard.*
class DomainRuleRowState {
    val domainText = WgControlState()
    val trustIcon = WgControlState()
    val blockIcon = WgControlState()
}
@Composable fun DomainRuleRowContent(b: DomainRuleRowState) = RuleToggleRow(b.domainText,b.trustIcon,b.blockIcon)
@Composable fun AppRuleSheetContent(b: AppRuleSheetState) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom=60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalDivider(Modifier.padding(vertical=10.dp).width(60.dp), thickness=3.dp)
        Row(Modifier.padding(20.dp), verticalAlignment=Alignment.CenterVertically) {
            if(b.bsacAppIcon.isVisible) DrawableImage(b.bsacAppIcon.drawable,Modifier.size(20.dp))
            if(b.bsacAppName.isVisible) Text(b.bsacAppName.text.toString(),Modifier.padding(start=10.dp),fontSize=12.sp)
        }
        if(b.bsacIpRuleTxt.isVisible) Text(wgAnnotatedText(b.bsacIpRuleTxt.text),Modifier.padding(10.dp),fontSize=16.sp)
        RuleToggleRow(b.bsacIpAddressTv,b.trustIcon,b.blockIcon)
        // choose_proxy_card was GONE in the shared baseline layout and never enabled.
        if(b.bsacDomainLl.isVisible) {
            if(b.bsacDomainRuleTxt.isVisible) Text(wgAnnotatedText(b.bsacDomainRuleTxt.text),Modifier.padding(10.dp),fontSize=16.sp)
            b.bsacDomainList.adapter?.Content()
        }
        Text(stringResource(R.string.bsac_title_desc),Modifier.padding(10.dp),fontSize=12.sp)
    }
}
@Composable private fun RuleToggleRow(label:WgControlState,trust:WgControlState,block:WgControlState) {
    Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=5.dp),verticalAlignment=Alignment.CenterVertically) {
        SelectionContainer(Modifier.weight(1f)) { Text(label.text.toString(),fontSize=14.sp) }
        WgIcon(trust,stringResource(R.string.ci_trust_rule))
        WgIcon(block,stringResource(R.string.ci_block))
    }
}
