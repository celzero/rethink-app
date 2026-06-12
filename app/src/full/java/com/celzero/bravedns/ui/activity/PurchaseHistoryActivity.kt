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
import Logger.LOG_TAG_UI
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import com.celzero.bravedns.ui.BaseActivity
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.paging.LoadState
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.PurchaseHistoryAdapter
import com.celzero.bravedns.databinding.ActivityPurchaseHistoryBinding
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import com.celzero.bravedns.viewmodel.PurchaseHistoryViewModel
import com.facebook.shimmer.Shimmer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import com.celzero.bravedns.iab.InAppBillingHandler
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * Displays the full purchase / subscription state-change history stored in
 * the SubscriptionStateHistory table, loaded in pages of 30 rows at a time
 * (using Paging 3) to avoid an ANR on devices with thousands of records.
 */
class PurchaseHistoryActivity : BaseActivity(R.layout.activity_purchase_history) {

    private val b by viewBinding(ActivityPurchaseHistoryBinding::bind)
    private val viewModel: PurchaseHistoryViewModel by viewModel()
    private lateinit var adapter: PurchaseHistoryAdapter
    private val persistentState by inject<PersistentState>()

    /** True after the first non-loading page arrives; prevents re-animation on config change. */
    private var listFirstShown = false

    companion object {
        private const val TAG = "PurchaseHistoryAct"
        private const val KEY_LIST_SHOWN = "list_first_shown"
    }

    private fun Context.isDarkThemeOn(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)

        handleFrostEffectIfNeeded(persistentState.theme)

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = Themes.isActivityLightTheme(isDarkThemeOn(), persistentState.theme)
            window.isNavigationBarContrastEnforced = false
        }

        listFirstShown = savedInstanceState?.getBoolean(KEY_LIST_SHOWN, false) ?: false

        setupToolbar()
        setupRecyclerView()
        startShimmer()
        observeHistory()
        observeTotalCount()
        loadHeroSubtitle()
        applyScrollPadding()
    }

    private fun applyScrollPadding() {
        b.contentContainer.post {
            b.contentContainer.setPadding(
                b.contentContainer.paddingLeft,
                0,
                b.contentContainer.paddingRight,
                b.contentContainer.paddingBottom
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_LIST_SHOWN, listFirstShown)
    }

    private fun setupToolbar() {
        b.collapsingToolbar.title = getString(R.string.payment_history_title)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        b.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        b.tvHeroSubtitle.text = ""
    }

    private fun setupRecyclerView() {
        adapter = PurchaseHistoryAdapter(this)
        adapter.stateRestorationPolicy =
            RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY

        b.rvHistory.apply {
            val lm = LinearLayoutManager(this@PurchaseHistoryActivity)
            lm.isItemPrefetchEnabled = true
            layoutManager = lm
            adapter = this@PurchaseHistoryActivity.adapter
            setHasFixedSize(true)
        }

        // Once the first batch of items has been bound, allow state restoration on
        // config changes (matches DnsLogFragment's pattern).
        b.rvHistory.post {
            try {
                if (adapter.itemCount > 0) {
                    adapter.stateRestorationPolicy =
                        RecyclerView.Adapter.StateRestorationPolicy.ALLOW
                }
            } catch (_: Exception) {
                Logger.e(LOG_TAG_UI, "$TAG: err setting recycler restoration policy")
            }
        }
    }

    /**
     * Feeds pages of [com.celzero.bravedns.database.SubscriptionStateHistory] into the
     * adapter (no JOIN, no product-name / token fields).
     * PagingDataAdapter.submitData is lifecycle-aware: it automatically cancels
     * the previous collection when a new PagingData arrives (e.g. after invalidation).
     */
    private fun observeHistory() {
        viewModel.historyList.observe(this) { pagingData ->
            adapter.submitData(lifecycle, pagingData)
        }

        adapter.addLoadStateListener { loadState ->
            val refreshState = loadState.source.refresh
            when {
                refreshState is LoadState.Loading -> {
                    // First-time or refresh load: show shimmer, hide content
                    b.shimmer.isVisible = true
                    b.emptyState.isVisible = false
                    b.errorState.isVisible = false
                    b.rvHistory.isVisible = false
                    startShimmer()
                }
                refreshState is LoadState.Error -> {
                    stopShimmer()
                    b.shimmer.isVisible = false
                    b.emptyState.isVisible = false
                    b.errorState.isVisible = true
                    b.rvHistory.isVisible = false
                    b.tvEntryCount.text = getString(R.string.symbol_hyphen)
                    val msg = refreshState.error.localizedMessage ?: "Unknown error"
                    b.tvErrorMessage.text = msg
                    Logger.e(LOG_TAG_UI, "$TAG error loading history: $msg")
                    b.btnRetry.setOnClickListener { adapter.retry() }
                }
                refreshState is LoadState.NotLoading -> {
                    stopShimmer()
                    b.shimmer.isVisible = false
                    b.errorState.isVisible = false

                    val isEmpty =
                        loadState.append.endOfPaginationReached && adapter.itemCount == 0
                    b.emptyState.isVisible = isEmpty
                    b.rvHistory.isVisible = !isEmpty

                    if (!isEmpty && !listFirstShown) {
                        listFirstShown = true
                        adapter.stateRestorationPolicy =
                            RecyclerView.Adapter.StateRestorationPolicy.ALLOW
                        b.rvHistory.alpha = 0f
                        b.rvHistory.animate().alpha(1f).setDuration(300).start()
                    }
                }
            }
        }
    }

    /** Populates the entry-count badge once the total is known. */
    private fun observeTotalCount() {
        viewModel.totalCount.observe(this) { count ->
            b.tvEntryCount.text = count.toString()
        }
    }

    private fun loadHeroSubtitle() {
        io {
            val deviceId = InAppBillingHandler.getObfuscatedDeviceId()
            val subtitle = buildHeroSubtitle(deviceId)
            uiCtx {
                b.tvHeroSubtitle.text = subtitle
            }
        }
    }

    private fun buildHeroSubtitle(deviceId: String): String {
        val sub = RpnProxyManager.getSubscriptionData() ?: return ""
        // Purchase token (show first 12 chars)
        var token = sub.subscriptionStatus.purchaseToken ?: ""
        token = token.length.let { if (it > 12) token.take(12) else token.ifBlank { "" } }
        val accountId = sub.subscriptionStatus.accountId.take(12).ifBlank { return token }
        val deviceId = deviceId.take(4).ifBlank { return token }
        val id = "$accountId • $deviceId"
        return if (token.isNotEmpty()) "$token \u00B7 $id" else id
    }

    private fun startShimmer() {
        if (b.shimmer.isShimmerStarted) return
        val shimmer = Shimmer.AlphaHighlightBuilder()
            .setDuration(1500)
            .setBaseAlpha(0.85f)
            .setDropoff(1f)
            .setHighlightAlpha(0.35f)
            .build()
        b.shimmer.setShimmer(shimmer)
        b.shimmer.startShimmer()
    }

    private fun stopShimmer() {
        if (b.shimmer.isShimmerStarted) b.shimmer.stopShimmer()
    }

    override fun onPause() {
        super.onPause()
        stopShimmer()
    }

    override fun onResume() {
        super.onResume()
        if (b.shimmer.isVisible) startShimmer()
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }
}
