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
package com.celzero.bravedns.ui.activity

import Logger
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.recyclerview.widget.LinearLayoutManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.BubbleAllowedAppsAdapter
import com.celzero.bravedns.adapter.BubbleBlockedAppsAdapter
import com.celzero.bravedns.data.AllowedAppInfo
import com.celzero.bravedns.viewmodel.AllowedAppsBubbleViewModel
import com.celzero.bravedns.data.BlockedAppInfo
import com.celzero.bravedns.viewmodel.BlockedAppsBubbleViewModel
import com.celzero.bravedns.database.AppInfoRepository
import com.celzero.bravedns.database.ConnectionTrackerDAO
import com.celzero.bravedns.database.DnsLogDAO
import com.celzero.bravedns.databinding.ActivityBubbleBinding
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Themes.Companion.getCurrentTheme
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

/**
 * BubbleActivity - Content activity for Android Bubble notifications
 *
 * This activity is launched by Android's Bubble API (Android 10+) when a user
 * interacts with a bubble notification. It displays recently blocked apps with
 * quick actions to temporarily allow them.
 *
 * The bubble is created via NotificationCompat.BubbleMetadata in BubbleHelper.
 * This activity provides the content that appears when the bubble is expanded.
 *
 * Based on: https://developer.android.com/develop/ui/views/notifications/bubbles
 *
 * Key features:
 * - Shows list of recently blocked apps
 * - Quick action to temporarily allow apps for 15 minutes
 * - Material Design 3 UI
 * - Works with Android's system bubble framework (not custom overlays)
 */
class BubbleActivity : AppCompatActivity(R.layout.activity_bubble) {
    private val b by viewBinding(ActivityBubbleBinding::bind)

    private val persistentState by inject<PersistentState>()
    private val connectionTrackerDAO by inject<ConnectionTrackerDAO>()
    private val appInfoRepository by inject<AppInfoRepository>()
    private val dnsLogDAO by inject<DnsLogDAO>()

    private lateinit var blockedAdapter: BubbleBlockedAppsAdapter
    private lateinit var allowedAdapter: BubbleAllowedAppsAdapter

    private var blockedCollectJob: kotlinx.coroutines.Job? = null
    private var allowedCollectJob: kotlinx.coroutines.Job? = null

    private var recyclerDecorationsAdded: Boolean = false

    companion object {
        private const val TAG = "BubbleActivity"
        private const val PAGE_SIZE = 20
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)

        Logger.d(TAG, "BubbleActivity onCreate, taskId: $taskId")

        // Handle back button press - minimize instead of close
        onBackPressedDispatcher.addCallback(this) {
            // Move to background, don't finish the activity
            moveTaskToBack(true)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Logger.d(TAG, "BubbleActivity onNewIntent - bubble clicked again")
        // Don't do anything special - just let onResume handle the refresh
    }

    override fun onResume() {
        super.onResume()

        // If VPN is off, don't load anything / don't start collectors.
        if (!VpnController.hasTunnel()) {
            Logger.i(TAG, "VPN is off; not loading bubble lists")
            stopCollectors()
            showVpnOffState()
            return
        }

        showContentState()
        setupRecyclerViews()
        setupLoadStateListeners()

        // Start collectors once per resume; cancel previous collectors if any.
        startAllowedCollector()
        startBlockedCollector()
    }

    override fun onStop() {
        super.onStop()
        // Don't stop the service when activity is minimized
        // The bubble notification should remain visible
        Logger.d(TAG, "BubbleActivity stopped (minimized)")
    }

    override fun onDestroy() {
        super.onDestroy()
        // Don't stop the service when activity is destroyed
        // The service manages its own lifecycle based on the toggle setting
        Logger.d(TAG, "BubbleActivity destroyed")
    }


    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
    }


    private fun startAllowedCollector() {
        allowedCollectJob?.cancel()
        allowedCollectJob = lifecycleScope.launch {
            try {
                val now = System.currentTimeMillis()

                val allowedAppsPager = Pager(
                    config = PagingConfig(
                        pageSize = PAGE_SIZE,
                        enablePlaceholders = false
                    ),
                    pagingSourceFactory = {
                        AllowedAppsBubbleViewModel(appInfoRepository, now)
                    }
                ).flow.cachedIn(lifecycleScope)

                allowedAppsPager.collect { pagingData ->
                    if (!isFinishing && !isDestroyed) {
                        allowedAdapter.submitData(pagingData)
                    }
                }
            } catch (_: CancellationException) {
                Logger.d(TAG, "Allowed apps loading cancelled (activity destroyed)")
            } catch (e: Exception) {
                Logger.e(TAG, "err loading allowed apps: ${e.message}", e)
                if (!isFinishing && !isDestroyed) {
                    b.bubbleAllowedAppsLl.visibility = View.GONE
                }
            }
        }
    }

    private fun startBlockedCollector() {
        blockedCollectJob?.cancel()
        blockedCollectJob = lifecycleScope.launch {
            try {
                val now = System.currentTimeMillis()
                val last15Mins = now - (15 * 60 * 1000)

                val tempAllowedApps = withContext(Dispatchers.IO) {
                    appInfoRepository.getAllTempAllowedApps(now)
                }
                val tempAllowedUids = tempAllowedApps.map { it.uid }.toSet()

                val blockedAppsPager = Pager(
                    config = PagingConfig(
                        pageSize = PAGE_SIZE,
                        enablePlaceholders = false
                    ),
                    pagingSourceFactory = {
                        BlockedAppsBubbleViewModel(
                            connectionTrackerDAO,
                            dnsLogDAO,
                            appInfoRepository,
                            last15Mins,
                            tempAllowedUids
                        )
                    }
                ).flow.cachedIn(lifecycleScope)

                blockedAppsPager.collect { pagingData ->
                    if (!isFinishing && !isDestroyed) {
                        blockedAdapter.submitData(pagingData)
                    }
                }

            } catch (_: CancellationException) {
                Logger.d(TAG, "Blocked apps loading cancelled (activity destroyed)")
            } catch (e: Exception) {
                Logger.e(TAG, "err loading blocked apps: ${e.message}", e)
                if (!isFinishing && !isDestroyed) {
                    b.bubbleProgressCard.visibility = View.GONE
                    b.bubbleProgressBar.visibility = View.GONE
                    b.bubbleEmptyState.visibility = View.VISIBLE
                    b.bubbleRecyclerView.visibility = View.GONE
                }
            }
        }
    }

    private fun allowApp(blockedApp: BlockedAppInfo) {
        // Optimistic UI update: remove right away from blocked list for fast feedback.
        // PagingDataAdapter doesn't support direct removal; we force a refresh after DB update,
        // but also hide the row by refreshing immediately.
        lifecycleScope.launch {
            try {
                Logger.i(TAG, "Temporarily allowing app for 15 minutes: ${blockedApp.appName} (uid: ${blockedApp.uid})")

                withContext<Unit>(Dispatchers.IO) {
                    FirewallManager.updateTempAllow(blockedApp.uid, true)
                }

                if (!isFinishing && !isDestroyed) {
                    // Refresh BOTH lists: remove from blocked and show in allowed.
                    blockedAdapter.refresh()
                    allowedAdapter.refresh()
                }

                Logger.i(TAG, "App temporarily allowed successfully for 15 minutes")
            } catch (e: Exception) {
                Logger.e(TAG, "err allowing app: ${e.message}", e)
            }
        }
    }

    private fun removeAllowedApp(allowedApp: AllowedAppInfo) {
        lifecycleScope.launch {
            try {
                Logger.i(TAG, "Removing temp allow for app: ${allowedApp.appName} (uid: ${allowedApp.uid})")

                withContext(Dispatchers.IO) {
                    // Clear temp allow status
                    appInfoRepository.clearTempAllowByUid(allowedApp.uid)
                }

                if (!isFinishing && !isDestroyed) {
                    // Refresh BOTH lists: remove from allowed and allow it to appear again in blocked.
                    allowedAdapter.refresh()
                    blockedAdapter.refresh()
                }

                Logger.i(TAG, "Temp allow removed successfully")
            } catch (e: Exception) {
                Logger.e(TAG, "err removing allowed app: ${e.message}", e)
            }
        }
    }

    private fun setupRecyclerViews() {
        // Setup blocked apps RecyclerView
        blockedAdapter = BubbleBlockedAppsAdapter { blockedApp ->
            allowApp(blockedApp)
        }
        b.bubbleRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@BubbleActivity)
            adapter = blockedAdapter
            if (!recyclerDecorationsAdded) {
                // Smaller spacing; item XML already has margins.
                addItemDecoration(object : androidx.recyclerview.widget.RecyclerView.ItemDecoration() {
                    override fun getItemOffsets(
                        outRect: android.graphics.Rect,
                        view: View,
                        parent: androidx.recyclerview.widget.RecyclerView,
                        state: androidx.recyclerview.widget.RecyclerView.State
                    ) {
                        outRect.bottom = 4 // 4dp
                    }
                })
            }
        }

        // Setup allowed apps RecyclerView
        allowedAdapter = BubbleAllowedAppsAdapter { allowedApp ->
            removeAllowedApp(allowedApp)
        }
        b.bubbleAllowedRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@BubbleActivity)
            adapter = allowedAdapter
            if (!recyclerDecorationsAdded) {
                addItemDecoration(object : androidx.recyclerview.widget.RecyclerView.ItemDecoration() {
                    override fun getItemOffsets(
                        outRect: android.graphics.Rect,
                        view: View,
                        parent: androidx.recyclerview.widget.RecyclerView,
                        state: androidx.recyclerview.widget.RecyclerView.State
                    ) {
                        outRect.bottom = 4 // 4dp
                    }
                })
                recyclerDecorationsAdded = true
            }
        }
    }

    private fun setupLoadStateListeners() {
        // Set up load state listener for allowed apps
        allowedAdapter.addLoadStateListener { loadState ->
            if (!isFinishing && !isDestroyed) {
                // Check if data is loaded (not loading and no errors)
                val isLoaded = loadState.refresh is androidx.paging.LoadState.NotLoading

                if (isLoaded) {
                    // Show/hide allowed apps card based on item count
                    val itemCount = allowedAdapter.itemCount
                    if (itemCount == 0) {
                        b.bubbleAllowedAppsLl.visibility = View.GONE
                    } else {
                        b.bubbleAllowedAppsLl.visibility = View.VISIBLE
                        b.bubbleAllowedCount.text = itemCount.toString()
                    }
                }
            }
        }

        // Set up load state listener for blocked apps
        blockedAdapter.addLoadStateListener { loadState ->
            if (!isFinishing && !isDestroyed) {
                val isLoading = loadState.refresh is androidx.paging.LoadState.Loading
                val isError = loadState.refresh is androidx.paging.LoadState.Error
                val isLoaded = loadState.refresh is androidx.paging.LoadState.NotLoading

                when {
                    isLoading -> {
                        // Show loading state
                        b.bubbleProgressCard.visibility = View.VISIBLE
                        b.bubbleProgressBar.visibility = View.VISIBLE
                        b.bubbleEmptyState.visibility = View.GONE
                        b.bubbleRecyclerView.visibility = View.GONE
                    }
                    isError -> {
                        // Show error/empty state
                        b.bubbleProgressCard.visibility = View.GONE
                        b.bubbleProgressBar.visibility = View.GONE
                        b.bubbleEmptyState.visibility = View.VISIBLE
                        b.bubbleRecyclerView.visibility = View.GONE
                    }
                    isLoaded -> {
                        // Hide loading, show content or empty state based on item count
                        b.bubbleProgressCard.visibility = View.GONE
                        b.bubbleProgressBar.visibility = View.GONE

                        val itemCount = blockedAdapter.itemCount
                        if (itemCount == 0) {
                            b.bubbleEmptyState.visibility = View.VISIBLE
                            b.bubbleRecyclerView.visibility = View.GONE
                        } else {
                            b.bubbleEmptyState.visibility = View.GONE
                            b.bubbleRecyclerView.visibility = View.VISIBLE
                        }
                    }
                }
            }
        }
    }

    private fun showVpnOffState() {
        // Avoid loading spinners if VPN isn't running.
        runCatching {
            b.bubbleProgressCard.visibility = View.GONE
            b.bubbleProgressBar.visibility = View.GONE
            b.bubbleAllowedAppsLl.visibility = View.GONE
            b.bubbleRecyclerView.visibility = View.GONE
            b.bubbleEmptyState.visibility = View.VISIBLE
            b.bubbleEmptyTitle.setText(R.string.bubble_empty_state_title)
        }
    }

    private fun showContentState() {
        runCatching {
            b.bubbleEmptyState.visibility = View.GONE
        }
    }

    private fun stopCollectors() {
        blockedCollectJob?.cancel()
        blockedCollectJob = null
        allowedCollectJob?.cancel()
        allowedCollectJob = null
    }
}
