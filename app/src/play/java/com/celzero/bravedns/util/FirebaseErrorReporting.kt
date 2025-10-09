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
import com.celzero.bravedns.service.PersistentState
import com.google.firebase.crashlytics.FirebaseCrashlytics
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Firebase Error Reporting Manager for Play Store variant
 * Handles automatic error reporting using Firebase Crashlytics
 */
object FirebaseErrorReporting : KoinComponent {

    private val persistentState by inject<PersistentState>()

    /**
     * Initialize Firebase Crashlytics if available and enabled
     */
    fun initialize() {
        try {
            val crashlytics = FirebaseCrashlytics.getInstance()
            crashlytics.isCrashlyticsCollectionEnabled = persistentState.firebaseErrorReportingEnabled
            Logger.i(LOG_FIREBASE, "crashlytics initialized, enabled: ${persistentState.firebaseErrorReportingEnabled}")
        } catch (e: Exception) {
            Logger.w(LOG_FIREBASE, "crashlytics not available: ${e.message}")
        }
    }

    /**
     * Enable or disable Firebase Crashlytics data collection
     */
    fun setEnabled(enabled: Boolean) {
        try {
            val crashlytics = FirebaseCrashlytics.getInstance()
            crashlytics.isCrashlyticsCollectionEnabled = enabled
            if (enabled) {
                crashlytics.sendUnsentReports()
            } else {
                crashlytics.deleteUnsentReports()
            }
            Logger.i(LOG_FIREBASE, "crashlytics enabled state set to: $enabled")
        } catch (e: Exception) {
            Logger.w(LOG_FIREBASE, "Failed to set crashlytics enabled state: ${e.message}")
        }
    }

    /**
     * Check if Firebase Crashlytics is available in current build variant
     */
    fun isAvailable(): Boolean {
        return try {
            FirebaseCrashlytics.getInstance() != null
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Log a custom message to Firebase Crashlytics
     */
    fun log(message: String) {
        if (!isAvailable() || !persistentState.firebaseErrorReportingEnabled) return

        try {
            val crashlytics = FirebaseCrashlytics.getInstance()
            crashlytics.log(message)
        } catch (e: Exception) {
            Logger.w(LOG_FIREBASE, "Failed to log message to crashlytics: ${e.message}")
        }
    }

    /**
     * Record a non-fatal exception to Firebase Crashlytics
     */
    fun recordException(throwable: Throwable) {
        if (!isAvailable() || !persistentState.firebaseErrorReportingEnabled) return

        try {
            val crashlytics = FirebaseCrashlytics.getInstance()
            crashlytics.recordException(throwable)
        } catch (e: Exception) {
            Logger.w(LOG_FIREBASE, "Failed to record exception to crashlytics: ${e.message}")
        }
    }

    /**
     * Set user ID for Firebase Crashlytics
     */
    fun setUserId(userId: String) {
        if (!isAvailable() || !persistentState.firebaseErrorReportingEnabled) return

        try {
            val crashlytics = FirebaseCrashlytics.getInstance()
            crashlytics.setUserId(userId)
        } catch (e: Exception) {
            Logger.w(LOG_FIREBASE, "Failed to set user ID in crashlytics: ${e.message}")
        }
    }

    /**
     * Set custom key-value pairs for Firebase Crashlytics
     */
    fun setCustomKey(key: String, value: String) {
        if (!isAvailable() || !persistentState.firebaseErrorReportingEnabled) return

        try {
            val crashlytics = FirebaseCrashlytics.getInstance()
            crashlytics.setCustomKey(key, value)
        } catch (e: Exception) {
            Logger.w(LOG_FIREBASE, "Failed to set custom key in crashlytics: ${e.message}")
        }
    }
}
