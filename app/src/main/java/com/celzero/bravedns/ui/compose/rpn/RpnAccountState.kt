package com.celzero.bravedns.ui.compose.rpn

import android.content.Context
import android.view.View
import com.celzero.bravedns.R

class RpnAccountState(context: Context) {
    val cardGetPlus = RpnControlState() .apply { visibility = View.GONE }
    val cardManagePurchaseDashboard = RpnControlState()
    val cardRunTest = RpnControlState()
    val dolphinSignature = RpnSignatureState()
    val rowManagePurchase = RpnControlState()
    val rowReportIssue = RpnControlState()
    val rowRestoreDefaults = RpnControlState()
    val tvHeroIds = RpnControlState() .apply { alpha = 0.7f ; setTextColor(com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.primaryLightColorText)); textSizeSp = context.resources.getDimension(R.dimen.mini_font_text_view) / context.resources.displayMetrics.scaledDensity; maxLines = 1; monospace = true }
    val tvHeroPlanName = RpnControlState() .apply { textSizeSp = 28f }
    val tvHeroPurchasedDate = RpnControlState() .apply { setTextColor(com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.primaryTextColor)); textSizeSp = 13f }
    val tvHeroWho = RpnControlState() .apply { alpha = 0.7f; visibility = View.GONE ; setTextColor(com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.primaryLightColorText)); textSizeSp = context.resources.getDimension(R.dimen.mini_font_text_view) / context.resources.displayMetrics.scaledDensity; maxLines = 1; monospace = true }
    val tvPlanDetailsHeader = RpnControlState() .apply { text = context.getString(R.string.rpn_overhauled_plan_details_header) }
    val tvStatusText = RpnControlState() .apply { text = context.getString(R.string.lbl_active) }
}
