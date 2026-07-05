/*
 * Copyright 2020 RethinkDNS and its authors
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
package com.celzero.bravedns.ui.compose.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.ui.compose.theme.CardPosition
import com.celzero.bravedns.R
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.bottomsheet.BackupRestoreSheet
import com.celzero.bravedns.ui.compose.theme.Dimensions
import com.celzero.bravedns.ui.compose.theme.RethinkActionListItem
import com.celzero.bravedns.ui.compose.theme.RethinkAnimatedSection
import com.celzero.bravedns.ui.compose.theme.RethinkLargeTopBar
import com.celzero.bravedns.ui.compose.theme.RethinkListItem
import com.celzero.bravedns.ui.compose.theme.RethinkToggleListItem
import com.celzero.bravedns.ui.compose.theme.SectionHeader
import com.celzero.bravedns.util.UIUtils.openUrl
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.Utilities.isFdroidFlavour
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

private fun miscFocusTarget(
    focusKey: String,
    showFirewallBubble: Boolean,
    isFdroidFlavor: Boolean
): Pair<Int, Int>? {
    val rowHeight = 76
    val cardRowStart = 54

    fun rowOffset(row: Int): Int {
        return cardRowStart + (rowHeight * row)
    }

    val togglesRow =
        when (focusKey) {
            "general_logs" -> 0
            "general_autostart" -> 1
            "general_tombstone" -> 2
            "general_firewall_bubble" -> if (showFirewallBubble) 3 else null
            "general_ip_info" -> if (showFirewallBubble) 4 else 3
            "general_app_updates" ->
                if (isFdroidFlavor) {
                    null
                } else if (showFirewallBubble) {
                    5
                } else {
                    4
                }
            "general_crash_reports" ->
                if (isFdroidFlavor) {
                    null
                } else if (showFirewallBubble) {
                    6
                } else {
                    5
                }
            "general_custom_downloader" ->
                if (isFdroidFlavor) {
                    if (showFirewallBubble) 5 else 4
                } else if (showFirewallBubble) {
                    7
                } else {
                    6
                }
            else -> null
        }

    return when (focusKey) {
        "general_appearance",
        "general_theme_mode" -> 0 to 48
        "general_theme_color" -> 0 to 148
        "general_backup",
        "general_backup_restore" -> 1 to rowOffset(0)
        "general_about",
        "general_website" -> 3 to rowOffset(0)
        else -> {
            togglesRow?.let { 2 to rowOffset(it) }
        }
    }
}

private fun miscFocusIndex(focusKey: String): Int? {
    return when (focusKey) {
        "general_appearance",
        "general_theme_mode",
        "general_theme_color" -> 0
        "general_backup_restore",
        "general_backup" -> 1
        "general_toggles",
        "general_logs",
        "general_autostart",
        "general_tombstone",
        "general_firewall_bubble",
        "general_ip_info",
        "general_app_updates",
        "general_crash_reports",
        "general_custom_downloader" -> 2
        "general_about",
        "general_website" -> 3
        else -> null
    }
}

private suspend fun smartScrollToItem(
    listState: LazyListState,
    density: Density,
    index: Int,
    offsetDp: Int
) {
    val offsetPx = with(density) { offsetDp.dp.toPx().roundToInt() }
    listState.animateScrollToItem(index, 0)
    repeat(3) {
        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
        val desiredTop = -offsetPx
        val delta = info.offset - desiredTop
        if (abs(delta) <= 8) return
        listState.animateScrollBy(delta.toFloat())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiscSettingsScreen(
    persistentState: PersistentState,
    eventLogger: EventLogger,
    initialFocusKey: String? = null,
    onBackClick: (() -> Unit)? = null,
    onRefreshDatabase: (() -> Unit)? = null,
    onThemeModeChanged: ((Int) -> Unit)? = null,
    onThemeColorChanged: ((Int) -> Unit)? = null
) {
    var logsEnabled by remember { mutableStateOf(persistentState.logsEnabled) }
    var checkUpdatesEnabled by remember { mutableStateOf(persistentState.checkForAppUpdate) }
    var firebaseEnabled by remember { mutableStateOf(persistentState.firebaseErrorReportingEnabled) }
    var ipInfoEnabled by remember { mutableStateOf(persistentState.downloadIpInfo) }
    var customDownloadEnabled by remember { mutableStateOf(persistentState.useCustomDownloadManager) }
    var autoStartEnabled by remember { mutableStateOf(persistentState.prefAutoStartBootUp) }
    var tombstoneEnabled by remember { mutableStateOf(persistentState.tombstoneApps) }
    var firewallBubbleEnabled by remember { mutableStateOf(persistentState.firewallBubbleEnabled) }
    var showBackupSheet by remember { mutableStateOf(false) }
    val initialFocus = initialFocusKey?.trim().orEmpty()
    var pendingFocusKey by rememberSaveable(initialFocus) { mutableStateOf(initialFocus) }
    var activeFocusKey by rememberSaveable(initialFocus) {
        mutableStateOf(initialFocus.ifBlank { null })
    }

    val context = LocalContext.current
    val aboutWebsiteLink = stringResource(id = R.string.about_website_link)
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val showFirewallBubble = isAtleastQ()
    val isFdroidFlavor = isFdroidFlavour()
    val generalToggleKeys = remember(showFirewallBubble, isFdroidFlavor) {
        buildList {
            add("general_logs")
            add("general_autostart")
            add("general_tombstone")
            if (showFirewallBubble) add("general_firewall_bubble")
            add("general_ip_info")
            if (!isFdroidFlavor) {
                add("general_app_updates")
                add("general_crash_reports")
            }
            add("general_custom_downloader")
        }
    }

    LaunchedEffect(pendingFocusKey) {
        val key = pendingFocusKey.trim()
        if (key.isBlank()) return@LaunchedEffect
        activeFocusKey = key
        val target =
            miscFocusTarget(
                focusKey = key,
                showFirewallBubble = showFirewallBubble,
                isFdroidFlavor = isFdroidFlavor
            )

        if (target != null) {
            val (index, offsetDp) = target
            smartScrollToItem(listState, density, index, offsetDp)
            delay(850)
            if (activeFocusKey == key) {
                activeFocusKey = null
            }
            pendingFocusKey = ""
            return@LaunchedEffect
        }

        val index = miscFocusIndex(key)
        if (index != null) {
            smartScrollToItem(listState, density, index, 0)
            delay(700)
            if (activeFocusKey == key) {
                activeFocusKey = null
            }
        }
        pendingFocusKey = ""
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            RethinkLargeTopBar(
                title = stringResource(R.string.settings_general_header).titlecaseFirst(),
                onBackClick = onBackClick,
                scrollBehavior = scrollBehavior
            )
        }
    ) { paddingValues ->
        LazyColumn(
            state = listState,
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
            item {
                RethinkAnimatedSection(index = 0) {
                    AppearanceSettingsCard(
                        themePreference = persistentState.theme,
                        colorPresetId = persistentState.themeColorPreset,
                        onAppearanceModeSelected = { mode ->
                            val themeId = mode.toThemePreference()
                            persistentState.theme = themeId
                            onThemeModeChanged?.invoke(themeId)
                            logEvent(
                                eventLogger = eventLogger,
                                msg = "Appearance",
                                details = "Theme set to ${mode.name.lowercase()}"
                            )
                        },
                        onColorPresetSelected = { preset ->
                            persistentState.themeColorPreset = preset.id
                            onThemeColorChanged?.invoke(preset.id)
                            logEvent(
                                eventLogger = eventLogger,
                                msg = "Appearance color",
                                details = "Color preset set to ${preset.name.lowercase()}"
                            )
                        },
                        showSectionHeader = true
                    )
                }
            }

            item {
                RethinkAnimatedSection(index = 1) {
                    SectionHeader(
                        title = stringResource(id = R.string.brbs_title)
                    )
                    Column {
                        RethinkActionListItem(
                            title = stringResource(id = R.string.brbs_backup_title),
                            description = stringResource(id = R.string.brbs_backup_desc),
                            icon = Icons.Rounded.Backup,
                            accentColor = MaterialTheme.colorScheme.secondary,
                            highlighted = activeFocusKey == "general_backup",
                            position = CardPosition.Single,
                            onClick = { showBackupSheet = true }
                        )
                    }
                }
            }

            item {
                RethinkAnimatedSection(index = 2) {
                    SectionHeader(
                        title = stringResource(id = R.string.settings_general_header).titlecaseFirst()
                    )
                    Column {
                        RethinkToggleListItem(
                            title = stringResource(id = R.string.settings_enable_logs),
                            description = stringResource(id = R.string.settings_enable_logs_desc),
                            iconRes = R.drawable.ic_logs_accent,
                            checked = logsEnabled,
                            accentColor = MaterialTheme.colorScheme.primary,
                            highlighted = activeFocusKey == "general_logs",
                            position = generalToggleKeys.positionFor("general_logs"),
                            onCheckedChange = { enabled ->
                                logsEnabled = enabled
                                persistentState.logsEnabled = enabled
                                logEvent(eventLogger, "Logs", "User ${if (enabled) "enabled" else "disabled"} logs")
                            }
                        )
                        RethinkToggleListItem(
                            title = stringResource(id = R.string.settings_autostart_bootup_heading),
                            description = stringResource(id = R.string.settings_autostart_bootup_desc),
                            iconRes = R.drawable.ic_auto_start,
                            checked = autoStartEnabled,
                            accentColor = MaterialTheme.colorScheme.primary,
                            highlighted = activeFocusKey == "general_autostart",
                            position = generalToggleKeys.positionFor("general_autostart"),
                            onCheckedChange = { enabled ->
                                autoStartEnabled = enabled
                                persistentState.prefAutoStartBootUp = enabled
                                logEvent(eventLogger, "Auto-start", "Auto-start on power-up set to $enabled")
                            }
                        )
                        RethinkToggleListItem(
                            title = stringResource(id = R.string.tombstone_app_title),
                            description = stringResource(id = R.string.tombstone_app_desc),
                            iconRes = R.drawable.ic_tombstone,
                            checked = tombstoneEnabled,
                            accentColor = MaterialTheme.colorScheme.primary,
                            highlighted = activeFocusKey == "general_tombstone",
                            position = generalToggleKeys.positionFor("general_tombstone"),
                            onCheckedChange = { enabled ->
                                tombstoneEnabled = enabled
                                persistentState.tombstoneApps = enabled
                                logEvent(eventLogger, "Tombstone apps", "Remember uninstalled apps set to $enabled")
                            }
                        )

                        if (showFirewallBubble) {
                            RethinkToggleListItem(
                                title = stringResource(id = R.string.firewall_bubble_title),
                                description = stringResource(id = R.string.firewall_bubble_desc),
                                iconRes = R.drawable.ic_firewall_bubble,
                                checked = firewallBubbleEnabled,
                                accentColor = MaterialTheme.colorScheme.primary,
                                highlighted = activeFocusKey == "general_firewall_bubble",
                                position = generalToggleKeys.positionFor("general_firewall_bubble"),
                                onCheckedChange = { enabled ->
                                    firewallBubbleEnabled = enabled
                                    persistentState.firewallBubbleEnabled = enabled
                                    logEvent(eventLogger, "Firewall bubble", "Firewall bubble set to $enabled")
                                }
                            )
                        }

                        RethinkToggleListItem(
                            title = stringResource(id = R.string.download_ip_info_title),
                            description = stringResource(
                                id = R.string.download_ip_info_desc,
                                stringResource(id = R.string.lbl_ipinfo_inc)
                            ),
                            iconRes = R.drawable.ic_ip_info,
                            checked = ipInfoEnabled,
                            accentColor = MaterialTheme.colorScheme.primary,
                            highlighted = activeFocusKey == "general_ip_info",
                            position = generalToggleKeys.positionFor("general_ip_info"),
                            onCheckedChange = { enabled ->
                                ipInfoEnabled = enabled
                                persistentState.downloadIpInfo = enabled
                            }
                        )

                        if (!isFdroidFlavor) {
                            RethinkToggleListItem(
                                title = stringResource(id = R.string.settings_check_update_heading),
                                description = stringResource(id = R.string.settings_check_update_desc),
                                iconRes = R.drawable.ic_update,
                                checked = checkUpdatesEnabled,
                                accentColor = MaterialTheme.colorScheme.primary,
                                highlighted = activeFocusKey == "general_app_updates",
                                position = generalToggleKeys.positionFor("general_app_updates"),
                                onCheckedChange = { enabled ->
                                    checkUpdatesEnabled = enabled
                                    persistentState.checkForAppUpdate = enabled
                                }
                            )

                            RethinkToggleListItem(
                                title = stringResource(id = R.string.settings_firebase_error_reporting_heading),
                                description = stringResource(id = R.string.settings_firebase_error_reporting_desc),
                                icon = Icons.Rounded.BugReport,
                                checked = firebaseEnabled,
                                accentColor = MaterialTheme.colorScheme.primary,
                                highlighted = activeFocusKey == "general_crash_reports",
                                position = generalToggleKeys.positionFor("general_crash_reports"),
                                onCheckedChange = { enabled ->
                                    firebaseEnabled = enabled
                                    persistentState.firebaseErrorReportingEnabled = enabled
                                }
                            )
                        }

                        RethinkToggleListItem(
                            title = stringResource(id = R.string.settings_custom_downloader_heading),
                            description = stringResource(id = R.string.settings_custom_downloader_desc),
                            icon = Icons.Rounded.Settings,
                            checked = customDownloadEnabled,
                            accentColor = MaterialTheme.colorScheme.primary,
                            highlighted = activeFocusKey == "general_custom_downloader",
                            position = generalToggleKeys.positionFor("general_custom_downloader"),
                            onCheckedChange = { enabled ->
                                customDownloadEnabled = enabled
                                persistentState.useCustomDownloadManager = enabled
                            }
                        )
                    }
                }
            }

            item {
                RethinkAnimatedSection(index = 3) {
                    SectionHeader(
                        title = stringResource(id = R.string.title_about)
                    )
                    Column {
                        RethinkListItem(
                            headline = stringResource(id = R.string.about_website),
                            supporting = stringResource(id = R.string.about_website_link),
                            leadingIcon = Icons.Rounded.Public,
                            leadingIconTint = MaterialTheme.colorScheme.tertiary,
                            leadingIconContainerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f),
                            position = CardPosition.Single,
                            highlighted = activeFocusKey == "general_website",
                            highlightContainerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.24f),
                            onClick = { openUrl(context, aboutWebsiteLink) }
                        )
                    }
                }
            }
        }
    }

    if (showBackupSheet) {
        BackupRestoreSheet(onDismiss = { showBackupSheet = false })
    }
}

private fun logEvent(eventLogger: EventLogger, msg: String, details: String) {
    eventLogger.log(
        type = EventType.UI_SETTING_CHANGED,
        severity = Severity.LOW,
        message = msg,
        source = EventSource.UI,
        userAction = true,
        details = details
    )
}

private fun List<String>.positionFor(key: String): CardPosition {
    val index = indexOf(key)
    if (index < 0) return CardPosition.Middle
    return when {
        size == 1 -> CardPosition.Single
        index == 0 -> CardPosition.First
        index == lastIndex -> CardPosition.Last
        else -> CardPosition.Middle
    }
}

private fun String.titlecaseFirst(): String {
    return replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}
