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
import Logger.LOG_TAG_UI
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.CompoundButton
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.ActivityAdvancedSettingBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities.isAtleastS
import org.koin.android.ext.android.inject

class AdvancedSettingActivity : AppCompatActivity(R.layout.activity_advanced_setting) {
    private val persistentState by inject<PersistentState>()
    private val b by viewBinding(ActivityAdvancedSettingBinding::bind)

    // Handler to update the dialer timeout value when the seekbar is moved
    private val handler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null

    companion object {
        private const val TAG = "AdvSetAct"
        private const val ONE_SEC = 1000L
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)
        initView()
        setupClickListeners()
    }

    private fun initView() {
        b.dvWgListenPortSwitch.isChecked = !persistentState.randomizeListenPort
        // Auto start app after reboot
        b.settingsActivityAutoStartSwitch.isChecked = persistentState.prefAutoStartBootUp
        // check if the device is running on Android 12 or above for EIMF
        if (isAtleastS()) {
            // endpoint independent mapping (eim) / endpoint independent filtering (eif)
            b.dvEimfRl.visibility = View.VISIBLE
            b.dvEimfSwitch.isChecked = persistentState.endpointIndependence
        } else {
            b.dvEimfRl.visibility = View.GONE
        }

        b.dvTcpKeepAliveSwitch.isChecked = persistentState.tcpKeepAlive
        b.settingsActivitySlowdownSwitch.isChecked = persistentState.slowdownMode

        b.dvExperimentalSwitch.isChecked = persistentState.nwEngExperimentalFeatures

        updateDialerTimeOutUi()
    }

    private fun updateDialerTimeOutUi() {
        val valueMin = persistentState.dialTimeoutSec / 60
        Logger.d(LOG_TAG_UI, "$TAG; dialer timeout value: $valueMin, persistentState: ${persistentState.dialTimeoutSec}")
        val displayText = if (valueMin == 0) {
            "Selected Value: (disabled)"
        } else {
            "Selected Value: $valueMin mins"
        }
        b.dvTimeoutDesc.text = displayText
        Logger.d(LOG_TAG_UI, "$TAG; dialer timeout value: $valueMin, progress: ${b.dvTimeoutSeekbar.progress}")
        if (valueMin == b.dvTimeoutSeekbar.progress) return
        b.dvTimeoutSeekbar.progress = valueMin
    }

    private fun updateDialerTimeOut(valueMin: Int) {
        persistentState.dialTimeoutSec = valueMin * 60
        updateDialerTimeOutUi()
    }

    private fun setupClickListeners() {

        b.dvWgListenPortSwitch.setOnCheckedChangeListener { _, isChecked ->
            persistentState.randomizeListenPort = !isChecked
        }

        b.dvWgListenPortRl.setOnClickListener {
            b.dvWgListenPortSwitch.isChecked = !b.dvWgListenPortSwitch.isChecked
        }

        b.dvEimfSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!isAtleastS()) {
                return@setOnCheckedChangeListener
            }

            persistentState.endpointIndependence = isChecked
        }

        b.dvEimfRl.setOnClickListener { b.dvEimfSwitch.isChecked = !b.dvEimfSwitch.isChecked }

        b.settingsAntiCensorshipRl.setOnClickListener {
            val intent = Intent(this, AntiCensorshipActivity::class.java)
            startActivity(intent)
        }

        b.settingsConsoleLogRl.setOnClickListener { openConsoleLogActivity() }

        b.settingsActivityAutoStartRl.setOnClickListener {
            b.settingsActivityAutoStartSwitch.isChecked =
                !b.settingsActivityAutoStartSwitch.isChecked
        }

        b.settingsActivityAutoStartSwitch.setOnCheckedChangeListener { _: CompoundButton, b: Boolean
            ->
            persistentState.prefAutoStartBootUp = b
        }

        b.dvTcpKeepAliveSwitch.setOnCheckedChangeListener { _, isChecked ->
            persistentState.tcpKeepAlive = isChecked
        }

        b.dvTcpKeepAliveRl.setOnClickListener { b.dvTcpKeepAliveSwitch.isChecked = !b.dvTcpKeepAliveSwitch.isChecked }

        b.settingsActivitySlowdownRl.setOnClickListener {
            b.settingsActivitySlowdownSwitch.isChecked = !b.settingsActivitySlowdownSwitch.isChecked
        }

        b.settingsActivitySlowdownSwitch.setOnCheckedChangeListener { _, isChecked ->
            persistentState.slowdownMode = isChecked
        }

        b.dvExperimentalSwitch.setOnCheckedChangeListener { _, isChecked ->
            persistentState.nwEngExperimentalFeatures = isChecked
        }

        b.dvTimeoutSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                handler.removeCallbacks(updateRunnable ?: Runnable {})

                updateRunnable = Runnable {
                    updateDialerTimeOut(progress)
                }

                handler.postDelayed(updateRunnable!!, ONE_SEC)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                handler.removeCallbacks(updateRunnable ?: Runnable {})
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                updateRunnable?.let {
                    handler.removeCallbacks(it)
                    handler.post(it)
                }
            }
        })
    }

    private fun openConsoleLogActivity() {
        try {
            val intent = Intent(this, ConsoleLogActivity::class.java)
            startActivity(intent)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "$TAG; err opening console log activity ${e.message}", e)
        }
    }
}
