/*
 * Copyright 2021 RethinkDNS and its authors
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
package com.celzero.bravedns.ui.activity

import androidx.activity.compose.setContent
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.appdetails.*
import com.celzero.bravedns.ui.compose.wireguard.WgControlState
import com.celzero.bravedns.ui.compose.wireguard.WgAlertDialogBuilder

import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Process
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagingData
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.AppWiseCountriesAdapter
import com.celzero.bravedns.adapter.AppWiseDomainsAdapter
import com.celzero.bravedns.adapter.AppWiseIpsAdapter
import com.celzero.bravedns.adapter.SummaryStatisticsAdapter
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.FirewallManager.updateFirewallStatus
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.ProxyManager.ID_NONE
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.ui.fragment.SummaryStatisticsFragment
import com.celzero.bravedns.ui.stats.CountryInsightsMapper
import com.celzero.bravedns.ui.stats.StatsInsightsMath
import com.celzero.bravedns.ui.stats.StatsViewMode
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import com.celzero.bravedns.util.Constants.Companion.RETHINK_PACKAGE
import com.celzero.bravedns.util.Constants.Companion.VIEW_PAGER_SCREEN_TO_LOAD
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.getCountryNameFromFlag
import com.celzero.bravedns.util.UIUtils.htmlToSpannedText
import com.celzero.bravedns.util.UIUtils.openAndroidAppInfo
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import com.celzero.bravedns.viewmodel.AppConnectionsViewModel
import com.celzero.bravedns.viewmodel.AppInfoViewModel
import com.celzero.bravedns.viewmodel.CustomDomainViewModel
import com.celzero.bravedns.viewmodel.CustomIpViewModel
import com.celzero.bravedns.viewmodel.SummaryStatisticsViewModel
import com.celzero.firestack.backend.Backend
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class AppInfoActivity : BaseActivity() {
    private val b = AppDetailsState()

    private val persistentState by inject<PersistentState>()
    private val eventLogger by inject<EventLogger>()
    private val appConfig by inject<AppConfig>()

    private val appInfoViewModel: AppInfoViewModel by viewModel()
    private val ipRulesViewModel: CustomIpViewModel by viewModel()
    private val domainRulesViewModel: CustomDomainViewModel by viewModel()
    private val networkLogsViewModel: AppConnectionsViewModel by viewModel()

    private var uid: Int = INVALID_UID
    private var requestedPackageName: String? = null
    private lateinit var appInfo: AppInfo

    private var appStatus = FirewallManager.FirewallStatus.NONE
    private var connStatus = FirewallManager.ConnectionStatus.ALLOW
    private var isWarningAcknowledged: Boolean = false
    private var notesDraft: String = ""
    private var shouldRestoreNotesDialog: Boolean = false
    private var isNotesSaveInFlight: Boolean = false
    private var notesDialog: android.app.Dialog? = null
    private var notesEditText: AppNotesDraft? = null

    // current presentation mode of the network-log sections; mirrors the stats
    // screen's persisted preference so both surfaces stay in sync
    private var statsViewMode: StatsViewMode
        get() = b.mode
        set(value) { b.mode = value }

    // latest snapshot per insights section, kept in sync with the list
    // adapters so switching to Insights renders instantly without refetching
    private val insightsSnapshots get() = b.insightsSnapshots

    // (adapter, observer) pairs registered for snapshot mirroring; unregistered
    // in onDestroy so no observer outlives the activity's view hierarchy
    private val insightsObservers =
        mutableListOf<Pair<AppConnectionsPresenter, () -> Unit>>()

    private val warningBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            finish()
        }
    }

    companion object {
        const val INTENT_UID = "UID"
        const val INTENT_PACKAGE_NAME = "PACKAGE_NAME"
        const val INTENT_ACTIVE_CONNS = "ACTIVE_CONNS"
        const val INTENT_ASN = "ASN"
        const val INTENT_COUNTRY = "COUNTRY"
        const val INTENT_IS_BLOCKED = "IS_BLOCKED"
        private const val TAG = "AppInfoActivity"

        // Temp allow duration constants
        private const val TEMP_ALLOW_DURATION_MINUTES = 15
        private const val MILLIS_PER_MINUTE = 60
        private const val MILLIS_PER_SECOND = 1000L
        private const val ALPHA_DISABLED = 0.5f
        private const val IS_WARNING_ACKNOWLEDGED = "IS_WARNING_ACKNOWLEDGED"
        private const val MAX_NOTE_LENGTH = 500
        private const val NOTES_DRAFT = "NOTES_DRAFT"
        private const val IS_NOTES_DIALOG_OPEN = "IS_NOTES_DIALOG_OPEN"

        // insights UI constants
        private const val PERCENTAGE_MULTIPLIER = 100

        // donut slices / ranking rows: top items only, one hue stepped by intensity
        private const val TOP_SLICES = 5
        private val SLICE_ALPHAS = intArrayOf(255, 190, 140, 100, 70)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)
        setContent { RethinkTheme { AppDetailsContent(b) } }
        handleFrostEffectIfNeeded(persistentState.theme)
        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = Themes.isActivityLightTheme(isDarkThemeOn(), persistentState.theme)
            window.isNavigationBarContrastEnforced = false
        }

        uid = intent.getIntExtra(INTENT_UID, INVALID_UID)
        requestedPackageName = intent.getStringExtra(INTENT_PACKAGE_NAME)
        Logger.d(LOG_TAG_UI, "AppInfoActivity, intent uid: $uid, package: $requestedPackageName")
        onBackPressedDispatcher.addCallback(this, warningBackCallback)

        if (savedInstanceState?.getBoolean(IS_WARNING_ACKNOWLEDGED, false) == true) {
            isWarningAcknowledged = true
        }
        notesDraft = savedInstanceState?.getString(NOTES_DRAFT).orEmpty()
        shouldRestoreNotesDialog = savedInstanceState?.getBoolean(IS_NOTES_DIALOG_OPEN, false) == true

        if (shouldShowRethinkWarning()) {
            showRethinkWarning()
        } else {
            proceedWithInit()
        }
    }

    private fun shouldShowRethinkWarning(): Boolean {
        return !isWarningAcknowledged && uid == Process.myUid()
    }

    private fun showRethinkWarning() {
        b.aadRethinkWarningContainer.visibility = View.VISIBLE
        warningBackCallback.isEnabled = true
        hideContentBehindWarning(true)

        b.aadRethinkWarningProceed.setOnClickListener {
            proceedWithInit()
        }
    }

    private fun proceedWithInit() {
        isWarningAcknowledged = true
        b.aadRethinkWarningContainer.visibility = View.GONE
        warningBackCallback.isEnabled = false
        hideContentBehindWarning(false)

        ipRulesViewModel.setUid(uid)
        domainRulesViewModel.setUid(uid)
        networkLogsViewModel.setUid(uid)
        initStatsViewMode()
        init()
        observeAppRules()
        observeNotesEvents()
        setupClickListeners()
    }

    private fun observeNotesEvents() {
        appInfoViewModel.notesSaveSuccessEvent.observe(this) { event ->
            event.getIfNotHandled()?.let { savedNotes ->
                isNotesSaveInFlight = false
                if (::appInfo.isInitialized) {
                    appInfo.notes = savedNotes
                    notesDraft = ""
                    if (notesDialog?.isShowing == true) {
                        notesEditText?.setText(savedNotes)
                        notesEditText?.setSelection(savedNotes.length)
                    }
                    logEvent(
                        "app notes updated",
                        "Notes updated for ${appInfo.appName} ($uid)"
                    )
                } else {
                    Logger.w(LOG_TAG_UI, "notes save success received before appInfo init for uid: $uid")
                }
            }
        }

        appInfoViewModel.notesErrorEvent.observe(this) { event ->
            event.getIfNotHandled()?.let { messageResId ->
                isNotesSaveInFlight = false
                showToastUiCentered(this, getString(messageResId), Toast.LENGTH_SHORT)
            }
        }
    }

    private fun hideContentBehindWarning(hide: Boolean) {
        val visibility = if (hide) View.GONE else View.VISIBLE
        b.aadAppParentRl.visibility = visibility

    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(IS_WARNING_ACKNOWLEDGED, isWarningAcknowledged)
        val isDialogOpen = notesDialog?.isShowing == true
        outState.putBoolean(IS_NOTES_DIALOG_OPEN, isDialogOpen)
        if (isDialogOpen) {
            outState.putString(NOTES_DRAFT, notesEditText?.text?.toString().orEmpty())
        } else {
            outState.putString(NOTES_DRAFT, "")
        }
    }

    private fun restoreNotesDialogIfNeeded() {
        if (!shouldRestoreNotesDialog) return
        val draft = notesDraft
        shouldRestoreNotesDialog = false
        showNotesDialog(draft)
    }

    private fun observeAppRules() {
        ipRulesViewModel.ipRulesCount(uid).observe(this) { b.aadIpBlockHeader.text = it.toString() }

        domainRulesViewModel.domainRulesCount(uid).observe(this) {
            b.aadDomainBlockHeader.text = it.toString()
        }
    }

    private fun init() {
        io {
            val appInfo = if (requestedPackageName.isNullOrBlank()) {
                FirewallManager.getAppInfoByUid(uid)
            } else {
                FirewallManager.getAppInfoByUidAndPackage(uid, requestedPackageName)
                    ?: FirewallManager.getAppInfoByUid(uid)
            }
            // case: app is uninstalled but still available in RethinkDNS database
            if (appInfo == null || uid == INVALID_UID || appInfo.tombstoneTs > 0) {
                uiCtx { showNoAppFoundDialog() }
                return@io
            }

            val packages = FirewallManager.getPackageNamesByUid(appInfo.uid)
            appStatus = FirewallManager.appStatus(appInfo.uid)
            connStatus = FirewallManager.connectionStatus(appInfo.uid)
            uiCtx {
                this.appInfo = appInfo

                b.aadAppDetailName.text = appName(packages.count())
                b.aadPkgName.text = getString(R.string.app_id_package, appInfo.uid, appInfo.packageName)
                b.excludeProxySwitch.isChecked = appInfo.isProxyExcluded
                
                // Set temporary allow toggle state
                val isTempAllowed = FirewallManager.isTempAllowed(appInfo.uid)
                b.tempAllowSwitch.isChecked = isTempAllowed
                updateTempAllowDescription(isTempAllowed, appInfo.tempAllowExpiryTime)
                
                displayDataUsage()
                displayProxyStatus()
                displayIcon(
                    Utilities.getIcon(this, appInfo.packageName, appInfo.appName),
                    b.aadAppDetailIcon
                )

                if (appInfo.packageName == RETHINK_PACKAGE) {
                    updateFirewallStatusUi(appStatus, connStatus)
                    setActiveConnsAdapter(true)
                    setRethinkDomainLogsAdapter()
                    setRethinkIpLogsAdapter()
                    setRethinkCountriesAdapter()
                } else {
                    updateFirewallStatusUi(appStatus, connStatus)
                    setActiveConnsAdapter(false)
                    if (persistentState.downloadIpInfo) {
                        setASNAdapter()
                    }
                    setDomainsAdapter()
                    setIpAdapter()
                    setCountriesAdapter()
                }
                setBlocklistAdapter()

                // disable exclude app option for apps with no package name
                if (FirewallManager.isUnknownPackage(uid)) {
                    b.aadAppSettingsExclude.alpha = ALPHA_DISABLED
                    b.aadAppSettingsExclude.isEnabled = false
                } else {
                    b.aadAppSettingsExclude.alpha = 1.0f
                    b.aadAppSettingsExclude.isEnabled = true
                }

                restoreNotesDialogIfNeeded()

                // first insights render now that the adapters (and their
                // snapshot observers) are wired; later renders are data-driven

            }
        }
    }

    private fun displayProxyStatus() {
        io {
            val proxy = ProxyManager.getProxyIdForApp(uid)
            val proxyNames = fetchProxyNames(proxy)
            uiCtx {
                if (proxyNames.isEmpty() || (proxyNames.size == 1 && proxyNames[0] == ID_NONE)) {
                    b.aadProxyDetails.visibility = View.GONE
                    return@uiCtx
                }
                b.aadProxyDetails.visibility = View.VISIBLE
                b.aadProxyDetails.text =
                    getString(
                        R.string.wireguard_apps_proxy_map_desc,
                        proxyNames.filter { it != ID_NONE })
            }
        }
    }

    private suspend fun fetchProxyNames(proxy: List<String>): List<String> {
        val names: MutableList<String> = mutableListOf()
        // in case of RPN show the name else return the same
        proxy.forEach {
            if (it.startsWith(Backend.RpnWin)) {
                val k = it.substring(Backend.RpnWin.length)
                var name = RpnProxyManager.getCountryConfigByKey(k)?.name ?: it
                if (name == RpnProxyManager.AUTO_SERVER_ID) {
                    name = getString(R.string.rpn_title) + " " + getString(R.string.server_settings_config_mode_auto)
                } else {
                    name = getString(R.string.rpn_title) + " " + name
                }
                names.add(name)
            } else {
                names.add(it)
            }
        }
        return names
    }

    private fun openCustomIpScreen() {
        val intent = Intent(this, CustomRulesActivity::class.java)
        intent.putExtra(VIEW_PAGER_SCREEN_TO_LOAD, CustomRulesActivity.Tabs.IP_RULES.screen)
        intent.putExtra(Constants.INTENT_UID, uid)
        startActivity(intent)
    }

    private fun openCustomDomainScreen() {
        val intent = Intent(this, CustomRulesActivity::class.java)
        intent.putExtra(VIEW_PAGER_SCREEN_TO_LOAD, CustomRulesActivity.Tabs.DOMAIN_RULES.screen)
        intent.putExtra(Constants.INTENT_UID, uid)
        startActivity(intent)
    }

    private fun displayDataUsage() {
        if (!::appInfo.isInitialized) {
            Logger.w(LOG_TAG_UI, "AppInfo not initialized yet in displayDataUsage")
            // Set default values when appInfo is not available
            b.aadDataUsageStatus.text = getString(R.string.two_argument,
                getString(R.string.symbol_upload, "0 B"),
                getString(R.string.symbol_download, "0 B"))
            return
        }

        val u = Utilities.humanReadableByteCount(appInfo.uploadBytes, true)
        val uploadBytes = getString(R.string.symbol_upload, u)
        val d = Utilities.humanReadableByteCount(appInfo.downloadBytes, true)
        val downloadBytes = getString(R.string.symbol_download, d)
        b.aadDataUsageStatus.text = getString(R.string.two_argument, uploadBytes, downloadBytes)
    }

    /**
     * Restores the persisted presentation mode, styles the toggle and attaches
     * its listener. Must run before data observation begins so the first
     * landing on either presentation is consistent.
     */
    private fun initStatsViewMode() {
        statsViewMode = StatsViewMode.fromId(persistentState.statsViewMode)
        b.onMode = { mode ->
            if (mode != statsViewMode) {
                statsViewMode = mode
                persistentState.statsViewMode = mode.id
                applyViewModeUi()
            }
        }
        setupInsightsClickListeners()
        applyViewModeUi()
    }

    /** Flips the two presentation containers; does not touch any data state. */
    private fun applyViewModeUi() {
        val insights = statsViewMode == StatsViewMode.INSIGHTS
        b.aadListContainer.visibility = if (insights) View.GONE else View.VISIBLE
        b.aadInsightsContainer.visibility = if (insights) View.VISIBLE else View.GONE
        refreshLogToggleVisibility()

    }

    /**
     * The list/insights toggle only makes sense when at least one log section
     * has content. Sections stay hidden until their data arrives (and are
     * hidden again when a feature or dataset is unavailable), so the toggle's
     * visibility can simply mirror the section containers themselves.
     */
    private fun refreshLogToggleVisibility() {
        val anySectionVisible = listOf(
            b.aadActiveConnsRl,
            b.aadMostContactedCountriesRl,
            b.aadAsnRl,
            b.aadBlockedAsnRl,
            b.aadBlocklistRl,
            b.aadMostContactedDomainRl,
            b.aadBlockedDomainsRl,
            b.aadMostContactedIpsRl,
            b.aadBlockedIpsRl
        ).any { it.visibility == View.VISIBLE }
        b.aadViewModeToggleGroup.visibility =
            if (anySectionVisible) View.VISIBLE else View.GONE
    }

    private fun setupInsightsClickListeners() {
        b.aadIaActiveConnsChip.setOnClickListener { openAppWiseDomainLogsActivity(activeConns = true) }
        b.aadIaDomainsChip.setOnClickListener { openAppWiseDomainLogsActivity(isBlocked = false) }
        b.aadIaIpsChip.setOnClickListener { openAppWiseIpLogsActivity(isBlocked = false) }
        b.aadIaCountriesChip.setOnClickListener { openAppWiseIpLogsActivity(country = true) }
        b.aadIaAsnChip.setOnClickListener { openAppWiseIpLogsActivity(asn = true, isBlocked = false) }
        // blocked domains drill into the same detail screen the stats screen
        // uses; blocklists keep the per-blocklist drill-down
        b.aadIaBlockedDomainsChip.setOnClickListener { openBlockedDomainsUi() }
        b.aadIaBlockedIpsChip.setOnClickListener { openAppWiseIpLogsActivity(isBlocked = true) }
        b.aadIaBlockedAsnChip.setOnClickListener { openAppWiseIpLogsActivity(asn = true, isBlocked = true) }
        b.aadIaBlocklistChip.setOnClickListener { openDetailedBlocklistsUi() }
    }

    /**
     * Opens the same per-type drill-down the stats screen uses, scoped to
     * this app's uid; the detail screen recomputes for the 7-day window.
     */
    private fun openDetailedStatsUi(type: SummaryStatisticsFragment.SummaryStatisticsType) {
        val intent = Intent(this, DetailedStatisticsActivity::class.java)
        intent.putExtra(DetailedStatisticsActivity.INTENT_TYPE, type.tid)
        intent.putExtra(
            DetailedStatisticsActivity.INTENT_TIME_CATEGORY,
            SummaryStatisticsViewModel.TimeCategory.SEVEN_DAYS.value
        )
        intent.putExtra(DetailedStatisticsActivity.INTENT_UID, uid)
        startActivity(intent)
    }

    private fun openDetailedBlocklistsUi() {
        openDetailedStatsUi(SummaryStatisticsFragment.SummaryStatisticsType.MOST_BLOCKED_BLOCKLISTS)
    }

    /**
     * Blocked domains drill down into the per-type detail screen like the
     * stats screen does. Rethink's own blocked domains live in RethinkLog,
     * which the detail screen does not read, so they stay on the app-scoped
     * domain log list.
     */
    private fun openBlockedDomainsUi() {
        if (android.os.Process.myUid() == uid) {
            openAppWiseDomainLogsActivity(isBlocked = true)
        } else {
            openDetailedStatsUi(SummaryStatisticsFragment.SummaryStatisticsType.MOST_BLOCKED_DOMAINS)
        }
    }

    /**
     * Wires a blocked-activity section for BOTH presentations: the RecyclerView
     * backs the list view (title + rows hidden when the section is empty) while
     * the snapshot hook feeds the matching insights section.
     */
    private fun setupBlockedSection(
        containerView: WgControlState,
        recyclerView: AppDetailListState,
        section: AppInsightsSection,
        data: LiveData<PagingData<AppConnection>>,
        adapter: AppConnectionsPresenter
    ) {
        // wrap_content height: data changes resize the view, so fixed-size must
        // stay off and animations disabled to avoid flicker on updates
        hookInsightsSnapshot(section, adapter)
        data.observe(this) {
            adapter.submitData(this.lifecycle, it)
        }
        recyclerView.adapter = adapter

        // hide both the title and the list when the section has no entries
        adapter.addLoadStateListener {
            if (it.append.endOfPaginationReached) {
                containerView.visibility =
                    if (adapter.itemCount >= 1) View.VISIBLE else View.GONE
                refreshLogToggleVisibility()
            }
        }
    }

    /**
     * Mirrors adapter list changes into [insightsSnapshots] and re-renders the
     * matching Insights section (only when the Insights view is visible).
     */
    private fun hookInsightsSnapshot(section: AppInsightsSection, adapter: AppConnectionsPresenter) {
        val observer: () -> Unit = { insightsSnapshots[section] = adapter.snapshot().items }
        adapter.addSnapshotListener(observer)
        insightsObservers.add(adapter to observer)
    }

    override fun onDestroy() {
        // detach snapshot observers before the view tree goes away so nothing
        // holds a reference to the (dying) adapters or row containers
        insightsObservers.forEach { (adapter, observer) ->
            runCatching { adapter.removeSnapshotListener(observer) }
        }
        insightsObservers.clear()
        insightsSnapshots.clear()
        super.onDestroy()
    }

    private fun updateFirewallStatusUi(
        firewallStatus: FirewallManager.FirewallStatus,
        connectionStatus: FirewallManager.ConnectionStatus
    ) {
        val statusText = getFirewallText(firewallStatus, connectionStatus)
        val statusWithTime = if (::appInfo.isInitialized && appInfo.modifiedTs > 0) {
            val now = System.currentTimeMillis()
            val uptime = now - appInfo.modifiedTs
            val relativeTime = DateUtils.getRelativeTimeSpanString(
                now - uptime,
                now,
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            )
            "$statusText $relativeTime"
        } else {
            statusText
        }

        b.aadFirewallStatus.text =
            htmlToSpannedText(
                getString(
                    R.string.ada_firewall_status,
                    statusWithTime
                )
            )

        when (firewallStatus) {
            FirewallManager.FirewallStatus.EXCLUDE -> {
                enableAppExcludedUi()
            }
            FirewallManager.FirewallStatus.BYPASS_UNIVERSAL -> {
                enableAppBypassedUi()
            }
            FirewallManager.FirewallStatus.ISOLATE -> {
                enableIsolateUi()
            }
            FirewallManager.FirewallStatus.BYPASS_DNS_FIREWALL -> {
                enableDnsFirewallBypassedUi()
            }
            FirewallManager.FirewallStatus.NONE -> {
                when (connectionStatus) {
                    FirewallManager.ConnectionStatus.ALLOW -> {
                        enableAllow()
                    }
                    else -> {
                        enableBlock(connectionStatus)
                    }
                }
                disableWhitelistExcludeUi()
            }
        }
    }

    private fun setupClickListeners() {

        b.aadAppInfoIcon.setOnClickListener {
            if (!::appInfo.isInitialized) {
                Logger.w(LOG_TAG_UI, "AppInfo not initialized yet in aadAppInfoIcon click listener, using uid: $uid")
                showToastUiCentered(
                    this,
                    this.getString(R.string.ctbs_app_info_not_available_toast),
                    Toast.LENGTH_SHORT
                )
                return@setOnClickListener
            }

            io {
                val appNames = FirewallManager.getAppNamesByUid(uid)
                uiCtx {
                    if (appNames.count() == 1) {
                        openAndroidAppInfo(this, appInfo.packageName)
                    } else if (appNames.count() > 1) {
                        showAppInfoDialog(appNames)
                    } else {
                        showToastUiCentered(
                            this,
                            this.getString(R.string.ctbs_app_info_not_available_toast),
                            Toast.LENGTH_SHORT
                        )
                    }
                }
            }
        }


        b.aadAppSettingsBypassDnsFirewall.setOnClickListener {
            guardAppInfoInitialized("aadAppSettingsBypassDnsFirewall") {
                if (appStatus == FirewallManager.FirewallStatus.BYPASS_DNS_FIREWALL) {
                    updateFirewallStatus(
                        FirewallManager.FirewallStatus.NONE,
                        FirewallManager.ConnectionStatus.ALLOW
                    )
                    logEvent(
                        "firewall rule change",
                        "DNS + Firewall bypass disabled for ${appInfo.appName} (${appInfo.uid})"
                    )
                } else {
                    updateFirewallStatus(
                        FirewallManager.FirewallStatus.BYPASS_DNS_FIREWALL,
                        FirewallManager.ConnectionStatus.ALLOW
                    )
                    logEvent(
                        "firewall rule change",
                        "DNS + Firewall bypass enabled for ${appInfo.appName} (${appInfo.uid})"
                    )
                }
            }
        }

        b.aadAppSettingsBlockWifi.setOnClickListener {
            guardAppInfoInitialized("aadAppSettingsBlockWifi") {
                toggleWifi(appInfo)
                updateFirewallStatusUi(appStatus, connStatus)
            }
        }

        b.aadAppSettingsBlockMd.setOnClickListener {
            guardAppInfoInitialized("aadAppSettingsBlockMd") {
                toggleMobileData(appInfo)
                updateFirewallStatusUi(appStatus, connStatus)
            }
        }

        b.aadAppSettingsBypassUniv.setOnClickListener {
            guardAppInfoInitialized("aadAppSettingsBypassUniv") {
                // change the status to allowed if already app is bypassed
                if (appStatus == FirewallManager.FirewallStatus.BYPASS_UNIVERSAL) {
                    updateFirewallStatus(
                        FirewallManager.FirewallStatus.NONE,
                        FirewallManager.ConnectionStatus.ALLOW
                    )
                    logEvent(
                        "firewall rule change",
                        "Universal bypass disabled for ${appInfo.appName} (${appInfo.uid})"
                    )
                } else {
                    updateFirewallStatus(
                        FirewallManager.FirewallStatus.BYPASS_UNIVERSAL,
                        FirewallManager.ConnectionStatus.ALLOW
                    )
                    logEvent(
                        "firewall rule change",
                        "Universal bypass enabled for ${appInfo.appName} (${appInfo.uid})"
                    )
                }
            }
        }

        b.aadAppSettingsExclude.setOnClickListener {
            guardAppInfoInitialized("aadAppSettingsExclude") {
                if (VpnController.isVpnLockdown()) {
                    showToastUiCentered(this, getString(R.string.hsf_exclude_error), Toast.LENGTH_SHORT)
                    return@guardAppInfoInitialized
                }

                io {
                    if (FirewallManager.isUnknownPackage(uid) && appStatus == FirewallManager.FirewallStatus.EXCLUDE) {
                        uiCtx {
                            showToastUiCentered(
                                this,
                                getString(R.string.exclude_no_package_err_toast),
                                Toast.LENGTH_LONG
                            )
                        }
                        return@io
                    }

                    // change the status to allowed if already app is excluded
                    if (appStatus == FirewallManager.FirewallStatus.EXCLUDE) {
                        updateFirewallStatus(
                            FirewallManager.FirewallStatus.NONE,
                            FirewallManager.ConnectionStatus.ALLOW
                        )
                        logEvent(
                            "firewall rule change",
                            "App exclusion disabled for ${appInfo.appName} (${appInfo.uid})"
                        )
                    } else {
                        updateFirewallStatus(
                            FirewallManager.FirewallStatus.EXCLUDE,
                            FirewallManager.ConnectionStatus.ALLOW
                        )
                        logEvent(
                            "firewall rule change",
                            "App exclusion enabled for ${appInfo.appName} (${appInfo.uid})"
                        )
                    }
                }
            }
        }

        b.aadAppSettingsIsolate.setOnClickListener {
            guardAppInfoInitialized("aadAppSettingsIsolate") {
                // change the status to allowed if already app is isolated
                if (appStatus == FirewallManager.FirewallStatus.ISOLATE) {
                    updateFirewallStatus(
                        FirewallManager.FirewallStatus.NONE,
                        FirewallManager.ConnectionStatus.ALLOW
                    )
                    logEvent(
                        "firewall rule change",
                        "App isolation disabled for ${appInfo.appName} (${appInfo.uid})"
                    )
                } else {
                    updateFirewallStatus(
                        FirewallManager.FirewallStatus.ISOLATE,
                        FirewallManager.ConnectionStatus.ALLOW
                    )
                    logEvent(
                        "firewall rule change",
                        "App isolation enabled for ${appInfo.appName} (${appInfo.uid})"
                    )
                }
            }
        }

        b.aadIpBlockCard.setOnClickListener { openCustomIpScreen() }

        b.aadDomainBlockCard.setOnClickListener { openCustomDomainScreen() }

        b.aadIpsChip.setOnClickListener { openAppWiseIpLogsActivity(isBlocked = false) }

        b.aadDomainsChip.setOnClickListener { openAppWiseDomainLogsActivity(isBlocked = false) }

        b.aadBlockedAsnChip.setOnClickListener { openAppWiseIpLogsActivity(asn = true, isBlocked = true) }

        b.aadBlockedDomainsChip.setOnClickListener { openBlockedDomainsUi() }

        b.aadBlockedIpsChip.setOnClickListener { openAppWiseIpLogsActivity(isBlocked = true) }

        b.aadBlocklistChip.setOnClickListener { openDetailedBlocklistsUi() }

        b.aadActiveConnsChip.setOnClickListener { openAppWiseDomainLogsActivity(activeConns = true) }

        b.aadAsnChip.setOnClickListener { openAppWiseIpLogsActivity(asn = true, isBlocked = false) }

        b.aadCountriesChip.setOnClickListener { openAppWiseIpLogsActivity(country = true) }

        b.excludeProxySwitch.setOnCheckedChangeListener { _, isChecked ->
            updateExcludeProxyStatus(isChecked)
        }

        b.excludeProxyRl.setOnClickListener {
            b.excludeProxySwitch.isChecked = !b.excludeProxySwitch.isChecked
        }

        b.tempAllowSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateTempAllowStatus(isChecked)
        }

        b.tempAllowRl.setOnClickListener {
            b.tempAllowSwitch.isChecked = !b.tempAllowSwitch.isChecked
        }

        b.aadCloseConnsChip.setOnClickListener {
            if (!::appInfo.isInitialized) {
                Logger.w(LOG_TAG_UI, "AppInfo not initialized yet in aadCloseConnsChip click listener, using uid: $uid")
                showCloseConnectionDialog(uid, "Unknown App")
                return@setOnClickListener
            }

            showCloseConnectionDialog(uid, appInfo.appName)
        }

        b.aadNotesChip.setOnClickListener {
            if (isNotesSaveInFlight) {
                showToastUiCentered(this, getString(R.string.lbl_saving), Toast.LENGTH_SHORT)
                return@setOnClickListener
            }
            guardAppInfoInitialized("aadNotesChip") {
                showNotesDialog()
            }
        }
    }

    private fun updateExcludeProxyStatus(isExcluded: Boolean) {
        io {
            FirewallManager.updateIsProxyExcluded(uid, isExcluded)
            if (::appInfo.isInitialized) {
                logEvent(
                    "proxy exclude change",
                    "Proxy exclude status changed for ${appInfo.appName} (${appInfo.uid}), new status: $isExcluded"
                )
            } else {
                logEvent(
                    "proxy exclude change",
                    "Proxy exclude status changed for uid: $uid, new status: $isExcluded"
                )
            }
        }
    }

    private fun updateTempAllowStatus(isAllowed: Boolean) {
        io {
            if (isAllowed) {
                FirewallManager.updateTempAllow(uid, true)
                val expiryTime = System.currentTimeMillis() + (TEMP_ALLOW_DURATION_MINUTES * MILLIS_PER_MINUTE * MILLIS_PER_SECOND)
                uiCtx {
                    updateTempAllowDescription(true, expiryTime)
                }
            } else {
                FirewallManager.updateTempAllow(uid, false)
                uiCtx {
                    updateTempAllowDescription(false, 0)
                }
            }
            if (::appInfo.isInitialized) {
                logEvent(
                    "temp allow change",
                    "Temporary allow status changed for ${appInfo.appName} (${appInfo.uid}), new status: $isAllowed"
                )
            } else {
                logEvent(
                    "temp allow change",
                    "Temporary allow status changed for uid: $uid, new status: $isAllowed"
                )
            }
        }
    }

    private fun updateTempAllowDescription(isAllowed: Boolean, expiryTime: Long) {
        if (isAllowed && expiryTime > 0) {
            val now = System.currentTimeMillis()
            if (expiryTime > now) {
                val relativeTime = DateUtils.getRelativeTimeSpanString(
                    expiryTime,
                    now,
                    DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE
                )
                b.tempAllowDesc.text = getString(R.string.temp_allow_active, relativeTime)
            } else {
                b.tempAllowDesc.text = getString(R.string.temp_allow_desc)
            }
        } else {
            b.tempAllowDesc.text = getString(R.string.temp_allow_desc)
        }
    }

    private fun openAppWiseDomainLogsActivity(
        activeConns: Boolean = false,
        isBlocked: Boolean? = null
    ) {
        val intent = Intent(this, AppWiseDomainLogsActivity::class.java)
        intent.putExtra(INTENT_UID, uid)
        intent.putExtra(INTENT_ACTIVE_CONNS, activeConns)
        isBlocked?.let { intent.putExtra(INTENT_IS_BLOCKED, it) }
        startActivity(intent)
    }

    private fun openAppWiseIpLogsActivity(
        asn: Boolean = false,
        country: Boolean = false,
        isBlocked: Boolean? = null
    ) {
        val intent = Intent(this, AppWiseIpLogsActivity::class.java)
        intent.putExtra(INTENT_UID, uid)
        intent.putExtra(INTENT_ASN, asn)
        intent.putExtra(INTENT_COUNTRY, country)
        isBlocked?.let { intent.putExtra(INTENT_IS_BLOCKED, it) }
        startActivity(intent)
    }

    private fun setActiveConnsAdapter(isRethink: Boolean) {
        val adapter = AppWiseDomainsAdapter(this, this, uid, isActiveConn = true)
        hookInsightsSnapshot(AppInsightsSection.ACTIVE_CONNS, adapter)
        val uptime = VpnController.uptimeMs()
        Logger.i(LOG_TAG_UI, "app-info-act, active conns, uptime: $uptime ms")
        if (isRethink) {
            networkLogsViewModel.getRethinkActiveConnsLimited(uptime).observe(this) {
                adapter.submitData(this.lifecycle, it)
            }
        } else {
            networkLogsViewModel.fetchTopActiveConnections(uid, uptime).observe(this) {
                adapter.submitData(this.lifecycle, it)
            }
        }
        b.aadActiveConnsRv.adapter = adapter

        adapter.addLoadStateListener {
            if (it.append.endOfPaginationReached) {
                if (adapter.itemCount >= 1) {
                    b.aadActiveConnsRl.visibility = View.VISIBLE
                } else {
                    b.aadActiveConnsRl.visibility = View.GONE
                }
                refreshLogToggleVisibility()
            }
        }
    }

    private fun setASNAdapter() {
        val adapter = AppWiseIpsAdapter(this, this, uid,  isAsn = true)
        hookInsightsSnapshot(AppInsightsSection.ASN, adapter)
        // blocked providers: same list+insights wiring as the allowed section,
        // rendered ASN-style (no per-IP bottom sheet)
        setupBlockedSection(
            b.aadBlockedAsnRl,
            b.aadBlockedAsnRv,
            AppInsightsSection.BLOCKED_ASN,
            networkLogsViewModel.getBlockedAsnLogsLimited(uid),
            AppWiseIpsAdapter(this, this, uid, isAsn = true)
        )
        networkLogsViewModel.getAsnLogsLimited(uid).observe(this) {
            adapter.submitData(this.lifecycle, it)
        }
        b.aadAsnRv.adapter = adapter

        adapter.addLoadStateListener {
            if (it.append.endOfPaginationReached) {
                if (adapter.itemCount >= 1) {
                    b.aadAsnRl.visibility = View.VISIBLE
                } else {
                    b.aadAsnRl.visibility = View.GONE
                }
                refreshLogToggleVisibility()
            }
        }
    }

    private fun setCountriesAdapter() {
        setupCountriesAdapter(networkLogsViewModel.getMostContactedCountriesLimited(uid))
    }

    private fun setRethinkCountriesAdapter() {
        // rethink's own logs are stored in RethinkLog, so countries are read
        // from there instead of the uid-filtered ConnectionTracker query
        setupCountriesAdapter(networkLogsViewModel.getRethinkCountriesLimited())
    }

    /**
     * Wires the most-blocked-blocklists section. Unlike the other sections,
     * the data is aggregated in memory (the blocklist CSV cannot be grouped in
     * SQL), so it arrives as a static list converted into static PagingData;
     * visibility is driven by the list itself. Blocklist blocking only exists
     * when dns is active, so the section stays hidden otherwise.
     */
    private fun setBlocklistAdapter() {
        if (!appConfig.getBraveMode().isDnsActive()) {
            b.aadBlocklistRl.visibility = View.GONE
            refreshLogToggleVisibility()
            return
        }
        val adapter = SummaryStatisticsAdapter(
            this,
            persistentState,
            appConfig,
            SummaryStatisticsFragment.SummaryStatisticsType.MOST_BLOCKED_BLOCKLISTS
        )
        // scope row-click drill-downs to this app: without it, tapping a
        // blocklist opens the device-wide list instead of this app's domains
        adapter.setUid(uid)
        adapter.setTimeCategory(SummaryStatisticsViewModel.TimeCategory.SEVEN_DAYS)
        hookInsightsSnapshot(AppInsightsSection.BLOCKLISTS, adapter)
        networkLogsViewModel.mostBlockedBlocklists.observe(this) { items ->
            adapter.submitData(this.lifecycle, PagingData.from(items))
            b.aadBlocklistRl.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
            refreshLogToggleVisibility()
        }
        b.aadBlocklistRv.adapter = adapter
    }

    private fun setupCountriesAdapter(data: LiveData<PagingData<AppConnection>>) {
        // wrap_content height: data changes resize the view, so fixed-size must
        // stay off and animations disabled to avoid flicker on updates
        val adapter = AppWiseCountriesAdapter(this, this)
        hookInsightsSnapshot(AppInsightsSection.COUNTRIES, adapter)
        data.observe(this) {
            adapter.submitData(this.lifecycle, it)
        }
        b.aadMostContactedCountriesRv.adapter = adapter

        // hide both the title and the list when this app has no country entries
        adapter.addLoadStateListener {
            if (it.append.endOfPaginationReached) {
                b.aadMostContactedCountriesRl.visibility =
                    if (adapter.itemCount >= 1) View.VISIBLE else View.GONE
                refreshLogToggleVisibility()
            }
        }
    }

    private fun setDomainsAdapter() {
        val adapter = AppWiseDomainsAdapter(this, this, uid)
        hookInsightsSnapshot(AppInsightsSection.DOMAINS, adapter)
        setupBlockedSection(
            b.aadBlockedDomainsRl,
            b.aadBlockedDomainsRv,
            AppInsightsSection.BLOCKED_DOMAINS,
            networkLogsViewModel.getBlockedDomainLogsLimited(uid),
            AppWiseDomainsAdapter(this, this, uid)
        )
        networkLogsViewModel.getDomainLogsLimited(uid).observe(this) {
            adapter.submitData(this.lifecycle, it)
        }
        b.aadMostContactedDomainRv.adapter = adapter

        adapter.addLoadStateListener {
            if (it.append.endOfPaginationReached) {
                if (adapter.itemCount >= 1) {
                    b.aadMostContactedDomainRl.visibility = View.VISIBLE
                } else {
                    b.aadMostContactedDomainRl.visibility = View.GONE
                }
                refreshLogToggleVisibility()
            }
        }
    }

    private fun setRethinkDomainLogsAdapter() {
        val adapter = AppWiseDomainsAdapter(this, this, uid)
        hookInsightsSnapshot(AppInsightsSection.DOMAINS, adapter)
        setupBlockedSection(
            b.aadBlockedDomainsRl,
            b.aadBlockedDomainsRv,
            AppInsightsSection.BLOCKED_DOMAINS,
            networkLogsViewModel.getRethinkBlockedDomainLogsLimited(),
            AppWiseDomainsAdapter(this, this, uid)
        )
        networkLogsViewModel.getRethinkDomainLogsLimited().observe(this) {
            adapter.submitData(this.lifecycle, it)
        }
        b.aadMostContactedDomainRv.adapter = adapter

        adapter.addLoadStateListener {
            if (it.append.endOfPaginationReached) {
                if (adapter.itemCount >= 1) {
                    b.aadMostContactedDomainRl.visibility = View.VISIBLE
                } else {
                    b.aadMostContactedDomainRl.visibility = View.GONE
                }
                refreshLogToggleVisibility()
            }
        }
    }

    private fun setRethinkIpLogsAdapter() {
        val adapter = AppWiseIpsAdapter(this, this, uid)
        hookInsightsSnapshot(AppInsightsSection.IPS, adapter)
        setupBlockedSection(
            b.aadBlockedIpsRl,
            b.aadBlockedIpsRv,
            AppInsightsSection.BLOCKED_IPS,
            networkLogsViewModel.getRethinkBlockedIpLogsLimited(),
            AppWiseIpsAdapter(this, this, uid)
        )
        networkLogsViewModel.getRethinkIpLogsLimited().observe(this) {
            adapter.submitData(this.lifecycle, it)
        }
        b.aadMostContactedIpsRv.adapter = adapter

        adapter.addLoadStateListener {
            if (it.append.endOfPaginationReached) {
                if (adapter.itemCount >= 1) {
                    b.aadMostContactedIpsRl.visibility = View.VISIBLE
                } else {
                    b.aadMostContactedIpsRl.visibility = View.GONE
                }
                refreshLogToggleVisibility()
            }
        }
    }

    private fun setIpAdapter() {
        val adapter = AppWiseIpsAdapter(this, this, uid)
        hookInsightsSnapshot(AppInsightsSection.IPS, adapter)
        setupBlockedSection(
            b.aadBlockedIpsRl,
            b.aadBlockedIpsRv,
            AppInsightsSection.BLOCKED_IPS,
            networkLogsViewModel.getBlockedIpLogsLimited(uid),
            AppWiseIpsAdapter(this, this, uid)
        )
        networkLogsViewModel.getIpLogsLimited(uid).observe(this) {
            adapter.submitData(this.lifecycle, it)
        }
        b.aadMostContactedIpsRv.adapter = adapter

        adapter.addLoadStateListener {
            if (it.append.endOfPaginationReached) {
                if (adapter.itemCount >= 1) {
                    b.aadMostContactedIpsRl.visibility = View.VISIBLE
                } else {
                    b.aadMostContactedIpsRl.visibility = View.GONE
                }
                refreshLogToggleVisibility()
            }
        }
    }

    private fun showAppInfoDialog(appNames: List<String>) {
        showAppDetailsPackageDialog(this, appNames) { position ->
            io {
                val pkg = FirewallManager.getPackageNameByAppName(appNames[position])
                uiCtx {
                    Logger.i(LOG_TAG_UI, "AppInfoActivity, package name: $pkg")
                    openAndroidAppInfo(applicationContext, pkg)
                }
            }
        }
    }

    private fun toggleMobileData(appInfo: AppInfo) {
        // toggle mobile data: change the connection status based on the current status.
        // if allow -> none(app status) + metered(connection status)
        // if unmetered -> none(app status) + both(connection status)
        // if metered -> none(app status) + allow(connection status)
        // if both -> none(app status) + unmetered(connection status)

        io {
            val connStatus = FirewallManager.connectionStatus(appInfo.uid)
            uiCtx {
                val cStat =
                    when (connStatus) {
                        FirewallManager.ConnectionStatus.METERED -> {
                            FirewallManager.ConnectionStatus.ALLOW
                        }
                        FirewallManager.ConnectionStatus.UNMETERED -> {
                            FirewallManager.ConnectionStatus.BOTH
                        }
                        FirewallManager.ConnectionStatus.BOTH -> {
                            FirewallManager.ConnectionStatus.UNMETERED
                        }
                        FirewallManager.ConnectionStatus.ALLOW -> {
                            FirewallManager.ConnectionStatus.METERED
                        }
                    }
                updateFirewallStatus(FirewallManager.FirewallStatus.NONE, cStat, connStatus)
            }
        }
    }

    private fun toggleWifi(appInfo: AppInfo) {
        // toggle wifi: change the connection status based on the current status.
        // if Wifi -> none(app status) + wifi(connection status)
        // if MOBILE DATA -> none(app status) + both(connection status)
        // if BOTH -> none(app status) + mobile data(connection status)
        // if ALLOW -> none(app status) + wifi(connection status)

        io {
            val connStatus = FirewallManager.connectionStatus(appInfo.uid)
            uiCtx {
                val cStat =
                    when (connStatus) {
                        FirewallManager.ConnectionStatus.UNMETERED -> {
                            FirewallManager.ConnectionStatus.ALLOW
                        }
                        FirewallManager.ConnectionStatus.BOTH -> {
                            FirewallManager.ConnectionStatus.METERED
                        }
                        FirewallManager.ConnectionStatus.METERED -> {
                            FirewallManager.ConnectionStatus.BOTH
                        }
                        FirewallManager.ConnectionStatus.ALLOW -> {
                            FirewallManager.ConnectionStatus.UNMETERED
                        }
                    }

                updateFirewallStatus(FirewallManager.FirewallStatus.NONE, cStat, connStatus)
                logEvent(
                    "firewall rule change",
                    "Toggled WIFI for ${appInfo.appName} (${appInfo.uid}), new conn status: $cStat"
                )
            }
        }
    }

    private fun updateFirewallStatus(
        aStat: FirewallManager.FirewallStatus,
        cStat: FirewallManager.ConnectionStatus,
        prevConnStat: FirewallManager.ConnectionStatus = FirewallManager.ConnectionStatus.ALLOW
    ) {
        io {
            val appNames = FirewallManager.getAppNamesByUid(appInfo.uid)
            if (appNames.count() > 1) {
                // guard only the dialog: showing it is pointless once the
                // activity is finishing
                uiCtx { showDialog(appNames, appInfo, aStat, cStat, prevConnStat) }
                return@io
            }
            completeFirewallChanges(aStat, cStat)
        }
    }

    private fun completeFirewallChanges(
        aStat: FirewallManager.FirewallStatus,
        cStat: FirewallManager.ConnectionStatus
    ) {
        appStatus = aStat
        connStatus = cStat
        io { updateFirewallStatus(appInfo.uid, aStat, cStat) }
        logEvent(
            "firewall rule change",
            "Firewall status changed for ${appInfo.appName} (${appInfo.uid}), new status: $aStat, conn status: $cStat"
        )
        lifecycleScope.launch(Dispatchers.Main) {
            if (!isFinishing && !isDestroyed) updateFirewallStatusUi(aStat, cStat)
        }
    }

    private fun enableAppBypassedUi() {
        setDrawable(R.drawable.ic_bypass_dns_firewall_off, b.aadAppSettingsBypassDnsFirewall)
        setDrawable(R.drawable.ic_firewall_wifi_on_grey, b.aadAppSettingsBlockWifi)
        setDrawable(R.drawable.ic_firewall_data_on_grey, b.aadAppSettingsBlockMd)
        setDrawable(R.drawable.ic_firewall_bypass_on, b.aadAppSettingsBypassUniv)
        setDrawable(R.drawable.ic_firewall_exclude_off, b.aadAppSettingsExclude)
        setDrawable(R.drawable.ic_firewall_lockdown_off, b.aadAppSettingsIsolate)
    }

    private fun enableDnsFirewallBypassedUi() {
        setDrawable(R.drawable.ic_bypass_dns_firewall_on, b.aadAppSettingsBypassDnsFirewall)
        setDrawable(R.drawable.ic_firewall_wifi_on_grey, b.aadAppSettingsBlockWifi)
        setDrawable(R.drawable.ic_firewall_data_on_grey, b.aadAppSettingsBlockMd)
        setDrawable(R.drawable.ic_firewall_bypass_off, b.aadAppSettingsBypassUniv)
        setDrawable(R.drawable.ic_firewall_exclude_off, b.aadAppSettingsExclude)
        setDrawable(R.drawable.ic_firewall_lockdown_off, b.aadAppSettingsIsolate)
    }

    private fun enableAppExcludedUi() {
        setDrawable(R.drawable.ic_bypass_dns_firewall_off, b.aadAppSettingsBypassDnsFirewall)
        setDrawable(R.drawable.ic_firewall_wifi_on_grey, b.aadAppSettingsBlockWifi)
        setDrawable(R.drawable.ic_firewall_data_on_grey, b.aadAppSettingsBlockMd)
        setDrawable(R.drawable.ic_firewall_bypass_off, b.aadAppSettingsBypassUniv)
        setDrawable(R.drawable.ic_firewall_exclude_on, b.aadAppSettingsExclude)
        setDrawable(R.drawable.ic_firewall_lockdown_off, b.aadAppSettingsIsolate)
    }

    private fun disableWhitelistExcludeUi() {
        setDrawable(R.drawable.ic_firewall_bypass_off, b.aadAppSettingsBypassUniv)
        setDrawable(R.drawable.ic_firewall_exclude_off, b.aadAppSettingsExclude)
        setDrawable(R.drawable.ic_firewall_lockdown_off, b.aadAppSettingsIsolate)
        setDrawable(R.drawable.ic_bypass_dns_firewall_off, b.aadAppSettingsBypassDnsFirewall)
    }

    private fun enableIsolateUi() {
        setDrawable(R.drawable.ic_bypass_dns_firewall_off, b.aadAppSettingsBypassDnsFirewall)
        setDrawable(R.drawable.ic_firewall_wifi_on_grey, b.aadAppSettingsBlockWifi)
        setDrawable(R.drawable.ic_firewall_data_on_grey, b.aadAppSettingsBlockMd)
        setDrawable(R.drawable.ic_firewall_bypass_off, b.aadAppSettingsBypassUniv)
        setDrawable(R.drawable.ic_firewall_exclude_off, b.aadAppSettingsExclude)
        setDrawable(R.drawable.ic_firewall_lockdown_on, b.aadAppSettingsIsolate)
    }

    private fun enableAllow() {
        setDrawable(R.drawable.ic_bypass_dns_firewall_off, b.aadAppSettingsBypassDnsFirewall)
        setDrawable(R.drawable.ic_firewall_wifi_on, b.aadAppSettingsBlockWifi)
        setDrawable(R.drawable.ic_firewall_data_on, b.aadAppSettingsBlockMd)
        setDrawable(R.drawable.ic_firewall_bypass_off, b.aadAppSettingsBypassUniv)
        setDrawable(R.drawable.ic_firewall_exclude_off, b.aadAppSettingsExclude)
        setDrawable(R.drawable.ic_firewall_lockdown_off, b.aadAppSettingsIsolate)
    }

    // update the BLOCK status based on connection status (mobile data + wifi + both)
    private fun enableBlock(cStat: FirewallManager.ConnectionStatus) {
        when (cStat) {
            FirewallManager.ConnectionStatus.METERED -> {
                setDrawable(R.drawable.ic_firewall_wifi_on, b.aadAppSettingsBlockWifi)
                setDrawable(R.drawable.ic_firewall_data_off, b.aadAppSettingsBlockMd)
            }
            FirewallManager.ConnectionStatus.UNMETERED -> {
                setDrawable(R.drawable.ic_firewall_wifi_off, b.aadAppSettingsBlockWifi)
                setDrawable(R.drawable.ic_firewall_data_on, b.aadAppSettingsBlockMd)
            }
            FirewallManager.ConnectionStatus.BOTH -> {
                setDrawable(R.drawable.ic_firewall_wifi_off, b.aadAppSettingsBlockWifi)
                setDrawable(R.drawable.ic_firewall_data_off, b.aadAppSettingsBlockMd)
            }
            FirewallManager.ConnectionStatus.ALLOW -> {
                setDrawable(R.drawable.ic_firewall_wifi_on, b.aadAppSettingsBlockWifi)
                setDrawable(R.drawable.ic_firewall_data_on, b.aadAppSettingsBlockMd)
            }
        }
        setDrawable(R.drawable.ic_bypass_dns_firewall_off, b.aadAppSettingsBypassDnsFirewall)
        setDrawable(R.drawable.ic_firewall_bypass_off, b.aadAppSettingsBypassUniv)
        setDrawable(R.drawable.ic_firewall_exclude_off, b.aadAppSettingsExclude)
        setDrawable(R.drawable.ic_firewall_lockdown_off, b.aadAppSettingsIsolate)
    }

    private fun setDrawable(drawable: Int, txt: WgControlState) { txt.imageResource = drawable }

    private fun getFirewallText(
        aStat: FirewallManager.FirewallStatus,
        cStat: FirewallManager.ConnectionStatus
    ): CharSequence {
        return when (aStat) {
            FirewallManager.FirewallStatus.NONE -> {
                when {
                    cStat.mobileData() -> getString(R.string.ada_app_status_block_md)
                    cStat.wifi() -> getString(R.string.ada_app_status_block_wifi)
                    cStat.allow() -> getString(R.string.ada_app_status_allow)
                    cStat.blocked() -> getString(R.string.ada_app_status_block)
                    else -> getString(R.string.ada_app_status_unknown)
                }
            }
            FirewallManager.FirewallStatus.EXCLUDE -> getString(R.string.ada_app_status_exclude)
            FirewallManager.FirewallStatus.BYPASS_UNIVERSAL ->
                getString(R.string.ada_app_status_whitelist)
            FirewallManager.FirewallStatus.ISOLATE -> getString(R.string.ada_app_status_isolate)
            FirewallManager.FirewallStatus.BYPASS_DNS_FIREWALL ->
                getString(R.string.ada_app_status_bypass_dns_firewall)
        }
    }

    private fun appName(packageCount: Int): String {
        return if (packageCount >= 2) {
            getString(
                R.string.ctbs_app_other_apps,
                appInfo.appName,
                packageCount.minus(1).toString()
            )
        } else {
            appInfo.appName
        }
    }

    private fun showNoAppFoundDialog() {
        val builder = WgAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
        builder.setTitle(getString(R.string.ada_noapp_dialog_title))
        builder.setMessage(getString(R.string.ada_noapp_dialog_message))
        builder.setCancelable(false)
        builder.setPositiveButton(getString(R.string.ada_noapp_dialog_positive)) {
            dialogInterface,
            _ ->
            dialogInterface.dismiss()
            finish()
        }
        builder.create().show()
    }

    private fun showDialog(
        packageList: List<String>,
        appInfo: AppInfo,
        aStat: FirewallManager.FirewallStatus,
        cStat: FirewallManager.ConnectionStatus,
        prevConnStat: FirewallManager.ConnectionStatus
    ) {

        val builderSingle = WgAlertDialogBuilder(this, R.style.App_Dialog_NoDim)

        builderSingle.setIcon(R.drawable.ic_firewall_block_grey)
        val count = packageList.count()
        builderSingle.setTitle(
            this.getString(R.string.ctbs_block_other_apps, appInfo.appName, count.toString())
        )

        builderSingle.setCancelable(false)

        builderSingle.setItems(packageList.toTypedArray(), null)

        builderSingle
            .setPositiveButton(getString(FirewallManager.getLabelForStatus(aStat, cStat, prevConnStat))) {
                di: DialogInterface,
                _: Int ->
                di.dismiss()
                completeFirewallChanges(aStat, cStat)
            }
            .setNeutralButton(this.getString(R.string.ctbs_dialog_negative_btn)) {
                _: DialogInterface,
                _: Int ->
            }

        val alertDialog: android.app.Dialog = builderSingle.create()
        alertDialog.show()
    }

    private fun showCloseConnectionDialog(uid: Int, appName: String) {
        Logger.v(LOG_TAG_UI, "$TAG show close connection dialog for uid: $uid")
        val dialog = WgAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
            .setTitle(this.getString(R.string.close_conns_dialog_title))
            .setMessage(getString(R.string.close_conns_dialog_desc, appName))
            .setPositiveButton(R.string.lbl_proceed) { _, _ ->
                // close the connection
                VpnController.closeConnectionsIfNeeded(uid, "app-info-dialog-manual-close")
                Logger.i(LOG_TAG_UI, "$TAG closed connection for uid: $uid")
                showToastUiCentered(this, getString(R.string.config_add_success_toast), Toast.LENGTH_LONG)
                logEvent("close connections",
                    "Closed active connections for $appName ($uid) from AppInfoActivity")
            }
            .setNegativeButton(R.string.lbl_cancel, null)
            .create()
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
    }

    private fun displayIcon(drawable: Drawable?, mIconImageView: WgControlState) {
        if (isFinishing || isDestroyed) return
        mIconImageView.drawable = drawable ?: Utilities.getDefaultIcon(this)
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun guardAppInfoInitialized(listenerName: String, block: () -> Unit) {
        if (!::appInfo.isInitialized) {
            Logger.w(LOG_TAG_UI, "AppInfo not initialized yet in $listenerName click listener, using uid: $uid")
            showToastUiCentered(
                this,
                this.getString(R.string.ctbs_app_info_not_available_toast),
                Toast.LENGTH_SHORT
            )
            return
        }
        block()
    }

    private fun logEvent(msg: String, details: String) {
        eventLogger.log(EventType.FW_RULE_MODIFIED, Severity.LOW, msg, EventSource.UI, true, details)
    }

    private fun showNotesDialog(draftText: String? = null) {
        val initialText = draftText ?: if (::appInfo.isInitialized) appInfo.notes else ""
        val editText = AppNotesDraft(initialText)
        notesEditText = editText
        val dialog = showAppNotesDialog(this, editText, onSave = {
            val newNotes = editText.text.toString().trim()
            notesDraft = newNotes
            saveNotesToDatabase(newNotes)
        }, onCancel = { notesDraft = "" }, onDismiss = {
            notesDialog = null
            notesEditText = null
            shouldRestoreNotesDialog = false
        })
        notesDialog = dialog
    }

    private fun saveNotesToDatabase(newNotes: String) {
        if (!::appInfo.isInitialized) {
            Logger.w(LOG_TAG_UI, "AppInfo not initialized in saveNotesToDatabase, uid: $uid")
            showToastUiCentered(
                this,
                this.getString(R.string.ctbs_app_info_not_available_toast),
                Toast.LENGTH_SHORT
            )
            return
        }
        if (isNotesSaveInFlight) {
            showToastUiCentered(this, getString(R.string.lbl_saving), Toast.LENGTH_SHORT)
            return
        }
        val packageName = appInfo.packageName
        isNotesSaveInFlight = true
        appInfoViewModel.updateAppNotes(appInfo.uid, packageName, newNotes)
    }

    private fun io(f: suspend () -> Unit): Job {
        return lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) {
            if (!isFinishing && !isDestroyed) f()
        }
    }
}
