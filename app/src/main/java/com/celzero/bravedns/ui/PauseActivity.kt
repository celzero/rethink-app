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

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.databinding.PauseActivityBinding
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.PauseTimer.PAUSE_VPN_EXTRA_MILLIS
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities.Companion.humanReadableTime
import kotlinx.coroutines.*
import org.koin.android.ext.android.inject
import java.util.concurrent.TimeUnit

class PauseActivity : AppCompatActivity(R.layout.pause_activity) {
    private val b by viewBinding(PauseActivityBinding::bind)
    private val persistentState by inject<PersistentState>()
    @Volatile var j: CompletableJob? = null

    enum class AutoOp {
        INCREASE, DECREASE, NONE
    }

    @Volatile var autoOp = AutoOp.NONE
    var lastStopActivityInvokeTime: Long = INIT_TIME_MS

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)
        initView()
        initClickListeners()
        observeAppState()
        observeTimer()
    }

    private fun initView() {
        FirewallManager.getApplistObserver().observe(this, {
            val blockedList = it.filter { a -> !a.isInternetAllowed }
            b.pacTimerDesc.text = getString(R.string.pause_desc, blockedList.size.toString())
        })
    }

    private fun observeAppState() {
        VpnController.connectionStatus.observe(this, {
            if (it != BraveVPNService.State.PAUSED) {
                stopPauseActivity()
            }
        })
    }

    private fun observeTimer() {
        VpnController.getBraveVpnService()?.getPauseCountDownObserver()?.observe(this, {
            b.pacTimer.text = humanReadableTime(it)
        })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initClickListeners() {
        b.pacPlusIv.setOnClickListener {
            increaseTimer()
        }

        b.pacStopIv.setOnClickListener {
            VpnController.getBraveVpnService()?.resumeApp()
            stopPauseActivity()
        }

        b.pacMinusIv.setOnClickListener {
            decreaseTimer()
        }

        b.pacPlusIv.setOnLongClickListener {
            autoOp = AutoOp.INCREASE
            handleLongPress()
            false
        }

        b.pacMinusIv.setOnLongClickListener {
            autoOp = AutoOp.DECREASE
            handleLongPress()
            false
        }

        b.pacPlusIv.setOnTouchListener { _, event ->
            if ((event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL)) {
                autoOp = AutoOp.NONE
            }
            false
        }

        b.pacMinusIv.setOnTouchListener { _, event ->
            if ((event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL)) {
                autoOp = AutoOp.NONE
            }
            false
        }
    }

    private fun decreaseTimer() {
        VpnController.getBraveVpnService()?.decreasePauseDuration(PAUSE_VPN_EXTRA_MILLIS)
    }

    private fun increaseTimer() {
        VpnController.getBraveVpnService()?.increasePauseDuration(PAUSE_VPN_EXTRA_MILLIS)
    }

    private fun stopPauseActivity() {
        // refrain from calling start activity multiple times
        if (SystemClock.elapsedRealtime() - lastStopActivityInvokeTime < TimeUnit.SECONDS.toMillis(
                1L)) {
            return
        }

        lastStopActivityInvokeTime = SystemClock.elapsedRealtime()
        val intent = Intent(this, HomeScreenActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    private fun handleLongPress() {
        if (j?.isActive == true) {
            return
        }

        j = Job()
        lifecycleScope.launch(j!! + Dispatchers.Main) {
            while (autoOp != AutoOp.NONE) {
                when (autoOp) {
                    AutoOp.INCREASE -> {
                        delay(200)
                        increaseTimer()
                    }
                    AutoOp.DECREASE -> {
                        delay(200)
                        decreaseTimer()
                    }
                    else -> {
                        // no-op
                    }
                }
            }
            j?.cancel()
        }
    }

}
