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
import androidx.activity.compose.setContent
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.purchase.*

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.ServerOrderHistoryAdapter
import com.celzero.bravedns.iab.InAppBillingHandler
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.ui.bottomsheet.EntitlementDetailBottomSheet
import com.celzero.bravedns.ui.custom.EmbeddedDolphinContent
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Themes.Companion.isActivityLightTheme
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import com.celzero.bravedns.viewmodel.ServerOrderHistoryViewModel
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
class ServerOrderHistoryActivity : BaseActivity() {

    private val b = HistoryScreenState()
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
        setDolphinSignature()
        setContent { RethinkTheme {
            HistoryScreen(b, server = true, count = adapter.itemCount,
                key = { index -> index },
                onBack = { onBackPressedDispatcher.onBackPressed() },
                onFetchHidden = { if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) b.fetchProgress.isVisible = false },
                row = { index -> adapter.Row(index) })
        } }
    }

    /** Dolphin signature (at the bottom of the content.); random pairing, fresh on every visit. */
    private fun setDolphinSignature() {
        b.dolphinSignature.setContent(EmbeddedDolphinContent.random())
    }

    private fun applyScrollPadding() { /* LazyColumn follows the native app bar. */ }

    private fun setupToolbar() {
        b.tvHeroSubtitle.text = ""
    }

    private fun setupRecyclerView() {
        adapter = ServerOrderHistoryAdapter(this)
    }

    private fun setupClickListeners() {
        b.chipEntitlement.setOnClickListener {
            EntitlementDetailBottomSheet.newInstance()
                .show(supportFragmentManager, "entitlementDetails")
        }
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

                            b.listEpoch++
                        }
                        is ServerOrderHistoryViewModel.UiState.Empty -> {
                            showFetchProgress(false)
                            stopShimmer()
                            b.shimmer.isVisible = false
                            b.rvOrders.isVisible = false
                            b.errorState.isVisible = false
                            b.emptyState.isVisible = true

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
        if (visible) b.fetchProgress.isVisible = true
        b.fetchTarget = if (visible) 1f else 0f
    }

    private fun showShimmer() {
        b.shimmer.isVisible = true
        b.emptyState.isVisible = false
        b.errorState.isVisible = false
        b.rvOrders.isVisible = false
        startShimmer()
    }

    private fun loadHeroSubtitle() {
        io {
            val deviceId = InAppBillingHandler.getObfuscatedDeviceId()
            val expiry = VpnController.getWinExpiryTs() ?: 0L
            val hex = expiry.toString(16)
            val subtitle = buildHeroSubtitle(deviceId, hex)
            uiCtx {
                b.tvHeroSubtitle.text = subtitle
            }
        }
    }

    /**
     * purchase token (first 12 chars) · accountId (first 12 chars) • deviceId (first 4 chars).
     */
    private fun buildHeroSubtitle(deviceId: String, expiry: String): String {
        val sub = RpnProxyManager.getSubscriptionData()?.subscriptionStatus ?: return ""
        return heroIdentityLine(sub.purchaseToken, sub.accountId, deviceId, expiry)
    }

    private fun heroIdentityLine(token: String, accountId: String, deviceId: String, expiry: String): String {
        val t = token.take(12)
        val a = accountId.take(12)
        val d = deviceId.take(4)
        val idPart = listOf(a, d).filter { it.isNotBlank() }.joinToString(" • ")
        return listOf(t, idPart, expiry).filter { it.isNotBlank() }.joinToString(" · ")
    }

    private fun startShimmer() {
        b.shimmerRunning = true
    }

    private fun stopShimmer() {
        b.shimmerRunning = false
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
        withContext(Dispatchers.Main) {
            if (!isFinishing && !isDestroyed) {
                f()
            }
        }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }
}


