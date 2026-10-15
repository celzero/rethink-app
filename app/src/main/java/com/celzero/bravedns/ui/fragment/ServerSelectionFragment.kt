/*
 * Copyright 2025 RethinkDNS and its authors
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

import com.celzero.bravedns.ui.compose.rpn.*
import com.celzero.bravedns.ui.compose.home.HomeTourAnchors
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.chrome.navigationOverlapBottomPx
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.celzero.bravedns.R
import com.celzero.bravedns.database.AppInfoRepository
import com.celzero.bravedns.database.ConnectionTrackerDAO
import com.celzero.bravedns.database.CountryConfig
import com.celzero.bravedns.database.CountryConfigRepository
import com.celzero.bravedns.database.DnsLogDAO
import com.celzero.bravedns.database.RethinkLogDao
import com.celzero.bravedns.database.SubscriptionStatus
import com.celzero.bravedns.database.SubscriptionStatusDao
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.rpnproxy.RpnProxyManager.AUTO_SERVER_ID
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.activity.FragmentHostActivity
import com.celzero.bravedns.ui.activity.RpnBypassAppsActivity
import com.celzero.bravedns.ui.adapter.CountryServerAdapter
import com.celzero.bravedns.ui.adapter.VpnServerAdapter
import com.celzero.bravedns.ui.bottomsheet.RpnLogActivityIntervalBottomSheet
import com.celzero.bravedns.ui.bottomsheet.RpnStatsBottomSheet
import com.celzero.bravedns.ui.bottomsheet.ServerRemovalNotificationBottomSheet
import com.celzero.bravedns.ui.bottomsheet.ServerSettingsBottomSheet
import com.celzero.bravedns.ui.custom.EmbeddedDolphinContent
import com.celzero.bravedns.ui.fragment.ServerSelectionFragment.Companion.ERROR_DOLPHIN_BOB_HALF_MS
import com.celzero.bravedns.ui.fragment.ServerSelectionFragment.Companion.RPN_HEATMAP_ROWS_PER_HOUR
import com.celzero.bravedns.ui.fragment.ServerSelectionFragment.Companion.RPN_HEATMAP_SLOTS
import com.celzero.bravedns.ui.fragment.ServerSelectionFragment.Companion.RPN_ONBOARDING_READY_TIMEOUT_MS
import com.celzero.bravedns.ui.tour.RpnOnboardingManager
import com.celzero.bravedns.ui.tour.TourOverlayController
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import com.celzero.bravedns.util.SnackbarHelper
import com.celzero.bravedns.util.SnackbarHelper.capitalizeWords
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.ServerSelectionViewModel
import com.celzero.firestack.backend.Backend
import com.celzero.bravedns.ui.compose.wireguard.WgAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.log10
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds

/**
 * Fragment for selecting VPN servers from a list.
 */
class ServerSelectionFragment : Fragment(),
    VpnServerAdapter.ServerSelectionListener,
    CountryServerAdapter.CitySelectionListener {

    private val subscriptionStatusDao by inject<SubscriptionStatusDao>()
    private val countryConfigRepository by inject<CountryConfigRepository>()
    private val appInfoRepository by inject<AppInfoRepository>()
    private val connectionTrackerDAO by inject<ConnectionTrackerDAO>()
    private val dnsLogDAO by inject<DnsLogDAO>()
    private val rethinkLogDao by inject<RethinkLogDao>()
    private val persistentState by inject<PersistentState>()
    private lateinit var tourAnchors: HomeTourAnchors
    private lateinit var ui: RpnSelectionState

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        tourAnchors = HomeTourAnchors.forActivity(requireActivity())
        ui = RpnSelectionState(inflater.context)
        return ComposeView(inflater.context).apply {
            id = R.id.root
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { RethinkTheme { RpnSelectionScreen(ui, tourAnchors) } }
        }
    }
    private val serverSelectionViewModel: ServerSelectionViewModel by activityViewModel()

    private lateinit var serverAdapter: CountryServerAdapter
    private lateinit var selectedAdapter: VpnServerAdapter

    private val allServers = mutableListOf<CountryConfig>()
    private val unselectedServers = mutableListOf<CountryConfig>()
    private val selectedServers = mutableListOf<CountryConfig>()

    private var statusUpdateJob: Job? = null

    /** Last touch position on the RPN heat map, used to resolve the tapped cell. */
    private var lastHeatmapTouchX = 0f
    private var lastHeatmapTouchY = 0f

    /**
     * Exclusive end (epoch-millis) of the most recently rendered heat-map
     * window; tapped cell timestamps are derived from it. Zero until the
     * first successful load.
     */
    private var heatmapWindowEndMs = 0L

    /** In-flight heat-map load; cancelled when a newer load supersedes it. */
    private var rpnHeatmapJob: Job? = null

    /** Looping alpha blink on the header status dot while connected. */
    private var blinkAnimator: ObjectAnimator? = null

    /** Job driving the registration / server-list polling loop. */
    private var serverLoadingJob: Job? = null
    /** Job driving the RPN reset progress loop. */
    private var rpnResetJob: Job? = null
    /**
     * Gentle looping bob on the error card's dolphin while the error/empty
     * state is visible; cancelled when the state is dismissed.
     */
    private var errorDolphinAnimator: ObjectAnimator? = null

    /**
     * Job polling for the VPN tunnel after the user taps "Start Rethink" on
     * the no-tunnel error card; re-drives the screen once the tunnel is up.
     */
    private var errorTunnelWaitJob: Job? = null
    /** Dialog shown while RPN reset is in progress. */
    private var rpnResetDialog: android.app.Dialog? = null
    private var resetDialogDismissedByUser = false
    /**
     * Short-lived job that polls [VpnController.getWinByKey] for each selected server
     * whose WIN tunnel key was null immediately after [initServers] completed.
     */
    private var tunnelWatchJob: Job? = null

    /** Guards against double-tapping the FAB stop/start. */
    private var toggleProxyInFlight = false
    /** Guards against re-entrant pull-to-refresh while a swipe refresh is in flight. */
    private var swipeRefreshInFlight = false
    /** Looping spin animator running on the FAB icon while stop/start is in progress. */
    private var fabLoadingAnimator: ObjectAnimator? = null

    /** Looping swim-across animation on the fx overlay while a pull-to-refresh is in flight. */
    private var refreshSwimAnimator: AnimatorSet? = null

    /** One-shot dolphin arc played on the fx overlay when a location finishes connecting. */
    private var connectArcAnimator: AnimatorSet? = null

    /** Last caption rendered on the relay tile state text; drives the change pop. */
    private var lastRelayCaption: String? = null

    /**
     * Last rendered filled-pill count for the location-capacity scale; the
     * newly-filled pill pops only when this count increases.
     */
    private var lastFilledCapacity = 0

    /** Previous connection UI state; CONNECTED transitions pulse the status dot once. */
    private var lastConnectionUiState: ConnectionUiState? = null

    private var isWinRegistered = false
    private var autoServer: CountryConfig? = null

    /** Cached relay-tile state: true when every enabled non-AUTO location has relay (hop) on. */
    private var isRelayAllOn = false

    /** Guards against double-tapping the relay quick-setting while a bulk toggle is in flight. */
    private var relayToggleInFlight = false

    /** True from the moment onViewCreated fires until initServers finishes. */
    private var isLoading = true

    /**
     * True when the user has explicitly stopped the proxy via the settings
     * bottom sheet.  While stopped:
     * - Hero banner shows "Stopped" chip
     * - Server list + search are dimmed / non-interactive
     * - Status chip is still tappable → opens settings sheet to restart
     */
    private var isProxyStopped = false

    /**
     * Prevents the bottom sheet from re-opening every time the subscription DB
     * row emits (e.g. on a background refresh while the screen is visible).
     */
    private var resubscribePromptShown = false

    /**
     * Load tiers available in the location filter dialog. [label] is the
     * server-load percentage range shown on the filter chip and in the
     * active-filter summary.
     */
    private enum class LoadFilter(val label: String) {
        ALL(""), LOW("≤ 40%"), MEDIUM("41–80%"), HIGH("> 80%")
    }

    /** Active load-tier filter for the "All locations" list. */
    private var loadFilter = LoadFilter.ALL

    /**
     * Active speed filter for the "All locations" list.  0 means "Any"; any other
     * value is a link speed in Mbps offered as a chip in the filter dialog.  The
     * option set is derived from the speeds actually present in [allServers], so
     * only the values the backend reports (e.g. 1 Gbps, 10 Gbps) are shown.
     */
    private var speedFilter = 0

    /** When true, the "All locations" list is restricted to favourite countries. */
    private var favouritesOnly = false

    companion object {
        private const val TAG = "ServerSelectionFragment"

        /**
         * Maximum number of NON-AUTO servers the user can select simultaneously.
         * AUTO is always kept connected on top of this limit.
         */
        private const val MAX_SELECTIONS = 5

        /** Half of the error card dolphin's bob cycle (down + up = one loop). */
        private const val ERROR_DOLPHIN_BOB_HALF_MS = 1_000L

        /** How far the error card dolphin floats up on each bob, in dp. */
        private const val ERROR_DOLPHIN_BOB_DP = 5f

        /** How long to wait for the VPN tunnel after "Start Rethink" is tapped. */
        private const val TUNNEL_WAIT_TIMEOUT_MS = 20_000L

        /** Poll interval while waiting for the VPN tunnel to come up. */
        private const val TUNNEL_WAIT_POLL_MS = 500L

        /**
         * Delay before the premium RPN onboarding tour starts, in milliseconds.
         */
        private const val RPN_ONBOARDING_START_DELAY_MS = 1500L

        /**
         * Poll interval for the RPN onboarding readiness check, in milliseconds.
         */
        private const val RPN_ONBOARDING_READY_POLL_INTERVAL_MS = 250L

        /**
         * Give the dashboard at most this long to settle (load finished, no
         * error container) before giving up on the onboarding for this visit.
         */
        private const val RPN_ONBOARDING_READY_TIMEOUT_MS = 20_000L

        /**
         * Pull-to-refresh must be dragged this far (dp) before it fires. The
         * framework default (~64dp) triggers on small accidental swipes at
         * scroll-top; requiring a deep, deliberate pull avoids spurious
         * refreshes. Roughly 3x the default.
         */
        private const val SWIPE_REFRESH_TRIGGER_DP = 220

        /**
         * Caps how far the spinner itself travels during the pull so the
         * indicator stays visible near the top while the user keeps dragging
         * past the trigger distance.
         */
        private const val SWIPE_REFRESH_SLINGSHOT_DP = 220

        /** UI connection states surfaced by [updateConnectionStatus]. */
        private enum class ConnectionUiState { DISCONNECTED, CONNECTING, CONNECTED, REGISTERING, FAILED }

        /** Maximum time the inline registration progress will poll before giving up. */
        private const val LOADING_DIALOG_TIMEOUT_MS = 20_000L
        /** Interval between registration / server-list poll iterations. */
        private const val LOADING_DIALOG_POLL_INTERVAL_MS = 1_500L

        // RPN activity heat map: one dot == one 10-minute interval, one
        // COLUMN == one clock hour (6 dots per column). The wall covers the
        // trailing 24 hours, so column 0 is the
        // hour starting 24 hours ago and the LAST column is the current
        private const val RPN_HEATMAP_HOURS = 24
        private const val RPN_HEATMAP_ROWS_PER_HOUR = 6 // 10-min buckets per hour
        private const val RPN_HEATMAP_BUCKET_MS = 10L * 60L * 1000L
        private const val RPN_HEATMAP_WINDOW_MS = 24L * 60L * 60L * 1000L
        private const val RPN_HEATMAP_SLOTS =
            RPN_HEATMAP_HOURS * RPN_HEATMAP_ROWS_PER_HOUR
        private const val RPN_HEATMAP_INTENSITY_LEVELS = 5

        // dot fill fraction per intensity level (mirrors HomeScreenFragment's
        // HEATMAP_CELL_SIZE_FRACTION: level 0 is a small placeholder dot)
        private val RPN_HEATMAP_CELL_SIZE_FRACTION =
            floatArrayOf(0.30f, 0.78f, 0.78f, 1f, 1f)

        // base dot diameter in dp before the per-level fill fraction; sized so
        // 24 columns + 2dp gaps fit the narrowest supported screens (~320dp)
        private const val RPN_HEATMAP_DOT_SIZE_DP = 5f

        /** Bubbles emitted when a capacity pill fills. */
        private const val CAPACITY_BUBBLE_COUNT = 2

        /** Duration of the connect-celebration arc, in milliseconds. */
        private const val CONNECT_ARC_DURATION_MS = 650L

        /** Duration of one full left-to-right refresh swim, in milliseconds. */
        private const val REFRESH_SWIM_DURATION_MS = 2400L

        /** Rolling window (last hour) for the Stats chip count and stats sheet. */
        private const val PULSE_STATS_WINDOW_MS = 60L * 60L * 1000L

        /** Duration of the shimmer→content fade-in when the initial load settles. */
        private const val CONTENT_FADE_IN_MS = 180L

        /** Delay between sections in the load-settle cascade. */
        private const val CONTENT_STAGGER_MS = 60L

        /** Duration of the activity-feed card's height-animated show/hide. */
        private const val FEED_CARD_ANIM_MS = 220L
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        isProxyStopped = RpnProxyManager.rpnMode().isNone()

        var navBarBottom = 0
        val viewLocation = IntArray(2)
        fun updateNavigationClearance() {
            val bounds = tourAnchors.find(R.id.nav_view)
            view.getLocationInWindow(viewLocation)
            // The Home shell reserves the entire bar, including system insets. Only
            // actual viewport overlap needs padding; standalone hosts retain their inset.
            val overlap = if (bounds != null) {
                navigationOverlapBottomPx(viewLocation[1] + view.height, bounds.top)
            } else navBarBottom
            ui.fabBottomPx = overlap + dpPx(16)
            ui.scrollBottomPx = if (bounds != null) overlap + dpPx(24) else 0
        }
        val reposition = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateNavigationClearance()
        }
        view.addOnLayoutChangeListener(reposition)
        val stopObservingNavigation = tourAnchors.observeBounds(R.id.nav_view) {
            updateNavigationClearance()
        }
        viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                stopObservingNavigation()
                view.removeOnLayoutChangeListener(reposition)
                owner.lifecycle.removeObserver(this)
            }
        })
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            navBarBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            updateNavigationClearance()
            insets
        }
        ViewCompat.requestApplyInsets(view)

        setupNavigationButtons()
        setupSearchBar()
        applyTunnelGatedVisibility()
        updateFilterButtonState()
        setupHeaderUI()
        setupRpnState()
        setupQuickSettings()
        setupStatsChip()
        setupRpnHeatmapClicks()
        setupSwipeToRefresh()
        loadRpnHeatmap()
        // Center the QS pill row once its real (measured) width is known.

        // Show the correct FAB immediately (no animation on first load).
        if (isProxyStopped) {
            applyProxyStoppedUi()
            ui.fabStopProxy.visibility  = View.GONE
            ui.fabStartProxy.visibility = View.VISIBLE
        } else {
            ui.fabStopProxy.visibility  = View.VISIBLE
            ui.fabStartProxy.visibility = View.GONE
        }

        animateHeaderEntry()
        observeRefreshState()
        observeResetState()
        scheduleRpnOnboardingIfNeeded()
    }

    /**
     * Schedules the premium RPN onboarding tour ([RpnOnboardingManager]).
     *
     * If the onboarding has already been completed at the current version,
     * this is a no-op.
     */
    private fun scheduleRpnOnboardingIfNeeded() {
        if (!RpnOnboardingManager.shouldShowOnboarding(persistentState)) return
        Utilities.delay(RPN_ONBOARDING_START_DELAY_MS, lifecycleScope) {
            waitUntilDashboardReadyThenStartTour()
        }
    }

    /**
     * Polls [isDashboardReadyForTour] until the dashboard is presentable, then
     * starts the tour. Gives up after [RPN_ONBOARDING_READY_TIMEOUT_MS].
     */
    private fun waitUntilDashboardReadyThenStartTour() {
        viewLifecycleOwner.lifecycleScope.launch {
            var waitedMs = 0L
            while (waitedMs < RPN_ONBOARDING_READY_TIMEOUT_MS) {
                if (isDashboardReadyForTour()) {
                    startRpnOnboardingTour()
                    return@launch
                }
                delay(RPN_ONBOARDING_READY_POLL_INTERVAL_MS.milliseconds)
                waitedMs += RPN_ONBOARDING_READY_POLL_INTERVAL_MS
            }
            Logger.w(
                LOG_TAG_UI,
                "$TAG: RPN onboarding aborted; dashboard not ready (still loading or error visible) after ${waitedMs}ms"
            )
        }
    }

    /**
     * `true` when the dashboard has settled into a presentable state:
     * the initial load (shimmer / WIN registration / server fetch) has finished
     * and neither the error nor the empty-state container is showing.
     */
    private fun isDashboardReadyForTour(): Boolean {
        if (!isAdded || isDetached || view == null) return false
        // Initial load still in progress (shimmer, registration, server fetch).
        if (isLoading) return false
        // Error or empty state visible — the retry path may still recover.
        if (ui.errorStateContainer.isVisible) return false
        return true
    }

    private fun startRpnOnboardingTour() {
        val host = activity ?: return
        try {
            TourOverlayController(
                activity   = host,
                steps      = RpnOnboardingManager.rpnOnboardingSteps(),
                composeAnchor = tourAnchors::find,
                onComplete = {
                    RpnOnboardingManager.markCompleted(persistentState)
                    Logger.v(LOG_TAG_UI, "$TAG: RPN onboarding tour completed")
                },
            ).start()
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "$TAG: failed to start RPN onboarding tour: ${e.message}", e)
        }
    }



    /**
     * pull-to-refresh on the whole screen.
     * The gesture is disabled while the initial load shimmer, an RPN reset,
     * or a stopped proxy is active.
     */
    private fun setupSwipeToRefresh() {
        ui.swipeRefresh.onRefresh = {
            Logger.i(LOG_TAG_UI, "$TAG.setupSwipeToRefresh: pull-to-refresh triggered")
            handleSwipeRefresh()
        }
        ui.swipeRefresh.isEnabled = !isLoading
    }

    /**
     * Pull-to-refresh handler: refreshes the WIN proxy inside the tunnel
     * ([VpnController.refreshRpnProxy]), then reloads the server list and
     * re-renders it. Runs as a one-shot suspend (no state flow), so the
     * spinner is shown/hidden here and re-entrant pulls are coalesced via
     * [swipeRefreshInFlight].
     */
    private fun handleSwipeRefresh() {
        // Coalesce: ignore a second pull while one refresh is already running.
        if (swipeRefreshInFlight) return
        swipeRefreshInFlight = true
        ui.swipeRefresh.isRefreshing = true
        startRefreshSwim()

        io {
            val refreshed = if (RpnProxyManager.isRpnActive()) {
                try {
                    VpnController.refreshRpnProxy(Backend.RpnWin)
                } catch (e: Exception) {
                    Logger.e(LOG_TAG_UI, "$TAG.handleSwipeRefresh: refreshRpnProxy failed: ${e.message}", e)
                    false
                }
            } else {
                Logger.w(LOG_TAG_UI, "$TAG.handleSwipeRefresh: RPN not active, skipping proxy refresh")
                false
            }

            // Reload the server list from cache/DB regardless of the refresh
            // result so the UI reflects the current server status/load.
            val servers = try { RpnProxyManager.getWinServers() } catch (_: Exception) { emptyList() }
            val selected = try { RpnProxyManager.getEnabledConfigs() } catch (_: Exception) { emptySet() }

            uiCtx {
                swipeRefreshInFlight = false
                stopRefreshSwim()
                if (!isAdded) return@uiCtx
                ui.swipeRefresh.isRefreshing = false
                if (refreshed) {
                    showToast(getString(R.string.dc_refresh_toast))
                } else {
                    Logger.w(LOG_TAG_UI, "$TAG.handleSwipeRefresh: proxy refresh failed or RPN inactive")
                }
                if (servers.any { it.id != AUTO_SERVER_ID }) {
                    initServers(servers, selected)
                }
            }
        }
    }

    private fun setupRpnState() {
        setupRecyclerViews()
        observeSubscription()
        observeServerRemovedEvents()

        // Re-render the last snapshot immediately; revalidate in the background.
        val snapshot = serverSelectionViewModel.dashboardSnapshot
        if (snapshot != null && snapshot.servers.any { it.id != AUTO_SERVER_ID }) {
            Logger.v(LOG_TAG_UI, "$TAG.setupRpnState: rendering cached snapshot (${snapshot.servers.size} servers)")
            isWinRegistered = snapshot.isWinRegistered
            setLoadingState(false)
            initServers(snapshot.servers, snapshot.selected)
        } else {
            setLoadingState(true)
        }

        refreshServerState()
    }

    /** Loads registration state and the server list, then re-renders. */
    private fun refreshServerState() {
        io {
            val rpnActive = RpnProxyManager.isRpnActive()

            // Always try to load the server list from cache/DB so the list is visible
            // even when the proxy is stopped (items will be dimmed and non-interactive).
            isWinRegistered = if (rpnActive) VpnController.isWinRegistered() else false
            val hasTunnel = VpnController.hasTunnel()
            val selectedList = RpnProxyManager.getEnabledConfigs()
            Logger.v(LOG_TAG_UI, "$TAG; WIN registered: $isWinRegistered, rpnActive: $rpnActive, hasTunnel: $hasTunnel")
            // try registering the rpn if it is not registered
            if (!isWinRegistered && rpnActive && hasTunnel) {
                RpnProxyManager.registerProxy(RpnProxyManager.RpnType.WIN)
            }

            val servers = RpnProxyManager.getWinServers()
            Logger.v(LOG_TAG_UI, "$TAG; fetched ${servers.size} servers from RPN")
            val hasRealServers = servers.any { it.id != AUTO_SERVER_ID }

            uiCtx {
                if (!isAdded) return@uiCtx
                when {
                    !rpnActive -> {
                        // Proxy is stopped: still load the list from cache/DB so the UI
                        // shows dimmed server items (non-interactive).  initServers()
                        // re-applies applyProxyStoppedUi() itself once adapters are
                        // populated (see the isProxyStopped guard inside initServers).
                        if (hasRealServers) {
                            initServers(servers, selectedList)
                        } else {
                            setLoadingState(false)
                            dismissServerLoadingDialog()
                            applyProxyStoppedUi()
                        }
                    }
                    (!isWinRegistered || !hasRealServers) -> {
                        // either registration is pending or the server list hasn't populated yet.
                        if (!hasTunnel) {
                            // VPN tunnel is not up
                            // Show a prompt to start Rethink instead of the loading dialog.
                            Logger.w(
                                LOG_TAG_UI,
                                "$TAG: no VPN tunnel available; showing no-tunnel error"
                            )
                            setLoadingState(false)
                            showErrorState(noTunnel = true)
                        } else {
                            Logger.i(
                                LOG_TAG_UI,
                                "$TAG: WIN registered=$isWinRegistered, hasRealServers=$hasRealServers; showing loading dialog"
                            )
                            showServerLoadingDialog()
                        }
                    }
                    else -> initServers(servers, selectedList)
                }
            }
        }

    }

    override fun onResume() {
        Logger.vv(LOG_TAG_UI, "$TAG.onResume")
        super.onResume()
        redriveProxyStartStopState()
        // Bypass apps / live-connection counts change outside this screen
        // (e.g. after returning from RpnBypassAppsActivity), so re-read them.
        refreshBypassAppsTileState()
        refreshRelayTileState()
        // Refresh the RPN heat map so newly logged connections show up when
        // the user returns to this screen.
        loadRpnHeatmap()
        // Same for the Stats chip: connections logged while away.
        refreshStatsChipState()
    }

    override fun onStart() {
        super.onStart()
        blinkAnimator?.let { if (it.isPaused) it.resume() }
        errorDolphinAnimator?.let { if (it.isPaused) it.resume() }
        listOf(ui.shimmerHeader, ui.shimmerServerList, ui.shimmerSubscriptionBanner).forEach {
            if (it.isVisible) it.startShimmer()
        }
    }

    override fun onStop() {
        blinkAnimator?.pause()
        errorDolphinAnimator?.pause()
        listOf(ui.shimmerHeader, ui.shimmerServerList, ui.shimmerSubscriptionBanner).forEach {
            if (it.isVisible) it.stopShimmer()
        }
        super.onStop()
    }

    /**
     * Observes [ServerSelectionViewModel.refreshState] and reacts to refresh results.
     */
    private fun observeRefreshState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                serverSelectionViewModel.refreshState.collect { state ->
                    when (state) {
                        is ServerSelectionViewModel.RefreshState.Done -> {
                            Logger.i(LOG_TAG_UI, "$TAG.observeRefreshState: Done, ${state.servers.size} servers")
                            serverSelectionViewModel.onRefreshConsumed()
                            initServers(state.servers, state.selected)
                        }
                        is ServerSelectionViewModel.RefreshState.NeedsLoading -> {
                            // updateWinProxy returned no servers on a user-initiated refresh.
                            Logger.w(LOG_TAG_UI, "$TAG.observeRefreshState: NeedsLoading on user-initiated refresh, consuming")
                            serverSelectionViewModel.onRefreshConsumed()
                        }
                        is ServerSelectionViewModel.RefreshState.NoTunnel -> {
                            // VPN tunnel dropped (or was never up) during the refresh; show
                            // the "Start Rethink to proceed" error so the user knows what to do.
                            Logger.w(LOG_TAG_UI, "$TAG.observeRefreshState: NoTunnel, showing no-tunnel error")
                            serverSelectionViewModel.onRefreshConsumed()
                            setLoadingState(false)
                            showErrorState(noTunnel = true)
                        }
                        is ServerSelectionViewModel.RefreshState.InProgress,
                        is ServerSelectionViewModel.RefreshState.Idle -> {
                            // no ui action needed here; the bottom sheet owns the animation.
                            // The pull-to-refresh spinner is managed independently by
                            // handleSwipeRefresh().
                        }
                    }
                }
            }
        }
    }

    /**
     * Observes [ServerSelectionViewModel.resetState] and reacts to the reset lifecycle.
     *
     * **[ServerSelectionViewModel.ResetState.InProgress]**:
     * - FABs are disabled to prevent proxy start/stop while reset is running.
     * - If [resetDialogDismissedByUser] is false (first enter or rotation): show the
     *   progress dialog.
     * - If [resetDialogDismissedByUser] is true (user dismissed dialog but reset is
     *   still running): show the inline progress bar as a non-blocking
     *   indicator and do NOT re-open the dialog.
     *
     * **[ServerSelectionViewModel.ResetState.Done]**: Consume result, dismiss dialog,
     * hide inline bar, restore FABs, delegate to [handleResetResult].
     *
     * **[ServerSelectionViewModel.ResetState.NoTunnel]**: Consume, dismiss, hide bar,
     * restore FABs, show "Start Rethink" error.
     */
    private fun observeResetState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                serverSelectionViewModel.resetState.collect { state ->
                    when (state) {
                        is ServerSelectionViewModel.ResetState.InProgress -> {
                            // Always disable FABs while reset is running so the user
                            // cannot start/stop the proxy mid-operation.
                            if (isAdded && view != null) {
                                ui.fabStopProxy.isClickable  = false
                                ui.fabStartProxy.isClickable = false
                                // Block pull-to-refresh while a reset is in flight.
                                ui.swipeRefresh.isEnabled = false
                            }
                            if (resetDialogDismissedByUser) {
                                // User explicitly dismissed the dialog; show inline bar
                                // as a subtle indicator; the dialog will NOT re-appear.
                                Logger.i(LOG_TAG_UI, "$TAG.observeResetState: InProgress, dialog dismissed, showing inline bar")
                                if (isAdded && view != null) {
                                    ui.registrationProgressBar.show()
                                    // Disable search and action icons while reset is in progress
                                    setSearchAndActionsEnabled(false)
                                }
                            } else if (rpnResetDialog?.isShowing != true) {
                                // Fresh InProgress (or fragment recreated after rotation):
                                // show the progress dialog.
                                Logger.i(LOG_TAG_UI, "$TAG.observeResetState: InProgress, showing reset dialog")
                                showRpnResetDialog()
                            }
                        }
                        is ServerSelectionViewModel.ResetState.Done -> {
                            Logger.i(LOG_TAG_UI, "$TAG.observeResetState: Done, result=${state.result}")
                            serverSelectionViewModel.onResetConsumed()
                            resetDialogDismissedByUser = false
                            dismissRpnResetDialog()
                            if (isAdded && view != null) {
                                ui.registrationProgressBar.hide()
                                ui.fabStopProxy.isClickable  = true
                                ui.fabStartProxy.isClickable = true
                                ui.swipeRefresh.isEnabled = true
                                // Restore search and action icons now that reset is done
                                setSearchAndActionsEnabled(true)
                            }
                            handleResetResult(state.result, state.servers, state.selected)
                        }
                        is ServerSelectionViewModel.ResetState.NoTunnel -> {
                            Logger.w(LOG_TAG_UI, "$TAG.observeResetState: NoTunnel, showing no-tunnel error")
                            serverSelectionViewModel.onResetConsumed()
                            resetDialogDismissedByUser = false
                            dismissRpnResetDialog()
                            if (isAdded && view != null) {
                                ui.registrationProgressBar.hide()
                                ui.fabStopProxy.isClickable  = true
                                ui.fabStartProxy.isClickable = true
                                ui.swipeRefresh.isEnabled = true
                                // Restore search and action icons
                                setSearchAndActionsEnabled(true)
                            }
                            showErrorState(noTunnel = true)
                        }
                        is ServerSelectionViewModel.ResetState.Idle -> {
                            // Ensure FABs are interactive when no reset is active.
                            if (isAdded && view != null) {
                                ui.fabStopProxy.isClickable  = true
                                ui.fabStartProxy.isClickable = true
                                ui.swipeRefresh.isEnabled = !isLoading
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Handles the UI update after a reset completes.
     * Called from [observeResetState] on the main thread after [dismissRpnResetDialog].
     */
    private fun handleResetResult(
        result: RpnProxyManager.ResetResult,
        servers: List<CountryConfig>,
        selected: Set<CountryConfig>
    ) {
        if (!isAdded) return
        when (result) {
            is RpnProxyManager.ResetResult.Success -> {
                isProxyStopped = false
                showToast(getString(R.string.rpn_restore_success))
                if (servers.isNotEmpty()) initServers(servers, selected)
            }
            is RpnProxyManager.ResetResult.Failure -> {
                Logger.w(LOG_TAG_UI, "$TAG.handleResetResult: reset failed, reason: ${result.reason}")
                showToast(getString(R.string.rpn_restore_failure, result.reason))
                if (!RpnProxyManager.isRpnActive()) {
                    isProxyStopped = true
                    applyProxyStoppedUi()
                } else if (servers.isNotEmpty()) {
                    initServers(servers, selected)
                }
            }
        }
    }

    private fun redriveProxyStartStopState() {
        // rederive proxy stopped state on every resume, external changes (notification,
        // BraveVPNService) may have changed rpnMode without going through the bottom sheet listener.
        val wasProxyStopped = isProxyStopped
        isProxyStopped = RpnProxyManager.rpnMode().isNone()

        // If state changed while we were away, refresh the UI immediately.
        if (wasProxyStopped != isProxyStopped && !isLoading) {
            if (isProxyStopped) {
                dismissServerLoadingDialog()
                applyProxyStoppedUi()
            } else {
                applyProxyRunningUi()
            }
        }

        if (!RpnProxyManager.isRpnActive()) {
            if (!isLoading) {
                dismissServerLoadingDialog()
                if (!isProxyStopped) {
                    isProxyStopped = true
                    applyProxyStoppedUi()
                }
            }
        } else {
            io {
                val (refreshedServers, removedServers) = RpnProxyManager.refreshWinServers()

                if (removedServers.isNotEmpty()) {
                    Logger.w(
                        LOG_TAG_UI,
                        "$TAG.onResume: ${removedServers.size} selected servers removed"
                    )
                    uiCtx {
                        if (!isAdded || requireActivity().isFinishing) return@uiCtx
                        val removedNames = removedServers.joinToString(", ") { it.countryName }
                        Logger.i(LOG_TAG_UI, "$TAG.onResume: notifying removal of: $removedNames")
                        try {
                            showServerRemovalNotifBottomSheet(removedServers, refreshedServers)
                        } catch (e: Exception) {
                            Logger.e(
                                LOG_TAG_UI,
                                "$TAG.onResume: error showing bottom sheet: ${e.message}",
                                e
                            )
                            if (isAdded && refreshedServers.isNotEmpty()) {
                                io {
                                    val sel = try { RpnProxyManager.getEnabledConfigs() } catch (_: Exception) { emptySet() }
                                    uiCtx { initServers(refreshedServers, sel) }
                                }
                            }
                        }
                    }
                } else if (refreshedServers.isNotEmpty()) {
                    Logger.v(
                        LOG_TAG_UI,
                        "$TAG.onResume: refreshed ${refreshedServers.size} servers, nothing removed"
                    )
                    // Sync isFavourite from the freshly-loaded (DB-backed) server list into the
                    // in-memory allServers.  Without this, any favourite toggled during a previous
                    // visit to the screen would be invisible after navigating away and back because
                    // allServers is never reloaded when no servers are removed.
                    uiCtx {
                        if (!isAdded || isLoading) return@uiCtx
                        val freshById = refreshedServers.associateBy { it.id }
                        var anyChanged = false
                        allServers.forEach { config ->
                            freshById[config.id]?.let { fresh ->
                                if (config.isFavourite != fresh.isFavourite) {
                                    config.isFavourite = fresh.isFavourite
                                    anyChanged = true
                                }
                            }
                        }
                        // Only rebuild the list when something actually changed to avoid an
                        // unnecessary DiffUtil pass on every resume.
                        if (anyChanged) {
                            refreshUnselectedList()
                        }
                    }
                }
            }
        }

    }

    private fun showServerRemovalNotifBottomSheet(
        removedServers: List<CountryConfig> = emptyList(),
        refreshedServers: List<CountryConfig> = emptyList(),
        selectedList: Set<CountryConfig> = emptySet()
    ) {
        val bs = ServerRemovalNotificationBottomSheet.newInstance(removedServers)
        bs.setOnDismissCallback {
            if (!isAdded || refreshedServers.isEmpty()) return@setOnDismissCallback
            io {
                val freshSelected = try {
                    RpnProxyManager.getEnabledConfigs().ifEmpty { selectedList }
                } catch (_: Exception) {
                    selectedList
                }
                uiCtx { initServers(refreshedServers, freshSelected) }
            }
        }
        bs.show(parentFragmentManager, "ServerRemovalNotification")
    }

    override fun onDestroyView() {
        // Cancel animations before the binding is torn down
        runCatching {
            fabLoadingAnimator?.cancel()
            fabLoadingAnimator = null
            blinkAnimator?.cancel()
            blinkAnimator = null
            ui.fabStopProxy.animate().cancel()
            ui.fabStartProxy.animate().cancel()
            ui.statusIndicator.animate().cancel()
            ui.statusCard.animate().cancel()
            ui.searchCard.animate().cancel()
        }
        statusUpdateJob?.cancel()
        statusUpdateJob = null
        tunnelWatchJob?.cancel()
        tunnelWatchJob = null
        refreshSwimAnimator?.cancel()
        refreshSwimAnimator = null
        connectArcAnimator?.cancel()
        connectArcAnimator = null
        dismissServerLoadingDialog()
        dismissRpnResetDialog()
        super.onDestroyView()
    }

    /** Slides/fades a section in; hidden views are skipped unless [forceShow]. */
    private fun animateSectionIn(view: RpnControlState, delayMs: Long = 0L, forceShow: Boolean = false) {
        if (!isAdded) return
        if (!view.isVisible && !forceShow) return
        view.alpha = 0f
        view.translationY = 12f
        view.isVisible = true
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(delayMs)
            .setDuration(CONTENT_FADE_IN_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun setLoadingState(loading: Boolean, skipHeader: Boolean = false) {
        if (!isAdded) return
        val wasLoading = isLoading
        isLoading = loading

        if (loading) {
            // Header shimmer
            if (!skipHeader) {
                ui.shimmerHeader.isVisible = true
                ui.shimmerHeader.startShimmer()
                ui.locationContent.isVisible = false
            } else {
                ui.shimmerHeader.stopShimmer()
                ui.shimmerHeader.isVisible = false
                ui.locationContent.isVisible = true
            }

            // hide real list and hint cards
            ui.shimmerServerList.isVisible = true
            ui.shimmerServerList.startShimmer()
            ui.rvServers.isVisible = false
            ui.emptySelectionCard.isVisible = false
            ui.rvSelectedServers.isVisible = false
            ui.frequentCountriesSection.isVisible = false
            ui.locationCapacityIndicator.isVisible = false
            ui.errorStateContainer.isVisible = false

            // Disable search bar and action icons while data is loading
            setSearchAndActionsEnabled(false)
            // Pull-to-refresh is meaningless during the initial load.
            ui.swipeRefresh.isEnabled = false
            ui.swipeRefresh.isRefreshing = false
        } else {
            // Stop and hide header shimmer, reveal real content
            ui.shimmerHeader.stopShimmer()
            ui.shimmerHeader.isVisible = false
            ui.locationContent.isVisible = true

            // Stop and hide list shimmer, reveal real list
            ui.shimmerServerList.stopShimmer()
            ui.shimmerServerList.isVisible = false
            ui.rvServers.isVisible = true

            // Coordinated settle cascade; silent reloads skip it.
            if (wasLoading) {
                animateSectionIn(ui.locationContent, forceShow = true)
                animateSectionIn(ui.rvSelectedServers, CONTENT_STAGGER_MS)
                animateSectionIn(ui.rvServers, CONTENT_STAGGER_MS, forceShow = true)
            }

            // Re-enable search bar and action icons once data is ready
            setSearchAndActionsEnabled(true)
            ui.swipeRefresh.isEnabled = true
        }
    }

    /**
     * Enables or disables the search bar and the action icon button (settingsBtn)
     * that sit beside it.  Both the visual state (alpha) and input state
     * (isEnabled / isFocusable) are updated so that the views are clearly non-interactive
     * while the fragment is loading or resetting.
     */
    private fun setSearchAndActionsEnabled(enabled: Boolean) {
        if (!isAdded) return
        val alpha = if (enabled) 1f else 0.5f
        ui.searchCard.alpha = alpha
        ui.searchCard.isEnabled = enabled
        ui.searchBar.isEnabled = enabled
        ui.searchBar.isFocusable = enabled
        ui.settingsBtn.alpha = alpha
        ui.settingsBtn.isEnabled = enabled
        ui.searchFilterBtn.alpha = alpha
        ui.searchFilterBtn.isEnabled = enabled
        setQuickSettingsEnabled(enabled)
    }

    private fun initServers(servers: List<CountryConfig>, selectedList: Set<CountryConfig> = emptySet()) {
        io {
            Logger.v(LOG_TAG_UI, "$TAG.initServers: ${servers.size} servers, selected=${selectedList.size}, WIN=$isWinRegistered")

            val hasRealServers = servers.any { it.id != AUTO_SERVER_ID }

            if (!hasRealServers) {
                uiCtx {
                    if (!isAdded) return@uiCtx
                    serverSelectionViewModel.dashboardSnapshot = null
                    setLoadingState(false)
                    showEmptyState()
                }
                Logger.w(LOG_TAG_UI, "$TAG.initServers: no real servers available (hasRealServers=false, total=${servers.size})")
                return@io
            }

            uiCtx { hideErrorState() }

            if (isWinRegistered) {
                autoServer = RpnProxyManager.getAutoServer()
                if (autoServer == null) {
                    Logger.w(LOG_TAG_UI, "$TAG.initServers: AUTO not in DB, creating")
                    RpnProxyManager.ensureAutoServerExists()
                    autoServer = RpnProxyManager.getAutoServer()
                }
                // always activate AUTO in the backend regardless of prior state
                autoServer?.let { auto ->
                    if (!auto.isEnabled) {
                        auto.isEnabled = true
                        RpnProxyManager.updateAutoServerState(auto)
                        Logger.i(LOG_TAG_UI, "$TAG.initServers: AUTO was disabled, re-enabling")
                    }
                }
            }

            val localSelected = mutableListOf<CountryConfig>()
            // Keys where the WIN tunnel is not yet set up (getWinByKey returned null).
            val pendingTunnelKeys = mutableSetOf<String>()

            // AUTO is always first in the selected list
            autoServer?.let { auto ->
                auto.isActive  = true
                auto.isEnabled = true
                localSelected.add(auto)
            }

            if (selectedList.isNotEmpty()) {
                val selected = selectedList.map { it.key }
                val filtered = servers.filter { it.key in selected }
                // Exclude AUTO; it is always handled explicitly above and must never
                // appear twice in localSelected (treat the list as a set for AUTO).
                val groupedByKey = filtered
                    .filter { it.isActive && it.id != AUTO_SERVER_ID }
                    .groupBy { it.key }
                groupedByKey.forEach { (key, serversWithSameKey) ->
                    // Skip if already at the non-AUTO limit
                    val nonAutoCount = localSelected.count { it.id != AUTO_SERVER_ID }
                    if (nonAutoCount >= MAX_SELECTIONS) return@forEach
                    val best = serversWithSameKey.minByOrNull { it.load } ?: serversWithSameKey.first()

                    if (VpnController.getWinByKey(Backend.RpnWin + key) == null) {
                        Logger.w(LOG_TAG_UI, "$TAG.initServers: WIN tunnel for key=$key not yet available (still setting up)")
                        pendingTunnelKeys.add(key)
                    }
                    localSelected.add(best)
                }
            }

            val localUnselected = servers.filter { server ->
                server.isActive && localSelected.none { it.key == server.key }
            }.toMutableList()

            Logger.v(LOG_TAG_UI, "$TAG.initServers: selected=${localSelected.size} " +
                    "(AUTO=${localSelected.any { it.id == AUTO_SERVER_ID }}), " +
                    "unselected=${localUnselected.size}")

            uiCtx {
                if (!isAdded) return@uiCtx

                allServers.clear()
                allServers.addAll(servers)
                selectedServers.clear()
                selectedServers.addAll(localSelected)
                unselectedServers.clear()
                unselectedServers.addAll(localUnselected)

                // a server-list (re)load is not a user action: sync the
                // capacity scale silently; the pop animation arms again after
                lastFilledCapacity = -1
                updateHeaderSummary()
                selectedAdapter.updateServers(selectedServers)
                serverAdapter.updateCountries(buildCountries(unselectedServers))
                updateAllServersCount()
                updateSelectedSectionVisibility()
                serverSelectionViewModel.dashboardSnapshot =
                    ServerSelectionViewModel.DashboardSnapshot(
                        servers = servers.toList(),
                        selected = selectedServers.toSet(),
                        isWinRegistered = isWinRegistered
                    )
                setLoadingState(false)
                // isLoading must be false before the summary refresh so the
                // location-capacity scale becomes visible with the loaded data.
                updateVpnStatus()
                refreshRelayTileState()
                // Re-apply stopped UI on top of fully-loaded state
                if (isProxyStopped) applyProxyStoppedUi()
                // Notify adapter which server items are still waiting for tunnel setup,
                // then start a short-lived polling job to clear them as they come up.
                selectedAdapter.setLoadingTunnelKeys(pendingTunnelKeys)
                if (pendingTunnelKeys.isNotEmpty()) startTunnelWatchJob(pendingTunnelKeys)
                // Show frequently-selected countries as quick-pick chips
                if (!isProxyStopped) loadAndShowFrequentChips()
            }
            Logger.v(LOG_TAG_UI, "$TAG.initServers: complete")
        }
    }

    private fun setupHeaderUI() {
        statusUpdateJob?.cancel()
        statusUpdateJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                delay(3_000.milliseconds)
                if (isAdded && view != null) {
                    runCatching {
                        updateConnectionStatusOnly()
                        if (!isLoading) updateConnectionDuration()
                    }.onFailure {
                        Logger.w(LOG_TAG_UI, "$TAG.setupHeaderUI: status poll failed: ${it.message}")
                    }
                }
            }
        }
        VpnController.connectionStatus.observe(viewLifecycleOwner) {
            if (isAdded && view != null) {
                runCatching { updateConnectionStatusOnly() }
                    .onFailure { Logger.w(LOG_TAG_UI, "$TAG: status observer failed: ${it.message}") }
            }
        }
    }

    /** Updates only the connection chip (colored dot + label). Safe to call frequently. */
    private fun updateConnectionStatusOnly() {
        if (!isAdded) return
        updateConnectionStatus(deriveConnectionUiState())
    }

    /** Derives the correct [ConnectionUiState] from live VPN adapter state. */
    private fun deriveConnectionUiState(): ConnectionUiState {
        if (isProxyStopped) return ConnectionUiState.DISCONNECTED
        // If the registration polling job is active, we are in the REGISTERING state.
        if (serverLoadingJob?.isActive == true) return ConnectionUiState.REGISTERING

        val vpnState = VpnController.state()
        return when {
            vpnState.on -> ConnectionUiState.CONNECTED
            vpnState.connectionState == BraveVPNService.State.NEW -> ConnectionUiState.CONNECTING
            vpnState.activationRequested && vpnState.connectionState != null && !vpnState.on ->
                ConnectionUiState.CONNECTING
            else -> ConnectionUiState.DISCONNECTED
        }
    }

    /**
     * Full header refresh: connection status + hero summary derived from [selectedServers].
     * Only called after data is loaded (not during loading).
     */
    private fun updateVpnStatus() {
        if (!isAdded) return
        updateConnectionStatus(deriveConnectionUiState())
        updateHeaderSummary()
        updateConnectionDuration()
    }

    /**
     * Refreshes the "Active • 2 min ago" label shown beside the header status dot.
     * Uses the same relative-time presentation as HomeScreenFragment's
     * active-since label ("10 min ago", "2 hrs ago", …).
     */
    private fun updateConnectionDuration() {
        if (!isAdded) return
        io {
            try {
                val stats = VpnController.getProxyStats(Backend.RpnWin)
                uiCtx {
                    if (!isAdded) return@uiCtx
                    val since = stats?.since ?: 0L
                    if (since <= 0L) {
                        ui.tvActiveDuration.text = ""
                        return@uiCtx
                    }
                    // returns a string describing 'since' as a time relative to 'now'
                    val relative = DateUtils.getRelativeTimeSpanString(
                        since,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS,
                        DateUtils.FORMAT_ABBREV_RELATIVE
                    )
                    ui.tvActiveDuration.text = getString(
                        R.string.two_argument_space,
                        getString(R.string.lbl_separator_dot),
                        relative.toString()
                    )
                }
            } catch (e: Exception) {
                Logger.w(LOG_TAG_UI, "$TAG.updateConnectionDuration: ${e.message}")
            }
        }
    }

    /**
     * Shows or hides tunnel-dependent controls based on the live tunnel state.
     */
    private fun applyTunnelGatedVisibility() {
        if (!isAdded) return
        val tunnelUp = VpnController.hasTunnel()
        ui.quickSettingsRow.isVisible = tunnelUp
        ui.searchCard.isVisible = tunnelUp
    }

    private fun updateConnectionStatus(uiState: ConnectionUiState) {
        if (!isAdded) return
        applyTunnelGatedVisibility()
        when (uiState) {
            ConnectionUiState.CONNECTED -> {
                ui.tvConnectionStatus.text = getString(R.string.lbl_active)
                // attr-based lookup so every app theme variant supplies its own accent
                ui.tvConnectionStatus.setTextColor(resolveAttrColor(R.attr.chipTextPositive))
                ui.statusIndicator.backgroundTintList =
                    ColorStateList.valueOf(resolveAttrColor(R.attr.chipTextPositive))
                ui.tvActiveDuration.alpha = 1f
                startStatusBlink()
                // a fresh CONNECTED transition (after CONNECTING/REGISTERING) pulses
                // the dot once; repeat calls from the status poller stay silent
                if (lastConnectionUiState == ConnectionUiState.CONNECTING ||
                    lastConnectionUiState == ConnectionUiState.REGISTERING
                ) {
                    pulseStatusDot()
                }
            }
            ConnectionUiState.CONNECTING -> {
                ui.tvConnectionStatus.text = getString(R.string.lbl_connecting)
                ui.tvConnectionStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorAmber_900))
                ui.statusIndicator.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.colorAmber_900)
                stopStatusBlink()
                // Pulse animation to indicate in-progress state
                ui.statusIndicator.animate().scaleX(1.2f).scaleY(1.2f).setDuration(600).withEndAction {
                    if (isAdded) ui.statusIndicator.animate().scaleX(0.8f).scaleY(0.8f).setDuration(600).withEndAction {
                        if (isAdded) ui.statusIndicator.animate().scaleX(1f).scaleY(1f).setDuration(300).start()
                    }.start()
                }.start()
            }
            ConnectionUiState.REGISTERING -> {
                ui.tvConnectionStatus.text = getString(R.string.rpn_restore_dialog_status_registering)
                ui.tvConnectionStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorAmber_900))
                ui.statusIndicator.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.colorAmber_900)
                ui.tvActiveDuration.alpha = 1f
                startStatusBlink()
            }
            ConnectionUiState.FAILED -> {
                ui.tvConnectionStatus.text = getString(R.string.ping_status_failed)
                ui.tvConnectionStatus.setTextColor(resolveAttrColor(R.attr.accentBad))
                ui.statusIndicator.backgroundTintList =
                    ColorStateList.valueOf(resolveAttrColor(R.attr.accentBad))
                stopStatusBlink()
                ui.tvActiveDuration.text = ""
            }
            ConnectionUiState.DISCONNECTED -> {
                ui.tvConnectionStatus.text = getString(R.string.lbl_inactive)
                ui.tvConnectionStatus.setTextColor(resolveAttrColor(R.attr.accentBad))
                ui.statusIndicator.backgroundTintList =
                    ColorStateList.valueOf(resolveAttrColor(R.attr.accentBad))
                stopStatusBlink()
            }
        }
        lastConnectionUiState = uiState
    }

    /** Starts a gentle repeating alpha blink on the header status dot. */
    private fun startStatusBlink() {
        if (!isAdded) return
        if (blinkAnimator?.isRunning == true) return
        ui.statusIndicator.alpha = 1f
        blinkAnimator = ObjectAnimator.ofFloat(ui.statusIndicator, "alpha", 1f, 0.25f).apply {
            duration = 2000L
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            start()
        }
    }

    /** Stops the status-dot blink and restores full opacity. */
    private fun stopStatusBlink() {
        blinkAnimator?.cancel()
        blinkAnimator = null
        if (isAdded) ui.statusIndicator.alpha = 1f
    }

    /**
     * Refreshes the premium hero summary: connected-location count, overlapping
     * country avatars, tier/ID block and the location-capacity scale.
     */
    private fun updateHeaderSummary() {
        if (!isAdded) return
        ui.locationContent.visibility = View.VISIBLE

        val nonAutoServers = selectedServers.filter { !it.id.equals(AUTO_SERVER_ID, ignoreCase = true) }
        val distinctCountries = nonAutoServers.distinctBy { it.cc }

        populateAvatarRow(distinctCountries)
        updateCapacityIndicator()
    }

    /**
     * Rebuilds the overlapping circular avatar strip. Each circle shows the country
     * flag emoji as its background with the ISO country code overlaid as foreground.
     */
    private fun populateAvatarRow(countries: List<CountryConfig>) {
        if (!isAdded) return
        ui.avatars = countries.take(MAX_SELECTIONS)
    }

    private fun loadRpnHeatmap() {
        rpnHeatmapJob?.cancel()
        rpnHeatmapJob = io {
            try {
                val proxyIdFilter = Backend.RpnWin + "%"
                val now = System.currentTimeMillis()
                val rangeEnd = (now / RPN_HEATMAP_BUCKET_MS + 1) * RPN_HEATMAP_BUCKET_MS
                val rangeStart = rangeEnd - RPN_HEATMAP_WINDOW_MS
                coroutineScope {
                    val dnsRows = async {
                        dnsLogDAO.getRpnActivityBuckets(
                            proxyIdFilter, rangeStart, rangeEnd, RPN_HEATMAP_BUCKET_MS
                        )
                    }
                    val connRows = async {
                        connectionTrackerDAO.getRpnActivityBuckets(
                            proxyIdFilter, rangeStart, rangeEnd, RPN_HEATMAP_BUCKET_MS
                        )
                    }
                    val rlogRows = async {
                        rethinkLogDao.getRpnActivityBuckets(
                            proxyIdFilter, rangeStart, rangeEnd, RPN_HEATMAP_BUCKET_MS
                        )
                    }

                    val counts = LongArray(RPN_HEATMAP_SLOTS)
                    for (rows in listOf(dnsRows, connRows, rlogRows)) {
                        rows.await().forEach { row ->
                            val idx = row.bucketIndex.toInt()
                            if (idx in counts.indices) counts[idx] += row.total
                        }
                    }
                    uiCtx {
                        heatmapWindowEndMs = rangeEnd
                        renderRpnHeatmap(counts)
                    }
                }
            } catch (e: Exception) {
                Logger.w(LOG_TAG_UI, "$TAG.loadRpnHeatmap failed: ${e.message}")
            }
        }
    }

    /**
     * Renders the RPN activity wall: [RPN_HEATMAP_SLOTS] dots where each
     * COLUMN is one clock hour ([RPN_HEATMAP_ROWS_PER_HOUR] dots per column,
     * one per 10-minute interval). Chronological order: oldest hour in the
     * leftmost column, current hour in the rightmost column; within a column
     * the :00 interval is at the top. GridLayout fills children row-major, so
     * each cell is given its explicit (column=hour, row=interval) position:
     * columns are weighted so the wall spans the full card width. Intensity
     * follows the same logarithmic scale as HomeScreenFragment's activity
     * wall; empty intervals render a small, faint placeholder dot so the grid
     * geometry stays stable.
     */
    private fun renderRpnHeatmap(counts: LongArray) {
        if (!isAdded || view == null) return
        ui.heatmap = counts.copyOf()
    }

    // logarithmic scale so skewed traffic distributions stay visually
    // distinguishable (1-9 -> 1, 10-99 -> 2, 100-999 -> 3, >=1000 -> 4);
    // zero always maps to the empty/placeholder dot (mirrors HomeScreenFragment)
    private fun rpnHeatmapIntensityLevel(count: Long): Int {
        if (count <= 0L) return 0
        return min(RPN_HEATMAP_INTENSITY_LEVELS - 1, log10(count.toDouble()).toInt() + 1)
    }

    /**
     * Tapping a heat-map cell opens [RpnLogActivityIntervalBottomSheet] on
     * that exact 10-minute interval. The touch position is captured by the
     * touch listener (returning false so the click still fires); the column
     * resolves to a clock hour and the row to the 10-minute interval within
     * that hour (both evenly weighted, same approach as HomeScreenFragment's
     * activity wall).
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupRpnHeatmapClicks() {
        ui.onHeatmapTap = { x, y ->
            lastHeatmapTouchX = x
            lastHeatmapTouchY = y
            openRpnHeatmapDetails()
        }
    }

    private fun openRpnHeatmapDetails() {
        if (!isAdded) return
        if (heatmapWindowEndMs <= 0L) return
        val grid = ui.rpnHeatmapGrid
        if (grid.width <= 0 || grid.height <= 0) return

        // GridLayout mirrors column order in RTL (oldest hour renders on the
        // right), so mirror the tap's x-position before resolving the column
        val x = if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            grid.width - lastHeatmapTouchX
        } else {
            lastHeatmapTouchX
        }

        val col = ((x / grid.width) * RPN_HEATMAP_HOURS)
            .toInt().coerceIn(0, RPN_HEATMAP_HOURS - 1)
        val row = ((lastHeatmapTouchY / grid.height) * RPN_HEATMAP_ROWS_PER_HOUR)
            .toInt().coerceIn(0, RPN_HEATMAP_ROWS_PER_HOUR - 1)
        val startMs = heatmapWindowEndMs - RPN_HEATMAP_WINDOW_MS +
            (col * RPN_HEATMAP_ROWS_PER_HOUR + row) * RPN_HEATMAP_BUCKET_MS

        val sheet = RpnLogActivityIntervalBottomSheet.newInstance(
            startMs,
            startMs + RPN_HEATMAP_BUCKET_MS
        )
        sheet.show(parentFragmentManager, RpnLogActivityIntervalBottomSheet.TAG)
    }

    private fun isLightTheme(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) !=
            Configuration.UI_MODE_NIGHT_YES

    /**
     * Updates the minimalist "N of M" capacity indicator: the capacity
     * pills below the connection list mirror the number of active
     * (non-AUTO) locations visually.
     */
    private fun updateCapacityIndicator() {
        if (!isAdded) return
        val filled = selectedServers.count { !it.id.equals(AUTO_SERVER_ID, ignoreCase = true) }
            .coerceIn(0, MAX_SELECTIONS)
        if (!isLoading && !isProxyStopped) {
            if (!ui.locationCapacityIndicator.isVisible) {
                animateSectionIn(ui.locationCapacityIndicator, forceShow = true)
            }
        } else {
            ui.locationCapacityIndicator.isVisible = false
        }
        val dots = listOf(
            ui.capacityDotOne, ui.capacityDotTwo, ui.capacityDotThree,
            ui.capacityDotFour, ui.capacityDotFive
        )
        dots.forEachIndexed { index, dot ->
            if (index < filled) {
                dot.alpha = 1f
                dot.backgroundTintList =
                    ColorStateList.valueOf(resolveAttrColor(R.attr.accentGood))
            } else {
                // Theme-aware "empty" tint: white is invisible on the light theme's
                // background, so use the adaptive on-surface-variant color instead.
                dot.alpha = 0.25f
                dot.backgroundTintList =
                    ColorStateList.valueOf(resolveAttrColor(R.attr.primaryLightColorText))
            }
        }
        // Pop the newly-filled pill when a location was added (never on load,
        // removal, or when the scale itself is hidden). lastFilledCapacity is
        // -1 right after a (re)load, suppressing the pop until the next user
        // action changes the count.
        if (lastFilledCapacity in 0..<filled &&
            filled in 1..dots.size &&
            ui.locationCapacityIndicator.isVisible &&
            !isLoading
        ) {
            popCapacityDot(dots[filled - 1])
            emitCapacityBubbles(dots[filled - 1])
        }
        lastFilledCapacity = filled
    }

    /** Small overshoot pop on a capacity pill that just filled. */
    private fun popCapacityDot(dot: RpnControlState) {
        if (isReducedMotionPreferred()) return
        dot.animate().cancel()
        dot.scaleX = 0.4f
        dot.scaleY = 0.4f
        dot.animate()
            .scaleX(1f).scaleY(1f)
            .setDuration(260)
            .setInterpolator(OvershootInterpolator(1.6f))
            .start()
    }

    /**
     * Emits a couple of tiny accent bubbles that drift up and fade from a
     * just-filled capacity pill.
     */
    private fun emitCapacityBubbles(dot: RpnControlState) {
        if (isReducedMotionPreferred()) return
        val overlay = ui.fxOverlay
        if (overlay.width <= 0 || overlay.height <= 0) return
        val color = resolveAttrColor(R.attr.accentGood)
        val loc = IntArray(2)
        dot.getLocationInWindow(loc)
        val rootLoc = IntArray(2)
        overlay.getLocationInWindow(rootLoc)
        val baseX = loc[0] + dot.width / 2f - rootLoc[0]
        val baseY = (loc[1] - rootLoc[1]).toFloat()

        repeat(CAPACITY_BUBBLE_COUNT) { i ->
            val bubble = RpnControlState().apply {
                setBackgroundColor(color)
                alpha = 0.7f
            }
            val size = dpPx(4)
            bubble.width = size; bubble.height = size
            overlay.children.add(bubble)
            bubble.translationX = baseX - size / 2f + (i - (CAPACITY_BUBBLE_COUNT - 1) / 2f) * dpPx(6)
            bubble.translationY = baseY
            bubble.animate()
                .translationY(baseY - dpPx(14))
                .alpha(0f)
                .setStartDelay(60L * i)
                .setDuration(520)
                .withEndAction { if (isAdded) overlay.children.remove(bubble) }
                .start()
        }
    }

    private fun animateHeaderEntry() {
        if (!isAdded) return
        ui.statusCard.alpha = 0f
        ui.statusCard.scaleX = 0.9f
        ui.statusCard.scaleY = 0.9f
        ui.statusCard.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(400)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    // --- dolphin fx (connect arc, refresh swim, capacity pop) ---

    private fun dpPx(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    /**
     * Light physical confirmation for accepted state-changing actions
     * (relay toggle, location selection). No-op when no view is attached.
     */
    private fun hapticTap() {
        view?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    /**
     * Connect celebration: a small dolphin swims a shallow arc from the top of
     * the selected-locations list (where the new location card just landed) up
     * to the hero status dot, which pulses on arrival. Never plays under
     * reduced motion, and a still-running arc is replaced, never queued.
     */
    private fun playConnectArc() {
        if (!isAdded || view == null) return
        if (isReducedMotionPreferred()) return
        val overlay = ui.fxOverlay
        if (overlay.width <= 0 || overlay.height <= 0) return

        connectArcAnimator?.cancel()

        val rootLoc = IntArray(2)
        overlay.getLocationInWindow(rootLoc)
        val from = IntArray(2)
        ui.rvSelectedServers.getLocationInWindow(from)
        val to = IntArray(2)
        ui.statusIndicator.getLocationInWindow(to)

        val startX = from[0] + ui.rvSelectedServers.width / 2f - rootLoc[0]
        val startY = (from[1] + dpPx(28) - rootLoc[1]).toFloat()
        val endX = to[0] + ui.statusIndicator.width / 2f - rootLoc[0]
        val endY = (to[1] + ui.statusIndicator.height / 2f - rootLoc[1]).toFloat()

        val dolphin = RpnControlState().apply {
            setImageResource(R.drawable.dolphin_secure)
            width = dpPx(28); height = dpPx(22)
            alpha = 0f
        }
        overlay.children.add(dolphin)
        dolphin.translationX = startX
        dolphin.translationY = startY

        // control point sits above the straight-line midpoint so the travel
        // reads as a leap rather than a slide
        val swimPath = Path().apply {
            moveTo(startX, startY)
            quadTo(
                (startX + endX) / 2f,
                minOf(startY, endY) - dpPx(56),
                endX,
                endY
            )
        }
        val swim = ObjectAnimator.ofFloat(
            dolphin,
            "translationX",
            "translationY",
            swimPath
        ).apply {
            duration = CONNECT_ARC_DURATION_MS
            interpolator = PathInterpolator(0.2f, 0.7f, 0.3f, 1f)
        }
        val fadeIn = ObjectAnimator.ofFloat(dolphin, "alpha", 0f, 0.9f).apply {
            duration = CONNECT_ARC_DURATION_MS / 3
        }

        connectArcAnimator = AnimatorSet().apply {
            playTogether(swim, fadeIn)
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    connectArcAnimator = null
                    if (isAdded) overlay.children.remove(dolphin)
                    if (!cancelled && isAdded) pulseStatusDot()
                }
            })
            start()
        }
    }

    /** One-shot pulse on the hero status dot (arrival beat of the connect arc). */
    private fun pulseStatusDot() {
        if (!isAdded || isReducedMotionPreferred()) return
        ui.statusIndicator.animate().cancel()
        ui.statusIndicator.scaleX = 1f
        ui.statusIndicator.scaleY = 1f
        ui.statusIndicator.animate()
            .scaleX(1.9f).scaleY(1.9f)
            .setDuration(140)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                if (isAdded) {
                    ui.statusIndicator.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(200)
                        .setInterpolator(OvershootInterpolator(1.2f))
                        .start()
                }
            }
            .start()
    }

    /**
     * While a pull-to-refresh is in flight, a random dolphin swims repeated
     * left-to-right passes with a gentle bob, just below the hero card.
     */
    private fun startRefreshSwim() {
        if (!isAdded || view == null) return
        if (isReducedMotionPreferred()) return
        if (refreshSwimAnimator?.isRunning == true) return
        val overlay = ui.fxOverlay
        if (overlay.width <= 0 || overlay.height <= 0) return

        val dolphin = RpnControlState().apply {
            setImageResource(EmbeddedDolphinContent.DOLPHINS.random())
            width = dpPx(36); height = dpPx(30)
            alpha = 0.75f
        }
        overlay.children.add(dolphin)

        val heroLoc = IntArray(2)
        ui.statusCard.getLocationInWindow(heroLoc)
        val rootLoc = IntArray(2)
        overlay.getLocationInWindow(rootLoc)
        val baseY = (heroLoc[1] + ui.statusCard.height - rootLoc[1]) + dpPx(10)
        val fromX = -dpPx(40).toFloat()
        val toX = overlay.width + dpPx(40).toFloat()

        dolphin.translationY = baseY.toFloat()
        val travel = ObjectAnimator.ofFloat(dolphin, "translationX", fromX, toX).apply {
            duration = REFRESH_SWIM_DURATION_MS
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.RESTART
            interpolator = LinearInterpolator()
        }
        val bob = ObjectAnimator.ofFloat(
            dolphin,
            "translationY",
            baseY.toFloat(),
            (baseY - dpPx(6)).toFloat()
        ).apply {
            duration = 500
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
        }
        refreshSwimAnimator = AnimatorSet().apply {
            playTogether(travel, bob)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    refreshSwimAnimator = null
                    if (isAdded) overlay.children.remove(dolphin)
                }
            })
            start()
        }
    }

    /** Stops the pull-to-refresh swim; cancelling removes the dolphin. */
    private fun stopRefreshSwim() {
        refreshSwimAnimator?.cancel()
        refreshSwimAnimator = null
    }

    private fun setupNavigationButtons() {
        ui.supportBtn.setOnClickListener { openAccount() }
        ui.settingsBtn.setOnClickListener { showServerSettingsBottomSheet() }
        ui.fabStopProxy.setOnClickListener  { onToggleProxyFabClicked() }
        ui.fabStartProxy.setOnClickListener { onToggleProxyFabClicked() }
        // Status chip: open settings when running, show a hint when stopped
        ui.statusChip.setOnClickListener {
            if (isProxyStopped) {
                showToast(getString(R.string.server_settings_proxy_stopped))
            } else {
                showServerSettingsBottomSheet()
            }
        }
    }

    private fun openAccount() {
        if (!isAdded || isStateSaved) return
        val hasPurchase = RpnProxyManager.getSubscriptionData()
            ?.subscriptionStatus
            ?.purchaseToken
            ?.isNotEmpty() == true
        if (hasPurchase) {
            val intent = FragmentHostActivity.createIntent(
                context = requireContext(),
                fragmentClass = RethinkPlusDashboardFragment::class.java,
                args = RethinkPlusDashboardFragment.createBundle(showManagePurchase = false)
            )
            startActivity(intent)
        } else {
            openHelpAndSupport()
        }
    }

    private fun focusLocationSearch() {
        if (!isAdded || !ui.searchCard.isEnabled) return
        ui.focusSearchRequest++
    }

    /**
     * Quick settings row below the hero banner: Relay (toggle), Add location,
     * Bypass apps and Stats. Mirrors the Android quick-settings tile look.
     */
    private fun setupQuickSettings() {
        ui.qsRelayTile.setOnClickListener { onRelayQuickSettingClicked() }
        ui.qsBypassAppsTile.setOnClickListener { openRpnBypassApps() }
        refreshQuickSettingCaptions()
    }

    private fun setupStatsChip() {
        ui.qsStatsTile.setOnClickListener { showStatsSheet() }
        refreshStatsChipState()
    }

    /** Stats sheet over the same window the Stats chip counts. */
    private fun showStatsSheet() {
        if (!isAdded || isStateSaved) return
        if (parentFragmentManager.findFragmentByTag(RpnStatsBottomSheet.TAG) != null) return
        RpnStatsBottomSheet.newInstance(PULSE_STATS_WINDOW_MS)
            .show(parentFragmentManager, RpnStatsBottomSheet.TAG)
    }

    /**
     * Center the pills when they fit; start-align when they overflow so the
     * scroll view can still reach the first pill.
     */


    /** Compact RPN connection count for the Stats chip (e.g. "812", "2.4K"). */
    private fun refreshStatsChipState() {
        io {
            val since = System.currentTimeMillis() - PULSE_STATS_WINDOW_MS
            val connCount = try {
                connectionTrackerDAO.countConnectionsByProxyPrefix(Backend.RpnWin, since)
            } catch (e: Exception) {
                Logger.w(LOG_TAG_UI, "$TAG.refreshStatsChipState: ${e.message}")
                0
            }
            uiCtx {
                if (!isAdded) return@uiCtx
                ui.qsStatsState.text = formatCompactCount(connCount)
            }
        }
    }

    /** Compact count: "812", "2.4K", "12.0K", "3.4M". */
    private fun formatCompactCount(count: Int): String = when {
        count >= 1_000_000 -> String.format(Locale.US, "%.1fM", count / 1_000_000.0)
        count >= 1_000 -> String.format(Locale.US, "%.1fK", count / 1_000.0)
        else -> count.toString()
    }

    /**
     * Refreshes all data-driven quick-settings captions: relay count
     * (n/m locations), location capacity (n/m), bypass apps
     * (not-bypassed/total) and live RPN connection count.
     */
    private fun refreshQuickSettingCaptions() {
        refreshRelayTileState()
        updateCapacityIndicator()
        refreshBypassAppsTileState()
        refreshStatsChipState()
    }

    /**
     * Refreshes the bypass-apps tile caption with "<not-bypassed>/<total>"
     * (e.g. "345/462") computed from the installed-apps DB snapshot.
     */
    private fun refreshBypassAppsTileState() {
        io {
            val apps = try {
                appInfoRepository.getAppInfo()
            } catch (e: Exception) {
                Logger.w(LOG_TAG_UI, "$TAG.refreshBypassAppsTileState: ${e.message}")
                emptyList()
            }
            val total = apps.size
            val bypassed = apps.count { it.isProxyExcluded }
            uiCtx {
                if (!isAdded) return@uiCtx
                ui.qsBypassAppsState.text =
                    String.format(Locale.US, "%d/%d", bypassed, total)
            }
        }
    }

    /**
     * Re-derives the relay tile state from the enabled locations: the caption
     * shows "<relay-on>/<total>" (e.g. "0/3", "2/3", "5/5") and the tile is
     * highlighted only when **all** enabled non-AUTO locations have hop
     * (relay) enabled.
     */
    private fun refreshRelayTileState() {
        io {
            val enabledNonAuto = try {
                RpnProxyManager.getEnabledConfigs()
                    .filter { !it.id.equals(AUTO_SERVER_ID, ignoreCase = true) }
            } catch (e: Exception) {
                Logger.w(LOG_TAG_UI, "$TAG.refreshRelayTileState: ${e.message}")
                emptyList()
            }
            val relayOnCount = enabledNonAuto.count { it.hopEnabled }
            val totalCount = enabledNonAuto.size
            val allOn = totalCount > 0 && relayOnCount == totalCount
            uiCtx {
                if (!isAdded) return@uiCtx
                isRelayAllOn = allOn
                applyRelayTileUi(relayOnCount, totalCount)
            }
        }
    }

    /**
     * Applies the relay tile caption ("n/m") and colours. The tile is
     * highlighted (positive colours) only when every enabled non-AUTO
     * location has relay on; otherwise it stays dim.
     */
    private fun applyRelayTileUi(relayOnCount: Int, totalCount: Int) {
        if (!isAdded) return
        val caption = String.format(Locale.US, "%d/%d", relayOnCount, totalCount)
        ui.qsRelayState.text = caption
        // pop the count when it actually changes so the eye is drawn to the
        // new state (skipped on first bind, reloads, and reduced motion)
        if (lastRelayCaption != null &&
            caption != lastRelayCaption &&
            !isReducedMotionPreferred()
        ) {
            ui.qsRelayState.animate().cancel()
            ui.qsRelayState.scaleX = 0.7f
            ui.qsRelayState.scaleY = 0.7f
            ui.qsRelayState.animate()
                .scaleX(1f).scaleY(1f)
                .setDuration(220)
                .setInterpolator(OvershootInterpolator(1.5f))
                .start()
        }
        lastRelayCaption = caption
        val allOn = totalCount > 0 && relayOnCount == totalCount
        if (allOn) {
            val onColor = resolveAttrColor(R.attr.chipTextPositive)
            ui.qsRelayIcon.imageTintList = ColorStateList.valueOf(onColor)
            // full-strength icon so the accent tint reads clearly in the on state
            ui.qsRelayIcon.alpha = 1f
            ui.qsRelayLabel.setTextColor(onColor)
            ui.qsRelayState.setTextColor(onColor)
        } else {
            val offColor = resolveAttrColor(R.attr.primaryLightColorText)
            ui.qsRelayIcon.imageTintList = ColorStateList.valueOf(offColor)
            // match the resting alpha of the other quick-setting tile icons
            ui.qsRelayIcon.alpha = 0.5f
            ui.qsRelayLabel.setTextColor(offColor)
            ui.qsRelayState.setTextColor(offColor)
        }
    }

    /**
     * Relay-all toggle: enables (or disables) hop for **all** enabled non-AUTO
     * locations. Toggling ON only after every location reports hop-enabled, so a
     * single disabled location flips the tile back to OFF (see [refreshRelayTileState]).
     *
     * Enabling is gated behind a confirmation when the AUTO location has
     * automation on: relayed traffic enters via AUTO, so
     * AUTO's automation (and its paused state) affects every relayed location.
     */
    private fun onRelayQuickSettingClicked() {
        if (isProxyStopped) {
            showToast(getString(R.string.server_settings_proxy_stopped))
            return
        }
        if (relayToggleInFlight) return

        val enabledNonAuto = selectedServers.filter { !it.id.equals(AUTO_SERVER_ID, ignoreCase = true) }
        if (enabledNonAuto.isEmpty()) {
            showToast(getString(R.string.qs_relay_no_locations_toast))
            return
        }
        hapticTap()

        val target = !isRelayAllOn
        if (!target) {
            startRelayBulkToggle(target)
            return
        }

        // confirm when AUTO has automation since it will affect the relayed locations as well.
        io {
            val automationEnabled = runCatching { RpnProxyManager.isAutoAutomationEnabled() }
                .onFailure { Logger.w(LOG_TAG_UI, "$TAG.onRelayQuickSettingClicked: automation check failed: ${it.message}") }
                .getOrDefault(false)
            uiCtx {
                if (!isAdded) return@uiCtx
                if (automationEnabled) {
                    showRelayAutomationDialog { startRelayBulkToggle(target) }
                } else {
                    startRelayBulkToggle(target)
                }
            }
        }
    }

    /** Confirmation dialog shown when AUTO automation (mobileOnly/ssidBased) is active. */
    private fun showRelayAutomationDialog(onProceed: () -> Unit) {
        if (!isAdded || isStateSaved) return
        val dialog = WgAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
            .setTitle(getString(R.string.qs_relay_automation_dialog_title))
            .setMessage(getString(R.string.qs_relay_automation_dialog_message))
            .setPositiveButton(getString(R.string.lbl_proceed)) { _, _ -> onProceed() }
            .setNegativeButton(getString(R.string.lbl_cancel), null)
            .create()
        dialog.show()
        UIUtils.capDialogWidth(dialog)
    }

    private fun startRelayBulkToggle(target: Boolean) {
        if (relayToggleInFlight) return
        relayToggleInFlight = true

        io {
            val toUpdate = try {
                RpnProxyManager.getEnabledConfigs()
                    .filter { !it.id.equals(AUTO_SERVER_ID, ignoreCase = true) && it.hopEnabled != target }
            } catch (e: Exception) {
                Logger.w(LOG_TAG_UI, "$TAG.onRelayQuickSettingClicked: ${e.message}")
                emptyList()
            }

            var failures = 0
            toUpdate.forEach { config ->
                try {
                    RpnProxyManager.setHopForWinServer(config.key, target)
                } catch (e: Exception) {
                    failures++
                    Logger.e(LOG_TAG_UI, "$TAG.onRelayQuickSettingClicked: hop toggle failed for ${config.key}", e)
                }
            }

            uiCtx {
                relayToggleInFlight = false
                popRelayTile()
                if (!isAdded) return@uiCtx
                if (failures > 0) {
                    showToast(getString(R.string.qs_relay_failure_toast, failures))
                } else {
                    showToast(
                        getString(
                            if (target) R.string.qs_relay_enabled_toast else R.string.qs_relay_disabled_toast
                        )
                    )
                }
                refreshRelayTileState()
            }
        }
    }

    /** Small overshoot pop on the relay tile (result-landed beat). */
    private fun popRelayTile() {
        if (!isAdded || isReducedMotionPreferred()) return
        ui.qsRelayTile.animate().cancel()
        ui.qsRelayTile.animate()
            .scaleX(1.1f).scaleY(1.1f)
            .setDuration(120)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                if (isAdded) {
                    ui.qsRelayTile.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(200)
                        .setInterpolator(OvershootInterpolator(1.4f))
                        .start()
                }
            }
            .start()
    }

    /** Opens the bypass-apps screen (apps excluded from RPN via FirewallManager). */
    private fun openRpnBypassApps() {
        if (!isAdded) return
        startActivity(Intent(requireContext(), RpnBypassAppsActivity::class.java))
    }

    /** Opens the RPN live-stats bottom sheet (guarded against duplicate sheets). */
    private fun showRpnStatsBottomSheet() {
        if (!isAdded || isStateSaved) return
        if (parentFragmentManager.findFragmentByTag(RpnStatsBottomSheet.TAG) != null) return
        RpnStatsBottomSheet.newInstance().show(parentFragmentManager, RpnStatsBottomSheet.TAG)
    }

    /** Dims / enables the quick-settings tiles together with the search bar & actions. */
    private fun setQuickSettingsEnabled(enabled: Boolean) {
        if (!isAdded) return
        val alpha = if (enabled) 1f else 0.5f
        ui.quickSettingsRow.alpha = alpha
        listOf(ui.qsRelayTile, ui.qsBypassAppsTile).forEach { tile ->
            tile.isEnabled = enabled
        }
    }

    /** Opens the unified server-settings bottom sheet. */
    private fun showServerSettingsBottomSheet() {
        if (!isAdded) return
        // Guard: avoid stacking duplicate sheets
        if (parentFragmentManager.findFragmentByTag("ServerSettings") != null) return
        val sheet = ServerSettingsBottomSheet.newInstance(isProxyStopped)
        sheet.setOnSettingsChangedListener(object : ServerSettingsBottomSheet.OnSettingsChangedListener {
            override fun onDnsModeChanged(tunTypes: String) {
                Logger.v(LOG_TAG_UI, "$TAG.onDnsModeChanged: tunTypes=$tunTypes")
                io { VpnController.onRpnOptsChange() }
            }
            override fun onConfigChanged() {
                Logger.v(LOG_TAG_UI, "$TAG.onConfigChanged")
                io { VpnController.onRpnOptsChange() }
            }

            override fun onReset() {
                // Kick off the IO work in the ViewModel so it survives rotation;
                // then show the progress dialog (animation only — no IO here).
                serverSelectionViewModel.reset()
                showRpnResetDialog()
            }

            override fun onAutoExcludeCountriesChanged(excludedCcCsv: String) {
                Logger.v(LOG_TAG_UI, "$TAG.onAutoExcludeCountriesChanged: excludedCcCsv=$excludedCcCsv")
                io { VpnController.onRpnOptsChange() }
            }
        })
        sheet.show(parentFragmentManager, "ServerSettings")
    }

    private fun onToggleProxyFabClicked() {
        if (toggleProxyInFlight) return
        toggleProxyInFlight = true
        ui.fabStopProxy.isClickable  = false
        ui.fabStartProxy.isClickable = false
        applyFabLoadingState()
        if (isProxyStopped) doStartProxy() else doStopProxy()
    }

    private fun applyFabLoadingState() {
        if (!isAdded) return
        fabLoadingAnimator?.cancel()
        fabLoadingAnimator = null

        // Determine the currently visible FAB and reset it to a known state so
        // any previously-running ViewPropertyAnimator is cleanly cancelled before
        // we take over with the ObjectAnimator pulse.
        val activeFab: RpnControlState = if (isProxyStopped) ui.fabStartProxy else ui.fabStopProxy
        activeFab.animate().cancel()
        activeFab.alpha  = 1f
        activeFab.scaleX = 1f
        activeFab.scaleY = 1f

        // Continuous alpha pulse tracked by fabLoadingAnimator so stopFabLoadingState()
        // can cancel it cleanly without triggering any nested end-action.
        fabLoadingAnimator = ObjectAnimator.ofFloat(activeFab, "alpha", 1f, 0.45f).apply {
            duration = 650L
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopFabLoadingState() {
        // Cancel the pulse ObjectAnimator first so it releases its hold on the FAB's alpha.
        fabLoadingAnimator?.cancel()
        fabLoadingAnimator = null

        // Re-enable interaction immediately.
        ui.fabStopProxy.isClickable  = true
        ui.fabStartProxy.isClickable = true
        toggleProxyInFlight = false

        if (isAdded) {
            // Cancel any ViewPropertyAnimator still running on either FAB and reset
            // all transform properties to their resting state so the subsequent
            // applyFabRunningState / applyFabStoppedState swap animations start clean.
            ui.fabStopProxy.animate().cancel()
            ui.fabStartProxy.animate().cancel()
            ui.fabStopProxy.alpha  = 1f; ui.fabStopProxy.scaleX  = 1f; ui.fabStopProxy.scaleY  = 1f
            ui.fabStartProxy.alpha = 1f; ui.fabStartProxy.scaleX = 1f; ui.fabStartProxy.scaleY = 1f
        }
    }

    /** Pops in the stop FAB (red circle) and pops out the start FAB. */
    private fun applyFabRunningState() {
        if (!isAdded) return
        // Cancel any running animation so we start from a clean resting state.
        ui.fabStartProxy.animate().cancel()
        ui.fabStartProxy.scaleX = 1f
        ui.fabStartProxy.scaleY = 1f
        ui.fabStartProxy.alpha  = 1f
        // Shrink + fade the start FAB out.
        ui.fabStartProxy.animate()
            .scaleX(0f).scaleY(0f).alpha(0f)
            .setDuration(200)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction { if (isAdded) ui.fabStartProxy.visibility = View.GONE }
            .start()
        // Pop the stop FAB in with a short delay so both animations overlap and the
        // screen is never empty
        ui.fabStopProxy.scaleX = 0f
        ui.fabStopProxy.scaleY = 0f
        ui.fabStopProxy.alpha  = 0f
        ui.fabStopProxy.visibility = View.VISIBLE
        ui.fabStopProxy.animate().cancel()
        ui.fabStopProxy.animate()
            .scaleX(1f).scaleY(1f).alpha(1f)
            .setStartDelay(80)
            .setDuration(240)
            .setInterpolator(OvershootInterpolator(1.8f))
            .start()
    }

    /** Pops in the start FAB (green pill) and pops out the stop FAB. */
    private fun applyFabStoppedState() {
        if (!isAdded) return
        // Cancel any running animation so we start from a clean resting state.
        ui.fabStopProxy.animate().cancel()
        ui.fabStopProxy.scaleX = 1f
        ui.fabStopProxy.scaleY = 1f
        ui.fabStopProxy.alpha  = 1f
        // Shrink + fade the stop FAB out.
        ui.fabStopProxy.animate()
            .scaleX(0f).scaleY(0f).alpha(0f)
            .setDuration(200)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction { if (isAdded) ui.fabStopProxy.visibility = View.GONE }
            .start()
        // Pop the start FAB in with overlap.
        ui.fabStartProxy.scaleX = 0f
        ui.fabStartProxy.scaleY = 0f
        ui.fabStartProxy.alpha  = 0f
        ui.fabStartProxy.visibility = View.VISIBLE
        ui.fabStartProxy.animate().cancel()
        ui.fabStartProxy.animate()
            .scaleX(1f).scaleY(1f).alpha(1f)
            .setStartDelay(80)
            .setDuration(240)
            .setInterpolator(OvershootInterpolator(1.8f))
            .start()
    }
    private fun doStopProxy() {
        io {
            val success = try {
                RpnProxyManager.stopProxy()
                true
            } catch (e: Exception) {
                Logger.e(LOG_TAG_UI, "$TAG: stop proxy error: ${e.message}", e)
                false
            }
            uiCtx {
                stopFabLoadingState()
                if (!isAdded) return@uiCtx
                if (success) {
                    isProxyStopped = true
                    applyProxyStoppedUi()
                    Logger.i(LOG_TAG_UI, "$TAG: proxy stopped")
                } else {
                    // Restore to the pre-click appearance on failure.
                    applyFabRunningState()
                }
            }
        }
    }

    private fun doStartProxy() {
        io {
            RpnProxyManager.startProxy()
            uiCtx {
                stopFabLoadingState()
                if (!isAdded) return@uiCtx
                isProxyStopped = false
                applyProxyRunningUi()
                Logger.i(LOG_TAG_UI, "$TAG: proxy started")
            }
        }
    }

    private fun startTunnelWatchJob(pendingKeys: Set<String>) {
        tunnelWatchJob?.cancel()
        val remaining = pendingKeys.toMutableSet()
        tunnelWatchJob = lifecycleScope.launch {
            val deadline = System.currentTimeMillis() + 10_000L
            while (remaining.isNotEmpty() && System.currentTimeMillis() < deadline) {
                delay(1_500L)
                val resolved = withContext(Dispatchers.IO) {
                    remaining.filter { VpnController.getWinByKey(it) != null }.toSet()
                }
                if (resolved.isNotEmpty()) {
                    remaining.removeAll(resolved)
                    resolved.forEach { key ->
                        if (isAdded) selectedAdapter.clearLoadingTunnelKey(key)
                    }
                    Logger.i(LOG_TAG_UI, "$TAG: tunnelWatchJob resolved=$resolved, pending=$remaining")
                }
            }
            // Deadline reached or all resolved; clear whatever is left.
            if (isAdded && remaining.isNotEmpty()) {
                Logger.w(LOG_TAG_UI, "$TAG: tunnelWatchJob: timed out with ${remaining.size} unresolved, clearing")
                selectedAdapter.setLoadingTunnelKeys(emptySet<String>())
            }
            tunnelWatchJob = null
        }
    }

    /**
     * Called when the user stops the proxy via the settings sheet.
     */
    private fun applyProxyStoppedUi() {
        if (!isAdded) return

        ui.tvConnectionStatus.text = getString(R.string.server_settings_proxy_stopped)
        ui.tvConnectionStatus.setTextColor(
            ContextCompat.getColor(requireContext(), R.color.colorAmber_900)
        )
        stopStatusBlink()
        ui.statusIndicator.backgroundTintList =
            ContextCompat.getColorStateList(requireContext(), R.color.colorAmber_900)

        // Hero summary: stopped state, no avatars, no duration.
        ui.tvActiveDuration.text = ""
        populateAvatarRow(emptyList())
        ui.locationCapacityIndicator.isVisible = false

        val stoppedAlpha = 0.5f
        ui.rvServers.alpha         = stoppedAlpha
        ui.rvSelectedServers.alpha = stoppedAlpha
        // Disable search bar and action icons while proxy is stopped
        setSearchAndActionsEnabled(false)
        // Also disable pull-to-refresh: re-fetching server status is not
        // meaningful while the proxy is stopped.
        ui.swipeRefresh.isEnabled = false

        // Adapters replace click handlers so tapping any server item opens the
        // settings sheet instead of selecting/deselecting or opening detail.
        selectedAdapter.setProxyStopped(true)
        serverAdapter.setProxyStopped(true)

        ui.errorStateContainer.visibility = View.GONE

        ui.errorRetryBtn.isEnabled = false
        ui.errorRetryBtn.isClickable = false
        ui.errorRetryBtn.setOnClickListener(null)
        ui.errorResetBtn.isEnabled = false
        ui.errorResetBtn.isClickable = false
        ui.errorResetBtn.setOnClickListener(null)

        ui.serverCountLayout.isVisible = false

        // Hide frequent chips while proxy is stopped
        ui.frequentCountriesSection.isVisible = false

        // FAB: switch to "Start" (green VPN icon)
        applyFabStoppedState()
    }

    private fun applyProxyRunningUi() {
        if (!isAdded) return

        ui.rvServers.alpha              = 1f
        ui.rvSelectedServers.alpha      = 1f
        // Re-enable search bar and action icons when proxy resumes
        setSearchAndActionsEnabled(true)
        // Re-enable pull-to-refresh (unless the initial load is still running;
        // setLoadingState() owns the enabled state in that case).
        ui.swipeRefresh.isEnabled = !isLoading

        selectedAdapter.setProxyStopped(false)
        serverAdapter.setProxyStopped(false)

        ui.errorRetryBtn.isEnabled = true
        ui.errorRetryBtn.isClickable = true
        ui.errorResetBtn.isEnabled = true
        ui.errorResetBtn.isClickable = true

        ui.serverCountLayout.isVisible = true

        updateConnectionStatus(deriveConnectionUiState())

        // FAB: switch to "Stop" (red stop icon)
        applyFabRunningState()

        // refetch WIN registration state and server list on a background thread.
        io {
            isWinRegistered = VpnController.isWinRegistered()
            val hasTunnel    = VpnController.hasTunnel()
            val servers      = RpnProxyManager.getWinServers()
            val selectedList = RpnProxyManager.getEnabledConfigs()
            val hasRealServers = servers.any { it.id != AUTO_SERVER_ID }

            uiCtx {
                if (!isAdded) return@uiCtx
                if (!isWinRegistered || !hasRealServers) {
                    if (!hasTunnel) {
                        // No tunnel; registration will fail, show "Start Rethink" error.
                        Logger.w(LOG_TAG_UI, "$TAG.applyProxyRunningUi: no tunnel, showing no-tunnel error")
                        showErrorState(noTunnel = true)
                    } else {
                        // WIN is not yet registered or the server list is not populated
                        // immediately after proxy start (can happen on first launch after
                        // reinstall). The polling dialog will keep retrying until both
                        // conditions are satisfied, then call initServers().
                        showServerLoadingDialog()
                    }
                } else {
                    initServers(servers, selectedList)
                }
            }
        }
    }

    private fun openHelpAndSupport() {
        val args = Bundle().apply { putString("ARG_KEY", "Launch_Rethink_Support_Dashboard") }
        startActivity(
            FragmentHostActivity.createIntent(
                context = requireContext(),
                fragmentClass = RethinkPlusDashboardFragment::class.java,
                args = args
            )
        )
    }

    private fun setupRecyclerViews() {
        serverAdapter = CountryServerAdapter(buildCountries(unselectedServers), this)
        ui.countryAdapter = serverAdapter
        selectedAdapter = VpnServerAdapter(requireContext(), buildSelectedServerGroups(selectedServers), this)
        ui.selectedAdapter = selectedAdapter
    }

    private fun setupSearchBar() {
        ui.searchBar.onQueryChanged = { filterServers(it) }
        ui.searchFilterBtn.setOnClickListener { showFilterDialog() }
        ui.tvActiveFilterSummary.setOnClickListener { clearFilters() }
        ui.searchBar.onFocusChanged = { hasFocus ->
            ui.searchCard.animate().scaleX(if (hasFocus) 1.02f else 1f).scaleY(if (hasFocus) 1.02f else 1f).setDuration(150).start()
        }
    }

    private fun updateSelectedSectionVisibility() {
        if (!isAdded) return
        val hasSelection = selectedServers.isNotEmpty()
        ui.rvSelectedServers.isVisible = hasSelection

        ui.emptySelectionCard.isVisible = !hasSelection && !isLoading
    }

    private fun updateAllServersCount() {
        if (!isAdded) return
        val count = unselectedServers.distinctBy { it.key }.size
        ui.tvServerCount.text = if (count == 0) ""
        else resources.getQuantityString(R.plurals.server_count, count, count)
    }

    private fun showEmptyState() {
        showUnifiedErrorState(
            illustration = EmbeddedDolphinContent.failureDrawable(
                EmbeddedDolphinContent.FailureFlavor.CONFUSED
            ),
            title = getString(R.string.server_selection_no_servers),
            hint = getString(R.string.server_selection_no_servers_hint),
            isError = false
        )
    }

    private fun showErrorState(noTunnel: Boolean = false) {
        if (noTunnel) {
            // VPN/tunnel is down; saying "Error fetching locations" would
            // mislead — the fetch never ran. Point the user at the fix.
            showUnifiedErrorState(
                illustration = EmbeddedDolphinContent.failureDrawable(
                    EmbeddedDolphinContent.FailureFlavor.OFFLINE
                ),
                title = getString(R.string.server_selection_vpn_stopped_title),
                hint = getString(R.string.server_selection_vpn_stopped_hint),
                isError = true,
                noTunnel = true
            )
        } else {
            showUnifiedErrorState(
                illustration = EmbeddedDolphinContent.failureDrawable(
                    EmbeddedDolphinContent.FailureFlavor.SERVER
                ),
                title = getString(R.string.server_selection_error_title),
                hint = getString(R.string.server_selection_error_hint),
                isError = true
            )
        }
    }

    private fun showUnifiedErrorState(
        illustration: Int,
        title: String,
        hint: String,
        isError: Boolean,
        noTunnel: Boolean = false
    ) {
        if (!isAdded) return
        ui.rvServers.isVisible = false
        ui.searchCard.isVisible = true
        ui.searchCard.isEnabled = false
        ui.searchCard.alpha = 0.5f
        ui.searchBar.isEnabled = false

        ui.supportBtn.isVisible = true
        ui.settingsBtn.isVisible = true

        // Keep the status card visible but update it for a premium feel.
        ui.statusCard.isVisible = true
        updateConnectionStatus(if (isError) ConnectionUiState.FAILED else ConnectionUiState.DISCONNECTED)
        populateAvatarRow(emptyList())

        ui.serverCountLayout.isVisible = false
        ui.rvSelectedServers.isVisible = false
        ui.emptySelectionCard.isVisible = false
        ui.frequentCountriesSection.isVisible = false
        ui.locationCapacityIndicator.isVisible = false

        // Update content: the sad-dolphin artwork is full-colour, so it is
        // rendered untinted and reads correctly in both light and dark themes.
        ui.errorIllustration.setImageResource(illustration)
        ui.errorIllustration.imageTintList = null
        ui.errorTitle.text = title
        ui.errorHint.text = hint
        ui.errorHint.isVisible = hint.isNotEmpty()

        // Animate the container sliding up from below
        ui.errorStateContainer.visibility = View.VISIBLE
        ui.errorStateContainer.alpha = 0f
        ui.errorStateContainer.translationY = 60f
        ui.errorStateContainer.animate()
            .alpha(1f).translationY(0f)
            .setDuration(450)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        ui.errorIllustration.alpha = 0f
        ui.errorIllustration.scaleX = 0.6f
        ui.errorIllustration.scaleY = 0.6f
        ui.errorIllustration.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setStartDelay(180)
            .setDuration(500)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        startErrorDolphinAnimation()

        if (noTunnel) {
            ui.errorRetryBtn.isVisible = true
            ui.errorRetryBtn.isEnabled = true
            ui.errorRetryBtn.isClickable = true
            ui.errorRetryBtn.text = getString(R.string.ssv_toast_start_rethink)
            ui.errorRetryBtn.setOnClickListener { startRethinkFromErrorCard() }
            ui.errorResetBtn.isVisible = false
            ui.errorReportBtn.isVisible = true
            ui.errorReportBtn.setOnClickListener { openHelpAndSupport() }
        } else if (isError) {
            ui.errorRetryBtn.isVisible = true
            ui.errorRetryBtn.isEnabled = true
            ui.errorRetryBtn.isClickable = true
            ui.errorRetryBtn.text = getString(R.string.server_selection_error_retry)
            ui.errorRetryBtn.setOnClickListener { retryLoadingServers() }

            ui.errorResetBtn.isVisible = false
            ui.errorResetBtn.isEnabled = true
            ui.errorResetBtn.isClickable = true
            ui.errorResetBtn.setOnClickListener {
                serverSelectionViewModel.reset()
                showRpnResetDialog()
            }

            ui.errorReportBtn.isVisible = true
            ui.errorReportBtn.setOnClickListener { openHelpAndSupport() }

            // Show reset only if proxy test passes
            io {
                val shouldShowReset = VpnController.testRpnProxy()
                uiCtx {
                    if (isAdded && ui.errorStateContainer.isVisible && isError) {
                        ui.errorResetBtn.isVisible = shouldShowReset
                    }
                }
            }
        } else {
            // Empty state (no servers found)
            ui.errorRetryBtn.isVisible = true
            ui.errorRetryBtn.text = getString(R.string.server_selection_error_retry)
            ui.errorRetryBtn.setOnClickListener { retryLoadingServers() }
            ui.errorResetBtn.isVisible = false
            ui.errorReportBtn.isVisible = true
            ui.errorReportBtn.setOnClickListener { openHelpAndSupport() }
        }
    }

    private fun hideErrorState() {
        if (!isAdded) return
        // Stop the bobbing dolphin and any pending tunnel wait before
        // recovering the screen.
        stopErrorDolphinAnimation()
        errorTunnelWaitJob?.cancel()
        errorTunnelWaitJob = null
        if (ui.errorStateContainer.isVisible) {
            ui.errorStateContainer.animate()
                .alpha(0f).translationY(-40f)
                .setDuration(250)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction { if (isAdded) ui.errorStateContainer.visibility = View.GONE }
                .start()
        }
        ui.rvServers.isVisible = true
        ui.searchCard.isVisible = true
        ui.searchCard.alpha = 1f
        ui.supportBtn.isVisible = true
        ui.settingsBtn.isVisible = true
        ui.statusCard.isVisible = true
        updateVpnStatus()
        ui.searchCard.isEnabled = true
        ui.searchBar.isEnabled = true
        if (!isProxyStopped) updateCapacityIndicator()
    }

    /**
     * Gently bobs the error card's dolphin up and down on a
     * [ERROR_DOLPHIN_BOB_HALF_MS]-per-direction loop (a full up-down cycle
     * every two seconds) so the moment feels alive without any image swaps.
     * Skipped entirely when the system's animator duration scale is off.
     */
    private fun startErrorDolphinAnimation() {
        stopErrorDolphinAnimation()
        if (isReducedMotionPreferred()) return
        val bobPx = ERROR_DOLPHIN_BOB_DP * resources.displayMetrics.density
        errorDolphinAnimator = ObjectAnimator.ofFloat(
            ui.errorIllustration, "translationY", 0f, -bobPx
        ).apply {
            duration = ERROR_DOLPHIN_BOB_HALF_MS
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopErrorDolphinAnimation() {
        errorDolphinAnimator?.cancel()
        errorDolphinAnimator = null
        if (isAdded) ui.errorIllustration.translationY = 0f
    }

    /**
     * Handles the "Start Rethink" tap on the no-tunnel error card: requests
     * the VPN to start, gives immediate feedback on the button, then polls
     * briefly for the tunnel. Once it is up, the normal retry path takes
     * over (loading dialog, registration, server list); if it never comes
     * up (e.g. consent denied), the no-tunnel card is restored.
     */
    private fun startRethinkFromErrorCard() {
        if (!isAdded) return
        Logger.i(LOG_TAG_UI, "$TAG.startRethinkFromErrorCard: requesting VPN start")
        errorTunnelWaitJob?.cancel()
        // user-initiated: never pass autoAttempt=true, it drops the start
        // request when the service is alive without a tunnel
        VpnController.start(requireContext())

        ui.errorRetryBtn.isEnabled = false
        ui.errorRetryBtn.isClickable = false
        ui.errorRetryBtn.text = getString(R.string.lbl_connecting)
        updateConnectionStatus(ConnectionUiState.CONNECTING)

        errorTunnelWaitJob = viewLifecycleOwner.lifecycleScope.launch {
            val deadline = System.currentTimeMillis() + TUNNEL_WAIT_TIMEOUT_MS
            while (isActive && System.currentTimeMillis() < deadline) {
                delay(TUNNEL_WAIT_POLL_MS)
                val hasTunnel = withContext(Dispatchers.IO) {
                    try { VpnController.hasTunnel() } catch (_: Exception) { false }
                }
                if (hasTunnel) {
                    if (!isAdded) return@launch
                    Logger.i(LOG_TAG_UI, "$TAG.startRethinkFromErrorCard: tunnel up, retrying")
                    // detach from the job before re-entering retryLoadingServers,
                    // which cancels any still-registered wait job
                    errorTunnelWaitJob = null
                    retryLoadingServers()
                    return@launch
                }
            }
            if (isAdded) {
                Logger.w(LOG_TAG_UI, "$TAG.startRethinkFromErrorCard: tunnel wait timed out")
                showErrorState(noTunnel = true)
            }
        }
    }

    private fun isReducedMotionPreferred(): Boolean =
        Settings.Global.getFloat(
            context?.contentResolver ?: return true,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f

    private fun retryLoadingServers() {
        if (!isAdded) return
        // A pending "Start Rethink" tunnel wait is superseded by an explicit
        // retry (if this call came from the wait itself, it already detached
        // its job reference above).
        errorTunnelWaitJob?.cancel()
        errorTunnelWaitJob = null
        if (!RpnProxyManager.isRpnActive()) {
            showToast(getString(R.string.server_selection_tap_to_select))
            return
        }

        // If the VPN tunnel is not up, show the "Start Rethink" prompt instead of spinning forever
        if (!VpnController.hasTunnel()) {
            Logger.w(LOG_TAG_UI, "$TAG.retryLoadingServers: no VPN tunnel, showing no-tunnel error")
            showErrorState(noTunnel = true)
            return
        }

        ui.errorRetryBtn.isEnabled = false
        ui.errorResetBtn.isEnabled = false
        ui.errorRetryBtn.text = getString(R.string.lbl_connecting)

        SnackbarHelper.dismiss()

        // Hide error container and show shimmer for immediate visual feedback.
        ui.errorStateContainer.animate()
            .alpha(0f).setDuration(200)
            .withEndAction { if (isAdded) ui.errorStateContainer.visibility = View.GONE }
            .start()

        updateConnectionStatus(ConnectionUiState.CONNECTING)
        setLoadingState(true, skipHeader = true)

        io {
            isWinRegistered = VpnController.isWinRegistered()
            Logger.v(LOG_TAG_UI, "$TAG.retryLoadingServers: WIN registered=$isWinRegistered")

            val selectedList = try {
                RpnProxyManager.getEnabledConfigs()
            } catch (e: Exception) {
                Logger.w(LOG_TAG_UI, "$TAG.retryLoadingServers: getEnabledCCs failed: ${e.message}")
                emptySet()
            }
            val servers = try {
                val refreshed = RpnProxyManager.updateWinProxy()
                if (!refreshed.isNullOrEmpty()) refreshed
                else RpnProxyManager.getWinServers()
            } catch (t: Throwable) {
                Logger.e(LOG_TAG_UI, "$TAG.retryLoadingServers: tunnel error (non-fatal): ${t.message}")
                // Fall back to whatever is in the DB/cache so the screen is not blank.
                try { RpnProxyManager.getWinServers() } catch (_: Exception) { emptyList() }
            }

            val hasRealServers = servers.any { it.id != AUTO_SERVER_ID }
            val hasTunnel = VpnController.hasTunnel()

            uiCtx {
                if (!isAdded) return@uiCtx
                ui.errorRetryBtn.isEnabled = true
                ui.errorResetBtn.isEnabled = true
                ui.errorRetryBtn.text = getString(R.string.server_selection_error_retry)
                when {
                    // WIN not yet registered or server list still empty; check tunnel first.
                    (!isWinRegistered || !hasRealServers) && RpnProxyManager.isRpnActive() -> {
                        if (!hasTunnel) {
                            Logger.w(LOG_TAG_UI, "$TAG.retryLoadingServers: no tunnel, showing no-tunnel error")
                            setLoadingState(false)
                            showErrorState(noTunnel = true)
                        } else {
                            Logger.i(
                                LOG_TAG_UI,
                                "$TAG.retryLoadingServers: WIN registered=$isWinRegistered, " +
                                    "hasRealServers=$hasRealServers, handing off to registration progress"
                            )
                            // showServerLoadingDialog() calls setLoadingState(true) internally
                            // do NOT call setLoadingState(false) here or the shimmers will flash off.
                            showServerLoadingDialog()
                        }
                    }
                    else -> initServers(servers, selectedList)
                }
            }
        }
    }


    private fun buildCountries(servers: List<CountryConfig>): List<CountryServerAdapter.CountryItem> {
        if (servers.isEmpty()) return emptyList()
        // AUTO is always kept exclusively in the selected list; filter it out here
        // as a defensive measure so it can never appear as a selectable country row
        // even if initServers() has a bug that leaves it in unselectedServers.
        return servers
            .asSequence()
            .filter { !it.id.equals(AUTO_SERVER_ID, ignoreCase = true) }
            .groupBy { it.cc }
            .map { (cc, list) ->
                val sample = list.first()
                val groups = list.groupBy { it.key }.map { (key, grouped) ->
                    val rep = grouped.first()
                    val leastLoad = if (grouped.all { it.load > 0 }) grouped.minOfOrNull { it.load } ?: 0 else 0
                    val bestLink  = if (grouped.all { it.link > 0 }) grouped.maxOfOrNull { it.link } ?: 0 else 0
                    CountryServerAdapter.ServerGroup(key, grouped, rep.serverLocation, leastLoad, bestLink, grouped.any { it.isEnabled })
                }.sortedBy { it.city.lowercase()     }

                CountryServerAdapter.CountryItem(cc, sample.countryName, sample.flagEmoji, groups, list.any { it.isFavourite })
                // Sorted purely A→Z (no favourites-first reordering) so the
                // alphabet section headers in CountryServerAdapter stay contiguous.
            }.sortedBy { it.countryName.lowercase() }
            .toList()
    }

    private fun buildSelectedServerGroups(servers: List<CountryConfig>): List<VpnServerAdapter.ServerGroup> {
        if (servers.isEmpty()) return emptyList()
        return servers.groupBy { it.key }.map { (key, grouped) ->
            val rep = grouped.first()
            val leastLoad = if (grouped.all { it.load > 0 }) grouped.minOfOrNull { it.load } ?: 0 else 0
            val bestLink  = if (grouped.all { it.link > 0 }) grouped.maxOfOrNull { it.link } ?: 0 else 0
            VpnServerAdapter.ServerGroup(key, grouped, rep.countryName, rep.flagEmoji, rep.serverLocation, rep.cc, bestLink, leastLoad, grouped.any { it.isActive })
        }
        .sortedWith(
            compareBy(
                { !it.key.equals(AUTO_SERVER_ID, ignoreCase = true) },
                { it.cityName.lowercase() }
            )
        )
    }

    /**
     * Rebuilds the "All locations" list applying the current search query together
     * with the active load-tier, speed-tier and favourites-only filters.  Called on
     * text changes and whenever the underlying list changes so filters survive refreshes.
     */
    private fun refreshUnselectedList() {
        if (!isAdded) return
        val q = ui.searchBar.query?.toString()?.trim()?.lowercase().orEmpty()
        val filtered = unselectedServers.filter { matchesFilters(it, q) }
        serverAdapter.updateCountries(buildCountries(filtered))
        updateFilterButtonState()
    }

    /** Returns true when [server] passes the search query and the active filters. */
    private fun matchesFilters(server: CountryConfig, query: String): Boolean {
        val matchesQuery = query.isEmpty() ||
                server.countryName.lowercase().contains(query) ||
                server.serverLocation.lowercase().contains(query) ||
                server.cc.lowercase().contains(query)
        if (!matchesQuery) return false

        if (favouritesOnly && !server.isFavourite) return false

        // Load is 0 when unknown; only explicit tiers filter on it.
        val matchesLoad = when (loadFilter) {
            LoadFilter.ALL -> true
            LoadFilter.LOW -> server.load in 1..40
            LoadFilter.MEDIUM -> server.load in 41..80
            LoadFilter.HIGH -> server.load > 80
        }
        if (!matchesLoad) return false

        // 0 means "Any"; otherwise match the exact link speed (Mbps) chosen in the
        // filter dialog.  Servers with an unknown speed (link == 0) only pass "Any".
        return speedFilter == 0 || server.link == speedFilter
    }

    /** True when any filter other than the defaults is active. */
    private fun isFilterActive(): Boolean =
        loadFilter != LoadFilter.ALL || speedFilter != 0 || favouritesOnly

    /** Human-readable summary of the active filters, or null when defaults are in effect. */
    private fun describeActiveFilter(): String? {
        val parts = mutableListOf<String>()
        if (loadFilter != LoadFilter.ALL) parts.add(loadFilter.label)
        if (speedFilter != 0) parts.add(formatLinkSpeed(speedFilter))
        if (favouritesOnly) parts.add(getString(R.string.server_selection_filter_favourites_only))
        if (parts.isEmpty()) return null
        return parts.joinToString(" ${getString(R.string.lbl_separator_dot)} ")
    }

    /**
     * Updates every "active filter" indicator on the main screen:
     * - the filter button (icon + background tint + content description), and
     * - the dismissible summary pill next to the server count.
     * Both use the high-contrast positive palette so an active filter is clearly
     * visible at a glance.
     */
    private fun updateFilterButtonState() {
        if (!isAdded) return
        val active = isFilterActive()
        val accent = resolveAttrColor(R.attr.accentGood)

        ui.searchFilterBtn.iconTint = ColorStateList.valueOf(
            resolveAttrColor(if (active) R.attr.accentGood else R.attr.primaryTextColor)
        )
        ui.searchFilterBtn.backgroundTintList = ColorStateList.valueOf(
            if (active) ColorUtils.setAlphaComponent(accent, 0x33)
            else resolveAttrColor(R.attr.colorSurfaceVariant)
        )
        ui.searchFilterBtn.contentDescription = if (active) {
            getString(
                R.string.server_selection_filter_active_desc,
                describeActiveFilter().orEmpty()
            )
        } else {
            getString(R.string.server_selection_filter_locations)
        }

        updateActiveFilterSummary()
    }

    /** Shows or hides the dismissible "active filter" pill; tapping it clears filters. */
    private fun updateActiveFilterSummary() {
        if (!isAdded) return
        val summary = describeActiveFilter()
        if (summary == null || isProxyStopped) {
            ui.tvActiveFilterSummary.isVisible = false
            return
        }
        // Re-tint the pill's translucent shape with the accent so its wash
        // matches the accent text/icon instead of the unrelated positive hue.
        ui.tvActiveFilterSummary.backgroundTintList = ColorStateList.valueOf(
            ColorUtils.setAlphaComponent(resolveAttrColor(R.attr.accentGood), 0x33)
        )
        ui.tvActiveFilterSummary.text = summary
        ui.tvActiveFilterSummary.isVisible = true
    }

    /** Resets all filters to their defaults and refreshes all indicators. */
    private fun clearFilters() {
        loadFilter = LoadFilter.ALL
        speedFilter = 0
        favouritesOnly = false
        refreshUnselectedList()
    }

    /** Re-applies the active filters on search-text changes. */
    private fun filterServers(query: String) {
        refreshUnselectedList()
    }

    /**
     * Shows the location filter dialog: single-choice load-tier and speed-tier chip
     * groups plus a favourites-only chip.  Applied on "Apply", cleared via "Reset".
     *
     * The chip that matches the currently-applied filter is pre-checked and, via
     * [createFilterChip]'s state-aware styling, rendered in the high-contrast
     * "positive" palette so the active filter is immediately obvious.
     */
    private fun showFilterDialog() {
        if (!isAdded) return
        val tiers = listOf(LoadFilter.ALL, LoadFilter.LOW, LoadFilter.MEDIUM, LoadFilter.HIGH)
        val speeds = listOf(0) + distinctSpeedOptions()
        rpnFilterDialog(
            requireActivity(),
            tiers.map { if (it == LoadFilter.ALL) getString(R.string.server_selection_filter_load_any) else it.label },
            tiers.indexOf(loadFilter),
            speeds.map { if (it == 0) getString(R.string.server_selection_filter_load_any) else formatLinkSpeed(it) },
            speeds.indexOf(speedFilter),
            favouritesOnly,
            onApply = { load, speed, favourite ->
                loadFilter = tiers.getOrElse(load) { LoadFilter.ALL }
                speedFilter = speeds.getOrElse(speed) { 0 }
                favouritesOnly = favourite
                refreshUnselectedList()
            },
            onReset = {
                loadFilter = LoadFilter.ALL
                speedFilter = 0
                favouritesOnly = false
                refreshUnselectedList()
            }
        )
    }

    /**
     * Returns the distinct, known link speeds (Mbps) available across
     * [allServers], sorted ascending.  Servers with an unknown speed
     * ([CountryConfig.link] == 0) are excluded so the dialog only ever offers
     * speeds that actually exist (e.g. 1000 → "1 Gbps", 10000 → "10 Gbps").
     */
    private fun distinctSpeedOptions(): List<Int> =
        allServers.map { it.link }.filter { it > 0 }.distinct().sorted()

    /**
     * Formats a link speed in Mbps for display in filter chips and the active
     * filter pill, e.g. 100 → "100 Mbps", 1000 → "1 Gbps", 2500 → "2.5 Gbps".
     */
    private fun formatLinkSpeed(linkMbps: Int): String {
        if (linkMbps < 1_000) return "$linkMbps Mbps"
        val gbps = linkMbps / 1_000.0
        return if (gbps == gbps.toLong().toDouble()) {
            "${gbps.toLong()} Gbps"
        } else {
            String.format(Locale.US, "%.1f Gbps", gbps)
        }
    }

    /**
     * Builds a checkable filter chip whose colors react to the checked state so the
     * selection is unmistakable:
     * - checked:   positive chip background + positive chip text + accent stroke
     * - unchecked: neutral chip background + neutral chip text + no stroke
     *
     * All colors come from the active theme via design-system attributes
     * ([R.attr.chipBgColorPositive], [R.attr.chipBgColorNeutral], [R.attr.accentGood], …)
     * so every app theme (dark / light / black / plus variants) gets correct contrast
     * without any hard-coded values.
     */


    /**
     * Builds the small all-caps section label used inside the filter dialog,
     * mirroring the `RethinkPlus.SectionLabel` style used across this screen.
     */



    private fun onServerSelected(server: CountryConfig, isEnabled: Boolean) {
        if (isEnabled) {
            if (server.id == AUTO_SERVER_ID) {
                // AUTO is always kept enabled
                if (selectedServers.any { it.id == AUTO_SERVER_ID }) return
                io {
                    val auto = RpnProxyManager.getAutoServer() ?: return@io
                    auto.isEnabled = true
                    RpnProxyManager.updateAutoServerState(auto)
                    uiCtx {
                        if (!isAdded) return@uiCtx

                        server.isEnabled = true
                        selectedServers.add(0, server) // AUTO always first
                        unselectedServers.removeAll { it.id == AUTO_SERVER_ID }
                        refreshAfterSelectionChange()
                    }
                }
                return
            }

            if (selectedServers.any { it.key == server.key }) {
                showToast("${server.serverLocation} is already selected")
                return
            }

            val nonAutoCount = selectedServers.count { it.id != AUTO_SERVER_ID }
            if (nonAutoCount >= MAX_SELECTIONS) {
                showToast(getString(R.string.server_selection_max_reached, MAX_SELECTIONS))
                return
            }

            // Move the server immediately to the selected list with a "Connecting…"
            // indicator so the user sees instant feedback while the backend IO runs.
            val grouped = allServers.filter { it.key == server.key }
            val best = grouped.minByOrNull { it.load } ?: server
            grouped.forEach { it.isActive = true }
            if (!selectedServers.any { it.key == best.key }) {
                selectedServers.add(best)
            }
            unselectedServers.removeAll { it.key == server.key }
            // Mark this key as "loading" so the adapter item shows "Connecting…" pulse.
            selectedAdapter.addLoadingTunnelKey(server.key)
            hapticTap()
            refreshAfterSelectionChange()

            io {
                val res = RpnProxyManager.enableWinServer(server.key)
                uiCtx {
                    if (!isAdded) return@uiCtx

                    if (!res.first) {
                        showToast("Failed to add ${server.countryName}: ${res.second}")
                        // Revert the optimistic changes.
                        selectedServers.removeAll { it.key == server.key }
                        grouped.forEach { it.isActive = false }
                        grouped.filter { s -> !unselectedServers.any { it.key == s.key } }
                               .forEach { unselectedServers.add(it) }
                        selectedAdapter.clearLoadingTunnelKey(server.key)
                        refreshAfterSelectionChange()
                        return@uiCtx
                    }

                    // clear the "Connecting…" indicator.
                    selectedAdapter.clearLoadingTunnelKey(server.key)
                    // Signature moment: a dolphin arcs from the location's new
                    // card up to the hero status dot to celebrate the connect.
                    playConnectArc()
                    Logger.v(LOG_TAG_UI, "$TAG.onServerSelected: best: $best, grouped: $grouped")
                }
            }
        } else {
            if (server.id == AUTO_SERVER_ID) {
                showToast(getString(R.string.server_selection_auto_always_on))
                return
            }

            val key = server.key
            io {
                val res = RpnProxyManager.disableWinServer(key)
                uiCtx {
                    if (!isAdded) return@uiCtx

                    if (!res.first) {
                        showToast("Failed to remove ${server.countryName}: ${res.second}")
                        return@uiCtx
                    }
                    // Mark inactive and move from selected → unselected
                    val removedServers = selectedServers.filter { it.key == key }
                    removedServers.forEach { s ->
                        s.isActive = false
                        s.isEnabled = false
                    }
                    selectedServers.removeAll { it.key == key }

                    allServers.filter { it.key == key }.forEach { s ->
                        s.isActive = false
                        s.isEnabled = false
                        if (!unselectedServers.any { it.key == s.key }) {
                            unselectedServers.add(s)
                        }
                    }
                    refreshAfterSelectionChange()
                }
            }
        }
    }

    private fun refreshAfterSelectionChange() {
        selectedAdapter.updateServers(selectedServers)
        refreshUnselectedList()
        updateAllServersCount()
        updateSelectedSectionVisibility()
        updateVpnStatus()
        refreshRelayTileState()
        if (!isProxyStopped) loadAndShowFrequentChips()
    }

    override fun onCitySelected(server: CountryConfig, isEnabled: Boolean) {
        onServerSelected(server, isEnabled)
    }

    override fun onServerGroupSelected(group: VpnServerAdapter.ServerGroup, isSelected: Boolean) {
        onServerSelected(group.getBestServer(), isSelected)
    }

    /**
     * Satisfies both [VpnServerAdapter.ServerSelectionListener] and
     * [CountryServerAdapter.CitySelectionListener].
     *
     * When the proxy is stopped, any tap on a server item (selected or unselected)
     * calls this method.  We show a brief hint pointing the user to the FAB.
     */
    override fun onProxyStoppedItemTapped() {
        showToast(getString(R.string.server_settings_proxy_stopped))
    }

    override fun onAddServerTapped() {
        // Surface the location picker: scroll to and focus the search bar that
        // drives the "All locations" list (same entry point as the quick-settings tile).
        focusLocationSearch()
    }

    override fun onRelayToggled() {
        // A per-server relay change in the adapter invalidates the aggregate
        // "all locations relayed" state shown by the Relay quick-settings tile.
        refreshRelayTileState()
    }

    override fun onFavouriteToggled(countryCode: String, countryName: String, isFavourite: Boolean) {
        // Mutate the in-memory CountryConfig objects immediately so every subsequent
        // call to buildCountries() reads the correct isFavourite value.  Without this
        // the star icon reverts the next time refreshAfterSelectionChange() is called
        // (e.g. when the user selects another server).
        allServers.filter { it.cc == countryCode }
                  .forEach { it.isFavourite = isFavourite }

        // Persist to DB on a background thread.
        io {
            countryConfigRepository.updateFavourite(countryCode, isFavourite)
        }

        val txt = if (isFavourite) getString(R.string.server_favourite_added, countryName)
        else getString(R.string.server_favourite_removed, countryName)
        showToast(txt)

        // Rebuild the unselected list so DiffUtil re-binds the affected row with the
        // correct star state.  unselectedServers shares the same CountryConfig object
        // references as allServers, so they're already updated above.
        refreshUnselectedList()
    }

    override fun onServerGroupRemoved(group: VpnServerAdapter.ServerGroup) {
        // AUTO is always kept connected
        if (group.key.equals(AUTO_SERVER_ID, true) || group.servers.any { it.id.equals(AUTO_SERVER_ID, true) }) {
            showToast(getString(R.string.server_selection_auto_always_on))
            return
        }
        val dialog = WgAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.server_selection_remove_title))
            .setMessage(getString(R.string.server_selection_remove_message, group.countryName, group.cityName))
            .setPositiveButton(getString(R.string.lbl_remove)) { _, _ ->
                io {
                    val res = RpnProxyManager.disableWinServer(group.key)
                    uiCtx {
                        if (!isAdded) return@uiCtx

                        if (!res.first) {
                            showToast("Failed to remove ${group.countryName}: ${res.second}")
                            return@uiCtx
                        }
                        val key = group.key
                        selectedServers.filter { it.key == key }.forEach { s ->
                            s.isActive = false
                            s.isEnabled = false
                        }
                        selectedServers.removeAll { it.key == key }

                        // Restore to unselected
                        allServers.filter { it.key == key }.forEach { s ->
                            s.isActive = false
                            s.isEnabled = false
                            if (!unselectedServers.any { it.id == s.id }) unselectedServers.add(s)
                        }
                        refreshAfterSelectionChange()
                        showToast("${group.countryName};${group.cityName} removed")
                    }
                }
            }
            .setNegativeButton(getString(R.string.lbl_cancel), null)
            .create()
        dialog.show()
        UIUtils.capDialogWidth(dialog)
    }

    private fun showToast(msg: String) {
        if (isAdded) Utilities.showToastUiCentered(requireContext(), msg, Toast.LENGTH_SHORT)
    }

    /**
     * Fetches the top-frequently-selected country codes on IO, then binds chips on the
     * main thread.
     *
     * **Frequent and favourite are independent concepts.**  Whether a country is starred
     * has no bearing on whether it appears here — only the selection count matters.
     * Countries already present in [selectedServers] are excluded.
     */
    private fun loadAndShowFrequentChips() {
        if (!isAdded) return
        io {
            val topCcs = try {
                countryConfigRepository.getTopFrequentCcs(limit = 5)
            } catch (e: Exception) {
                Logger.w(LOG_TAG_UI, "$TAG.loadAndShowFrequentChips: getTopFrequentCcs error: ${e.message}")
                emptyList()
            }
            uiCtx {
                if (!isAdded) return@uiCtx
                populateFrequentChips(topCcs)
            }
        }
    }

    /**
     * Clears and repopulates the frequent chip group from the top-frequently-selected
     * country codes.
     *
     * Only the selection count determines membership — favourite state is irrelevant so
     * un-starring a country never removes it from the strip.  Countries already in
     * [selectedServers] are excluded.  The whole section is hidden when no chips remain.
     */
    private fun populateFrequentChips(topCcs: List<String>) {
        if (!isAdded) return
        val chipGroup = ui.frequentChipGroup
        chipGroup.children.clear()

        val selectedCcs = selectedServers.map { it.cc }.toSet()

        var visibleCount = 0
        for (cc in topCcs) {
            if (cc in selectedCcs) continue
            val rep = allServers.firstOrNull { it.cc == cc && !it.id.equals(AUTO_SERVER_ID, true) } ?: continue
            chipGroup.children.add(buildFrequentChip(rep))
            visibleCount++
        }

        val show = visibleCount > 0
        if (show && !ui.frequentCountriesSection.isVisible) {
            // Animate the strip sliding in from below.
            ui.frequentCountriesSection.alpha = 0f
            ui.frequentCountriesSection.translationY = 12f
            ui.frequentCountriesSection.isVisible = true
            ui.frequentCountriesSection.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(250)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        } else if (!show) {
            ui.frequentCountriesSection.isVisible = false
        }
        // If already visible and chips changed, no animation needed — the chip group
        // was just rebuilt in-place (removeAllViews + addView) which is already fast.
    }

    /**
     * Creates a styled [Chip] for a quick-pick [server].
     *
     * Chips represent the top-frequently-selected countries only.  Whether the country
     * is starred (favourite) has no effect on chip appearance — the star is managed
     * exclusively by the full country list in [CountryServerAdapter].
     */
    private fun buildFrequentChip(server: CountryConfig): RpnControlState {
        val chip = RpnControlState()
        chip.text = getString(R.string.two_argument_space, server.flagEmoji, server.countryName)
        chip.setOnClickListener {
            if (isProxyStopped) {
                showToast(getString(R.string.server_settings_proxy_stopped))
                return@setOnClickListener
            }
            onServerSelected(server, isEnabled = true)
        }
        return chip
    }

    /**
     * Observes [SubscriptionStatusDao.observeCurrentSubscription] via a Room Flow so the
     * banner always reflects the live DB value
     */
    private fun observeSubscription() {
        // Show shimmer banner while data hasn't arrived yet
        ui.shimmerSubscriptionBanner.visibility = View.VISIBLE
        ui.shimmerSubscriptionBanner.startShimmer()
        ui.subscriptionBanner.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                subscriptionStatusDao.observeCurrentSubscription().collectLatest { sub ->
                    if (!isAdded) return@collectLatest
                    uiCtx {
                        ui.shimmerSubscriptionBanner.stopShimmer()
                        ui.shimmerSubscriptionBanner.visibility = View.GONE
                        // Subscription details belong to the account surface, not the
                        // compact RPN connection header.
                        ui.subscriptionBanner.visibility = View.GONE
                        maybeShowResubscribePrompt(sub)
                    }
                }
            }
        }
    }

    /**
     * Populates (or hides) the subscription details banner from [sub].
     */
    private fun updateSubscriptionBanner(sub: SubscriptionStatus?) {
        if (sub == null || sub.purchaseToken.isEmpty()) {
            ui.subscriptionBanner.visibility = View.GONE
            return
        }

        val now = System.currentTimeMillis()
        val billingExpiry = sub.billingExpiry
        val hasExpiry = billingExpiry > 0L && billingExpiry != Long.MAX_VALUE

        // Determine product type (one-time vs recurring subscription)
        val isOneTime = sub.productId.contains("onetime", ignoreCase = true) ||
                sub.productId.contains("inapp", ignoreCase = true)

        // For SUBS: NEVER derive expired from local clock
        // For INAPP: local expiry IS authoritative.
        val isLocallyExpired = isOneTime && hasExpiry && billingExpiry < now

        // DB status tells us the true state (written by state machine after Play reconcile)
        val statusState = SubscriptionStatus.SubscriptionState.fromId(sub.status)
        // For SUBS use DB status for expired check; for INAPP use local clock
        val isEffectivelyExpired = when {
            isOneTime -> isLocallyExpired
            else -> statusState == SubscriptionStatus.SubscriptionState.STATE_EXPIRED ||
                    statusState == SubscriptionStatus.SubscriptionState.STATE_REVOKED
        }

        val showDateRow = when {
            isEffectivelyExpired && !isOneTime -> false   // SUBS expired: badge is enough
            isOneTime -> true                             // always for one-time
            statusState == SubscriptionStatus.SubscriptionState.STATE_CANCELLED ||
            statusState == SubscriptionStatus.SubscriptionState.STATE_GRACE ||
            statusState == SubscriptionStatus.SubscriptionState.STATE_ON_HOLD ||
            statusState == SubscriptionStatus.SubscriptionState.STATE_PAUSED -> true  // show end date
            else -> false  // Active SUBS: hide date row
        }

        if (!showDateRow) {
            ui.tvExpiryLabel.text = getString(R.string.lbl_plan)
            ui.tvExpiryDate.text  = sub.planId.capitalizeWords()
            ui.tvDaysRemaining.isVisible = false
        } else if (!hasExpiry) {
            // Date row needed but expiry not yet known
            ui.tvExpiryDate.text = "-"
            ui.tvExpiryLabel.text = if (isOneTime)
                getString(R.string.lbl_expires_on)
            else
                getString(R.string.server_selection_sub_ends_on)
            ui.tvDaysRemaining.isVisible = false
        } else {
            val dateStr = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                .format(Date(billingExpiry))

            val expiryLabelRes = when {
                isEffectivelyExpired -> R.string.server_selection_sub_expired_on
                isOneTime -> R.string.lbl_expires_on
                statusState == SubscriptionStatus.SubscriptionState.STATE_CANCELLED ||
                statusState == SubscriptionStatus.SubscriptionState.STATE_GRACE ->
                    R.string.server_selection_sub_ends_on
                else -> R.string.server_selection_sub_ends_on
            }
            ui.tvExpiryLabel.text = getString(expiryLabelRes)
            ui.tvExpiryDate.text  = dateStr

            if (!isEffectivelyExpired) {
                val daysLeft = TimeUnit.MILLISECONDS.toDays(billingExpiry - now)
                if (daysLeft >= 0) {
                    ui.tvDaysRemaining.text = if (daysLeft == 0L)
                        getString(R.string.server_selection_sub_expires_today)
                    else
                        getString(R.string.server_selection_sub_days_left, daysLeft)
                    ui.tvDaysRemaining.setTextColor(
                        if (daysLeft <= 7)
                            resolveAttrColor(R.attr.colorGolden)
                        else
                            resolveAttrColor(R.color.primaryText)
                    )
                    ui.tvDaysRemaining.isVisible = true
                } else {
                    ui.tvDaysRemaining.isVisible = false
                }
            } else {
                ui.tvDaysRemaining.isVisible = false
            }
        }

        // Animate banner in if it was previously hidden
        if (!ui.subscriptionBanner.isVisible) {
            ui.subscriptionBanner.alpha = 0f
            ui.subscriptionBanner.visibility = View.VISIBLE
            ui.subscriptionBanner.animate()
                .alpha(1f).setDuration(300)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        }
    }

    /**
     * Shows [RethinkPlusDashboardFragment] (Manage Purchase) once per session when the subscription is in the
     * **Cancelled** state (isAutoRenewing=false, still active until billing period ends).
     */
    private fun maybeShowResubscribePrompt(sub: SubscriptionStatus?) {
        if (sub == null || resubscribePromptShown) return

        val statusState = SubscriptionStatus.SubscriptionState.fromId(sub.status)
        if (statusState != SubscriptionStatus.SubscriptionState.STATE_CANCELLED) return

        // INAPP products (one-time purchases) cannot be resubscribed
        val isOneTime = sub.productId.contains("onetime", ignoreCase = true) ||
                sub.productId.contains("inapp", ignoreCase = true)
        if (isOneTime) return

        if (!isAdded || isStateSaved) return

        val purchaseDetail = RpnProxyManager.getSubscriptionData()?.purchaseDetail
        if (purchaseDetail == null) {
            Logger.w(LOG_TAG_UI, "$TAG.maybeShowResubscribePrompt: purchaseDetail unavailable, skipping prompt")
            return
        }

        // Gate 1: the machine must carry the cancellation in SOME form — either the
        // machine STATE is Cancelled (server-side cancel via Manage Purchase) or the
        // machine data status is CANCELLED (Play-side cancel: reconcile fires
        // PaymentSuccessful which keeps the machine STATE Active but writes CANCELLED
        // to the row). Both legitimate cancellation paths satisfy one of the two.
        val machineState = RpnProxyManager.getSubscriptionState()
        val machineDataCancelled = RpnProxyManager.getSubscriptionData()
            ?.subscriptionStatus?.status == SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id
        if (!machineState.isCancelled && !machineDataCancelled) {
            Logger.i(LOG_TAG_UI, "$TAG.maybeShowResubscribePrompt: machine=${machineState.name} " +
                    "does not confirm DB CANCELLED, skipping prompt")
            return
        }

        // Gate 2: Play must confirm no auto-renewal for this purchase. If Play still
        // reports isAutoRenewing=true, the CANCELLED row is stale or was written
        // without Play confirmation; the next reconcile restores ACTIVE. Do not set
        // resubscribePromptShown here so the prompt can fire later if Play confirms.
        if (purchaseDetail.isAutoRenewing) {
            Logger.w(LOG_TAG_UI, "$TAG.maybeShowResubscribePrompt: DB CANCELLED but Play reports " +
                    "isAutoRenewing=true for token=${purchaseDetail.purchaseToken.take(8)}, skipping prompt")
            return
        }

        // Gate 3: the DB row must belong to the purchase the machine knows about,
        // otherwise the prompt would describe a different purchase than the row read.
        if (sub.purchaseToken.isNotEmpty() &&
            purchaseDetail.purchaseToken.isNotEmpty() &&
            sub.purchaseToken != purchaseDetail.purchaseToken
        ) {
            Logger.w(LOG_TAG_UI, "$TAG.maybeShowResubscribePrompt: DB row token != machine purchase " +
                    "token, skipping prompt")
            return
        }

        resubscribePromptShown = true
        Logger.i(LOG_TAG_UI, "$TAG.maybeShowResubscribePrompt: showing resubscribe prompt for status: ${statusState.name} productId=${purchaseDetail.productId}, planId=${purchaseDetail.planId}")

        try {
            val intent = FragmentHostActivity.createIntent(
                context = requireContext(),
                fragmentClass = RethinkPlusDashboardFragment::class.java,
                args = RethinkPlusDashboardFragment.createBundle(showManagePurchase = true)
            )
            startActivity(intent)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "$TAG.maybeShowResubscribePrompt: error opening dashboard: ${e.message}", e)
            resubscribePromptShown = false  // allow retry on next emission
        }
    }

    private fun observeServerRemovedEvents() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                RpnProxyManager.serverRemovedEvent.collect { removedConfigs ->
                    if (!isAdded || requireActivity().isFinishing) return@collect
                    Logger.w(
                        LOG_TAG_UI,
                        "$TAG.observeServerRemovedEvents: ${removedConfigs.size} server(s) removed from tunnel list"
                    )
                    // Fetch the refreshed list (already synced to DB+cache by updateWinProxy)
                    val refreshedServers = try {
                        withContext(Dispatchers.IO) { RpnProxyManager.getWinServers() }
                    } catch (e: Exception) {
                        Logger.w(LOG_TAG_UI, "$TAG.observeServerRemovedEvents: could not fetch updated servers: ${e.message}")
                        emptyList()
                    }
                    val selectedList = try {
                        withContext(Dispatchers.IO) { RpnProxyManager.getEnabledConfigs() }
                    } catch (e: Exception) {
                        Logger.w(LOG_TAG_UI, "$TAG.observeServerRemovedEvents: could not fetch selectedList: ${e.message}")
                        emptySet()
                    }

                    uiCtx {
                        if (!isAdded || requireActivity().isFinishing) return@uiCtx
                        // Guard: don't stack duplicate sheets
                        if (parentFragmentManager.findFragmentByTag("ServerRemovalNotification") != null) {
                            Logger.d(LOG_TAG_UI, "$TAG.observeServerRemovedEvents: sheet already showing, skipping")
                            // Still refresh the list even if the sheet is already up
                            if (refreshedServers.isNotEmpty()) initServers(refreshedServers, selectedList)
                            return@uiCtx
                        }
                        try {
                            showServerRemovalNotifBottomSheet(
                                removedServers   = removedConfigs,
                                refreshedServers = refreshedServers,
                                selectedList     = selectedList
                            )
                        } catch (e: Exception) {
                            Logger.e(LOG_TAG_UI, "$TAG.observeServerRemovedEvents: error showing sheet: ${e.message}", e)
                            // Fall back: just refresh the list so removed servers are gone from UI
                            if (refreshedServers.isNotEmpty()) initServers(refreshedServers, selectedList)
                        }
                    }
                }
            }
        }
    }

    /**
     * Shows the inline progress bar pinned to the bottom of the hero banner
     * and starts a background polling loop that waits for WIN registration and the
     * server-list to become available.
     *
     * [setLoadingState] is called immediately so shimmers always cover any potentially
     * stale server list, giving the user a clear "something is loading" signal regardless
     * of which code path triggered this call.
     */
    private fun showServerLoadingDialog() {
        if (serverLoadingJob?.isActive == true) {
            Logger.d(LOG_TAG_UI, "$TAG: registration progress already running, skipping")
            return
        }
        dismissServerLoadingDialog() // cancel any stale job

        if (!isAdded) return

        // Set status to REGISTERING or Loading in the header for smooth feedback.
        if (isWinRegistered) {
            updateConnectionStatus(ConnectionUiState.CONNECTING)
        } else {
            updateConnectionStatus(ConnectionUiState.REGISTERING)
        }

        setLoadingState(true, skipHeader = true)
        ui.registrationProgressBar.show()
        // Prevent start/stop proxy FAB taps while registration is in progress.
        ui.fabStopProxy.isClickable  = false
        ui.fabStartProxy.isClickable = false

        Logger.i(
            LOG_TAG_UI,
            "$TAG: showRegistrationProgress; waiting up to ${LOADING_DIALOG_TIMEOUT_MS / 1000}s"
        )

        serverLoadingJob = lifecycleScope.launch {
            val startTime = System.currentTimeMillis()

            while (true) {
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed >= LOADING_DIALOG_TIMEOUT_MS) break

                // Poll registration status and server list
                val hasTunnel = withContext(Dispatchers.IO) {
                    try { VpnController.hasTunnel() } catch (_: Exception) { false }
                }
                val winRegistered = withContext(Dispatchers.IO) {
                    try { VpnController.isWinRegistered() } catch (_: Exception) { false }
                }
                val servers = withContext(Dispatchers.IO) {
                    try { RpnProxyManager.getWinServers() } catch (_: Exception) { emptyList() }
                }
                val hasRealServers = servers.any { !it.id.equals(AUTO_SERVER_ID, true) }

                Logger.d(
                    LOG_TAG_UI,
                    "$TAG: registration poll, hasTunnel=$hasTunnel, winRegistered=$winRegistered, " +
                        "realServers=${servers.count { !it.id.equals(AUTO_SERVER_ID, true) }}, elapsed=${elapsed}ms"
                )

                if (!hasTunnel) {
                    Logger.w(LOG_TAG_UI, "$TAG: registration; VPN tunnel lost, showing no-tunnel error")
                    withContext(Dispatchers.Main) {
                        if (!isAdded) return@withContext
                        dismissServerLoadingDialog()
                        setLoadingState(false)
                        showErrorState(noTunnel = true)
                    }
                    return@launch
                }

                if (winRegistered && hasRealServers) {
                    // Registration complete and servers available, load the list.
                    val selectedList = withContext(Dispatchers.IO) {
                        try { RpnProxyManager.getEnabledConfigs() } catch (_: Exception) { emptySet() }
                    }
                    withContext(Dispatchers.Main) {
                        if (!isAdded) return@withContext
                        Logger.i(LOG_TAG_UI, "$TAG: registration complete (elapsed=${elapsed}ms)")
                        isWinRegistered = true
                        dismissServerLoadingDialog()
                        initServers(servers, selectedList)
                    }
                    return@launch
                }

                delay(LOADING_DIALOG_POLL_INTERVAL_MS)
            }

            // Timed out; hide the bar and surface whatever state we have.
            Logger.w(LOG_TAG_UI, "$TAG: registration timed out after ${LOADING_DIALOG_TIMEOUT_MS / 1000}s")
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                dismissServerLoadingDialog()
                setLoadingState(false)
                if (!RpnProxyManager.isRpnActive()) {
                    isProxyStopped = true
                    applyProxyStoppedUi()
                } else if (!VpnController.hasTunnel()) {
                    showErrorState(noTunnel = true)
                } else {
                    showErrorState()
                }
            }
        }
    }

    /**
     * Cancels the polling job and hides the inline registration progress bar.
     * Restores FAB interactivity. Safe to call when the bar is not showing.
     */
    private fun dismissServerLoadingDialog() {
        serverLoadingJob?.cancel()
        serverLoadingJob = null
        runCatching {
            if (isAdded && view != null) {
                ui.registrationProgressBar.hide()
                ui.fabStopProxy.isClickable  = true
                ui.fabStartProxy.isClickable = true
                updateVpnStatus()
            }
        }
    }

    private fun showRpnResetDialog() {
        if (!isAdded) return
        if (rpnResetDialog?.isShowing == true) {
            Logger.d(LOG_TAG_UI, "$TAG: reset dialog already showing, skipping duplicate call")
            return
        }
        dismissRpnResetDialog()

        val progress = RpnProgressState(requireContext())
        val tvTitle = progress.title
        val tvStatus = progress.status
        val tvHint = progress.hint
        val timeoutBar = progress.timeout

        tvTitle.text  = getString(R.string.rpn_restore_dialog_title)
        tvHint.text   = getString(R.string.rpn_restore_dialog_hint)
        timeoutBar.max = LOADING_DIALOG_TIMEOUT_MS.toInt()
        timeoutBar.setProgressCompat(0, false)

        val dialog = rpnProgressDialog(requireActivity(), progress)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        UIUtils.capDialogWidth(dialog)
        dialog.setOnCancelListener {
            Logger.i(LOG_TAG_UI, "$TAG: reset dialog dismissed by user, switching to inline bar")
            resetDialogDismissedByUser = true
            dismissRpnResetDialog()
            if (isAdded && view != null) ui.registrationProgressBar.show()
        }
        rpnResetDialog = dialog

        Logger.i(LOG_TAG_UI, "$TAG: showRpnResetDialog; timeout=${LOADING_DIALOG_TIMEOUT_MS / 1000}s")

        val statusMessages = listOf(
            getString(R.string.rpn_restore_dialog_status_unregistering),
            getString(R.string.rpn_restore_dialog_status_fetching),
            getString(R.string.rpn_restore_dialog_status_registering),
            getString(R.string.rpn_restore_dialog_status_refreshing),
        )

        rpnResetJob = lifecycleScope.launch {
            val startTime = System.currentTimeMillis()
            var msgIdx = 0
            while (serverSelectionViewModel.resetState.value is ServerSelectionViewModel.ResetState.InProgress) {
                val elapsed = System.currentTimeMillis() - startTime

                val statusMsg = when {
                    elapsed > LOADING_DIALOG_TIMEOUT_MS * 0.75 ->
                        getString(R.string.server_loading_dialog_status_timeout)
                    else -> statusMessages[msgIdx % statusMessages.size]
                }

                if (isAdded) {
                    tvStatus.animate().alpha(0f).setDuration(120).withEndAction {
                        if (isAdded) {
                            tvStatus.text = statusMsg
                            tvStatus.animate().alpha(0.65f).setDuration(120).start()
                        }
                    }.start()
                    timeoutBar.setProgressCompat(
                        elapsed.coerceAtMost(LOADING_DIALOG_TIMEOUT_MS).toInt(), true
                    )
                }

                delay(LOADING_DIALOG_POLL_INTERVAL_MS.milliseconds)
                msgIdx++
            }
        }
    }

    /** Cancels the reset job and safely dismisses the reset progress dialog. */
    private fun dismissRpnResetDialog() {
        rpnResetJob?.cancel()
        rpnResetJob = null
        runCatching {
            if (rpnResetDialog?.isShowing == true) rpnResetDialog?.dismiss()
        }
        rpnResetDialog = null
    }


    private fun io(f: suspend () -> Unit): Job =
        lifecycleScope.launch(Dispatchers.IO) { f() }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) {
            if (isAdded && view != null) {
                f()
            }
        }
    }

    private fun resolveAttrColor(attrRes: Int): Int {
        UIUtils.fetchColor(requireContext(), attrRes).let { return it }
    }
}
