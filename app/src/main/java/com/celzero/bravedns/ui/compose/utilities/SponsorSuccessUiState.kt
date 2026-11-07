/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.utilities
import android.content.Context
import androidx.compose.runtime.*
import com.celzero.bravedns.R

class SponsorSuccessUiState(context: Context) {
    val successEmblem = UtilityControl(context, "fragment_sponsor_success.success_emblem").apply { imageRes = R.drawable.dns_icon; colorFilter = com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.accentGood) }
    val successTitle = UtilityControl(context, "fragment_sponsor_success.success_title").apply { text = context.getText(R.string.sponsor_thank_you); textColor = com.celzero.bravedns.util.UIUtils.fetchColor(context, androidx.appcompat.R.attr.colorPrimary); fontStyle = 1; textAppearance = com.google.android.material.R.style.TextAppearance_Material3_HeadlineLarge }
    val successMessage = UtilityControl(context, "fragment_sponsor_success.success_message").apply { text = context.getText(R.string.sponsor_support_message); textColor = com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant); textAppearance = com.google.android.material.R.style.TextAppearance_Material3_BodyLarge; centered = true }
    val continueButton = UtilityControl(context, "fragment_sponsor_success.continue_button").apply { text = context.getText(R.string.sponsor_continue); textAppearance = com.google.android.material.R.style.TextAppearance_Material3_TitleMedium }
    val savedControls get() = listOf<UtilityControl>()
}
