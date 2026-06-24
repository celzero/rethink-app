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
package com.celzero.bravedns.ui.compose.configure

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.theme.Dimensions
import com.celzero.bravedns.ui.compose.theme.RethinkGridTile
import com.celzero.bravedns.ui.compose.theme.RethinkListItem
import com.celzero.bravedns.ui.compose.theme.SectionHeaderWithSubtitle
import com.celzero.bravedns.ui.compose.theme.cardPositionFor
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.celzero.bravedns.ui.compose.theme.RethinkAnimatedSection

private data class ConfigureEntry(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val iconTint: Color,
    val iconRes: Int,
    val onClick: () -> Unit,
    val keywords: List<String> = emptyList(),
)

private data class ConfigureSectionModel(
    val title: String,
    val subtitle: String? = null,
    val accentColor: Color,
    val layout: ConfigureSectionLayout,
    val entries: List<ConfigureEntry>,
)

private enum class ConfigureSectionLayout {
    GridFour,
    GridPairThenList,
    List
}

private val ConfigureTileGap = 2.dp

private data class ConfigureSearchTarget(
    val id: String,
    val title: String,
    val path: String,
    val iconRes: Int,
    val iconTint: Color,
    val onClick: () -> Unit,
    val keywords: List<String>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigureScreen(
    isDebug: Boolean,
    onAppsClick: () -> Unit,
    onDnsClick: () -> Unit,
    onFirewallClick: () -> Unit,
    onProxyClick: () -> Unit,
    onNetworkClick: () -> Unit,
    onOthersClick: () -> Unit,
    onLogsClick: () -> Unit,
    onAntiCensorshipClick: () -> Unit,
    onAdvancedClick: () -> Unit,
    onSearchDestinationClick: ((SettingsSearchDestination) -> Unit)? = null
) {
    var query by rememberSaveable { mutableStateOf("") }
    var isSearchOpen by rememberSaveable { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val largeTopBarScrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val searchTopBarScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val activeTopBarScrollBehavior =
        if (isSearchOpen) searchTopBarScrollBehavior else largeTopBarScrollBehavior
    val closeSearch = {
        isSearchOpen = false
        query = ""
    }

    LaunchedEffect(isSearchOpen) {
        if (isSearchOpen) {
            delay(120)
            searchFocusRequester.requestFocus()
            keyboardController?.show()
        } else {
            keyboardController?.hide()
        }
    }

    BackHandler(enabled = isSearchOpen) {
        closeSearch()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                isSearchOpen = false
                query = ""
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val protectionTitle = stringResource(R.string.lbl_protection)
    val systemTitle = stringResource(R.string.lbl_system)
    val advancedTitle = stringResource(R.string.lbl_advanced)

    val appsTitle = stringResource(R.string.lbl_apps)
    val dnsTitle = stringResource(R.string.lbl_dns)
    val firewallTitle = stringResource(R.string.lbl_firewall)
    val proxyTitle = stringResource(R.string.lbl_proxy)
    val networkTitle = stringResource(R.string.lbl_network)
    val settingsTitle = stringResource(R.string.title_settings)
    val logsTitle = stringResource(R.string.lbl_logs)
    val antiCensorshipTitle = stringResource(R.string.anti_censorship_title)
    val iconTints = rememberConfigureIconTints()

    val sections = buildList {
        add(
            ConfigureSectionModel(
                title = protectionTitle,
                accentColor = MaterialTheme.colorScheme.primary,
                layout = ConfigureSectionLayout.GridFour,
                entries = listOf(
                    ConfigureEntry(
                        id = "apps",
                        title = appsTitle,
                        iconTint = iconTints.apps,
                        iconRes = R.drawable.ic_app_info_accent,
                        onClick = onAppsClick,
                        keywords = listOf("apps", "application", "app list")
                    ),
                    ConfigureEntry(
                        id = "dns",
                        title = dnsTitle,
                        iconTint = iconTints.dns,
                        iconRes = R.drawable.dns_home_screen,
                        onClick = onDnsClick,
                        keywords = listOf("dns", "doh", "dot", "dnscrypt", "resolver", "blocklist")
                    ),
                    ConfigureEntry(
                        id = "firewall",
                        title = firewallTitle,
                        iconTint = iconTints.firewall,
                        iconRes = R.drawable.firewall_home_screen,
                        onClick = onFirewallClick,
                        keywords = listOf("firewall", "allow", "block", "rules", "wifi", "mobile")
                    ),
                    ConfigureEntry(
                        id = "proxy",
                        title = proxyTitle,
                        iconTint = iconTints.proxy,
                        iconRes = R.drawable.ic_proxy,
                        onClick = onProxyClick,
                        keywords = listOf("proxy", "socks5", "http proxy", "wireguard", "orbot", "tor")
                    )
                )
            )
        )

        add(
            ConfigureSectionModel(
                title = systemTitle,
                accentColor = MaterialTheme.colorScheme.secondary,
                layout = ConfigureSectionLayout.GridPairThenList,
                entries = listOf(
                    ConfigureEntry(
                        id = "network",
                        title = networkTitle,
                        iconTint = iconTints.network,
                        iconRes = R.drawable.ic_network_tunnel,
                        onClick = onNetworkClick,
                        keywords = listOf("network", "vpn", "tunnel", "metered")
                    ),
                    ConfigureEntry(
                        id = "settings",
                        title = settingsTitle,
                        iconTint = iconTints.settings,
                        iconRes = R.drawable.ic_other_settings,
                        onClick = onOthersClick,
                        keywords = listOf("settings", "general", "theme", "appearance", "backup", "restore")
                    ),
                    ConfigureEntry(
                        id = "logs",
                        title = logsTitle,
                        subtitle = stringResource(R.string.settings_enable_logs_desc),
                        iconTint = iconTints.logs,
                        iconRes = R.drawable.ic_logs_accent,
                        onClick = onLogsClick,
                        keywords = listOf("logs", "events", "network logs", "console logs")
                    )
                )
            )
        )

        add(
            ConfigureSectionModel(
                title = advancedTitle,
                accentColor = MaterialTheme.colorScheme.tertiary,
                layout = ConfigureSectionLayout.List,
                entries = buildList {
                    add(
                        ConfigureEntry(
                            id = "anti-censorship",
                            title = antiCensorshipTitle,
                            subtitle = stringResource(R.string.anti_censorship_desc),
                            iconTint = iconTints.antiCensorship,
                            iconRes = R.drawable.ic_anti_dpi,
                            onClick = onAntiCensorshipClick,
                            keywords = listOf("anti censorship", "dpi", "evasion")
                        )
                    )
                    if (isDebug) {
                        add(
                            ConfigureEntry(
                                id = "advanced",
                                title = advancedTitle,
                                subtitle = stringResource(R.string.adv_set_experimental_desc),
                                iconTint = iconTints.advanced,
                                iconRes = R.drawable.ic_advanced_settings,
                                onClick = onAdvancedClick,
                                keywords = listOf("advanced", "experimental", "debug")
                            )
                        )
                    }
                }
            )
        )
    }

    fun openDestination(destination: SettingsSearchDestination) {
        if (onSearchDestinationClick != null) {
            onSearchDestinationClick(destination)
            return
        }

        when (destination) {
            SettingsSearchDestination.Apps -> onAppsClick()
            is SettingsSearchDestination.Dns -> onDnsClick()
            is SettingsSearchDestination.Firewall -> onFirewallClick()
            is SettingsSearchDestination.Proxy -> onProxyClick()
            is SettingsSearchDestination.Network -> onNetworkClick()
            is SettingsSearchDestination.General -> onOthersClick()
            SettingsSearchDestination.Logs -> onLogsClick()
            SettingsSearchDestination.AntiCensorship -> onAntiCensorshipClick()
            SettingsSearchDestination.Advanced -> onAdvancedClick()
        }
    }

    val deepSearchTargets =
        buildSettingsSearchIndex(isDebug = isDebug).map { entry ->
            ConfigureSearchTarget(
                id = entry.id,
                title = entry.title,
                path = entry.path,
                iconRes = entry.iconRes,
                iconTint = iconTintForDestination(entry.destination, iconTints),
                onClick = { openDestination(entry.destination) },
                keywords = buildList {
                    addAll(entry.keywords)
                    add(entry.title)
                    add(entry.subtitle)
                    add(entry.path)
                }
            )
        }

    val topLevelSearchTargets = sections.flatMap { section ->
        section.entries.map { entry ->
            ConfigureSearchTarget(
                id = "top-level.${entry.id}",
                title = entry.title,
                path = "${section.title} > ${entry.title}",
                iconRes = entry.iconRes,
                iconTint = entry.iconTint,
                onClick = entry.onClick,
                keywords = buildList {
                    addAll(entry.keywords)
                    add(section.title)
                    add(entry.title)
                    entry.subtitle?.let { add(it) }
                }
            )
        }
    }

    val normalizedQuery = if (isSearchOpen) query.normalizeSearchQuery() else ""
    val searchTargets = (deepSearchTargets + topLevelSearchTargets).distinctBy { it.id }

    val searchResults = remember(normalizedQuery, searchTargets) {
        if (normalizedQuery.isBlank()) {
            emptyList()
        } else {
            searchTargets
                .mapNotNull { target ->
                    val score = target.searchScore(normalizedQuery)
                    if (score > 0) target to score else null
                }
                .sortedWith(
                    compareByDescending<Pair<ConfigureSearchTarget, Int>> { it.second }
                        .thenBy { it.first.title }
                )
                .map { it.first }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(activeTopBarScrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (isSearchOpen) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(
                            onClick = { closeSearch() }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(id = R.string.configure_search_close),
                            )
                        }
                    },
                    title = {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = Dimensions.spacingSm)
                                .focusRequester(searchFocusRequester),
                            singleLine = true,
                            placeholder = {
                                Text(
                                    text = stringResource(id = R.string.configure_search_hint),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            shape = RoundedCornerShape(Dimensions.cornerRadiusMdLg),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent
                            )
                        )
                    },
                    actions = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(id = R.string.cd_clear_search)
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                    scrollBehavior = searchTopBarScrollBehavior
                )
            } else {
                LargeTopAppBar(
                    title = {
                        Text(
                            text = stringResource(id = R.string.lbl_configure),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    actions = {
                        IconButton(onClick = { isSearchOpen = true }) {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = stringResource(id = R.string.configure_search_open)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                    scrollBehavior = largeTopBarScrollBehavior
                )
            }
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
                top = Dimensions.spacingMd,
                bottom = Dimensions.spacingLg
            ),
            verticalArrangement = Arrangement.spacedBy(Dimensions.spacingLg)
        ) {
            if (normalizedQuery.isBlank()) {
                sections.forEachIndexed { index, section ->
                    item {
                        RethinkAnimatedSection(index = index) {
                            ConfigureSection(section)
                        }
                    }
                }
                item {
                    Spacer(modifier = Modifier.height(Dimensions.spacingSm))
                }
            } else {
                item {
                    if (searchResults.isEmpty()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(Dimensions.cornerRadiusXl),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = stringResource(id = R.string.configure_search_empty_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = stringResource(id = R.string.configure_search_empty_subtitle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        SearchResultsGroup(
                            results = searchResults,
                            query = normalizedQuery,
                            onResultClick = { target ->
                                target.onClick()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigureSection(section: ConfigureSectionModel) {
    Column {
        SectionHeaderWithSubtitle(
            title = section.title,
            subtitle = section.subtitle,
            color = section.accentColor
        )

        Spacer(modifier = Modifier.height(Dimensions.spacingSm))

        when (section.layout) {
            ConfigureSectionLayout.GridFour -> {
                ConfigureGrid(
                    entries = section.entries
                )
            }

            ConfigureSectionLayout.GridPairThenList -> {
                when (section.entries.size) {
                    3 -> {
                        ConfigureTriadGrid(
                            entries = section.entries
                        )
                    }

                    2 -> {
                        ConfigurePairGrid(
                            first = section.entries[0],
                            second = section.entries[1]
                        )
                    }

                    in 4..Int.MAX_VALUE -> {
                        ConfigurePairGrid(
                            first = section.entries[0],
                            second = section.entries[1]
                        )
                        Spacer(modifier = Modifier.height(Dimensions.spacingXs))
                        ConfigureSectionList(
                            entries = section.entries.drop(2)
                        )
                    }

                    else -> {
                        ConfigureSectionList(
                            entries = section.entries
                        )
                    }
                }
            }

            ConfigureSectionLayout.List -> {
                ConfigureSectionList(
                    entries = section.entries
                )
            }
        }
    }
}

@Composable
private fun ConfigureGrid(
    entries: List<ConfigureEntry>
) {
    if (entries.size != 4) return

    Column(verticalArrangement = Arrangement.spacedBy(ConfigureTileGap)) {
        ConfigureTopRowTiles(
            first = entries[0],
            second = entries[1]
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ConfigureTileGap)
        ) {
            ConfigureGridTile(
                entry = entries[2],
                shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 28.dp, bottomEnd = 12.dp),
                modifier = Modifier.weight(1f)
            )
            ConfigureGridTile(
                entry = entries[3],
                shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 12.dp, bottomEnd = 28.dp),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ConfigurePairGrid(
    first: ConfigureEntry,
    second: ConfigureEntry
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ConfigureTileGap)
    ) {
        ConfigureGridTile(
            entry = first,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 12.dp, bottomStart = 28.dp, bottomEnd = 12.dp),
            modifier = Modifier.weight(1f)
        )
        ConfigureGridTile(
            entry = second,
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 28.dp, bottomStart = 12.dp, bottomEnd = 28.dp),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ConfigureTriadGrid(
    entries: List<ConfigureEntry>
) {
    if (entries.size != 3) return

    Column(verticalArrangement = Arrangement.spacedBy(ConfigureTileGap)) {
        ConfigureTopRowTiles(
            first = entries[0],
            second = entries[1]
        )

        ConfigureGridTile(
            entry = entries[2],
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 28.dp, bottomEnd = 28.dp),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ConfigureTopRowTiles(
    first: ConfigureEntry,
    second: ConfigureEntry,
    itemGap: Dp = ConfigureTileGap
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(itemGap)
    ) {
        ConfigureGridTile(
            entry = first,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 12.dp, bottomStart = 12.dp, bottomEnd = 12.dp),
            modifier = Modifier.weight(1f)
        )
        ConfigureGridTile(
            entry = second,
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 28.dp, bottomStart = 12.dp, bottomEnd = 12.dp),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ConfigureSectionList(
    entries: List<ConfigureEntry>
) {
    val iconTint = MaterialTheme.colorScheme.onPrimaryFixed.copy(alpha = 0.8f)

    Column {
        entries.forEachIndexed { index, entry ->
            RethinkListItem(
                headline = entry.title,
                leadingIconPainter = painterResource(id = entry.iconRes),
                leadingIconTint = iconTint,
                leadingIconContainerColor = entry.iconTint,
                position = cardPositionFor(index = index, lastIndex = entries.lastIndex),
                highlightContainerColor = entry.iconTint.copy(alpha = 0.22f),
                showTrailingChevron = false,
                onClick = entry.onClick,
            )
        }
    }
}

@Composable
private fun ConfigureGridTile(
    entry: ConfigureEntry,
    shape: RoundedCornerShape,
    modifier: Modifier = Modifier
) {
    val iconTint = MaterialTheme.colorScheme.onPrimaryFixed.copy(alpha = 0.8f)

    RethinkGridTile(
        title = entry.title,
        iconRes = entry.iconRes,
        accentColor = entry.iconTint,
        shape = shape,
        modifier = modifier,
        iconTint = iconTint,
        iconContainerColor = entry.iconTint,
        onClick = entry.onClick
    )
}

private data class ConfigureIconTints(
    val apps: Color,
    val dns: Color,
    val firewall: Color,
    val proxy: Color,
    val network: Color,
    val settings: Color,
    val logs: Color,
    val antiCensorship: Color,
    val advanced: Color,
)

@Composable
private fun rememberConfigureIconTints(): ConfigureIconTints {
    return ConfigureIconTints(
        apps = Color(0xFF74C5FF),
        dns = Color(0xFFC5ACFF),
        firewall = Color(0xFFFF907F),
        proxy = Color(0xFF46EBC8),
        network = Color(0xFFA3BCFF),
        settings = Color(0xFFFFD878),
        logs = Color(0xFF7EED92),
        antiCensorship = Color(0xFFFFA7E0),
        advanced = Color(0xFFFFE182)
    )
}

private fun iconTintForDestination(
    destination: SettingsSearchDestination,
    iconTints: ConfigureIconTints
): Color {
    return when (destination) {
        SettingsSearchDestination.Apps -> iconTints.apps
        is SettingsSearchDestination.Dns -> iconTints.dns
        is SettingsSearchDestination.Firewall -> iconTints.firewall
        is SettingsSearchDestination.Proxy -> iconTints.proxy
        is SettingsSearchDestination.Network -> iconTints.network
        is SettingsSearchDestination.General -> iconTints.settings
        SettingsSearchDestination.Logs -> iconTints.logs
        SettingsSearchDestination.AntiCensorship -> iconTints.antiCensorship
        SettingsSearchDestination.Advanced -> iconTints.advanced
    }
}

@Composable
private fun SearchResultsGroup(
    results: List<ConfigureSearchTarget>,
    query: String,
    onResultClick: (ConfigureSearchTarget) -> Unit
) {
    val limitedResults = results.take(12)
    val highlightColor = MaterialTheme.colorScheme.primary
    val iconTint = MaterialTheme.colorScheme.onPrimaryFixed.copy(alpha = 0.8f)

    Column {
        limitedResults.forEachIndexed { index, target ->
            val highlightedTitle = remember(target.title, query, highlightColor) {
                target.title.highlightMatches(query = query, highlightColor = highlightColor)
            }
            val highlightedPath = remember(target.path, query, highlightColor) {
                target.path.highlightMatches(query = query, highlightColor = highlightColor)
            }
            RethinkListItem(
                headline = target.title,
                headlineAnnotated = highlightedTitle,
                supporting = target.path,
                supportingAnnotated = highlightedPath,
                leadingIconPainter = painterResource(id = target.iconRes),
                leadingIconTint = iconTint,
                leadingIconContainerColor = target.iconTint,
                position = cardPositionFor(index = index, lastIndex = limitedResults.lastIndex),
                highlightContainerColor = target.iconTint.copy(alpha = 0.22f),
                showTrailingChevron = false,
                onClick = { onResultClick(target) }
            )
        }
    }
}

private fun String.normalizeSearchQuery(): String {
    return lowercase().trim().replace(Regex("\\s+"), " ")
}

private fun ConfigureSearchTarget.searchScore(query: String): Int {
    if (query.isBlank()) return 0

    val titleNorm = title.normalizeSearchQuery()
    val pathNorm = path.normalizeSearchQuery()
    val keywordNorm = keywords.joinToString(" ").normalizeSearchQuery()

    var score = 0
    if (titleNorm.startsWith(query)) score += 10
    if (titleNorm.contains(query)) score += 7
    if (keywordNorm.contains(query)) score += 6
    if (pathNorm.contains(query)) score += 4

    return score
}

private fun String.highlightMatches(query: String, highlightColor: Color): AnnotatedString {
    if (isBlank() || query.isBlank()) return AnnotatedString(this)

    val ranges = mutableListOf<Pair<Int, Int>>()
    val tokens = query.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotBlank() }.distinct()

    tokens.forEach { token ->
        var startIndex = 0
        while (startIndex < length) {
            val index = indexOf(token, startIndex = startIndex, ignoreCase = true)
            if (index < 0) break
            ranges += index to (index + token.length)
            startIndex = index + token.length
        }
    }

    if (ranges.isEmpty()) return AnnotatedString(this)

    val merged = ranges
        .sortedBy { it.first }
        .fold(mutableListOf<Pair<Int, Int>>()) { acc, range ->
            if (acc.isEmpty()) {
                acc += range
                return@fold acc
            }
            val last = acc.last()
            if (range.first <= last.second) {
                acc[acc.lastIndex] = last.first to maxOf(last.second, range.second)
            } else {
                acc += range
            }
            acc
        }

    return buildAnnotatedString {
        append(this@highlightMatches)
        merged.forEach { (start, end) ->
            addStyle(
                style = SpanStyle(color = highlightColor, fontWeight = FontWeight.SemiBold),
                start = start,
                end = end
            )
        }
    }
}
