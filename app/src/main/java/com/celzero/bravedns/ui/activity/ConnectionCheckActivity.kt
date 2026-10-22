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

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.adapter.DecisionStepAdapter
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.databinding.ActivityConnectionCheckBinding
import com.celzero.bravedns.service.ConnectionSimulator
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities.isAtleastQ
import dev.androidbroadcast.vbpd.viewBinding
import inet.ipaddr.IPAddressString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class ConnectionCheckActivity : BaseActivity(R.layout.activity_connection_check) {
    private val b by viewBinding(ActivityConnectionCheckBinding::bind)
    private val persistentState by inject<PersistentState>()
    private val stepsAdapter by lazy { DecisionStepAdapter(this) }

    // uid of the selected app; null = unknown / no-uid
    private var selectedUid: Int? = null
    private val uidByLabel = linkedMapOf<String, Int?>()

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = Themes.isActivityLightTheme(isDarkThemeOn(), persistentState.theme)
            window.isNavigationBarContrastEnforced = false
        }
        // debug-only diagnostic: bail out if somehow launched in a production build
        if (!DEBUG) {
            finish()
            return
        }
        setupAppPicker()
        setupStepsRecycler()
        setupClickListeners()
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
    }

    private fun setupAppPicker() {
        uidByLabel[UNKNOWN_APP_LABEL] = null
        FirewallManager.getApplistObserver().observe(this) { apps ->
            refreshAppDropdown(apps)
        }
    }

    private fun refreshAppDropdown(apps: Collection<AppInfo>) {
        val byUid = linkedMapOf<Int, AppInfo>()
        apps.forEach { app ->
            // multiple packages can share a uid; keep the first entry per uid
            if (!byUid.containsKey(app.uid)) byUid[app.uid] = app
        }
        byUid.values.sortedBy { it.appName.lowercase() }.forEach { app ->
            val label = "${app.appName} (${app.packageName})"
            if (!uidByLabel.containsKey(label)) uidByLabel[label] = app.uid
        }
        val labels = uidByLabel.keys.toList()
        b.ccAppInput.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, labels))
        if (b.ccAppInput.text.isNullOrBlank()) {
            b.ccAppInput.setText(UNKNOWN_APP_LABEL, false)
            selectedUid = null
        }
        b.ccAppInput.setOnItemClickListener { _, _, position, _ ->
            selectedUid = uidByLabel[labels[position]]
        }
    }

    private fun setupStepsRecycler() {
        b.ccStepsRv.layoutManager = LinearLayoutManager(this)
        b.ccStepsRv.adapter = stepsAdapter
    }

    private fun setupClickListeners() {
        b.ccSimulateBtn.setOnClickListener { runSimulation() }
    }

    private fun runSimulation() {
        val domain = b.ccDomainInput.text?.toString()?.trim().orEmpty()
        if (domain.isBlank()) {
            b.ccDomainInputLayout.error = getString(R.string.connection_check_err_domain)
            return
        }
        b.ccDomainInputLayout.error = null
        val ip = b.ccIpInput.text?.toString()?.trim().orEmpty().ifEmpty { null }
        if (ip != null && runCatching { IPAddressString(ip).address }.isFailure) {
            b.ccIpInputLayout.error = getString(R.string.connection_check_err_ip)
            return
        }
        b.ccIpInputLayout.error = null
        hideKeyboard()
        setResultLoading()

        io {
            val result = ConnectionSimulator.simulate(
                ConnectionSimulator.SimulationInput(domain = domain, ip = ip, uid = selectedUid)
            )
            uiCtx { renderResult(result) }
        }
    }

    private fun setResultLoading() {
        b.ccResultCard.visibility = View.VISIBLE
        b.ccVerdictIcon.setImageResource(R.drawable.ic_shield_check)
        b.ccVerdictIcon.setColorFilter(UIUtils.fetchColor(this, R.attr.primaryTextColor))
        b.ccVerdictTitle.text = getString(R.string.connection_check_running)
        b.ccDetailsTv.text = ""
        b.ccConfigTv.text = ""
        stepsAdapter.submitList(emptyList())
        b.ccSimulateBtn.isEnabled = false
    }

    private fun renderResult(result: ConnectionSimulator.SimulationResult) {
        b.ccSimulateBtn.isEnabled = true
        if (result.blocked) {
            b.ccVerdictIcon.setImageResource(R.drawable.ic_cross_accent)
            b.ccVerdictIcon.setColorFilter(UIUtils.fetchColor(this, R.attr.accentBad))
        } else {
            b.ccVerdictIcon.setImageResource(R.drawable.ic_tick)
            b.ccVerdictIcon.setColorFilter(UIUtils.fetchColor(this, R.attr.accentGood))
        }
        b.ccVerdictTitle.text = getString(
            if (result.blocked) R.string.connection_check_verdict_blocked
            else R.string.connection_check_verdict_allowed
        )
        b.ccDetailsTv.text = result.detailLines.joinToString("\n") { "• $it" }
        b.ccConfigTv.text = result.configLines.joinToString("\n") { "• $it" }
        stepsAdapter.submitList(result.steps)
        b.ccResultCard.visibility = View.VISIBLE
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
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

    companion object {
        private const val UNKNOWN_APP_LABEL = "Unknown app (no uid)"
    }
}
