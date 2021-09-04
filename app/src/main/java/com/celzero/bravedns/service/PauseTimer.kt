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

import android.os.CountDownTimer
import android.util.Log
import androidx.lifecycle.MutableLiveData
import com.celzero.bravedns.data.AppMode
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_UI
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_VPN

class PauseTimer(val appMode: AppMode) {

    private var timer: CountDownTimer? = null
    private var countDownMs: Long = Constants.DEFAULT_PAUSE_TIME_MS
    private var pauseCountDownTimer: MutableLiveData<Long> = MutableLiveData()

    fun startCountDownTimer(timeInMills: Long) {
        countDownMs = timeInMills
        if (DEBUG) Log.d(LOG_TAG_UI, "Timer started with: $timeInMills")
        timer?.cancel()
        timer = object : CountDownTimer(countDownMs, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                countDownMs = millisUntilFinished
                pauseCountDownTimer.postValue(millisUntilFinished)
            }

            override fun onFinish() {
                Log.d(LOG_TAG_VPN, "Timer count down timer onFinish.")
                VpnController.getBraveVpnService()?.resumeApp()
                pauseCountDownTimer.postValue(0)
            }
        }.start()
    }

    fun stopCountDownTimer() {
        timer?.cancel()
        countDownMs = Constants.DEFAULT_PAUSE_TIME_MS
        pauseCountDownTimer.postValue(0)
    }

    fun incrementTimer(timeInMills: Long) {
        countDownMs = countDownMs.plus(timeInMills)
        startCountDownTimer(countDownMs)
    }

    fun decrementTimer(timeInMills: Long) {
        countDownMs = countDownMs.minus(timeInMills)
        startCountDownTimer(countDownMs)
    }

    fun getPauseCountdownObserver(): MutableLiveData<Long> {
        return pauseCountDownTimer
    }
}
