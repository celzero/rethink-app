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
package com.celzero.bravedns.service

import Logger
import Logger.LOG_TAG_VPN
import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.Observer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/**
 * Manages app pause/resume state and timers.
 * Extracted from BraveVPNService for better separation of concerns.
 * Wraps the existing PauseTimer for StateFlow-based observation.
 */
class PauseStateManager(
    private val context: Context,
    private val persistentState: PersistentState,
    private val scope: CoroutineScope
) : SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        private const val TAG = "PauseState"
    }

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _pauseCountdown = MutableStateFlow(0L)
    val pauseCountdown: StateFlow<Long> = _pauseCountdown.asStateFlow()

    private var keyguardManager: KeyguardManager? = null

    init {
        // Observe the existing PauseTimer's LiveData and convert to StateFlow
        scope.launch(Dispatchers.Main) {
            PauseTimer.getPauseCountDownObserver().toFlow().collect { remaining ->
                _pauseCountdown.value = remaining
                _isPaused.value = remaining > 0
            }
        }
    }

    fun startPause() {
        PauseTimer.start(PauseTimer.DEFAULT_PAUSE_TIME_MS)
        _isPaused.value = true
        Logger.i(LOG_TAG_VPN, "$TAG App paused")
    }

    fun stopPause() {
        PauseTimer.stop()
        _isPaused.value = false
        Logger.i(LOG_TAG_VPN, "$TAG App resumed")
    }

    fun increasePauseDuration(durationMs: Long) {
        PauseTimer.addDuration(durationMs)
    }

    fun decreasePauseDuration(durationMs: Long) {
        PauseTimer.subtractDuration(durationMs)
    }

    fun isDeviceLocked(): Boolean {
        if (!persistentState.getBlockWhenDeviceLocked()) return false

        if (keyguardManager == null) {
            keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        }
        return keyguardManager?.isKeyguardLocked == true
    }

    override fun onSharedPreferenceChanged(preferences: SharedPreferences?, key: String?) {
        // Handle preference changes if needed
    }
}

// Extension to convert LiveData to Flow
private fun <T> androidx.lifecycle.LiveData<T>.toFlow() = callbackFlow {
    val observer = Observer<T> { value ->
        trySend(value)
    }
    observeForever(observer)
    awaitClose { removeObserver(observer) }
}
