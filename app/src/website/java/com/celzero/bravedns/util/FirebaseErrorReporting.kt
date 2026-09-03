/*
 * Copyright 2025 RethinkDNS and its authors
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
package com.celzero.bravedns.util

import com.celzero.bravedns.util.Logger.LOG_FIREBASE
import org.koin.core.component.KoinComponent

/**
 * Firebase Error Reporting Manager for website variant
 * This is a stub implementation since Firebase is only available in play builds
 */
object FirebaseErrorReporting : KoinComponent {

    const val TOKEN_REGENERATION_PERIOD_DAYS: Long = 45
    const val TOKEN_LENGTH = 16
    /**
     * Initialize Firebase Crashlytics - no-op for website variant
     */
    fun initialize() {
        Logger.i(LOG_FIREBASE, "crashlytics not available in website variant")
    }

    /**
     * Enable or disable Firebase Crashlytics data collection - no-op for website variant
     */
    fun setEnabled(enabled: Boolean) {
        Logger.i(LOG_FIREBASE, "crashlytics not available in website variant")
    }

    /**
     * Check if Firebase Crashlytics is available - Always false for website variant
     */
    fun isAvailable(): Boolean {
        return false
    }

    /**
     * Log a custom message - no-op for website variant
     */
    fun log(msg: String) {
        // no-op: firebase not available in website variant
    }

    /**
     * Record a non-fatal exception - no-op for website variant
     */
    fun recordException(throwable: Throwable) {
        // no-op: firebase not available in website variant
    }

    /**
     * Set user ID - no-op for website variant
     */
    fun setUserId(uid: String) {
        // no-op: firebase not available in website variant
    }

    /**
     * Set custom key-value pairs - no-op for website variant
     */
    fun setCustomKey(key: String, value: String) {
        // no-op: firebase not available in website variant
    }
}
