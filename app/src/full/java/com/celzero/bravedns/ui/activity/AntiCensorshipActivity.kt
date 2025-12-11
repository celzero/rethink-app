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
import Logger.LOG_TAG_FIREWALL
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.CompoundButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.databinding.ActivityAntiCensorshipBinding
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.Utilities.isOsVersionAbove412
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import com.celzero.firestack.settings.Settings
import org.koin.android.ext.android.inject

class AntiCensorshipActivity : AppCompatActivity(R.layout.activity_anti_censorship) {
    val b by viewBinding(ActivityAntiCensorshipBinding::bind)

    private val persistentState by inject<PersistentState>()
    private val eventLogger by inject<EventLogger>()

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
    }

    companion object {
        private const val DESYNC_SUPPORTED_VERSION = "4.12"
    }

    enum class DialStrategies(val mode: Int) {
        SPLIT_AUTO(Settings.SplitAuto),
        SPLIT_TCP(Settings.SplitTCP),
        SPLIT_TCP_TLS(Settings.SplitTCPOrTLS),
        DESYNC(Settings.SplitDesync),
        NEVER_SPLIT(Settings.SplitNever)
    }

    enum class RetryStrategies(val mode: Int) {
        RETRY_WITH_SPLIT(Settings.RetryWithSplit),
        RETRY_NEVER(Settings.RetryNever),
        RETRY_AFTER_SPLIT(Settings.RetryAfterSplit)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)

        handleFrostEffectIfNeeded(persistentState.theme)

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = false
            window.isNavigationBarContrastEnforced = false
        }
        initView()
        setupClickListeners()
    }

    private fun initView() {
        updateDialStrategy(persistentState.dialStrategy)
        updateRetryStrategy(persistentState.retryStrategy)

        b.settingsActivityVpnHeadingText.text = getString(R.string.anti_censorship_title).lowercase()
    }

    private fun updateDialStrategy(selectedState: Int) {
        if (!isOsVersionAbove412(DESYNC_SUPPORTED_VERSION)) {
            // desync is not supported for os version below 4.12
            // so reset the dial strategy to split auto if desync is selected
            if (selectedState == DialStrategies.DESYNC.mode) {
                persistentState.dialStrategy = DialStrategies.SPLIT_AUTO.mode
                b.acRadioDesync.isChecked = false
                b.acDesyncRl.visibility = View.GONE
                b.acRadioSplitAuto.isChecked = true
                Logger.i(LOG_TAG_FIREWALL, "Desync mode is not supported in Android 11 and below")
                return
            } else {
                b.acRadioDesync.isEnabled = false
                b.acDesyncRl.visibility = View.GONE
            }
        }
        when (selectedState) {
            DialStrategies.NEVER_SPLIT.mode -> {
                b.acRadioNeverSplit.isChecked = true
            }
            DialStrategies.SPLIT_AUTO.mode -> {
                b.acRadioSplitAuto.isChecked = true
            }
            DialStrategies.SPLIT_TCP.mode -> {
                b.acRadioSplitTcp.isChecked = true
            }
            DialStrategies.SPLIT_TCP_TLS.mode -> {
                b.acRadioSplitTls.isChecked = true
            }
            DialStrategies.DESYNC.mode -> {
                b.acRadioDesync.isChecked = true
            }
        }
    }

    private fun updateRetryStrategy(selectedState: Int) {
        when (selectedState) {
            RetryStrategies.RETRY_WITH_SPLIT.mode -> {
                b.acRadioRetryWithSplit.isChecked = true
            }
            RetryStrategies.RETRY_NEVER.mode -> {
                b.acRadioNeverRetry.isChecked = true
            }
            RetryStrategies.RETRY_AFTER_SPLIT.mode -> {
                b.acRadioRetryAfterSplit.isChecked = true
            }
        }
    }

    private fun setupClickListeners() {
        b.acRadioNeverSplit.setOnCheckedChangeListener { _: CompoundButton, isSelected: Boolean ->
            handleAcMode(isSelected, DialStrategies.NEVER_SPLIT.mode)
        }

        b.acNeverSplitRl.setOnClickListener {
            b.acRadioNeverSplit.isChecked = !b.acRadioNeverSplit.isChecked
        }

        b.acRadioSplitAuto.setOnCheckedChangeListener { _: CompoundButton, isSelected: Boolean ->
            handleAcMode(isSelected, DialStrategies.SPLIT_AUTO.mode)
        }

        b.acRadioSplitTcp.setOnCheckedChangeListener { _: CompoundButton, isSelected: Boolean ->
            handleAcMode(isSelected, DialStrategies.SPLIT_TCP.mode)
        }

        b.acRadioSplitTls.setOnCheckedChangeListener { _: CompoundButton, isSelected: Boolean ->
            handleAcMode(isSelected, DialStrategies.SPLIT_TCP_TLS.mode)
        }

        b.acRadioDesync.setOnCheckedChangeListener { _: CompoundButton, isSelected: Boolean ->
            handleAcMode(isSelected, DialStrategies.DESYNC.mode)
        }

        b.acSplitAutoRl.setOnClickListener {
            b.acRadioSplitAuto.isChecked = !b.acRadioSplitAuto.isChecked
        }

        b.acSplitTcpRl.setOnClickListener {
            b.acRadioSplitTcp.isChecked = !b.acRadioSplitTcp.isChecked
        }

        b.acSplitTlsRl.setOnClickListener {
            b.acRadioSplitTls.isChecked = !b.acRadioSplitTls.isChecked
        }

        b.acDesyncRl.setOnClickListener {
            b.acRadioDesync.isChecked = !b.acRadioDesync.isChecked
        }

        b.acRadioRetryWithSplit.setOnCheckedChangeListener { _: CompoundButton, isSelected: Boolean ->
            handleRetryMode(isSelected, RetryStrategies.RETRY_WITH_SPLIT.mode)
        }

        b.acRadioNeverRetry.setOnCheckedChangeListener { _: CompoundButton, isSelected: Boolean ->
            handleRetryMode(isSelected, RetryStrategies.RETRY_NEVER.mode)
        }

        b.acRadioRetryAfterSplit.setOnCheckedChangeListener { _: CompoundButton, isSelected: Boolean ->
            handleRetryMode(isSelected, RetryStrategies.RETRY_AFTER_SPLIT.mode)
        }

        b.acRetryWithSplitRl.setOnClickListener {
            b.acRadioRetryWithSplit.isChecked = !b.acRadioRetryWithSplit.isChecked
        }

        b.acRetryNeverRl.setOnClickListener {
            b.acRadioNeverRetry.isChecked = !b.acRadioNeverRetry.isChecked
        }

        b.acRetryAfterSplitRl.setOnClickListener {
            b.acRadioRetryAfterSplit.isChecked = !b.acRadioRetryAfterSplit.isChecked
        }
    }

    private fun handleAcMode(isSelected: Boolean, mode: Int) {
        if (isSelected) {
            persistentState.dialStrategy = mode
            disableRadioButtons(mode)
            if (mode == DialStrategies.NEVER_SPLIT.mode) {
                // disable retry radio buttons for never split
                handleRetryMode(true, RetryStrategies.RETRY_NEVER.mode, showToast = false)
            } else if (mode == DialStrategies.SPLIT_AUTO.mode) {
                // enable retry radio buttons for desync
                handleRetryMode(true, RetryStrategies.RETRY_WITH_SPLIT.mode, showToast = false)
            }
            logEvent("Anti-censorship dial strategy changed to $mode")
        } else {
            // no-op
        }
    }

    private fun handleRetryMode(isSelected: Boolean, mode: Int, showToast: Boolean = true) {
        var m = mode
        var shouldShowToast = false
        if (DialStrategies.NEVER_SPLIT.mode == persistentState.dialStrategy && mode != RetryStrategies.RETRY_NEVER.mode) {
            m = RetryStrategies.RETRY_NEVER.mode
            shouldShowToast = showToast && isSelected
        }

        if (isSelected) {
            persistentState.retryStrategy = m
            updateRetryStrategy(m)
            disableRetryRadioButtons(m)
            if (shouldShowToast) Utilities.showToastUiCentered(this, getString(R.string.ac_toast_retry_disabled), Toast.LENGTH_LONG)
            logEvent("Anti-censorship retry strategy changed to $m")
        } else {
            // no-op
        }
    }

    private fun disableRadioButtons(mode: Int) {
        when (mode) {
            DialStrategies.NEVER_SPLIT.mode -> {
                b.acRadioSplitAuto.isChecked = false
                b.acRadioSplitTcp.isChecked = false
                b.acRadioSplitTls.isChecked = false
                b.acRadioDesync.isChecked = false
            }
            DialStrategies.SPLIT_AUTO.mode -> {
                b.acRadioNeverSplit.isChecked = false
                b.acRadioSplitTcp.isChecked = false
                b.acRadioSplitTls.isChecked = false
                b.acRadioDesync.isChecked = false
            }
            DialStrategies.SPLIT_TCP.mode -> {
                b.acRadioNeverSplit.isChecked = false
                b.acRadioSplitAuto.isChecked = false
                b.acRadioSplitTls.isChecked = false
                b.acRadioDesync.isChecked = false
            }
            DialStrategies.SPLIT_TCP_TLS.mode -> {
                b.acRadioNeverSplit.isChecked = false
                b.acRadioSplitAuto.isChecked = false
                b.acRadioSplitTcp.isChecked = false
                b.acRadioDesync.isChecked = false
            }
            DialStrategies.DESYNC.mode -> {
                b.acRadioNeverSplit.isChecked = false
                b.acRadioSplitAuto.isChecked = false
                b.acRadioSplitTcp.isChecked = false
                b.acRadioSplitTls.isChecked = false
            }
        }
    }

    private fun disableRetryRadioButtons(mode: Int) {
        when (mode) {
            RetryStrategies.RETRY_WITH_SPLIT.mode -> {
                b.acRadioNeverRetry.isChecked = false
                b.acRadioRetryAfterSplit.isChecked = false
            }
            RetryStrategies.RETRY_NEVER.mode -> {
                b.acRadioRetryWithSplit.isChecked = false
                b.acRadioRetryAfterSplit.isChecked = false
            }
            RetryStrategies.RETRY_AFTER_SPLIT.mode -> {
                b.acRadioRetryWithSplit.isChecked = false
                b.acRadioNeverRetry.isChecked = false
            }
        }
    }

    private fun logEvent(details: String) {
        eventLogger.log(EventType.UI_TOGGLE, Severity.LOW, "Anti-censorship UI", EventSource.UI, false, details)
    }
}
