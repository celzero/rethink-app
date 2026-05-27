/*
 * Copyright 2024 RethinkDNS and its authors
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
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.celzero.bravedns.ui.BaseActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.ActivityPingTestBinding
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class PingTestActivity : BaseActivity(R.layout.activity_ping_test) {
    private val b by viewBinding(ActivityPingTestBinding::bind)
    private val persistentState by inject<PersistentState>()

    companion object {
        private const val TAG = "PingUi"
        private const val MIN_TEST_DURATION_MS = 1500L
    }

    private var isTesting = false
    private var testStartTime: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = false
            window.isNavigationBarContrastEnforced = false
        }
        initView()
        setupClickListeners()
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
    }

    private fun initView() {
        if (!VpnController.hasTunnel()) {
            showStartVpnDialog()
            return
        }
        // Pre-fill with default domains so user can immediately run the test.
        if (b.reachInput.text.isNullOrEmpty()) {
            b.reachInput.setText(getString(R.string.lbl_auto))
        }
        showReadyState()
    }

    private fun showStartVpnDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.vpn_not_active_dialog_title))
            .setMessage(getString(R.string.vpn_not_active_dialog_desc))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.dns_info_positive)) { dialog, _ ->
                dialog.dismiss()
                finish()
            }
            .create()
            .show()
    }

    private fun setupClickListeners() {
        b.pingButton.setOnClickListener {
            if (!isTesting) performTest()
        }

        b.reachInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                performTest()
                true
            } else {
                false
            }
        }
    }

    private fun showReadyState() {
        b.statusIcon.setImageResource(R.drawable.ic_shield_check)
        b.statusIcon.colorFilter = null
        b.statusIcon.setColorFilter(ContextCompat.getColor(this, R.color.colorPrimary))
        b.statusTitle.text = getString(R.string.ping_ready_title)
        b.statusDescription.text = getString(R.string.ping_ready_desc)
        b.pingButton.text = getString(R.string.ping_test_button)
        b.pingButton.isEnabled = true

        b.progressIndicator.visibility = View.GONE
        b.latencyContainer.visibility = View.GONE
        b.resultsCard.visibility = View.GONE
    }

    private fun showTestingState() {
        isTesting = true
        testStartTime = System.currentTimeMillis()

        animateIconPulse()

        b.statusIcon.setColorFilter(UIUtils.fetchColor(this, R.attr.primaryTextColor))
        b.statusTitle.text = getString(R.string.ping_testing_title)
        b.statusDescription.text = getString(R.string.ping_testing_desc)
        b.pingButton.text = getString(R.string.ping_testing_title)
        b.pingButton.isEnabled = false

        b.progressIndicator.visibility = View.VISIBLE
        b.latencyContainer.visibility = View.GONE
        b.resultsCard.visibility = View.GONE

        setInputEnabled(false)
    }

    private fun showSuccessState(latencyMs: Long) {
        isTesting = false
        animateSuccess()

        b.statusIcon.setImageResource(R.drawable.ic_tick)
        b.statusIcon.setColorFilter(ContextCompat.getColor(this, R.color.accentGood))
        b.statusTitle.text = getString(R.string.ping_success_title)
        b.statusDescription.text = getString(R.string.ping_success_desc)
        b.pingButton.text = getString(R.string.ping_test_again)
        b.pingButton.isEnabled = true

        b.progressIndicator.visibility = View.GONE
        b.latencyContainer.visibility = View.VISIBLE
        b.latencyText.text = getString(R.string.ping_total_latency, latencyMs)

        setInputEnabled(true)
    }

    private fun showPartialState(latencyMs: Long) {
        isTesting = false
        animateFailure()

        b.statusIcon.setImageResource(R.drawable.ic_cross_accent)
        b.statusIcon.setColorFilter(UIUtils.fetchColor(this, R.attr.accentWarning))
        b.statusTitle.text = getString(R.string.ping_partial_title)
        b.statusDescription.text = getString(R.string.ping_partial_desc)
        b.pingButton.text = getString(R.string.ping_test_again)
        b.pingButton.isEnabled = true

        b.progressIndicator.visibility = View.GONE
        b.latencyContainer.visibility = View.VISIBLE
        b.latencyText.text = getString(R.string.ping_total_latency, latencyMs)

        setInputEnabled(true)
    }

    private fun showFailureState() {
        isTesting = false
        animateFailure()

        b.statusIcon.setImageResource(R.drawable.ic_cross_accent)
        b.statusIcon.setColorFilter(ContextCompat.getColor(this, R.color.accentBad))
        b.statusTitle.text = getString(R.string.ping_failure_title)
        b.statusDescription.text = getString(R.string.ping_failure_desc)
        b.pingButton.text = getString(R.string.ping_test_again)
        b.pingButton.isEnabled = true

        b.progressIndicator.visibility = View.GONE
        b.latencyContainer.visibility = View.GONE

        setInputEnabled(true)
    }

    private fun showNoProxyState() {
        isTesting = false

        b.statusIcon.setImageResource(R.drawable.ic_cross_accent)
        b.statusIcon.setColorFilter(ContextCompat.getColor(this, R.color.accentBad))
        b.statusTitle.text = getString(R.string.ping_no_proxy_title)
        b.statusDescription.text = getString(R.string.ping_reach_rpn_disabled)
        b.pingButton.text = getString(R.string.ping_test_again)
        b.pingButton.isEnabled = true

        b.progressIndicator.visibility = View.GONE
        b.latencyContainer.visibility = View.GONE
        b.resultsCard.visibility = View.GONE

        setInputEnabled(true)
    }

    private fun setInputEnabled(enabled: Boolean) {
        b.reachInputLayout.isEnabled = enabled
        b.reachInput.isEnabled = enabled
        b.reachInput.isFocusable = enabled
        b.reachInput.isFocusableInTouchMode = enabled
    }

    private fun showResultsCard(results: List<Pair<String, Boolean>>) {
        b.resultsCard.visibility = View.GONE
        b.resultsContainer.removeAllViews()


        results.forEach { (domain, reachable) ->
            val row = LayoutInflater.from(this).inflate(
                R.layout.item_ping_result_row, b.resultsContainer, false
            )
            row.findViewById<ImageView>(R.id.row_icon).apply {
                if (reachable) {
                    setImageResource(R.drawable.ic_tick)
                    setColorFilter(UIUtils.fetchColor(this@PingTestActivity, R.attr.accentGood))
                } else {
                    setImageResource(R.drawable.ic_cross_accent)
                    setColorFilter(UIUtils.fetchColor(this@PingTestActivity, R.attr.accentBad))
                }
            }
            row.findViewById<TextView>(R.id.row_domain).apply {
                text = domain
                setTextColor(UIUtils.fetchColor(this@PingTestActivity, R.attr.primaryTextColor))
            }
            row.findViewById<TextView>(R.id.row_status).apply {
                if (reachable) {
                    text = getString(R.string.ping_reach_reachable)
                    setTextColor(UIUtils.fetchColor(this@PingTestActivity, R.attr.accentGood))
                } else {
                    text = getString(R.string.ping_reach_unreachable)
                    setTextColor(UIUtils.fetchColor(this@PingTestActivity, R.attr.accentBad))
                }
            }
            b.resultsContainer.addView(row)
        }

        // Animate results card in
        b.resultsCard.alpha = 0f
        b.resultsCard.translationY = 40f
        b.resultsCard.visibility = View.VISIBLE
        b.resultsCard.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(400)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    private fun performTest() {
        // Guard: RPN must be enabled
        if (!RpnProxyManager.isRpnEnabled()) {
            Toast.makeText(this, getString(R.string.ping_reach_rpn_disabled), Toast.LENGTH_LONG).show()
            return
        }

        val rawInput = b.reachInput.text?.toString()?.trim().orEmpty()
        val csv = if (rawInput.isEmpty() || rawInput == getString(R.string.lbl_auto)) {
            ""
        } else {
            rawInput
        }
        val domains = csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        b.reachInputLayout.error = null

        // Dismiss keyboard and clear focus
        b.reachInput.clearFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(b.reachInput.windowToken, 0)

        showTestingState()

        io {
            try {
                if (!RpnProxyManager.isRpnActive()) {
                    uiCtx { showNoProxyState() }
                    return@io
                }

                val startTime = System.currentTimeMillis()
                if (domains.isEmpty()) {
                    val result = VpnController.testRpnProxy()
                    uiCtx {
                        if (result) showSuccessState(System.currentTimeMillis() - startTime)
                        else showFailureState()
                    }
                } else {
                    val results: List<Pair<String, Boolean>> = domains.map { domain ->
                        domain to VpnController.isRpnReachable(domain)
                    }
                    val latency = System.currentTimeMillis() - startTime

                    Logger.d(
                        Logger.LOG_IAB,
                        "$TAG reachability results: $results, latency: ${latency}ms"
                    )

                    // Honour minimum animation duration for UX
                    val elapsed = System.currentTimeMillis() - testStartTime
                    if (elapsed < MIN_TEST_DURATION_MS) {
                        delay(MIN_TEST_DURATION_MS - elapsed)
                    }

                    val allOk = results.all { it.second }
                    val anyOk = results.any { it.second }

                    uiCtx {
                        when {
                            allOk -> showSuccessState(latency)
                            anyOk -> showPartialState(latency)
                            else -> showFailureState()
                        }
                        showResultsCard(results)
                    }
                }
            } catch (e: Exception) {
                Logger.e(Logger.LOG_IAB, "$TAG err during test: ${e.message}", e)
                uiCtx { showFailureState() }
            }
        }
    }

    private fun animateIconPulse() {
        val scaleX = ObjectAnimator.ofFloat(b.statusIcon, "scaleX", 1f, 0.75f, 1f)
        val scaleY = ObjectAnimator.ofFloat(b.statusIcon, "scaleY", 1f, 0.75f, 1f)
        val alpha  = ObjectAnimator.ofFloat(b.statusIcon, "alpha",  1f, 0.5f,  1f)
        AnimatorSet().apply {
            playTogether(scaleX, scaleY, alpha)
            duration = 900
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun animateSuccess() {
        val scaleX   = ObjectAnimator.ofFloat(b.statusIcon, "scaleX",    0f, 1.3f, 1f)
        val scaleY   = ObjectAnimator.ofFloat(b.statusIcon, "scaleY",    0f, 1.3f, 1f)
        val rotation = ObjectAnimator.ofFloat(b.statusIcon, "rotation", -25f, 0f)
        AnimatorSet().apply {
            playTogether(scaleX, scaleY, rotation)
            duration = 500
            interpolator = OvershootInterpolator(2.2f)
            start()
        }
    }

    private fun animateFailure() {
        // Shake
        ObjectAnimator.ofFloat(
            b.statusIcon, "translationX",
            0f, 22f, -22f, 18f, -18f, 10f, -10f, 4f, -4f, 0f
        ).apply {
            duration = 480
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        // Scale in
        val scaleX = ObjectAnimator.ofFloat(b.statusIcon, "scaleX", 0f, 1f)
        val scaleY = ObjectAnimator.ofFloat(b.statusIcon, "scaleY", 0f, 1f)
        AnimatorSet().apply {
            playTogether(scaleX, scaleY)
            duration = 300
            start()
        }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }
}
