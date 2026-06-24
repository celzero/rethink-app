/*
 * Copyright 2024 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@file:OptIn(ExperimentalMaterial3Api::class)

package com.celzero.bravedns.ui.compose.about

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.compose.settings.AppearanceSettingsCard
import com.celzero.bravedns.ui.compose.theme.CardPosition
import com.celzero.bravedns.ui.compose.theme.Dimensions
import com.celzero.bravedns.ui.compose.theme.RethinkAnimatedSection
import com.celzero.bravedns.ui.compose.theme.RethinkGridTile
import com.celzero.bravedns.ui.compose.theme.RethinkLargeTopBar
import com.celzero.bravedns.ui.compose.theme.RethinkListItem
import com.celzero.bravedns.ui.compose.theme.SectionHeader
import com.celzero.bravedns.ui.compose.theme.cardPositionFor

private data class AboutItem(
    val headline: String,
    val iconPainter: Painter,
    val onClick: () -> Unit,
)

private fun aboutTopClusterPosition(index: Int, lastIndex: Int): CardPosition {
    return when {
        lastIndex <= 0 -> CardPosition.Last
        index == lastIndex -> CardPosition.Last
        else -> CardPosition.Middle
    }
}

@Composable
fun AboutScreen(
    uiState: AboutUiState,
    onSponsorClick: () -> Unit,
    onTelegramClick: () -> Unit,
    onBugReportClick: () -> Unit,
    onWhatsNewClick: () -> Unit,
    onAppUpdateClick: () -> Unit,
    onContributorsClick: () -> Unit,
    onTranslateClick: () -> Unit,
    onWebsiteClick: () -> Unit,
    onGithubClick: () -> Unit,
    onFaqClick: () -> Unit,
    onDocsClick: () -> Unit,
    onPrivacyPolicyClick: () -> Unit,
    onTermsOfServiceClick: () -> Unit,
    onLicenseClick: () -> Unit,
    onTwitterClick: () -> Unit,
    onEmailClick: () -> Unit,
    onRedditClick: () -> Unit,
    onElementClick: () -> Unit,
    onMastodonClick: () -> Unit,
    onGeneralSettingsClick: () -> Unit,
    onAppInfoClick: () -> Unit,
    onVpnProfileClick: () -> Unit,
    onNotificationClick: () -> Unit,
    onStatsClick: () -> Unit,
    onDbStatsClick: () -> Unit,
    onFlightRecordClick: () -> Unit,
    onEventLogsClick: () -> Unit,
    onTokenClick: () -> Unit,
    onTokenDoubleTap: () -> Unit,
    onFossClick: () -> Unit,
    onFlossFundsClick: () -> Unit,
    persistentState: PersistentState,
    onThemeModeChanged: ((Int) -> Unit)? = null,
    onThemeColorChanged: ((Int) -> Unit)? = null
) {
    val aboutTitle = stringResource(id = R.string.title_about)
    val appName = stringResource(id = R.string.app_name)
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val expandedTitle = remember(appName) { appName }
    val expandedSubtitle = remember(uiState.versionName, uiState.slicedVersion) {
        fun withSingleVPrefix(version: String): String {
            return if (version.startsWith("v", ignoreCase = true)) version else "v$version"
        }

        when {
            uiState.versionName.isNotBlank() -> withSingleVPrefix(uiState.versionName)
            uiState.slicedVersion.isNotBlank() -> withSingleVPrefix(uiState.slicedVersion)
            else -> null
        }
    }
    val topBarTitle by remember(scrollBehavior.state, expandedTitle, aboutTitle) {
        derivedStateOf {
            if (scrollBehavior.state.collapsedFraction >= 0.55f) {
                aboutTitle
            } else {
                expandedTitle
            }
        }
    }
    val topBarSubtitle by remember(scrollBehavior.state, expandedSubtitle) {
        derivedStateOf {
            if (scrollBehavior.state.collapsedFraction >= 0.55f) {
                null
            } else {
                expandedSubtitle
            }
        }
    }
    val quickActionIconTint = MaterialTheme.colorScheme.onPrimaryFixed.copy(alpha = 0.8f)
    val telegramTint = Color(0xFF74C5FF)
    val bugReportTint = Color(0xFFFF907F)

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            RethinkLargeTopBar(
                title = topBarTitle,
                subtitle = topBarSubtitle,
                scrollBehavior = scrollBehavior,
                titleTextStyle = MaterialTheme.typography.headlineMedium
            )
        }
    ) { paddingValues ->
        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(
                start = Dimensions.screenPaddingHorizontal,
                end = Dimensions.screenPaddingHorizontal,
                top = Dimensions.spacingSm,
                bottom = Dimensions.spacing3xl
            ),
            verticalArrangement = Arrangement.spacedBy(Dimensions.spacingLg)
        ) {
            // ── Sponsor card (hidden intentionally) ───────────────────
            // item {
            //     RethinkAnimatedSection(index = 0) {
            //         SponsorCard(uiState, onSponsorClick)
            //     }
            // }

            // ── Appearance (shared settings component) ───────────────
            item {
                RethinkAnimatedSection(index = 1) {
                    AppearanceSettingsCard(
                        themePreference = persistentState.theme,
                        colorPresetId = persistentState.themeColorPreset,
                        onAppearanceModeSelected = { mode ->
                            val themeId = mode.toThemePreference()
                            persistentState.theme = themeId
                            onThemeModeChanged?.invoke(themeId)
                        },
                        onColorPresetSelected = { preset ->
                            persistentState.themeColorPreset = preset.id
                            onThemeColorChanged?.invoke(preset.id)
                        },
                        sectionHeaderColor = MaterialTheme.colorScheme.primary,
                        showSectionHeader = true
                    )
                }
            }

            // ── Quick actions (Telegram + Bug Report) ─────────────────
            item {
                RethinkAnimatedSection(index = 2) {
                    AboutAppSection(
                        uiState = uiState,
                        onTelegramClick = onTelegramClick,
                        onBugReportClick = onBugReportClick,
                        onWhatsNewClick = onWhatsNewClick,
                        onAppUpdateClick = onAppUpdateClick,
                        quickActionIconTint = quickActionIconTint,
                        telegramTint = telegramTint,
                        bugReportTint = bugReportTint
                    )
                }
            }

            // ── Web section ───────────────────────────────────────────
            item {
                RethinkAnimatedSection(index = 3) {
                    AboutSection(
                        title = stringResource(id = R.string.about_web),
                        accentColor = MaterialTheme.colorScheme.secondary,
                        items = listOf(
                            AboutItem(
                                headline = stringResource(id = R.string.about_website),
                                iconPainter = rememberVectorPainter(image = Icons.Rounded.Public),
                                onClick = onWebsiteClick
                            ),
                            AboutItem(stringResource(id = R.string.about_github), painterResource(id = R.drawable.ic_github), onGithubClick),
                            AboutItem(
                                headline = stringResource(id = R.string.about_faq),
                                iconPainter = rememberVectorPainter(image = Icons.AutoMirrored.Rounded.HelpOutline),
                                onClick = onFaqClick
                            ),
                            AboutItem(
                                headline = stringResource(id = R.string.about_docs),
                                iconPainter = rememberVectorPainter(image = Icons.AutoMirrored.Rounded.Article),
                                onClick = onDocsClick
                            ),
                            AboutItem(
                                stringResource(id = R.string.about_privacy_policy),
                                rememberVectorPainter(image = Icons.Rounded.Policy),
                                onPrivacyPolicyClick
                            ),
                            AboutItem(
                                stringResource(id = R.string.about_terms_of_service),
                                rememberVectorPainter(image = Icons.Rounded.Gavel),
                                onTermsOfServiceClick
                            ),
                            AboutItem(
                                stringResource(id = R.string.about_license),
                                rememberVectorPainter(image = Icons.AutoMirrored.Rounded.Article),
                                onLicenseClick
                            ),
                        ),
                    )
                }
            }

            // ── Connect / community section ───────────────────────────
            item {
                RethinkAnimatedSection(index = 4) {
                    AboutConnectSection(
                        onTwitterClick = onTwitterClick,
                        onEmailClick = onEmailClick,
                        onRedditClick = onRedditClick,
                        onElementClick = onElementClick,
                        onMastodonClick = onMastodonClick
                    )
                }
            }

            // ── System settings section ───────────────────────────────
            item {
                RethinkAnimatedSection(index = 5) {
                    AboutSection(
                        title = stringResource(id = R.string.about_settings),
                        accentColor = MaterialTheme.colorScheme.primary,
                        items = listOf(
                            AboutItem(
                                stringResource(id = R.string.settings_general_header),
                                rememberVectorPainter(image = Icons.Rounded.Settings),
                                onGeneralSettingsClick
                            ),
                            AboutItem(
                                stringResource(id = R.string.about_settings_app_info),
                                rememberVectorPainter(image = Icons.Rounded.Info),
                                onAppInfoClick
                            ),
                            AboutItem(
                                stringResource(id = R.string.about_settings_vpn_profile),
                                rememberVectorPainter(image = Icons.Rounded.VpnKey),
                                onVpnProfileClick
                            ),
                            AboutItem(
                                stringResource(id = R.string.about_settings_notification),
                                rememberVectorPainter(image = Icons.Rounded.Notifications),
                                onNotificationClick
                            ),
                        ),
                    )
                }
            }

            // ── Debug / diagnostics section ───────────────────────────
            item {
                val items = buildList {
                    add(AboutItem(stringResource(id = R.string.title_statistics), painterResource(id = R.drawable.ic_log_level), onStatsClick))
                    add(
                        AboutItem(
                            stringResource(id = R.string.title_database_dump),
                            rememberVectorPainter(image = Icons.Rounded.Backup),
                            onDbStatsClick
                        )
                    )
                    if (uiState.isDebug) {
                        add(
                            AboutItem(
                                "Flight Recorder",
                                rememberVectorPainter(image = Icons.Rounded.Backup),
                                onFlightRecordClick
                            )
                        )
                    }
                    add(
                        AboutItem(
                            stringResource(id = R.string.event_logs_title),
                            rememberVectorPainter(image = Icons.AutoMirrored.Rounded.Article),
                            onEventLogsClick
                        )
                    )
                }
                RethinkAnimatedSection(index = 6) {
                    AboutSection(
                        title = stringResource(id = R.string.title_statistics),
                        accentColor = MaterialTheme.colorScheme.secondary,
                        items = items,
                    )
                }
            }

            // ── Partner logos ─────────────────────────────────────────
            item {
                RethinkAnimatedSection(index = 7) {
                    PartnerLogosCard(onFossClick, onFlossFundsClick)
                }
            }

            // ── Version footer ────────────────────────────────────────
            item {
                RethinkAnimatedSection(index = 8) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (uiState.isFirebaseEnabled && !uiState.isFdroid) {
                            Text(
                                text = uiState.firebaseToken,
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .alpha(0.5f)
                                    .padding(bottom = Dimensions.spacingMd)
                                    .clickable { onTokenClick() }
                            )
                        }
                        Text(
                            text = "${uiState.versionName} · ${uiState.installSource}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .alpha(0.75f)
                        )
                        if (uiState.buildNumber.isNotBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = uiState.buildNumber,
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .alpha(0.55f)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── Sponsor Card ───────────────────────────────────────────────────────────

@Suppress("unused")
@Composable
private fun SponsorCard(uiState: AboutUiState, onSponsorClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = tween(100),
        label = "sponsor_scale"
    )

    Surface(
        shape = RoundedCornerShape(Dimensions.cornerRadius3xl),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        onClick = onSponsorClick,
        interactionSource = interactionSource,
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(Dimensions.cornerRadiusMdLg),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(52.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_launcher_foreground),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
                Text(
                    text = stringResource(id = R.string.about_bravedns_explantion),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = stringResource(
                        id = R.string.sponser_dialog_usage_msg,
                        uiState.daysSinceInstall,
                        uiState.sponsoredAmount
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }

            Button(
                onClick = onSponsorClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(Dimensions.cornerRadiusMdLg),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Favorite,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(id = R.string.about_sponsor_link_text),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ─── Section composable with colored icon containers ────────────────────────

@Composable
private fun AboutSection(
    title: String,
    accentColor: Color,
    items: List<AboutItem>,
) {
    Column {
        SectionHeader(title = title, color = accentColor)
        items.forEachIndexed { index, item ->
            RethinkListItem(
                headline = item.headline,
                leadingIconPainter = item.iconPainter,
                leadingIconTint = accentColor,
                leadingIconContainerColor = accentColor.copy(alpha = 0.14f),
                position = cardPositionFor(index = index, lastIndex = items.lastIndex),
                highlightContainerColor = accentColor.copy(alpha = 0.24f),
                onClick = item.onClick
            )
        }
    }
}

@Composable
private fun AboutAppSection(
    uiState: AboutUiState,
    onTelegramClick: () -> Unit,
    onBugReportClick: () -> Unit,
    onWhatsNewClick: () -> Unit,
    onAppUpdateClick: () -> Unit,
    quickActionIconTint: Color,
    telegramTint: Color,
    bugReportTint: Color
) {
    val accentColor = MaterialTheme.colorScheme.primary
    val listItems = buildList {
        add(
            AboutItem(
                headline = stringResource(id = R.string.about_whats_new, uiState.slicedVersion),
                iconPainter = rememberVectorPainter(image = Icons.Rounded.NewReleases),
                onClick = onWhatsNewClick
            )
        )
        if (!uiState.isFdroid) {
            add(
                AboutItem(
                    headline = stringResource(id = R.string.about_app_update_check),
                    iconPainter = painterResource(id = R.drawable.ic_update),
                    onClick = onAppUpdateClick
                )
            )
        }
    }

    Column {
        SectionHeader(title = stringResource(id = R.string.about_app), color = accentColor)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.spacingGridTile)
        ) {
            RethinkGridTile(
                title = stringResource(id = R.string.about_join_telegram),
                iconRes = R.drawable.ic_telegram,
                accentColor = telegramTint,
                iconTint = quickActionIconTint,
                iconContainerColor = telegramTint,
                shape = RoundedCornerShape(
                    topStart = 22.dp,
                    topEnd = 12.dp,
                    bottomStart = 6.dp,
                    bottomEnd = 6.dp
                ),
                modifier = Modifier.weight(1f),
                onClick = onTelegramClick
            )
            if (uiState.isBugReportRunning) {
                RethinkGridTile(
                    title = stringResource(id = R.string.collecting_logs_progress_text),
                    iconRes = R.drawable.ic_android_icon,
                    accentColor = bugReportTint,
                    iconTint = quickActionIconTint,
                    iconContainerColor = bugReportTint,
                    shape = RoundedCornerShape(
                        topStart = 12.dp,
                        topEnd = 22.dp,
                        bottomStart = 6.dp,
                        bottomEnd = 6.dp
                    ),
                    modifier = Modifier.weight(1f),
                    trailing = {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = bugReportTint
                        )
                    }
                )
            } else {
                RethinkGridTile(
                    title = stringResource(id = R.string.about_bug_report),
                    iconRes = R.drawable.ic_android_icon,
                    accentColor = bugReportTint,
                    iconTint = quickActionIconTint,
                    iconContainerColor = bugReportTint,
                    shape = RoundedCornerShape(
                        topStart = 12.dp,
                        topEnd = 22.dp,
                        bottomStart = 6.dp,
                        bottomEnd = 6.dp
                    ),
                    modifier = Modifier.weight(1f),
                    onClick = onBugReportClick
                )
            }
        }

        listItems.forEachIndexed { index, item ->
            RethinkListItem(
                headline = item.headline,
                leadingIconPainter = item.iconPainter,
                leadingIconTint = accentColor,
                leadingIconContainerColor = accentColor.copy(alpha = 0.14f),
                position = aboutTopClusterPosition(index = index, lastIndex = listItems.lastIndex),
                highlightContainerColor = accentColor.copy(alpha = 0.24f),
                onClick = item.onClick
            )
        }
    }
}

@Composable
private fun AboutConnectSection(
    onTwitterClick: () -> Unit,
    onEmailClick: () -> Unit,
    onRedditClick: () -> Unit,
    onElementClick: () -> Unit,
    onMastodonClick: () -> Unit
) {
    val accentColor = MaterialTheme.colorScheme.tertiary
    Column {
        SectionHeader(title = stringResource(id = R.string.about_connect), color = accentColor)

        RethinkListItem(
            headline = stringResource(id = R.string.about_twitter),
            leadingIconPainter = painterResource(id = R.drawable.ic_twitter),
            leadingIconTint = accentColor,
            leadingIconContainerColor = accentColor.copy(alpha = 0.14f),
            position = CardPosition.First,
            highlightContainerColor = accentColor.copy(alpha = 0.24f),
            onClick = onTwitterClick
        )

        Spacer(modifier = Modifier.height(Dimensions.spacingXs))

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                RethinkGridTile(
                    title = stringResource(id = R.string.about_email),
                    iconRes = R.drawable.ic_mail,
                    accentColor = accentColor,
                    shape = RoundedCornerShape(
                        topStart = 12.dp,
                        topEnd = 12.dp,
                        bottomStart = 12.dp,
                        bottomEnd = 12.dp
                    ),
                    modifier = Modifier.weight(1f),
                    onClick = onEmailClick
                )
                RethinkGridTile(
                    title = stringResource(id = R.string.lbl_reddit),
                    iconRes = R.drawable.ic_reddit,
                    accentColor = accentColor,
                    shape = RoundedCornerShape(
                        topStart = 12.dp,
                        topEnd = 12.dp,
                        bottomStart = 12.dp,
                        bottomEnd = 12.dp
                    ),
                    modifier = Modifier.weight(1f),
                    onClick = onRedditClick
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                RethinkGridTile(
                    title = stringResource(id = R.string.lbl_matrix),
                    iconRes = R.drawable.ic_element,
                    accentColor = accentColor,
                    shape = RoundedCornerShape(
                        topStart = 12.dp,
                        topEnd = 12.dp,
                        bottomStart = 28.dp,
                        bottomEnd = 12.dp
                    ),
                    modifier = Modifier.weight(1f),
                    onClick = onElementClick
                )
                RethinkGridTile(
                    title = stringResource(id = R.string.lbl_mastodon),
                    iconRes = R.drawable.ic_mastodon,
                    accentColor = accentColor,
                    shape = RoundedCornerShape(
                        topStart = 12.dp,
                        topEnd = 12.dp,
                        bottomStart = 12.dp,
                        bottomEnd = 28.dp
                    ),
                    modifier = Modifier.weight(1f),
                    onClick = onMastodonClick
                )
            }
        }
    }
}

// ─── Partner Logos Card ──────────────────────────────────────────────────────

@Composable
private fun PartnerLogosCard(onFossClick: () -> Unit, onFlossFundsClick: () -> Unit) {
    val isLightTheme = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    val cardColor =
        if (isLightTheme) Color(0xFF141922) else MaterialTheme.colorScheme.surfaceContainerLow
    val cardBorderColor =
        if (isLightTheme) Color.White.copy(alpha = 0.18f)
        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
    val textColor =
        if (isLightTheme) Color(0xFFE8EDF8) else MaterialTheme.colorScheme.onSurfaceVariant
    val logoChipColor =
        if (isLightTheme) Color.White.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface
    val logoChipBorderColor =
        if (isLightTheme) Color.White.copy(alpha = 0.22f)
        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)

    Surface(
        shape = RoundedCornerShape(Dimensions.cornerRadius3xl),
        color = cardColor,
        border = BorderStroke(1.dp, cardBorderColor),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(id = R.string.about_mozilla),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                color = textColor,
                modifier = Modifier.alpha(0.8f)
            )
            Image(
                painter = painterResource(id = R.drawable.mozilla),
                contentDescription = null,
                modifier = Modifier.width(150.dp),
                contentScale = ContentScale.FillWidth
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val logoShape = RoundedCornerShape(Dimensions.cornerRadiusMdLg)
                Surface(
                    shape = logoShape,
                    color = logoChipColor,
                    border = BorderStroke(1.dp, logoChipBorderColor),
                    modifier = Modifier
                        .clip(logoShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = onFossClick
                        )
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.foss_logo),
                        contentDescription = null,
                        modifier = Modifier
                            .width(126.dp)
                            .height(46.dp)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        contentScale = ContentScale.Fit
                    )
                }
                Surface(
                    shape = logoShape,
                    color = logoChipColor,
                    border = BorderStroke(1.dp, logoChipBorderColor),
                    modifier = Modifier
                        .clip(logoShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = onFlossFundsClick
                        )
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_floss_fund_badge),
                        contentDescription = null,
                        modifier = Modifier
                            .width(126.dp)
                            .height(46.dp)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }
    }
}
