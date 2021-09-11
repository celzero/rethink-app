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
package com.celzero.bravedns.service

import android.util.Log
import androidx.lifecycle.MutableLiveData
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_UI
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_VPN
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

object PauseTimer {

    private var countdownMs: AtomicLong = AtomicLong(Constants.DEFAULT_PAUSE_TIME_MS)
    private var pauseCountDownTimer: MutableLiveData<Long> = MutableLiveData()
    private const val LOCKDOWN_STATUS_CHECK_TIME_IN_SEC = 30L
    private const val COUNT_DOWN_INTERVAL = 1000L

    fun start(durationMs: Long) {
        if (DEBUG) Log.d(LOG_TAG_UI, "timer started, duration: $durationMs")
        CoroutineScope(Dispatchers.Main).launch {
            setCountdown(durationMs)
            while (countdownMs.get() > 0L) {
                delay(COUNT_DOWN_INTERVAL)
                val c = addCountdown(-COUNT_DOWN_INTERVAL)

                // Check vpn lockdown state every 30 secs
                if (TimeUnit.MILLISECONDS.toSeconds(c) % LOCKDOWN_STATUS_CHECK_TIME_IN_SEC == 0L) {
                    resumeAppIfVpnLockdown()
                }
            }

            if (DEBUG) Log.d(LOG_TAG_VPN, "pause timer complete")
            VpnController.getBraveVpnService()?.resumeApp()
            setCountdown(INIT_TIME_MS)
        }
    }

    private fun resumeAppIfVpnLockdown() {
        // edge-case: there is no call-back for the lockdown mode so using this check, when the
        // lockdown mode is detected, set the app state as ACTIVE regardless of the current state
        if (!Utilities.isVpnLockdownEnabled(VpnController.getBraveVpnService())) return

        VpnController.getBraveVpnService()?.resumeApp()

    }

    private fun setCountdown(c: Long): Long {
        countdownMs.set(c)
        pauseCountDownTimer.postValue(c)
        return c
    }

    private fun addCountdown(c: Long): Long {
        val r = countdownMs.getAndAdd(c)
        pauseCountDownTimer.postValue(r)
        return r
    }

    fun stop() {
        setCountdown(INIT_TIME_MS)
    }

    fun increment(duration: Long) {
        addCountdown(duration)
    }

    fun decrement(duration: Long) {
        addCountdown(-duration)
    }

    fun getPauseCountDownObserver(): MutableLiveData<Long> {
        return pauseCountDownTimer
    }
}
