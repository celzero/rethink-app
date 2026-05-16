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

import Logger
import Logger.LOG_TAG_UI
import android.animation.ObjectAnimator
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
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
import com.celzero.bravedns.ui.bottomsheet.ManageRpnPurchaseBtmSht
import com.celzero.bravedns.ui.bottomsheet.ServerRemovalNotificationBottomSheet
import com.celzero.bravedns.ui.bottomsheet.ServerSettingsBottomSheet
import com.celzero.bravedns.util.SnackbarHelper
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.ServerSelectionViewModel
import com.celzero.firestack.backend.Backend
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.chip.Chip
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

    /** Job driving the registration / server-list polling loop. */
    private var serverLoadingJob: Job? = null
    /** Non-dismissable dialog shown while WIN registers / servers load. */
    private var serverLoadingDialog: android.app.Dialog? = null
    /** Job driving the RPN reset progress loop. */
    private var rpnResetJob: Job? = null
    /** Non-dismissable dialog shown while RPN reset is in progress. */
    private var rpnResetDialog: android.app.Dialog? = null
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

    companion object {
        private const val TAG = "ServerSelectionFragment"

        /**
         * Maximum number of NON-AUTO servers the user can select simultaneously.
         * AUTO is always kept connected on top of this limit.
         */
        private const val MAX_SELECTIONS = 5

        /** UI connection states surfaced by [updateConnectionStatus]. */
        private enum class ConnectionUiState { DISCONNECTED, CONNECTING, CONNECTED }

        /** Maximum time the registration loading dialog will wait before giving up. */
        private const val LOADING_DIALOG_TIMEOUT_MS = 20_000L
        /** Interval between each registration / server-list poll within the dialog. */
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
                    0,
                    b.serversScrollView.paddingRight,
                    navView.height + 300
                )
            }
        }

        animateHeaderEntry()
        observeRefreshState()
        observeResetState()
    }

    private fun applyScrollPadding() {
        b.serversScrollView.post {
            b.serversScrollView.setPadding(
                b.serversScrollView.paddingLeft,
                0,
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
            val selectedList = RpnProxyManager.getEnabledConfigs()
            Logger.v(LOG_TAG_UI, "$TAG; WIN registered: $isWinRegistered, rpnActive: $rpnActive")

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
                        Logger.i(
                            LOG_TAG_UI,
                            "$TAG: WIN registered=$isWinRegistered, hasRealServers=$hasRealServers; showing loading dialog"
                        )
                        showServerLoadingDialog()
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
                            Logger.w(LOG_TAG_UI, "$TAG.observeRefreshState: NeedsLoading on user-initiated refresh — consuming silently")
                            serverSelectionViewModel.onRefreshConsumed()
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
     * **[ServerSelectionViewModel.ResetState.InProgress]**: If the fragment was recreated
     * while a reset was running (rotation during the progress dialog), re-show the dialog
     * and restart the animation loop. The ViewModel's [viewModelScope] job is still alive.
     *
     * **[ServerSelectionViewModel.ResetState.Done]**: Consumes the result, dismisses the
     * dialog, and delegates UI update to [handleResetResult].
     */
    private fun observeResetState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                serverSelectionViewModel.resetState.collect { state ->
                    when (state) {
                        is ServerSelectionViewModel.ResetState.InProgress -> {
                            // Handles rotation: if the fragment is recreated while a reset is
                            // already running, re-show the progress dialog so the user still
                            // sees feedback.  The ViewModel's coroutine keeps running regardless.
                            if (rpnResetDialog?.isShowing != true) {
                                Logger.i(LOG_TAG_UI, "$TAG.observeResetState: InProgress (re-attach after rotation?), showing dialog")
                                showRpnResetDialog()
                            }
                        }
                        is ServerSelectionViewModel.ResetState.Done -> {
                            Logger.i(LOG_TAG_UI, "$TAG.observeResetState: Done, result=${state.result}")
                            serverSelectionViewModel.onResetConsumed()
                            dismissRpnResetDialog()
                            handleResetResult(state.result, state.servers, state.selected)
                        }
                        is ServerSelectionViewModel.ResetState.Idle -> { /* nothing to do */ }
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
                            serverAdapter.updateCountries(buildCountries(unselectedServers))
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

    private fun setLoadingState(loading: Boolean) {
        if (!isAdded) return
        isLoading = loading

        if (loading) {
            // Header shimmer
            b.shimmerHeader.isVisible = true
            b.shimmerHeader.startShimmer()
            b.locationContent.isVisible = false

            // hide real list and hint cards
            b.shimmerServerList.isVisible = true
            b.shimmerServerList.startShimmer()
            b.rvServers.isVisible = false
            b.emptySelectionCard.isVisible = false
            b.selectedServersCard.isVisible = false
            b.emptyStateLayout.isVisible = false
            b.frequentCountriesSection.isVisible = false
        } else {
            // Stop and hide header shimmer, reveal real content
            b.shimmerHeader.stopShimmer()
            b.shimmerHeader.isVisible = false
            b.locationContent.isVisible = true

            // Stop and hide list shimmer, reveal real list
            b.shimmerServerList.stopShimmer()
            b.shimmerServerList.isVisible = false
            b.rvServers.isVisible = true
        }
    }

    private fun initServers(servers: List<CountryConfig>, selectedList: Set<CountryConfig> = emptySet()) {
        io {
            Logger.v(LOG_TAG_UI, "$TAG.initServers: ${servers.size} servers, selected=${selectedList.size}, WIN=$isWinRegistered")

            val hasRealServers = servers.any { it.id != AUTO_SERVER_ID }

            if (!hasRealServers) {
                uiCtx {
                    if (!isAdded) return@uiCtx
                    setLoadingState(false)
                    showErrorState()
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

                selectedAdapter.updateServers(selectedServers)
                serverAdapter.updateCountries(buildCountries(unselectedServers))
                updateAllServersCount()
                updateSelectedSectionVisibility()
                updateVpnStatus()
                setLoadingState(false)
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
        b.collapsingToolbar.title = getString(R.string.server_selection_title)
        b.collapsingToolbar.titleCollapseMode = CollapsingToolbarLayout.TITLE_COLLAPSE_MODE_SCALE
        // Title is invisible while the header is expanded so it doesn't overlap the status
        // card content; it fades in only once the toolbar is fully collapsed.
        b.collapsingToolbar.setExpandedTitleColor(Color.TRANSPARENT)
        b.collapsingToolbar.setCollapsedTitleTextColor(resolveAttrColor(R.attr.primaryTextColor))

        b.appBarLayout.addOnOffsetChangedListener { appBar, verticalOffset ->
            val scrollRange = appBar.totalScrollRange
            if (scrollRange == 0) return@addOnOffsetChangedListener
            val collapsedFraction = (-verticalOffset).toFloat() / scrollRange.toFloat()
            val contentAlpha = (1f - ((collapsedFraction - 0.40f) / 0.35f)).coerceIn(0f, 1f)
            b.statusCard.alpha = contentAlpha
        }

        populateHeroPlanAccountRow()
        // Periodic status + hero-IP refresh.  updateHeroIpRow uses the RpnProxyManager
        // cache so the IO path only fires on reconnect (since change) or first load.
        statusUpdateJob = lifecycleScope.launch {
            while (true) {
                delay(3_000)
                if (isAdded && !isLoading) {
                    updateConnectionStatusOnly()
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
            uiCtx {
                if (!isAdded) return@uiCtx
                b.tvHeroAccountId.text = if (accountId.isNotEmpty()) "$accountId • $deviceId" else ""
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
     * Full header refresh: connection status + current location derived from [selectedServers].
     * Only called after data is loaded (not during loading).
     */
    private fun updateVpnStatus() {
        if (!isAdded) return
        updateConnectionStatus(deriveConnectionUiState())
        when {
            selectedServers.isEmpty() -> {
                updateCurrentLocation(
                    countryName = if (isWinRegistered) AUTO_SERVER_ID else getString(R.string.vpn_status_disconnected),
                    location = ""
                )
            }
            selectedServers.size == 1 -> {
                val s = selectedServers.first()
                if (s.id.equals(AUTO_SERVER_ID, ignoreCase = true)) {
                    updateCurrentLocation(AUTO_SERVER_ID, "")
                } else {
                    updateCurrentLocation(s.countryName, s.serverLocation)
                }
            }
            else -> {
                val uniqueNames = selectedServers
                    .filter { !it.id.equals(AUTO_SERVER_ID, ignoreCase = true) }
                    .map { it.countryName }
                    .distinct()
                val namesText = uniqueNames.joinToString(", ")
                val locationText = selectedServers
                    .asSequence()
                    .filter { !it.id.equals(AUTO_SERVER_ID, ignoreCase = true) }
                    .map { it.serverLocation }
                    .distinct()
                    .take(2)
                    .joinToString(", ")
                updateCurrentLocation(namesText, locationText)
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
                b.statusIndicator.animate().scaleX(1.3f).scaleY(1.3f).setDuration(500).withEndAction {
                    if (isAdded) b.statusIndicator.animate().scaleX(1f).scaleY(1f).setDuration(500).start()
                }.start()
            }
            ConnectionUiState.CONNECTING -> {
                b.tvConnectionStatus.text = getString(R.string.lbl_connecting)
                b.tvConnectionStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorAmber_900))
                b.statusIndicator.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.colorAmber_900)
                // Pulse animation to indicate in-progress state
                b.statusIndicator.animate().scaleX(1.2f).scaleY(1.2f).setDuration(600).withEndAction {
                    if (isAdded) b.statusIndicator.animate().scaleX(0.8f).scaleY(0.8f).setDuration(600).withEndAction {
                        if (isAdded) b.statusIndicator.animate().scaleX(1f).scaleY(1f).setDuration(300).start()
                    }.start()
                }.start()
            }
            ConnectionUiState.DISCONNECTED -> {
                b.tvConnectionStatus.text = getString(R.string.lbl_inactive)
                b.tvConnectionStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.accentBad))
                b.statusIndicator.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.accentBad)
            }
        }
    }

    private fun updateCurrentLocation(countryName: String, location: String) {
        if (!isAdded) return

        b.locationContent.visibility = View.VISIBLE
        b.tvCurrentCountry.text = countryName.capitalizeWords()
        b.tvCurrentLocation.text = location.capitalizeWords()
    }

    private fun String.capitalizeWords(): String {
        return split(" ")
            .joinToString(" ") { word ->
                word.lowercase().replaceFirstChar { it.uppercase() }
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
        b.supportBtn.setOnClickListener { openHelpAndSupport() }
        b.settingsBtn.setOnClickListener { showServerSettingsBottomSheet() }
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
        b.statusIndicator.backgroundTintList =
            ContextCompat.getColorStateList(requireContext(), R.color.colorAmber_900)

        // Hint under the flag/country row
        b.tvCurrentLocation.visibility = View.GONE

        val stoppedAlpha = 0.5f
        b.rvServers.alpha         = stoppedAlpha
        b.rvSelectedServers.alpha = stoppedAlpha
        b.searchCard.alpha        = stoppedAlpha
        b.searchCard.isEnabled    = false
        b.searchBar.isEnabled     = false
        b.searchBar.isFocusable   = false

        b.settingsBtn.alpha     = stoppedAlpha

        // Adapters replace click handlers so tapping any server item opens the
        // settings sheet instead of selecting/deselecting or opening detail.
        selectedAdapter.setProxyStopped(true)
        serverAdapter.setProxyStopped(true)

        b.errorStateContainer.visibility = View.GONE

        b.errorRetryBtn.isEnabled = false
        b.errorRetryBtn.isClickable = false
        b.errorRetryBtn.setOnClickListener(null)

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
        b.searchCard.alpha             = 1f
        b.searchCard.isEnabled         = true
        b.searchBar.isEnabled          = true
        b.searchBar.isFocusable        = true
        b.searchBar.isFocusableInTouchMode = true

        b.settingsBtn.alpha      = 1f

        selectedAdapter.setProxyStopped(false)
        serverAdapter.setProxyStopped(false)

        b.errorRetryBtn.isEnabled = true
        b.errorRetryBtn.isClickable = true
        b.serverCountLayout.isVisible = true

        updateConnectionStatus(deriveConnectionUiState())

        // FAB: switch to "Stop" (red stop icon)
        applyFabRunningState()

        // refetch WIN registration state and server list on a background thread.
        io {
            isWinRegistered = VpnController.isWinRegistered()
            val servers      = RpnProxyManager.getWinServers()
            val selectedList = RpnProxyManager.getEnabledConfigs()
            val hasRealServers = servers.any { it.id != AUTO_SERVER_ID }

            uiCtx {
                if (!isAdded) return@uiCtx
                if (!isWinRegistered || !hasRealServers) {
                    // WIN is not yet registered or the server list is not populated
                    // immediately after proxy start (can happen on first launch after
                    // reinstall). The polling dialog will keep retrying until both
                    // conditions are satisfied, then call initServers().
                    showServerLoadingDialog()
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
        b.selectedServersCard.isVisible = hasSelection
        b.rvSelectedServers.isVisible = hasSelection

        b.emptySelectionCard.isVisible = !hasSelection && !isLoading
    }

    private fun updateAllServersCount() {
        if (!isAdded) return
        val count = unselectedServers.distinctBy { it.key }.size
        b.tvServerCount.text = if (count == 0) ""
        else resources.getQuantityString(R.plurals.server_count, count, count)
    }

    private fun showErrorState() {
        if (!isAdded) return
        b.rvServers.isVisible = false
        b.searchCard.isVisible = false
        b.supportBtn.isVisible = false
        b.settingsBtn.isVisible = false
        b.statusCard.isVisible = false

        b.selectedServersCard.isVisible = false
        b.emptySelectionCard.isVisible = false
        b.frequentCountriesSection.isVisible = false

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

        b.errorRetryBtn.isEnabled = true
        b.errorRetryBtn.text = getString(R.string.server_selection_error_retry)
        b.errorRetryBtn.setOnClickListener { retryLoadingServers() }
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
    }

    private fun retryLoadingServers() {
        if (!isAdded) return
        if (!RpnProxyManager.isRpnActive()) {
            showToast(getString(R.string.server_selection_tap_to_select))
            return
        }
        b.errorRetryBtn.isEnabled = false
        b.errorRetryBtn.text = getString(R.string.lbl_connecting)

        SnackbarHelper.dismiss()

        // Hide error container and show shimmer for immediate visual feedback.
        b.errorStateContainer.animate()
            .alpha(0f).setDuration(200)
            .withEndAction { if (isAdded) b.errorStateContainer.visibility = View.GONE }
            .start()
        setLoadingState(true)

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
                val refreshed = RpnProxyManager.updateWinProxy(userRequest = true)
                if (!refreshed.isNullOrEmpty()) refreshed
                else RpnProxyManager.getWinServers()
            } catch (t: Throwable) {
                Logger.e(LOG_TAG_UI, "$TAG.retryLoadingServers: tunnel error (non-fatal): ${t.message}")
                // Fall back to whatever is in the DB/cache so the screen is not blank.
                try { RpnProxyManager.getWinServers() } catch (_: Exception) { emptyList() }
            }

            val hasRealServers = servers.any { it.id != AUTO_SERVER_ID }

            uiCtx {
                if (!isAdded) return@uiCtx
                b.errorRetryBtn.isEnabled = true
                b.errorRetryBtn.text = getString(R.string.server_selection_error_retry)
                when {
                    // WIN not yet registered or server list still empty, hand off to the
                    // polling dialog (same path as setupRpnState / applyProxyRunningUi).
                    (!isWinRegistered || !hasRealServers) && RpnProxyManager.isRpnActive() -> {
                        Logger.i(
                            LOG_TAG_UI,
                            "$TAG.retryLoadingServers: WIN registered=$isWinRegistered, " +
                                "hasRealServers=$hasRealServers, showing loading dialog"
                        )
                        setLoadingState(false)
                        showServerLoadingDialog()
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

    private fun filterServers(query: String) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            serverAdapter.updateCountries(buildCountries(unselectedServers))
            animateSearchClearButton(false)
            return
        }
        val filtered = unselectedServers.filter { s ->
            s.countryName.lowercase().contains(q) ||
            s.serverLocation.lowercase().contains(q) ||
            s.cc.lowercase().contains(q)
        }
        serverAdapter.updateCountries(buildCountries(filtered))
        animateSearchClearButton(true)
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
        serverAdapter.updateCountries(buildCountries(unselectedServers))
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
        serverAdapter.updateCountries(buildCountries(unselectedServers))
    }

    override fun onServerGroupRemoved(group: VpnServerAdapter.ServerGroup) {
        // AUTO is always kept connected
        if (group.key == AUTO_SERVER_ID || group.servers.any { it.id == AUTO_SERVER_ID }) {
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
            val rep = allServers.firstOrNull { it.cc == cc && it.id != AUTO_SERVER_ID } ?: continue
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
                    updateSubscriptionBanner(sub)
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

        val now           = System.currentTimeMillis()
        val billingExpiry = sub.billingExpiry
        val hasExpiry     = billingExpiry > 0L && billingExpiry != Long.MAX_VALUE

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
            // Active SUBS: replace date with a clean "managed by Play" hint
            b.tvExpiryLabel.text = getString(R.string.subscription_label)
            b.tvExpiryDate.text  = getString(R.string.server_selection_sub_managed_by_play)
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
     * Shows [ResubscribeBottomSheet] once per session when the subscription is in the
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

        // Prevent duplicate sheets
        if (childFragmentManager.findFragmentByTag("resubscribe") != null) return
        if (!isAdded || isStateSaved) return

        val purchaseDetail = RpnProxyManager.getSubscriptionData()?.purchaseDetail
        if (purchaseDetail == null) {
            Logger.w(LOG_TAG_UI, "$TAG.maybeShowResubscribePrompt: purchaseDetail unavailable, skipping prompt")
            return
        }

        resubscribePromptShown = true
        Logger.i(LOG_TAG_UI, "$TAG.maybeShowResubscribePrompt: showing resubscribe prompt for productId=${purchaseDetail.productId}, planId=${purchaseDetail.planId}")

        try {
            ManageRpnPurchaseBtmSht.newInstance()
                .show(childFragmentManager, "resubscribe")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "$TAG.maybeShowResubscribePrompt: error showing sheet: ${e.message}", e)
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
     * Shows a non-dismissable, informative dialog while WIN registration or the
     * server-list fetch is in progress.
     */
    private fun showServerLoadingDialog() {
        if (serverLoadingDialog?.isShowing == true) {
            Logger.d(LOG_TAG_UI, "$TAG: loading dialog already showing, skipping duplicate call")
            return
        }
        dismissServerLoadingDialog()

        val dialogView = layoutInflater.inflate(R.layout.dialog_server_loading, null)
        val tvStatus = dialogView.findViewById<android.widget.TextView>(R.id.tv_server_loading_status)
        val timeoutBar = dialogView.findViewById<LinearProgressIndicator>(R.id.server_loading_timeout_bar)
        timeoutBar.max = LOADING_DIALOG_TIMEOUT_MS.toInt()
        timeoutBar.setProgressCompat(0, false)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .setCancelable(false)
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        serverLoadingDialog = dialog

        Logger.i(
            LOG_TAG_UI,
            "$TAG: showServerLoadingDialog; waiting up to ${LOADING_DIALOG_TIMEOUT_MS / 1000}s"
        )

        val statusMessages = listOf(
            getString(R.string.server_loading_dialog_status_checking),
            getString(R.string.server_loading_dialog_status_connecting),
            getString(R.string.server_loading_dialog_status_fetching),
            getString(R.string.server_loading_dialog_status_almost),
        )

        serverLoadingJob = lifecycleScope.launch {
            val startTime = System.currentTimeMillis()
            var msgIdx = 0

            while (true) {
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed >= LOADING_DIALOG_TIMEOUT_MS) break

                // Decide which status message to show
                val statusMsg = when {
                    elapsed > LOADING_DIALOG_TIMEOUT_MS * 0.75 ->
                        getString(R.string.server_loading_dialog_status_timeout)
                    else -> statusMessages[msgIdx % statusMessages.size]
                }

                // cross-fade the status text for a polished feel
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    // Animate old text out, swap, animate new text in
                    tvStatus.animate().alpha(0f).setDuration(120).withEndAction {
                        if (isAdded) {
                            tvStatus.text = statusMsg
                            tvStatus.animate().alpha(0.65f).setDuration(120).start()
                        }
                    }.start()
                    timeoutBar.setProgressCompat(elapsed.coerceAtMost(LOADING_DIALOG_TIMEOUT_MS).toInt(), true)
                }


                // Poll registration status and server list on the IO thread
                val winRegistered = withContext(Dispatchers.IO) {
                    try { VpnController.isWinRegistered() } catch (_: Exception) { false }
                }
                val servers = withContext(Dispatchers.IO) {
                    try { RpnProxyManager.getWinServers() } catch (_: Exception) { emptyList() }
                }
                val hasRealServers = servers.any { it.id != AUTO_SERVER_ID }

                Logger.d(
                    LOG_TAG_UI,
                    "$TAG: loading dialog poll, winRegistered=$winRegistered, " +
                        "realServers=${servers.count { it.id != AUTO_SERVER_ID }}, elapsed=${elapsed}ms"
                )

                if (winRegistered && hasRealServers) {
                    // Registration complete and servers available, load the list
                    val selectedList = withContext(Dispatchers.IO) {
                        try { RpnProxyManager.getEnabledConfigs() } catch (_: Exception) { emptySet() }
                    }
                    withContext(Dispatchers.Main) {
                        if (!isAdded) return@withContext
                        Logger.i(
                            LOG_TAG_UI,
                            "$TAG: loading dialog, registration complete (elapsed=${elapsed}ms)"
                        )
                        isWinRegistered = true
                        dismissServerLoadingDialog()
                        initServers(servers, selectedList)
                    }
                    return@launch
                }

                delay(LOADING_DIALOG_POLL_INTERVAL_MS)
                msgIdx++
            }

            // Timed out; dismiss dialog and surface whatever state we have.
            // If the proxy is not active by now, apply the stopped UI so the FAB is visible.
            Logger.w(
                LOG_TAG_UI,
                "$TAG: loading dialog timed out after ${LOADING_DIALOG_TIMEOUT_MS / 1000}s"
            )
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                dismissServerLoadingDialog()
                setLoadingState(false)
                if (!RpnProxyManager.isRpnActive()) {
                    isProxyStopped = true
                    applyProxyStoppedUi()
                } else {
                    showErrorState()
                }
            }
        }
    }

    /**
     * Cancels the polling job and safely dismisses the loading dialog.
     * Safe to call when no dialog is showing.
     */
    private fun dismissServerLoadingDialog() {
        serverLoadingJob?.cancel()
        serverLoadingJob = null
        runCatching {
            if (serverLoadingDialog?.isShowing == true) serverLoadingDialog?.dismiss()
        }
        serverLoadingDialog = null
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

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .setCancelable(false)
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
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

                delay(LOADING_DIALOG_POLL_INTERVAL_MS)
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
        withContext(Dispatchers.Main) { f() }
    }

    private fun resolveAttrColor(attrRes: Int): Int {
        UIUtils.fetchColor(requireContext(), attrRes).let { return it }
    }
}
