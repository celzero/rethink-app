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

import Logger
import Logger.LOG_FIREBASE
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Utilities.getRandomString
import com.google.firebase.crashlytics.FirebaseCrashlytics
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Firebase Error Reporting Manager for Play Store variant
 * Handles automatic error reporting using Firebase Crashlytics
 */
object FirebaseErrorReporting : KoinComponent {

    private val persistentState by inject<PersistentState>()
    const val TOKEN_REGENERATION_PERIOD_DAYS: Long = 45
    const val TOKEN_LENGTH = 16

    /**
     * Initialize Firebase Crashlytics if available and enabled
     */
    fun initialize() {
        if (!isAvailable()) {
            Logger.w(LOG_FIREBASE, "crashlytics not available in this build variant")
            return
        }
        if (!persistentState.firebaseErrorReportingEnabled) {
            Logger.i(LOG_FIREBASE, "crashlytics disabled in settings")
            return
        }
        try {
            val crashlytics = FirebaseCrashlytics.getInstance()
            val token = persistentState.firebaseUserToken
            if (token.isEmpty()) {
                val newToken = getRandomString(TOKEN_LENGTH)
                persistentState.firebaseUserToken = newToken
                persistentState.firebaseUserTokenTimestamp = System.currentTimeMillis()
                crashlytics.setUserId(newToken)
                Logger.i(LOG_FIREBASE, "generated new firebase token: $newToken")
            } else {
                crashlytics.setUserId(token)
                Logger.i(LOG_FIREBASE, "existing firebase token found: $token")
            }
            setEnabled(persistentState.firebaseErrorReportingEnabled)
            Logger.i(LOG_FIREBASE, "crashlytics initialized, enabled? ${crashlytics.isCrashlyticsCollectionEnabled}")
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
            Logger.w(LOG_FIREBASE, "err setting crashlytics state: ${e.message}")
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
            Logger.w(LOG_FIREBASE, "err; log message to crashlytics: ${e.message}")
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
            Logger.w(LOG_FIREBASE, "err; rec-ex to crashlytics: ${e.message}")
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
            Logger.w(LOG_FIREBASE, "err; set user-id crashlytics: ${e.message}")
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
            Logger.w(LOG_FIREBASE, "err; set custom key: ${e.message}")
        }
    }
}
