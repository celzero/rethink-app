/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.celzero.bravedns.ui.compose.about

import com.celzero.bravedns.ui.compose.controls.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.chrome.homeNavigationClearance
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.ui.compose.utilities.utilityDimensionSp
import com.celzero.bravedns.ui.compose.utilities.utilityTextStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import com.celzero.bravedns.ui.compose.wireguard.WgCard
import com.celzero.bravedns.ui.compose.wireguard.WgControlState
import com.celzero.bravedns.ui.compose.wireguard.resolveWgText

@Composable
fun AboutContent(b: AboutState) {
    Surface(color = themeColor(R.attr.background)) {
        // Home consumes its reserved insets; standalone hosts still need every safe edge.
        LazyColumn(Modifier.fillMaxSize()
            .safeDrawingPadding()
            .testTag("about"), contentPadding = PaddingValues(bottom = homeNavigationClearance(60.dp))) {
            item { AboutHeader(b) }
            item { AboutIntroduction(b) }
            item { AboutQuickActions(b) }
            item { AboutSection(R.string.about_app, groupTop = 10.dp) }
            item { AboutRow(b.aboutAppUpdate) }
            item { AboutRow(b.aboutAppContributors) }
            item { AboutRow(b.aboutAppTranslate) }
            item { Spacer(Modifier.height(15.dp)) }
            item { AboutSection(R.string.about_web) }
            item { AboutRow(b.aboutWebsite) }
            item { AboutRow(b.aboutGithub) }
            item { AboutRow(b.aboutFaq) }
            item { AboutRow(b.aboutBlog) }
            item { AboutRow(b.aboutPrivacyPolicy) }
            item { AboutRow(b.aboutTermsOfService) }
            item { Spacer(Modifier.height(16.dp)) }
            item { AboutSection(R.string.about_connect) }
            item { AboutRow(b.aboutTwitter) }
            item { AboutRow(b.aboutMail) }
            item { AboutRow(b.aboutReddit) }
            item { AboutRow(b.aboutElement) }
            item { AboutRow(b.aboutMastodon) }
            item { Spacer(Modifier.height(16.dp)) }
            item { AboutSection(R.string.about_settings) }
            item { AboutRow(b.aboutAppInfo) }
            item { AboutRow(b.aboutVpnProfile) }
            item { AboutRow(b.aboutAppNotification) }
            item { Spacer(Modifier.height(16.dp)) }
            item { AboutSection(R.string.title_statistics) }
            item { AboutRow(b.aboutStats) }
            item { AboutRow(b.aboutProc) }
            item { AboutRow(b.aboutStackTrace, startMargin = 21.dp, iconGap = 22.dp) }
            item { AboutRow(b.aboutMemoryProfile, iconGap = 22.dp) }
            item { AboutRow(b.aboutDbStats) }
            item { AboutRow(b.aboutConsoleLogs) }
            item { AboutRow(b.aboutEventLogs) }
            item { Spacer(Modifier.height(16.dp)) }
            item { AboutFooter(b) }
        }
    }
}

/** The original TextViews own their typography; do not inherit Compose bodyLarge defaults. */
@Composable
internal fun AboutHeader(b: AboutState) {
    Column(Modifier.padding(15.dp)) {
        Text(if (b.fhsTitleRethink.isAllCaps) b.fhsTitleRethink.text.toString().uppercase() else b.fhsTitleRethink.text.toString(),
            Modifier.fillMaxWidth().alpha(.5f).clickable { b.fhsTitleRethink.performClick() }
                .padding(horizontal = 7.dp).testTag("about.title"),
            style = utilityTextStyle(appearance = R.style.TextAppearance_Heading_HomeScreen),
            color = themeColor(R.attr.colorOnSurface))
        Text(stringResource(R.string.about_title_desc), Modifier.alpha(.5f).padding(horizontal = 10.dp),
            style = utilityTextStyle(appearance = R.style.TextAppearance_SubHeading_HomeScreen),
            color = themeColor(R.attr.colorOnSurface))
    }
}

@Composable
internal fun AboutIntroduction(b: AboutState) {
    WgCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), contentPadding = PaddingValues(0.dp)) {
        // Preserve fragment_about's inner wrapper, paragraph margins and 5dp line spacing.
        Column(Modifier.padding(horizontal = 6.dp, vertical = 5.dp)) {
            AboutDescription(stringResource(R.string.about_bravedns_explantion),
                Modifier.padding(top = 20.dp, bottom = 20.dp), "about.description.intro")
            AboutDescription(stringResource(R.string.about_bravedns_whoarewe),
                Modifier.padding(bottom = 20.dp), "about.description.authors")
            if (b.sponsorInfoUsage.isVisible) AboutDescription(b.sponsorInfoUsage.text.toString(),
                Modifier.padding(bottom = 10.dp), "about.description.usage")
        }
        AboutFramedAction(b.aboutSponsor, "about.sponsor",
            Modifier.padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 16.dp),
            startPadding = 18.dp, tint = themeColor(R.attr.secondaryTextColor), iconTint = Color.Unspecified)
        AboutFramedAction(b.aboutManageRpn, "about.manage-rpn",
            Modifier.padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 16.dp),
            startPadding = 18.dp, tint = themeColor(R.attr.secondaryTextColor), iconTint = Color.Unspecified)
        if (b.aboutCommunitySponsor.isVisible) Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(R.drawable.ic_sponsor_badge), null, Modifier.size(48.dp).alpha(.7f), tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.sponsor_community_sponsor), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(stringResource(R.string.sponsor_about_thanks), Modifier.padding(top = 8.dp), color = themeColor(R.attr.colorOnSurfaceVariant), textAlign = TextAlign.Center)
            Text(b.aboutSponsorSince.text.toString(), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = themeColor(R.attr.colorOnSurfaceVariant))
            OriginalButton("fragment_about:about_sponsor_again", { b.aboutSponsorAgain.performClick() }, Modifier.padding(top = 10.dp)) { Text(b.aboutSponsorAgain.text.toString()) }
        }
    }
}

@Composable
internal fun AboutQuickActions(b: AboutState) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 20.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f).testTag("about.telegram-column")) {
            AboutFramedAction(b.aboutTelegram, "about.telegram")
            AboutActionSubtitle(R.string.about_bravedns_toreport_text, "about.telegram-subtitle")
        }
        Column(Modifier.weight(1f).testTag("about.report-column")) {
            if (b.progressLayout.isVisible) Row(Modifier.align(Alignment.CenterHorizontally)
                .padding(10.dp).testTag("about.report-progress"), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text(stringResource(R.string.collecting_logs_progress_text), Modifier.padding(2.dp),
                    style = utilityTextStyle(), color = themeColor(com.google.android.material.R.attr.colorSecondary))
            }
            AboutFramedAction(b.aboutCrashLog, "about.report")
            AboutActionSubtitle(R.string.about_bug_report_desc, "about.report-subtitle")
        }
    }
}

@Composable
private fun AboutDescription(text: String, modifier: Modifier, tag: String) {
    Text(text, modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag(tag),
        style = utilityTextStyle(appearance = androidx.appcompat.R.style.TextAppearance_AppCompat_Subhead,
            size = utilityDimensionSp(LocalContext.current, R.dimen.large_font_text_view), lineExtraDp = 5f),
        color = themeColor(R.attr.primaryTextColor))
}

@Composable
private fun AboutActionSubtitle(label: Int, tag: String) {
    // These two XML labels have no explicit TextAppearance or textColor. Preserve the themed
    // TextView defaults rather than turning the secondary caption into primary body text.
    val defaults = resolveWgText(LocalContext.current, LocalDensity.current, source = null)
    Text(stringResource(label), Modifier.fillMaxWidth().padding(top = 3.dp, bottom = 10.dp).testTag(tag),
        style = defaults.style.copy(fontSize = utilityDimensionSp(LocalContext.current, R.dimen.large_font_text_view).sp),
        color = Color(defaults.colors.defaultColor), textAlign = TextAlign.Center)
}

@Composable
private fun AboutSection(label: Int, groupTop: Dp = 16.dp) {
    Text(stringResource(label), Modifier.padding(top = groupTop).padding(start = 60.dp, top = 16.dp, bottom = 8.dp)
        .padding(5.dp).semantics { heading() },
        style = utilityTextStyle(appearance = com.google.android.material.R.style.TextAppearance_Material3_TitleSmall),
        color = MaterialTheme.colorScheme.primary)
}

/** TextView's 24dp drawableStart and -15dp drawablePadding leave a 9dp label offset.
 * Keep the icon at the edge and the label centered, rather than centering an icon/text cluster. */
@Composable
internal fun AboutFramedAction(node: WgControlState, tag: String, modifier: Modifier = Modifier,
    startPadding: Dp = 16.dp, tint: Color = themeColor(R.attr.primaryTextColor), iconTint: Color = tint) {
    if (!node.isVisible) return
    val shape = RoundedCornerShape(16.dp)
    Box(modifier.fillMaxWidth().clip(shape).border(.75.dp, themeColor(R.attr.border), shape)
        .clickable(enabled = node.isEnabled && node.isClickable, role = Role.Button) { node.performClick() }
        .testTag(tag).heightIn(min = 48.dp)
        .padding(start = startPadding, top = 12.dp, end = 16.dp, bottom = 12.dp),
        contentAlignment = Alignment.CenterStart) {
        node.imageResource?.let { Icon(painterResource(it), null, Modifier.size(24.dp), tint = iconTint) }
        Text(node.text.toString(), Modifier.fillMaxWidth()
            .padding(start = if (node.imageResource != null) 9.dp else 0.dp),
            style = utilityTextStyle(appearance = androidx.appcompat.R.style.TextAppearance_AppCompat_Subhead,
                size = utilityDimensionSp(LocalContext.current, R.dimen.large_font_text_view)),
            color = tint, textAlign = TextAlign.Center)
    }
}

@Composable
private fun AboutRow(node: WgControlState, startMargin: Dp = 16.dp, iconGap: Dp = 20.dp) {
    if (!node.isVisible) return
    Row(Modifier.fillMaxWidth().padding(start = startMargin, end = 16.dp)
        .clickable(enabled = node.isEnabled && node.isClickable) { node.performClick() }
        .heightIn(min = 48.dp).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        node.imageResource?.let { Icon(painterResource(it), null, Modifier.size(24.dp), tint = Color.Unspecified) }
        Text(node.text.toString(), Modifier.weight(1f).padding(start = iconGap),
            style = utilityTextStyle(appearance = androidx.appcompat.R.style.TextAppearance_AppCompat_Subhead,
                size = utilityDimensionSp(LocalContext.current, R.dimen.large_font_text_view), lineExtraDp = 5f),
            color = themeColor(R.attr.primaryTextColor))
    }
}

@Composable
private fun AboutFooter(b: AboutState) {
    Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, themeColor(R.attr.colorSurfaceContainer).copy(alpha = .55f), Color.Transparent)))
        .padding(top = 36.dp, bottom = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.about_mozilla), Modifier.padding(horizontal = 24.dp).alpha(.6f),
            style = utilityTextStyle(size = utilityDimensionSp(LocalContext.current, R.dimen.small_font_text_view), lineExtraDp = 3f),
            color = themeColor(R.attr.primaryLightColorText), textAlign = TextAlign.Center)
        Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf(b.mozillaImg, b.fossImg, b.flossFundsImg).forEach { node ->
                Image(painterResource(requireNotNull(node.imageResource)), stringResource(R.string.about_mozilla),
                    Modifier.size(80.dp, 24.dp).alpha(.75f).clickable { node.performClick() })
            }
        }
        SelectionContainer {
            Text(b.aboutAppVersion.text.toString(), Modifier.fillMaxWidth().padding(top = 28.dp, start = 16.dp, end = 16.dp).alpha(.75f),
                color = themeColor(R.attr.primaryLightColorText),
                style = utilityTextStyle(size = utilityDimensionSp(LocalContext.current, R.dimen.small_font_text_view), family = "monospace", lineExtraDp = 4f), textAlign = TextAlign.Center)
        }
        if (b.tokenTextView.isVisible) AboutTokenContent(b)
        Text(b.aboutLicense.text.toString(), Modifier.fillMaxWidth().padding(horizontal = 16.dp).alpha(.75f).clickable { b.aboutLicense.performClick() }.padding(top = 8.dp),
            style = utilityTextStyle(size = utilityDimensionSp(LocalContext.current, R.dimen.small_font_text_view), family = "monospace"), color = themeColor(R.attr.primaryLightColorText), textAlign = TextAlign.Center)
    }
}
