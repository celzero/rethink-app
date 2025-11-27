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
import Logger.LOG_TAG_UI
import com.celzero.bravedns.service.PersistentState
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

object NewSettingsManager: KoinComponent {

    private val persistentState: PersistentState by inject<PersistentState>()

    private const val EXPIRY_DAYS = 7

    private val newSettingsList = emptyList<String>()

    init {
        handleNewSettings()
    }

    fun shouldShowBadge(key: String): Boolean {
        val newSettings = persistentState.newSettings.split(",").filter { it.isNotEmpty() }.toSet()
        val seenSettings =
            persistentState.newSettingsSeen.split(",").filter { it.isNotEmpty() }.toSet()
        val contains = newSettings.contains(key)
        val seen = seenSettings.contains(key)
        val show = contains && !seen
        return show
    }

    fun markSettingSeen(key: String) {
        val seenSettings =
            persistentState.newSettingsSeen.split(",").filter { it.isNotEmpty() }.toMutableSet()
        if (seenSettings.contains(key)) {
            return
        }
        seenSettings.add(key)
        saveSeenSettings(seenSettings)
    }

    fun initializeNewSettings() {
        persistentState.newSettings = newSettingsList.joinToString(",")
        persistentState.newSettingsSeen = ""
    }

    private fun isExpired(): Boolean {
        val appUpdatedTs = persistentState.appUpdateTimeTs
        val now = System.currentTimeMillis()
        val diff = now - appUpdatedTs
        if (appUpdatedTs == 0L) {
            // if app update time is not set, consider it as expired
            return true
        }
        return diff >= EXPIRY_DAYS * 24 * 60 * 60 * 1000L
    }

    private fun saveSeenSettings(set: Set<String>) {
        persistentState.newSettingsSeen = set.joinToString(",")
        // remove the setting from new settings if it exists
        val newSettings =
            persistentState.newSettings.split(",").filter { it.isNotEmpty() }.toMutableSet()
        newSettings.removeAll(set)
        persistentState.newSettings = newSettings.joinToString(",")
    }

    fun clearAll() {
        persistentState.newSettings = ""
        persistentState.newSettingsSeen = ""
        Logger.v(LOG_TAG_UI, "NewSettingsManager: cleared all new settings")
    }

    fun handleNewSettings() {
        if (isExpired()) {
            // reset all new settings
            clearAll()
            Logger.v(LOG_TAG_UI, "NewSettingsManager: new settings expired, resetting all settings")
        }
    }
}
