/*
 * Copyright 2021 RethinkDNS and its authors
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
package com.celzero.bravedns.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.data.AppMode
import com.celzero.bravedns.databinding.PauseActivityBinding
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Constants.Companion.EXTRA_MILLIS
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.humanReadableTime
import org.koin.android.ext.android.inject

class PauseActivity : AppCompatActivity(R.layout.pause_activity) {
    private val b by viewBinding(PauseActivityBinding::bind)
    private val persistentState by inject<PersistentState>()

    var autoIncrement = false
    var autoDecrement = false
    val repeatUpdateHandler: Handler = Handler()

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Utilities.getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)
    }

    private fun initView() {
        FirewallManager.getApplistObserver().observe(this, {
            val blockedList = it.filter { a -> !a.isInternetAllowed }
            b.pacTimerDesc.text = getString(R.string.pause_desc, blockedList.size.toString())
        })
    }

    override fun onResume() {
        super.onResume()
        initView()
        initClickListeners()
        checkAppState()
    }

    private fun checkAppState() {
        VpnController.connectionStatus.observe(this, {
            if (it != BraveVPNService.State.PAUSED) {
                stopPauseActivity()
            } else {
                observeTimer()
            }
        })

    }

    private fun observeTimer() {
        VpnController.getBraveVpnService()?.getPauseCountdownObserver()?.observe(this, {
            b.pacTimer.text = humanReadableTime(it)
        })
    }

    private fun initClickListeners() {
        b.pacPlusIv.setOnClickListener {
            incrementTimer()
        }

        b.pacStopIv.setOnClickListener {
            VpnController.getBraveVpnService()?.resumeApp()
            stopPauseActivity()
        }

        b.pacMinusIv.setOnClickListener {
            decrementTimer()
        }

        b.pacPlusIv.setOnLongClickListener {
            autoIncrement = true
            autoDecrement = false
            repeatUpdateHandler.post(RptUpdater())
            false
        }

        b.pacMinusIv.setOnLongClickListener {
            autoDecrement = true
            autoIncrement = false
            repeatUpdateHandler.post(RptUpdater())
            false
        }

        b.pacPlusIv.setOnTouchListener { _, event ->
            if ((event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) && autoIncrement) {
                autoIncrement = false
            }
            false
        }

        b.pacMinusIv.setOnTouchListener { _, event ->
            if ((event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) && autoDecrement) {
                autoDecrement = false
            }
            false
        }
    }

    private fun decrementTimer() {
        VpnController.getBraveVpnService()?.decrementTimer(EXTRA_MILLIS)
    }

    private fun incrementTimer() {
        VpnController.getBraveVpnService()?.incrementTimer(EXTRA_MILLIS)
    }

    private fun stopPauseActivity() {
        val intent = Intent(this, HomeScreenActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    inner class RptUpdater : Runnable {
        override fun run() {
            if (autoIncrement) {
                incrementTimer()
                repeatUpdateHandler.postDelayed(RptUpdater(), 200)
            } else if (autoDecrement) {
                decrementTimer()
                repeatUpdateHandler.postDelayed(RptUpdater(), 200)
            } else {
                // no-op
            }
        }
    }

    override fun onBackPressed() {
        // Go to android home screen instead of going to previous activity
        val startMain = Intent(Intent.ACTION_MAIN)
        startMain.addCategory(Intent.CATEGORY_HOME)
        startMain.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(startMain)
    }
}
