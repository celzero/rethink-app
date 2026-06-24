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
package com.celzero.bravedns.ui


import Logger
import Logger.LOG_TAG_APP_UPDATE
import Logger.LOG_TAG_BACKUP_RESTORE
import Logger.LOG_TAG_DOWNLOAD
import Logger.LOG_TAG_UI
import Logger.LOG_TAG_VPN
import android.Manifest
import android.app.UiModeManager
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.content.res.Configuration
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.database.Cursor
import android.net.VpnService
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import com.celzero.bravedns.BuildConfig
import com.celzero.bravedns.NonStoreAppUpdater
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.backup.BackupHelper
import com.celzero.bravedns.backup.BackupHelper.Companion.BACKUP_FILE_EXTN
import com.celzero.bravedns.backup.BackupHelper.Companion.INTENT_RESTART_APP
import com.celzero.bravedns.backup.BackupHelper.Companion.INTENT_SCHEME
import com.celzero.bravedns.backup.RestoreAgent
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.data.SummaryStatisticsType
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.database.AppInfoRepository
import com.celzero.bravedns.database.EventDao
import com.celzero.bravedns.database.RefreshDatabase
import com.celzero.bravedns.scheduler.BugReportZipper
import com.celzero.bravedns.scheduler.EnhancedBugReport
import com.celzero.bravedns.scheduler.WorkScheduler
import com.celzero.bravedns.service.AppUpdater
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.RethinkBlocklistManager
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.WireguardManager

import com.celzero.bravedns.ui.compose.dns.ConfigureRethinkScreenType
import com.celzero.bravedns.ui.compose.navigation.HomeNavRequest
import com.celzero.bravedns.ui.compose.navigation.CustomRulesMode
import com.celzero.bravedns.ui.compose.navigation.CustomRulesTab
import com.celzero.bravedns.ui.compose.navigation.HomeRoute
import com.celzero.bravedns.ui.compose.wireguard.WgType
import com.celzero.bravedns.ui.compose.navigation.HomeScreenRoot
import com.celzero.bravedns.ui.compose.theme.CardPosition
import com.celzero.bravedns.ui.compose.theme.Dimensions
import com.celzero.bravedns.ui.compose.theme.RethinkConfirmDialog
import com.celzero.bravedns.ui.compose.theme.RethinkModalBottomSheet
import com.celzero.bravedns.ui.compose.theme.RethinkListItem
import com.celzero.bravedns.ui.compose.theme.RethinkTheme
import com.celzero.bravedns.ui.compose.theme.RethinkColorPreset
import com.celzero.bravedns.ui.compose.theme.cardPositionFor
import com.celzero.bravedns.ui.compose.settings.AppLockScreen
import com.celzero.bravedns.ui.compose.settings.AppLockResult
import com.celzero.bravedns.ui.compose.home.PauseScreen
import com.celzero.bravedns.util.BioMetricType
import androidx.lifecycle.asFlow
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.MAX_ENDPOINT
import com.celzero.bravedns.util.Constants.Companion.PKG_NAME_PLAY_STORE
import com.celzero.bravedns.util.Constants.Companion.RETHINKDNS_SPONSOR_LINK
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import com.celzero.bravedns.viewmodel.SummaryStatisticsViewModel
import com.celzero.bravedns.util.FirebaseErrorReporting
import com.celzero.bravedns.util.FirebaseErrorReporting.TOKEN_LENGTH
import com.celzero.bravedns.util.FirebaseErrorReporting.TOKEN_REGENERATION_PERIOD_DAYS
import com.celzero.bravedns.util.NewSettingsManager
import com.celzero.bravedns.util.RemoteFileTagUtil
import com.celzero.bravedns.util.UIUtils
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.celzero.bravedns.util.QrCodeFromFileScanner
import com.celzero.bravedns.util.TunnelImporter
import com.google.zxing.qrcode.QRCodeReader
import com.celzero.bravedns.util.UIUtils.openNetworkSettings
import com.celzero.bravedns.util.UIUtils.openUrl
import com.celzero.bravedns.util.UIUtils.openVpnProfile
import com.celzero.bravedns.util.UIUtils.sendEmailIntent
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.getPackageMetadata
import com.celzero.bravedns.util.Utilities.getRandomString
import com.celzero.bravedns.util.Utilities.isAtleastO_MR1
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.Utilities.isPlayStoreFlavour
import com.celzero.bravedns.util.Utilities.isWebsiteFlavour
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.celzero.bravedns.util.disableFrostTemporarily
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import com.celzero.bravedns.viewmodel.AppConnectionsViewModel
import com.celzero.bravedns.viewmodel.CustomDomainViewModel
import com.celzero.bravedns.viewmodel.CustomIpViewModel
import com.celzero.bravedns.viewmodel.CheckoutViewModel
import com.celzero.bravedns.viewmodel.DomainConnectionsViewModel
import com.celzero.bravedns.viewmodel.EventsViewModel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class HomeScreenActivity : AppCompatActivity() {
    private val persistentState by inject<PersistentState>()
    private val appInfoDb by inject<AppInfoRepository>()
    private val appUpdateManager by inject<AppUpdater>()
    private val rdb by inject<RefreshDatabase>()
    private val appConfig by inject<AppConfig>()
    private val workScheduler by inject<WorkScheduler>()
    private val appDatabase by inject<AppDatabase>()
    private val eventDao by inject<EventDao>()
    private val eventLogger by inject<EventLogger>()

    private val homeViewModel by viewModel<com.celzero.bravedns.ui.compose.home.HomeScreenViewModel>()
    private val summaryViewModel by viewModel<com.celzero.bravedns.viewmodel.SummaryStatisticsViewModel>()
    private val aboutViewModel by viewModel<com.celzero.bravedns.ui.compose.about.AboutViewModel>()
    private val detailedStatsViewModel by viewModel<com.celzero.bravedns.viewmodel.DetailedStatisticsViewModel>()
    private val domainConnectionsViewModel by viewModel<DomainConnectionsViewModel>()
    private val eventsViewModel by viewModel<EventsViewModel>()
    private val appInfoIpRulesViewModel by viewModel<CustomIpViewModel>()
    private val appInfoDomainRulesViewModel by viewModel<CustomDomainViewModel>()
    private val appInfoNetworkLogsViewModel by viewModel<AppConnectionsViewModel>()
    private val consoleLogViewModel by inject<com.celzero.bravedns.viewmodel.ConsoleLogViewModel>()
    private val consoleLogRepository by inject<com.celzero.bravedns.database.ConsoleLogRepository>()
    private val proxyAppsMappingViewModel by viewModel<com.celzero.bravedns.viewmodel.ProxyAppsMappingViewModel>()
    private val dnsSettingsViewModel by viewModel<com.celzero.bravedns.ui.compose.dns.DnsSettingsViewModel>()
    private val appDownloadManager by inject<com.celzero.bravedns.download.AppDownloadManager>()
    private val rethinkEndpointViewModel by viewModel<com.celzero.bravedns.viewmodel.RethinkEndpointViewModel>()
    private val remoteFileTagViewModel by viewModel<com.celzero.bravedns.viewmodel.RethinkRemoteFileTagViewModel>()
    private val localFileTagViewModel by viewModel<com.celzero.bravedns.viewmodel.RethinkLocalFileTagViewModel>()
    private val remoteBlocklistPacksMapViewModel by viewModel<com.celzero.bravedns.viewmodel.RemoteBlocklistPacksMapViewModel>()
    private val localBlocklistPacksMapViewModel by viewModel<com.celzero.bravedns.viewmodel.LocalBlocklistPacksMapViewModel>()
    private val appInfoViewModel by viewModel<com.celzero.bravedns.viewmodel.AppInfoViewModel>()
    private val connectionTrackerViewModel by viewModel<com.celzero.bravedns.viewmodel.ConnectionTrackerViewModel>()
    private val dnsLogViewModel by viewModel<com.celzero.bravedns.viewmodel.DnsLogViewModel>()
    private val rethinkLogViewModel by viewModel<com.celzero.bravedns.viewmodel.RethinkLogViewModel>()
    private val connectionTrackerRepository by inject<com.celzero.bravedns.database.ConnectionTrackerRepository>()
    private val dnsLogRepository by inject<com.celzero.bravedns.database.DnsLogRepository>()
    private val rethinkLogRepository by inject<com.celzero.bravedns.database.RethinkLogRepository>()

    // ConfigureOtherDns ViewModels
    private val dohViewModel by viewModel<com.celzero.bravedns.viewmodel.DoHEndpointViewModel>()
    private val dotViewModel by viewModel<com.celzero.bravedns.viewmodel.DoTEndpointViewModel>()
    private val dnsProxyViewModel by viewModel<com.celzero.bravedns.viewmodel.DnsProxyEndpointViewModel>()
    private val dnsCryptViewModel by viewModel<com.celzero.bravedns.viewmodel.DnsCryptEndpointViewModel>()
    private val dnsCryptRelayViewModel by viewModel<com.celzero.bravedns.viewmodel.DnsCryptRelayEndpointViewModel>()
    private val oDohViewModel by viewModel<com.celzero.bravedns.viewmodel.ODoHEndpointViewModel>()
    private val checkoutViewModel: CheckoutViewModel? by lazy {
        runCatching { get<CheckoutViewModel>() }.getOrNull()
    }
    private val wgConfigViewModel by viewModel<com.celzero.bravedns.viewmodel.WgConfigViewModel>()


    // TODO: see if this can be replaced with a more robust solution
    // keep track of when app went to background
    private var appInBackground = false
    private var showBugReportSheet by mutableStateOf(false)
    private var homeDialogState by mutableStateOf<HomeDialog?>(null)
    private var snackbarHostState: SnackbarHostState? = null
    private var homeNavRequest by mutableStateOf<HomeNavRequest?>(null)

    private lateinit var startForResult: androidx.activity.result.ActivityResultLauncher<Intent>
    private lateinit var notificationPermissionResult: androidx.activity.result.ActivityResultLauncher<String>

    // WireGuard Import Launchers
    private val tunnelFileImportResultLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { data ->
            if (data == null) return@registerForActivityResult
            val contentResolver = contentResolver ?: return@registerForActivityResult
            lifecycleScope.launch {
                if (QrCodeFromFileScanner.validContentType(contentResolver, data)) {
                    try {
                        val qrCodeFromFileScanner =
                            QrCodeFromFileScanner(contentResolver, QRCodeReader())
                        val result = qrCodeFromFileScanner.scan(data)
                        if (result != null) {
                            withContext(Dispatchers.Main) {
                                TunnelImporter.importTunnel(result.text) {
                                    showToastUiCentered(
                                        this@HomeScreenActivity,
                                        it.toString(),
                                        Toast.LENGTH_LONG
                                    )
                                }
                            }
                        } else {
                            val message = resources.getString(R.string.invalid_file_error)
                            showToastUiCentered(this@HomeScreenActivity, message, Toast.LENGTH_LONG)
                        }
                    } catch (e: Exception) {
                        val message = resources.getString(R.string.invalid_file_error)
                        showToastUiCentered(this@HomeScreenActivity, message, Toast.LENGTH_LONG)
                    }
                } else {
                    TunnelImporter.importTunnel(contentResolver, data) {
                        showToastUiCentered(
                            this@HomeScreenActivity,
                            it.toString(),
                            Toast.LENGTH_LONG
                        )
                    }
                }
            }
        }

    private val qrImportResultLauncher =
        registerForActivityResult(ScanContract()) { result ->
            val qrCode = result.contents
            if (qrCode != null) {
                lifecycleScope.launch {
                    TunnelImporter.importTunnel(qrCode) {
                        showToastUiCentered(
                            this@HomeScreenActivity,
                            it.toString(),
                            Toast.LENGTH_LONG
                        )
                    }
                }
            }
        }

    // TODO - #324 - Usage of isDarkTheme() in all activities.
    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                lightScrim = 0x00000000,
                darkScrim = 0x00000000
            ),
            navigationBarStyle = SystemBarStyle.auto(
                lightScrim = 0x00000000,
                darkScrim = 0x00000000
            )
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.clearFlags(
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                    WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
        )
        window.navigationBarDividerColor = AndroidColor.TRANSPARENT
        window.isNavigationBarContrastEnforced = false
        window.isStatusBarContrastEnforced = false


        val resolvedThemePreference =
            Themes.resolveThemePreference(isDarkThemeOn(), persistentState.theme)

        val homeStartDestination =
            if (persistentState.firstTimeLaunch && !isAppRunningOnTv()) {
                HomeRoute.Welcome
            } else {
                HomeRoute.Home
            }

        handleFrostEffectIfNeeded(resolvedThemePreference)

        registerForActivityResult()

        updateNewVersion()

        // handle intent receiver for backup/restore
        handleIntent()
        handleNavigationIntent(intent)

        initUpdateCheck()

        observeAppState()

        NewSettingsManager.handleNewSettings()

        regenerateFirebaseTokenIfNeeded()

        appConfig.getBraveModeObservable().postValue(appConfig.getBraveMode().mode)

        setContent {
            var composeThemePreference by remember { mutableStateOf(persistentState.theme) }
            var composeThemeColorPreset by remember { mutableStateOf(persistentState.themeColorPreset) }

            val colorPreset = RethinkColorPreset.fromId(composeThemeColorPreset)
            RethinkTheme(
                themePreference = composeThemePreference,
                colorPreset = colorPreset
            ) {
                val hostState = remember { SnackbarHostState() }
                DisposableEffect(hostState) {
                    snackbarHostState = hostState
                    onDispose {
                        if (snackbarHostState == hostState) {
                            snackbarHostState = null
                        }
                    }
                }
                val homeState by homeViewModel.uiState.collectAsStateWithLifecycle()
                val aboutState by aboutViewModel.uiState.collectAsStateWithLifecycle()

                var isUnlocked by remember { mutableStateOf(false) }
                val vpnState by remember { VpnController.connectionStatus.asFlow() }.collectAsStateWithLifecycle(
                    initialValue = null
                )

                if (vpnState == BraveVPNService.State.PAUSED) {
                    PauseScreen(onFinish = { })
                } else if (isUnlocked) {
                    HomeScreenRoot(
                        homeUiState = homeState,
                        onHomeStartStopClick = { handleMainScreenBtnClickEvent() },
                        onHomeDnsClick = { navigateToDnsDetailIfAllowed() },
                        onHomeFirewallClick = { homeNavRequest = HomeNavRequest.FirewallSettings },
                        onHomeProxyClick = {
                            if (appConfig.isWireGuardEnabled()) {
                                homeNavRequest = HomeNavRequest.WgMain
                            } else {
                                homeNavRequest = HomeNavRequest.ProxySettings
                            }
                        },
                        onHomeLogsClick = { homeNavRequest = HomeNavRequest.NetworkLogs },
                        onHomeAppsClick = { homeNavRequest = HomeNavRequest.AppList },
                        onHomeSponsorClick = {
                            // promptForAppSponsorship()
                        },
                        summaryViewModel = summaryViewModel,
                        onOpenDetailedStats = { type -> openDetailedStatsUi(type) },
                        startDestination = homeStartDestination,
                        isDebug = DEBUG,
                        onConfigureAppsClick = { homeNavRequest = HomeNavRequest.AppList },
                        onConfigureDnsClick = { navigateToDnsDetailIfAllowed() },
                        onConfigureFirewallClick = {
                            homeNavRequest = HomeNavRequest.FirewallSettings
                        },
                        onFirewallUniversalClick = {
                            homeNavRequest = HomeNavRequest.UniversalFirewallSettings
                        },
                        onFirewallCustomIpClick = {
                            homeNavRequest =
                                HomeNavRequest.CustomRules(
                                    uid = UID_EVERYBODY,
                                    tab = CustomRulesTab.IP,
                                    mode = CustomRulesMode.APP_SPECIFIC
                                )
                        },
                        onFirewallAppWiseIpClick = { openAppWiseIpScreen() },
                        onConfigureProxyClick = { homeNavRequest = HomeNavRequest.ProxySettings },
                        onConfigureNetworkClick = {
                            homeNavRequest = HomeNavRequest.TunnelSettings
                        },
                        onConfigureOthersClick = { homeNavRequest = HomeNavRequest.MiscSettings },
                        onConfigureLogsClick = { homeNavRequest = HomeNavRequest.NetworkLogs },
                        onConfigureAntiCensorshipClick = {
                            homeNavRequest = HomeNavRequest.AntiCensorship
                        },
                        onConfigureAdvancedClick = {
                            homeNavRequest = HomeNavRequest.AdvancedSettings
                        },
                        aboutUiState = aboutState,
                        onSponsorClick = { openUrl(this, RETHINKDNS_SPONSOR_LINK) },
                        onTelegramClick = {
                            openUrl(
                                this,
                                getString(R.string.about_telegram_link)
                            )
                        },
                        onBugReportClick = { aboutViewModel.triggerBugReport() },
                        onWhatsNewClick = { showNewFeaturesDialog() },
                        onAppUpdateClick = { checkForUpdate(AppUpdater.UserPresent.INTERACTIVE) },
                        onContributorsClick = { showContributors() },
                        onTranslateClick = {
                            openUrl(
                                this,
                                getString(R.string.about_translate_link)
                            )
                        },
                        onWebsiteClick = { openUrl(this, getString(R.string.about_website_link)) },
                        onGithubClick = { openUrl(this, getString(R.string.about_github_link)) },
                        onFaqClick = { openUrl(this, getString(R.string.about_faq_link)) },
                        onDocsClick = { openUrl(this, getString(R.string.about_docs_link)) },
                        onPrivacyPolicyClick = {
                            openUrl(
                                this,
                                getString(R.string.about_privacy_policy_link)
                            )
                        },
                        onTermsOfServiceClick = {
                            openUrl(
                                this,
                                getString(R.string.about_terms_link)
                            )
                        },
                        onLicenseClick = { openUrl(this, getString(R.string.about_license_link)) },
                        onTwitterClick = {
                            openUrl(
                                this,
                                getString(R.string.about_twitter_handle)
                            )
                        },
                        onEmailClick = { disableFrostTemporarily(); sendEmailIntent(this) },
                        onRedditClick = { openUrl(this, getString(R.string.about_reddit_handle)) },
                        onElementClick = { openUrl(this, getString(R.string.about_matrix_handle)) },
                        onMastodonClick = {
                            openUrl(
                                this,
                                getString(R.string.about_mastodom_handle)
                            )
                        },
                        onGeneralSettingsClick = { homeNavRequest = HomeNavRequest.MiscSettings },
                        onAppInfoClick = { UIUtils.openAndroidAppInfo(this, packageName) },
                        onVpnProfileClick = { openVpnProfile(this) },
                        onNotificationClick = { openNotificationSettings() },
                        onStatsClick = { openStatsDialog() },
                        onDbStatsClick = { openDatabaseDumpDialog() },
                        onFlightRecordClick = { initiateFlightRecord() },
                        onEventLogsClick = { openEventLogs() },
                        onTokenClick = { copyTokenToClipboard() },
                        onTokenDoubleTap = { aboutViewModel.generateNewToken() },
                        onFossClick = { openUrl(this, getString(R.string.about_foss_link)) },
                        onFlossFundsClick = {
                            openUrl(
                                this,
                                getString(R.string.about_floss_fund_link)
                            )
                        },
                        snackbarHostState = hostState,
                        detailedStatsViewModel = detailedStatsViewModel,
                        domainConnectionsViewModel = domainConnectionsViewModel,
                        eventsViewModel = eventsViewModel,
                        eventDao = eventDao,
                        appInfoEventLogger = eventLogger,
                        appInfoIpRulesViewModel = appInfoIpRulesViewModel,
                        appInfoDomainRulesViewModel = appInfoDomainRulesViewModel,
                        appInfoNetworkLogsViewModel = appInfoNetworkLogsViewModel,
                        persistentState = persistentState,
                        appConfig = appConfig,
                        onOpenVpnProfile = { UIUtils.openVpnProfile(this@HomeScreenActivity) },
                        onRefreshDatabase = { lifecycleScope.launch { rdb.refresh(RefreshDatabase.ACTION_REFRESH_INTERACTIVE) } },
                        onThemeModeChanged = { composeThemePreference = it },
                        onThemeColorChanged = { composeThemeColorPreset = it },
                        consoleLogViewModel = consoleLogViewModel,
                        consoleLogRepository = consoleLogRepository,
                        onShareConsoleLogs = {
                            homeNavRequest = HomeNavRequest.NetworkLogs
                        }, // Fallback to Activity for complex share
                        onConsoleLogsDeleteComplete = {
                            showToastUiCentered(
                                this@HomeScreenActivity,
                                getString(R.string.config_add_success_toast),
                                Toast.LENGTH_SHORT
                            )
                        },
                        proxyAppsMappingViewModel = proxyAppsMappingViewModel,
                        dnsSettingsViewModel = dnsSettingsViewModel,
                        appDownloadManager = appDownloadManager,
                        onDnsCustomDnsClick = { startCustomDnsActivity() },
                        onDnsLocalBlocklistConfigureClick = { startLocalBlocklistConfigureActivity() },
                        onDnsRethinkPlusDnsClick = { startRethinkPlusDnsActivity() },
                        homeNavRequest = homeNavRequest,
                        onHomeNavConsumed = { homeNavRequest = null },
                        rethinkEndpointViewModel = rethinkEndpointViewModel,
                        remoteFileTagViewModel = remoteFileTagViewModel,
                        localFileTagViewModel = localFileTagViewModel,
                        remoteBlocklistPacksMapViewModel = remoteBlocklistPacksMapViewModel,
                        localBlocklistPacksMapViewModel = localBlocklistPacksMapViewModel,
                        appInfoViewModel = appInfoViewModel,
                        refreshDatabase = rdb,
                        connectionTrackerViewModel = connectionTrackerViewModel,
                        dnsLogViewModel = dnsLogViewModel,
                        rethinkLogViewModel = rethinkLogViewModel,
                        connectionTrackerRepository = connectionTrackerRepository,
                        dnsLogRepository = dnsLogRepository,
                        rethinkLogRepository = rethinkLogRepository,
                        onConfigureOtherDns = { index ->
                            homeNavRequest = HomeNavRequest.ConfigureOtherDns(index)
                        },
                        // ConfigureOtherDns ViewModels
                        dohViewModel = dohViewModel,
                        dotViewModel = dotViewModel,
                        dnsProxyViewModel = dnsProxyViewModel,
                        dnsCryptViewModel = dnsCryptViewModel,
                        dnsCryptRelayViewModel = dnsCryptRelayViewModel,
                        oDohViewModel = oDohViewModel,
                        // UniversalFirewallSettings callbacks
                        onNavigateToLogs = { searchQuery ->
                            homeNavRequest = HomeNavRequest.NetworkLogs
                        },
                        onOpenAccessibilitySettings = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        // WireGuard dependencies
                        wgConfigViewModel = wgConfigViewModel,
                        // Checkout dependencies
                        checkoutViewModel = checkoutViewModel,
                        onNavigateToProxy = { homeNavRequest = HomeNavRequest.ProxySettings },
                        // WgMain callbacks
                        onWgCreateClick = {
                            homeNavRequest = HomeNavRequest.WgConfigEditor(
                                com.celzero.bravedns.service.WireguardManager.INVALID_CONF_ID,
                                com.celzero.bravedns.ui.compose.wireguard.WgType.DEFAULT
                            )
                        },
                        onWgImportClick = { launchFileImport() },
                        onWgQrScanClick = { launchQrScanner() },
                        appDatabase = appDatabase
                    )
                } else {
                    AppLockScreen(
                        persistentState = persistentState,
                        onAuthResult = { result ->
                            when (result) {
                                AppLockResult.Success, AppLockResult.NotRequired -> {
                                    isUnlocked = true
                                }

                                AppLockResult.Failure -> {
                                    finish()
                                }

                                AppLockResult.Pending -> {
                                    // Waiting for user interaction
                                }
                            }
                        }
                    )
                }


                if (showBugReportSheet) {
                    BugReportFilesSheet(onDismiss = { showBugReportSheet = false })
                }
                HomeDialogHost()
            }
        }

        // enable in-app messaging, will be used to show in-app messages in case of billing issues
        //enableInAppMessaging()
    }


    /*private fun enableInAppMessaging() {
        initiateBillingIfNeeded()
        // enable in-app messaging
        InAppBillingHandler.enableInAppMessaging(this)
        Logger.v(LOG_IAB, "enableInAppMessaging: enabled")
    }

    private fun initiateBillingIfNeeded() {
        if (InAppBillingHandler.isBillingClientSetup()) {
            Logger.i(LOG_IAB, "ensureBillingSetup: billing client already setup")
            return
        }

        InAppBillingHandler.initiate(this.applicationContext)
        Logger.i(LOG_IAB, "ensureBillingSetup: billing client initiated")
    }*/

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNavigationIntent(intent)
        Logger.v(LOG_TAG_UI, "home screen activity received new intent")
    }

    override fun onResume() {
        super.onResume()
        // if app is coming from background, don't reset the activity stack
        if (appInBackground) {
            appInBackground = false
            Logger.d(LOG_TAG_UI, "app restored from background, maintaining activity stack")
        }
    }

    // check if app running on TV
    private fun isAppRunningOnTv(): Boolean {
        return try {
            val uiModeManager: UiModeManager = getSystemService(UI_MODE_SERVICE) as UiModeManager
            uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        } catch (_: Exception) {
            false
        }
    }

    private fun isInForeground(): Boolean {
        return !this.isFinishing && !this.isDestroyed
    }

    private fun handleIntent() {
        val intent = this.intent ?: return
        if (
            intent.scheme?.equals(INTENT_SCHEME) == true &&
            intent.data?.path?.contains(BACKUP_FILE_EXTN) == true
        ) {
            handleRestoreProcess(intent.data)
        } else if (intent.scheme?.equals(INTENT_SCHEME) == true) {
            showToastUiCentered(
                this,
                getString(R.string.brbs_restore_no_uri_toast),
                Toast.LENGTH_SHORT
            )
        } else if (intent.getBooleanExtra(INTENT_RESTART_APP, false)) {
            Logger.i(LOG_TAG_UI, "Restart from restore, so refreshing app database...")
            io { rdb.refresh(RefreshDatabase.ACTION_REFRESH_RESTORE) }
        }
    }

    private fun handleNavigationIntent(intent: Intent?) {
        if (intent == null) return
        handleNotificationAction(intent)
        val target = intent.getStringExtra(EXTRA_NAV_TARGET) ?: return
        when (target) {
            NAV_TARGET_DOMAIN_CONNECTIONS -> {
                val typeValue = intent.getIntExtra(EXTRA_DC_TYPE, 0)
                val flag = intent.getStringExtra(EXTRA_DC_FLAG).orEmpty()
                val domain = intent.getStringExtra(EXTRA_DC_DOMAIN).orEmpty()
                val asn = intent.getStringExtra(EXTRA_DC_ASN).orEmpty()
                val ip = intent.getStringExtra(EXTRA_DC_IP).orEmpty()
                val isBlocked = intent.getBooleanExtra(EXTRA_DC_IS_BLOCKED, false)
                val timeCategoryValue = intent.getIntExtra(EXTRA_DC_TIME_CATEGORY, 0)
                val type =
                    com.celzero.bravedns.ui.compose.logs.DomainConnectionsInputType.fromValue(
                        typeValue
                    )
                val timeCategory =
                    DomainConnectionsViewModel.TimeCategory.fromValue(timeCategoryValue)
                        ?: DomainConnectionsViewModel.TimeCategory.ONE_HOUR
                homeNavRequest =
                    HomeNavRequest.DomainConnections(
                        type = type,
                        flag = flag,
                        domain = domain,
                        asn = asn,
                        ip = ip,
                        isBlocked = isBlocked,
                        timeCategory = timeCategory
                    )
            }

            NAV_TARGET_APP_INFO -> {
                val uid = intent.getIntExtra(EXTRA_APP_INFO_UID, Constants.INVALID_UID)
                if (uid == Constants.INVALID_UID) return
                homeNavRequest = HomeNavRequest.AppInfo(uid = uid)
            }

            NAV_TARGET_NETWORK_LOGS -> {
                // Navigate to network logs screen
                homeNavRequest = HomeNavRequest.NetworkLogs
            }

            NAV_TARGET_WG_MAIN -> {
                homeNavRequest = HomeNavRequest.WgMain
            }
        }
    }

    private fun handleNotificationAction(intent: Intent) {
        if (intent.extras == null) return

        val accessibility = intent.getStringExtra(Constants.NOTIF_INTENT_EXTRA_ACCESSIBILITY_NAME)
        if (Constants.NOTIF_INTENT_EXTRA_ACCESSIBILITY_VALUE == accessibility) {
            homeDialogState = HomeDialog.AccessibilityCrash
            return
        }

        val newApp = intent.getStringExtra(Constants.NOTIF_INTENT_EXTRA_NEW_APP_NAME)
        if (Constants.NOTIF_INTENT_EXTRA_NEW_APP_VALUE == newApp) {
            val uid =
                intent.getIntExtra(Constants.NOTIF_INTENT_EXTRA_APP_UID, Constants.INVALID_UID)
            if (uid > 0) {
                homeNavRequest = HomeNavRequest.AppInfo(uid = uid)
            }
            return
        }

        val wg = intent.getStringExtra(Constants.NOTIF_WG_PERMISSION_NAME)
        if (Constants.NOTIF_WG_PERMISSION_VALUE == wg) {
            homeNavRequest = HomeNavRequest.WgMain
            return
        }
    }


    private fun handleRestoreProcess(uri: Uri?) {
        if (uri == null) {
            showToastUiCentered(
                this,
                getString(R.string.brbs_restore_no_uri_toast),
                Toast.LENGTH_SHORT
            )
            return
        }

        showRestoreDialog(uri)
    }

    private fun regenerateFirebaseTokenIfNeeded() {
        if (Utilities.isFdroidFlavour()) return
        if (!persistentState.firebaseErrorReportingEnabled) return

        val now = System.currentTimeMillis()
        val fortyFiveDaysMs = TimeUnit.DAYS.toMillis(TOKEN_REGENERATION_PERIOD_DAYS)

        var token = persistentState.firebaseUserToken
        var ts = persistentState.firebaseUserTokenTimestamp
        if (token.isBlank() || now - ts > fortyFiveDaysMs) {
            token = getRandomString(TOKEN_LENGTH)
            ts = now
            persistentState.firebaseUserToken = token
            persistentState.firebaseUserTokenTimestamp = ts
            FirebaseErrorReporting.setUserId(token)
        }
    }

    private fun showRestoreDialog(uri: Uri) {
        if (!isInForeground()) return
        homeDialogState = HomeDialog.Restore(uri)
    }

    private fun startRestore(fileUri: Uri) {
        Logger.i(LOG_TAG_BACKUP_RESTORE, "invoke worker to initiate the restore process")
        val data = Data.Builder()
        data.putString(BackupHelper.DATA_BUILDER_RESTORE_URI, fileUri.toString())

        val importWorker =
            OneTimeWorkRequestBuilder<RestoreAgent>()
                .setInputData(data.build())
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .addTag(RestoreAgent.TAG)
                .build()
        WorkManager.getInstance(this).beginWith(importWorker).enqueue()
    }

    private fun observeRestoreWorker() {
        val workManager = WorkManager.getInstance(this.applicationContext)

        // observer for custom download manager worker
        workManager.getWorkInfosByTagLiveData(RestoreAgent.TAG).observe(this) { workInfoList ->
            val workInfo = workInfoList?.getOrNull(0) ?: return@observe
            Logger.i(
                LOG_TAG_BACKUP_RESTORE,
                "WorkManager state: ${workInfo.state} for ${RestoreAgent.TAG}"
            )
            if (WorkInfo.State.SUCCEEDED == workInfo.state) {
                showToastUiCentered(
                    this,
                    getString(R.string.brbs_restore_complete_toast),
                    Toast.LENGTH_SHORT
                )
                workManager.pruneWork()
            } else if (
                WorkInfo.State.CANCELLED == workInfo.state ||
                WorkInfo.State.FAILED == workInfo.state
            ) {
                showToastUiCentered(
                    this,
                    getString(R.string.brbs_restore_no_uri_toast),
                    Toast.LENGTH_SHORT
                )
                workManager.pruneWork()
                workManager.cancelAllWorkByTag(RestoreAgent.TAG)
            } else { // state == blocked
                // no-op
            }
        }
    }

    private fun observeAppState() {
        VpnController.connectionStatus.observe(this) {
            if (it == BraveVPNService.State.PAUSED) {
                // Handled in setContent
            }
        }
    }

    private fun removeThisMethod() {
        // set allowBypass to false for all versions, overriding the user's preference.
        // the default was true for Play Store and website versions, and false for F-Droid.
        // when allowBypass is true, some OEMs bypass the VPN service, causing connections
        // to fail due to the "Block connections without VPN" option.
        persistentState.allowBypass = false

        io {
            appInfoDb.setRethinkToBypassDnsAndFirewall()
            appInfoDb.setRethinkToBypassProxy(true)
        }

        // change the persistent state for defaultDnsUrl, if its google.com (only for v055d)
        // TODO: remove this post v054.
        // this is to fix the default dns url, as the default dns url is changed from
        // dns.google.com to dns.google. In servers.xml default ips available for dns.google
        // so changing the default dns url to dns.google
        if (persistentState.defaultDnsUrl.contains("dns.google.com")) {
            persistentState.defaultDnsUrl = Constants.DEFAULT_DNS_LIST[2].url
        }
        moveRemoteBlocklistFileFromAsset()
        // if biometric auth is enabled, then set the biometric auth type to 3 (15 minutes)
        if (persistentState.biometricAuth) {
            persistentState.biometricAuthType =
                BioMetricType.FIFTEEN_MIN.action
            // reset the bio metric auth time, as now the value is changed from System.currentTimeMillis
            // to SystemClock.elapsedRealtime
            persistentState.biometricAuthTime = SystemClock.elapsedRealtime()
        }

        // reset the local blocklist download from android download manager to custom in v055o
        persistentState.useCustomDownloadManager = true

        // delete residue wgs from database, remove this post v055o
        io { WireguardManager.deleteResidueWgs() }
        // reset the plus url to empty if it is set as /rec
        io {
            val rpe = appConfig.getRethinkPlusEndpoint()
            val url = rpe?.url ?: return@io
            if (url == "https://max.rethinkdns.com/rec" || url == "https://rplus.rethinkdns.com/rec") {
                val newUrl = if (rpe.url.contains(MAX_ENDPOINT)) {
                    Constants.RETHINK_BASE_URL_MAX
                } else {
                    Constants.RETHINK_BASE_URL_SKY
                }
                appConfig.updateRethinkEndpoint(Constants.RETHINK_DNS_PLUS, newUrl, 0)
            }
        }
    }

    // fixme: find a cleaner way to implement this, move this to some other place
    private fun moveRemoteBlocklistFileFromAsset() {
        io {
            // already there is a remote blocklist file available
            if (
                persistentState.remoteBlocklistTimestamp >
                Constants.PACKAGED_REMOTE_FILETAG_TIMESTAMP
            ) {
                RethinkBlocklistManager.readJson(
                    this,
                    RethinkBlocklistManager.DownloadType.REMOTE,
                    persistentState.remoteBlocklistTimestamp
                )
                return@io
            }

            RemoteFileTagUtil.moveFileToLocalDir(this.applicationContext, persistentState)
        }
    }


    private fun updateNewVersion() {
        if (!isNewVersion()) return

        // no need to show new settings on first time launch
        if (persistentState.appVersion != 0) {
            // if app version is not 0, then it means the app is updated
            NewSettingsManager.initializeNewSettings()
            Logger.i(LOG_TAG_UI, "app version already set, so its update, showing new settings")
        }
        val version = getLatestVersion()
        Logger.i(LOG_TAG_UI, "new version detected, updating the app version, version: $version")
        persistentState.appVersion = version
        persistentState.showWhatsNewChip = true
        persistentState.appUpdateTimeTs = System.currentTimeMillis()

        // FIXME: remove this post v054
        removeThisMethod()
    }

    private fun isNewVersion(): Boolean {
        val versionStored = persistentState.appVersion
        val version = getLatestVersion()
        return (version != 0 && version != versionStored)
    }

    private fun getLatestVersion(): Int {
        val pInfo: PackageInfo? = getPackageMetadata(this.packageManager, this.packageName)
        // TODO: modify this to use the latest version code api
        @Suppress("DEPRECATION")
        val v = pInfo?.versionCode ?: 0
        // latest version has apk variant (baseAbiVersionCode * 10000000 + variant.versionCode)
        // so we need to mod the version code by 10000000 to get the actual version code
        // for example: 10000000 + 45 = 10000045, so the version code is 1
        // see build.gradle (:app), #project.ext.versionCodes
        val latestVersionCode = v % 10000000 // 10000000 is the base version code
        Logger.i(LOG_TAG_UI, "latest version code: $latestVersionCode")
        return latestVersionCode
    }

    // FIXME - Move it to Android's built-in WorkManager
    private fun initUpdateCheck() {
        if (!isUpdateRequired()) return

        val diff = System.currentTimeMillis() - persistentState.lastAppUpdateCheck

        val daysElapsed = TimeUnit.MILLISECONDS.toDays(diff)
        Logger.i(LOG_TAG_UI, "App update check initiated, number of days: $daysElapsed")
        if (daysElapsed <= 1L) return

        checkForUpdate()
    }

    private fun isUpdateRequired(): Boolean {
        val calendar: Calendar = Calendar.getInstance()
        val day: Int = calendar.get(Calendar.DAY_OF_WEEK)
        return (day == Calendar.FRIDAY || day == Calendar.SATURDAY) &&
                persistentState.checkForAppUpdate
    }

    fun checkForUpdate(
        isInteractive: AppUpdater.UserPresent = AppUpdater.UserPresent.NONINTERACTIVE
    ) {
        // do not check for debug builds
        if (BuildConfig.DEBUG) return

        // Check updates only for play store / website version. Not fDroid.
        if (!isPlayStoreFlavour() && !isWebsiteFlavour()) {
            Logger.i(LOG_TAG_APP_UPDATE, "update check not for ${BuildConfig.FLAVOR}")
            return
        }

        if (isGooglePlayServicesAvailable() && isPlayStoreFlavour()) {
            try {
                appUpdateManager.checkForAppUpdate(
                    isInteractive,
                    this,
                    installStateUpdatedListener
                ) // Might be play updater or web updater
            } catch (e: Exception) {
                Logger.crash(LOG_TAG_APP_UPDATE, "err in app update check: ${e.message}", e)
                runOnUiThread {
                    showDownloadDialog(
                        AppUpdater.InstallSource.STORE,
                        getString(R.string.download_update_dialog_failure_title),
                        getString(R.string.download_update_dialog_failure_message)
                    )
                }
            }
        } else {
            try {
                get<NonStoreAppUpdater>()
                    .checkForAppUpdate(
                        isInteractive,
                        this,
                        installStateUpdatedListener
                    ) // Always web updater
            } catch (e: Exception) {
                Logger.e(LOG_TAG_APP_UPDATE, "Error in app (web) update check: ${e.message}", e)
                runOnUiThread {
                    showDownloadDialog(
                        AppUpdater.InstallSource.OTHER,
                        getString(R.string.download_update_dialog_failure_title),
                        getString(R.string.download_update_dialog_failure_message)
                    )
                }
            }
        }
    }

    private fun isGooglePlayServicesAvailable(): Boolean {
        // applicationInfo.enabled - When false, indicates that all components within
        // this application are considered disabled, regardless of their individually set enabled
        // status.
        // TODO: prompt dialog to user that Playservice is disabled, so switch to update
        // check for website
        return Utilities.getApplicationInfo(this, PKG_NAME_PLAY_STORE)?.enabled == true
    }

    private val installStateUpdatedListener =
        object : AppUpdater.InstallStateListener {
            override fun onStateUpdate(state: AppUpdater.InstallState) {
                Logger.i(LOG_TAG_UI, "InstallStateUpdatedListener: state: " + state.status)
                when (state.status) {
                    AppUpdater.InstallStatus.DOWNLOADED -> {
                        // CHECK THIS if AppUpdateType.FLEXIBLE, otherwise you can skip
                        showUpdateCompleteSnackbar()
                    }

                    else -> {
                        appUpdateManager.unregisterListener(this)
                    }
                }
            }

            override fun onUpdateCheckFailed(
                installSource: AppUpdater.InstallSource,
                isInteractive: AppUpdater.UserPresent
            ) {
                runOnUiThread {
                    if (isInteractive == AppUpdater.UserPresent.INTERACTIVE) {
                        showDownloadDialog(
                            installSource,
                            getString(R.string.download_update_dialog_failure_title),
                            getString(R.string.download_update_dialog_failure_message)
                        )
                    }
                }
            }

            override fun onUpToDate(
                installSource: AppUpdater.InstallSource,
                isInteractive: AppUpdater.UserPresent
            ) {
                runOnUiThread {
                    if (isInteractive == AppUpdater.UserPresent.INTERACTIVE) {
                        showDownloadDialog(
                            installSource,
                            getString(R.string.download_update_dialog_message_ok_title),
                            getString(R.string.download_update_dialog_message_ok)
                        )
                    }
                }
            }

            override fun onUpdateAvailable(installSource: AppUpdater.InstallSource) {
                runOnUiThread {
                    showDownloadDialog(
                        installSource,
                        getString(R.string.download_update_dialog_title),
                        getString(R.string.download_update_dialog_message)
                    )
                }
            }

            override fun onUpdateQuotaExceeded(installSource: AppUpdater.InstallSource) {
                runOnUiThread {
                    showDownloadDialog(
                        installSource,
                        getString(R.string.download_update_dialog_trylater_title),
                        getString(R.string.download_update_dialog_trylater_message)
                    )
                }
            }
        }

    private fun showUpdateCompleteSnackbar() {
        lifecycleScope.launch {
            val result =
                snackbarHostState?.showSnackbar(
                    message = getString(R.string.update_complete_snack_message),
                    actionLabel = getString(R.string.update_complete_action_snack),
                    duration = SnackbarDuration.Indefinite
                )
            if (result == SnackbarResult.ActionPerformed) {
                appUpdateManager.completeUpdate()
            }
        }
    }

    private fun showDownloadDialog(
        source: AppUpdater.InstallSource,
        title: String,
        message: String
    ) {
        if (!isInForeground()) return
        homeDialogState = HomeDialog.Download(source, title, message)
    }

    private fun initiateDownload() {
        try {
            val url = Constants.RETHINK_APP_DOWNLOAD_LINK
            val uri = url.toUri()
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = uri
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            showToastUiCentered(this, getString(R.string.no_browser_error), Toast.LENGTH_SHORT)
            Logger.w(Logger.LOG_TAG_VPN, "err opening rethink download link: ${e.message}", e)
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            appUpdateManager.unregisterListener(installStateUpdatedListener)
        } catch (e: IllegalArgumentException) {
            Logger.w(LOG_TAG_DOWNLOAD, "Unregister receiver exception")
        }
        // mark that app is going to background
        appInBackground = true
        Logger.v(LOG_TAG_UI, "home screen activity is stopped, app going to background")
    }


    private sealed interface HomeDialog {
        data class Restore(val uri: Uri) : HomeDialog
        data class Download(
            val source: AppUpdater.InstallSource,
            val title: String,
            val message: String
        ) : HomeDialog

        data class AlwaysOnStop(val message: String) : HomeDialog
        data object AlwaysOnDisable : HomeDialog
        data object PrivateDns : HomeDialog
        data class FirstTimeVpn(val intent: Intent) : HomeDialog
        data class Stats(val displayText: String, val dump: String) : HomeDialog
        data class Sponsor(val usageMessage: String, val amount: String) : HomeDialog
        data object Contributors : HomeDialog
        data object NoLog : HomeDialog
        data object AccessibilityCrash : HomeDialog
        data class NewFeatures(val title: String) : HomeDialog
    }

    private fun openDetailedStatsUi(type: SummaryStatisticsType) {
        val timeCategory = summaryViewModel.uiState.value.timeCategory.value
        val category =
            SummaryStatisticsViewModel.TimeCategory.fromValue(timeCategory)
                ?: SummaryStatisticsViewModel.TimeCategory.ONE_HOUR
        homeNavRequest = HomeNavRequest.DetailedStats(type, category)
    }

    private fun promptForAppSponsorship() {
        val installTime = packageManager.getPackageInfo(packageName, 0).firstInstallTime
        val timeDiff = System.currentTimeMillis() - installTime
        val days = (timeDiff / (1000L * 60L * 60L * 24L)).toDouble()
        val month = days / 30.0
        val amount = month * (0.60 + 0.20)

        val msg = getString(
            R.string.sponser_dialog_usage_msg,
            days.toInt().toString(),
            "%.2f".format(amount)
        )
        val formattedAmount =
            getString(
                R.string.two_argument_no_space,
                getString(R.string.symbol_dollar),
                "%.2f".format(amount)
            )
        homeDialogState = HomeDialog.Sponsor(msg, formattedAmount)
    }

    private fun handleMainScreenBtnClickEvent() {
        Utilities.delay(TimeUnit.MILLISECONDS.toMillis(500L), lifecycleScope) { }
        handleVpnActivation()
    }

    private fun handleVpnActivation() {
        if (handleAlwaysOnVpn()) return

        if (VpnController.hasTunnel()) {
            stopVpnService()
        } else {
            prepareAndStartVpn()
        }
    }

    private fun handleAlwaysOnVpn(): Boolean {
        if (Utilities.isOtherVpnHasAlwaysOn(this)) {
            showAlwaysOnDisableDialog()
            return true
        }

        if (VpnController.isAlwaysOn(this) && VpnController.hasTunnel()) {
            showAlwaysOnStopDialog()
            return true
        }

        return false
    }

    private fun showAlwaysOnStopDialog() {
        val message =
            if (VpnController.isVpnLockdown()) {
                UIUtils.htmlToSpannedText(getString(R.string.always_on_dialog_lockdown_stop_message))
                    .toString()
            } else {
                getString(R.string.always_on_dialog_stop_message)
            }
        homeDialogState = HomeDialog.AlwaysOnStop(message)
    }

    private fun showAlwaysOnDisableDialog() {
        homeDialogState = HomeDialog.AlwaysOnDisable
    }

    private fun startDnsActivity(screenToLoad: Int) {
        if (Utilities.isPrivateDnsActive(this)) {
            showPrivateDnsDialog()
            return
        }

        if (canStartRethinkActivity()) {
            io {
                val endpoint = appConfig.getRemoteRethinkEndpoint()
                val url = endpoint?.url ?: ""
                val name = endpoint?.name ?: ""
                uiCtx {
                    homeNavRequest = HomeNavRequest.ConfigureRethinkBasic(
                        ConfigureRethinkScreenType.DB_LIST,
                        name,
                        url
                    )
                }
            }
            return
        }

        homeNavRequest = HomeNavRequest.DnsDetail
    }

    private fun navigateToDnsDetailIfAllowed() {
        if (Utilities.isPrivateDnsActive(this)) {
            showPrivateDnsDialog()
            return
        }
        homeNavRequest = HomeNavRequest.DnsDetail
    }

    private fun startCustomDnsActivity() {
        homeNavRequest = HomeNavRequest.DnsList
    }

    private fun startRethinkPlusDnsActivity() {
        homeNavRequest = HomeNavRequest.ConfigureRethinkBasic(ConfigureRethinkScreenType.DB_LIST)
    }

    private fun startLocalBlocklistConfigureActivity() {
        homeNavRequest = HomeNavRequest.ConfigureRethinkBasic(ConfigureRethinkScreenType.LOCAL)
    }

    private fun canStartRethinkActivity(): Boolean {
        val dns = appConfig.getDnsType()
        return dns.isRethinkRemote() && !WireguardManager.oneWireGuardEnabled()
    }

    private fun showPrivateDnsDialog() {
        homeDialogState = HomeDialog.PrivateDns
    }

    private fun startFirewallActivity(screenToLoad: Int) {
        homeNavRequest = HomeNavRequest.FirewallSettings
    }

    private fun openUniversalFirewallScreen() {
        homeNavRequest = HomeNavRequest.UniversalFirewallSettings
    }

    private fun openCustomIpScreen() {
        homeNavRequest =
            HomeNavRequest.CustomRules(
                uid = UID_EVERYBODY,
                tab = CustomRulesTab.IP,
                mode = CustomRulesMode.APP_SPECIFIC
            )
    }

    private fun openAppWiseIpScreen() {
        homeNavRequest =
            HomeNavRequest.CustomRules(
                uid = UID_EVERYBODY,
                tab = CustomRulesTab.IP,
                mode = CustomRulesMode.ALL_RULES
            )
    }

    private fun startAppsActivity() {
        homeNavRequest = HomeNavRequest.AppList
    }

    private fun prepareAndStartVpn() {
        if (prepareVpnService()) {
            startVpnService()
        }
    }

    private fun stopVpnService() {
        VpnController.stop("home", this)
    }

    private fun startVpnService() {
        getNotificationPermissionIfNeeded()
        VpnController.start(this, true)
    }

    private fun getNotificationPermissionIfNeeded() {
        if (!Utilities.isAtleastT()) {
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        if (!persistentState.shouldRequestNotificationPermission) {
            Logger.w(LOG_TAG_VPN, "User rejected notification permission for the app")
            return
        }

        notificationPermissionResult.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Throws(ActivityNotFoundException::class)
    private fun prepareVpnService(): Boolean {
        val prepareVpnIntent: Intent? =
            try {
                Logger.i(LOG_TAG_VPN, "Preparing VPN service")
                VpnService.prepare(this)
            } catch (e: NullPointerException) {
                Logger.e(LOG_TAG_VPN, "Device does not support system-wide VPN mode.", e)
                return false
            }
        if (prepareVpnIntent != null) {
            Logger.i(LOG_TAG_VPN, "VPN service is prepared")
            showFirstTimeVpnDialog(prepareVpnIntent)
            return false
        }
        Logger.i(LOG_TAG_VPN, "VPN service is prepared, starting VPN service")
        return true
    }

    private fun showFirstTimeVpnDialog(prepareVpnIntent: Intent) {
        homeDialogState = HomeDialog.FirstTimeVpn(prepareVpnIntent)
    }

    private fun registerForActivityResult() {
        startForResult =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                when (result.resultCode) {
                    Activity.RESULT_OK -> {
                        startVpnService()
                    }

                    Activity.RESULT_CANCELED -> {
                        showToastUiCentered(
                            this,
                            getString(R.string.hsf_vpn_prepare_failure),
                            Toast.LENGTH_LONG
                        )
                    }

                    else -> {
                        stopVpnService()
                    }
                }
            }

        notificationPermissionResult =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {
                persistentState.shouldRequestNotificationPermission = it
                if (it) {
                    Logger.i(LOG_TAG_UI, "User accepted notification permission")
                } else {
                    Logger.w(LOG_TAG_UI, "User rejected notification permission")
                    lifecycleScope.launch {
                        snackbarHostState?.showSnackbar(
                            message = getString(R.string.hsf_notification_permission_failure),
                            duration = SnackbarDuration.Long
                        )
                    }
                }
            }
    }

    private fun copyTokenToClipboard() {
        val text = persistentState.firebaseUserToken
        val clipboard = ContextCompat.getSystemService(this, ClipboardManager::class.java)
        val clip = ClipData.newPlainText("token", text)
        clipboard?.setPrimaryClip(clip)
        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun initiateFlightRecord() {
        io { VpnController.performFlightRecording() }
        Toast.makeText(this, "Flight recording started", Toast.LENGTH_SHORT).show()
    }

    private fun openEventLogs() {
        homeNavRequest = HomeNavRequest.Events
    }

    private fun getVersionName(): String {
        return Utilities.getPackageMetadata(packageManager, packageName)?.versionName ?: ""
    }

    private fun openStatsDialog() {
        io {
            val stat = VpnController.getNetStat()
            val formatedStat = UIUtils.formatNetStat(stat)
            val vpnStats = VpnController.vpnStats()
            val stats = formatedStat + vpnStats
            uiCtx {
                val displayText = if (formatedStat == null) "No Stats" else stats
                homeDialogState = HomeDialog.Stats(displayText, stats)
            }
        }
    }

    private fun copyToClipboard(label: String, text: String): ClipboardManager? {
        val clipboard = ContextCompat.getSystemService(this, ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
        return clipboard
    }

    private fun openDatabaseDumpDialog() {
        homeNavRequest = HomeNavRequest.Database
    }

    private fun hasAnyLogsAvailable(): Boolean {
        val dir = filesDir
        val bugReportDir = java.io.File(dir, BugReportZipper.BUG_REPORT_DIR_NAME)
        if (bugReportDir.exists() && bugReportDir.isDirectory) {
            val bugReportFiles = bugReportDir.listFiles()
            if (bugReportFiles != null && bugReportFiles.any { it.isFile && it.length() > 0 }) {
                return true
            }
        }

        return false
    }

    private fun showNoLogDialog() {
        homeDialogState = HomeDialog.NoLog
    }

    private fun openNotificationSettings() {
        val packageName = packageName
        try {
            val intent = Intent()
            if (Utilities.isAtleastO()) {
                intent.action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            } else {
                intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                intent.addCategory(Intent.CATEGORY_DEFAULT)
                intent.data = android.net.Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            showToastUiCentered(
                this,
                getString(R.string.notification_screen_error),
                Toast.LENGTH_SHORT
            )
            Logger.w(LOG_TAG_UI, "activity not found ${e.message}", e)
        }
    }

    private fun showNewFeaturesDialog() {
        val v = getVersionName().slice(0..6)
        val title = getString(R.string.about_whats_new, v)
        homeDialogState = HomeDialog.NewFeatures(title)
    }

    private fun showContributors() {
        homeDialogState = HomeDialog.Contributors
    }

    private fun promptCrashLogAction() {
        if (Utilities.isAtleastO()) {
            io {
                try {
                    EnhancedBugReport.addLogsToZipFile(this@HomeScreenActivity)
                } catch (e: Exception) {
                    Logger.w(LOG_TAG_UI, "err adding tombstone to zip: ${e.message}", e)
                }
            }
        }

        val dir = filesDir
        val zipPath = BugReportZipper.getZipFileName(dir)
        val zipFile = java.io.File(zipPath)

        if (!zipFile.exists() || zipFile.length() <= 0) {
            showToastUiCentered(
                this,
                getString(R.string.log_file_not_available),
                Toast.LENGTH_SHORT
            )
            return
        }

        showBugReportSheet = true
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun BugReportFilesSheet(onDismiss: () -> Unit) {
        val scope = rememberCoroutineScope()
        val bugReportFiles = remember { mutableStateListOf<BugReportFile>() }
        var isSending by remember { mutableStateOf(false) }
        var progressText by remember { mutableStateOf("") }
        var pendingDelete by remember { mutableStateOf<BugReportFile?>(null) }

        LaunchedEffect(Unit) {
            try {
                val files = withContext(Dispatchers.IO) { collectAllBugReportFiles() }
                bugReportFiles.clear()
                bugReportFiles.addAll(files)
            } catch (e: Exception) {
                Logger.e(LOG_TAG_UI, "err loading bug report: ${e.message}", e)
                showToastUiCentered(
                    this@HomeScreenActivity,
                    getString(R.string.bug_report_file_not_found),
                    Toast.LENGTH_SHORT
                )
                onDismiss()
            }
        }

        val totalSize = bugReportFiles.filter { it.isSelected }.sumOf { it.file.length() }
        val hasSelection = bugReportFiles.any { it.isSelected }
        val allSelected = bugReportFiles.isNotEmpty() && bugReportFiles.all { it.isSelected }

        RethinkModalBottomSheet(
            onDismissRequest = onDismiss,
            contentPadding = PaddingValues(Dimensions.spacingNone),
            verticalSpacing = Dimensions.spacingNone,
            includeBottomSpacer = false
        ) {
            Column(modifier = Modifier.padding(Dimensions.spacingLg)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = Dimensions.spacingMd),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = allSelected,
                            onCheckedChange = { checked ->
                                bugReportFiles.forEach { it.isSelected = checked }
                            }
                        )
                        Text(
                            text =
                                if (allSelected) {
                                    getString(R.string.bug_report_deselect_all)
                                } else {
                                    getString(R.string.lbl_select_all)
                                        .replaceFirstChar(Char::titlecase)
                                },
                            modifier = Modifier.clickable {
                                bugReportFiles.forEach { it.isSelected = !allSelected }
                            }
                        )
                    }
                    Text(
                        text = formatFileSize(totalSize),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (bugReportFiles.isEmpty()) {
                    Text(text = getString(R.string.bug_report_no_files_available))
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                    ) {
                        items(bugReportFiles, key = { it.file.absolutePath }) { item ->
                            BugReportFileRow(
                                fileItem = item,
                                onShare = { openBugReportFile(item.file) },
                                onDelete = { pendingDelete = item }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(Dimensions.spacingMd))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isSending) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(Dimensions.iconSizeSm))
                            Spacer(modifier = Modifier.width(Dimensions.spacingSm))
                            Text(text = progressText)
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            if (!isSending) {
                                scope.launch {
                                    sendBugReport(
                                        bugReportFiles = bugReportFiles,
                                        onSending = { sending, text ->
                                            isSending = sending
                                            progressText = text
                                        },
                                        onDone = {
                                            showBugReportSheet = false
                                        }
                                    )
                                }
                            }
                        },
                        enabled = hasSelection && !isSending
                    ) {
                        Text(text = getString(R.string.about_bug_report_dialog_positive_btn))
                    }
                }
            }
        }

        pendingDelete?.let { fileItem ->
            RethinkConfirmDialog(
                onDismissRequest = { pendingDelete = null },
                title = getString(R.string.lbl_delete),
                message = getString(R.string.bug_report_delete_confirmation, fileItem.name),
                confirmText = getString(R.string.lbl_delete),
                dismissText = getString(R.string.lbl_cancel),
                isConfirmDestructive = true,
                onConfirm = {
                    pendingDelete = null
                    scope.launch {
                        deleteBugReportFile(
                            fileItem = fileItem,
                            bugReportFiles = bugReportFiles,
                            onDismiss = onDismiss
                        )
                    }
                },
                onDismiss = { pendingDelete = null }
            )
        }
    }

    @Composable
    private fun BugReportFileRow(
        fileItem: BugReportFile,
        onShare: () -> Unit,
        onDelete: () -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Dimensions.spacingSm)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(Dimensions.cornerRadiusMd)
                )
                .padding(Dimensions.spacingMd),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = fileItem.isSelected,
                onCheckedChange = { checked -> fileItem.isSelected = checked }
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fileItem.name,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
                )
                Text(
                    text =
                        "${formatFileSize(fileItem.file.length())} - ${formatDate(fileItem.file.lastModified())}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onShare) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_share),
                    contentDescription = null
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_delete),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    private fun collectAllBugReportFiles(): List<BugReportFile> {
        val files = mutableListOf<BugReportFile>()
        val dir = filesDir

        val bugReportZip = File(BugReportZipper.getZipFileName(dir))
        if (bugReportZip.exists() && bugReportZip.length() > 0) {
            files.add(
                BugReportFile(
                    file = bugReportZip,
                    name = bugReportZip.name,
                    type = FileType.ZIP,
                    isSelected = true
                )
            )
        }

        if (Utilities.isAtleastO()) {
            val tombstoneZip = EnhancedBugReport.getTombstoneZipFile(this)
            if (tombstoneZip != null && tombstoneZip.exists() && tombstoneZip.length() > 0) {
                files.add(
                    BugReportFile(
                        file = tombstoneZip,
                        name = tombstoneZip.name,
                        type = FileType.ZIP,
                        isSelected = true
                    )
                )
            }
        }

        val bugReportDir = File(dir, BugReportZipper.BUG_REPORT_DIR_NAME)
        if (bugReportDir.exists() && bugReportDir.isDirectory) {
            bugReportDir.listFiles()?.forEach { file ->
                if (file.isFile && file.length() > 0) {
                    files.add(
                        BugReportFile(
                            file = file,
                            name = file.name,
                            type = getFileType(file),
                            isSelected = true
                        )
                    )
                }
            }
        }

        if (Utilities.isAtleastO()) {
            val tombstoneDir = File(dir, EnhancedBugReport.TOMBSTONE_DIR_NAME)
            if (tombstoneDir.exists() && tombstoneDir.isDirectory) {
                tombstoneDir.listFiles()?.forEach { file ->
                    if (file.isFile && file.length() > 0) {
                        files.add(
                            BugReportFile(
                                file = file,
                                name = file.name,
                                type = FileType.TEXT,
                                isSelected = true
                            )
                        )
                    }
                }
            }
        }

        return files.sortedByDescending { it.file.lastModified() }
    }

    private fun getFileType(file: File): FileType {
        return when (file.extension.lowercase()) {
            "zip" -> FileType.ZIP
            "txt", "log" -> FileType.TEXT
            else -> FileType.TEXT
        }
    }

    private suspend fun sendBugReport(
        bugReportFiles: List<BugReportFile>,
        onSending: (Boolean, String) -> Unit,
        onDone: () -> Unit
    ) {
        val selectedFiles = bugReportFiles.filter { it.isSelected }.map { it.file }

        if (selectedFiles.isEmpty()) {
            showToastUiCentered(
                this,
                getString(R.string.bug_report_no_files_selected),
                Toast.LENGTH_SHORT
            )
            return
        }

        onSending(true, getString(R.string.bug_report_creating_zip))

        try {
            val attachmentUri = withContext(Dispatchers.IO) {
                if (selectedFiles.size == 1) {
                    getFileUri(selectedFiles[0])
                } else {
                    createCombinedZip(selectedFiles)
                }
            }

            if (attachmentUri != null) {
                val emailIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(getString(R.string.about_mail_to)))
                    putExtra(
                        Intent.EXTRA_SUBJECT,
                        getString(R.string.about_mail_bugreport_subject)
                    )
                    putExtra(Intent.EXTRA_STREAM, attachmentUri)
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                startActivity(
                    Intent.createChooser(
                        emailIntent,
                        getString(R.string.about_mail_bugreport_share_title)
                    )
                )
                onDone()
            } else {
                showToastUiCentered(
                    this,
                    getString(R.string.error_loading_log_file),
                    Toast.LENGTH_SHORT
                )
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "err sending bug report: ${e.message}", e)
            showToastUiCentered(
                this,
                getString(R.string.error_loading_log_file),
                Toast.LENGTH_SHORT
            )
        } finally {
            onSending(false, "")
        }
    }

    private fun createCombinedZip(files: List<File>): Uri? {
        val tempDir = cacheDir
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val zipFile = File(tempDir, "rethinkdns_bugreport_$timestamp.zip")

        try {
            val addedEntries = mutableSetOf<String>()

            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                files.forEach { file ->
                    if (file.extension == "zip") {
                        ZipFile(file).use { zf ->
                            val entries = zf.entries()
                            while (entries.hasMoreElements()) {
                                val entry = entries.nextElement()
                                if (!entry.isDirectory && !addedEntries.contains(entry.name)) {
                                    addedEntries.add(entry.name)

                                    val newEntry = ZipEntry(entry.name)
                                    zos.putNextEntry(newEntry)
                                    zf.getInputStream(entry).use { input ->
                                        input.copyTo(zos)
                                    }
                                    zos.closeEntry()
                                }
                            }
                        }
                    } else {
                        if (!addedEntries.contains(file.name)) {
                            addedEntries.add(file.name)

                            val entry = ZipEntry(file.name)
                            zos.putNextEntry(entry)
                            FileInputStream(file).use { input ->
                                input.copyTo(zos)
                            }
                            zos.closeEntry()
                        }
                    }
                }
            }

            return getFileUri(zipFile)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "err creating combined zip: ${e.message}", e)
            zipFile.delete()
            return null
        }
    }

    private fun getFileUri(file: File): Uri? {
        return try {
            FileProvider.getUriForFile(
                this,
                BugReportZipper.FILE_PROVIDER_NAME,
                file
            )
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "err getting file uri: ${e.message}", e)
            null
        }
    }

    private fun formatFileSize(size: Long): String {
        return when {
            size < BYTES_IN_KB -> "$size B"
            size < BYTES_IN_MB -> "${size / BYTES_IN_KB} KB"
            else -> String.format(Locale.US, "%.1f MB", size / MB_DIVISOR)
        }
    }

    private fun formatDate(timestamp: Long): String {
        return SimpleDateFormat("MMM d, yyyy HH:mm", Locale.US).format(Date(timestamp))
    }

    private suspend fun deleteBugReportFile(
        fileItem: BugReportFile,
        bugReportFiles: MutableList<BugReportFile>,
        onDismiss: () -> Unit
    ) {
        try {
            val deleted = withContext(Dispatchers.IO) { fileItem.file.delete() }

            if (deleted) {
                bugReportFiles.remove(fileItem)

                showToastUiCentered(
                    this,
                    getString(R.string.bug_report_file_deleted, fileItem.name),
                    Toast.LENGTH_SHORT
                )

                if (bugReportFiles.isEmpty()) {
                    showToastUiCentered(
                        this,
                        getString(R.string.bug_report_no_files_available),
                        Toast.LENGTH_SHORT
                    )
                    onDismiss()
                }
            } else {
                showToastUiCentered(
                    this,
                    getString(R.string.bug_report_delete_failed, fileItem.name),
                    Toast.LENGTH_SHORT
                )
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "err deleting file: ${e.message}", e)
            showToastUiCentered(
                this,
                getString(R.string.bug_report_delete_failed, fileItem.name),
                Toast.LENGTH_SHORT
            )
        }
    }

    private fun openBugReportFile(file: File) {
        try {
            val uri = getFileUri(file) ?: return

            val mimeType = when (file.extension.lowercase()) {
                "zip" -> "application/zip"
                "txt", "log" -> "text/plain"
                else -> "text/plain"
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            startActivity(Intent.createChooser(intent, getString(R.string.about_bug_report)))
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "err opening file: ${e.message}", e)
            showToastUiCentered(
                this,
                getString(R.string.bug_report_error_opening_file),
                Toast.LENGTH_SHORT
            )
        }
    }

    private fun handleShowAppExitInfo() {
        if (WorkScheduler.isWorkRunning(this, WorkScheduler.APP_EXIT_INFO_JOB_TAG)) return

        workScheduler.scheduleOneTimeWorkForAppExitInfo()

        val workManager = WorkManager.getInstance(applicationContext)
        workManager.getWorkInfosByTagLiveData(WorkScheduler.APP_EXIT_INFO_ONE_TIME_JOB_TAG).observe(
            this
        ) { workInfoList ->
            val workInfo = workInfoList?.getOrNull(0) ?: return@observe
            Logger.i(
                Logger.LOG_TAG_SCHEDULER,
                "WorkManager state: ${workInfo.state} for ${WorkScheduler.APP_EXIT_INFO_ONE_TIME_JOB_TAG}"
            )
            if (WorkInfo.State.SUCCEEDED == workInfo.state) {
                onAppExitInfoSuccess()
                workManager.pruneWork()
            } else if (
                WorkInfo.State.CANCELLED == workInfo.state ||
                WorkInfo.State.FAILED == workInfo.state
            ) {
                onAppExitInfoFailure()
                workManager.pruneWork()
                workManager.cancelAllWorkByTag(WorkScheduler.APP_EXIT_INFO_ONE_TIME_JOB_TAG)
            } else {
                // no-op
            }
        }
    }

    data class BugReportFile(
        val file: File,
        val name: String,
        val type: FileType,
        var isSelected: Boolean
    )

    enum class FileType {
        ZIP,
        TEXT
    }

    companion object {
        private const val BYTES_IN_KB = 1024L
        private const val BYTES_IN_MB = 1024L * 1024L
        private const val MB_DIVISOR = 1024.0 * 1024.0
        const val EXTRA_NAV_TARGET = "extra_nav_target"
        const val NAV_TARGET_DOMAIN_CONNECTIONS = "nav_target_domain_connections"
        const val NAV_TARGET_APP_INFO = "nav_target_app_info"
        const val NAV_TARGET_NETWORK_LOGS = "nav_target_network_logs"
        const val NAV_TARGET_WG_MAIN = "nav_target_wg_main"
        const val EXTRA_DC_TYPE = "extra_dc_type"
        const val EXTRA_DC_FLAG = "extra_dc_flag"
        const val EXTRA_DC_DOMAIN = "extra_dc_domain"
        const val EXTRA_DC_ASN = "extra_dc_asn"
        const val EXTRA_DC_IP = "extra_dc_ip"
        const val EXTRA_DC_IS_BLOCKED = "extra_dc_is_blocked"
        const val EXTRA_DC_TIME_CATEGORY = "extra_dc_time_category"
        const val EXTRA_APP_INFO_UID = "extra_app_info_uid"
    }

    @Composable
    private fun WhatsNewDialogContent() {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(Dimensions.spacingLg)
                    .verticalScroll(rememberScrollState())
        ) {
            HtmlText(
                text = getString(R.string.whats_new_version_update),
                textAlign = TextAlign.Start
            )
        }
    }

    @Composable
    private fun HomeDialogHost() {
        when (val dialog = homeDialogState) {
            is HomeDialog.Restore -> {
                HomeConfirmDialog(
                    onDismissRequest = { homeDialogState = null },
                    title = getString(R.string.brbs_restore_dialog_title),
                    message = getString(R.string.brbs_restore_dialog_message),
                    confirmText = getString(R.string.brbs_restore_dialog_positive),
                    onConfirm = {
                        homeDialogState = null
                        startRestore(dialog.uri)
                        observeRestoreWorker()
                    },
                    dismissText = getString(R.string.lbl_cancel),
                    onDismiss = { homeDialogState = null }
                )
            }

            is HomeDialog.Download -> {
                val isUpdateAvailable =
                    dialog.title == getString(R.string.download_update_dialog_title)
                val resolvedMessage =
                    if (isUpdateAvailable && dialog.source == AppUpdater.InstallSource.STORE) {
                        "A new version is available. Please update from Play Store."
                    } else {
                        dialog.message
                    }
                val primaryLabel =
                    if (isUpdateAvailable && dialog.source != AppUpdater.InstallSource.STORE) {
                        getString(R.string.hs_download_positive_website)
                    } else {
                        getString(R.string.hs_download_positive_default)
                    }

                HomeConfirmDialog(
                    onDismissRequest = {
                        if (!isUpdateAvailable) {
                            homeDialogState = null
                        }
                    },
                    title = dialog.title,
                    message = resolvedMessage,
                    confirmText = primaryLabel,
                    dismissText =
                        if (isUpdateAvailable) {
                            getString(R.string.hs_download_negative_default)
                        } else {
                            null
                        },
                    onConfirm = {
                        if (isUpdateAvailable) {
                            if (dialog.source == AppUpdater.InstallSource.STORE) {
                                appUpdateManager.completeUpdate()
                            } else {
                                initiateDownload()
                            }
                        }
                        homeDialogState = null
                    },
                    onDismiss = {
                        if (isUpdateAvailable) {
                            persistentState.lastAppUpdateCheck = System.currentTimeMillis()
                            homeDialogState = null
                        }
                    }
                )

            }

            is HomeDialog.AlwaysOnStop -> {
                HomeAlwaysOnStopDialog(
                    title = getString(R.string.always_on_dialog_stop_heading),
                    message = dialog.message,
                    stopText = getString(R.string.always_on_dialog_positive),
                    openSettingsText = getString(R.string.always_on_dialog_neutral),
                    cancelText = getString(R.string.lbl_cancel),
                    onStop = {
                        homeDialogState = null
                        stopVpnService()
                    },
                    onOpenSettings = {
                        homeDialogState = null
                        openVpnProfile(this@HomeScreenActivity)
                    },
                    onCancel = {
                        homeDialogState = null
                    }
                )
            }

            HomeDialog.AlwaysOnDisable -> {
                HomeConfirmDialog(
                    onDismissRequest = {},
                    title = getString(R.string.always_on_dialog_heading),
                    message = getString(R.string.always_on_dialog),
                    confirmText = getString(R.string.always_on_dialog_positive_btn),
                    dismissText = getString(R.string.lbl_cancel),
                    onConfirm = {
                        homeDialogState = null
                        openVpnProfile(this@HomeScreenActivity)
                    },
                    onDismiss = { homeDialogState = null }
                )
            }

            HomeDialog.PrivateDns -> {
                HomeConfirmDialog(
                    onDismissRequest = {},
                    title = getString(R.string.private_dns_dialog_heading),
                    message = getString(R.string.private_dns_dialog_desc),
                    confirmText = getString(R.string.private_dns_dialog_positive),
                    dismissText = getString(R.string.lbl_dismiss),
                    onConfirm = {
                        homeDialogState = null
                        openNetworkSettings(
                            this@HomeScreenActivity,
                            Settings.ACTION_WIRELESS_SETTINGS
                        )
                    },
                    onDismiss = { homeDialogState = null }
                )
            }

            is HomeDialog.FirstTimeVpn -> {
                HomeConfirmDialog(
                    onDismissRequest = {},
                    title = getString(R.string.hsf_vpn_dialog_header),
                    message = getString(R.string.hsf_vpn_dialog_message),
                    confirmText = getString(R.string.lbl_proceed),
                    dismissText = getString(R.string.lbl_cancel),
                    onConfirm = {
                        homeDialogState = null
                        try {
                            startForResult.launch(dialog.intent)
                        } catch (e: ActivityNotFoundException) {
                            Logger.e(LOG_TAG_VPN, "Activity not found to start VPN service", e)
                            showToastUiCentered(
                                this@HomeScreenActivity,
                                getString(R.string.hsf_vpn_prepare_failure),
                                Toast.LENGTH_LONG
                            )
                        }
                    },
                    onDismiss = { homeDialogState = null }
                )
            }

            is HomeDialog.Stats -> {
                HomeStatsDialog(
                    onDismissRequest = { homeDialogState = null },
                    title = getString(R.string.title_statistics),
                    displayText = dialog.displayText,
                    dismissText = getString(R.string.fapps_info_dialog_positive_btn),
                    copyText = getString(R.string.dns_info_neutral),
                    onDismiss = { homeDialogState = null },
                    onCopy = {
                        copyToClipboard("stats_dump", dialog.dump)
                        showToastUiCentered(
                            this@HomeScreenActivity,
                            getString(R.string.copied_clipboard),
                            Toast.LENGTH_SHORT
                        )
                    }
                )
            }

            is HomeDialog.Sponsor -> {
                /*
                 * Sponsor dialog intentionally hidden for now.
                 *
                 * Dialog(
                 *     onDismissRequest = { homeDialogState = null },
                 *     properties = DialogProperties(usePlatformDefaultWidth = false)
                 * ) {
                 *     Surface(color = MaterialTheme.colorScheme.background) {
                 *         Column(modifier = Modifier
                 *             .fillMaxWidth()
                 *             .padding(Dimensions.spacingLg)) {
                 *             Text(
                 *                 text = getString(R.string.about_sponsor_link_text),
                 *                 style = MaterialTheme.typography.titleLarge
                 *             )
                 *             Spacer(modifier = Modifier.height(Dimensions.spacingSm))
                 *             SponsorInfoDialogContent(
                 *                 amount = dialog.amount,
                 *                 usageMessage = dialog.usageMessage,
                 *                 onSponsorClick = {
                 *                     openUrl(
                 *                         this@HomeScreenActivity,
                 *                         RETHINKDNS_SPONSOR_LINK
                 *                     )
                 *                 }
                 *             )
                 *             Spacer(modifier = Modifier.height(Dimensions.spacingMd))
                 *             Row(
                 *                 modifier = Modifier.fillMaxWidth(),
                 *                 horizontalArrangement = Arrangement.End
                 *             ) {
                 *                 TextButton(onClick = { homeDialogState = null }) {
                 *                     Text(text = getString(R.string.lbl_cancel))
                 *                 }
                 *             }
                 *         }
                 *     }
                 * }
                 */
                LaunchedEffect(Unit) {
                    homeDialogState = null
                }
            }

            HomeDialog.Contributors -> {
                Dialog(
                    onDismissRequest = { homeDialogState = null },
                    properties = DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        ContributorsDialogContent(onDismiss = { homeDialogState = null })
                    }
                }
            }

            HomeDialog.NoLog -> {
                HomeConfirmDialog(
                    onDismissRequest = { homeDialogState = null },
                    title = getString(R.string.about_bug_no_log_dialog_title),
                    message = getString(R.string.about_bug_no_log_dialog_message),
                    confirmText = getString(R.string.about_bug_no_log_dialog_positive_btn),
                    dismissText = getString(R.string.lbl_cancel),
                    onConfirm = {
                        homeDialogState = null
                        sendEmailIntent(this@HomeScreenActivity)
                    },
                    onDismiss = { homeDialogState = null }
                )
            }

            is HomeDialog.NewFeatures -> {
                HomeNewFeaturesDialog(
                    onDismissRequest = { homeDialogState = null },
                    title = dialog.title,
                    dismissText = getString(R.string.about_dialog_positive_button),
                    contactText = getString(R.string.about_dialog_neutral_button),
                    onDismiss = { homeDialogState = null },
                    onContact = {
                        homeDialogState = null
                        sendEmailIntent(this@HomeScreenActivity)
                    },
                    content = { WhatsNewDialogContent() }
                )
            }

            HomeDialog.AccessibilityCrash -> {
                HomeConfirmDialog(
                    onDismissRequest = { homeDialogState = null },
                    title = getString(R.string.lbl_action_required),
                    message = getString(R.string.alert_firewall_accessibility_regrant_explanation),
                    confirmText = getString(R.string.univ_accessibility_crash_dialog_positive),
                    dismissText = getString(R.string.lbl_cancel),
                    onConfirm = {
                        UIUtils.openAndroidAppInfo(this@HomeScreenActivity, packageName)
                        homeDialogState = null
                    },
                    onDismiss = { homeDialogState = null }
                )
            }

            null -> Unit
        }
    }

    @Composable
    private fun ContributorsDialogContent(onDismiss: () -> Unit) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(Dimensions.spacingXl)
                    .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd)) {
                    Image(
                        painter = painterResource(id = android.R.drawable.ic_menu_close_clear_cancel),
                        contentDescription = getString(R.string.lbl_dismiss)
                    )
                }
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_authors),
                        contentDescription = null,
                        modifier = Modifier.size(Dimensions.iconSizeMd)
                    )
                    Spacer(modifier = Modifier.width(Dimensions.spacingSm))
                    Text(
                        text = getString(R.string.contributors_dialog_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(modifier = Modifier.height(Dimensions.spacingMd))

            HtmlText(
                text = getString(R.string.contributors_list),
                textAlign = TextAlign.Center
            )
        }
    }

    @Suppress("unused")
    @Composable
    private fun SponsorInfoDialogContent(
        amount: String,
        usageMessage: String,
        onSponsorClick: () -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = amount, style = MaterialTheme.typography.titleLarge)
            Text(text = usageMessage, style = MaterialTheme.typography.bodyMedium)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onSponsorClick) {
                    Text(text = getString(R.string.about_sponsor_link_text))
                }
            }
        }
    }

    @Composable
    private fun HtmlText(text: String, textAlign: TextAlign) {
        val textValue = remember(text) { UIUtils.htmlToSpannedText(text).toString() }
        Text(
            text = textValue,
            modifier = Modifier.fillMaxWidth(),
            style =
                MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = textAlign
                )
        )
    }

    private fun onAppExitInfoFailure() {
        showToastUiCentered(
            this,
            getString(R.string.log_file_not_available),
            Toast.LENGTH_SHORT
        )
        hideBugReportProgressUi()
    }

    private fun onAppExitInfoSuccess() {
        promptCrashLogAction()
    }

    private fun hideBugReportProgressUi() {
        aboutViewModel.setBugReportRunning(false)
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }

    private fun launchFileImport() {
        try {
            tunnelFileImportResultLauncher.launch("*/*")
        } catch (e: ActivityNotFoundException) {
            showToastUiCentered(
                this,
                getString(R.string.blocklist_update_check_failure),
                Toast.LENGTH_SHORT
            )
        } catch (e: Exception) {
            showToastUiCentered(
                this,
                getString(R.string.blocklist_update_check_failure),
                Toast.LENGTH_SHORT
            )
        }
    }

    private fun launchQrScanner() {
        try {
            qrImportResultLauncher.launch(
                ScanOptions()
                    .setOrientationLocked(false)
                    .setBeepEnabled(false)
                    .setPrompt(resources.getString(R.string.lbl_qr_code))
            )
        } catch (e: ActivityNotFoundException) {
            showToastUiCentered(
                this,
                getString(R.string.blocklist_update_check_failure),
                Toast.LENGTH_SHORT
            )
        } catch (e: Exception) {
            showToastUiCentered(
                this,
                getString(R.string.blocklist_update_check_failure),
                Toast.LENGTH_SHORT
            )
        }
    }
}
