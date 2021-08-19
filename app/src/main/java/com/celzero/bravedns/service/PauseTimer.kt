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
    private var countdownMs: Long = Constants.DEFAULT_PAUSE_TIME_MS
    private var pauseCountdownObserver: MutableLiveData<Long> = MutableLiveData()

    fun startCountDownTimer(timeInMills: Long) {
        countdownMs = timeInMills
        if (DEBUG) Log.d(LOG_TAG_UI,
                                 "Timer started with: $timeInMills")
        timer?.cancel()
        timer = object : CountDownTimer(countdownMs, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                countdownMs = millisUntilFinished
                pauseCountdownObserver.postValue(millisUntilFinished)
            }

            override fun onFinish() {
                Log.d(LOG_TAG_VPN, "Timer count down timer onFinish.")
                appMode.setAppState(AppMode.AppState.ACTIVE)
                pauseCountdownObserver.postValue(0)
            }
        }.start()
    }

    fun stopCountDownTimer() {
        timer?.cancel()
        countdownMs = Constants.DEFAULT_PAUSE_TIME_MS
        pauseCountdownObserver.postValue(0)
    }

    fun incrementTimer(timeInMills: Long) {
        if (DEBUG) Log.d(LOG_TAG_UI,
                         "Increment pause timer, remaining time: $countdownMs, incremented minutes: $timeInMills")
        countdownMs = countdownMs.plus(timeInMills)
        startCountDownTimer(countdownMs)
    }

    fun decrementTimer(timeInMills: Long) {
        if (DEBUG) Log.d(LOG_TAG_UI,
                         "Decrement pause timer, remaining time: $countdownMs, decremented minutes: $timeInMills")
        countdownMs = countdownMs.minus(timeInMills)
        startCountDownTimer(countdownMs)
    }

    fun getPauseCountdownObserver(): MutableLiveData<Long> {
        return pauseCountdownObserver
    }
}