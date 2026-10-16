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

import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
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
import android.widget.ArrayAdapter
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
import by.kirich1409.viewbindingdelegate.viewBinding
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
import com.celzero.bravedns.databinding.ActivityAppDetailsBinding
import com.celzero.bravedns.databinding.ViewInsightsRankRowBinding
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.FirewallManager.updateFirewallStatus
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.ProxyManager.ID_NONE
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.ui.custom.DonutChartView
import com.celzero.bravedns.ui.fragment.SummaryStatisticsFragment
import com.celzero.bravedns.ui.stats.CountryInsightsMapper
import com.celzero.bravedns.ui.stats.StatsInsightsMath
import com.celzero.bravedns.ui.stats.StatsViewMode
import com.celzero.bravedns.viewmodel.SummaryStatisticsViewModel
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import com.celzero.bravedns.util.Constants.Companion.RETHINK_PACKAGE
import com.celzero.bravedns.util.Constants.Companion.VIEW_PAGER_SCREEN_TO_LOAD
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
import com.celzero.firestack.backend.Backend
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class AppInfoActivity : BaseActivity(R.layout.activity_app_details) {
    private val b by viewBinding(ActivityAppDetailsBinding::bind)

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
    private var notesDialog: AlertDialog? = null
    private var notesEditText: EditText? = null

    // current presentation mode of the network-log sections; mirrors the stats
    // screen's persisted preference so both surfaces stay in sync
    private var statsViewMode: StatsViewMode = StatsViewMode.LIST

    // latest snapshot per insights section, kept in sync with the list
    // adapters so switching to Insights renders instantly without refetching
    private val insightsSnapshots = mutableMapOf<AppInsightsSection, List<AppConnection>>()

    // (adapter, observer) pairs registered for snapshot mirroring; unregistered
    // in onDestroy so no observer outlives the activity's view hierarchy
    private val insightsObservers =
        mutableListOf<Pair<PagingDataAdapter<AppConnection, *>, RecyclerView.AdapterDataObserver>>()

    // theme-resolved insights colors; refreshed on every full render
    private var allowedColor: Int = 0
    private var blockedColor: Int = 0
    private var trackColor: Int = 0
    private var centerTextColor: Int = 0

    // true when the ASN section was actually wired up (setting enabled and
    // app is not Rethink itself); gates the insights ASN section
    private var isAsnInsightsAvailable: Boolean = false

    // network-log sections rendered in the insights view; both the Rethink and
    // non-Rethink data sources map onto these same sections. declaration order
    // mirrors the on-screen section order (App -> Country -> Provider ->
    // Blocklist -> Domain -> IP), same as the stats screen
    private enum class AppInsightsSection {
        ACTIVE_CONNS,
        COUNTRIES,
        ASN,
        BLOCKED_ASN,
        BLOCKLISTS,
        DOMAINS,
        BLOCKED_DOMAINS,
        IPS,
        BLOCKED_IPS
    }

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
                if (statsViewMode == StatsViewMode.INSIGHTS) renderInsights()
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
        // restore the persisted mode before the listener attaches (setting
        // checkedButton here does not fire the toggle listener)
        statsViewMode = StatsViewMode.fromId(persistentState.statsViewMode)
        val modeBtn = b.aadViewModeToggleGroup.findViewById<MaterialButton>(
            if (statsViewMode == StatsViewMode.INSIGHTS) {
                b.aadViewModeInsightsBtn.id
            } else {
                b.aadViewModeListBtn.id
            }
        )
        modeBtn.isChecked = true
        refreshViewModeToggleUi()
        b.aadViewModeToggleGroup.addOnButtonCheckedListener(viewModeToggleListener)
        setupInsightsClickListeners()
        applyViewModeUi()
    }

    /**
     * View-mode switch: persists the mode and flips the presentation only.
     * Both views share the same adapters/state, so switching never refetches.
     */
    private val viewModeToggleListener =
        MaterialButtonToggleGroup.OnButtonCheckedListener { _, _, isChecked ->
            // restyle on BOTH events (checked + unchecked): the group emits the
            // unchecked callback for the old button after (or before) the checked
            // callback for the new one, so each event re-syncs the visuals
            refreshViewModeToggleUi()
            if (!isChecked) return@OnButtonCheckedListener
            val newMode =
                if (b.aadViewModeInsightsBtn.id == b.aadViewModeToggleGroup.checkedButtonId) {
                    StatsViewMode.INSIGHTS
                } else {
                    StatsViewMode.LIST
                }
            if (newMode == statsViewMode) return@OnButtonCheckedListener
            statsViewMode = newMode
            persistentState.statsViewMode = newMode.id
            applyViewModeUi()
        }

    /** Applies the selected/unselected styling to both view-mode buttons. */
    private fun refreshViewModeToggleUi() {
        styleViewModeBtn(b.aadViewModeListBtn)
        styleViewModeBtn(b.aadViewModeInsightsBtn)
    }

    private fun styleViewModeBtn(mb: MaterialButton) {
        // derive selection from statsViewMode (not isChecked): the group fires
        // the checked/unchecked pair in quick succession and isChecked can be
        // mid-transition, which would leave both buttons looking selected
        val selected =
            (mb.id == b.aadViewModeInsightsBtn.id) == (statsViewMode == StatsViewMode.INSIGHTS)
        if (selected) {
            mb.backgroundTintList =
                ColorStateList.valueOf(
                    UIUtils.fetchToggleBtnColors(this, R.color.accentGood)
                )
            mb.iconTint =
                ColorStateList.valueOf(
                    UIUtils.fetchColor(this, R.attr.homeScreenHeaderTextColor)
                )
        } else {
            mb.backgroundTintList =
                ColorStateList.valueOf(
                    UIUtils.fetchToggleBtnColors(this, R.color.defaultToggleBtnBg)
                )
            mb.iconTint =
                ColorStateList.valueOf(
                    UIUtils.fetchColor(this, R.attr.defaultToggleBtnTxt)
                )
        }
    }

    /** Flips the two presentation containers; does not touch any data state. */
    private fun applyViewModeUi() {
        val insights = statsViewMode == StatsViewMode.INSIGHTS
        b.aadListContainer.visibility = if (insights) View.GONE else View.VISIBLE
        b.aadInsightsContainer.visibility = if (insights) View.VISIBLE else View.GONE
        refreshViewModeToggleUi()
        refreshLogToggleVisibility()
        if (insights) {
            renderInsights()
        }
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
        containerView: View,
        recyclerView: RecyclerView,
        section: AppInsightsSection,
        data: LiveData<PagingData<AppConnection>>,
        adapter: PagingDataAdapter<AppConnection, *>
    ) {
        recyclerView.layoutManager = LinearLayoutManager(this)
        // wrap_content height: data changes resize the view, so fixed-size must
        // stay off and animations disabled to avoid flicker on updates
        recyclerView.setHasFixedSize(false)
        recyclerView.itemAnimator = null
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
    private fun hookInsightsSnapshot(
        section: AppInsightsSection,
        adapter: PagingDataAdapter<AppConnection, *>
    ) {
        val observer = object : RecyclerView.AdapterDataObserver() {
            private fun cacheAndRender() {
                insightsSnapshots[section] = adapter.snapshot().items
                if (statsViewMode == StatsViewMode.INSIGHTS && !isFinishing && !isDestroyed) {
                    renderInsightsSection(section)
                }
            }

            override fun onChanged() = cacheAndRender()
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = cacheAndRender()
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) =
                cacheAndRender()

            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = cacheAndRender()
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = cacheAndRender()
            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) =
                cacheAndRender()
        }
        adapter.registerAdapterDataObserver(observer)
        insightsObservers.add(adapter to observer)
    }

    /** Re-renders every insights section from the cached snapshots. */
    private fun renderInsights() {
        applyInsightsTheme()
        AppInsightsSection.entries.forEach { renderInsightsSection(it) }
        applyInsightsSectionGating()
    }

    private fun isBlockedSection(section: AppInsightsSection): Boolean {
        return section == AppInsightsSection.BLOCKED_ASN ||
            section == AppInsightsSection.BLOCKED_DOMAINS ||
            section == AppInsightsSection.BLOCKED_IPS ||
            section == AppInsightsSection.BLOCKLISTS
    }

    /** Hides insights sections whose list-view counterparts are unavailable. */
    private fun applyInsightsSectionGating() {
        // ASN lookups are an optional (downloaded) dataset and Rethink's own
        // screen never wires the ASN adapter; when available, visibility is
        // driven by the snapshot inside renderRanking (hidden until data exists)
        if (!isAsnInsightsAvailable) {
            b.aadIaAsnLl.visibility = View.GONE
            b.aadIaBlockedAsnLl.visibility = View.GONE
        }
    }

    /** Resolves every insights color from the active theme; no hardcoded colors. */
    private fun applyInsightsTheme() {
        allowedColor = UIUtils.fetchColor(this, R.attr.accentGood)
        blockedColor = UIUtils.fetchColor(this, R.attr.accentBad)
        trackColor = UIUtils.fetchColor(this, R.attr.colorSurfaceContainerHighest)
        centerTextColor = UIUtils.fetchColor(this, R.attr.primaryTextColor)
        val subtle = UIUtils.fetchColor(this, R.attr.secondaryTextColor)
        b.aadIaCountriesMap.setColors(trackColor, allowedColor, subtle)
        allInsightsDonuts().forEach { donut ->
            donut.setTrackColor(trackColor)
            donut.setCenterTextColor(centerTextColor)
        }
    }

    private fun allInsightsDonuts(): List<DonutChartView> {
        return listOf(
            b.aadIaActiveConnsDonut,
            b.aadIaAsnDonut,
            b.aadIaDomainsDonut,
            b.aadIaIpsDonut,
            b.aadIaBlockedAsnDonut,
            b.aadIaBlockedDomainsDonut,
            b.aadIaBlockedIpsDonut,
            b.aadIaBlocklistDonut
        )
    }

    private fun renderInsightsSection(section: AppInsightsSection) {
        val items = insightsSnapshots[section].orEmpty()
        when (section) {
            AppInsightsSection.ACTIVE_CONNS ->
                renderRanking(
                    section, b.aadIaActiveConnsLl, b.aadIaActiveConnsRows,
                    b.aadIaActiveConnsDonut, items
                )
            AppInsightsSection.ASN ->
                renderRanking(
                    section, b.aadIaAsnLl, b.aadIaAsnRows,
                    b.aadIaAsnDonut, items
                )
            AppInsightsSection.COUNTRIES -> {
                renderRanking(
                    section, b.aadIaCountriesLl, b.aadIaCountriesRows,
                    null, items
                )
                updateCountryMap(items)
            }
            AppInsightsSection.DOMAINS ->
                renderRanking(
                    section, b.aadIaDomainsLl, b.aadIaDomainsRows,
                    b.aadIaDomainsDonut, items
                )
            AppInsightsSection.IPS ->
                renderRanking(
                    section, b.aadIaIpsLl, b.aadIaIpsRows,
                    b.aadIaIpsDonut, items
                )
            AppInsightsSection.BLOCKED_ASN ->
                renderRanking(
                    section, b.aadIaBlockedAsnLl, b.aadIaBlockedAsnRows,
                    b.aadIaBlockedAsnDonut, items
                )
            AppInsightsSection.BLOCKED_DOMAINS ->
                renderRanking(
                    section, b.aadIaBlockedDomainsLl, b.aadIaBlockedDomainsRows,
                    b.aadIaBlockedDomainsDonut, items
                )
            AppInsightsSection.BLOCKED_IPS ->
                renderRanking(
                    section, b.aadIaBlockedIpsLl, b.aadIaBlockedIpsRows,
                    b.aadIaBlockedIpsDonut, items
                )
            AppInsightsSection.BLOCKLISTS -> {
                // blocklist blocking only exists when dns is active; the
                // section disappears until a blocklist has blocked something
                if (appConfig.getBraveMode().isDnsActive()) {
                    renderRanking(
                        section, b.aadIaBlocklistLl, b.aadIaBlocklistRows,
                        b.aadIaBlocklistDonut, items
                    )
                } else {
                    b.aadIaBlocklistLl.visibility = View.GONE
                }
            }
        }
    }

    /**
     * Renders a section as a donut chart (top slices, single hue stepped by
     * intensity) plus normalized ranking rows (bar length = value / maxValue,
     * the longest item at ~100%). The whole section — heading, action chip
     * and chart — is hidden when there is nothing to show; no separate
     * empty-state message is displayed. Blocked sections use the negative
     * accent hue throughout.
     */
    private fun renderRanking(
        section: AppInsightsSection,
        containerView: View,
        rowsContainer: LinearLayout,
        donut: DonutChartView?,
        items: List<AppConnection>
    ) {
        val blocked = isBlockedSection(section)
        containerView.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        if (items.isEmpty()) {
            rowsContainer.removeAllViews()
            donut?.visibility = View.GONE
            return
        }
        donut?.visibility = View.VISIBLE
        val fractions = StatsInsightsMath.normalizeFractions(items.map { it.count.toLong() })
        renderDonut(donut, fractions, items, blocked)
        val inflater = LayoutInflater.from(this)
        val barColor = if (blocked) blockedColor else allowedColor
        rowsContainer.removeAllViews()
        // rows are capped to the donut's top-slice count: the full ranked list
        // stays one tap away on the "see more" screen
        val rows = minOf(TOP_SLICES, items.size)
        items.take(rows).forEachIndexed { index, item ->
            val rowBinding = ViewInsightsRankRowBinding.inflate(inflater, rowsContainer, false)
            val label = rowLabel(item, section)
            val metric = item.count.toString()
            rowBinding.irName.text = label
            rowBinding.irCount.text = metric
            rowBinding.irBar.max = PERCENTAGE_MULTIPLIER
            rowBinding.irBar.progress = (fractions[index] * PERCENTAGE_MULTIPLIER).toInt()
            rowBinding.irBar.setIndicatorColor(
                if (!blocked && item.blocked) blockedColor else barColor
            )
            rowBinding.root.contentDescription = getString(R.string.ci_desc, label, metric)
            rowsContainer.addView(rowBinding.root)
        }
    }

    /**
     * Donut slices: the top [TOP_SLICES] items, one hue (green for
     * allowed/contacted sections, red for blocked sections) at stepped
     * intensities; the remainder stays visible as the neutral track ring.
     * The center carries the section total.
     */
    private fun renderDonut(
        donut: DonutChartView?,
        fractions: List<Float>,
        items: List<AppConnection>,
        blocked: Boolean
    ) {
        donut ?: return
        val base = if (blocked) blockedColor else allowedColor
        val top = minOf(TOP_SLICES, fractions.size)
        val slices = (0 until top).map { i ->
            DonutChartView.Slice(fractions[i], ColorUtils.setAlphaComponent(base, SLICE_ALPHAS[i]))
        }
        donut.setData(slices)
        donut.setCenterText(items.sumOf { it.count.toLong() }.toString())
    }

    private fun rowLabel(item: AppConnection, section: AppInsightsSection): String {
        return when (section) {
            AppInsightsSection.ACTIVE_CONNS,
            AppInsightsSection.IPS,
            AppInsightsSection.BLOCKED_IPS ->
                item.ipAddress
            AppInsightsSection.DOMAINS,
            AppInsightsSection.BLOCKED_DOMAINS ->
                item.appOrDnsName?.dropLastWhile { it == '.' }.orEmpty()
            AppInsightsSection.COUNTRIES -> {
                // country-name lookups fail with a dash placeholder, never a
                // real name; such rows render as "Unknown <flag>"
                val name = getCountryNameFromFlag(item.flag)
                if (name.isNotEmpty() && !name.all { it == '-' }) {
                    name
                } else {
                    getString(
                        R.string.two_argument_space,
                        getString(R.string.network_log_app_name_unknown),
                        item.flag
                    )
                }
            }
            AppInsightsSection.ASN,
            AppInsightsSection.BLOCKED_ASN ->
                getString(R.string.two_argument_space, item.flag, item.appOrDnsName.orEmpty())
            AppInsightsSection.BLOCKLISTS -> item.appOrDnsName.orEmpty()
        }
    }

    /** Feeds the offline map: emoji flag -> ISO code -> connection count. */
    private fun updateCountryMap(items: List<AppConnection>) {
        val stats = items.mapNotNull { item ->
            CountryInsightsMapper.toCountryCode(item.flag)?.let { it to item.count }
        }.toMap()
        b.aadIaCountriesMap.setCountryCounts(stats)
        // accessibility summary: top few countries, list holds exact numbers
        val summary = stats.entries.take(3).joinToString(", ") {
            getString(R.string.ci_desc, it.key, it.value.toString())
        }
        b.aadIaCountriesMap.contentDescription =
            if (summary.isEmpty()) {
                getString(R.string.cd_stats_country_map)
            } else {
                getString(R.string.two_argument_colon, getString(R.string.cd_stats_country_map), summary)
            }
    }

    override fun onDestroy() {
        // detach snapshot observers before the view tree goes away so nothing
        // holds a reference to the (dying) adapters or row containers
        insightsObservers.forEach { (adapter, observer) ->
            runCatching { adapter.unregisterAdapterDataObserver(observer) }
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

        TooltipCompat.setTooltipText(b.aadCloseConnsChip, getString(R.string.close_conns_dialog_title))
        TooltipCompat.setTooltipText(b.aadAppInfoIcon, getString(R.string.about_settings_app_info))

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
        val layoutManager = LinearLayoutManager(this)
        b.aadActiveConnsRv.layoutManager = layoutManager
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
        val layoutManager = LinearLayoutManager(this)
        b.aadAsnRv.layoutManager = layoutManager
        val adapter = AppWiseIpsAdapter(this, this, uid,  isAsn = true)
        isAsnInsightsAvailable = true
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
        b.aadBlocklistRv.layoutManager = LinearLayoutManager(this)
        b.aadBlocklistRv.setHasFixedSize(false)
        b.aadBlocklistRv.itemAnimator = null
        b.aadBlocklistRv.adapter = adapter
    }

    private fun setupCountriesAdapter(data: LiveData<PagingData<AppConnection>>) {
        val layoutManager = LinearLayoutManager(this)
        b.aadMostContactedCountriesRv.layoutManager = layoutManager
        // wrap_content height: data changes resize the view, so fixed-size must
        // stay off and animations disabled to avoid flicker on updates
        b.aadMostContactedCountriesRv.setHasFixedSize(false)
        b.aadMostContactedCountriesRv.itemAnimator = null
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
        val layoutManager = LinearLayoutManager(this)
        b.aadMostContactedDomainRv.layoutManager = layoutManager
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
        val layoutManager = LinearLayoutManager(this)
        b.aadMostContactedDomainRv.layoutManager = layoutManager
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
        val layoutManager = LinearLayoutManager(this)
        b.aadMostContactedIpsRv.layoutManager = layoutManager
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
        b.aadMostContactedIpsRv.setHasFixedSize(true)
        val layoutManager = LinearLayoutManager(this)
        b.aadMostContactedIpsRv.layoutManager = layoutManager
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
        val builderSingle = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
        builderSingle.setTitle(this.getString(R.string.about_settings_app_info))

        val arrayAdapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_activated_1)
        arrayAdapter.addAll(appNames)
        builderSingle.setCancelable(false)

        builderSingle.setItems(appNames.toTypedArray(), null)

        builderSingle.setPositiveButton(getString(R.string.ada_noapp_dialog_positive)) {
            dialog: DialogInterface,
            _: Int ->
            dialog.dismiss()
        }

        val alertDialog = builderSingle.create()
        val ctx = this.applicationContext
        alertDialog.listView.setOnItemClickListener { _, _, position, _ ->
            io {
                val pkg = FirewallManager.getPackageNameByAppName(appNames[position])
                uiCtx {
                    Logger.i(LOG_TAG_UI, "AppInfoActivity, package name: $pkg")
                    openAndroidAppInfo(ctx, pkg)
                }
            }
        }
        alertDialog.show()
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

    private fun setDrawable(drawable: Int, txt: TextView) {
        val top = ContextCompat.getDrawable(this, drawable)
        txt.setCompoundDrawablesWithIntrinsicBounds(null, top, null, null)
    }

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
        val builder = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
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

        val builderSingle = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)

        builderSingle.setIcon(R.drawable.ic_firewall_block_grey)
        val count = packageList.count()
        builderSingle.setTitle(
            this.getString(R.string.ctbs_block_other_apps, appInfo.appName, count.toString())
        )

        val arrayAdapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_activated_1)
        arrayAdapter.addAll(packageList)
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

        val alertDialog: AlertDialog = builderSingle.create()
        alertDialog.listView.setOnItemClickListener { _, _, _, _ -> }
        alertDialog.show()
    }

    private fun showCloseConnectionDialog(uid: Int, appName: String) {
        Logger.v(LOG_TAG_UI, "$TAG show close connection dialog for uid: $uid")
        val dialog = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
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

    private fun displayIcon(drawable: Drawable?, mIconImageView: ImageView) {
        if (isFinishing || isDestroyed) return
        Glide.with(this).load(drawable).error(Utilities.getDefaultIcon(this)).into(mIconImageView)
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
        val editText = EditText(this).apply {
            setText(initialText)
            hint = getString(R.string.hint_notes)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setMinLines(4)
            setMaxLines(10)
            filters = arrayOf(InputFilter.LengthFilter(MAX_NOTE_LENGTH))
        }
        val counterText = TextView(this).apply {
            gravity = Gravity.END
            text = getString(R.string.ctbs_note_char_counter, initialText.length, MAX_NOTE_LENGTH)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val horizontalPadding = (24 * resources.displayMetrics.density).toInt()
            setPadding(horizontalPadding, 0, horizontalPadding, 0)
            addView(
                editText,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                counterText,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                val currentLength = s?.length ?: 0
                counterText.text = getString(R.string.ctbs_note_char_counter, currentLength, MAX_NOTE_LENGTH)
            }
        })

        notesEditText = editText

        val dialog = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
            .setTitle(R.string.lbl_notes)
            .setView(container)
            .setPositiveButton(R.string.lbl_save) { _, _ ->
                val newNotes = editText.text.toString().trim()
                notesDraft = newNotes
                saveNotesToDatabase(newNotes)
            }
            .setNegativeButton(R.string.lbl_cancel) { _, _ ->
                notesDraft = ""
            }
            .create()
        dialog.setOnDismissListener {
            notesDialog = null
            notesEditText = null
            shouldRestoreNotesDialog = false
        }
        notesDialog = dialog
        dialog.show()
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
