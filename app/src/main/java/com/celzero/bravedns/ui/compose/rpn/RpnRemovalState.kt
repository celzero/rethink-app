package com.celzero.bravedns.ui.compose.rpn

import android.content.Context
import android.view.View
import com.celzero.bravedns.R

class RpnRemovalState(context: Context) {
    val rows = androidx.compose.runtime.mutableStateListOf<Pair<com.celzero.bravedns.database.CountryConfig, RpnControlState>>()
    val btnUnderstand = RpnControlState() .apply { text = context.getString(R.string.ada_noapp_dialog_positive) ; textSizeSp = 15f; bold = true }
    val iconContainer = RpnControlState()
    val notificationCard = RpnControlState()
    val notificationIcon = RpnControlState() .apply { setImageResource(R.drawable.ic_info_white) ; imageTintList = android.content.res.ColorStateList.valueOf(androidx.core.content.ContextCompat.getColor(context, android.R.color.white)) }
    val notificationSubtitle = RpnControlState() .apply { text = context.getString(R.string.server_removal_subtitle); alpha = 0.7f ; setTextColor(com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.primaryLightColorText)); textSizeSp = 13f }
    val notificationTitle = RpnControlState() .apply { text = context.getString(R.string.server_removal_title) ; setTextColor(com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.primaryTextColor)); textSizeSp = 20f; bold = true }
}
