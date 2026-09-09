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

import com.celzero.bravedns.util.Logger
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.PingTestHistoryAdapter
import com.celzero.bravedns.databinding.ActivityPingTestBinding
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.rpnproxy.RpnProxyManager.PingTestOutcome
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities.isAtleastQ
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.Canvas
import android.graphics.Paint
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.koin.android.ext.android.inject
import kotlin.time.Duration.Companion.milliseconds

class PingTestActivity : BaseActivity(R.layout.activity_ping_test) {
    private val b by viewBinding(ActivityPingTestBinding::bind)
    private val persistentState by inject<PersistentState>()

    companion object {
        private const val TAG = "PingUi"
        private const val MIN_TEST_DURATION_MS = 1500L
    }

    private var isTesting = false
    private var testStartTime: Long = 0
    private val historyAdapter by lazy { PingTestHistoryAdapter(this) }

    /**
     * False when the VPN or RPN proxy is inactive: custom target entry is then
     * disabled and only the AUTO (default probes) test via [VpnController.testRpnProxy]
     * is allowed, with a small note shown instead of the old blocking dialog.
     */
    private var allowCustomTargets = false

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = Themes.isActivityLightTheme(isDarkThemeOn(), persistentState.theme)
            window.isNavigationBarContrastEnforced = false
        }
        initView()
        setupClickListeners()
        setupHistory()
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
    }

    private fun initView() {
        allowCustomTargets = VpnController.hasTunnel() && RpnProxyManager.isRpnActive()
        // Input stays empty; an empty input runs the AUTO (default probes) test,
        // conveyed via the field's hint so the user can type straight away.
        showReadyState()
        if (!allowCustomTargets) {
            b.rpnInactiveNote.visibility = View.VISIBLE
            setInputEnabled(false)
        }
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

    private fun setupHistory() {
        b.historyRecycler.layoutManager = LinearLayoutManager(this)
        b.historyRecycler.adapter = historyAdapter
        b.historyRecycler.addItemDecoration(historyDividerDecoration())

        lifecycleScope.launch {
            RpnProxyManager.pingTestHistory.collect { entries ->
                historyAdapter.submitList(entries)
                b.historyCard.visibility = if (entries.isNotEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    /**
     * Hairline dividers between history rows, inset to align with the row text
     * (16dp start padding + 32dp icon + 13dp gap), mirroring the results card.
     */
    private fun historyDividerDecoration(): RecyclerView.ItemDecoration {
        val density = resources.displayMetrics.density
        val paint = Paint().apply {
            color = UIUtils.fetchColor(this@PingTestActivity, R.attr.colorSurfaceVariant)
            alpha = 102 // ~40% for a subtle hairline
            strokeWidth = density
        }
        return object : RecyclerView.ItemDecoration() {
            override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
                val start = (density * 61f).toInt()
                for (i in 0 until parent.childCount - 1) {
                    val child = parent.getChildAt(i)
                    val params = child.layoutParams as RecyclerView.LayoutParams
                    val top = (child.bottom + params.bottomMargin).toFloat()
                    c.drawLine(start.toFloat(), top, parent.width.toFloat(), top, paint)
                }
            }
        }
    }

    private fun showReadyState() {
        b.statusIcon.setImageResource(R.drawable.ic_shield_check)
        b.statusIcon.colorFilter = null
        b.statusIcon.setColorFilter(UIUtils.fetchColor(this, R.attr.primaryTextColor))
        b.statusTitle.text = getString(R.string.settings_connectivity_checks)
        b.statusDescription.text = getString(R.string.ping_ready_desc)
        b.pingButton.text = getString(R.string.rpn_perform_test)
        b.pingButton.isEnabled = true

        b.progressIndicator.visibility = View.GONE
        b.latencyContainer.visibility = View.GONE
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

        setInputEnabled(true)
    }

    private fun setInputEnabled(enabled: Boolean) {
        // never re-enable custom entry when RPN/VPN is inactive (see allowCustomTargets)
        val effective = enabled && allowCustomTargets
        b.reachInputLayout.isEnabled = effective
        b.reachInput.isEnabled = effective
        b.reachInput.isFocusable = effective
        b.reachInput.isFocusableInTouchMode = effective
    }

    private fun performTest() {
        val rawInput = b.reachInput.text?.toString()?.trim().orEmpty()
        val csv = rawInput
        val domains = csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        // Guard: RPN must be enabled
        if (!RpnProxyManager.isRpnEnabled() && csv.isNotEmpty()) {
            Toast.makeText(this, getString(R.string.ping_reach_rpn_disabled), Toast.LENGTH_LONG).show()
            return
        }

        b.reachInputLayout.error = null

        // Dismiss keyboard and clear focus
        b.reachInput.clearFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(b.reachInput.windowToken, 0)

        showTestingState()

        io {
            try {
                if (!RpnProxyManager.isRpnActive() && csv.isNotEmpty()) {
                    uiCtx { showNoProxyState() }
                    return@io
                }

                val startTime = System.currentTimeMillis()
                if (domains.isEmpty()) {
                    val result = VpnController.testRpnProxy()
                    val latency = System.currentTimeMillis() - startTime
                    recordHistory(csv, if (result) PingTestOutcome.SUCCESS else PingTestOutcome.FAILURE,
                        latency, if (result) 1 else 0, 1)
                    uiCtx {
                        if (result) showSuccessState(latency)
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

                    val passed = results.count { it.second }
                    val outcome = when {
                        results.all { it.second } -> PingTestOutcome.SUCCESS
                        results.any { it.second } -> PingTestOutcome.PARTIAL
                        else -> PingTestOutcome.FAILURE
                    }
                    recordHistory(csv, outcome, latency, passed, results.size)

                    // Honour minimum animation duration for UX
                    val elapsed = System.currentTimeMillis() - testStartTime
                    if (elapsed < MIN_TEST_DURATION_MS) {
                        delay((MIN_TEST_DURATION_MS - elapsed).milliseconds)
                    }

                    val allOk = results.all { it.second }
                    val anyOk = results.any { it.second }

                    uiCtx {
                        when {
                            allOk -> showSuccessState(latency)
                            anyOk -> showPartialState(latency)
                            else -> showFailureState()
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.e(Logger.LOG_IAB, "$TAG err during test: ${e.message}", e)
                recordHistory(csv, PingTestOutcome.FAILURE, System.currentTimeMillis() - testStartTime,
                    0, maxOf(1, domains.size))
                uiCtx { showFailureState() }
            }
        }
    }

    private fun recordHistory(targets: String, outcome: PingTestOutcome, latencyMs: Long, passed: Int, total: Int) {
        RpnProxyManager.recordPingTest(targets, outcome, latencyMs, passed, total)
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
