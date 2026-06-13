/*
 * Copyright 2026 RethinkDNS and its authors
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
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.ServerOrderHistoryAdapter
import com.celzero.bravedns.databinding.ActivityServerOrderHistoryBinding
import com.celzero.bravedns.iab.InAppBillingHandler
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Themes.Companion.isActivityLightTheme
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import com.celzero.bravedns.viewmodel.ServerOrderHistoryViewModel
import com.facebook.shimmer.Shimmer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * Shows the user's raw purchase / order records fetched live from the billing
 * server's `GET /g/tx?cid=&purchaseToken=&tot=20` endpoint.
 *
 * Unlike [PurchaseHistoryActivity] (which shows local state-machine
 * transitions stored in Room), this activity always fetches fresh data from
 * the server, giving the user an accurate view of what the server holds.
 */
class ServerOrderHistoryActivity : BaseActivity(R.layout.activity_server_order_history) {

    private val b by viewBinding(ActivityServerOrderHistoryBinding::bind)
    private val viewModel: ServerOrderHistoryViewModel by viewModel()
    private lateinit var adapter: ServerOrderHistoryAdapter
    private val persistentState by inject<PersistentState>()

    companion object {
        private const val TAG = "ServerOrderAct"
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
            controller.isAppearanceLightNavigationBars =
                isActivityLightTheme(isDarkThemeOn(), persistentState.theme)
            window.isNavigationBarContrastEnforced = false
        }

        setupToolbar()
        setupRecyclerView()
        setupClickListeners()
        startShimmer()
        observeUiState()
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

    private fun setupToolbar() {
        b.collapsingToolbar.title = getString(R.string.server_order_title)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        b.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        b.tvHeroSubtitle.text = ""
    }

    private fun setupRecyclerView() {
        adapter = ServerOrderHistoryAdapter(this)
        b.rvOrders.apply {
            layoutManager = LinearLayoutManager(this@ServerOrderHistoryActivity)
                .also { it.isItemPrefetchEnabled = true }
            adapter = this@ServerOrderHistoryActivity.adapter
            setHasFixedSize(true)
        }
    }

    private fun setupClickListeners() {
        b.chipPaymentHistory.setOnClickListener {
            openBillingHistory()
        }
    }


    private fun openBillingHistory() {
        try {
            startActivity(Intent(this, PurchaseHistoryActivity::class.java))
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "$TAG open PurchaseHistoryActivity error: ${e.message}", e)
            showToastUiCentered(this, getString(R.string.payment_history_open_error), Toast.LENGTH_SHORT)
        }
    }

    private fun observeUiState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        is ServerOrderHistoryViewModel.UiState.Loading -> {
                            showShimmer()
                            showFetchProgress(true)
                        }
                        is ServerOrderHistoryViewModel.UiState.Success -> {
                            showFetchProgress(false)
                            stopShimmer()
                            b.shimmer.isVisible = false
                            b.emptyState.isVisible = false
                            b.errorState.isVisible = false
                            b.rvOrders.isVisible = true

                            adapter.submitList(state.orders)
                            animateCountBadge(state.orders.size.toString())

                            b.rvOrders.alpha = 0f
                            b.rvOrders.animate().alpha(1f).setDuration(280).start()
                        }
                        is ServerOrderHistoryViewModel.UiState.Empty -> {
                            showFetchProgress(false)
                            stopShimmer()
                            b.shimmer.isVisible = false
                            b.rvOrders.isVisible = false
                            b.errorState.isVisible = false
                            b.emptyState.isVisible = true
                            animateCountBadge("0")

                            b.tvEmptySubtitle.text = if (state.isNoCredentials)
                                getString(R.string.server_order_empty_no_credentials)
                            else
                                getString(R.string.server_order_empty_subtitle)
                        }
                        is ServerOrderHistoryViewModel.UiState.Error -> {
                            showFetchProgress(false)
                            stopShimmer()
                            b.shimmer.isVisible = false
                            b.rvOrders.isVisible = false
                            b.emptyState.isVisible = false
                            b.errorState.isVisible = true
                            animateCountBadge("-")

                            val msg = state.message.take(160)
                            b.tvErrorMessage.text = msg
                            Logger.e(LOG_TAG_UI, "$TAG error: $msg")
                            b.btnRetry.setOnClickListener { viewModel.reload() }
                        }
                    }
                }
            }
        }
    }

    /**
     * Shows or hides the thin progress indicator that signals an in-flight network request.
     * Uses a smooth alpha animation to avoid jarring transitions.
     */
    private fun showFetchProgress(visible: Boolean) {
        if (visible) {
            b.fetchProgress.isVisible = true
            b.fetchProgress.animate().alpha(1f).setDuration(200).start()
        } else {
            b.fetchProgress.animate().alpha(0f).setDuration(400)
                .withEndAction {
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        b.fetchProgress.isVisible = false
                    }
                }
                .start()
        }
    }

    /**
     * Cross-fades the order-count badge from its current value to [newValue].
     * The animation prevents a jarring number pop when data arrives.
     */
    private fun animateCountBadge(newValue: String) {
        if (b.tvOrderCount.text == newValue) return
        b.tvOrderCount.animate().alpha(0f).setDuration(150).withEndAction {
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@withEndAction
            b.tvOrderCount.text = newValue
            b.tvOrderCount.animate().alpha(1f).setDuration(200).start()
        }.start()
    }

    private fun showShimmer() {
        b.shimmer.isVisible = true
        b.emptyState.isVisible = false
        b.errorState.isVisible = false
        b.rvOrders.isVisible = false
        b.tvOrderCount.text = "-"
        startShimmer()
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
        var token = sub.subscriptionStatus.purchaseToken ?: ""
        token = if (token.length > 12) token.take(12) else token.ifBlank { "" }
        val accountId = sub.subscriptionStatus.accountId.take(12).ifBlank { return token }
        val did = deviceId.take(4).ifBlank { return token }
        val id = "$accountId • $did"
        return if (token.isNotEmpty()) "$token · $id" else id
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


