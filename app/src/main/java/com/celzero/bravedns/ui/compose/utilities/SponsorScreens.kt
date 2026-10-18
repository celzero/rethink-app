/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.utilities
import com.celzero.bravedns.ui.compose.controls.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.themeColor

@OptIn(ExperimentalLayoutApi::class)
@Composable fun SponsorScreen(ui: SponsorUiStateModel) {
    Surface(Modifier.fillMaxSize(), color = themeColor(R.attr.background)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), border = BorderStroke(1.dp, themeColor(com.google.android.material.R.attr.colorOutlineVariant)), colors = CardDefaults.cardColors(containerColor = themeColor(androidx.cardview.R.attr.cardBackgroundColor))) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(Modifier.size(60.dp), shape = CircleShape, color = themeColor(com.google.android.material.R.attr.colorPrimaryContainer)) {
                            Box(contentAlignment = Alignment.Center) { UtilityImage(ui.heroEmblem, Modifier.size(44.dp)) }
                        }
                        UtilityText(ui.heroTitleDesc, Modifier.padding(top = 16.dp))
                    }
                }
                UtilityText(ui.alreadySponsorBanner, Modifier.padding(vertical = 12.dp))
                UtilityText(ui.sponsorPlayUnavailableText, Modifier.padding(vertical = 12.dp))
                if (ui.contributionCard.isVisible) Card(Modifier.fillMaxWidth().padding(top = 10.dp), shape = RoundedCornerShape(28.dp), border = BorderStroke(1.dp, themeColor(com.google.android.material.R.attr.colorOutlineVariant))) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.sponsor_contribution_title), style = utilityTextStyle(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge, weight = android.graphics.Typeface.BOLD), color = themeColor(R.attr.primaryTextColor))
                        Text(stringResource(R.string.sponsor_contribution_subtitle), style = utilityTextStyle(com.google.android.material.R.style.TextAppearance_Material3_BodySmall), color = themeColor(R.attr.colorOnSurfaceVariant))
                        UtilityText(ui.contributionPrice, Modifier.padding(top = 16.dp))
                        var sliding by remember { mutableStateOf(false) }
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            OriginalSlider("fragment_sponsor:contribution_seekbar", ui.contributionSeekbar.value, { sliding = true; ui.contributionSeekbar.userValue(it) }, onValueChangeFinished = { sliding = false }, valueRange = 0f..6f, steps = 5,
                                modifier = Modifier.fillMaxWidth().testTag(ui.contributionSeekbar.tag).semantics { stateDescription = ui.contributionSeekbar.label(ui.contributionSeekbar.value) })
                            if (sliding) OriginalSliderValueLabel("fragment_sponsor:contribution_seekbar",
                                ui.contributionSeekbar.label(ui.contributionSeekbar.value), ui.contributionSeekbar.value / 6f,
                                Modifier.matchParentSize())
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("$1", style = utilityTextStyle(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall))
                            Text("$100", style = utilityTextStyle(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall))
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(ui.chip5, ui.chip10, ui.chip25, ui.chip50).forEach { control ->
                                OriginalChip(control.tag.replace('.', ':'), control.isChecked, { ui.selectChip(control) }, label = { Text(control.text.toString()) }, modifier = Modifier.testTag(control.tag))
                            }
                        }
                    }
                }
            }
            Surface(color = themeColor(com.google.android.material.R.attr.colorSurface), shadowElevation = 8.dp) {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (ui.supportButton.isVisible) OriginalButton("fragment_sponsor:support_button", { ui.supportButton.performClick() }, enabled = ui.supportButton.isEnabled, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag(ui.supportButton.tag)) {
                        Text(ui.supportButton.text.toString())
                    }
                    UtilityText(ui.errorText, Modifier.padding(top = 8.dp))
                    UtilityButton(ui.payWithStripeLink, Modifier.padding(top = 12.dp), source = "fragment_sponsor:pay_with_stripe_link")
                }
            }
        }
    }
}

@Composable fun SponsorSuccessScreen(ui: SponsorSuccessUiState) {
    Surface(Modifier.fillMaxSize(), color = themeColor(R.attr.background)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(Modifier.fillMaxWidth().heightIn(min = maxHeight).verticalScroll(rememberScrollState()).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Surface(Modifier.size(140.dp), shape = CircleShape, color = themeColor(R.attr.colorSurfaceContainerHigh)) {
                    Box(contentAlignment = Alignment.Center) { UtilityImage(ui.successEmblem, Modifier.size(80.dp)) }
                }
                UtilityText(ui.successTitle, Modifier.padding(top = 40.dp).semantics { heading() })
                Text(ui.successMessage.text.toString(), Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), textAlign = TextAlign.Center,
                    color = themeColor(R.attr.colorOnSurfaceVariant), style = utilityTextStyle(ui.successMessage.textAppearance, lineExtraDp = 6f))
                OriginalButton("fragment_sponsor_success:continue_button", { ui.continueButton.performClick() }, Modifier.fillMaxWidth().padding(top = 56.dp).heightIn(min = 64.dp).testTag(ui.continueButton.tag)) {
                    Text(ui.continueButton.text.toString(), style = utilityTextStyle(ui.continueButton.textAppearance))
                }
            }
        }
    }
}
