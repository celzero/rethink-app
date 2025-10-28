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

import android.content.Context
import Logger.LOG_FIREBASE
import org.koin.core.component.KoinComponent

/**
 * Firebase Error Reporting Manager for F-Droid variant
 * This is a stub implementation since Firebase is not available in F-Droid builds
 */
object FirebaseErrorReporting : KoinComponent {

    const val TOKEN_REGENERATION_PERIOD_DAYS: Long = 45
    const val TOKEN_LENGTH = 16
    /**
     * Initialize Firebase Crashlytics - No-op for F-Droid variant
     */
    fun initialize() {
        Logger.i(LOG_FIREBASE, "crashlytics not available in F-Droid variant")
    }

    /**
     * Enable or disable Firebase Crashlytics data collection - No-op for F-Droid variant
     */
    fun setEnabled(enabled: Boolean) {
        Logger.i(LOG_FIREBASE, "crashlytics not available in F-Droid variant")
    }

    /**
     * Check if Firebase Crashlytics is available - Always false for F-Droid variant
     */
    fun isAvailable(): Boolean {
        return false
    }

    /**
     * Log a custom message - No-op for F-Droid variant
     */
    fun log(message: String) {
        // No-op: Firebase not available in F-Droid variant
    }

    /**
     * Record a non-fatal exception - No-op for F-Droid variant
     */
    fun recordException(throwable: Throwable) {
        // No-op: Firebase not available in F-Droid variant
    }

    /**
     * Set user ID - No-op for F-Droid variant
     */
    fun setUserId(userId: String) {
        // No-op: Firebase not available in F-Droid variant
    }

    /**
     * Set custom key-value pairs - No-op for F-Droid variant
     */
    fun setCustomKey(key: String, value: String) {
        // No-op: Firebase not available in F-Droid variant
    }
}
