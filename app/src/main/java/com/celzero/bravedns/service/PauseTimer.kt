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

import Logger
import Logger.LOG_TAG_UI
import Logger.LOG_TAG_VPN
import androidx.lifecycle.MutableLiveData
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

// should this be replaced with developer.android.com/reference/android/os/CountDownTimer?
object PauseTimer {

    // default duration for pause state: 15mins
    val DEFAULT_PAUSE_TIME_MS = TimeUnit.MINUTES.toMillis(15)

    private var countdownMs: AtomicLong = AtomicLong(DEFAULT_PAUSE_TIME_MS)
    private var pauseCountDownTimer: MutableLiveData<Long> = MutableLiveData()
    private const val COUNT_DOWN_INTERVAL = 1000L

    // increment/decrement value to pause vpn
    val PAUSE_VPN_EXTRA_MILLIS = TimeUnit.MINUTES.toMillis(1)

    fun start(durationMs: Long) {
        Logger.d(LOG_TAG_UI, "timer started, duration: $durationMs")
        io {
            try {
                setCountdown(durationMs)
                while (countdownMs.get() > 0L) {
                    delay(COUNT_DOWN_INTERVAL)
                    addCountdown(-COUNT_DOWN_INTERVAL)
                }
            } finally {
                Logger.d(LOG_TAG_VPN, "pause timer complete")
                VpnController.resumeApp()
                setCountdown(INIT_TIME_MS)
            }
        }
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

    fun addDuration(duration: Long) {
        addCountdown(duration)
    }

    fun subtractDuration(duration: Long) {
        addCountdown(-duration)
    }

    fun getPauseCountDownObserver(): MutableLiveData<Long> {
        return pauseCountDownTimer
    }

    private fun io(f: suspend () -> Unit) = CoroutineScope(Dispatchers.IO).launch { f() }
}
