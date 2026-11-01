/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.utilities
import android.content.Context
import androidx.compose.runtime.*
import com.celzero.bravedns.R

class PauseUiState(context: Context) {
    val pacHeader = UtilityControl(context, "activity_pause.pac_header").apply { visibility = android.view.View.VISIBLE }
    val fhsTitleRethink = UtilityControl(context, "activity_pause.fhs_title_rethink").apply { text = context.getText(R.string.app_name_small_case); visibility = android.view.View.VISIBLE; textColor = com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.primaryTextColor); textAppearance = R.style.TextAppearance_Heading_HomeScreen; centered = true; allCaps = true }
    val fhsTitleRethinkDesc = UtilityControl(context, "activity_pause.fhs_title_rethink_desc").apply { text = context.getText(R.string.pause_title_desc); visibility = android.view.View.VISIBLE; textColor = com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.primaryTextColor); fontSize = utilityDimensionSp(context, R.dimen.small_font_subheading_text); centered = true }
    val pacTimerText = UtilityControl(context, "activity_pause.pac_timer_text").apply { text = context.getText(R.string.pause_text); textColor = com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.primaryTextColor); fontSize = 40f; fontFamily = "sans-serif-smallcaps"; fontStyle = 1; textAppearance = androidx.appcompat.R.style.TextAppearance_AppCompat_Subhead; centered = true }
    val pacTimer = UtilityControl(context, "activity_pause.pac_timer").apply { text = "10:00"; textColor = com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.secondaryTextColor); fontSize = 75f; textAppearance = androidx.appcompat.R.style.TextAppearance_AppCompat_Subhead; centered = true }
    val pacMinusIv = UtilityControl(context, "activity_pause.pac_minus_iv").apply { imageRes = R.drawable.ic_minus }
    val pacStopIv = UtilityControl(context, "activity_pause.pac_stop_iv").apply { imageRes = R.drawable.ic_stop }
    val pacPlusIv = UtilityControl(context, "activity_pause.pac_plus_iv").apply { imageRes = R.drawable.ic_plus }
    val pacTimerDesc = UtilityControl(context, "activity_pause.pac_timer_desc").apply { text = context.getText(R.string.pause_desc); fontSize = utilityDimensionSp(context, R.dimen.small_font_subheading_text); centered = true }
    val savedControls get() = listOf<UtilityControl>()
}
