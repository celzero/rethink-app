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
package com.celzero.bravedns.ui.compose.navigation

import android.graphics.drawable.Drawable
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.asFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.toRoute
import androidx.paging.compose.collectAsLazyPagingItems
import com.celzero.bravedns.R
import com.celzero.bravedns.data.SummaryStatisticsType
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.ui.compose.alerts.AlertsScreen
import com.celzero.bravedns.ui.compose.about.AboutScreen
import com.celzero.bravedns.ui.compose.about.AboutUiState
import com.celzero.bravedns.ui.compose.app.AppInfoScreen
import com.celzero.bravedns.ui.compose.configure.ConfigureScreen
import com.celzero.bravedns.ui.compose.configure.SettingsSearchDestination
import com.celzero.bravedns.ui.compose.events.EventsScreen
import com.celzero.bravedns.ui.compose.firewall.FirewallSettingsScreen
import com.celzero.bravedns.ui.compose.home.HomeScreen
import com.celzero.bravedns.ui.compose.settings.AdvancedSettingsScreen
import com.celzero.bravedns.ui.compose.settings.AntiCensorshipScreen
import com.celzero.bravedns.ui.compose.settings.AppLockScreen
import com.celzero.bravedns.ui.compose.settings.AppLockResult
import com.celzero.bravedns.ui.compose.settings.MiscSettingsScreen
import com.celzero.bravedns.ui.compose.settings.TunnelSettingsScreen
import com.celzero.bravedns.ui.compose.settings.ConsoleLogScreen
import com.celzero.bravedns.ui.compose.settings.ProxySettingsScreen
import com.celzero.bravedns.ui.compose.proxy.TcpProxyMainScreen
import com.celzero.bravedns.ui.compose.logs.NetworkLogsScreen
import com.celzero.bravedns.ui.compose.settings.PingTestScreen
import com.celzero.bravedns.ui.dialog.WgIncludeAppsScreen
import com.celzero.bravedns.ui.compose.logs.AppWiseIpLogsScreen
import com.celzero.bravedns.viewmodel.ProxyAppsMappingViewModel
import com.celzero.bravedns.ui.compose.apps.AppListScreen
import com.celzero.bravedns.ui.compose.firewall.CustomRulesScreen
import com.celzero.bravedns.ui.compose.home.WelcomeScreen
import com.celzero.bravedns.ui.compose.home.HomeScreenUiState
import com.celzero.bravedns.ui.compose.firewall.RulesMode
import com.celzero.bravedns.ui.compose.firewall.RulesTab
import com.celzero.bravedns.ui.compose.wireguard.WgConfigDetailScreen
import com.celzero.bravedns.ui.compose.wireguard.WgConfigEditorScreen
import com.celzero.bravedns.ui.compose.wireguard.WgType
import com.celzero.bravedns.ui.compose.rpn.RpnAvailabilityScreen
import com.celzero.bravedns.ui.compose.rpn.RpnCountriesScreen
import com.celzero.bravedns.ui.compose.rpn.RpnWinProxyDetailsScreen
import com.celzero.bravedns.ui.compose.logs.DomainConnectionsInputType
import com.celzero.bravedns.ui.compose.logs.DomainConnectionsScreen
import com.celzero.bravedns.ui.compose.statistics.DetailedStatisticsScreen
import com.celzero.bravedns.ui.compose.statistics.SummaryStatisticsScreen
import com.celzero.bravedns.ui.compose.database.DatabaseScreen
import com.celzero.bravedns.database.EventDao
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.viewmodel.AppConnectionsViewModel
import com.celzero.bravedns.viewmodel.CustomDomainViewModel
import com.celzero.bravedns.viewmodel.CustomIpViewModel
import com.celzero.bravedns.viewmodel.DomainConnectionsViewModel
import com.celzero.bravedns.viewmodel.DetailedStatisticsViewModel
import com.celzero.bravedns.viewmodel.EventsViewModel
import com.celzero.bravedns.viewmodel.SummaryStatisticsViewModel
import com.celzero.bravedns.viewmodel.ConsoleLogViewModel
import com.celzero.bravedns.database.ConsoleLogRepository
import com.celzero.bravedns.download.AppDownloadManager
import com.celzero.bravedns.ui.compose.dns.ConfigureRethinkBasicScreen
import com.celzero.bravedns.ui.compose.dns.ConfigureRethinkScreenType
import com.celzero.bravedns.ui.compose.dns.DnsDetailScreen
import com.celzero.bravedns.ui.compose.dns.DnsListScreen
import com.celzero.bravedns.ui.compose.dns.DnsSettingsViewModel
import com.celzero.bravedns.viewmodel.LocalBlocklistPacksMapViewModel
import com.celzero.bravedns.viewmodel.RemoteBlocklistPacksMapViewModel
import com.celzero.bravedns.viewmodel.RethinkEndpointViewModel
import com.celzero.bravedns.viewmodel.RethinkLocalFileTagViewModel
import com.celzero.bravedns.viewmodel.RethinkRemoteFileTagViewModel
import com.celzero.bravedns.viewmodel.AppInfoViewModel
import com.celzero.bravedns.database.RefreshDatabase
import com.celzero.bravedns.viewmodel.ConnectionTrackerViewModel
import com.celzero.bravedns.viewmodel.DnsLogViewModel
import com.celzero.bravedns.viewmodel.RethinkLogViewModel
import com.celzero.bravedns.database.ConnectionTrackerRepository
import com.celzero.bravedns.database.DnsLogRepository
import com.celzero.bravedns.database.RethinkLogRepository
import com.celzero.bravedns.ui.compose.dns.ConfigureOtherDnsScreen
import com.celzero.bravedns.ui.compose.dns.DnsScreenType
import com.celzero.bravedns.ui.compose.firewall.UniversalFirewallSettingsScreen
import com.celzero.bravedns.ui.compose.settings.CheckoutScreen
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.viewmodel.DoHEndpointViewModel
import com.celzero.bravedns.viewmodel.DoTEndpointViewModel
import com.celzero.bravedns.viewmodel.DnsProxyEndpointViewModel
import com.celzero.bravedns.viewmodel.DnsCryptEndpointViewModel
import com.celzero.bravedns.viewmodel.DnsCryptRelayEndpointViewModel
import com.celzero.bravedns.viewmodel.ODoHEndpointViewModel
import com.celzero.bravedns.viewmodel.CheckoutViewModel
import com.celzero.bravedns.ui.compose.logs.AppWiseDomainLogsScreen
import com.celzero.bravedns.viewmodel.WgConfigViewModel
import com.celzero.bravedns.ui.compose.wireguard.WgMainScreen
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val BOTTOM_BAR_ENTER_DURATION = 220
private const val BOTTOM_BAR_EXIT_DURATION = 180
private const val NAV_ENTER_DURATION = 240
private const val NAV_EXIT_DURATION = 200

enum class HomeDestination(
    val route: HomeRoute,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    HOME(HomeRoute.Home, R.string.txt_home, Icons.Filled.Home, Icons.Filled.Home),
    STATS(HomeRoute.Stats, R.string.title_statistics, Icons.Filled.Star, Icons.Filled.Star),
    CONFIGURE(HomeRoute.Configure, R.string.lbl_configure, Icons.Filled.Settings, Icons.Filled.Settings),
    ABOUT(HomeRoute.About, R.string.title_about, Icons.Filled.Info, Icons.Filled.Info)
}

sealed interface HomeNavRequest {
    data class DetailedStats(
        val type: SummaryStatisticsType,
        val timeCategory: SummaryStatisticsViewModel.TimeCategory
    ) : HomeNavRequest

    data object Alerts : HomeNavRequest
    data object RpnCountries : HomeNavRequest
    data object RpnAvailability : HomeNavRequest
    data object Events : HomeNavRequest
    data object FirewallSettings : HomeNavRequest
    data object AdvancedSettings : HomeNavRequest
    data object AntiCensorship : HomeNavRequest
    data object TunnelSettings : HomeNavRequest
    data object MiscSettings : HomeNavRequest
    data object ConsoleLogs : HomeNavRequest
    data object NetworkLogs : HomeNavRequest
    data object AppList : HomeNavRequest
    data class CustomRules(
        val uid: Int = UID_EVERYBODY,
        val tab: CustomRulesTab = CustomRulesTab.IP,
        val mode: CustomRulesMode = CustomRulesMode.APP_SPECIFIC
    ) : HomeNavRequest
    data object ProxySettings : HomeNavRequest
    data object TcpProxyMain : HomeNavRequest
    data object Welcome : HomeNavRequest
    data object AppLock : HomeNavRequest
    data object PingTest : HomeNavRequest
    data object DnsDetail : HomeNavRequest
    data class WgConfigDetail(val configId: Int, val wgType: WgType) : HomeNavRequest
    data class WgConfigEditor(val configId: Int, val wgType: WgType) : HomeNavRequest
    data class RpnWinProxyDetails(val countryCode: String) : HomeNavRequest
    data class AppInfo(val uid: Int) : HomeNavRequest
    data class DomainConnections(
        val type: DomainConnectionsInputType,
        val flag: String,
        val domain: String,
        val asn: String,
        val ip: String,
        val isBlocked: Boolean,
        val timeCategory: DomainConnectionsViewModel.TimeCategory
    ) : HomeNavRequest

    data object DnsList : HomeNavRequest
    data class AppWiseIpLogs(val uid: Int, val isAsn: Boolean) : HomeNavRequest
    data class ConfigureRethinkBasic(
        val screenType: ConfigureRethinkScreenType,
        val remoteName: String = "",
        val remoteUrl: String = "",
        val uid: Int = -1
    ) : HomeNavRequest

    data class ConfigureOtherDns(val dnsType: Int) : HomeNavRequest
    data object UniversalFirewallSettings : HomeNavRequest
    data class AppWiseDomainLogs(val uid: Int) : HomeNavRequest
    data object Checkout : HomeNavRequest
    data object WgMain : HomeNavRequest
    data object Database : HomeNavRequest
}

@Composable
fun HomeScreenRoot(
    homeUiState: HomeScreenUiState,
    onHomeStartStopClick: () -> Unit,
    onHomeDnsClick: () -> Unit,
    onHomeFirewallClick: () -> Unit,
    onHomeProxyClick: () -> Unit,
    onHomeLogsClick: () -> Unit,
    onHomeAppsClick: () -> Unit,
    onHomeSponsorClick: () -> Unit,
    summaryViewModel: SummaryStatisticsViewModel,
    onOpenDetailedStats: (SummaryStatisticsType) -> Unit,
    startDestination: HomeRoute,
    isDebug: Boolean,
    onConfigureAppsClick: () -> Unit,
    onConfigureDnsClick: () -> Unit,
    onConfigureFirewallClick: () -> Unit,
    onFirewallUniversalClick: () -> Unit,
    onFirewallCustomIpClick: () -> Unit,
    onFirewallAppWiseIpClick: () -> Unit,
    onConfigureProxyClick: () -> Unit,
    onConfigureNetworkClick: () -> Unit,
    onConfigureOthersClick: () -> Unit,
    onConfigureLogsClick: () -> Unit,
    onConfigureAntiCensorshipClick: () -> Unit,
    onConfigureAdvancedClick: () -> Unit,
    aboutUiState: AboutUiState,
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
    snackbarHostState: SnackbarHostState,
    detailedStatsViewModel: DetailedStatisticsViewModel,
    domainConnectionsViewModel: DomainConnectionsViewModel,
    eventsViewModel: EventsViewModel,
    eventDao: EventDao,
    appInfoEventLogger: EventLogger,
    appInfoIpRulesViewModel: CustomIpViewModel,
    appInfoDomainRulesViewModel: CustomDomainViewModel,
    appInfoNetworkLogsViewModel: AppConnectionsViewModel,
    persistentState: PersistentState,
    appConfig: AppConfig,
    onOpenVpnProfile: () -> Unit,
    onRefreshDatabase: (() -> Unit)? = null,
    onThemeModeChanged: ((Int) -> Unit)? = null,
    onThemeColorChanged: ((Int) -> Unit)? = null,
    consoleLogViewModel: ConsoleLogViewModel,
    consoleLogRepository: ConsoleLogRepository,
    onShareConsoleLogs: () -> Unit,
    onConsoleLogsDeleteComplete: () -> Unit,
    proxyAppsMappingViewModel: ProxyAppsMappingViewModel,
    dnsSettingsViewModel: DnsSettingsViewModel,
    appDownloadManager: AppDownloadManager,
    onDnsCustomDnsClick: () -> Unit,
    onDnsRethinkPlusDnsClick: () -> Unit,
    onDnsLocalBlocklistConfigureClick: () -> Unit,
    homeNavRequest: HomeNavRequest?,
    onHomeNavConsumed: () -> Unit,
    onAppLockResult: (AppLockResult) -> Unit = {},
    // ConfigureRethinkBasic dependencies
    rethinkEndpointViewModel: RethinkEndpointViewModel,
    remoteFileTagViewModel: RethinkRemoteFileTagViewModel,
    localFileTagViewModel: RethinkLocalFileTagViewModel,
    remoteBlocklistPacksMapViewModel: RemoteBlocklistPacksMapViewModel,
    localBlocklistPacksMapViewModel: LocalBlocklistPacksMapViewModel,
    appInfoViewModel: AppInfoViewModel,
    refreshDatabase: RefreshDatabase,
    connectionTrackerViewModel: ConnectionTrackerViewModel,
    dnsLogViewModel: DnsLogViewModel,
    rethinkLogViewModel: RethinkLogViewModel,
    connectionTrackerRepository: ConnectionTrackerRepository,
    dnsLogRepository: DnsLogRepository,
    rethinkLogRepository: RethinkLogRepository,
    onConfigureOtherDns: (Int) -> Unit,
    // ConfigureOtherDns dependencies
    dohViewModel: DoHEndpointViewModel,
    dotViewModel: DoTEndpointViewModel,
    dnsProxyViewModel: DnsProxyEndpointViewModel,
    dnsCryptViewModel: DnsCryptEndpointViewModel,
    dnsCryptRelayViewModel: DnsCryptRelayEndpointViewModel,
    oDohViewModel: ODoHEndpointViewModel,
    // UniversalFirewallSettings callbacks
    onNavigateToLogs: (String) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    // WireGuard dependencies
    wgConfigViewModel: WgConfigViewModel,
    // Checkout dependencies
    checkoutViewModel: CheckoutViewModel?,
    onNavigateToProxy: () -> Unit,
    // WgMain callbacks
    onWgCreateClick: () -> Unit,
    onWgImportClick: () -> Unit,
    onWgQrScanClick: () -> Unit,
    appDatabase: AppDatabase
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val currentHierarchy = currentDestination?.hierarchy.orEmpty()
    val currentRoute = currentDestination?.route
    val topLevelRoutes = remember {
        HomeDestination.entries.mapNotNull { it.route::class.qualifiedName }.toSet()
    }
    val isHomeRoute = currentRoute == HomeRoute.Home::class.qualifiedName
    val isWelcomeRoute = currentRoute == HomeRoute.Welcome::class.qualifiedName
    val showBottomBar =
        !isWelcomeRoute &&
                currentHierarchy.any { it.route != null && topLevelRoutes.contains(it.route) }
    val isWideScreen = LocalConfiguration.current.screenWidthDp >= 840
    val showNavigationRail = showBottomBar && isWideScreen
    val showNavigationBar = showBottomBar && !isWideScreen

    LaunchedEffect(homeNavRequest) {
        val request = homeNavRequest ?: return@LaunchedEffect
        when (request) {
            is HomeNavRequest.DetailedStats -> {
                navController.navigate(HomeRoute.DetailedStats(request.type.tid, request.timeCategory.value))
            }

            HomeNavRequest.Alerts -> {
                navController.navigate(HomeRoute.Alerts)
            }

            HomeNavRequest.RpnCountries -> {
                navController.navigate(HomeRoute.RpnCountries)
            }

            HomeNavRequest.RpnAvailability -> {
                navController.navigate(HomeRoute.RpnAvailability)
            }

            HomeNavRequest.Events -> {
                navController.navigate(HomeRoute.Events)
            }

            HomeNavRequest.FirewallSettings -> {
                navController.navigate(HomeRoute.FirewallSettings())
            }

            HomeNavRequest.AdvancedSettings -> {
                navController.navigate(HomeRoute.AdvancedSettings)
            }

            HomeNavRequest.AntiCensorship -> {
                navController.navigate(HomeRoute.AntiCensorship)
            }

            HomeNavRequest.TunnelSettings -> {
                navController.navigate(HomeRoute.TunnelSettings())
            }

            HomeNavRequest.MiscSettings -> {
                navController.navigate(HomeRoute.MiscSettings())
            }

            HomeNavRequest.ConsoleLogs -> {
                navController.navigate(HomeRoute.ConsoleLogs)
            }

            HomeNavRequest.NetworkLogs -> {
                navController.navigate(HomeRoute.NetworkLogs)
            }

            HomeNavRequest.AppList -> {
                navController.navigate(HomeRoute.AppList)
            }

            is HomeNavRequest.CustomRules -> {
                navController.navigate(
                    HomeRoute.CustomRules(
                        uid = request.uid,
                        tab = request.tab.value,
                        mode = request.mode.value
                    )
                )
            }

            HomeNavRequest.ProxySettings -> {
                navController.navigate(HomeRoute.ProxySettings())
            }

            HomeNavRequest.TcpProxyMain -> {
                navController.navigate(HomeRoute.TcpProxyMain)
            }

            HomeNavRequest.Welcome -> {
                navController.navigate(HomeRoute.Welcome)
            }

            HomeNavRequest.PingTest -> {
                navController.navigate(HomeRoute.PingTest)
            }

            HomeNavRequest.AppLock -> {
                navController.navigate(HomeRoute.AppLock)
            }

            HomeNavRequest.DnsDetail -> {
                navController.navigate(HomeRoute.DnsDetail())
            }

            is HomeNavRequest.RpnWinProxyDetails -> {
                navController.navigate(HomeRoute.RpnWinProxyDetails(request.countryCode))
            }

            is HomeNavRequest.DomainConnections -> {
                navController.navigate(
                    HomeRoute.DomainConnections(
                        typeId = request.type.type,
                        flag = request.flag,
                        domain = request.domain,
                        asn = request.asn,
                        ip = request.ip,
                        isBlocked = request.isBlocked,
                        timeCategory = request.timeCategory.value
                    )
                )
            }

            is HomeNavRequest.AppInfo -> {
                navController.navigate(HomeRoute.AppInfo(request.uid))
            }

            is HomeNavRequest.WgConfigDetail -> {
                navController.navigate(HomeRoute.WgConfigDetail(request.configId, request.wgType))
            }

            is HomeNavRequest.WgConfigEditor -> {
                navController.navigate(HomeRoute.WgConfigEditor(request.configId, request.wgType))
            }

            is HomeNavRequest.ConfigureRethinkBasic -> {
                navController.navigate(
                    HomeRoute.ConfigureRethinkBasic(
                        screenTypeOrdinal = request.screenType.ordinal,
                        remoteName = request.remoteName,
                        remoteUrl = request.remoteUrl,
                        uid = request.uid
                    )
                )
            }

            HomeNavRequest.DnsList -> {
                navController.navigate(HomeRoute.DnsList)
            }

            is HomeNavRequest.AppWiseIpLogs -> {
                navController.navigate(HomeRoute.AppWiseIpLogs(request.uid, request.isAsn))
            }

            is HomeNavRequest.ConfigureOtherDns -> {
                navController.navigate(HomeRoute.ConfigureOtherDns(request.dnsType))
            }

            HomeNavRequest.UniversalFirewallSettings -> {
                navController.navigate(HomeRoute.UniversalFirewallSettings)
            }

            is HomeNavRequest.AppWiseDomainLogs -> {
                navController.navigate(HomeRoute.AppWiseDomainLogs(request.uid))
            }

            HomeNavRequest.Checkout -> {
                navController.navigate(HomeRoute.Checkout)
            }

            HomeNavRequest.WgMain -> {
                navController.navigate(HomeRoute.WgMain)
            }

            HomeNavRequest.Database -> {
                navController.navigate(HomeRoute.Database)
            }
        }
        onHomeNavConsumed()
    }

    BackHandler(enabled = !isHomeRoute) {
        if (isWelcomeRoute) {
            persistentState.firstTimeLaunch = false
            navController.navigate(HomeRoute.Home) {
                popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                launchSingleTop = true
            }
        } else {
            navController.navigate(HomeRoute.Home) {
                popUpTo(navController.graph.findStartDestination().id) { inclusive = false }
                launchSingleTop = true
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            AnimatedVisibility(
                visible = showNavigationBar,
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(
                        durationMillis = BOTTOM_BAR_ENTER_DURATION,
                        easing = FastOutSlowInEasing
                    )
                ) + fadeIn(animationSpec = tween(durationMillis = BOTTOM_BAR_ENTER_DURATION)),
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(
                        durationMillis = BOTTOM_BAR_EXIT_DURATION,
                        easing = FastOutSlowInEasing
                    )
                ) + fadeOut(animationSpec = tween(durationMillis = BOTTOM_BAR_EXIT_DURATION))
            ) {
                NavigationBar(
                    modifier = Modifier.fillMaxWidth(),
                    windowInsets =
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Start + WindowInsetsSides.End + WindowInsetsSides.Bottom
                        )
                ) {
                    HomeDestination.entries.forEach { destination ->
                        val routeName = destination.route::class.qualifiedName
                        val isSelected = currentHierarchy.any { it.route == routeName }
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector =
                                        if (isSelected) destination.selectedIcon else destination.unselectedIcon,
                                    contentDescription = stringResource(id = destination.labelRes)
                                )
                            },
                            label = {
                                Text(
                                    text = stringResource(id = destination.labelRes),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            alwaysShowLabel = true,
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        val navHostModifier = Modifier
            .padding(paddingValues)
            .consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))

        val navHostContent: @Composable (Modifier) -> Unit = { modifier ->
            NavHost(
                navController = navController,
                startDestination = startDestination,
                modifier = modifier,
                enterTransition = {
                    val topLevelTransition =
                        topLevelRoutes.contains(initialState.destination.route) &&
                            topLevelRoutes.contains(targetState.destination.route)
                    if (topLevelTransition) {
                        fadeIn(animationSpec = tween(durationMillis = NAV_EXIT_DURATION))
                    } else {
                        slideIntoContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Left,
                            animationSpec = tween(
                                durationMillis = NAV_ENTER_DURATION,
                                easing = FastOutSlowInEasing
                            )
                        ) + fadeIn(animationSpec = tween(durationMillis = NAV_ENTER_DURATION))
                    }
                },
                exitTransition = {
                    val topLevelTransition =
                        topLevelRoutes.contains(initialState.destination.route) &&
                            topLevelRoutes.contains(targetState.destination.route)
                    if (topLevelTransition) {
                        fadeOut(animationSpec = tween(durationMillis = NAV_EXIT_DURATION))
                    } else {
                        slideOutOfContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Left,
                            animationSpec = tween(
                                durationMillis = NAV_EXIT_DURATION,
                                easing = FastOutSlowInEasing
                            )
                        ) + fadeOut(animationSpec = tween(durationMillis = NAV_EXIT_DURATION))
                    }
                },
                popEnterTransition = {
                    val topLevelTransition =
                        topLevelRoutes.contains(initialState.destination.route) &&
                            topLevelRoutes.contains(targetState.destination.route)
                    if (topLevelTransition) {
                        fadeIn(animationSpec = tween(durationMillis = NAV_EXIT_DURATION))
                    } else {
                        slideIntoContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Right,
                            animationSpec = tween(
                                durationMillis = NAV_ENTER_DURATION,
                                easing = FastOutSlowInEasing
                            )
                        ) + fadeIn(animationSpec = tween(durationMillis = NAV_ENTER_DURATION))
                    }
                },
                popExitTransition = {
                    val topLevelTransition =
                        topLevelRoutes.contains(initialState.destination.route) &&
                            topLevelRoutes.contains(targetState.destination.route)
                    if (topLevelTransition) {
                        fadeOut(animationSpec = tween(durationMillis = NAV_EXIT_DURATION))
                    } else {
                        slideOutOfContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Right,
                            animationSpec = tween(
                                durationMillis = NAV_EXIT_DURATION,
                                easing = FastOutSlowInEasing
                            )
                        ) + fadeOut(animationSpec = tween(durationMillis = NAV_EXIT_DURATION))
                    }
                }
            ) {
            composable<HomeRoute.Home> {
                HomeScreen(
                    uiState = homeUiState,
                    onStartStopClick = onHomeStartStopClick,
                    onDnsClick = onHomeDnsClick,
                    onFirewallClick = onHomeFirewallClick,
                    onProxyClick = onHomeProxyClick,
                    onLogsClick = onHomeLogsClick,
                    onAppsClick = onHomeAppsClick,
                    onSponsorClick = onHomeSponsorClick
                )
            }
            composable<HomeRoute.Stats> {
                SummaryStatisticsScreen(
                    viewModel = summaryViewModel,
                    persistentState = persistentState,
                    onSeeMoreClick = onOpenDetailedStats
                )
            }
            composable<HomeRoute.Alerts> {
                AlertsScreen(onBackClick = { navController.popBackStack() })
            }
            composable<HomeRoute.RpnCountries> {
                RpnCountriesScreen(onBackClick = { navController.popBackStack() })
            }
            composable<HomeRoute.RpnAvailability> {
                RpnAvailabilityScreen(onBackClick = { navController.popBackStack() })
            }
            composable<HomeRoute.Events> {
                EventsScreen(
                    viewModel = eventsViewModel,
                    eventDao = eventDao,
                    onBackClick = { navController.popBackStack() }
                )
            }
            composable<HomeRoute.FirewallSettings> { entry ->
                val args = entry.toRoute<HomeRoute.FirewallSettings>()
                FirewallSettingsScreen(
                    onUniversalFirewallClick = onFirewallUniversalClick,
                    onCustomIpDomainClick = onFirewallCustomIpClick,
                    onAppWiseIpDomainClick = onFirewallAppWiseIpClick,
                    initialFocusKey = args.focusKey.takeIf { it.isNotBlank() },
                    onBackClick = { navController.popBackStack() }
                )
            }
            composable<HomeRoute.AdvancedSettings> {
                AdvancedSettingsScreen(
                    persistentState = persistentState,
                    onBackClick = { navController.popBackStack() }
                )
            }
            composable<HomeRoute.AntiCensorship> {
                AntiCensorshipScreen(
                    persistentState = persistentState,
                    eventLogger = appInfoEventLogger,
                    onBackClick = { navController.popBackStack() }
                )
            }
            composable<HomeRoute.TunnelSettings> { entry ->
                val args = entry.toRoute<HomeRoute.TunnelSettings>()
                TunnelSettingsScreen(
                    persistentState = persistentState,
                    appConfig = appConfig,
                    eventLogger = appInfoEventLogger,
                    onOpenVpnProfile = onOpenVpnProfile,
                    initialFocusKey = args.focusKey.takeIf { it.isNotBlank() },
                    onBackClick = { navController.popBackStack() }
                )
            }
            composable<HomeRoute.MiscSettings> { entry ->
                val args = entry.toRoute<HomeRoute.MiscSettings>()
                MiscSettingsScreen(
                    persistentState = persistentState,
                    eventLogger = appInfoEventLogger,
                    initialFocusKey = args.focusKey.takeIf { it.isNotBlank() },
                    onBackClick = { navController.popBackStack() },
                    onRefreshDatabase = onRefreshDatabase,
                    onThemeModeChanged = onThemeModeChanged,
                    onThemeColorChanged = onThemeColorChanged
                )
            }
            composable<HomeRoute.PingTest> {
                PingTestScreen(
                    onBackClick = { navController.popBackStack() }
                )
            }
            composable<HomeRoute.ConsoleLogs> {
                ConsoleLogScreen(
                    viewModel = consoleLogViewModel,
                    consoleLogRepository = consoleLogRepository,
                    persistentState = persistentState,
                    onShareClick = onShareConsoleLogs,
                    onDeleteComplete = onConsoleLogsDeleteComplete,
                    onBackClick = { navController.popBackStack() }
                )
            }
            composable<HomeRoute.NetworkLogs> {
                NetworkLogsScreen(
                    connectionTrackerViewModel = connectionTrackerViewModel,
                    dnsLogViewModel = dnsLogViewModel,
                    rethinkLogViewModel = rethinkLogViewModel,
                    connectionTrackerRepository = connectionTrackerRepository,
                    dnsLogRepository = dnsLogRepository,
                    rethinkLogRepository = rethinkLogRepository,
                    persistentState = persistentState,
                    eventLogger = appInfoEventLogger,
                    onBackClick = { navController.popBackStack() }
                )
            }

            composable<HomeRoute.AppList> {
                AppListScreen(
                    viewModel = appInfoViewModel,
                    eventLogger = appInfoEventLogger,
                    refreshDatabase = refreshDatabase,
                    onAppClick = { uid -> navController.navigate(HomeRoute.AppInfo(uid)) },
                    onBackClick = { navController.popBackStack() }
                )
            }

            composable<HomeRoute.CustomRules> { entry ->
                val args = entry.toRoute<HomeRoute.CustomRules>()
                CustomRulesScreen(
                    uid = args.uid,
                    initialTab = RulesTab.fromValue(args.tab),
                    initialMode = RulesMode.fromValue(args.mode),
                    domainViewModel = appInfoDomainRulesViewModel,
                    ipViewModel = appInfoIpRulesViewModel,
                    eventLogger = appInfoEventLogger,
                    onBackClick = { navController.popBackStack() }
                )
            }

            composable<HomeRoute.ProxySettings> { entry ->
                val args = entry.toRoute<HomeRoute.ProxySettings>()
                ProxySettingsScreen(
                    appConfig = appConfig,
                    persistentState = persistentState,
                    eventLogger = appInfoEventLogger,
                    mappingViewModel = proxyAppsMappingViewModel,
                    initialFocusKey = args.focusKey.takeIf { it.isNotBlank() },
                    onWireguardClick = { navController.navigate(HomeRoute.WgMain) },
                    onOpenOrbotApps = {
                        navController.navigate(HomeRoute.OrbotAppSelect) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateToDns = { navController.navigate(HomeRoute.DnsDetail()) },
                    onBackClick = { navController.popBackStack() }
                )
            }

            composable<HomeRoute.OrbotAppSelect> {
                WgIncludeAppsScreen(
                    viewModel = proxyAppsMappingViewModel,
                    proxyId = ProxyManager.ID_ORBOT_BASE,
                    proxyName = ProxyManager.ORBOT_PROXY_NAME,
                    onDismiss = { navController.popBackStack() }
                )
            }

            composable<HomeRoute.TcpProxyMain> {
                TcpProxyMainScreen(
                    appConfig = appConfig,
                    mappingViewModel = proxyAppsMappingViewModel,
                    onBackClick = { navController.popBackStack() }
                )
            }
            composable<HomeRoute.Welcome> {
                WelcomeScreen(
                    onFinish = {
                        persistentState.firstTimeLaunch = false
                        navController.navigate(HomeRoute.Home) {
                            popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable<HomeRoute.AppLock> {
                AppLockScreen(
                    persistentState = persistentState,
                    onAuthResult = { result ->
                        onAppLockResult(result)
                        navController.popBackStack()
                    }
                )
            }
            composable<HomeRoute.DnsDetail> { entry ->
                val args = entry.toRoute<HomeRoute.DnsDetail>()
                DnsDetailScreen(
                    viewModel = dnsSettingsViewModel,
                    persistentState = persistentState,
                    appDownloadManager = appDownloadManager,
                    initialFocusKey = args.focusKey.takeIf { it.isNotBlank() },
                    onCustomDnsClick = onDnsCustomDnsClick,
                    onRethinkPlusDnsClick = onDnsRethinkPlusDnsClick,
                    onLocalBlocklistConfigureClick = onDnsLocalBlocklistConfigureClick,
                    onBackClick = { navController.popBackStack() }
                )
            }
            composable<HomeRoute.AppInfo> { entry ->
                val args = entry.toRoute<HomeRoute.AppInfo>()
                AppInfoScreen(
                    uid = args.uid,
                    eventLogger = appInfoEventLogger,
                    ipRulesViewModel = appInfoIpRulesViewModel,
                    domainRulesViewModel = appInfoDomainRulesViewModel,
                    networkLogsViewModel = appInfoNetworkLogsViewModel,
                    onBackClick = { navController.popBackStack() },
                    onAppWiseIpLogsClick = { u, isAsn ->
                        navController.navigate(HomeRoute.AppWiseIpLogs(u, isAsn))
                    },
                    onCustomIpRulesClick = { u ->
                        navController.navigate(
                            HomeRoute.CustomRules(
                                uid = u,
                                tab = CustomRulesTab.IP.value,
                                mode = CustomRulesMode.APP_SPECIFIC.value
                            )
                        )
                    },
                    onCustomDomainRulesClick = { u ->
                        navController.navigate(
                            HomeRoute.CustomRules(
                                uid = u,
                                tab = CustomRulesTab.DOMAIN.value,
                                mode = CustomRulesMode.APP_SPECIFIC.value
                            )
                        )
                    }
                )
            }
            composable<HomeRoute.DnsList> {
                DnsListScreen(
                    appConfig = appConfig,
                    onConfigureOtherDns = onConfigureOtherDns,
                    onConfigureRethinkBasic = { type ->
                        navController.navigate(
                            HomeRoute.ConfigureRethinkBasic(
                                screenTypeOrdinal = ConfigureRethinkScreenType.entries[type].ordinal,
                                remoteName = "",
                                remoteUrl = "",
                                uid = -1
                            )
                        )
                    },
                    onBackClick = { navController.popBackStack() }
                )
            }
            composable<HomeRoute.AppWiseIpLogs> { entry ->
                val args = entry.toRoute<HomeRoute.AppWiseIpLogs>()
                AppWiseIpLogsScreen(
                    uid = args.uid,
                    isAsn = args.isAsn,
                    viewModel = appInfoNetworkLogsViewModel,
                    eventLogger = appInfoEventLogger,
                    onBackClick = { navController.popBackStack() }
                )
            }

            composable<HomeRoute.RpnWinProxyDetails> { entry ->
                val args = entry.toRoute<HomeRoute.RpnWinProxyDetails>()
                RpnWinProxyDetailsScreen(
                    countryCode = args.countryCode,
                    onBackClick = { navController.popBackStack() }
                )
            }

            composable<HomeRoute.DomainConnections> { entry ->
                val args = entry.toRoute<HomeRoute.DomainConnections>()
                val timeCategory = DomainConnectionsViewModel.TimeCategory.fromValue(args.timeCategory)
                    ?: DomainConnectionsViewModel.TimeCategory.ONE_HOUR
                val type = DomainConnectionsInputType.fromValue(args.typeId)

                DomainConnectionsScreen(
                    viewModel = domainConnectionsViewModel,
                    type = type,
                    flag = args.flag,
                    domain = args.domain,
                    asn = args.asn,
                    ip = args.ip,
                    isBlocked = args.isBlocked,
                    timeCategory = timeCategory,
                    onBackClick = { navController.popBackStack() }
                )
            }

            composable<HomeRoute.DetailedStats> { entry ->
                val args = entry.toRoute<HomeRoute.DetailedStats>()
                val type = SummaryStatisticsType.getType(args.typeId)
                val timeCategory = SummaryStatisticsViewModel.TimeCategory.fromValue(args.timeCategory)

                DetailedStatisticsScreen(
                    type = type,
                    timeCategory = timeCategory ?: SummaryStatisticsViewModel.TimeCategory.TWENTY_FOUR_HOUR,
                    viewModel = detailedStatsViewModel,
                    onBackClick = { navController.popBackStack() }
                )
            }

            composable<HomeRoute.WgConfigDetail> { entry ->
                val args = entry.toRoute<HomeRoute.WgConfigDetail>()
                WgConfigDetailScreen(
                    configId = args.configId,
                    wgType = args.wgType,
                    persistentState = persistentState,
                    eventLogger = appInfoEventLogger,
                    mappingViewModel = proxyAppsMappingViewModel,
                    onEditConfig = { id, type ->
                        navController.navigate(HomeRoute.WgConfigEditor(id, type))
                    },
                    onBackClick = { navController.popBackStack() }
                )
            }

            composable<HomeRoute.WgConfigEditor> { entry ->
                val args = entry.toRoute<HomeRoute.WgConfigEditor>()
                WgConfigEditorScreen(
                    configId = args.configId,
                    wgType = args.wgType,
                    persistentState = persistentState,
                    onBackClick = { navController.popBackStack() },
                    onSaveSuccess = { navController.popBackStack() }
                )
            }
            composable<HomeRoute.Configure> {
                ConfigureScreen(
                    isDebug = isDebug,
                    onAppsClick = onConfigureAppsClick,
                    onDnsClick = onConfigureDnsClick,
                    onFirewallClick = onConfigureFirewallClick,
                    onProxyClick = onConfigureProxyClick,
                    onNetworkClick = onConfigureNetworkClick,
                    onOthersClick = onConfigureOthersClick,
                    onLogsClick = onConfigureLogsClick,
                    onAntiCensorshipClick = onConfigureAntiCensorshipClick,
                    onAdvancedClick = onConfigureAdvancedClick,
                    onSearchDestinationClick = { destination ->
                        when (destination) {
                            SettingsSearchDestination.Apps -> navController.navigate(HomeRoute.AppList)
                            is SettingsSearchDestination.Dns -> navController.navigate(
                                HomeRoute.DnsDetail(destination.focusKey)
                            )
                            is SettingsSearchDestination.Firewall -> navController.navigate(
                                HomeRoute.FirewallSettings(destination.focusKey)
                            )
                            is SettingsSearchDestination.Proxy -> navController.navigate(
                                HomeRoute.ProxySettings(destination.focusKey)
                            )
                            is SettingsSearchDestination.Network -> navController.navigate(
                                HomeRoute.TunnelSettings(destination.focusKey)
                            )
                            is SettingsSearchDestination.General -> navController.navigate(
                                HomeRoute.MiscSettings(destination.focusKey)
                            )
                            SettingsSearchDestination.Logs -> navController.navigate(HomeRoute.NetworkLogs)
                            SettingsSearchDestination.AntiCensorship -> navController.navigate(HomeRoute.AntiCensorship)
                            SettingsSearchDestination.Advanced -> navController.navigate(HomeRoute.AdvancedSettings)
                        }
                    }
                )
            }
            composable<HomeRoute.About> {
                AboutScreen(
                    uiState = aboutUiState,
                    onSponsorClick = onSponsorClick,
                    onTelegramClick = onTelegramClick,
                    onBugReportClick = onBugReportClick,
                    onWhatsNewClick = onWhatsNewClick,
                    onAppUpdateClick = onAppUpdateClick,
                    onContributorsClick = onContributorsClick,
                    onTranslateClick = onTranslateClick,
                    onWebsiteClick = onWebsiteClick,
                    onGithubClick = onGithubClick,
                    onFaqClick = onFaqClick,
                    onDocsClick = onDocsClick,
                    onPrivacyPolicyClick = onPrivacyPolicyClick,
                    onTermsOfServiceClick = onTermsOfServiceClick,
                    onLicenseClick = onLicenseClick,
                    onTwitterClick = onTwitterClick,
                    onEmailClick = onEmailClick,
                    onRedditClick = onRedditClick,
                    onElementClick = onElementClick,
                    onMastodonClick = onMastodonClick,
                    onGeneralSettingsClick = onGeneralSettingsClick,
                    onAppInfoClick = onAppInfoClick,
                    onVpnProfileClick = onVpnProfileClick,
                    onNotificationClick = onNotificationClick,
                    onStatsClick = onStatsClick,
                    onDbStatsClick = onDbStatsClick,
                    onFlightRecordClick = onFlightRecordClick,
                    onEventLogsClick = onEventLogsClick,
                    onTokenClick = onTokenClick,
                    onTokenDoubleTap = onTokenDoubleTap,
                    onFossClick = onFossClick,
                    onFlossFundsClick = onFlossFundsClick,
                    persistentState = persistentState,
                    onThemeModeChanged = onThemeModeChanged,
                    onThemeColorChanged = onThemeColorChanged
                )
            }
            composable<HomeRoute.ConfigureRethinkBasic> { entry ->
                val args = entry.toRoute<HomeRoute.ConfigureRethinkBasic>()
                val screenType = ConfigureRethinkScreenType.entries.getOrElse(args.screenTypeOrdinal) {
                    ConfigureRethinkScreenType.REMOTE
                }
                ConfigureRethinkBasicScreen(
                    screenType = screenType,
                    uid = args.uid,
                    persistentState = persistentState,
                    appConfig = appConfig,
                    appDownloadManager = appDownloadManager,
                    rethinkEndpointViewModel = rethinkEndpointViewModel,
                    remoteFileTagViewModel = remoteFileTagViewModel,
                    localFileTagViewModel = localFileTagViewModel,
                    remoteBlocklistPacksMapViewModel = remoteBlocklistPacksMapViewModel,
                    localBlocklistPacksMapViewModel = localBlocklistPacksMapViewModel,
                    onBackClick = { navController.popBackStack() }
                )
            }
            composable<HomeRoute.ConfigureOtherDns> { entry ->
                val args = entry.toRoute<HomeRoute.ConfigureOtherDns>()
                ConfigureOtherDnsScreen(
                    dnsType = DnsScreenType.fromIndex(args.dnsType),
                    appConfig = appConfig,
                    persistentState = persistentState,
                    dohViewModel = dohViewModel,
                    dotViewModel = dotViewModel,
                    dnsProxyViewModel = dnsProxyViewModel,
                    dnsCryptViewModel = dnsCryptViewModel,
                    dnsCryptRelayViewModel = dnsCryptRelayViewModel,
                    oDohViewModel = oDohViewModel,
                    onBackClick = { navController.popBackStack() }
                )
            }
            composable<HomeRoute.UniversalFirewallSettings> {
                UniversalFirewallSettingsScreen(
                    persistentState = persistentState,
                    eventLogger = appInfoEventLogger,
                    connTrackerRepository = connectionTrackerRepository,
                    onNavigateToLogs = onNavigateToLogs,
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                    onBackClick = { navController.popBackStack() }
                )
            }
            composable<HomeRoute.Checkout> {
                val currentCheckoutViewModel = checkoutViewModel
                if (currentCheckoutViewModel == null) {
                    Text(text = stringResource(id = R.string.checkout_unavailable_desc))
                } else {
                    val paymentStatus by currentCheckoutViewModel.paymentStatus.collectAsStateWithLifecycle()
                    val workInfoList by currentCheckoutViewModel.paymentWorkInfo
                        .asFlow()
                        .collectAsStateWithLifecycle(initialValue = emptyList())

                    LaunchedEffect(workInfoList) {
                        currentCheckoutViewModel.updatePaymentStatusFromWorkInfo(workInfoList)
                    }

                    CheckoutScreen(
                        paymentStatus = paymentStatus,
                        onStartPayment = { currentCheckoutViewModel.startPayment() },
                        onNavigateToProxy = onNavigateToProxy,
                        onBackClick = { navController.popBackStack() }
                    )
                }
            }
            composable<HomeRoute.AppWiseDomainLogs> { entry ->
                val args = entry.toRoute<HomeRoute.AppWiseDomainLogs>()
                AppWiseDomainLogsScreen(
                    uid = args.uid,
                    viewModel = appInfoNetworkLogsViewModel,
                    eventLogger = appInfoEventLogger,
                    onBackClick = { navController.popBackStack() }
                )
            }
            composable<HomeRoute.WgMain> {
                WgMainScreen(
                    wgConfigViewModel = wgConfigViewModel,
                    persistentState = persistentState,
                    appConfig = appConfig,
                    eventLogger = appInfoEventLogger,
                    onBackClick = { navController.popBackStack() },
                    onCreateClick = onWgCreateClick,
                    onImportClick = onWgImportClick,
                    onQrScanClick = onWgQrScanClick,
                    onConfigDetailClick = { configId, wgType ->
                        navController.navigate(HomeRoute.WgConfigDetail(configId, wgType))
                    }
                )
            }
            composable<HomeRoute.Database> {
                DatabaseScreen(
                    onBackClick = { navController.popBackStack() },
                    appDatabase = appDatabase
                )
            }
            }
        }

        if (showNavigationRail) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .consumeWindowInsets(paddingValues)
            ) {
                NavigationRail(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(88.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Start)
                ) {
                    HomeDestination.entries.forEach { destination ->
                        val routeName = destination.route::class.qualifiedName
                        val isSelected = currentHierarchy.any { it.route == routeName }
                        NavigationRailItem(
                            selected = isSelected,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = if (isSelected) destination.selectedIcon else destination.unselectedIcon,
                                    contentDescription = stringResource(id = destination.labelRes)
                                )
                            },
                            label = {
                                Text(
                                    text = stringResource(id = destination.labelRes),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            alwaysShowLabel = true,
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }

                navHostContent(Modifier.weight(1f))
            }
        } else {
            navHostContent(navHostModifier)
        }
    }
}
