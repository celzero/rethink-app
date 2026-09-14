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
package com.celzero.bravedns.ui.fragment

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.icu.text.CompactDecimalFormat
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.transition.TransitionManager
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.distinctUntilChanged
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.ConnectionTrackerRepository
import com.celzero.bravedns.database.DnsLogRepository
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.databinding.FragmentHomeScreenBinding
import com.celzero.bravedns.databinding.ItemHomeFirewallRulesPageBinding
import com.celzero.bravedns.databinding.ViewHomeLogsActivityBinding
import com.celzero.bravedns.databinding.ViewHomeLogsAppsBinding
import com.celzero.bravedns.net.doh.Transaction
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.rpnproxy.RpnProxyManager.AUTO_SERVER_ID
import com.celzero.bravedns.scheduler.WorkScheduler
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.service.LogActivityAggregator
import com.celzero.bravedns.service.LogActivityInterval
import com.celzero.bravedns.service.LogActivityState
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.service.WireguardManager.WG_UPTIME_THRESHOLD
import com.celzero.bravedns.ui.activity.AlertsActivity
import com.celzero.bravedns.ui.activity.AppInfoActivity
import com.celzero.bravedns.ui.activity.AppListActivity
import com.celzero.bravedns.ui.activity.ConfigureRethinkBasicActivity
import com.celzero.bravedns.ui.activity.ConfigureRethinkBasicActivity.Companion.RETHINK_BLOCKLIST_NAME
import com.celzero.bravedns.ui.activity.ConfigureRethinkBasicActivity.Companion.RETHINK_BLOCKLIST_URL
import com.celzero.bravedns.ui.activity.CustomRulesActivity
import com.celzero.bravedns.ui.activity.DnsDetailActivity
import com.celzero.bravedns.ui.activity.FirewallActivity
import com.celzero.bravedns.ui.activity.FragmentHostActivity
import com.celzero.bravedns.ui.activity.NetworkLogsActivity
import com.celzero.bravedns.ui.activity.PauseActivity
import com.celzero.bravedns.ui.activity.ProxySettingsActivity
import com.celzero.bravedns.ui.activity.UniversalFirewallSettingsActivity
import com.celzero.bravedns.ui.activity.WgMainActivity
import com.celzero.bravedns.ui.bottomsheet.HomeScreenSettingBottomSheet
import com.celzero.bravedns.ui.bottomsheet.LogActivityIntervalBottomSheet
import com.celzero.bravedns.ui.custom.AppHistogramView
import com.celzero.bravedns.ui.tour.GuidedTourManager
import com.celzero.bravedns.ui.tour.TourOverlayController
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import com.celzero.bravedns.util.Logger.LOG_TAG_VPN
import com.celzero.bravedns.util.NotificationActionType
import com.celzero.bravedns.util.RotatingBorderDrawable
import com.celzero.bravedns.util.SnackbarHelper.capitalizeWords
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.htmlToSpannedText
import com.celzero.bravedns.util.UIUtils.openAppInfo
import com.celzero.bravedns.util.UIUtils.openNetworkSettings
import com.celzero.bravedns.util.UIUtils.openVpnProfile
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.delay
import com.celzero.bravedns.util.Utilities.getPrivateDnsMode
import com.celzero.bravedns.util.Utilities.isAtleast37
import com.celzero.bravedns.util.Utilities.isAtleastN
import com.celzero.bravedns.util.Utilities.isAtleastP
import com.celzero.bravedns.util.Utilities.isAtleastR
import com.celzero.bravedns.util.Utilities.isOtherVpnHasAlwaysOn
import com.celzero.bravedns.util.Utilities.isPrivateDnsActive
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.celzero.firestack.backend.Backend
import com.facebook.shimmer.Shimmer
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.waseemsabir.betterypermissionhelper.BatteryPermissionHelper
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import android.graphics.drawable.Drawable
import java.util.Locale
import java.util.Locale.getDefault
import java.util.concurrent.TimeUnit
import kotlin.math.log10
import kotlin.time.Duration.Companion.milliseconds

class HomeScreenFragment : Fragment(R.layout.fragment_home_screen) {
    private val b by viewBinding(FragmentHomeScreenBinding::bind)

    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()
    private val workScheduler by inject<WorkScheduler>()
    private val eventLogger by inject<EventLogger>()
    private val activityAggregator by inject<LogActivityAggregator>()
    private val connectionTrackerRepository by inject<ConnectionTrackerRepository>()
    private val dnsLogRepository by inject<DnsLogRepository>()

    private var isVpnActivated: Boolean = false

    // Rotating gradient border on the start button; runs only while the VPN
    // is stopped to draw attention to the call-to-action. Cleared in
    // onDestroyView().
    private var rotationAnimator: ValueAnimator? = null

    // Active-state presentation captured once per view (in px / drawable)
    // before any state-dependent styling runs, so low-emphasis inactive
    // styling can be restored exactly on re-activation.
    private var appsHeadlineSizePx: Float = 0f

    // presentation state for the blocked/allowed activity grid; toggling this
    // only re-renders from the cached aggregate, it never queries the database
    private var displayMode: ActivityDisplayMode = ActivityDisplayMode.ALLOWED
    // last state emitted by LogActivityAggregator; kept so the toggle can
    // re-render without waiting for a new emission
    private var lastActivityState: LogActivityState? = null

    // last tap coordinates on the activity grid, used to resolve the exact
    // cell (row == hour, column == day) the user tapped; zeroed on
    // non-touch activation (keyboard), which falls back to the latest window
    private var lastGridTouchX = 0f
    private var lastGridTouchY = 0f

    // pre-inflated pages of the logs card pager; created once per view and
    // (re)attached by LogsPagesAdapter on bind
    private lateinit var logsPage: ViewHomeLogsActivityBinding
    private lateinit var topAppsPage: ViewHomeLogsAppsBinding

    // top-apps histogram state: blocked mode re-ranks by blocked attempts
    // (dns-log + connection-tracker rows), allowed mode by total data usage
    // (connection-tracker rows only)
    private var topAppsBlockedMode = false
    private var topAppsEntries: List<AppHistogramView.Entry> = emptyList()
    private var topAppsRequestGen = 0
    private var lastTopAppsFetchAt = 0L

    // page-change callback kept so it can be unregistered in onDestroyView()
    private var logsPagerCallback: ViewPager2.OnPageChangeCallback? = null

    // page-change callback for the swipeable rules card; unregistered in
    // onDestroyView() for the same reason as [logsPagerCallback]
    private var rulesPagerCallback: ViewPager2.OnPageChangeCallback? = null

    // whether the logs card is showing its live content (VPN on + logging
    // on); drives the dots visibility alongside the brave mode
    private var logsCardActive = false

    // pending revert of the header back to window totals after a bar tap
    private var topAppsHeaderRevertJob: Job? = null

    // swipeable rules card: one page per universal rule type (firewall, IP,
    // domain); counts are pushed in by the rule-count LiveData observers
    private val rulesPagesAdapter by lazy {
        FirewallRulesPagesAdapter(
            listOf(
                getString(R.string.hsf_rules_univ_label),
                getString(R.string.hsf_rules_ip_label),
                getString(R.string.hsf_rules_dom_label)
            )
        )
    }

    private lateinit var themeNames: Array<String>
    private lateinit var startForResult: ActivityResultLauncher<Intent>
    private lateinit var notificationPermissionResult: ActivityResultLauncher<String>
    private lateinit var localNetworkPermissionResult: ActivityResultLauncher<String>

    private val batteryPermissionHelper = BatteryPermissionHelper.getInstance()

    private val appRulesMutex = Mutex()
    private val rethinkUid = android.os.Process.myUid()
    @Volatile
    private var canRethinkBlockItself: Boolean = false

    companion object {
        private const val TAG = "HSFragment"
        private const val MAX_RULE_BADGE_CHARS = 3

        // swipeable rules card page indices
        private const val RULES_PAGE_UNIVERSAL = 0
        private const val RULES_PAGE_IP = 1
        private const val RULES_PAGE_DOMAIN = 2

        // animated border ring around the start button (VPN-off call-to-action)
        private const val BORDER_STROKE_WIDTH_DP = 2.5f
        // duration of one full swing across the contrast color band; the ring
        // spins fast until the first swing completes, then settles slower
        private const val BORDER_HUE_CYCLE_MS = 3000L
        // half-width of the hue band around the complementary hue, so the
        // highlight stays on the contrasting side of the wheel
        private const val BORDER_HUE_SWING_DEG = 30f
        private const val BORDER_FAST_ROTATION_MS = 1200L
        private const val BORDER_SLOW_ROTATION_MS = 3000L

        // UI interaction delays (milliseconds)
        private const val UI_DELAY_MS = 500L

        // Proxy / dns resolver status polling delays (milliseconds).
        // The next poll is delayed by however long the previous status check took, clamped
        // to this range. If the library is slow (e.g. 6 s for a far-away server) we back
        // off to the same duration so we never have overlapping in-flight requests, but we
        // cap at MAX so the card never goes stale for too long.
        private const val MIN_POLL_DELAY_MS = 5000L
        private const val MAX_PROXY_POLL_DELAY_MS = 10_000L
        private const val TEXT_FADE_DURATION_MS = 150L

        // Traffic display rotation
        private const val TRAFFIC_DISPLAY_CYCLE_MODULO = 3
        private const val TRAFFIC_DISPLAY_STATS_RATE = 0
        private const val TRAFFIC_DISPLAY_BANDWIDTH = 1
        private const val TRAFFIC_DISPLAY_DELAY_MS = 5000L

        // Byte conversion constants (KB, MB, GB, TB)
        private const val BYTES_PER_KB = 1024.0
        private const val BYTES_PER_MB = 1024.0 * 1024.0
        private const val BYTES_PER_GB = 1024.0 * 1024.0 * 1024.0
        private const val BYTES_PER_TB = 1024.0 * 1024.0 * 1024.0 * 1024.0

        // Byte conversion thresholds (Long)
        private const val KB_THRESHOLD = 1024L
        private const val MB_THRESHOLD = 1024L * 1024L
        private const val GB_THRESHOLD = 1024L * 1024L * 1024L
        private const val TB_THRESHOLD = 1024L * 1024L * 1024L * 1024L

        // Shimmer animation constants
        private const val SHIMMER_DURATION_MS = 2000L
        private const val SHIMMER_BASE_ALPHA = 0.85f
        private const val SHIMMER_DROP_OFF = 1f
        private const val SHIMMER_HIGHLIGHT_ALPHA = 0.35f

        // Inactive-state emphasis: card headlines shrink and dim when their
        // feature is off, so "off" hints never compete with live status values
        private const val INACTIVE_TEXT_SCALE = 0.6f
        private const val INACTIVE_ELEMENT_ALPHA = 0.45f

        // Monochrome proxy-health scheme (non-RPN proxies): one neutral hue
        // with stepped alpha per state — active stays fully opaque, idle and
        // failing fade out so visual weight tracks importance.
        private const val MONO_IDLE_ALPHA = 153    // 0.6
        private const val MONO_FAILING_ALPHA = 89  // 0.35

        // The "Stopped" slot always renders as a dimmed neutral (it is not a
        // health signal — it only flags that the RPN itself is not routing)
        private const val STOPPED_ALPHA = 89       // 0.35

        // Blocklist-count suffix on the DNS card: rendered smaller and lighter
        // than the resolver name so it never competes with it
        private const val BLOCKLIST_COUNT_SUFFIX_SCALE = 0.8f
        private const val BLOCKLIST_COUNT_SUFFIX_ALPHA = 153  // 0.6

        // activity grid intensity levels (empty + 4 logarithmic levels)
        private const val HEATMAP_INTENSITY_LEVELS = 5

        private const val HEATMAP_GRID_ROWS = 6
        private const val HEATMAP_GRID_HEIGHT_DP = 56

        private const val HEATMAP_CELL_OVAL_RATIO = 2f

        // fraction of the max cell size per intensity level
        private val HEATMAP_CELL_SIZE_FRACTION = floatArrayOf(0.30f, 0.78f, 0.78f, 1f, 1f)

        // alpha of the unselected allowed/blocked header block; the block
        // matching the active toggle mode renders at full opacity
        private const val LOGS_HEADER_UNSELECTED_ALPHA = 0.7f

        // top-apps histogram page of the logs card
        private const val TOP_APPS_COUNT = 20
        // re-querying on every swipe-to-page would hammer the log databases;
        // this throttle keeps page switches cheap while the data stays fresh
        private const val TOP_APPS_REFRESH_MIN_INTERVAL_MS = 15_000L

        // page-dot indicator: unselected dots render dimmed; the selected dot
        // fills to full opacity
        private const val DOT_UNSELECTED_ALPHA = 0.35f

        // how long a tapped app's values stay in the header before reverting
        // to the window totals
        private const val TOP_APPS_HEADER_REVERT_MS = 5_000L

        // in-memory logs-card view state: survives fragment/view recreation
        // (navigation, tab switches, config changes) until the process is
        // killed; deliberately not persisted to disk
        private var savedLogsPagePosition = 0
        private var savedTopAppsBlockedMode = false
    }

    enum class ScreenType {
        DNS,
        FIREWALL,
        LOGS,
        RULES,
        PROXY,
        ALERTS,
        RETHINK,
        PROXY_WIREGUARD
    }

    // which counter drives the activity grid's cell intensity
    enum class ActivityDisplayMode {
        BLOCKED,
        ALLOWED
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        registerForActivityResult()
    }

    @SuppressLint("ClickableViewAccessibility") // requires for grid coordinates touch
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Logger.v(LOG_TAG_UI, "$TAG: init view in home screen fragment")
        initializeValues()
        setupLogsPager()
        initializeClickListeners()
        isVpnActivated = VpnController.state().activationRequested
        captureActiveEmphasis()
        updateMainButtonUi()
        updateCardsUi()
        updateLogsToggleUi(displayMode == ActivityDisplayMode.BLOCKED)
        observeLogActivity()
        // one listener on the grid container itself: every tap anywhere inside
        logsPage.fhsLogsGrid.isClickable = true
        logsPage.fhsLogsGrid.contentDescription = getString(R.string.logs_card_grid_desc)
        // record tap position; returning false lets the click event fire
        logsPage.fhsLogsGrid.setOnTouchListener { _, event ->
            lastGridTouchX = event.x
            lastGridTouchY = event.y
            false
        }
        logsPage.fhsLogsGrid.setOnClickListener { openIntervalDetails(it) }
        // the activity wall is reconciled with the databases when
        // BraveVPNService is created, not on every home-screen resume
        syncDnsStatus()
        observeVpnState()
        //observeSponsorState()
        scheduleTourIfNeeded()
    }

    private fun initializeValues() {
        themeNames =
            arrayOf(
                getString(R.string.settings_theme_dialog_themes_1),
                getString(R.string.settings_theme_dialog_themes_2),
                getString(R.string.settings_theme_dialog_themes_3),
                getString(R.string.settings_theme_dialog_themes_4)
            )

        appConfig.getBraveModeObservable().postValue(appConfig.getBraveMode().mode)
    }

    /**
     * Schedules the guided tour to start 100 ms after the layout settles.
     *
     * The short delay lets the home screen render its cards fully before the
     * overlay attaches, preventing any visual flash. If the tour has already
     * been completed at the current version, this is a no-op.
     */
    private fun scheduleTourIfNeeded() {
        if (!GuidedTourManager.shouldShowTour(persistentState)) return
        delay(100L, lifecycleScope) {
            val host = activity ?: return@delay
            if (!isAdded || isDetached) return@delay
            try {
                TourOverlayController(
                    activity   = host,
                    steps      = GuidedTourManager.homeScreenSteps(),
                    onComplete = {
                        GuidedTourManager.markCompleted(persistentState)
                        Logger.v(LOG_TAG_UI, "$TAG: guided tour completed")
                    },
                ).start()
            } catch (e: Exception) {
                Logger.e(LOG_TAG_UI, "$TAG: failed to start guided tour: ${e.message}", e)
            }
        }
    }

    private fun initializeClickListeners() {
        setupFirewallRulesCard()

        b.fhsCardAppsCv.setOnClickListener {
            Logger.v(LOG_TAG_UI, "$TAG: click event on apps card")
            startAppsActivity()
            logEvent(
                EventType.UI_NAVIGATION,
                "HomeScreen: Apps card clicked",
                "Navigating to AppListActivity from HomeScreenFragment"
            )
        }

        b.fhsCardDnsLl.setOnClickListener {
            Logger.v(LOG_TAG_UI, "$TAG: click event on dns card")
            startDnsActivity(DnsDetailActivity.Tabs.CONFIGURE.screen)
            logEvent(
                EventType.UI_NAVIGATION,
                "HomeScreen: DNS card clicked",
                "Navigating to DnsDetailActivity from HomeScreenFragment"
            )
        }

        b.fhsThroughputLl.setOnClickListener {
            Logger.v(LOG_TAG_UI, "$TAG: click event on throughput card")
            openBottomSheet()
            logEvent(
                EventType.UI_NAVIGATION,
                "HomeScreen: Throughput card clicked",
                "Opening HomeScreen settings bottom sheet from HomeScreenFragment"
            )
        }

        b.homeFragmentBottomSheetIcon.setOnClickListener {
            Logger.v(LOG_TAG_UI, "$TAG: click event on bottom sheet icon")
            b.homeFragmentBottomSheetIcon.isEnabled = false
            openBottomSheet()
            delay(TimeUnit.MILLISECONDS.toMillis(UI_DELAY_MS), lifecycleScope) {
                b.homeFragmentBottomSheetIcon.isEnabled = true
            }
            logEvent(
                EventType.UI_NAVIGATION,
                "HomeScreen: Bottom sheet icon clicked",
                "Opening HomeScreen settings bottom sheet from HomeScreenFragment"
            )
        }

        b.homeFragmentPauseIcon.setOnClickListener {
            Logger.v(LOG_TAG_UI, "$TAG: click event on pause icon")
            handlePause()
            logEvent(
                EventType.UI_NAVIGATION,
                "HomeScreen: Pause icon clicked",
                "Opening PauseActivity from HomeScreenFragment"
            )
        }

        b.fhsDnsOnOffBtn.setOnClickListener {
            Logger.v(LOG_TAG_UI, "$TAG: click event on main button")
            handleMainScreenBtnClickEvent()
            delay(TimeUnit.MILLISECONDS.toMillis(UI_DELAY_MS), lifecycleScope) {
                if (isAdded) {
                    b.homeFragmentBottomSheetIcon.isEnabled = true
                }
            }
            logEvent(
                EventType.UI_TOGGLE,
                "HomeScreen: Main DNS On/Off button clicked",
                "Toggling VPN state from HomeScreenFragment"
            )
        }

        appConfig.getBraveModeObservable().observe(viewLifecycleOwner) {
            Logger.v(LOG_TAG_UI, "$TAG: brave mode changed to $it")
            updateCardsUi()
            syncDnsStatus()
            // the top-apps page is firewall-only; rebuild the pager pages so
            // DNS-only mode shows a single (activity wall) page
            refreshLogsPagerMode()
        }

        b.fhsCardLogsLl.setOnClickListener { openNetworkLogs() }

        logsPage.fhsLogsToggleGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val isBlocked = checkedIds.contains(R.id.fhs_logs_blocked_chip)
            toggleLogsView(if (isBlocked) ActivityDisplayMode.BLOCKED else ActivityDisplayMode.ALLOWED)
        }

        b.fhsLogsEnableChip.setOnClickListener {
            Logger.v(LOG_TAG_UI, "$TAG: enable logs chip clicked")
            persistentState.logsEnabled = true
            if (isVpnActivated) {
                // preference stored and vpn running: bring the full card back
                observeLogsCount()
            } else {
                // vpn is off: the preference is stored, but the card stays in
                // its disabled state, now without the enable chip
                disableLogsCard()
            }
        }

        b.fhsCardProxyLl.setOnClickListener {
            Logger.v(LOG_TAG_UI, "$TAG: click event on proxy card")
            // RPN-owned cards (active or stopped-but-purchased) land on RPN
            // server selection; only when RPN AND WireGuard are both active
            // does the tap open the combined proxy settings screen.
            val isRpnOwned = RpnProxyManager.isRpnActive() || RpnProxyManager.hasValidSubscription()
            when {
                isRpnOwned && appConfig.isWireGuardEnabled() -> startActivity(ScreenType.PROXY)
                isRpnOwned -> openRpnServerSelection()
                appConfig.isWireGuardEnabled() -> startActivity(ScreenType.PROXY_WIREGUARD)
                else -> startActivity(ScreenType.PROXY)
            }
            logEvent(
                EventType.UI_NAVIGATION,
                "HomeScreen: Proxy card clicked",
                "Navigating to rpn: ${RpnProxyManager.isRpnActive()}, wg: ${appConfig.isWireGuardEnabled()}  from HomeScreenFragment"
            )
        }

        b.fhsProtectionLevelTxt.setOnClickListener {
            openRethinkAppInfoIfNeeded()
        }

        // comment out the below code to disable the alerts card (v0.5.5b)
        // b.fhsCardAlertsLl.setOnClickListener { startActivity(ScreenType.ALERTS) }
    }

    private fun openRethinkAppInfoIfNeeded() {
        if (canRethinkBlockItself) {
            val intent = Intent(context, AppInfoActivity::class.java)
            intent.putExtra(AppInfoActivity.INTENT_UID, rethinkUid)
            startActivity(intent)
        } else {
            // no-op
        }
    }

    private fun openRpnDashboardScreen() {
        val args = Bundle().apply { putString("ARG_KEY", "Launch_Rethink_Support_Dashboard") }
        startActivity(
            FragmentHostActivity.createIntent(
                context = requireContext(),
                fragmentClass = RethinkPlusDashboardFragment::class.java,
                args = args
            )
        )
    }

    /**
     * Opens the RPN server-selection screen directly, bypassing
     * [ProxySettingsActivity]. Used when the proxy card is tapped while RPN
     * is active.
     */
    private fun openRpnServerSelection() {
        startActivity(
            FragmentHostActivity.createIntent(
                context = requireContext(),
                fragmentClass = ServerSelectionFragment::class.java
            )
        )
    }

    private fun logEvent(type: EventType, msg: String, details: String) {
        io {
            eventLogger.log(type, Severity.LOW, msg, EventSource.UI, true, details)
        }
    }

    private fun handlePause() {
        if (!VpnController.hasTunnel()) {
            showToastUiCentered(
                requireContext(),
                requireContext().getString(R.string.hsf_pause_vpn_failure),
                Toast.LENGTH_SHORT
            )
            return
        }

        VpnController.pauseApp()
        persistentState.notificationActionType = NotificationActionType.PAUSE_STOP.action
        openPauseActivity()
    }

    private fun openPauseActivity() {
        val intent = Intent()
        intent.setClass(requireContext(), PauseActivity::class.java)
        startActivity(intent)
        requireActivity().finish()
    }

    private fun updateCardsUi() {
        if (isVpnActivated) {
            showActiveCards()
        } else {
            showDisabledCards()
        }
    }

    private fun observeVpnState() {
        persistentState.vpnEnabledLiveData.observe(viewLifecycleOwner) {
            isVpnActivated = it
            updateMainButtonUi()
            updateCardsUi()
            syncDnsStatus()
            handleRethinkAppStatus()
            handleShimmer()
        }

        VpnController.connectionStatus.observe(viewLifecycleOwner) {
            // No need to handle states in Home screen fragment for pause state
            if (VpnController.isAppPaused()) return@observe

            syncDnsStatus()
            handleShimmer()
        }
    }

    /**
     * Captures active-state typography and the button's Material background
     * once per view, before any state-dependent styling is applied.
     */
    private fun captureActiveEmphasis() {
        appsHeadlineSizePx = b.fhsCardAllowedApps.textSize
    }

    /**
     * Reflects the VPN running/stopped state on the start/stop button.
     *
     * Uses MaterialButton's native tint + stroke (instead of swapping the
     * background drawable) so the button never enters MaterialButton's
     * "background overwritten" state — which would otherwise drop the tint and
     * desync the visible state from [isVpnActivated]. The started (running)
     * look (surface fill + outline border) matches [R.drawable.rectangle_border_background];
     * the stopped state uses the accent color for a high-visibility call-to-action.
     */
    private fun updateMainButtonUi() {
        val ctx = context ?: return
        val btn = b.fhsDnsOnOffBtn
        if (isVpnActivated) {
            // Running: quiet surface tone with an outline border; both colors
            // come from the active theme so light/dark variants stay legible
            // uppercased here: textAllCaps from XML is not reliably applied to
            // programmatically-set MaterialButton text
            btn.text = getString(R.string.hsf_stop_btn_state).uppercase()
            btn.strokeWidth = (1f * resources.displayMetrics.density).toInt()
            btn.strokeColor = ColorStateList.valueOf(UIUtils.fetchColor(ctx, R.attr.colorOutline))
            btn.backgroundTintList =
                ColorStateList.valueOf(UIUtils.fetchColor(ctx, R.attr.background))
            btn.setTextColor(UIUtils.fetchColor(ctx, R.attr.primaryTextColor))
            stopBorderAnimation()
        } else {
            // Stopped: accent-filled, high-visibility call-to-action
            btn.text = getString(R.string.hsf_start_btn_state).uppercase()
            btn.strokeWidth = 0
            // resolve accentGood from the theme attribute (not the fixed color)
            // so the accent tracks the active theme
            btn.backgroundTintList =
                ColorStateList.valueOf(UIUtils.fetchColor(ctx, R.attr.accentGood))
            btn.setTextColor(UIUtils.fetchColor(ctx, R.attr.invertedPrimaryTextColor))
            startBorderAnimation()
        }
    }

    /**
     * Animates a thin ring around the start button. Runs only while the VPN is
     * stopped to draw attention to the call-to-action. The ring is a stroke-only
     * pill drawable whose sweep-gradient highlight rotates via its shader
     * matrix, so only the border moves — never the shape.
     *
     * The highlight color stays inside the complementary band of the button's
     * own accent (accentGood), resolved from the active theme: hues near the
     * fill color would blend into the button, while the opposite side of the
     * wheel always contrasts. The hue swings smoothly across that band while
     * the ring spins fast; once the first color swing completes, the rotation
     * settles to a slower, calmer pace.
     */
    private fun startBorderAnimation() {
        val ctx = context ?: return
        val borderView = b.fhsAnimatedBorderView
        borderView.isVisible = true

        val drawable =
            borderView.background as? RotatingBorderDrawable
                ?: RotatingBorderDrawable().also {
                    it.configure(
                        UIUtils.fetchColor(ctx, R.attr.invertedPrimaryTextColor),
                        BORDER_STROKE_WIDTH_DP * resources.displayMetrics.density,
                        rainbow = true
                    )
                    borderView.background = it
                }

        if (rotationAnimator?.isRunning == true) return
        // center of the contrast band: the hue directly opposite the fill
        val hsv = FloatArray(3)
        Color.colorToHSV(UIUtils.fetchColor(ctx, R.attr.accentGood), hsv)
        val contrastHue = (hsv[0] + 180f) % 360f
        val startElapsedMs = SystemClock.elapsedRealtime()
        // the animator itself is only a frame tick; hue and rotation are both
        // derived from elapsed time so they stay in sync without drift
        rotationAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = BORDER_HUE_CYCLE_MS
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                val elapsed = SystemClock.elapsedRealtime() - startElapsedMs
                // triangle wave: sweep to one edge of the band and back
                val frac =
                    (elapsed % BORDER_HUE_CYCLE_MS).toFloat() / BORDER_HUE_CYCLE_MS
                val swing = if (frac <= 0.5f) frac * 2f else 2f - frac * 2f
                drawable.setHighlightHue(contrastHue + swing * BORDER_HUE_SWING_DEG)
                // negated so the highlight travels counterclockwise
                drawable.rotation = -borderRotationFor(elapsed)
                borderView.invalidate()
            }
            start()
        }
        Logger.v(LOG_TAG_UI, "$TAG: start button border animation started")
    }

    /**
     * Ring rotation for a given elapsed time: one turn per [BORDER_FAST_ROTATION_MS]
     * during the first hue cycle, then one turn per [BORDER_SLOW_ROTATION_MS].
     */
    private fun borderRotationFor(elapsedMs: Long): Float {
        val fastTurns = BORDER_HUE_CYCLE_MS.toFloat() / BORDER_FAST_ROTATION_MS
        if (elapsedMs <= BORDER_HUE_CYCLE_MS) {
            return (elapsedMs.toFloat() / BORDER_FAST_ROTATION_MS) * 360f
        }
        val slowMs = elapsedMs - BORDER_HUE_CYCLE_MS
        return (fastTurns + slowMs.toFloat() / BORDER_SLOW_ROTATION_MS) * 360f
    }

    private fun stopBorderAnimation() {
        rotationAnimator?.cancel()
        rotationAnimator = null
        b.fhsAnimatedBorderView.isVisible = false
    }

    private fun showDisabledCards() {
        disableFirewallCard()
        disabledDnsCard()
        disableAppsCard()
        disableProxyCard()
        disableLogsCard()
    }

    private fun showActiveCards() {
        enableFirewallCardIfNeeded()
        enableDnsCardIfNeeded()
        enableAppsCardIfNeeded()
        enableProxyCardIfNeeded()
        enableLogsCardIfNeeded()
        // comment out the below code to disable the alerts card (v0.5.5b)
        // enableAlertsCardIfNeeded()
    }

    private fun enableFirewallCardIfNeeded() {
        if (appConfig.getBraveMode().isFirewallActive()) {
            b.fhsFirewallBadgesRow.alpha = 1f
            observeUniversalStates()
            observeCustomRulesCount()
        } else {
            disableFirewallCard()
            unobserveUniversalStates()
            unObserveCustomRulesCount()
        }
    }

    private fun enableAppsCardIfNeeded() {
        // apps screen can be accessible on all app modes.
        if (isVpnActivated) {
            observeAppStates()
        } else {
            disableAppsCard()
            unobserveAppStates()
        }
    }

    private fun enableDnsCardIfNeeded() {
        if (appConfig.getBraveMode().isDnsActive()) {
            observeDnsStates()
        } else {
            disabledDnsCard()
            unobserveDnsStates()
        }
    }

    private fun enableProxyCardIfNeeded() {
        Logger.vv(LOG_TAG_UI, "$TAG enableProxyCardIfNeeded")
        if (isVpnActivated && !appConfig.getBraveMode().isDnsMode()) {
            Logger.vv(LOG_TAG_UI, "$TAG enableProxyCardIfNeeded: isVpnActivated")
            // A purchased (but currently stopped) RPN keeps the card alive so
            // the health row can render the "Stopped" state instead of
            // collapsing the card to "proxy inactive".
            val isAnyProxyEnabled =
                appConfig.isProxyEnabled() || RpnProxyManager.isRpnActive() || RpnProxyManager.hasValidSubscription()
            Logger.vv(LOG_TAG_UI, "$TAG enableProxyCardIfNeeded: isAnyProxyEnabled=$isAnyProxyEnabled")
            if (isAnyProxyEnabled) {
                showProxyActiveIndicator()
                observeProxyStates()
            } else {
                unobserveProxyStates()
                disableProxyCard()
            }
        } else {
            Logger.vv(LOG_TAG_UI, "$TAG enableProxyCardIfNeeded: isVpnActivated=$isVpnActivated")
            unobserveProxyStates()
            disableProxyCard()
        }
    }

    private fun enableLogsCardIfNeeded() {
        if (isVpnActivated && persistentState.logsEnabled) {
            observeLogsCount()
        } else {
            disableLogsCard()
        }
    }

    // comment out the below code to disable the alerts card (v0.5.5b)
    /* private fun enableAlertsCardIfNeeded() {
        if (isVpnActivated) {
            observeAlertsCount()
        } else {
            disableAlertsCard()
            unObserveAlertsCount()
        }
    }

    private fun disableAlertsCard() {
        b.fhsCardAlertsApps.text = getString(R.string.firewall_card_text_inactive)
        b.fhsCardAlertsApps.isSelected = true
    }

    private fun unObserveAlertsCount() {
        alertsViewModel.getBlockedAppsLogList().removeObservers(viewLifecycleOwner)
    }

    private fun observeAlertsCount() {
        alertsViewModel.getBlockedAppsLogList().observe(viewLifecycleOwner) {
            var message = ""
            it.forEach { apps ->
                if (it.indexOf(apps) > 2) return@forEach
                message += apps.appOrDnsName + ", "
            }
            if (message.isEmpty()) {
                b.fhsCardAlertsApps.text = "No alerts"
                b.fhsCardAlertsApps.isSelected = true
                return@observe
            }
            message = message.dropLastWhile { i -> i == ' ' }
            message = message.dropLastWhile { i -> i == ',' }
            b.fhsCardAlertsApps.text = "$message recently blocked"
            b.fhsCardAlertsApps.isSelected = true
        }
    } */
    private var proxyStateListenerJob: Job? = null
    private var dnsStateListenerJob: Job? = null
    // last sampled p50 latency (ms) of the active resolver, used by
    // renderDnsHeadline() whenever either latency or region updates
    private var lastDnsP50: Long? = null
    // last known DNS status
    private var lastDnsStatus: Int? = null
    // bumped on every updateUiWithDnsStates() invocation; on going blocklist
    // lookups compare against it so only the latest DNS selection result is
    // applied to fhsCardDnsConnectedDns
    @Volatile
    private var dnsBlocklistGeneration: Int = 0
    // Cache the distinctUntilChanged() LiveData so the same observer instance is reused and
    // unobserveProxyStates() can actually remove it. Without this, every call to
    // observeProxyStates() creates a NEW MediatorLiveData wrapper and registers a brand-new
    // observer – they accumulate silently and all fire together on the next state change.
    private var proxyStatusLiveData: androidx.lifecycle.LiveData<Int>? = null

    // Guard flag: prevents observeDnsStates() from registering duplicate observers on
    // connectedDns / regionLiveData across the multiple updateCardsUi() call sites within a
    // single view-lifecycle.  Reset in unobserveDnsStates() and onDestroyView().
    private var dnsObserverActive: Boolean = false

    private fun observeProxyStates() {
        Logger.vv(LOG_TAG_UI, "$TAG observeProxyStates")
            if (proxyStatusLiveData != null) {
            // Restart the job here if it is no longer active so the card stays live.
            if (proxyStateListenerJob?.isActive != true) {
                val lastStatus = proxyStatusLiveData!!.value
                Logger.vv(LOG_TAG_UI, "$TAG proxy state changed to $lastStatus")
                if (lastStatus != null) {
                    startProxyPollingForStatus(lastStatus)
                }
            }
            return
        } else {
            Logger.vv(LOG_TAG_UI, "$TAG observeProxyStates: first time")
        }
        proxyStatusLiveData = persistentState.getProxyStatus()
        Logger.vv(LOG_TAG_UI, "$TAG proxy state changed to ${proxyStatusLiveData?.value}")
        proxyStatusLiveData?.distinctUntilChanged()?.observe(viewLifecycleOwner) { resId ->
            Logger.vv(LOG_TAG_UI, "$TAG proxy state changed to $resId")
            startProxyPollingForStatus(resId)
        }
    }

    /**
     * Starts the health-row poll for the given persisted proxy status.
     * A status of `-1` means no proxy provider is configured; that still
     * shows a poll when RPN is purchased (but stopped) so the card can
     * render the RPN "Stopped" state, and only collapses to the compact
     * "proxy inactive" indicator when there is no RPN purchase.
     */
    private fun startProxyPollingForStatus(resId: Int) {
        if (resId != -1) {
            startProxyStatePolling(resId)
        } else if (view != null && isAdded) {
            if (RpnProxyManager.hasValidSubscription()) {
                startProxyStatePolling(R.string.rpn_title)
            } else {
                showProxyInactive()
            }
        }
    }

    private fun startDnsStatePolling() {
        if (dnsStateListenerJob?.isActive == true) {
            Logger.vv(LOG_TAG_UI, "$TAG cancel prev dns state listener job")
            dnsStateListenerJob?.cancel()
            dnsStateListenerJob = null
        }
        dnsStateListenerJob = ui("dnsStates") {
            while (isAdded && view != null) {
                val checkStart = SystemClock.elapsedRealtime()
                updateUiWithDnsStatusPolled()
                syncDnsStatus()
                val elapsed = SystemClock.elapsedRealtime() - checkStart
                val nextDelay = elapsed.coerceIn(MIN_POLL_DELAY_MS, MAX_PROXY_POLL_DELAY_MS)
                Logger.v(LOG_TAG_UI, "$TAG dns poll: check took ${elapsed}ms, next delay ${nextDelay}ms")
                kotlinx.coroutines.delay(nextDelay.milliseconds)
            }
            dnsStateListenerJob?.cancel()
        }
    }

    private suspend fun updateUiWithDnsStatusPolled() {
        if (view == null || !isAdded) {
            dnsStateListenerJob?.cancel()
            return
        }

        if (!isVpnActivated) {
            dnsStateListenerJob?.cancel()
            return
        }

        val id = getConnectedDnsId()
        val status = withContext(Dispatchers.IO) {
            VpnController.getDnsStatus(id)
        }
        // re-sample latency every poll; the one-shot sample taken in
        // observeDnsStates() usually runs before the tunnel is ready (-1) and
        // would otherwise keep the headline stuck on the fallback value
        val p50 = withContext(Dispatchers.IO) {
            VpnController.p50(id)
        }

        uiCtx {
            // keep the last valid sample; -1 just means "no data yet"
            if (p50 >= 0) {
                lastDnsP50 = p50
            }
            updateUiWithDnsStates(status)
        }
    }

    private fun getConnectedDnsId(): String {
        val preferredId = if (appConfig.isSystemDns()) {
            Backend.System
        } else if (appConfig.isSmartDnsEnabled()) {
            Backend.Plus
        } else {
            Backend.Preferred
        }

        return if (WireguardManager.oneWireGuardEnabled()) {
            val id = WireguardManager.getOneWireGuardProxyId()
            if (id == null) {
                preferredId
            } else {
                "${ProxyManager.ID_WG_BASE}${id}"
            }
        } else {
            preferredId
        }
    }

    /**
     * Cancels any in-flight polling job and starts a fresh one for the given [resId].
     * Extracted so both the LiveData observer and the resume-restart path share identical
     * logic and there is a single place to read/cancel/assign [proxyStateListenerJob].
     */
    private fun startProxyStatePolling(resId: Int) {
        if (proxyStateListenerJob?.isActive == true) {
            Logger.vv(LOG_TAG_UI, "$TAG cancel prev proxy state listener job")
            proxyStateListenerJob?.cancel()
            proxyStateListenerJob = null
        }
        proxyStateListenerJob = ui("proxyStates") {
            while (isAdded && view != null) {
                // updateUiWithProxyStates is now suspend: the while-loop awaits its
                // completion before the next delay, so no overlapping IO coroutines.
                val checkStart = SystemClock.elapsedRealtime()
                updateUiWithProxyStates(resId)
                val elapsed = SystemClock.elapsedRealtime() - checkStart
                // Adaptive delay: if the library status call was slow (e.g. 6 s for a
                // distant server), wait at least that long before the next check.
                // This prevents back-to-back in-flight requests while still staying
                // responsive when the service is fast. Capped at MAX_PROXY_POLL_DELAY_MS
                // so the card never goes stale for too long.
                val nextDelay = elapsed.coerceIn(MIN_POLL_DELAY_MS, MAX_PROXY_POLL_DELAY_MS)
                Logger.v(LOG_TAG_UI, "$TAG proxy poll: check took ${elapsed}ms, next delay ${nextDelay}ms")
                kotlinx.coroutines.delay(nextDelay.milliseconds)
            }
            proxyStateListenerJob?.cancel()
        }
    }

    // suspend so the while-loop in observeProxyStates awaits completion before the next delay,
    // and so withContext(IO) is a structured child of proxyStateListenerJob (cancellable).
    private suspend fun updateUiWithProxyStates(resId: Int) {
        // These checks run on the Main thread (called from ui("proxyStates") coroutine).
        if (view == null || !isAdded) {
            proxyStateListenerJob?.cancel()
            return
        }

        if (
            !viewLifecycleOwner
                .lifecycle
                .currentState
                .isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        ) {
            return
        }

        if (!isVpnActivated) {
            proxyStateListenerJob?.cancel()
            disableProxyCard()
            return
        }

        // get proxy type from app config
        val proxyType = AppConfig.ProxyType.of(appConfig.getProxyType())

        if (proxyType.isProxyTypeWireguard() || RpnProxyManager.isRpnActive()) {
            withContext(Dispatchers.IO) {
                if (view == null || !isAdded) return@withContext

                val proxies = WireguardManager.getActiveConfigs()
                val rpnProxies = if (RpnProxyManager.isRpnActive()) RpnProxyManager.getEnabledConfigs() else emptyList()
                var active = 0
                var failing = 0
                var idle = 0
                val now = System.currentTimeMillis()
                Logger.v(LOG_TAG_UI, "$TAG active proxies; wg: ${proxies.size}, rpn: ${rpnProxies.size}")

                // If no proxies are configured but WireGuard/rpn is enabled, show appropriate message
                if (proxies.isEmpty() && rpnProxies.isEmpty()) {
                    // counts are pending, but the RPN stopped flag is
                    // independent of them
                    val stoppedCount =
                        if (isRpnStoppedEarly()) RpnProxyManager.getEnabledConfigs().size else 0
                    uiCtx {
                        b.fhsCardOtherProxyCount.visibility = View.VISIBLE
                        b.fhsCardOtherProxyCount.setTextAnimated(getString(R.string.lbl_checking))
                        updateStoppedSlot(isStoppedSlotVisible())
                        b.fhsProxyStoppedCount.text = stoppedCount.toString()
                    }
                    return@withContext
                }

                proxies.forEach {
                    val proxyId = "${ProxyManager.ID_WG_BASE}${it.getId()}"
                    val triple = getProxyStatus(proxyId, now, active, failing, idle)
                    active = triple.first
                    failing = triple.second
                    idle = triple.third
                    Logger.v(LOG_TAG_UI, "$TAG wg proxy status: act: $active, fail: $failing, idle: $idle")
                }
                rpnProxies.forEach {
                    val proxyId = if (it.key.equals(AUTO_SERVER_ID, true)) Backend.RpnWin else Backend.RpnWin + it.key
                    val triple = getProxyStatus(proxyId, now, active, failing, idle)
                    active = triple.first
                    failing = triple.second
                    idle = triple.third
                    Logger.v(LOG_TAG_UI, "$TAG rpn proxy status: act: $active, fail: $failing, idle: $idle")
                }

                val isBoth = proxies.isNotEmpty() && rpnProxies.isNotEmpty()

                // The "Stopped" slot tracks the RPN alone: while the RPN is
                // not routing (soft-stopped), every selected RPN server counts
                // as stopped — even while WireGuard is up.
                val isRpnStopped = RpnProxyManager.hasValidSubscription() && !RpnProxyManager.isRpnActive()
                val stoppedCount = if (isRpnStopped) RpnProxyManager.getEnabledConfigs().size else 0

                uiCtx {
                    b.fhsCardOtherProxyCount.visibility = View.VISIBLE
                    // Colored scheme is reserved for RPN (also when RPN and
                    // WireGuard are both active); WireGuard-only or any other
                    // proxy renders monochrome.
                    updateProxyHealthCounts(
                        active,
                        idle,
                        failing,
                        colored = rpnProxies.isNotEmpty(),
                        stopped = stoppedCount,
                        showStopped = isStoppedSlotVisible()
                    )
                    Logger.v(LOG_TAG_UI, "$TAG overall wg proxy status; proxies: ${proxies.size}, active: $active, failing: $failing, idle: $idle")

                    if (active == 0 && idle == 0 && failing == 0 && (proxies.isNotEmpty() || rpnProxies.isNotEmpty())) {
                        Logger.w(LOG_TAG_UI, "$TAG proxy status empty but proxies exist, health row shows no state")
                    }

                    if (isBoth) {
                        b.fhsCardOtherProxyCount.isSelected = true
                        b.fhsCardOtherProxyCount.setTextAnimated(getString(R.string.two_argument, getString(R.string.rpn_title), getString(R.string.lbl_wireguard)))
                    } else {
                        b.fhsCardOtherProxyCount.setTextAnimated(getString(resId))
                    }
                }
            }
        } else {
            // For non-WireGuard proxies, show active if any proxy is enabled
            if (view == null || !isAdded) return

            if (appConfig.isProxyEnabled() || RpnProxyManager.isRpnActive()) {
                showProxyActiveIndicator()
            } else if (RpnProxyManager.hasValidSubscription()) {
                // RPN purchased but not routing: render the RPN "Stopped"
                // state instead of collapsing the card to "proxy inactive"
                showRpnStopped()
                return
            } else {
                showProxyInactive()
                return
            }
            b.fhsCardOtherProxyCount.visibility = View.VISIBLE
            b.fhsCardOtherProxyCount.setTextAnimated(getString(resId))
        }
    }

    /**
     * Renders the RPN "Stopped" state on the proxy card: the headline reads
     * "RPN" and every selected RPN server is counted in the "Stopped" slot
     * while the live/idle/failing counts stay zero. Only reachable when a
     * valid RPN subscription exists but RPN is not routing.
     * Must be called off the Main thread (it fetches the selected servers).
     */
    private suspend fun showRpnStopped() {
        val stopped = withContext(Dispatchers.IO) {
            if (view == null || !isAdded) return@withContext 0
            RpnProxyManager.getEnabledConfigs().size
        }
        uiCtx {
            if (view == null || !isAdded) return@uiCtx

            b.fhsCardOtherProxyCount.visibility = View.VISIBLE
            b.fhsCardOtherProxyCount.alpha = 1f
            b.fhsCardOtherProxyCount.isSelected = true
            b.fhsCardOtherProxyCount.setTextAnimated(getString(R.string.rpn_title))
            updateProxyHealthCounts(
                active = 0,
                idle = 0,
                failing = 0,
                colored = true,
                stopped = stopped,
                showStopped = true
            )
        }
    }

    private suspend fun getProxyStatus(proxyId: String, now: Long, a: Int, f: Int, i: Int): Triple<Int, Int, Int> {
        var active = a
        var failing = f
        var idle = i
        Logger.vv(LOG_TAG_UI, "$TAG init stats check for $proxyId")

        val isRpn = proxyId.startsWith(Backend.RpnWin)

        val stats = VpnController.getProxyStats(proxyId)
        val statusPair = VpnController.getProxyStatusById(proxyId)

        val status = UIUtils.ProxyStatus.entries.find { s -> s.id == statusPair.first }

        val dnsStats = if (!isRpn && isSplitDns()) {
            VpnController.getDnsStatus(proxyId)
        } else {
            null
        }

        // Handle paused state as idle (TPU is the pause status)
        if (status == UIUtils.ProxyStatus.TPU) {
            idle++
            return Triple(active, failing, idle)
        }

        // DNS error check (WireGuard only – dnsStats is null for RPN)
        if (dnsStats != null && isDnsError(dnsStats)) {
            failing++
            return Triple(active, failing, idle)
        }

        // Null stats: for WireGuard this is a genuine failure signal.
        // For RPN, the proxy entry might not yet be registered; treat as idle/waiting.
        if (stats == null) {
            if (isRpn) idle++ else failing++
            return Triple(active, failing, idle)
        }

        val lastOk = stats.lastOK
        val since = stats.since

        // since/lastOK uptime heuristic: only applied to WireGuard.
        // For RPN the status enum owns the failure decision.
        if (!isRpn && now - since > WG_UPTIME_THRESHOLD && lastOk == 0L) {
            failing++
            return Triple(active, failing, idle)
        }

        if (status != null) {
            when (status) {
                UIUtils.ProxyStatus.TOK -> {
                    if (isRpn || lastOk > 0L || now - since < WG_UPTIME_THRESHOLD) {
                        active++
                    } else {
                        failing++
                    }
                }
                UIUtils.ProxyStatus.TUP -> {
                    // Starting / connecting – count as active
                    active++
                }
                UIUtils.ProxyStatus.TZZ -> {
                    // Idle state.  For RPN, idle is a normal operational state; count it.
                    // For WireGuard, only count as idle if it ever had a successful handshake.
                    if (isRpn || lastOk > 0L || now - since < WG_UPTIME_THRESHOLD) {
                        idle++
                    } else {
                        failing++
                    }
                }
                // UIUtils.ProxyStatus.TPU handled above
                else -> {
                    // Explicitly bad states (TKO, END, TNT, unknown)
                    failing++
                }
            }
        } else {
            idle++
        }
        return Triple(active, failing, idle)
    }

    private fun isSplitDns(): Boolean {
        // by default, the split dns is enabled for android R and above, as we know the app
        // which sends dns queries
        if (isAtleastR()) return true

        return persistentState.splitDns
    }

    private fun isDnsError(statusId: Int?): Boolean {
        if (statusId == null) return true

        val s = Transaction.Status.fromId(statusId)
        return s == Transaction.Status.BAD_QUERY || s == Transaction.Status.BAD_RESPONSE || s == Transaction.Status.NO_RESPONSE || s == Transaction.Status.SEND_FAIL || s == Transaction.Status.CLIENT_ERROR || s == Transaction.Status.INTERNAL_ERROR || s == Transaction.Status.TRANSPORT_ERROR
    }


    private fun unobserveProxyStates() {
        // Must remove from the distinctUntilChanged() wrapper we registered on, not from the
        // raw getProxyStatus() LiveData. Previously this called removeObservers() on the wrong
        // LiveData instance, leaving ghost observers that accumulated across calls.
        proxyStatusLiveData?.removeObservers(viewLifecycleOwner)
        proxyStatusLiveData = null
        proxyStateListenerJob?.cancel()
        proxyStateListenerJob = null
    }

    private fun disableLogsCard() {
        if (view == null || !isAdded) return

        logsPage.fhsCardAllowedLogsCount.visibility = View.GONE
        logsPage.fhsCardAllowedLogsLabel.text = getString(R.string.lbl_disabled)
        logsPage.fhsCardBlockedLogsCount.visibility = View.GONE
        logsPage.fhsCardBlockedLogsLabel.visibility = View.GONE
        logsPage.fhsCardLogsDuration.visibility = View.GONE
        // heatmap (grid + its legend) carries no data when logging is off
        logsPage.fhsLogsGrid.visibility = View.GONE
        logsPage.fhsLogsLegend.visibility = View.GONE
        // allowed/blocked chips only make sense on an active card; when the
        // logging preference is off they give way to an inline enable chip
        logsPage.fhsLogsToggleGroup.visibility = View.GONE
        // collapse the pager, but keep a hint so the card never reads as
        // empty; the transition animates the collapse smoothly
        animateLogsCardTransition()
        logsCardActive = false
        b.fhsLogsPager.visibility = View.GONE
        updateLogsDotsVisibility()
        val showEnableChip = !persistentState.logsEnabled
        b.fhsLogsDisabledTxt.text =
            getString(
                if (showEnableChip) R.string.logs_card_disabled_logs_hint
                else R.string.logs_card_disabled_vpn_hint
            )
        b.fhsLogsDisabledRow.visibility = View.VISIBLE
        b.fhsLogsEnableChip.visibility =
            if (showEnableChip) View.VISIBLE else View.GONE
    }

    /**
     * Renders the compact, non-intrusive "proxy inactive" indicator: dimmed
     * headline, dimmed proxy icon, and the health legend hidden while no
     * proxy is running.
     */
    private fun showProxyInactive() {
        if (view == null || !isAdded) return

        Logger.w(LOG_TAG_UI, "$TAG proxy inactive, showing compact indicator")
        b.fhsCardOtherProxyCount.text = getString(R.string.hsf_proxy_off_indicator)
        b.fhsCardOtherProxyCount.alpha = 0.65f
        b.fhsProxyHealthContainer.isVisible = false
    }

    /** Restores the proxy card to full emphasis for a running proxy. */
    private fun showProxyActiveIndicator() {
        if (view == null || !isAdded) return

        b.fhsProxyHealthContainer.isVisible = true
        b.fhsCardOtherProxyCount.alpha = 1f
        // Provisional scheme until the proxy poll computes the exact one:
        // colored only when RPN is active, monochrome otherwise. The stopped
        // slot (RPN-only) shows with a provisional 0; the poll corrects both.
        val isRpnActive = RpnProxyManager.isRpnActive()
        applyProxyHealthColorScheme(isRpnActive)
        updateStoppedSlot(isStoppedSlotVisible())
        b.fhsProxyStoppedCount.text = "0"
    }

    /**
     * Applies the proxy-health row color scheme. RPN users get the semantic
     * accent colors (good/warning/bad); everyone else (WireGuard-only, plain
     * SOCKS5/HTTP proxies) gets a monochrome scheme where a single neutral
     * hue fades with importance: active is fully opaque, idle is dimmer, and
     * failing is the dimmest. When RPN and WireGuard are both active, the
     * colored scheme wins.
     * Must be called on Main.
     */
    private fun applyProxyHealthColorScheme(colored: Boolean) {
        if (view == null || !isAdded) return

        val ctx = requireContext()
        // The stopped slot tracks the RPN alone and carries no health
        // semantics, so it stays a dimmed neutral in both schemes.
        val stopped = ColorUtils.setAlphaComponent(
            UIUtils.fetchColor(ctx, R.attr.primaryLightColorText),
            STOPPED_ALPHA
        )
        b.fhsProxyBarStopped.setBackgroundColor(stopped)
        b.fhsProxyDotStopped.backgroundTintList = ColorStateList.valueOf(stopped)
        b.fhsProxyStoppedCount.setTextColor(stopped)
        if (colored) {
            val good = UIUtils.fetchColor(ctx, R.attr.accentGood)
            val warning = UIUtils.fetchColor(ctx, R.attr.accentWarning)
            val bad = UIUtils.fetchColor(ctx, R.attr.accentBad)
            b.fhsProxyBarActive.setBackgroundColor(good)
            b.fhsProxyBarIdle.setBackgroundColor(warning)
            b.fhsProxyBarFailing.setBackgroundColor(bad)
            b.fhsProxyDotActive.backgroundTintList = ColorStateList.valueOf(good)
            b.fhsProxyDotIdle.backgroundTintList = ColorStateList.valueOf(warning)
            b.fhsProxyDotFailing.backgroundTintList = ColorStateList.valueOf(bad)
            b.fhsProxyLiveCount.setTextColor(good)
            b.fhsProxyIdleCount.setTextColor(warning)
            b.fhsProxyFailingCount.setTextColor(bad)
        } else {
            // Monochrome: one neutral hue with stepped alpha so the row stays
            // readable without carrying good/warning/bad semantics. Active is
            // fully opaque; idle and failing fade out progressively.
            val hue = UIUtils.fetchColor(ctx, R.attr.primaryLightColorText)
            val active = hue
            val idle = ColorUtils.setAlphaComponent(hue, MONO_IDLE_ALPHA)
            val failing = ColorUtils.setAlphaComponent(hue, MONO_FAILING_ALPHA)
            b.fhsProxyBarActive.setBackgroundColor(active)
            b.fhsProxyBarIdle.setBackgroundColor(idle)
            b.fhsProxyBarFailing.setBackgroundColor(failing)
            b.fhsProxyDotActive.backgroundTintList = ColorStateList.valueOf(active)
            b.fhsProxyDotIdle.backgroundTintList = ColorStateList.valueOf(idle)
            b.fhsProxyDotFailing.backgroundTintList = ColorStateList.valueOf(failing)
            b.fhsProxyLiveCount.setTextColor(active)
            b.fhsProxyIdleCount.setTextColor(idle)
            b.fhsProxyFailingCount.setTextColor(failing)
        }
    }

    /**
     * Renders the per-state proxy counts (Live / Idle / Failing) in the health
     * row at the bottom of the proxy card. [colored] selects the accent-color
     * scheme (RPN) versus the monochrome scheme (all other proxies).
     * [stopped] is the count shown in the RPN-only "Stopped" slot (1 when the
     * RPN is not routing), and [showStopped] controls that slot's visibility.
     * Must be called on Main.
     */
    private fun updateProxyHealthCounts(
        active: Int,
        idle: Int,
        failing: Int,
        colored: Boolean,
        stopped: Int = 0,
        showStopped: Boolean = false
    ) {
        if (view == null || !isAdded) return

        b.fhsProxyLiveCount.text = active.toString()
        b.fhsProxyIdleCount.text = idle.toString()
        b.fhsProxyFailingCount.text = failing.toString()
        b.fhsProxyStoppedCount.text = stopped.toString()
        updateStoppedSlot(showStopped)
        applyProxyHealthColorScheme(colored)
    }

    /**
     * Shows/hides the RPN-only "Stopped" slot (legend column + bar segment).
     * Both views toggle together so the remaining bars keep equal widths.
     */
    private fun updateStoppedSlot(show: Boolean) {
        if (view == null || !isAdded) return

        b.fhsProxyBarStopped.isVisible = show
        b.fhsProxyStoppedColumn.isVisible = show
    }

    /**
     * The "Stopped" slot appears only when RPN is part of the proxy card:
     * while RPN is routing, or when RPN is stopped but the subscription is
     * still valid (so the slot can flag "stopped"). It never shows for
     * WireGuard-only or plain SOCKS5/HTTP proxies.
     */
    private fun isStoppedSlotVisible(): Boolean {
        return RpnProxyManager.isRpnActive() || RpnProxyManager.hasValidSubscription()
    }

    /** True when a valid RPN subscription exists but RPN is not routing. */
    private fun isRpnStoppedEarly(): Boolean {
        return !RpnProxyManager.isRpnActive() && RpnProxyManager.hasValidSubscription()
    }

    private fun toggleLogsView(mode: ActivityDisplayMode) {
        if (displayMode == mode) return
        displayMode = mode
        updateLogsHeaderEmphasis(mode == ActivityDisplayMode.BLOCKED)

        // re-render from the cached aggregate only; the toggle must not
        // trigger another database query
        lastActivityState?.let { buildLogsHeatmap(it, mode) }
    }

    /**
     * Dims the logs-card header block that does not match the active toggle
     * mode so the emphasized (full-opacity) count always tracks the selected
     * allowed/blocked chip. Idempotent; safe to call on every toggle and
     * re-render.
     */
    private fun updateLogsHeaderEmphasis(blocked: Boolean) {
        if (view == null || !isAdded) return

        logsPage.fhsLogsAllowedHeader.alpha =
            if (blocked) LOGS_HEADER_UNSELECTED_ALPHA else 1f
        logsPage.fhsLogsBlockedHeader.alpha =
            if (blocked) 1f else LOGS_HEADER_UNSELECTED_ALPHA
    }

    private fun updateLogsToggleUi(blocked: Boolean) {
        displayMode = if (blocked) ActivityDisplayMode.BLOCKED else ActivityDisplayMode.ALLOWED
        if (blocked) {
            logsPage.fhsLogsBlockedChip.isChecked = true
        } else {
            logsPage.fhsLogsAllowedChip.isChecked = true
        }
        updateLogsHeaderEmphasis(blocked)
    }

    /**
     * Builds the two-page swipeable logs card. Page 1 is the top-apps
     * histogram (the card's default view); the activity wall sits on page 2,
     * one swipe to the right. The histogram page exists only when the active
     * brave mode enforces the firewall — DNS-only mode shows a single page
     * (the activity wall) with no page dots.
     */
    private fun setupLogsPager() {
        logsPage = ViewHomeLogsActivityBinding.inflate(layoutInflater)
        topAppsPage = ViewHomeLogsAppsBinding.inflate(layoutInflater)

        // restore the histogram chip selection before wiring listeners so the
        // initial check state matches without triggering a redundant reload
        topAppsBlockedMode = savedTopAppsBlockedMode
        if (topAppsBlockedMode) topAppsPage.fhsTopAppsBlockedChip.isChecked = true

        b.fhsLogsPager.adapter = LogsPagesAdapter(pageViews())
        b.fhsLogsPager.getChildAt(0).overScrollMode = View.OVER_SCROLL_NEVER

        b.fhsLogsDots.visibility =
            if (pagerPageCount() > 1) View.VISIBLE else View.GONE
        updatePagerDots(0)
        b.fhsLogsDot0.setOnClickListener { b.fhsLogsPager.setCurrentItem(0, true) }
        b.fhsLogsDot1.setOnClickListener { b.fhsLogsPager.setCurrentItem(1, true) }

        logsPagerCallback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updatePagerDots(position)
                savedLogsPagePosition = position
                // load/refresh the histogram lazily: it sits at position 0
                // (only when the two-page layout applies; position 0 is the
                // activity wall in DNS-only mode)
                if (position == 0 && pagerPageCount() > 1) refreshTopAppsHistogram()
            }
        }
        b.fhsLogsPager.registerOnPageChangeCallback(logsPagerCallback!!)

        // restore the page the user last viewed (in-memory until the
        // process dies); the callback above syncs the dots for the jump
        if (pagerPageCount() > 1 && savedLogsPagePosition == 1) {
            b.fhsLogsPager.setCurrentItem(1, false)
        }
        updateLogsDotsVisibility()

        // the pager pages consume touches, so the card-level click listener
        // never fires over them; delegate the same navigation from each
        // page's open areas (the heatmap grid and histogram bars keep their
        // own interactions)
        logsPage.root.setOnClickListener { openNetworkLogs() }
        topAppsPage.root.setOnClickListener { openNetworkLogs() }

        topAppsPage.fhsTopAppsHistogram.setLightTheme(isLightTheme())
        applyTopAppsLegendColors()
        topAppsPage.fhsTopAppsHistogram.onEntrySelected = { entry ->
            showTopAppsEntry(entry)
        }

        topAppsPage.fhsTopAppsToggleGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val blocked = checkedIds.contains(R.id.fhs_top_apps_blocked_chip)
            if (blocked == topAppsBlockedMode) return@setOnCheckedStateChangeListener
            topAppsBlockedMode = blocked
            savedTopAppsBlockedMode = blocked
            // re-rank from the database; a cached re-render would show the
            // wrong ordering for the newly selected mode
            refreshTopAppsHistogram(force = true)
        }

        renderTopAppsHeader(null)
        // the histogram is the card's opening page; load it right away
        refreshTopAppsHistogram()
    }

    /** Re-applies the firewall-only page rule after a brave-mode change. */
    private fun refreshLogsPagerMode() {
        if (!this::logsPage.isInitialized || !this::topAppsPage.isInitialized) return
        val twoPages = pagerPageCount() > 1
        // re-assigning the adapter rebuilds the page list; position 0 is the
        // histogram in the two-page layout, the activity wall in DNS-only.
        // The user's last page is restored when it still exists.
        b.fhsLogsPager.adapter = LogsPagesAdapter(pageViews())
        updateLogsDotsVisibility()
        if (twoPages && savedLogsPagePosition == 1) {
            b.fhsLogsPager.setCurrentItem(1, false)
        }
        updatePagerDots(b.fhsLogsPager.currentItem)
    }

    /**
     * The dots exist only when the card is showing content AND the two-page
     * layout applies. Centralized here so a brave-mode change running after
     * disableLogsCard() cannot re-show the dots on a collapsed card.
     */
    private fun updateLogsDotsVisibility() {
        if (view == null || !isAdded) return

        b.fhsLogsDots.visibility =
            if (logsCardActive && pagerPageCount() > 1) View.VISIBLE else View.GONE
    }

    private fun pagerPageCount(): Int {
        return if (appConfig.getBraveMode().isFirewallActive()) 2 else 1
    }

    private fun pageViews(): List<View> {
        return if (pagerPageCount() > 1) {
            // histogram first, activity wall one swipe to the right
            listOf(topAppsPage.root, logsPage.root)
        } else {
            listOf(logsPage.root)
        }
    }

    private fun updatePagerDots(position: Int) {
        if (view == null || !isAdded) return

        // the dot keeps its circular shape at all times; selection is shown
        // purely by filling the color (full opacity vs dimmed)
        listOf(b.fhsLogsDot0, b.fhsLogsDot1).forEachIndexed { index, dot ->
            dot.alpha = if (index == position) 1f else DOT_UNSELECTED_ALPHA
        }
    }

    /** Opens the network logs screen; shared by the card and its pages. */
    private fun openNetworkLogs() {
        Logger.v(LOG_TAG_UI, "$TAG: click event on logs card")
        startActivity(ScreenType.LOGS, NetworkLogsActivity.Tabs.NETWORK_LOGS.screen)
        logEvent(
            EventType.UI_NAVIGATION,
            "HomeScreen: Logs card clicked",
            "Navigating to NetworkLogsActivity from HomeScreenFragment"
        )
    }

    /**
     * Fetches the top-apps histogram data for the trailing 24-hour window.
     * Allowed mode ranks by total bytes (connection-tracker only); blocked
     * mode ranks by blocked-attempt count summed across connection-tracker
     * and dns-log rows — the two tables hold distinct event kinds, and the
     * merge key (uid, appName) collapses them into a single entry per app so
     * nothing is listed or counted twice. Unknown apps are excluded in both
     * modes.
     */
    private fun refreshTopAppsHistogram(force: Boolean = false) {
        if (!appConfig.getBraveMode().isFirewallActive()) return

        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastTopAppsFetchAt < TOP_APPS_REFRESH_MIN_INTERVAL_MS) return
        lastTopAppsFetchAt = now

        val gen = ++topAppsRequestGen
        val blocked = topAppsBlockedMode
        io {
            val entries = loadTopAppsEntries(blocked)
            uiCtx {
                if (gen != topAppsRequestGen) return@uiCtx
                topAppsEntries = entries
                // a data refresh invalidates any tapped-app header swap
                topAppsHeaderRevertJob?.cancel()
                topAppsHeaderRevertJob = null
                topAppsPage.fhsTopAppsHistogram.submit(entries, blocked)
                applyTopAppsLegendColors()
                topAppsPage.fhsTopAppsEmpty.isVisible = entries.isEmpty()
                topAppsPage.fhsTopAppsHistogram.isVisible = entries.isNotEmpty()
                renderTopAppsHeader(null)
            }
        }
    }

    private suspend fun loadTopAppsEntries(blocked: Boolean): List<AppHistogramView.Entry> {
        val ctx = context ?: return emptyList()
        val end = System.currentTimeMillis()
        val start = end - LogActivityAggregator.HOURS_IN_WINDOW * LogActivityAggregator.HOUR_MS
        return try {
            if (blocked) {
                val conn = connectionTrackerRepository.getTopBlockedApps(start, end, TOP_APPS_COUNT)
                val dns = dnsLogRepository.getTopBlockedApps(start, end, TOP_APPS_COUNT)
                val merged = LinkedHashMap<Pair<Int, String>, Long>()
                for (row in conn + dns) {
                    if (isUnknownAppName(row.appName)) continue
                    val key = row.uid to row.appName
                    merged[key] = (merged[key] ?: 0L) + row.blocked
                }
                merged.entries
                    .sortedByDescending { it.value }
                    .take(TOP_APPS_COUNT)
                    .map {
                        AppHistogramView.Entry(
                            it.key.first, it.key.second, 0L, 0L, it.value,
                            iconForUid(ctx, it.key.first)
                        )
                    }
            } else {
                connectionTrackerRepository.getTopAppsByUsage(start, end, TOP_APPS_COUNT)
                    .filter { !isUnknownAppName(it.appName) }
                    .map {
                        AppHistogramView.Entry(
                            it.uid, it.appName,
                            it.unmeteredTotalBytes(), it.meteredTotalBytes(), 0L,
                            iconForUid(ctx, it.uid)
                        )
                    }
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "$TAG: failed to load top apps: ${e.message}", e)
            emptyList()
        }
    }

    // the "Unknown" entry is a catch-all for uids that carry no resolvable
    // app; it is noise in a per-app ranking, so it is dropped
    private fun isUnknownAppName(appName: String): Boolean {
        return appName.isBlank() ||
                appName == Constants.UNKNOWN_APP ||
                appName == getString(R.string.network_log_app_name_unknown)
    }

    private fun iconForUid(ctx: Context, uid: Int): Drawable? {
        return try {
            ctx.packageManager.getPackagesForUid(uid)?.firstOrNull()?.let {
                Utilities.getIcon(ctx, it)
            } ?: Utilities.getDefaultIcon(ctx)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Updates the header blocks above the histogram, mirroring the activity
     * wall's header: two value-over-label stacks (Wi-Fi, Mobile data) in
     * allowed mode, a single total stack in blocked mode. With no selection
     * the values are the window totals; a tapped slot temporarily swaps in
     * that app's values and names it in the window caption below.
     */
    private fun renderTopAppsHeader(entry: AppHistogramView.Entry?) {
        if (view == null || !isAdded) return

        val wifiValue = topAppsPage.fhsTopAppsWifiValue
        val wifiLabel = topAppsPage.fhsTopAppsWifiLabel
        val mobileHeader = topAppsPage.fhsTopAppsMobileHeader
        val mobileValue = topAppsPage.fhsTopAppsMobileValue

        // the tapped app is named in the window caption for as long as its
        // values are shown; otherwise the caption carries the window text
        val windowCaption = topAppsPage.fhsTopAppsWindow
        windowCaption.text =
            if (entry == null) getString(R.string.logs_card_heatmap_window)
            else entry.appName

        if (topAppsBlockedMode) {
            // blocked has no connection-type split: one total, one block
            mobileHeader.isVisible = false
            wifiValue.text =
                formatDecimal(entry?.blockedCount ?: topAppsEntries.sumOf { it.blockedCount })
            wifiLabel.setText(R.string.logs_card_apps_count_label_blocked)
            return
        }

        mobileHeader.isVisible = true
        wifiValue.text = formatBytesCompact(
            entry?.unmeteredBytes ?: topAppsEntries.sumOf { it.unmeteredBytes }
        )
        mobileValue.text = formatBytesCompact(
            entry?.meteredBytes ?: topAppsEntries.sumOf { it.meteredBytes }
        )
        wifiLabel.setText(R.string.logs_card_apps_legend_wifi)
    }

    /**
     * Tapping a bar swaps the header totals for that app's values for a few
     * seconds, then reverts; tapping the selected bar again reverts at once.
     * The header blocks themselves stay in place — only the numbers (and the
     * window caption, naming the app) change, so nothing jumps.
     */
    private fun showTopAppsEntry(entry: AppHistogramView.Entry?) {
        if (view == null || !isAdded) return

        topAppsHeaderRevertJob?.cancel()
        topAppsHeaderRevertJob = null
        renderTopAppsHeader(entry)
        if (entry == null) return

        topAppsHeaderRevertJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(TOP_APPS_HEADER_REVERT_MS)
            if (isAdded && view != null) renderTopAppsHeader(null)
        }
    }

    // compact data label for the histogram header: whole numbers below GB
    // ("234 MB"), a single decimal from GB up ("1.5 GB")
    private fun formatBytesCompact(bytes: Long): String {
        val unit = getCommonUnit(bytes, 0)
        val v = when (unit) {
            "TB" -> bytes / BYTES_PER_TB
            "GB" -> bytes / BYTES_PER_GB
            "MB" -> bytes / BYTES_PER_MB
            "KB" -> bytes / BYTES_PER_KB
            else -> bytes.toDouble()
        }
        val text =
            if (unit == "GB" || unit == "TB") String.format(Locale.ROOT, "%.1f", v)
            else String.format(Locale.ROOT, "%.0f", v)
        return getString(R.string.two_argument_no_space, text, unit)
    }

    /**
     * Tints the Wi-Fi / mobile-data legend glyphs with the exact segment
     * colors the histogram draws, so the color key always matches the bars.
     */
    private fun applyTopAppsLegendColors() {
        if (view == null || !isAdded) return

        val histogram = topAppsPage.fhsTopAppsHistogram
        topAppsPage.fhsTopAppsSwatchWifi.imageTintList =
            ColorStateList.valueOf(histogram.unmeteredColor())
        topAppsPage.fhsTopAppsSwatchMobile.imageTintList =
            ColorStateList.valueOf(histogram.meteredColor())
    }

    /**
     * Pager adapter over pre-inflated page views. RecyclerView detaches and
     * re-attaches pages as the user swipes, so each bind re-parents the page
     * into the holder's container.
     */
    private inner class LogsPageViewHolder(val container: FrameLayout) :
        RecyclerView.ViewHolder(container)

    private inner class LogsPagesAdapter(private val pages: List<View>) :
        RecyclerView.Adapter<LogsPageViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogsPageViewHolder {
            val container = FrameLayout(parent.context)
            container.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            return LogsPageViewHolder(container)
        }

        override fun getItemCount(): Int = pages.size

        override fun onBindViewHolder(holder: LogsPageViewHolder, position: Int) {
            val page = pages[position]
            (page.parent as? ViewGroup)?.removeView(page)
            holder.container.addView(
                page,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
    }

    private inner class FirewallRulesPageViewHolder(
        val itemBinding: ItemHomeFirewallRulesPageBinding
    ) : RecyclerView.ViewHolder(itemBinding.root)

    /**
     * Pages for the swipeable rules card. Each page pairs a live count with a
     * human-readable rule-type label so the card explains itself; the count is
     * re-published by the rule-count observers via [updateCount].
     */
    private inner class FirewallRulesPagesAdapter(private val labels: List<String>) :
        RecyclerView.Adapter<FirewallRulesPageViewHolder>() {

        private val counts = mutableListOf("0", "0", "0")
        var onRulePageTapped: ((Int) -> Unit)? = null

        fun updateCount(page: Int, count: String) {
            if (counts[page] == count) return
            counts[page] = count
            notifyItemChanged(page)
        }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int
        ): FirewallRulesPageViewHolder {
            val itemBinding = ItemHomeFirewallRulesPageBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return FirewallRulesPageViewHolder(itemBinding)
        }

        override fun getItemCount(): Int = labels.size

        override fun onBindViewHolder(holder: FirewallRulesPageViewHolder, position: Int) {
            holder.itemBinding.itemRulesPageCount.text = counts[position]
            holder.itemBinding.itemRulesPageLabel.text =
                labels[position].replaceFirstChar { if (it.isLowerCase()) it.titlecase(getDefault()) else it.toString() }
            holder.itemBinding.root.setOnClickListener { onRulePageTapped?.invoke(position) }
        }
    }

    /**
     * Collects [LogActivityAggregator.activity] (a map of epoch-day to
     * [LogActivityState]); the fragment never queries the log databases for the
     * grid nor maintains any counters itself. It renders the trailing
     * 24-hour window split into 10-minute buckets.
     */
    private fun observeLogActivity() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                activityAggregator.activity.collect { state ->
                    lastActivityState = state
                    buildLogsHeatmap(state, displayMode)
                }
            }
        }
    }

    /**
     * Renders the blocked/allowed activity wall: 24 columns (one per hour
     * over the trailing 24 hours, oldest left, latest right) x 6 rows (one
     * per 10-minute bucket within each hour, :00 at top, :50 at bottom). The
        * newest bucket (now) is the bottom-right cell. Cell intensity is a
        * deterministic logarithmic level of the real aggregated count; a count
        * of zero renders a negligible placeholder dot (far smaller and fainter
        * than the lowest real level) while the cell itself stays reserved so
        * the grid geometry is unaffected. Tapping a cell opens the
        * detail sheet on that exact 10-minute interval.
     */
    private fun buildLogsHeatmap(
        state: LogActivityState,
        mode: ActivityDisplayMode = displayMode
    ) {
        val grid = logsPage.fhsLogsGrid
        grid.removeAllViews()

        val ctx = context ?: return
        val blockedMode = mode == ActivityDisplayMode.BLOCKED
        val base = UIUtils.fetchColor(ctx, R.attr.primaryLightColorText)
        val alphas =
            if (isLightTheme()) intArrayOf(0x40, 0x80, 0x80, 0xB3, 0xE6)
            else intArrayOf(0x24, 0x52, 0x52, 0x85, 0xCC)
        val gap = (2f * resources.displayMetrics.density).toInt()
        val gridHeightPx = HEATMAP_GRID_HEIGHT_DP * resources.displayMetrics.density
        val cellBaseHeight = (gridHeightPx - HEATMAP_GRID_ROWS * gap * 2f) / HEATMAP_GRID_ROWS

        // iterate hour-major so each visual column holds one hour: cells are
        // added bucket-by-bucket across the columns (GridLayout auto-places
        // children row-major), giving column = hour index (oldest left,
        // latest right) and row = 10-min bucket within the hour (:00 top,
        // :50 bottom); the newest bucket (now) lands in the bottom-right cell
        for (row in 0 until LogActivityAggregator.BUCKETS_PER_HOUR) {
            for (col in 0 until LogActivityAggregator.HOURS_IN_WINDOW) {
                val interval =
                    state.intervals[col * LogActivityAggregator.BUCKETS_PER_HOUR + row]
                val count = if (blockedMode) interval.blocked else interval.allowed
                val lvl = intensityLevel(count)
                // empty buckets render a negligible placeholder dot instead of
                // the lowest real level: far smaller and fainter, so "no
                // activity" reads as near-nothing without leaving the wall
                // looking gappy
                /*val frac =
                    if (lvl == 0) HEATMAP_EMPTY_CELL_FRACTION
                    else HEATMAP_CELL_SIZE_FRACTION[lvl]
                val cellAlpha = if (lvl == 0) HEATMAP_EMPTY_CELL_ALPHA else alphas[lvl]*/
                val frac = HEATMAP_CELL_SIZE_FRACTION[lvl]
                val cellAlpha = alphas[lvl]
                val cell = View(ctx)
                cell.background =
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(ColorUtils.setAlphaComponent(base, cellAlpha))
                    }
                cell.isClickable = false
                cell.isFocusable = false
                // alternative treatment: hide the circle entirely (keeps the
                // cell slot reserved, but leaves visible gaps in the wall)
                // if (lvl == 0) cell.visibility = View.GONE

                val lp =
                    GridLayout.LayoutParams().apply {
                        width = (cellBaseHeight * frac).toInt()
                        height = (cellBaseHeight * frac).toInt()
                        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                        rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                        setMargins(gap, gap, gap, gap)
                        setGravity(Gravity.CENTER)
                    }
                cell.layoutParams = lp
                grid.addView(cell)
            }
        }

        val swatches =
            listOf(
                logsPage.fhsLogsSwatch0,
                logsPage.fhsLogsSwatch1,
                logsPage.fhsLogsSwatch2,
                logsPage.fhsLogsSwatch3
            )
        val legendLevels = intArrayOf(0, 2, 3, 4)
        val legendAlphas = intArrayOf(alphas[0], alphas[2], alphas[3], alphas[4])
        val legendBaseH = 3f * resources.displayMetrics.density
        swatches.forEachIndexed { i, swatch ->
            val frac = HEATMAP_CELL_SIZE_FRACTION[legendLevels[i]]
            swatch.background =
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ColorUtils.setAlphaComponent(base, legendAlphas[i]))
                }
            swatch.layoutParams =
                LinearLayout.LayoutParams(
                    (legendBaseH * HEATMAP_CELL_OVAL_RATIO * frac).toInt(),
                    (legendBaseH * frac).toInt()
                ).apply { gravity = Gravity.CENTER_VERTICAL }
        }

        renderLogsHeaderCount(state)
    }

    private fun renderLogsHeaderCount(state: LogActivityState) {
        if (view == null || !isAdded) return
        if (!isVpnActivated) return

        var allowed = 0L
        var blocked = 0L
        for (interval in state.intervals) {
            allowed += interval.allowed
            blocked += interval.blocked
        }
        logsPage.fhsCardAllowedLogsCount.text = formatDecimal(allowed)
        logsPage.fhsCardAllowedLogsCount.isSelected = true
        logsPage.fhsCardBlockedLogsCount.text = formatDecimal(blocked)
        logsPage.fhsCardBlockedLogsCount.isSelected = true

        logsPage.fhsCardLogsDuration.visibility = View.VISIBLE
    }

    /**
     * Resolves the [LogActivityInterval] under the last tap on the activity
     * grid. Columns and rows are evenly weighted, so the hour is proportional
     * to the tap's x-position and the 10-minute bucket within that hour to
     * its y-position. Returns null when the grid has no data or was activated
     * without a touch (keyboard/accessibility), which falls back to the
     * latest window.
     */
    private fun tappedInterval(grid: View): LogActivityInterval? {
        val state = lastActivityState ?: return null
        if (state.intervals.size < LogActivityAggregator.TOTAL_SLOTS) return null
        if (grid.width <= 0 || grid.height <= 0) return null
        val col =
            ((lastGridTouchX / grid.width) * LogActivityAggregator.HOURS_IN_WINDOW).toInt()
                .coerceIn(0, LogActivityAggregator.HOURS_IN_WINDOW - 1)
        val row =
            ((lastGridTouchY / grid.height) * LogActivityAggregator.BUCKETS_PER_HOUR).toInt()
                .coerceIn(0, LogActivityAggregator.BUCKETS_PER_HOUR - 1)
        return state.intervals.getOrNull(
            col * LogActivityAggregator.BUCKETS_PER_HOUR + row
        )
    }

    /**
     * Opens the activity detail sheet without waiting on any aggregation or
     * loading state. When [grid] is set and the tap resolves to a cell, the
     * sheet opens on that cell's exact 10-minute window; otherwise it builds
     * its own default (latest) window and renders its data asynchronously.
     */
    private fun openIntervalDetails(grid: View?) {
        val interval = grid?.let { tappedInterval(it) }
        val sheet =
            if (interval != null) {
                LogActivityIntervalBottomSheet.newInstance(
                    interval.startTimestamp,
                    interval.startTimestamp + LogActivityAggregator.BUCKET_MS
                )
            } else {
                LogActivityIntervalBottomSheet.newInstance()
            }
        sheet.show(parentFragmentManager, LogActivityIntervalBottomSheet.TAG)
    }

    // logarithmic scale so skewed traffic distributions stay visually
    // distinguishable (1-9 -> 1, 10-99 -> 2, 100-999 -> 3, >=1000 -> 4);
    // zero always maps to the empty/default cell
    private fun intensityLevel(count: Long): Int {
        if (count <= 0L) return 0
        return minOf(alphasMaxIndex(), log10(count.toDouble()).toInt() + 1)
    }

    private fun alphasMaxIndex(): Int = HEATMAP_INTENSITY_LEVELS - 1

    private fun disableProxyCard() {
        proxyStateListenerJob?.cancel()
        showProxyInactive()
    }

    private fun disableFirewallCard() {
        if (view == null || !isAdded) return

        updateRulesPageCounts(RULES_PAGE_UNIVERSAL, 0)
        updateRulesPageCounts(RULES_PAGE_IP, 0)
        updateRulesPageCounts(RULES_PAGE_DOMAIN, 0)
        b.fhsFirewallBadgesRow.alpha = INACTIVE_ELEMENT_ALPHA
    }

    private fun disabledDnsCard() {
        if (view == null || !isAdded) return

        // subtle, low-emphasis hint instead of the oversized legacy label
        b.fhsCardDnsConnectedDns.text = getString(R.string.hsf_dns_mode_off_indicator)
        b.fhsCardDnsConnectedDns.alpha = 0.75f
        b.fhsCardDnsLatency.text = getString(R.string.lbl_disabled).lowercase()
        b.fhsCardDnsLatency.isSelected = true
    }

    private fun disableAppsCard() {
        if (view == null || !isAdded) return

        b.fhsCardAllowedApps.text = getString(R.string.hsf_firewall_mode_off_indicator)
        b.fhsCardAllowedApps.applyLowEmphasis(appsHeadlineSizePx)
        b.fhsCardAppsAllApps.text = ""
        b.fhsAppsLabel.visibility = View.GONE
    }

    /**
     * The observers are for the DNS cards, when the mode is set to DNS/DNS+Firewall. The observers
     * are register to update the UI in the home screen
     */
    private fun observeDnsStates() {
        // The latency check is a one-shot IO call that refreshes the displayed latency every time
        // this function is called (e.g. on each brave-mode observer or onResume() cycle).
        io {
            var dnsId = if (WireguardManager.oneWireGuardEnabled()) {
                val id = WireguardManager.getOneWireGuardProxyId()
                if (id == null) {
                    if (appConfig.isSmartDnsEnabled()) {
                        Backend.Plus
                    } else {
                        Backend.Preferred
                    }
                } else {
                    "${ProxyManager.ID_WG_BASE}${id}"
                }
            } else {
                if (appConfig.isSmartDnsEnabled()) {
                    Backend.Plus
                } else {
                    Backend.Preferred
                }
            }
            if (persistentState.enableDnsCache) {
                dnsId = Backend.CT + dnsId
            }
            val p50 = VpnController.p50(dnsId)
            uiCtx {
                lastDnsP50 = p50
                renderDnsHeadline()
                b.fhsCardDnsLatency.isSelected = true
                startDnsStatePolling()
            }
        }

        // Guard: only register the LiveData observers once per view-lifecycle.  Without this,
        // every call to updateCardsUi() → enableDnsCardIfNeeded() → observeDnsStates() adds a
        // NEW lambda observer to connectedDns, causing duplicate updateUiWithDnsStates() calls and
        // accumulating IO coroutines. The latency block above still runs on every call so the
        // displayed latency is always fresh. Reset in unobserveDnsStates() / onDestroyView().
        if (dnsObserverActive) {
            Logger.v(LOG_TAG_UI, "$TAG dns observer already registered")
            return
        }
        dnsObserverActive = true

        VpnController.getRegionLiveData().distinctUntilChanged().observe(viewLifecycleOwner) {
            Logger.vv(LOG_TAG_UI, "$TAG region changed to $it")
            if (isAdded && view != null) {
                renderDnsHeadline()
            }
        }
    }

    /**
     * Renders the DNS card's second line as "<status> · <region>(<p50>)"
     * (e.g. "Connected · BLR(45 ms)"). Latency is formatted via
     * UIUtils.formatLatency(): "45 ms" below one second, "1.5 s" / "15 s"
     * above it. Falls back to the latency alone when the resolver region is
     * unknown and shows the region alone until a latency sample is available.
     * When no sample exists at all, shows the status (or "Inactive").
     */
    private fun renderDnsHeadline(dnsStatus: Int? = null) {
        if (view == null || !isAdded) return
        if (dnsStatus != null) lastDnsStatus = dnsStatus

        val status =
            lastDnsStatus?.let {
                getString(UIUtils.getDnsStatusStringRes(it)).lowercase().capitalizeWords()
            }
        val region = VpnController.getRegionLiveData().value
        val p50 = lastDnsP50

        val latency =
            when {
                p50 != null && p50 >= 0L && !region.isNullOrEmpty() ->
                    getString(R.string.two_argument_parenthesis, region, UIUtils.formatLatency(p50))
                p50 != null && p50 >= 0L ->
                    UIUtils.formatLatency(p50)
                !region.isNullOrEmpty() -> region
                else -> null
            }

        val parts = listOfNotNull(status, latency)
        b.fhsCardDnsLatency.text =
            if (parts.isEmpty()) b.fhsCardDnsLatency.context.getString(R.string.lbl_inactive)
            else parts.joinToString(" · ")
        b.fhsCardDnsLatency.isSelected = true
    }

    private fun updateUiWithDnsStates(dnsStatus: Int? = null) {
        // Check if view is available before accessing binding
        if (view == null || !isAdded) return

        // show the resolver name alongside its connection status
        val dnsName = appConfig.getConnectedDnsObservable().value
        b.fhsCardDnsConnectedDns.alpha = 1f
        b.fhsCardDnsConnectedDns.text = dnsName
        b.fhsCardDnsConnectedDns.isSelected = true

        val generation = ++dnsBlocklistGeneration

        // for RethinkDNS Plus, append the number of blocklists in-use,
        // rendered smaller and lighter than the resolver name, mirroring
        // RethinkEndpointAdapter.updateDnsStatus()
        if (appConfig.isRethinkDnsConnected()) {
            io {
                val count = appConfig.getRemoteRethinkEndpoint()?.blocklistCount ?: 0
                if (count > 0) {
                    uiCtx {
                        if (generation != dnsBlocklistGeneration) return@uiCtx
                        val countLabel =
                            getString(R.string.blocklist_count_home_screen, count.toString())
                        b.fhsCardDnsConnectedDns.text =
                            withBlocklistCountSuffix(dnsName, countLabel)
                    }
                }
            }
        }

        renderDnsHeadline(dnsStatus)
    }

    /**
     * Appends [suffix] to [text] with a " · " separator, styling the suffix smaller and
     * lighter than the main text so the resolver name stays prominent.
     */
    private fun withBlocklistCountSuffix(text: String?, suffix: String): CharSequence {
        if (text.isNullOrEmpty()) return suffix
        val separator = " · "
        val sb = SpannableStringBuilder(text).append(separator).append(suffix)
        val start = text.length + separator.length
        val base =
            UIUtils.fetchColor(b.fhsCardDnsConnectedDns.context, R.attr.primaryLightColorText)
        val fadedColor = ColorUtils.setAlphaComponent(base, BLOCKLIST_COUNT_SUFFIX_ALPHA)
        sb.setSpan(
            RelativeSizeSpan(BLOCKLIST_COUNT_SUFFIX_SCALE),
            start,
            sb.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        sb.setSpan(
            ForegroundColorSpan(fadedColor),
            start,
            sb.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return sb
    }

    /**
     * Prepares the logs card header for rendering. The cumulative
     * allowed/blocked counts are derived from the [LogActivityAggregator]
     * state (the same source as the heatmap) inside [buildLogsHeatmap];
     * nothing is observed from the log databases here.
     */
    private fun observeLogsCount() {
        logsPage.fhsCardAllowedLogsLabel.text = getString(R.string.lbl_allowed)
        logsPage.fhsCardAllowedLogsLabel.visibility = View.VISIBLE
        logsPage.fhsCardAllowedLogsCount.visibility = View.VISIBLE
        logsPage.fhsCardBlockedLogsCount.visibility = View.VISIBLE
        logsPage.fhsCardBlockedLogsLabel.visibility = View.VISIBLE
        logsPage.fhsCardLogsDuration.visibility = View.VISIBLE
        logsPage.fhsLogsGrid.visibility = View.VISIBLE
        logsPage.fhsLogsLegend.visibility = View.VISIBLE
        logsPage.fhsLogsToggleGroup.visibility = View.VISIBLE
        // expand the pager back; the transition grows the card smoothly so
        // re-enabling never snaps the surrounding cards into place
        animateLogsCardTransition()
        logsCardActive = true
        b.fhsLogsPager.visibility = View.VISIBLE
        updateLogsDotsVisibility()
        b.fhsLogsDisabledRow.visibility = View.GONE
        b.fhsLogsEnableChip.visibility = View.GONE

        // render immediately from the cached aggregate so the header counts
        // are not blank until the next aggregator emission
        lastActivityState?.let { buildLogsHeatmap(it, displayMode) }
    }

    /**
     * Wraps the card's enable/disable visibility changes in a transition so
     * the collapse/expand of the pager animates instead of snapping the card
     * and everything below it into a new size.
     */
    private fun animateLogsCardTransition() {
        (b.fhsCardLogsLl.parent as? ViewGroup)?.let { parent ->
            TransitionManager.beginDelayedTransition(parent)
        }
    }

    private fun formatDecimal(i: Long?): String {
        return if (isAtleastN()) {
            CompactDecimalFormat.getInstance(Locale.US, CompactDecimalFormat.CompactStyle.SHORT)
                .format(i)
        } else {
            i.toString()
        }
    }

    // unregister all dns related observers
    private fun unobserveDnsStates() {
        dnsObserverActive = false
        dnsStateListenerJob?.cancel()
        lastDnsP50 = null
        lastDnsStatus = null
        appConfig.getConnectedDnsObservable().removeObservers(viewLifecycleOwner)
        VpnController.getRegionLiveData().removeObservers(viewLifecycleOwner)
    }

    private fun observeUniversalStates() {
        persistentState.universalRulesCount.observe(viewLifecycleOwner) {
            updateTotalRuleCount()
        }
    }

    private fun observeCustomRulesCount() {
        // observer for ips count
        IpRulesManager.getCustomIpsLiveData().observe(viewLifecycleOwner) {
            updateTotalRuleCount()
        }

        DomainRulesManager.getUniversalCustomDomainCount().observe(viewLifecycleOwner) {
            updateTotalRuleCount()
        }
    }

    private fun updateTotalRuleCount() {
        val univ = persistentState.getUniversalRulesCount()
        val ips = IpRulesManager.getCustomIpsLiveData().value ?: 0
        val doms = DomainRulesManager.getUniversalCustomDomainCount().value ?: 0
        updateRulesPageCounts(RULES_PAGE_UNIVERSAL, univ)
        updateRulesPageCounts(RULES_PAGE_IP, ips)
        updateRulesPageCounts(RULES_PAGE_DOMAIN, doms)
    }

    /**
     * Wires the swipeable rules card: pages carry the per-type rule counts as
     * plain text ("24 · universal IP rules") so a new user can tell what the
     * numbers mean at a glance, and a tap on the visible page opens the
     * matching rules screen. Swiping moves between the three rule types; the
     * dots below the pager mirror the selected page.
     */
    private fun setupFirewallRulesCard() {
        rulesPagesAdapter.onRulePageTapped = { position -> openRulesScreen(position) }
        b.fhsFirewallRulesPager.adapter = rulesPagesAdapter
        b.fhsFirewallRulesPager.getChildAt(0).overScrollMode = View.OVER_SCROLL_NEVER
        rulesPagerCallback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateRulesDots(position)
            }
        }
        b.fhsFirewallRulesPager.registerOnPageChangeCallback(rulesPagerCallback!!)
        updateRulesDots(b.fhsFirewallRulesPager.currentItem)
    }

    private fun updateRulesPageCounts(page: Int, count: Int) {
        rulesPagesAdapter.updateCount(page, compactRuleCount(count))
    }

    /** Dims the inactive dots; the selected dot stays fully opaque. */
    private fun updateRulesDots(position: Int) {
        if (view == null || !isAdded) return

        listOf(b.fhsRulesDot0, b.fhsRulesDot1, b.fhsRulesDot2).forEachIndexed { index, dot ->
            dot.alpha = if (index == position) 1f else DOT_UNSELECTED_ALPHA
        }
    }

    /** Opens the rules screen that matches the page the user tapped. */
    private fun openRulesScreen(position: Int) {
        when (position) {
            RULES_PAGE_UNIVERSAL -> {
                Logger.v(LOG_TAG_UI, "$TAG: tap on universal firewall rules page")
                startActivity(Intent(requireContext(), UniversalFirewallSettingsActivity::class.java))
                logEvent(
                    EventType.UI_NAVIGATION,
                    "HomeScreen: Universal firewall page tapped",
                    "Navigating to UniversalFirewallSettingsActivity from HomeScreenFragment"
                )
            }
            RULES_PAGE_IP -> {
                Logger.v(LOG_TAG_UI, "$TAG: tap on universal IP rules page")
                val intent = Intent(requireContext(), CustomRulesActivity::class.java)
                intent.putExtra(Constants.VIEW_PAGER_SCREEN_TO_LOAD, CustomRulesActivity.Tabs.IP_RULES.screen)
                intent.putExtra(CustomRulesActivity.INTENT_RULES, CustomRulesActivity.RULES.APP_SPECIFIC_RULES.type)
                intent.putExtra(Constants.INTENT_UID, Constants.UID_EVERYBODY)
                startActivity(intent)
                logEvent(
                    EventType.UI_NAVIGATION,
                    "HomeScreen: IP rules page tapped",
                    "Navigating to CustomRulesActivity (IP tab) from HomeScreenFragment"
                )
            }
            RULES_PAGE_DOMAIN -> {
                Logger.v(LOG_TAG_UI, "$TAG: tap on universal domain rules page")
                val intent = Intent(requireContext(), CustomRulesActivity::class.java)
                intent.putExtra(Constants.VIEW_PAGER_SCREEN_TO_LOAD, CustomRulesActivity.Tabs.DOMAIN_RULES.screen)
                intent.putExtra(CustomRulesActivity.INTENT_RULES, CustomRulesActivity.RULES.APP_SPECIFIC_RULES.type)
                intent.putExtra(Constants.INTENT_UID, Constants.UID_EVERYBODY)
                startActivity(intent)
                logEvent(
                    EventType.UI_NAVIGATION,
                    "HomeScreen: Domain rules page tapped",
                    "Navigating to CustomRulesActivity (domain tab) from HomeScreenFragment"
                )
            }
        }
    }

    private fun compactRuleCount(count: Int): String {
        if (count <= 0) return "0"
        if (count < 1000) return count.toString()
        val units = charArrayOf('K', 'M', 'B')
        var value = count
        var unit = -1
        while (value >= 1000 && unit < units.lastIndex) {
            value /= 1000
            unit++
        }
        var label = "$value${units[unit]}"
        if (label.length > MAX_RULE_BADGE_CHARS) {
            label = "${value / 10}${units[unit]}"
        }
        return label
    }

    private fun unObserveCustomRulesCount() {
        DomainRulesManager.getUniversalCustomDomainCount().removeObservers(viewLifecycleOwner)
        IpRulesManager.getCustomIpsLiveData().removeObservers(viewLifecycleOwner)
    }

    // remove firewall card related observers
    private fun unobserveUniversalStates() {
        persistentState.universalRulesCount.removeObservers(viewLifecycleOwner)
    }

    /**
     * The observers for the firewall card in the home screen, will be calling this method when the
     * VPN is active and the mode is set to either Firewall or DNS+Firewall.
     */
    private fun observeAppStates() {
        FirewallManager.getApplistObserver().observe(viewLifecycleOwner) {
            io {
                try {
                    val copy: Collection<AppInfo>
                    appRulesMutex.withLock {
                        copy = mutableListOf<AppInfo>().apply { addAll(it) }.toList()
                    }
                    val blockedCount =
                        copy.count { a ->
                            a.connectionStatus != FirewallManager.ConnectionStatus.ALLOW.id
                        }
                    val bypassCount =
                        copy.count { a ->
                            a.firewallStatus == FirewallManager.FirewallStatus.BYPASS_UNIVERSAL.id ||
                                    a.firewallStatus ==
                                    FirewallManager.FirewallStatus.BYPASS_DNS_FIREWALL.id
                        }
                    val excludedCount =
                        copy.count { a ->
                            a.firewallStatus == FirewallManager.FirewallStatus.EXCLUDE.id
                        }
                    val isolatedCount =
                        copy.count { a ->
                            a.firewallStatus == FirewallManager.FirewallStatus.ISOLATE.id
                        }
                    val allApps = copy.count()
                    val allowedApps =
                        allApps - (blockedCount + bypassCount + excludedCount + isolatedCount)
                    uiCtx {
                        b.fhsCardAllowedApps.restoreFullEmphasis(appsHeadlineSizePx)
                        b.fhsCardAllowedApps.text = allowedApps.toString()
                        b.fhsCardAllowedApps.isSelected = true
                        b.fhsAppsLabel.visibility = View.VISIBLE
                        b.fhsCardAppsAllApps.text = getString(R.string.two_argument_space, getString(R.string.symbol_slash), allApps.toString())
                        b.fhsCardAppsBlockedCount.text = getString(R.string.two_argument_space, blockedCount.toString(), getString(R.string.lbl_blocked).lowercase())
                        b.fhsCardAppsIsolatedCount.text = getString(R.string.two_argument_space, isolatedCount.toString(), getString(R.string.fapps_firewall_filter_isolate).lowercase())
                        b.fhsCardAppsBypassedCount.text = getString(R.string.two_argument_space, bypassCount.toString(), getString(R.string.fapps_firewall_filter_bypass_universal).lowercase())
                        b.fhsCardAppsExcludedCount.text = getString(R.string.two_argument_space, excludedCount.toString(), getString(R.string.fapps_firewall_filter_excluded).lowercase())
                    }
                } catch (e: Exception) { // NoSuchElementException, ConcurrentModification
                    Logger.e(
                        LOG_TAG_VPN,
                        "error retrieving value from appInfos observer ${e.message}",
                        e
                    )
                }
            }
        }
    }

    // unregister all firewall related observers
    private fun unobserveAppStates() {
        FirewallManager.getApplistObserver().removeObservers(viewLifecycleOwner)
    }

    private fun handleMainScreenBtnClickEvent() {
        b.fhsDnsOnOffBtn.isEnabled = false
        delay(TimeUnit.MILLISECONDS.toMillis(UI_DELAY_MS), lifecycleScope) {
            if (isAdded) {
                b.fhsDnsOnOffBtn.isEnabled = true
            }
        }

        // prompt user to disable battery optimization and restrict background data
        // disabled the battery optimization check as it's confusing for users
        if (isRestrictBackgroundActive(requireContext()) && batteryOptimizationActive(requireContext()) && !isVpnActivated) {
            showBatteryOptimizationDialog()
        }

        handleVpnActivation()
    }

    private fun handleVpnActivation() {
        if (handleAlwaysOnVpn()) return

        if (isVpnActivated) {
            stopVpnService()
        } else {
            prepareAndStartVpn()
        }
    }

    private fun batteryOptimizationActive(context: Context): Boolean {
        // check whether or not Battery Permission is Available for Device
        val bph = batteryPermissionHelper.isBatterySaverPermissionAvailable(context = context, onlyIfSupported = true)
        Logger.d(LOG_TAG_UI, "battery optimization available: $bph")
        return bph
    }

    private fun showBatteryOptimizationDialog() {
        if (!isAtleastN()) return

        val builder = MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
        val title =
            getString(
                R.string.battery_optimization_dialog_heading,
                getString(R.string.lbl_battery_optimization)
            )
        val msg =
            getString(
                R.string.restrict_dialog_message,
                getString(R.string.lbl_battery_optimization)
            )
        builder.setTitle(title)
        builder.setMessage(msg)
        builder.setCancelable(false)
        builder.setPositiveButton(R.string.lbl_proceed) { _, _ ->
            Logger.v(LOG_TAG_UI, "launch battery optimization settings")
            batteryPermissionHelper.getPermission(requireContext(), open = true, newTask = true)
        }

        builder.setNegativeButton(R.string.lbl_dismiss) { _, _ ->
            // no-op
        }
        val dialog = builder.create()
        dialog.show()
        UIUtils.capDialogWidth(dialog)
    }

    private fun isRestrictBackgroundActive(context: Context): Boolean {
        if (!isAtleastN()) return false

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val isBackgroundRestricted = cm.restrictBackgroundStatus
        Logger.d(LOG_TAG_UI, "restrict background status: $isBackgroundRestricted")

        return if (isAtleastP()) {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            Logger.d(LOG_TAG_UI, "above P, background restricted: ${am.isBackgroundRestricted}")
            am.isBackgroundRestricted ||
                    isBackgroundRestricted == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        } else {
            isBackgroundRestricted == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        }
    }

    private fun showAlwaysOnStopDialog() {
        val builder = MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)

        builder.setTitle(R.string.always_on_dialog_stop_heading)
        if (VpnController.isVpnLockdown()) {
            builder.setMessage(
                htmlToSpannedText(getString(R.string.always_on_dialog_lockdown_stop_message))
            )
        } else {
            builder.setMessage(R.string.always_on_dialog_stop_message)
        }

        builder.setCancelable(false)
        builder.setPositiveButton(R.string.always_on_dialog_positive) { _, _ -> stopVpnService() }

        builder.setNegativeButton(R.string.lbl_cancel) { _, _ ->
            // no-op
        }

        builder.setNeutralButton(R.string.always_on_dialog_neutral) { _, _ ->
            openVpnProfile(requireContext())
        }

        val dialog = builder.create()
        dialog.show()
        UIUtils.capDialogWidth(dialog)
    }

    private fun openBottomSheet() {
        val bottomSheetFragment = HomeScreenSettingBottomSheet()
        bottomSheetFragment.show(parentFragmentManager, bottomSheetFragment.tag)
    }

    private fun handleAlwaysOnVpn(): Boolean {
        if (isOtherVpnHasAlwaysOn(requireContext())) {
            showAlwaysOnDisableDialog()
            return true
        }

        // if always-on is enabled and vpn is activated, show the dialog to stop the vpn #799
        if (VpnController.isAlwaysOn(requireContext()) && isVpnActivated) {
            showAlwaysOnStopDialog()
            return true
        }

        return false
    }

    private fun showAlwaysOnDisableDialog() {
        val builder = MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
        builder.setTitle(R.string.always_on_dialog_heading)
        builder.setMessage(R.string.always_on_dialog)
        builder.setCancelable(false)
        builder.setPositiveButton(R.string.always_on_dialog_positive_btn) { _, _ ->
            openVpnProfile(requireContext())
        }

        builder.setNegativeButton(R.string.lbl_cancel) { _, _ ->
            // no-op
        }

        val dialog = builder.create()
        dialog.show()
        UIUtils.capDialogWidth(dialog)
    }

    override fun onResume() {
        super.onResume()
        isVpnActivated = VpnController.state().activationRequested
        updateMainButtonUi()
        handleShimmer()
        maybeAutoStartVpn()
        updateCardsUi()
        syncDnsStatus()
        handleLockdownModeIfNeeded()
        startTrafficStats()
        //maybeShowGracePeriodDialog()
        handleRethinkAppStatus()
    }

    private fun handleRethinkAppStatus() {
        Logger.vv(LOG_TAG_UI, "handleRethinkAppStatus")
        io {
            if (isVpnActivated && shouldShowRethinkWarning()) {
                canRethinkBlockItself = true
                Logger.d(LOG_TAG_UI, "canRethinkBlockItself = true, showing warning")
                uiCtx {
                    val color = ColorUtils.setAlphaComponent(
                        ContextCompat.getColor(requireContext(), R.color.accentBad),
                        128 // 0-255 (128 = 50% opacity)
                    )
                    b.fhsCardAppsCv.strokeColor = color
                    b.fhsCardAppsCv.strokeWidth = (2f * requireContext().resources.displayMetrics.density).toInt()
                    //b.fhsCardAppsRethinkWarningTv?.visibility = View.VISIBLE
                    //b.fhsCardAppsRethinkWarningTv?.setTextColor(color)
                }
            } else {
                canRethinkBlockItself = false
                Logger.d(LOG_TAG_UI, "$TAG canRethinkBlockItself = false, hiding warning")
                uiCtx {
                    // cards are borderless; fully drop the warning stroke
                    b.fhsCardAppsCv.strokeWidth = 0
                    //b.fhsCardAppsRethinkWarningTv?.visibility = View.GONE
                }
            }
        }
    }

    @Volatile
    private var trafficStatsTicker: Job? = null

    private fun startTrafficStats() {
        // onResume() restarts this ticker; cancel the previous job first so
        // multiple tickers never stack up
        stopTrafficStats()
        trafficStatsTicker =
            ui("trafficStatsTicker") {
                var counter = 0
                while (true) {
                    // make it as 3 options and add the protos
                    if (!isAdded) return@ui

                    if (counter % TRAFFIC_DISPLAY_CYCLE_MODULO == TRAFFIC_DISPLAY_STATS_RATE) {
                        displayTrafficStatsRate()
                    } else if (counter % TRAFFIC_DISPLAY_CYCLE_MODULO == TRAFFIC_DISPLAY_BANDWIDTH) {
                        displayTrafficStatsBW()
                    } else {
                        displayProtos()
                    }
                    // show protos
                    kotlinx.coroutines.delay(TRAFFIC_DISPLAY_DELAY_MS.milliseconds)
                    counter++
                }
            }
    }

    private fun displayProtos() {
        b.fhsInternetSpeed.visibility = View.VISIBLE
        b.fhsInternetSpeedUnit.visibility = View.VISIBLE
        b.fhsInternetSpeed.text = VpnController.protocols()
        b.fhsInternetSpeedUnit.text = getString(R.string.lbl_protos)
        // refresh the active-since label on the protection bar as well
        updateActiveSinceUi()
    }

    /**
     * Shows how long the VPN has been up on the protection bar using the same
     * relative-time presentation as HomeScreenSettingBottomSheet.updateUptime().
     * The label is hidden when the VPN is not running.
     */
    private fun updateActiveSinceUi() {
        val uptimeMs = VpnController.uptimeMs()
        if (!isVpnActivated || uptimeMs < INIT_TIME_MS) {
            b.fhsActiveSinceTxt.visibility = View.GONE
            return
        }

        val now = System.currentTimeMillis()
        // returns a string describing 'time' as a time relative to 'now'
        val t =
            DateUtils.getRelativeTimeSpanString(
                now - uptimeMs,
                now,
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            )
        b.fhsActiveSinceTxt.visibility = View.VISIBLE
        b.fhsActiveSinceTxt.text = t
    }

    private fun displayTrafficStatsBW() {
        val txRx = convertToCommonUnit(txRx.tx, txRx.rx)

        b.fhsInternetSpeed.visibility = View.VISIBLE
        b.fhsInternetSpeedUnit.visibility = View.VISIBLE
        b.fhsInternetSpeed.text =
            getString(
                R.string.two_argument_space,
                getString(
                    R.string.two_argument_space,
                    txRx.first,
                    getString(R.string.symbol_black_up)
                ),
                getString(
                    R.string.two_argument_space,
                    txRx.second,
                    getString(R.string.symbol_black_down)
                )
            )
        b.fhsInternetSpeedUnit.text = getCommonUnit(this.txRx.tx, this.txRx.rx)
    }

    private fun stopTrafficStats() {
        // null before the first startTrafficStats(); ?. avoids
        // UninitializedPropertyAccessException on the initial onResume()
        trafficStatsTicker?.cancel()
        trafficStatsTicker = null
    }

    data class TxRx(
        val tx: Long = TrafficStats.getTotalTxBytes(),
        val rx: Long = TrafficStats.getTotalRxBytes(),
        val time: Long = SystemClock.elapsedRealtime()
    )

    private var txRx = TxRx()

    private fun displayTrafficStatsRate() {
        val curr = TxRx()
        val dur = (curr.time - txRx.time) / 1000L
        if (txRx.time <= 0L || dur <= 0) {
            // no measurable window yet (first tick after start, where the
            // baseline was seeded moments ago): advance the baseline and show
            // the cumulative counters immediately instead of hiding the row
            // until the next cycle
            txRx = curr
            displayTrafficStatsBW()
            return
        }
        val tx = curr.tx - txRx.tx
        val rx = curr.rx - txRx.rx
        txRx = curr
        val txRx = convertToCommonUnit(tx/dur, rx/dur)
        b.fhsInternetSpeed.visibility = View.VISIBLE
        b.fhsInternetSpeedUnit.visibility = View.VISIBLE
        b.fhsInternetSpeed.text =
            getString(
                R.string.two_argument_space,
                getString(
                    R.string.two_argument_space,
                    txRx.first,
                    getString(R.string.symbol_black_up)
                ),
                getString(
                    R.string.two_argument_space,
                    txRx.second,
                    getString(R.string.symbol_black_down)
                )
            )
        b.fhsInternetSpeedUnit.text = getString(R.string.symbol_ps, getCommonUnit(tx/dur, rx/dur))
    }

    // TODO: Move this to a common utility class
    private fun getCommonUnit(bytes1: Long, bytes2: Long): String {
        val maxBytes = maxOf(bytes1, bytes2)
        return when {
            maxBytes >= TB_THRESHOLD -> "TB"
            maxBytes >= GB_THRESHOLD -> "GB"
            maxBytes >= MB_THRESHOLD -> "MB"
            maxBytes >= KB_THRESHOLD -> "KB"
            else -> "B"
        }
    }

    private fun convertToCommonUnit(bytes1: Long, bytes2: Long): Pair<String, String> {
        val unit = getCommonUnit(bytes1, bytes2)
        val v = when (unit) {
            "TB" -> Pair(bytesToTB(bytes1), bytesToTB(bytes2))
            "GB" -> Pair(bytesToGB(bytes1), bytesToGB(bytes2))
            "MB" -> Pair(bytesToMB(bytes1), bytesToMB(bytes2))
            "KB" -> Pair(bytesToKB(bytes1), bytesToKB(bytes2))
            else -> Pair(bytes1.toDouble(), bytes2.toDouble())
        }
        return Pair(String.format(Locale.ROOT, "%.2f", v.first), String.format(Locale.ROOT, "%.2f", v.second))
    }

    private fun bytesToKB(bytes: Long): Double = bytes / BYTES_PER_KB
    private fun bytesToMB(bytes: Long): Double = bytes / BYTES_PER_MB
    private fun bytesToGB(bytes: Long): Double = bytes / BYTES_PER_GB
    private fun bytesToTB(bytes: Long): Double = bytes / BYTES_PER_TB

    /**
     * Issue fix - https://github.com/celzero/rethink-app/issues/57 When the application
     * crashes/updates it goes into red waiting state. This causes confusion to the users also
     * requires click of START button twice to start the app. FIX : The check for the controller
     * state. If persistence state has vpn enabled and the VPN is not connected then the start will
     * be initiated.
     */
    @Suppress("DEPRECATION")
    private fun maybeAutoStartVpn() {
        if (isVpnActivated && !VpnController.isOn()) {
            // On API 37+, the local network permission is mandatory. Do not auto-start
            // (which is invoked from onResume and would otherwise re-prompt for the
            // permission on every resume, causing a dialog loop) when the permission is
            // missing. The user must explicitly start the VPN from the home screen button,
            // which requests the permission exactly once.
            if (isAtleast37() && !hasLocalNetworkPermission()) {
                Logger.i(LOG_TAG_VPN, "skip auto-start: local network permission missing")
                // Reset the VPN controller and persisted activation state so the
                // home-screen button reflects that the VPN is off. Without this,
                // isVpnActivated stays true (the persisted flag was set before the
                // VPN died) and the button shows STOP — but VpnController.isOn()
                // is false, so the first user tap tries to stop an already-stopped
                // VPN and the button never flips to START.
                VpnController.stop("auto-start-permission-missing", requireContext())
                persistentState.setVpnEnabled(false)
                return
            }
            // this case will happen when the app is updated or crashed
            // generate the bug report and start the vpn
            triggerBugReport()
            Logger.i(LOG_TAG_VPN, "start VPN (previous state)")
            prepareAndStartVpn()
        }
    }

    private fun triggerBugReport() {
        if (WorkScheduler.isWorkRunning(requireContext(), WorkScheduler.APP_EXIT_INFO_JOB_TAG)) {
            Logger.v(LOG_TAG_VPN, "bug report already triggered")
            return
        }

        Logger.v(LOG_TAG_VPN, "trigger bug report")
        workScheduler.scheduleOneTimeWorkForAppExitInfo()
    }

    // set the app mode to dns+firewall mode when vpn in lockdown state
    private fun handleLockdownModeIfNeeded() {
        if (VpnController.isVpnLockdown() && !appConfig.getBraveMode().isDnsFirewallMode()) {
            io { appConfig.changeBraveMode(AppConfig.BraveMode.DNS_FIREWALL.mode) }
        }
    }

    private fun handleShimmer() {
        if (!isVpnActivated) {
            startShimmer()
            return
        }

        if (VpnController.hasTunnel()) {
            stopShimmer()
        }
    }

    override fun onPause() {
        super.onPause()
        stopShimmer()
        stopTrafficStats()
        proxyStateListenerJob?.cancel()
        dnsStateListenerJob?.cancel()
    }

    override fun onDestroyView() {
        // Cancel any running jobs before the view is destroyed
        proxyStateListenerJob?.cancel()
        proxyStateListenerJob = null
        proxyStatusLiveData = null
        dnsObserverActive = false
        stopBorderAnimation()
        // ViewPager2 keeps a global reference to its callback; unregistering
        // here prevents it from outliving the (recreated) view
        logsPagerCallback?.let { b.fhsLogsPager.unregisterOnPageChangeCallback(it) }
        logsPagerCallback = null
        topAppsHeaderRevertJob?.cancel()
        topAppsHeaderRevertJob = null
        rulesPagerCallback?.let { b.fhsFirewallRulesPager.unregisterOnPageChangeCallback(it) }
        rulesPagerCallback = null
        super.onDestroyView()
    }

    private fun startDnsActivity(screenToLoad: Int) {
        if (isPrivateDnsActive(requireContext())) {
            showPrivateDnsDialog()
            return
        }

        if (canStartRethinkActivity()) {
            // no need to pass value in intent, as default load to Rethink remote
            startActivity(ScreenType.RETHINK, screenToLoad)
            return
        }

        startActivity(ScreenType.DNS, screenToLoad)
        return
    }

    private fun canStartRethinkActivity(): Boolean {
        val dns = appConfig.getDnsType()
        return dns.isRethinkRemote() && !WireguardManager.oneWireGuardEnabled()
    }

    private fun showPrivateDnsDialog() {
        val builder = MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
        builder.setTitle(R.string.private_dns_dialog_heading)
        builder.setMessage(R.string.private_dns_dialog_desc)
        builder.setCancelable(false)
        builder.setPositiveButton(R.string.private_dns_dialog_positive) { _, _ ->
            openNetworkSettings(requireContext(), Settings.ACTION_WIRELESS_SETTINGS)
        }

        builder.setNegativeButton(R.string.lbl_dismiss) { _, _ ->
            // no-op
        }
        val dialog = builder.create()
        dialog.show()
        UIUtils.capDialogWidth(dialog)
    }

    private fun startAppsActivity() {
        Logger.d(LOG_TAG_VPN, "Status : $isVpnActivated , BraveMode: ${appConfig.getBraveMode()}")

        // no need to check for app modes to open this activity
        // one use case: https://github.com/celzero/rethink-app/issues/611
        val intent = Intent(requireContext(), AppListActivity::class.java)
        startActivity(intent)
    }

    private fun startActivity(type: ScreenType, screenToLoad: Int = 0) {
        val intent =
            when (type) {
                ScreenType.DNS -> Intent(requireContext(), DnsDetailActivity::class.java)
                ScreenType.FIREWALL -> Intent(requireContext(), FirewallActivity::class.java)
                ScreenType.LOGS -> Intent(requireContext(), NetworkLogsActivity::class.java)
                ScreenType.RULES -> Intent(requireContext(), CustomRulesActivity::class.java)
                ScreenType.PROXY -> Intent(requireContext(), ProxySettingsActivity::class.java)
                ScreenType.ALERTS -> Intent(requireContext(), AlertsActivity::class.java)
                ScreenType.RETHINK ->
                    Intent(requireContext(), ConfigureRethinkBasicActivity::class.java)

                ScreenType.PROXY_WIREGUARD -> Intent(requireContext(), WgMainActivity::class.java)
            }
        if (type == ScreenType.RETHINK) {
            io {
                val endpoint = appConfig.getRemoteRethinkEndpoint()
                val url = endpoint?.url
                val name = endpoint?.name
                intent.putExtra(RETHINK_BLOCKLIST_NAME, name)
                intent.putExtra(RETHINK_BLOCKLIST_URL, url)
                uiCtx { startActivity(intent) }
            }
        } else {
            intent.putExtra(Constants.VIEW_PAGER_SCREEN_TO_LOAD, screenToLoad)
            startActivity(intent)
        }
    }

    private fun prepareAndStartVpn() {
        // On Android 16 (SDK 37)+ the local network access permission is mandatory for the
        // VPN tunnel to function. Request it *before* establishing the VPN; if the user denies
        // it, the VPN is not started. The flow resumes from proceedWithVpnStart() once the
        // permission result is delivered.
        if (isAtleast37() && !hasLocalNetworkPermission()) {
            requestLocalNetworkPermission()
            return
        }
        proceedWithVpnStart()
    }

    // Resumes the VPN start flow (VpnService.prepare + start) once the local network
    // permission requirement has been satisfied (or is not required).
    private fun proceedWithVpnStart() {
        if (prepareVpnService()) {
            startVpnService()
        }
    }

    private fun stopShimmer() {
        //if (!b.shimmerViewContainer1.isShimmerStarted) return

        //b.shimmerViewContainer1.stopShimmer()
    }

    private fun startShimmer() {
        val builder = Shimmer.AlphaHighlightBuilder()
        builder.setDuration(SHIMMER_DURATION_MS)
        builder.setBaseAlpha(SHIMMER_BASE_ALPHA)
        builder.setDropoff(SHIMMER_DROP_OFF)
        builder.setHighlightAlpha(SHIMMER_HIGHLIGHT_ALPHA)
        //b.shimmerViewContainer1.setShimmer(builder.build())
        //b.shimmerViewContainer1.startShimmer()
    }

    private fun stopVpnService() {
        VpnController.stop("home", requireContext())
    }

    private fun startVpnService() {
        // Hard gate: never start the VPN service without the mandatory local network
        // permission on API 37+. This is the single choke point that invokes
        // VpnController.start(), so guarding here makes the denial final regardless of
        // which caller (button, auto-start, or VPN-consent result) reached us.
        if (isAtleast37() && !hasLocalNetworkPermission()) {
            Logger.w(LOG_TAG_VPN, "startVpnService aborted: local network permission missing")
            handleLocalNetworkPermissionDenied()
            return
        }
        // runtime permission for notification (Android 13)
        getNotificationPermissionIfNeeded()
        // user-initiated start must always reach the service: autoAttempt=true
        // would silently drop the request when the service instance is alive
        // but the tunnel is down, leaving the button stuck on START
        VpnController.start(requireContext())
    }

    private fun getNotificationPermissionIfNeeded() {
        if (!Utilities.isAtleastT()) {
            // notification permission is needed for version 13 or above
            return
        }

        if (
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            // notification permission is granted to the app, do nothing
            return
        }

        if (!persistentState.shouldRequestNotificationPermission) {
            // user rejected notification permission
            Logger.w(LOG_TAG_VPN, "User rejected notification permission for the app")
            return
        }

        notificationPermissionResult.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private suspend fun shouldShowRethinkWarning(): Boolean {
        val tag = "rethink_app_status"
        // show the warning in below cases:
        // 1. Rethink is excluded from proxy, and proxy lockdown is enabled (loopback mode)
        // 2. Rethink is either blocked/isolated
        // 3. Rethink is not bypassed(Universal), and universal firewall enabled

        val loopback = persistentState.routeRethinkInRethink
        if (!loopback) {
            Logger.d(LOG_TAG_UI, "$tag not in loopback mode")
            return false
        }

        val appInfo = FirewallManager.getAppInfoByUid(rethinkUid) ?: return false

        val isProxyExcluded = appInfo.isProxyExcluded
        val isProxyLockdown = persistentState.wgGlobalLockdown
        // TODO: check if rethink is part of any active proxies, if not then we need to show
        // the warning (in case of Proxy lockdown) regardless of proxyExcluded.
        // val isAnyProxyActive = appConfig.isProxyEnabled() || RpnProxyManager.isRpnActive()
        if (isProxyExcluded && isProxyLockdown) {
            Logger.d(LOG_TAG_UI, "$tag rethink is exempted from proxy but in proxy lockdown mode")
            return true
        }
        val firewallStatus = FirewallManager.FirewallStatus.getStatus(appInfo.firewallStatus)
        val connStatus = FirewallManager.ConnectionStatus.getStatus(appInfo.connectionStatus)
        val isRethinkBlockedOrIsolated = firewallStatus.isIsolate() || !connStatus.allow()
        if (isRethinkBlockedOrIsolated) {
            Logger.d(LOG_TAG_UI, "$tag rethink is blocked or isolated")
            return true
        }
        val isAppBypass = firewallStatus.bypassUniversal() || firewallStatus.bypassDnsFirewall()
        val count =  persistentState.universalRulesCount.value
        val isAnyUnivRulesEnabled = count != null && count > 0
        if (!isAppBypass && isAnyUnivRulesEnabled) {
            Logger.d(LOG_TAG_UI, "$tag rethink is not bypassed, and universal firewall is enabled")
            return true
        }

        Logger.d(LOG_TAG_UI, "$tag rethink app, no warning needed, proxyExcluded? $isProxyExcluded, proxyLockdown? $isProxyLockdown, isAppBypass? $isAppBypass, isAnyUnivRulesEnabled? $isAnyUnivRulesEnabled")
        return false
    }

    @Throws(ActivityNotFoundException::class)
    private fun prepareVpnService(): Boolean {
        val prepareVpnIntent: Intent? =
            try {
                // In some cases, the intent used to register the VPN service does not open the
                // application (from Android settings). This happens in some of the Android
                // versions.
                // VpnService.prepare() is now registered with requireContext() instead of context.
                // Issue #469
                Logger.i(LOG_TAG_VPN, "Preparing VPN service")
                VpnService.prepare(requireContext())
            } catch (e: NullPointerException) {
                // This exception is not mentioned in the documentation, but it has been encountered
                // users and also by other developers, e.g.
                // https://stackoverflow.com/questions/45470113.
                Logger.e(LOG_TAG_VPN, "Device does not support system-wide VPN mode.", e)
                return false
            } catch (e: IllegalStateException) {
                // VpnService.prepare() throws IllegalStateException("Unavailable in lockdown
                // mode") when another VPN app is set as Always-on VPN with "Block connections
                // without VPN" enabled. See ConnectivityService.throwIfLockdownEnabled().
                Logger.e(LOG_TAG_VPN, "VPN is in lockdown mode, cannot prepare VPN service.", e)
                showToastUiCentered(
                    requireContext(),
                    getString(R.string.hsf_vpn_lockdown_prepare_failure),
                    Toast.LENGTH_LONG
                )
                return false
            }
        // If the VPN.prepare() is not null, then the first time VPN dialog is shown, Show info
        // dialog before that.
        if (prepareVpnIntent != null) {
            Logger.i(LOG_TAG_VPN, "VPN service is prepared")
            showFirstTimeVpnDialog(prepareVpnIntent)
            return false
        }
        Logger.i(LOG_TAG_VPN, "VPN service is prepared, starting VPN service")
        return true
    }

    private fun showFirstTimeVpnDialog(prepareVpnIntent: Intent) {
        val builder = MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
        builder.setTitle(R.string.hsf_vpn_dialog_header)
        builder.setMessage(R.string.hsf_vpn_dialog_message)
        builder.setCancelable(false)
        builder.setPositiveButton(R.string.lbl_proceed) { _, _ ->
            try {
                startForResult.launch(prepareVpnIntent)
            } catch (e: ActivityNotFoundException) {
                Logger.e(LOG_TAG_VPN, "Activity not found to start VPN service", e)
                showToastUiCentered(
                    requireContext(),
                    getString(R.string.hsf_vpn_prepare_failure),
                    Toast.LENGTH_LONG
                )
            }
        }

        builder.setNegativeButton(R.string.lbl_cancel) { _, _ ->
            // no-op
        }
        val dialog = builder.create()
        dialog.show()
        UIUtils.capDialogWidth(dialog)
    }

    private fun registerForActivityResult() {
        startForResult =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
                when (result.resultCode) {
                    Activity.RESULT_OK -> {
                        startVpnService()
                    }
                    Activity.RESULT_CANCELED -> {
                        showToastUiCentered(
                            requireContext(),
                            getString(R.string.hsf_vpn_prepare_failure),
                            Toast.LENGTH_LONG
                        )
                    }
                    else -> {
                        stopVpnService()
                    }
                }
            }

        // Sets up permissions request launcher.
        notificationPermissionResult =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {
                persistentState.shouldRequestNotificationPermission = it
                if (it) {
                    Logger.i(LOG_TAG_UI, "User accepted notification permission")
                } else {
                    Logger.w(LOG_TAG_UI, "User rejected notification permission")
                    Snackbar.make(
                        requireActivity().findViewById<View>(android.R.id.content).rootView,
                        getString(R.string.hsf_notification_permission_failure),
                        Snackbar.LENGTH_LONG
                    )
                        .show()
                }
            }

        localNetworkPermissionResult =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) {
                    Logger.i(LOG_TAG_UI, "User accepted local network permission")
                    // Permission granted; resume the VPN start flow that was deferred in
                    // prepareAndStartVpn().
                    proceedWithVpnStart()
                } else {
                    Logger.w(LOG_TAG_UI, "User rejected local network permission")
                    handleLocalNetworkPermissionDenied()
                }
            }
    }

    @RequiresApi(Build.VERSION_CODES.CINNAMON_BUN)
    private fun hasLocalNetworkPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_LOCAL_NETWORK
        ) == PackageManager.PERMISSION_GRANTED
    }

    @RequiresApi(Build.VERSION_CODES.CINNAMON_BUN)
    private fun requestLocalNetworkPermission() {
        Logger.i(LOG_TAG_UI, "Requesting local network permission before starting VPN")
        localNetworkPermissionResult.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }

    /**
     * Handles the case where the mandatory local network access permission (API 37+) has been
     * denied. The VPN cannot function without it, so this:
     *  1. Disables the VPN itself - stopping the service if running and resetting the persistent
     *     activation flag so the UI reflects the actual (disabled) state and auto-start does not
     *     loop on the next resume.
     *  2. Shows an explanatory dialog offering to open the app's permission settings, since once
     *     the user has selected "Don't ask again" the system permission prompt can no longer be
     *     shown and granting must happen from Settings.
     */
    private fun handleLocalNetworkPermissionDenied() {
        Logger.w(LOG_TAG_VPN, "Local network permission denied; disabling VPN")
        // Disable the VPN itself: stop the service if it is running and reset the persistent
        // activation flag. setVpnEnabled(false) posts to vpnEnabledLiveData, which observeVpnState
        // consumes to set isVpnActivated = false (button flips back to START) and prevents
        // maybeAutoStartVpn() from re-driving the permission prompt on the next onResume.
        VpnController.stop("local-network-permission-denied", requireContext())
        persistentState.setVpnEnabled(false)
        showLocalNetworkPermissionDialog()
    }

    private fun showLocalNetworkPermissionDialog() {
        val builder = MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
        builder.setTitle(R.string.hsf_local_network_permission_dialog_heading)
        val appName = if (Utilities.isAlphaBuild()) getString(R.string.app_name_alpha) else getString(R.string.app_name)
        builder.setMessage(
            getString(
                R.string.hsf_local_network_permission_dialog_message,
                appName
            )
        )
        builder.setCancelable(false)
        builder.setPositiveButton(R.string.lbl_proceed) { _, _ ->
            // Route the user to the app's permission settings page so they can grant the
            // permission; the in-app system prompt may no longer be shown if "Don't ask again"
            // was selected.
            openAppInfo(requireContext())
        }
        builder.setNegativeButton(R.string.lbl_dismiss) { _, _ ->
            // no-op; the VPN remains disabled until the user grants the permission and starts it.
        }
        val dialog = builder.create()
        dialog.show()
        UIUtils.capDialogWidth(dialog)
    }

    // Sets the UI DNS status on/off.
    private fun syncDnsStatus() {
        if (canRethinkBlockItself) {
            b.fhsProtectionLevelTxt.setTextColor(fetchTextColor(R.attr.accentWarning))
            b.fhsProtectionLevelTxt.text = getString(R.string.rethink_home_screen_warning).capitalizeWords()
            return
        }
        val vpnState = VpnController.state()
        // Change status and explanation text
        var statusId: Int
        var colorId: Int
        val privateDnsMode: Utilities.PrivateDnsMode = getPrivateDnsMode(requireContext())

        if (vpnState.on) {
            colorId = fetchTextColor(R.color.accentGood)
            statusId = if (appConfig.getBraveMode().isFirewallMode()) {
                UIUtils.getDnsStatusStringRes(Transaction.Status.COMPLETE.id)
            } else {
                R.string.status_protected
            }
        } else if (isVpnActivated) {
            colorId = fetchTextColor(R.color.accentBad)
            statusId = R.string.status_waiting
        } else if (isAnotherVpnActive()) {
            colorId = fetchTextColor(R.color.accentBad)
            statusId = R.string.status_exposed
        } else {
            colorId = fetchTextColor(R.color.accentBad)
            statusId =
                when (privateDnsMode) {
                    Utilities.PrivateDnsMode.STRICT -> R.string.status_strict
                    else -> R.string.status_exposed
                }
        }

        if (statusId == R.string.status_protected) {
            if (RpnProxyManager.isRpnActive()) {
                statusId = R.string.status_protected_with_rpn
            } else if (appConfig.getBraveMode().isDnsMode() && isPrivateDnsActive(requireContext())) {
                statusId = R.string.status_protected_with_private_dns
                colorId = fetchTextColor(R.color.primaryLightColorText)
            } else if (appConfig.getBraveMode().isDnsMode()) {
                statusId = R.string.status_protected
            } else if (appConfig.isOrbotProxyEnabled() && isPrivateDnsActive(requireContext())) {
                statusId = R.string.status_protected_with_tor_private_dns
                colorId = fetchTextColor(R.color.primaryLightColorText)
            } else if (appConfig.isOrbotProxyEnabled()) {
                statusId = R.string.status_protected_with_tor
            } else if (
                (appConfig.isCustomSocks5Enabled() && appConfig.isCustomHttpProxyEnabled()) &&
                isPrivateDnsActive(requireContext())
            ) { // SOCKS5 + Http + PrivateDns
                statusId = R.string.status_protected_with_proxy_private_dns
                colorId = fetchTextColor(R.color.primaryLightColorText)
            } else if (appConfig.isCustomSocks5Enabled() && appConfig.isCustomHttpProxyEnabled()) {
                statusId = R.string.status_protected_with_proxy
            } else if (appConfig.isCustomSocks5Enabled() && isPrivateDnsActive(requireContext())) {
                statusId = R.string.status_protected_with_socks5_private_dns
                colorId = fetchTextColor(R.color.primaryLightColorText)
            } else if (
                appConfig.isCustomHttpProxyEnabled() && isPrivateDnsActive(requireContext())
            ) {
                statusId = R.string.status_protected_with_http_private_dns
                colorId = fetchTextColor(R.color.primaryLightColorText)
            } else if (appConfig.isCustomHttpProxyEnabled()) {
                statusId = R.string.status_protected_with_http
            } else if (appConfig.isCustomSocks5Enabled()) {
                statusId = R.string.status_protected_with_socks5
            } else if (isPrivateDnsActive(requireContext())) {
                statusId = R.string.status_protected_with_private_dns
                colorId = fetchTextColor(R.color.primaryLightColorText)
            } else if (appConfig.isWireGuardEnabled() && isPrivateDnsActive(requireContext())) {
                statusId = R.string.status_protected_with_wg_private_dns
                colorId = fetchTextColor(R.color.primaryLightColorText)
            } else if (appConfig.isWireGuardEnabled()) {
                statusId = R.string.status_protected_with_wg
            }
        }

        if (persistentState.wgGlobalLockdown) {
            val stat = getString(statusId).lowercase()
            val s  = stat.replaceFirst(getString(R.string.status_protected), getString(R.string.firewall_rule_global_lockdown), true).capitalizeWords()
            b.fhsProtectionLevelTxt.setTextColor(colorId)
            b.fhsProtectionLevelTxt.text = s
        } else {
            b.fhsProtectionLevelTxt.setTextColor(colorId)
            val s = getString(statusId).capitalizeWords()
            b.fhsProtectionLevelTxt.text = s
        }
        val isUnderlyingVpnNwEmpty = VpnController.isUnderlyingVpnNetworkEmpty()
        if (isUnderlyingVpnNwEmpty) {
            b.fhsProtectionLevelTxt.setTextColor(fetchTextColor(R.color.accentBad))
            b.fhsProtectionLevelTxt.text = getString(R.string.status_no_network).capitalizeWords()
        }
        updateActiveSinceUi()
    }

    private fun isAnotherVpnActive(): Boolean {
        return try {
            val connectivityManager =
                requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = connectivityManager.activeNetwork ?: return false
            val capabilities =
                connectivityManager.getNetworkCapabilities(activeNetwork)
                    ?: // It's not clear when this can happen, but it has occurred for at least one
                    // user.
                    return false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } catch (e: SecurityException) {
            // Fix: Handle SecurityException that can occur when calling getNetworkCapabilities()
            // on certain devices/Android versions or when app lacks proper permissions
            Logger.e(LOG_TAG_VPN, "SecurityException checking VPN status: ${e.message}", e)
            false
        } catch (e: Exception) {
            // Catch any other unexpected exceptions
            Logger.e(LOG_TAG_VPN, "err checking VPN status: ${e.message}", e)
            false
        }
    }

    private fun fetchTextColor(attr: Int): Int {
        val attributeFetch =
            when (attr) {
                R.color.accentGood -> {
                    R.attr.accentGood
                }
                R.color.accentBad -> {
                    R.attr.accentBad
                }
                R.color.accentWarning -> {
                    R.attr.accentWarning
                }
                else -> {
                    R.attr.colorOnSurfaceVariant
                }
            }
        return UIUtils.fetchColor(requireContext(), attributeFetch)
    }

    /**
     * Updates the [android.widget.TextView] text with a smooth crossfade so the transition
     * from the old value to [newText] is never a jarring snap.  If the text is already equal
     * the view is left untouched to avoid unnecessary animation work.
     */
    private fun android.widget.TextView.setTextAnimated(newText: String) {
        if (text?.toString() == newText) return
        animate()
            .alpha(0f)
            .setDuration(TEXT_FADE_DURATION_MS)
            .withEndAction {
                text = newText
                animate()
                    .alpha(1f)
                    .setDuration(TEXT_FADE_DURATION_MS)
                    .start()
            }
            .start()
    }

    /**
     * Shrinks and dims a card headline for its feature's inactive state so the
     * "off" hint reads as a subtle status indicator rather than a headline.
     */
    private fun android.widget.TextView.applyLowEmphasis(activeSizePx: Float) {
        if (activeSizePx <= 0f) return
        setTextSize(TypedValue.COMPLEX_UNIT_PX, activeSizePx * INACTIVE_TEXT_SCALE)
        // higher alpha in light mode for readability
        alpha = if (isLightTheme()) 0.7f else INACTIVE_ELEMENT_ALPHA
    }

    private fun isLightTheme(): Boolean {
        return Themes.isActivityLightTheme(isDarkThemeOn(), persistentState.theme)
    }

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    /** Restores a card headline to its captured active-state emphasis. */
    private fun android.widget.TextView.restoreFullEmphasis(activeSizePx: Float) {
        if (activeSizePx <= 0f) return
        setTextSize(TypedValue.COMPLEX_UNIT_PX, activeSizePx)
        alpha = 1f
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) {
            if (isAdded && view != null) {
                f()
            }
        }
    }

    private fun ui(n: String, f: suspend () -> Unit): Job {
        val mainCtx = CoroutineName(n) + Dispatchers.Main
        return lifecycleScope.launch(mainCtx) {
            if (isAdded && view != null) { f() }
        }
    }
}
