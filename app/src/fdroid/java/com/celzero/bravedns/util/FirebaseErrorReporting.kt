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
 * Firebase Error Reporting Manager for fdroid variant
 * This is a stub implementation since Firebase is not available in fdroid builds
 */
object FirebaseErrorReporting : KoinComponent {

    const val TOKEN_REGENERATION_PERIOD_DAYS: Long = 45
    const val TOKEN_LENGTH = 16
    /**
     * Initialize Firebase Crashlytics - no-op for fdroid variant
     */
    fun initialize() {
        Logger.i(LOG_FIREBASE, "crashlytics not available in fdroid variant")
    }

    /**
     * Enable or disable Firebase Crashlytics data collection - no-op for fdroid variant
     */
    fun setEnabled(_: Boolean) {
        Logger.i(LOG_FIREBASE, "crashlytics not available in fdroid variant")
    }

    /**
     * Check if Firebase Crashlytics is available - Always false for fdroid variant
     */
    fun isAvailable(): Boolean {
        return false
    }

    /**
     * Log a custom message - no-op for fdroid variant
     */
    fun log(_: String) {
        // no-op: firebase not available in fdroid variant
    }

    /**
     * Record a non-fatal exception - no-op for fdroid variant
     */
    fun recordException(_: Throwable) {
        // no-op: firebase not available in fdroid variant
    }

    /**
     * Set user ID - no-op for fdroid variant
     */
    fun setUserId(_: String) {
        // no-op: firebase not available in fdroid variant
    }

    /**
     * Set custom key-value pairs - no-op for fdroid variant
     */
    fun setCustomKey(_: String, _: String) {
        // no-op: firebase not available in fdroid variant
    }
}
