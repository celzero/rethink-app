package com.celzero.bravedns.ui.compose.rpn

import android.content.Context
import android.view.View
import com.celzero.bravedns.R

class RpnServerRowState(context: Context) {
    val flagWatermark = RpnFlagWatermarkState(context)
    val appsAction = RpnControlState() .apply { setTextColor(com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.serverChipTextColor)); textSizeSp = context.resources.getDimension(R.dimen.small_font_text_view) / context.resources.displayMetrics.scaledDensity; maxLines = 1 }
    val appsActionContainer = RpnControlState() .apply { isClickable = true; isFocusable = true }
    val ivStatusCheck = RpnControlState() .apply { alpha = 0.75f; visibility = View.GONE; setImageResource(R.drawable.ic_check_circle) ; imageTintList = android.content.res.ColorStateList.valueOf(com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.chipBgColorPositive)) }
    val lastRoutedAppAction = RpnControlState() .apply { visibility = View.GONE ; setTextColor(com.celzero.bravedns.util.UIUtils.fetchColor(context, androidx.appcompat.R.attr.colorPrimary)); textSizeSp = 10f; maxLines = 1 }
    val lastRoutedAppContainer = RpnControlState() .apply { isClickable = true; isFocusable = true }
    val lastRoutedAppFallbackIcon = RpnControlState() .apply { visibility = View.GONE; setImageResource(R.drawable.ic_timer) ; imageTintList = android.content.res.ColorStateList.valueOf(com.celzero.bravedns.util.UIUtils.fetchColor(context, androidx.appcompat.R.attr.colorPrimary)) }
    val lastRoutedAppIconStack = RpnControlState()
    val refreshStopIcon = RpnControlState() .apply { contentDescription = context.getString(R.string.lbl_remove); setImageResource(R.drawable.ic_cross) ; imageTintList = android.content.res.ColorStateList.valueOf(com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.primaryLightColorText)) }
    val relayAction = RpnControlState() .apply { setTextColor(com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.serverChipTextColor)); textSizeSp = context.resources.getDimension(R.dimen.small_font_text_view) / context.resources.displayMetrics.scaledDensity; maxLines = 1 }
    val relayActionContainer = RpnControlState() .apply { isClickable = true; isFocusable = true }
    val relayIcon = RpnControlState() .apply { visibility = View.GONE; setImageResource(R.drawable.ic_check_circle) ; imageTintList = android.content.res.ColorStateList.valueOf(com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.serverChipTextColor)) }
    val routedAppIcon0 = RpnControlState()
    val routedAppIcon1 = RpnControlState().apply { visibility = View.GONE }
    val routedAppIcon2 = RpnControlState().apply { visibility = View.GONE }
    val serverCard = RpnControlState() .apply { isClickable = true; isFocusable = true }
    val tvCountryName = RpnControlState() .apply { setTextColor(com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.primaryTextColor)); textSizeSp = 16f; maxLines = 1 }
    val tvServerIp = RpnControlState() .apply { visibility = View.GONE ; setTextColor(com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.primaryLightColorText)); textSizeSp = context.resources.getDimension(R.dimen.small_font_text_view) / context.resources.displayMetrics.scaledDensity; maxLines = 1; monospace = true }
    val tvServerStatus = RpnControlState() .apply { setTextColor(com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.primaryLightColorText)); textSizeSp = context.resources.getDimension(R.dimen.small_font_text_view) / context.resources.displayMetrics.scaledDensity; maxLines = 1 }
}
