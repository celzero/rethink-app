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

import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import android.animation.ObjectAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context.CLIPBOARD_SERVICE
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.database.CountryConfig
import com.celzero.bravedns.database.CountryConfigRepository
import com.celzero.bravedns.database.SubscriptionStatus
import com.celzero.bravedns.database.SubscriptionStatusDao
import com.celzero.bravedns.databinding.FragmentServerSelectionBinding
import com.celzero.bravedns.iab.InAppBillingHandler
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.rpnproxy.RpnProxyManager.AUTO_SERVER_ID
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.activity.FragmentHostActivity
import com.celzero.bravedns.ui.adapter.CountryServerAdapter
import com.celzero.bravedns.ui.adapter.VpnServerAdapter
import com.celzero.bravedns.ui.bottomsheet.ServerRemovalNotificationBottomSheet
import com.celzero.bravedns.ui.bottomsheet.ServerSettingsBottomSheet
import com.celzero.bravedns.util.SnackbarHelper
import com.celzero.bravedns.util.SnackbarHelper.capitalizeWords
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.ServerSelectionViewModel
import com.celzero.firestack.backend.Backend
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.toString

/**
 * Fragment for selecting VPN servers from a list.
 */
class ServerSelectionFragment : Fragment(R.layout.fragment_server_selection),
    VpnServerAdapter.ServerSelectionListener,
    CountryServerAdapter.CitySelectionListener {

    private val subscriptionStatusDao by inject<SubscriptionStatusDao>()
    private val countryConfigRepository by inject<CountryConfigRepository>()
    private val b by viewBinding(FragmentServerSelectionBinding::bind)
    private val serverSelectionViewModel: ServerSelectionViewModel by activityViewModel()

    private lateinit var serverAdapter: CountryServerAdapter
    private lateinit var selectedAdapter: VpnServerAdapter

    private val allServers = mutableListOf<CountryConfig>()
    private val unselectedServers = mutableListOf<CountryConfig>()
    private val selectedServers = mutableListOf<CountryConfig>()

    private var statusUpdateJob: Job? = null
    private var headerScrollListener: NestedScrollView.OnScrollChangeListener? = null

    /** Looping alpha blink on the header status dot while connected. */
    private var blinkAnimator: ObjectAnimator? = null

    /** Job driving the registration / server-list polling loop. */
    private var serverLoadingJob: Job? = null
    /** Job driving the RPN reset progress loop. */
    private var rpnResetJob: Job? = null
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
    /** Looping spin animator running on the FAB icon while stop/start is in progress. */
    private var fabLoadingAnimator: ObjectAnimator? = null

    private var isWinRegistered = false
    private var autoServer: CountryConfig? = null

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
    private var winIdentifier: String? = null

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

        /** UI connection states surfaced by [updateConnectionStatus]. */
        private enum class ConnectionUiState { DISCONNECTED, CONNECTING, CONNECTED, REGISTERING, FAILED }

        /** Maximum time the inline registration progress will poll before giving up. */
        private const val LOADING_DIALOG_TIMEOUT_MS = 20_000L
        /** Interval between registration / server-list poll iterations. */
        private const val LOADING_DIALOG_POLL_INTERVAL_MS = 1_500L
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        isProxyStopped = RpnProxyManager.rpnMode().isNone()

        val density = resources.displayMetrics.density
        // 16dp baseline margin for FABs.
        val fabMarginPx = (16f * density + 0.5f).toInt()

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val navBarBottom  = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val navViewHeight = requireActivity().findViewById<View>(R.id.nav_view)?.height ?: 0
            val fabBottom = navBarBottom + navViewHeight + fabMarginPx
            Logger.d(LOG_TAG_UI, "$TAG.onViewCreated: navBarBottom=$navBarBottom, navViewHeight=$navViewHeight, fabBottom=$fabBottom")

            // Apply margins to both FABs so they clear nav bar + bottom nav.
            listOf(b.fabStopProxy, b.fabStartProxy).forEach { fab ->
                (fab.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let { lp ->
                    lp.bottomMargin = fabBottom
                    lp.marginEnd    = fabMarginPx
                    fab.layoutParams = lp
                }
            }
            insets
        }

        // FAB position is handled exclusively via bottomMargin in the listener above.
        ViewCompat.setOnApplyWindowInsetsListener(b.fabStopProxy)  { _, _ -> WindowInsetsCompat.CONSUMED }
        ViewCompat.setOnApplyWindowInsetsListener(b.fabStartProxy) { _, _ -> WindowInsetsCompat.CONSUMED }

        // Force inset dispatch, needed on some OEM devices where the system does not
        // automatically re-dispatch insets after the fragment view is attached.
        ViewCompat.requestApplyInsets(b.root)

        applyScrollPadding()

        setupNavigationButtons()
        setupSearchBar()
        updateFilterButtonState()
        setupHeaderUI()
        setupRpnState()

        // Show the correct FAB immediately (no animation on first load).
        if (isProxyStopped) {
            applyProxyStoppedUi()
            b.fabStopProxy.visibility  = View.GONE
            b.fabStartProxy.visibility = View.VISIBLE
        } else {
            b.fabStopProxy.visibility  = View.VISIBLE
            b.fabStartProxy.visibility = View.GONE
        }

        // Apply bottom padding so the last list item is not hidden behind the
        // BottomNavigationView (which floats over the fragment container).
        requireActivity().findViewById<View>(R.id.nav_view)?.let { navView ->
            navView.post {
                if (!isAdded) return@post
                b.serversScrollView.setPadding(
                    b.serversScrollView.paddingLeft,
                    b.serversScrollView.paddingTop,
                    b.serversScrollView.paddingRight,
                    navView.height + 300
                )
            }
        }

        // Fade in the pinned collapsed title (flag + count) as the hero scrolls away.
        headerScrollListener = NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, _ ->
            val bar = b.collapsedTitleBar
            val range = (b.headerContainer.height - bar.height).coerceAtLeast(1)
            bar.alpha = (scrollY.toFloat() / range).coerceIn(0f, 1f)
        }
        b.serversScrollView.setOnScrollChangeListener(headerScrollListener)

        animateHeaderEntry()
        observeRefreshState()
        observeResetState()
    }

    private fun applyScrollPadding() {
        b.serversScrollView.post {
            b.serversScrollView.setPadding(
                b.serversScrollView.paddingLeft,
                20,
                b.serversScrollView.paddingRight,
                b.serversScrollView.paddingBottom
            )
        }
    }

    private fun setupRpnState() {
        setupRecyclerViews()
        setLoadingState(true)
        observeSubscription()
        observeServerRemovedEvents()

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
                                b.fabStopProxy.isClickable  = false
                                b.fabStartProxy.isClickable = false
                            }
                            if (resetDialogDismissedByUser) {
                                // User explicitly dismissed the dialog; show inline bar
                                // as a subtle indicator; the dialog will NOT re-appear.
                                Logger.i(LOG_TAG_UI, "$TAG.observeResetState: InProgress, dialog dismissed, showing inline bar")
                                if (isAdded && view != null) {
                                    b.registrationProgressBar.show()
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
                                b.registrationProgressBar.hide()
                                b.fabStopProxy.isClickable  = true
                                b.fabStartProxy.isClickable = true
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
                                b.registrationProgressBar.hide()
                                b.fabStopProxy.isClickable  = true
                                b.fabStartProxy.isClickable = true
                                // Restore search and action icons
                                setSearchAndActionsEnabled(true)
                            }
                            showErrorState(noTunnel = true)
                        }
                        is ServerSelectionViewModel.ResetState.Idle -> {
                            // Ensure FABs are interactive when no reset is active.
                            if (isAdded && view != null) {
                                b.fabStopProxy.isClickable  = true
                                b.fabStartProxy.isClickable = true
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
            b.serversScrollView.setOnScrollChangeListener(null as NestedScrollView.OnScrollChangeListener?)
            headerScrollListener = null
            fabLoadingAnimator?.cancel()
            fabLoadingAnimator = null
            blinkAnimator?.cancel()
            blinkAnimator = null
            b.fabStopProxy.animate().cancel()
            b.fabStartProxy.animate().cancel()
            b.statusIndicator.animate().cancel()
            b.statusCard.animate().cancel()
            b.searchCard.animate().cancel()
            b.searchClearBtn.animate().cancel()
        }
        runCatching {
            b.rvServers.suppressLayout(false)
            b.rvSelectedServers.suppressLayout(false)
        }
        statusUpdateJob?.cancel()
        statusUpdateJob = null
        tunnelWatchJob?.cancel()
        tunnelWatchJob = null
        dismissServerLoadingDialog()
        dismissRpnResetDialog()
        super.onDestroyView()
    }

    private fun setLoadingState(loading: Boolean, skipHeader: Boolean = false) {
        if (!isAdded) return
        isLoading = loading

        if (loading) {
            // Header shimmer
            if (!skipHeader) {
                b.shimmerHeader.isVisible = true
                b.shimmerHeader.startShimmer()
                b.locationContent.isVisible = false
            } else {
                b.shimmerHeader.stopShimmer()
                b.shimmerHeader.isVisible = false
                b.locationContent.isVisible = true
            }

            // hide real list and hint cards
            b.shimmerServerList.isVisible = true
            b.shimmerServerList.startShimmer()
            b.rvServers.isVisible = false
            b.emptySelectionCard.isVisible = false
            b.rvSelectedServers.isVisible = false
            b.selectedLocationsHeader.isVisible = false
            b.frequentCountriesSection.isVisible = false
            b.locationCapacityIndicator.isVisible = false
            b.errorStateContainer.isVisible = false

            // Disable search bar and action icons while data is loading
            setSearchAndActionsEnabled(false)
        } else {
            // Stop and hide header shimmer, reveal real content
            b.shimmerHeader.stopShimmer()
            b.shimmerHeader.isVisible = false
            b.locationContent.isVisible = true

            // Stop and hide list shimmer, reveal real list
            b.shimmerServerList.stopShimmer()
            b.shimmerServerList.isVisible = false
            b.rvServers.isVisible = true

            // Re-enable search bar and action icons once data is ready
            setSearchAndActionsEnabled(true)
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
        b.searchCard.alpha              = alpha
        b.searchCard.isEnabled          = enabled
        b.searchBar.isEnabled           = enabled
        b.searchBar.isFocusable         = enabled
        b.searchBar.isFocusableInTouchMode = enabled
        b.settingsBtn.alpha             = alpha
        b.settingsBtn.isEnabled         = enabled
        b.searchFilterBtn.alpha         = alpha
        b.searchFilterBtn.isEnabled     = enabled
        b.addLocationBtn.alpha          = alpha
        b.addLocationBtn.isEnabled      = enabled
    }

    private fun initServers(servers: List<CountryConfig>, selectedList: Set<CountryConfig> = emptySet()) {
        io {
            Logger.v(LOG_TAG_UI, "$TAG.initServers: ${servers.size} servers, selected=${selectedList.size}, WIN=$isWinRegistered")

            val hasRealServers = servers.any { it.id != AUTO_SERVER_ID }

            if (!hasRealServers) {
                uiCtx {
                    if (!isAdded) return@uiCtx
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

                updateHeaderSummary()
                selectedAdapter.updateServers(selectedServers)
                serverAdapter.updateCountries(buildCountries(unselectedServers))
                updateAllServersCount()
                updateSelectedSectionVisibility()
                setLoadingState(false)
                // isLoading must be false before the summary refresh so the
                // location-capacity scale becomes visible with the loaded data.
                updateVpnStatus()
                // Re-apply stopped UI on top of fully-loaded state
                if (isProxyStopped) applyProxyStoppedUi()
                // Notify adapter which server items are still waiting for tunnel setup,
                // then start a short-lived polling job to clear them as they come up.
                selectedAdapter.setLoadingTunnelKeys(pendingTunnelKeys)
                if (pendingTunnelKeys.isNotEmpty()) startTunnelWatchJob(pendingTunnelKeys)
                b.rvServers.post { b.rvServers.requestLayout() }
                b.rvSelectedServers.post { b.rvSelectedServers.requestLayout() }
                // Show frequently-selected countries as quick-pick chips
                if (!isProxyStopped) loadAndShowFrequentChips()
            }
            Logger.v(LOG_TAG_UI, "$TAG.initServers: complete")
        }
    }

    private fun setupHeaderUI() {
        populateHeroPlanAccountRow()
        // Keep the hero header (status dot, duration, summary) in sync with the backend.
        statusUpdateJob = lifecycleScope.launch {
            while (true) {
                delay(3_000.milliseconds)
                if (isAdded && !isLoading) {
                    updateConnectionStatusOnly()
                    updateConnectionDuration()
                }
            }
        }
    }

    private fun populateHeroPlanAccountRow() {
        if (!isAdded) return
        val sub = RpnProxyManager.getSubscriptionData()?.subscriptionStatus
        if (sub == null || sub.purchaseToken.isEmpty()) {
            b.tvHeroPlanName.text = ""
            b.tvHeroAccountId.text = ""
            return
        }
        val raw = sub.productTitle.ifBlank { sub.planId.ifBlank { sub.productId } }
        val planLabel = when (raw) {
            InAppBillingHandler.ONE_TIME_PRODUCT_2YRS -> "One-Time 2 years"
            InAppBillingHandler.ONE_TIME_PRODUCT_5YRS -> "One-Time 5 years"
            InAppBillingHandler.SUBS_PRODUCT_YEARLY -> "Subscription Yearly"
            InAppBillingHandler.SUBS_PRODUCT_MONTHLY -> "Subscription Monthly"
            else -> ""
        }
        if (planLabel.isEmpty()) {
            b.tvHeroPlanName.visibility = View.GONE
        } else {
            b.tvHeroPlanName.visibility = View.VISIBLE
            b.tvHeroPlanName.text = planLabel
        }
        val accountId = sub.accountId.take(12)
        // Clear while we fetch the real device ID from SecureIdentityStore on IO.
        b.tvHeroAccountId.text = accountId.ifEmpty { "" }
        io {
            val realDeviceId = runCatching { InAppBillingHandler.getObfuscatedDeviceId() }.getOrDefault("")
            val deviceId = realDeviceId.take(4)
            if (winIdentifier.isNullOrEmpty()) {
                winIdentifier = VpnController.getWinIdentifier()
            }
            val who = winIdentifier
            uiCtx {
                if (!isAdded) return@uiCtx
                b.tvHeroAccountId.text = if (accountId.isNotEmpty()) "$accountId • $deviceId" else ""

                if (who.isNullOrEmpty()) {
                    b.tvHeroWho.visibility = View.GONE
                } else {
                    b.tvHeroWho.visibility = View.VISIBLE
                    b.tvHeroWho.text = who
                }
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
            // Fully connected tunnel
            vpnState.on -> ConnectionUiState.CONNECTED
            // VPN start has been requested but tunnel not yet up
            vpnState.activationRequested && !vpnState.on -> ConnectionUiState.CONNECTING
            // NEW state = tunnel was just created, still handshaking
            vpnState.connectionState == BraveVPNService.State.NEW -> ConnectionUiState.CONNECTING
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
                        b.tvActiveDuration.text = ""
                        return@uiCtx
                    }
                    // returns a string describing 'since' as a time relative to 'now'
                    val relative = DateUtils.getRelativeTimeSpanString(
                        since,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS,
                        DateUtils.FORMAT_ABBREV_RELATIVE
                    )
                    b.tvActiveDuration.text = getString(
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

    private fun updateConnectionStatus(uiState: ConnectionUiState) {
        if (!isAdded) return
        when (uiState) {
            ConnectionUiState.CONNECTED -> {
                b.tvConnectionStatus.text = getString(R.string.lbl_active)
                b.tvConnectionStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.accentGood))
                b.statusIndicator.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.accentGood)
                b.tvActiveDuration.alpha = 1f
                startStatusBlink()
            }
            ConnectionUiState.CONNECTING -> {
                b.tvConnectionStatus.text = getString(R.string.lbl_connecting)
                b.tvConnectionStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorAmber_900))
                b.statusIndicator.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.colorAmber_900)
                stopStatusBlink()
                // Pulse animation to indicate in-progress state
                b.statusIndicator.animate().scaleX(1.2f).scaleY(1.2f).setDuration(600).withEndAction {
                    if (isAdded) b.statusIndicator.animate().scaleX(0.8f).scaleY(0.8f).setDuration(600).withEndAction {
                        if (isAdded) b.statusIndicator.animate().scaleX(1f).scaleY(1f).setDuration(300).start()
                    }.start()
                }.start()
            }
            ConnectionUiState.REGISTERING -> {
                b.tvConnectionStatus.text = getString(R.string.rpn_restore_dialog_status_registering)
                b.tvConnectionStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorAmber_900))
                b.statusIndicator.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.colorAmber_900)
                b.tvActiveDuration.alpha = 1f
                startStatusBlink()
            }
            ConnectionUiState.FAILED -> {
                b.tvConnectionStatus.text = getString(R.string.ping_status_failed)
                b.tvConnectionStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.accentBad))
                b.statusIndicator.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.accentBad)
                stopStatusBlink()
                b.tvActiveDuration.text = ""
            }
            ConnectionUiState.DISCONNECTED -> {
                b.tvConnectionStatus.text = getString(R.string.lbl_inactive)
                b.tvConnectionStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.accentBad))
                b.statusIndicator.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.accentBad)
                stopStatusBlink()
            }
        }
    }

    /** Starts a gentle repeating alpha blink on the header status dot. */
    private fun startStatusBlink() {
        if (!isAdded) return
        if (blinkAnimator?.isRunning == true) return
        b.statusIndicator.alpha = 1f
        blinkAnimator = ObjectAnimator.ofFloat(b.statusIndicator, View.ALPHA, 1f, 0.25f).apply {
            duration = 900L
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            start()
        }
    }

    /** Stops the status-dot blink and restores full opacity. */
    private fun stopStatusBlink() {
        blinkAnimator?.cancel()
        blinkAnimator = null
        if (isAdded) b.statusIndicator.alpha = 1f
    }

    /**
     * Refreshes the premium hero summary: connected-location count, overlapping
     * country avatars, tier/ID block and the location-capacity scale.
     */
    private fun updateHeaderSummary() {
        if (!isAdded) return
        b.locationContent.visibility = View.VISIBLE

        val nonAutoServers = selectedServers.filter { !it.id.equals(AUTO_SERVER_ID, ignoreCase = true) }
        val distinctCountries = nonAutoServers.distinctBy { it.cc }

        // Collapsed app-bar title: first selected location's flag + location count.
        val collapsedFlag = distinctCountries.firstOrNull()?.flagEmoji.orEmpty()
        b.tvCollapsedFlag.text = collapsedFlag
        b.tvCollapsedFlag.isVisible = collapsedFlag.isNotEmpty()
        b.tvCollapsedTitle.text = if (distinctCountries.isEmpty()) "" else resources.getQuantityString(
            R.plurals.server_count, distinctCountries.size, distinctCountries.size
        )
        populateAvatarRow(distinctCountries)
        updateCapacityIndicator()
    }

    /**
     * Rebuilds the overlapping circular avatar strip. Each circle shows the country
     * flag emoji as its background with the ISO country code overlaid as foreground.
     */
    private fun populateAvatarRow(countries: List<CountryConfig>) {
        if (!isAdded) return
        val row = b.avatarRow
        row.removeAllViews()
        val density = resources.displayMetrics.density
        countries.take(MAX_SELECTIONS).forEachIndexed { index, config ->
            if (config.cc.isBlank()) return@forEachIndexed
            val avatar = FrameLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (38f * density).toInt(), (38f * density).toInt()
                ).apply { marginStart = if (index == 0) 0 else -(10f * density).toInt() }
                background = AppCompatResources.getDrawable(requireContext(), R.drawable.bg_avatar_circle)
                clipChildren = false
            }
            val flag = AppCompatTextView(requireContext()).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
                )
                gravity = Gravity.CENTER
                textSize = 22f
                text = config.flagEmoji
            }
            val iso = AppCompatTextView(requireContext()).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
                )
                gravity = Gravity.CENTER
                textSize = 10f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
                setShadowLayer(2f * density, 0f, 1f * density, Color.argb(128, 0, 0, 0))
                text = config.cc.uppercase(Locale.US)
            }
            avatar.addView(flag)
            avatar.addView(iso)
            row.addView(avatar)
        }
    }

    /** Updates the minimalist "N of M" capacity pills below the connection list. */
    private fun updateCapacityIndicator() {
        if (!isAdded) return
        val filled = selectedServers.count { !it.id.equals(AUTO_SERVER_ID, ignoreCase = true) }
            .coerceIn(0, MAX_SELECTIONS)
        b.locationCapacityIndicator.isVisible = !isLoading && !isProxyStopped
        b.tvCapacityLabel.text = String.format(Locale.US, "%d of %d locations", filled, MAX_SELECTIONS)
        val dots = listOf(
            b.capacityDotOne, b.capacityDotTwo, b.capacityDotThree,
            b.capacityDotFour, b.capacityDotFive
        )
        dots.forEachIndexed { index, dot ->
            if (index < filled) {
                dot.alpha = 1f
                dot.backgroundTintList =
                    ContextCompat.getColorStateList(requireContext(), R.color.accentGood)
            } else {
                // Theme-aware "empty" tint: white is invisible on the light theme's
                // background, so use the adaptive on-surface-variant color instead.
                dot.alpha = 0.25f
                dot.backgroundTintList =
                    ColorStateList.valueOf(resolveAttrColor(R.attr.primaryLightColorText))
            }
        }
    }

    private fun animateHeaderEntry() {
        if (!isAdded) return
        b.statusCard.alpha = 0f
        b.statusCard.scaleX = 0.9f
        b.statusCard.scaleY = 0.9f
        b.statusCard.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(400)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    private fun setupNavigationButtons() {
        b.supportBtn.setOnClickListener { openAccount() }
        b.settingsBtn.setOnClickListener { showServerSettingsBottomSheet() }
        b.manageSelectedBtn.setOnClickListener {
            b.serversScrollView.smoothScrollTo(0, b.selectedLocationsHeader.top)
        }
        b.addLocationBtn.setOnClickListener { focusLocationSearch() }
        b.fabStopProxy.setOnClickListener  { onToggleProxyFabClicked() }
        b.fabStartProxy.setOnClickListener { onToggleProxyFabClicked() }
        // Status chip: open settings when running, show a hint when stopped
        b.statusChip.setOnClickListener {
            if (isProxyStopped) {
                showToast(getString(R.string.server_settings_proxy_stopped))
            } else {
                showServerSettingsBottomSheet()
            }
        }

        b.tvHeroWho.setOnClickListener {
            val text = b.tvHeroWho.text?.toString().orEmpty()
            if (text.isBlank()) return@setOnClickListener
            val clipboard = requireContext().getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("who", text))
            Utilities.showToastUiCentered(
                requireContext(),
                getString(R.string.copied_clipboard),
                Toast.LENGTH_SHORT
            )
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
        if (!isAdded) return
        b.serversScrollView.smoothScrollTo(0, b.allLocationsHeader.top)
        b.searchBar.requestFocus()
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
        b.fabStopProxy.isClickable  = false
        b.fabStartProxy.isClickable = false
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
        val activeFab: View = if (isProxyStopped) b.fabStartProxy else b.fabStopProxy
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
        b.fabStopProxy.isClickable  = true
        b.fabStartProxy.isClickable = true
        toggleProxyInFlight = false

        if (isAdded) {
            // Cancel any ViewPropertyAnimator still running on either FAB and reset
            // all transform properties to their resting state so the subsequent
            // applyFabRunningState / applyFabStoppedState swap animations start clean.
            b.fabStopProxy.animate().cancel()
            b.fabStartProxy.animate().cancel()
            b.fabStopProxy.alpha  = 1f; b.fabStopProxy.scaleX  = 1f; b.fabStopProxy.scaleY  = 1f
            b.fabStartProxy.alpha = 1f; b.fabStartProxy.scaleX = 1f; b.fabStartProxy.scaleY = 1f
        }
    }

    /** Pops in the stop FAB (red circle) and pops out the start FAB. */
    private fun applyFabRunningState() {
        if (!isAdded) return
        // Cancel any running animation so we start from a clean resting state.
        b.fabStartProxy.animate().cancel()
        b.fabStartProxy.scaleX = 1f
        b.fabStartProxy.scaleY = 1f
        b.fabStartProxy.alpha  = 1f
        // Shrink + fade the start FAB out.
        b.fabStartProxy.animate()
            .scaleX(0f).scaleY(0f).alpha(0f)
            .setDuration(200)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction { if (isAdded) b.fabStartProxy.visibility = View.GONE }
            .start()
        // Pop the stop FAB in with a short delay so both animations overlap and the
        // screen is never empty
        b.fabStopProxy.scaleX = 0f
        b.fabStopProxy.scaleY = 0f
        b.fabStopProxy.alpha  = 0f
        b.fabStopProxy.visibility = View.VISIBLE
        b.fabStopProxy.animate().cancel()
        b.fabStopProxy.animate()
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
        b.fabStopProxy.animate().cancel()
        b.fabStopProxy.scaleX = 1f
        b.fabStopProxy.scaleY = 1f
        b.fabStopProxy.alpha  = 1f
        // Shrink + fade the stop FAB out.
        b.fabStopProxy.animate()
            .scaleX(0f).scaleY(0f).alpha(0f)
            .setDuration(200)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction { if (isAdded) b.fabStopProxy.visibility = View.GONE }
            .start()
        // Pop the start FAB in with overlap.
        b.fabStartProxy.scaleX = 0f
        b.fabStartProxy.scaleY = 0f
        b.fabStartProxy.alpha  = 0f
        b.fabStartProxy.visibility = View.VISIBLE
        b.fabStartProxy.animate().cancel()
        b.fabStartProxy.animate()
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

        b.tvConnectionStatus.text = getString(R.string.server_settings_proxy_stopped)
        b.tvConnectionStatus.setTextColor(
            ContextCompat.getColor(requireContext(), R.color.colorAmber_900)
        )
        stopStatusBlink()
        b.statusIndicator.backgroundTintList =
            ContextCompat.getColorStateList(requireContext(), R.color.colorAmber_900)

        // Hero summary: stopped state, no avatars, no duration.
        b.tvActiveDuration.text = ""
        b.tvCollapsedFlag.isVisible = false
        b.tvCollapsedTitle.text = ""
        populateAvatarRow(emptyList())
        b.locationCapacityIndicator.isVisible = false

        val stoppedAlpha = 0.5f
        b.rvServers.alpha         = stoppedAlpha
        b.rvSelectedServers.alpha = stoppedAlpha
        // Disable search bar and action icons while proxy is stopped
        setSearchAndActionsEnabled(false)

        // Adapters replace click handlers so tapping any server item opens the
        // settings sheet instead of selecting/deselecting or opening detail.
        selectedAdapter.setProxyStopped(true)
        serverAdapter.setProxyStopped(true)

        b.errorStateContainer.visibility = View.GONE

        b.errorRetryBtn.isEnabled = false
        b.errorRetryBtn.isClickable = false
        b.errorRetryBtn.setOnClickListener(null)
        b.errorResetBtn.isEnabled = false
        b.errorResetBtn.isClickable = false
        b.errorResetBtn.setOnClickListener(null)

        b.serverCountLayout.isVisible = false

        // Hide frequent chips while proxy is stopped
        b.frequentCountriesSection.isVisible = false

        // FAB: switch to "Start" (green VPN icon)
        applyFabStoppedState()
    }

    private fun applyProxyRunningUi() {
        if (!isAdded) return

        b.rvServers.alpha              = 1f
        b.rvSelectedServers.alpha      = 1f
        // Re-enable search bar and action icons when proxy resumes
        setSearchAndActionsEnabled(true)

        selectedAdapter.setProxyStopped(false)
        serverAdapter.setProxyStopped(false)

        b.errorRetryBtn.isEnabled = true
        b.errorRetryBtn.isClickable = true
        b.errorResetBtn.isEnabled = true
        b.errorResetBtn.isClickable = true

        b.serverCountLayout.isVisible = true

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
        b.rvServers.layoutManager = LinearLayoutManager(requireContext())
        serverAdapter = CountryServerAdapter(buildCountries(unselectedServers), this)
        b.rvServers.adapter = serverAdapter
        b.rvServers.itemAnimator?.apply { changeDuration = 200; moveDuration = 200; addDuration = 200; removeDuration = 200 }

        b.rvSelectedServers.layoutManager = LinearLayoutManager(requireContext())
        selectedAdapter = VpnServerAdapter(requireContext(), buildSelectedServerGroups(selectedServers), this)
        b.rvSelectedServers.adapter = selectedAdapter
        b.rvSelectedServers.itemAnimator?.apply { changeDuration = 200; moveDuration = 200; addDuration = 200; removeDuration = 200 }
    }

    private fun setupSearchBar() {
        b.searchBar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { filterServers(s.toString()) }
            override fun afterTextChanged(s: Editable?) {}
        })
        b.searchClearBtn.setOnClickListener {
            b.searchBar.text?.clear()
            animateSearchClearButton(false)
        }
        b.searchFilterBtn.setOnClickListener { showFilterDialog() }
        b.tvActiveFilterSummary.setOnClickListener { clearFilters() }
        b.searchBar.setOnFocusChangeListener { _, hasFocus ->
            b.searchCard.animate().scaleX(if (hasFocus) 1.02f else 1f).scaleY(if (hasFocus) 1.02f else 1f).setDuration(150).start()
        }
    }

    private fun animateSearchClearButton(show: Boolean) {
        if (!isAdded) return
        if (show && b.searchClearBtn.visibility != View.VISIBLE) {
            b.searchClearBtn.visibility = View.VISIBLE
            b.searchClearBtn.alpha = 0f; b.searchClearBtn.scaleX = 0.5f; b.searchClearBtn.scaleY = 0.5f
            b.searchClearBtn.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200)
                .setInterpolator(AccelerateDecelerateInterpolator()).start()
        } else if (!show && b.searchClearBtn.isVisible) {
            b.searchClearBtn.animate().alpha(0f).scaleX(0.5f).scaleY(0.5f).setDuration(200)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction { if (isAdded) b.searchClearBtn.visibility = View.GONE }
                .start()
        }
    }

    private fun updateSelectedSectionVisibility() {
        if (!isAdded) return
        val hasSelection = selectedServers.isNotEmpty()
        b.selectedLocationsHeader.isVisible = hasSelection
        b.rvSelectedServers.isVisible = hasSelection
        b.rvSelectedServers.isVisible = hasSelection

        b.emptySelectionCard.isVisible = !hasSelection && !isLoading
    }

    private fun updateAllServersCount() {
        if (!isAdded) return
        val count = unselectedServers.distinctBy { it.key }.size
        b.tvServerCount.text = if (count == 0) ""
        else resources.getQuantityString(R.plurals.server_count, count, count)
    }

    private fun showEmptyState() {
        showUnifiedErrorState(
            illustration = R.drawable.illustrations_no_record,
            title = getString(R.string.server_selection_no_servers),
            message = getString(R.string.server_selection_no_servers_desc),
            hint = "",
            isError = false
        )
    }

    private fun showErrorState(noTunnel: Boolean = false) {
        if (noTunnel) {
            showUnifiedErrorState(
                illustration = R.drawable.ic_firewall_wifi_off,
                title = getString(R.string.server_selection_error_title),
                message = getString(R.string.server_selection_error_message),
                hint = getString(R.string.ssv_toast_start_rethink),
                isError = true,
                noTunnel = true
            )
        } else {
            showUnifiedErrorState(
                illustration = R.drawable.ic_firewall_wifi_off,
                title = getString(R.string.server_selection_error_title),
                message = getString(R.string.server_selection_error_message),
                hint = getString(R.string.server_selection_error_hint),
                isError = true
            )
        }
    }

    private fun showUnifiedErrorState(
        illustration: Int,
        title: String,
        message: String,
        hint: String,
        isError: Boolean,
        noTunnel: Boolean = false
    ) {
        if (!isAdded) return
        b.rvServers.isVisible = false
        b.searchCard.isVisible = true
        b.searchCard.isEnabled = false
        b.searchBar.isEnabled = false

        b.supportBtn.isVisible = true
        b.settingsBtn.isVisible = true

        // Keep the status card visible but update it for a premium feel.
        b.statusCard.isVisible = true
        updateConnectionStatus(if (isError) ConnectionUiState.FAILED else ConnectionUiState.DISCONNECTED)
        b.tvCollapsedFlag.isVisible = false
        b.tvCollapsedTitle.text = ""
        populateAvatarRow(emptyList())

        b.serverCountLayout.isVisible = false
        b.rvSelectedServers.isVisible = false
        b.emptySelectionCard.isVisible = false
        b.frequentCountriesSection.isVisible = false
        b.locationCapacityIndicator.isVisible = false

        // Update content
        b.errorIllustration.setImageResource(illustration)
        b.errorIllustration.imageTintList = ColorStateList.valueOf(
            resolveAttrColor(if (isError) R.attr.accentBad else R.attr.primaryLightColorText)
        )
        b.errorTitle.text = title
        b.errorMessage.text = message
        b.errorHint.text = hint
        b.errorHint.isVisible = hint.isNotEmpty()

        // Animate the container sliding up from below
        b.errorStateContainer.visibility = View.VISIBLE
        b.errorStateContainer.alpha = 0f
        b.errorStateContainer.translationY = 60f
        b.errorStateContainer.animate()
            .alpha(1f).translationY(0f)
            .setDuration(450)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        b.errorIllustration.alpha = 0f
        b.errorIllustration.scaleX = 0.6f
        b.errorIllustration.scaleY = 0.6f
        b.errorIllustration.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setStartDelay(180)
            .setDuration(500)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        if (noTunnel) {
            b.errorRetryBtn.isVisible = true
            b.errorRetryBtn.isEnabled = false
            b.errorRetryBtn.isClickable = false
            b.errorRetryBtn.text = getString(R.string.ssv_toast_start_rethink)
            b.errorRetryBtn.setOnClickListener(null)
            b.errorResetBtn.isVisible = false
            b.errorReportBtn.isVisible = true
            b.errorReportBtn.setOnClickListener { openHelpAndSupport() }
        } else if (isError) {
            b.errorRetryBtn.isVisible = true
            b.errorRetryBtn.isEnabled = true
            b.errorRetryBtn.isClickable = true
            b.errorRetryBtn.text = getString(R.string.server_selection_error_retry)
            b.errorRetryBtn.setOnClickListener { retryLoadingServers() }

            b.errorResetBtn.isVisible = false
            b.errorResetBtn.isEnabled = true
            b.errorResetBtn.isClickable = true
            b.errorResetBtn.setOnClickListener {
                serverSelectionViewModel.reset()
                showRpnResetDialog()
            }

            b.errorReportBtn.isVisible = true
            b.errorReportBtn.setOnClickListener { openHelpAndSupport() }

            // Show reset only if proxy test passes
            io {
                val shouldShowReset = VpnController.testRpnProxy()
                uiCtx {
                    if (isAdded && b.errorStateContainer.isVisible && isError) {
                        b.errorResetBtn.isVisible = shouldShowReset
                    }
                }
            }
        } else {
            // Empty state (no servers found)
            b.errorRetryBtn.isVisible = true
            b.errorRetryBtn.text = getString(R.string.server_selection_error_retry)
            b.errorRetryBtn.setOnClickListener { retryLoadingServers() }
            b.errorResetBtn.isVisible = false
            b.errorReportBtn.isVisible = true
            b.errorReportBtn.setOnClickListener { openHelpAndSupport() }
        }
    }

    private fun hideErrorState() {
        if (!isAdded) return
        if (b.errorStateContainer.isVisible) {
            b.errorStateContainer.animate()
                .alpha(0f).translationY(-40f)
                .setDuration(250)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction { if (isAdded) b.errorStateContainer.visibility = View.GONE }
                .start()
        }
        b.rvServers.isVisible = true
        b.searchCard.isVisible = true
        b.supportBtn.isVisible = true
        b.settingsBtn.isVisible = true
        b.statusCard.isVisible = true
        updateVpnStatus()
        b.searchCard.isEnabled = true
        b.searchBar.isEnabled = true
        if (!isProxyStopped) updateCapacityIndicator()
    }

    private fun retryLoadingServers() {
        if (!isAdded) return
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

        b.errorRetryBtn.isEnabled = false
        b.errorResetBtn.isEnabled = false
        b.errorRetryBtn.text = getString(R.string.lbl_connecting)

        SnackbarHelper.dismiss()

        // Hide error container and show shimmer for immediate visual feedback.
        b.errorStateContainer.animate()
            .alpha(0f).setDuration(200)
            .withEndAction { if (isAdded) b.errorStateContainer.visibility = View.GONE }
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
                b.errorRetryBtn.isEnabled = true
                b.errorResetBtn.isEnabled = true
                b.errorRetryBtn.text = getString(R.string.server_selection_error_retry)
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
            }.sortedBy { it.countryName.lowercase()  }.sortedBy { !it.isFavourite }
            .toList()
    }

    private fun buildSelectedServerGroups(servers: List<CountryConfig>): List<VpnServerAdapter.ServerGroup> {
        if (servers.isEmpty()) return emptyList()
        return servers.groupBy { it.key }.map { (key, grouped) ->
            val rep = grouped.first()
            val leastLoad = if (grouped.all { it.load > 0 }) grouped.minOfOrNull { it.load } ?: 0 else 0
            val bestLink  = if (grouped.all { it.link > 0 }) grouped.maxOfOrNull { it.link } ?: 0 else 0
            VpnServerAdapter.ServerGroup(key, grouped, rep.countryName, rep.flagEmoji, rep.serverLocation, rep.cc, bestLink, leastLoad, grouped.any { it.isActive })
        }.sortedBy { it.cityName.lowercase() }
    }

    /**
     * Rebuilds the "All locations" list applying the current search query together
     * with the active load-tier, speed-tier and favourites-only filters.  Called on
     * text changes and whenever the underlying list changes so filters survive refreshes.
     */
    private fun refreshUnselectedList() {
        if (!isAdded) return
        val q = b.searchBar.text?.toString()?.trim()?.lowercase().orEmpty()
        val filtered = unselectedServers.filter { matchesFilters(it, q) }
        serverAdapter.updateCountries(buildCountries(filtered))
        animateSearchClearButton(q.isNotEmpty())
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

        b.searchFilterBtn.iconTint = ColorStateList.valueOf(
            resolveAttrColor(if (active) R.attr.accentGood else R.attr.primaryTextColor)
        )
        b.searchFilterBtn.backgroundTintList = ColorStateList.valueOf(
            resolveAttrColor(if (active) R.attr.chipBgColorPositive else R.attr.colorSurfaceVariant)
        )
        b.searchFilterBtn.contentDescription = if (active) {
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
            b.tvActiveFilterSummary.isVisible = false
            return
        }
        b.tvActiveFilterSummary.text = summary
        b.tvActiveFilterSummary.isVisible = true
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
        val density = resources.displayMetrics.density

        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (22f * density).toInt(), (6f * density).toInt(),
                (22f * density).toInt(), 0
            )
        }

        val loadLabel = buildDialogTitleLabel(getString(R.string.server_selection_filter_by_load))
        container.addView(loadLabel)

        val loadTiers = listOf(LoadFilter.ALL, LoadFilter.LOW, LoadFilter.MEDIUM, LoadFilter.HIGH)
        val loadGroup = ChipGroup(requireContext()).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        val loadChipsById = mutableMapOf<LoadFilter, Chip>()
        loadTiers.forEach { tier ->
            val label =
                if (tier == LoadFilter.ALL) getString(R.string.server_selection_filter_load_any)
                else tier.label
            val chip = createFilterChip(label, isChecked = loadFilter == tier)
            loadChipsById[tier] = chip
            loadGroup.addView(chip)
        }
        container.addView(loadGroup)

        val speedLabel = buildDialogTitleLabel(getString(R.string.server_selection_filter_by_speed))
        container.addView(speedLabel)

        // Offer one chip per distinct speed present in the server list (e.g. "Any",
        // "1 Gbps", "10 Gbps", "20 Gbps") so users only ever see speeds that exist.
        val speedOptions = distinctSpeedOptions()
        val speedGroup = ChipGroup(requireContext()).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        val speedChipsByValue = mutableMapOf<Int, Chip>()
        val anyChip = createFilterChip(
            getString(R.string.server_selection_filter_load_any), isChecked = speedFilter == 0
        )
        speedChipsByValue[0] = anyChip
        speedGroup.addView(anyChip)
        speedOptions.forEach { linkMbps ->
            val chip = createFilterChip(
                formatLinkSpeed(linkMbps), isChecked = speedFilter == linkMbps
            )
            speedChipsByValue[linkMbps] = chip
            speedGroup.addView(chip)
        }
        container.addView(speedGroup)

        val favLabel = buildDialogTitleLabel(getString(R.string.server_selection_filter_favourites))
        container.addView(favLabel)

        val favChip = createFilterChip(
            getString(R.string.server_selection_filter_favourites_only),
            isChecked = favouritesOnly
        )
        container.addView(favChip)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.server_selection_filter_locations))
            .setView(container)
            .setPositiveButton(getString(R.string.lbl_apply)) { _, _ ->
                loadFilter = loadChipsById.entries
                    .firstOrNull { it.value.isChecked }?.key ?: LoadFilter.ALL
                speedFilter = speedChipsByValue.entries
                    .firstOrNull { it.value.isChecked }?.key ?: 0
                favouritesOnly = favChip.isChecked
                refreshUnselectedList()
            }
            .setNeutralButton(getString(R.string.lbl_reset)) { _, _ ->
                loadFilter = LoadFilter.ALL
                speedFilter = 0
                favouritesOnly = false
                refreshUnselectedList()
            }
            .setNegativeButton(getString(R.string.lbl_cancel), null)
            .show()
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
    private fun createFilterChip(label: String, isChecked: Boolean): Chip {
        val density = resources.displayMetrics.density
        return Chip(requireContext()).apply {
            text = label
            isCheckable = true
            this.isChecked = isChecked
            // The color + stroke contrast carries the selection state; the default
            // checkmark would be redundant (and low-contrast on some themes).
            isCheckedIconVisible = false
            chipBackgroundColor = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(
                    resolveAttrColor(R.attr.chipBgColorPositive),
                    resolveAttrColor(R.attr.background)
                )
            )
            setTextColor(
                ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(
                        resolveAttrColor(R.attr.chipTextPositive),
                        resolveAttrColor(R.attr.primaryTextColor)
                    )
                )
            )
            chipStrokeColor = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(resolveAttrColor(R.attr.chipTextPositive), Color.TRANSPARENT)
            )
            chipStrokeWidth = 1f * density
            // Compact but accessible touch target, matching the frequent-country chips.
            chipMinHeight = 36f * density
            chipStartPadding = 12f * density
            chipEndPadding = 12f * density
        }
    }

    /**
     * Builds the small all-caps section label used inside the filter dialog,
     * mirroring the `RethinkPlus.SectionLabel` style used across this screen.
     */
    private fun buildDialogTitleLabel(text: String): AppCompatTextView {
        return AppCompatTextView(requireContext()).apply {
            this.text = text
            textSize = 10.5f
            setAllCaps(true)
            letterSpacing = 0.13f
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            setTextColor(resolveAttrColor(R.attr.primaryLightColorText))
            val density = resources.displayMetrics.density
            setPadding(0, (10f * density).toInt(), 0, (6f * density).toInt())
        }
    }


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
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
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
            .show()
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
        val chipGroup = b.frequentChipGroup
        chipGroup.removeAllViews()

        val selectedCcs = selectedServers.map { it.cc }.toSet()

        var visibleCount = 0
        for (cc in topCcs) {
            if (cc in selectedCcs) continue
            val rep = allServers.firstOrNull { it.cc == cc && !it.id.equals(AUTO_SERVER_ID, true) } ?: continue
            chipGroup.addView(buildFrequentChip(rep))
            visibleCount++
        }

        val show = visibleCount > 0
        if (show && !b.frequentCountriesSection.isVisible) {
            // Animate the strip sliding in from below.
            b.frequentCountriesSection.alpha = 0f
            b.frequentCountriesSection.translationY = 12f
            b.frequentCountriesSection.isVisible = true
            b.frequentCountriesSection.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(250)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        } else if (!show) {
            b.frequentCountriesSection.isVisible = false
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
    private fun buildFrequentChip(server: CountryConfig): Chip {
        val density = resources.displayMetrics.density

        val chip = Chip(requireContext())
        chip.text = getString(R.string.two_argument_space, server.flagEmoji, server.countryName)
        chip.isClickable = true
        chip.isCheckable = false
        chip.isCloseIconVisible = false

        // Background: subtle positive tint.
        val bgColor = UIUtils.fetchColor(requireContext(), R.attr.chipBgColorPositive)
        chip.chipBackgroundColor = android.content.res.ColorStateList.valueOf(bgColor)

        val textColor = UIUtils.fetchColor(requireContext(), R.attr.primaryTextColor)
        chip.setTextColor(textColor)
        chip.textSize = 13f

        // Stroke: green accent at 35 % opacity.
        chip.chipStrokeWidth = 1f * density
        val strokeBaseColor = UIUtils.fetchColor(requireContext(), R.attr.accentGood)
        chip.chipStrokeColor = android.content.res.ColorStateList.valueOf(
            Color.argb(
                (255 * 0.35f).toInt(),
                Color.red(strokeBaseColor),
                Color.green(strokeBaseColor),
                Color.blue(strokeBaseColor)
            )
        )

        // Compact but accessible sizing.
        chip.chipMinHeight = 36f * density
        chip.chipStartPadding = 12f * density
        chip.chipEndPadding = 12f * density

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
        b.shimmerSubscriptionBanner.visibility = View.VISIBLE
        b.shimmerSubscriptionBanner.startShimmer()
        b.subscriptionBanner.visibility = View.GONE

        lifecycleScope.launch {
            subscriptionStatusDao.observeCurrentSubscription().collectLatest { sub ->
                if (!isAdded) return@collectLatest
                uiCtx {
                    b.shimmerSubscriptionBanner.stopShimmer()
                    b.shimmerSubscriptionBanner.visibility = View.GONE
                    // Subscription details belong to the account surface, not the
                    // compact RPN connection header.
                    b.subscriptionBanner.visibility = View.GONE
                    maybeShowResubscribePrompt(sub)
                }
            }
        }
    }

    /**
     * Populates (or hides) the subscription details banner from [sub].
     */
    private fun updateSubscriptionBanner(sub: SubscriptionStatus?) {
        if (sub == null || sub.purchaseToken.isEmpty()) {
            b.subscriptionBanner.visibility = View.GONE
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
            b.tvExpiryLabel.text = getString(R.string.lbl_plan)
            b.tvExpiryDate.text  = sub.planId.capitalizeWords()
            b.tvDaysRemaining.isVisible = false
        } else if (!hasExpiry) {
            // Date row needed but expiry not yet known
            b.tvExpiryDate.text = "-"
            b.tvExpiryLabel.text = if (isOneTime)
                getString(R.string.lbl_expires_on)
            else
                getString(R.string.server_selection_sub_ends_on)
            b.tvDaysRemaining.isVisible = false
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
            b.tvExpiryLabel.text = getString(expiryLabelRes)
            b.tvExpiryDate.text  = dateStr

            if (!isEffectivelyExpired) {
                val daysLeft = TimeUnit.MILLISECONDS.toDays(billingExpiry - now)
                if (daysLeft >= 0) {
                    b.tvDaysRemaining.text = if (daysLeft == 0L)
                        getString(R.string.server_selection_sub_expires_today)
                    else
                        getString(R.string.server_selection_sub_days_left, daysLeft)
                    b.tvDaysRemaining.setTextColor(
                        if (daysLeft <= 7)
                            resolveAttrColor(R.attr.colorGolden)
                        else
                            resolveAttrColor(R.color.primaryText)
                    )
                    b.tvDaysRemaining.isVisible = true
                } else {
                    b.tvDaysRemaining.isVisible = false
                }
            } else {
                b.tvDaysRemaining.isVisible = false
            }
        }

        // Animate banner in if it was previously hidden
        if (!b.subscriptionBanner.isVisible) {
            b.subscriptionBanner.alpha = 0f
            b.subscriptionBanner.visibility = View.VISIBLE
            b.subscriptionBanner.animate()
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
        lifecycleScope.launch {
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
        b.registrationProgressBar.show()
        // Prevent start/stop proxy FAB taps while registration is in progress.
        b.fabStopProxy.isClickable  = false
        b.fabStartProxy.isClickable = false

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
                b.registrationProgressBar.hide()
                b.fabStopProxy.isClickable  = true
                b.fabStartProxy.isClickable = true
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

        val dialogView = layoutInflater.inflate(R.layout.dialog_server_loading, null)
        val tvTitle    = dialogView.findViewById<android.widget.TextView>(R.id.tv_server_loading_title)
        val tvStatus   = dialogView.findViewById<android.widget.TextView>(R.id.tv_server_loading_status)
        val tvHint     = dialogView.findViewById<android.widget.TextView>(R.id.tv_server_loading_hint)
        val timeoutBar = dialogView.findViewById<LinearProgressIndicator>(R.id.server_loading_timeout_bar)

        tvTitle.text  = getString(R.string.rpn_restore_dialog_title)
        tvHint.text   = getString(R.string.rpn_restore_dialog_hint)
        timeoutBar.max = LOADING_DIALOG_TIMEOUT_MS.toInt()
        timeoutBar.setProgressCompat(0, false)

        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        dialog.setOnCancelListener {
            Logger.i(LOG_TAG_UI, "$TAG: reset dialog dismissed by user, switching to inline bar")
            resetDialogDismissedByUser = true
            dismissRpnResetDialog()
            if (isAdded && view != null) b.registrationProgressBar.show()
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

    private fun resolveAttrColor(attrRes: Int): Int {
        UIUtils.fetchColor(requireContext(), attrRes).let { return it }
    }
}
