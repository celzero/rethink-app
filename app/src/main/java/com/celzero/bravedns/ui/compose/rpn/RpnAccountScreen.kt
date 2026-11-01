package com.celzero.bravedns.ui.compose.rpn

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.themeColor

@Composable
fun RpnAccountScreen(ui: RpnAccountState) {
    val scroll = rememberScrollState()
    var viewport by remember { mutableIntStateOf(0) }
    val revealed = scroll.maxValue - scroll.value <= viewport * .25f
    val opacity by animateFloatAsState(if (revealed) 1f else 0f, tween(200), label = "account signature reveal")
    Box(Modifier.fillMaxSize().background(themeColor(R.attr.background))) {
        Column(Modifier.fillMaxSize().onSizeChanged { viewport = it.height }.verticalScroll(scroll).padding(top = 12.dp, bottom = 120.dp)) {
            Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).rpnHeroBackground().padding(20.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.rpn_overhauled_current_plan_header), style = MaterialTheme.typography.labelSmall)
                    RpnText(ui.tvStatusText, Modifier.background(themeColor(R.attr.chipBgColorPositive), RoundedCornerShape(20.dp)).padding(horizontal = 10.dp, vertical = 5.dp), MaterialTheme.typography.labelSmall)
                }
                RpnText(ui.tvHeroPlanName, Modifier.padding(top = 16.dp), MaterialTheme.typography.headlineMedium.copy(fontSize = 28.sp, fontWeight = FontWeight.Bold))
                RpnText(ui.tvHeroPurchasedDate, Modifier.padding(top = 14.dp), MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp))
                RpnText(ui.tvHeroIds, Modifier.padding(top = 12.dp), MaterialTheme.typography.bodySmall)
                RpnText(ui.tvHeroWho, Modifier.padding(top = 6.dp).clickable { ui.tvHeroWho.performClick() }, MaterialTheme.typography.bodySmall)
            }
            if (ui.cardGetPlus.isVisible) RpnAccountAction(ui.cardGetPlus, R.drawable.ic_rethink_plus, R.string.rpn_cta_get_plus, R.string.rpn_no_purchase_subtitle)
            RpnAccountAction(ui.cardRunTest, R.drawable.ic_rethink_plus, R.string.rpn_perform_test, R.string.rpn_test_desc)
            if (ui.tvPlanDetailsHeader.isVisible) RpnSectionLabel(R.string.rpn_overhauled_plan_details_header)
            if (ui.cardManagePurchaseDashboard.isVisible) RpnSettingsCard {
                RpnAccountRow(ui.rowManagePurchase, R.drawable.ic_rethink_plus, R.string.manage_purchase_title, R.string.rpn_overhauled_manage_purchase_desc)
                RpnSettingsDivider()
                RpnAccountRow(ui.rowRestoreDefaults, R.drawable.ic_refresh, R.string.rpn_restore_confirm_title, R.string.rpn_restore_dialog_hint)
            }
            RpnSectionLabel(R.string.about_connect)
            RpnSettingsCard { RpnAccountRow(ui.rowReportIssue, R.drawable.ic_info_white, R.string.contact_support_title, R.string.rpn_report_desc) }
        }
        if (revealed || opacity > 0f) RpnSignature(ui.dolphinSignature, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp).alpha(opacity))
    }
}
@Composable private fun RpnAccountAction(state: RpnControlState, icon: Int, title: Int, desc: Int) { RpnSettingsCard { RpnAccountRow(state, icon, title, desc) } }
@Composable private fun RpnAccountRow(state: RpnControlState, icon: Int, title: Int, desc: Int) {
    Row(Modifier.fillMaxWidth().clickable(enabled = state.isEnabled && state.isClickable) { state.performClick() }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, Modifier.size(38.dp).background(themeColor(R.attr.chipBgColorPositive), RoundedCornerShape(24.dp)).padding(8.dp), tint = themeColor(R.attr.accentGood))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(stringResource(title)); Text(stringResource(desc), style = MaterialTheme.typography.bodySmall, color = themeColor(R.attr.primaryLightColorText)) }
        Icon(painterResource(R.drawable.ic_right_arrow_white), null, Modifier.size(17.dp), tint = themeColor(R.attr.primaryLightColorText))
    }
}
