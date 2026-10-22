/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.utilities
import android.content.Context
import androidx.compose.runtime.*
import com.celzero.bravedns.R

class SponsorUiStateModel(context: Context) {
    val sponsorScrollView = UtilityControl(context, "fragment_sponsor.sponsor_scroll_view").apply {  }
    val heroEmblem = UtilityControl(context, "fragment_sponsor.hero_emblem").apply { imageRes = R.drawable.ic_heart; colorFilter = com.celzero.bravedns.util.UIUtils.fetchColor(context, com.google.android.material.R.attr.colorOnPrimaryContainer) }
    val heroTitleDesc = UtilityControl(context, "fragment_sponsor.hero_title_desc").apply { text = context.getText(R.string.sponsor_community_sponsor_desc); textColor = com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant); textAppearance = com.google.android.material.R.style.TextAppearance_Material3_BodySmall; centered = true }
    val alreadySponsorBanner = UtilityControl(context, "fragment_sponsor.already_sponsor_banner").apply { text = context.getText(R.string.sponsor_already_sponsor); visibility = android.view.View.GONE; textColor = com.celzero.bravedns.util.UIUtils.fetchColor(context, androidx.appcompat.R.attr.colorPrimary); textAppearance = com.google.android.material.R.style.TextAppearance_Material3_BodyMedium; centered = true }
    val sponsorPlayUnavailableText = UtilityControl(context, "fragment_sponsor.sponsor_play_unavailable_text").apply { text = context.getText(R.string.sponsor_play_unavailable_msg); visibility = android.view.View.GONE; textColor = com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.colorOnSurfaceVariant); textAppearance = com.google.android.material.R.style.TextAppearance_Material3_BodyMedium; centered = true }
    val contributionCard = UtilityControl(context, "fragment_sponsor.contribution_card").apply {  }
    val contributionPrice = UtilityControl(context, "fragment_sponsor.contribution_price").apply { textColor = com.celzero.bravedns.util.UIUtils.fetchColor(context, androidx.appcompat.R.attr.colorPrimary); fontStyle = 1; textAppearance = com.google.android.material.R.style.TextAppearance_Material3_DisplayMedium; centered = true }
    val contributionSeekbar = UtilityControl(context, "fragment_sponsor.contribution_seekbar").apply { value = 1f }
    val chipGroup = UtilityControl(context, "fragment_sponsor.chip_group").apply {  }
    val chip5 = UtilityControl(context, "fragment_sponsor.chip_5").apply { text = "\$5" }
    val chip10 = UtilityControl(context, "fragment_sponsor.chip_10").apply { text = "\$10" }
    val chip25 = UtilityControl(context, "fragment_sponsor.chip_25").apply { text = "\$25" }
    val chip50 = UtilityControl(context, "fragment_sponsor.chip_50").apply { text = "\$50" }
    val bottomActionBar = UtilityControl(context, "fragment_sponsor.bottom_action_bar").apply {  }
    val supportButton = UtilityControl(context, "fragment_sponsor.support_button").apply { text = context.getText(R.string.sponsor_support_button); textAppearance = com.google.android.material.R.style.TextAppearance_Material3_TitleMedium }
    val errorText = UtilityControl(context, "fragment_sponsor.error_text").apply { visibility = android.view.View.GONE; textColor = com.celzero.bravedns.util.UIUtils.fetchColor(context, androidx.appcompat.R.attr.colorError); textAppearance = com.google.android.material.R.style.TextAppearance_Material3_BodySmall; centered = true }
    val payWithStripeLink = UtilityControl(context, "fragment_sponsor.pay_with_stripe_link").apply { text = context.getText(R.string.sponsor_pay_via_stripe); textColor = com.celzero.bravedns.util.UIUtils.fetchColor(context, androidx.appcompat.R.attr.colorPrimary); fontStyle = 1; textAppearance = com.google.android.material.R.style.TextAppearance_Material3_LabelLarge }
    fun selectChip(control: UtilityControl) { val value = !control.isChecked; chipGroup.children.forEach { it.isChecked = it === control && value }; control.performClick() }
    init { chipGroup.children.addAll(listOf(chip5, chip10, chip25, chip50)) }
    val savedControls get() = listOf<UtilityControl>(chip5, chip10, chip25, chip50)
}
