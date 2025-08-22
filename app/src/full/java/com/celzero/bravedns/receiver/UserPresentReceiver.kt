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
package com.celzero.bravedns.receiver

import Logger
import Logger.LOG_TAG_UI
import Logger.LOG_TAG_VPN
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * BroadcastReceiver to handle user presence and user unlocking events.
 * Informs the VpnController about these events and handles VPN auto-start for Private Space unlock.
 */
class UserPresentReceiver : BroadcastReceiver(), KoinComponent {

    val persistentState by inject<PersistentState>()
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_USER_PRESENT -> {
                VpnController.screenUnlock()
                Logger.v(LOG_TAG_UI, "user-present: action user present, inform vpn service")
            }
            Intent.ACTION_USER_UNLOCKED -> {
                handleUserUnlocked(context)
            }
            else -> {
                Logger.v(LOG_TAG_UI, "user-present: unknown action ${intent.action}, skipping")
            }
        }
    }

    private fun handleUserUnlocked(context: Context) {
        Logger.v(LOG_TAG_UI, "user-present: user unlocked (Private Space), inform vpn service")
        VpnController.screenUnlock()

        // Auto-start VPN if auto-start is enabled and VPN was previously active
        // This handles Private Space unlock scenario where VPN should auto-start
        if (!persistentState.prefAutoStartBootUp) {
            Logger.v(LOG_TAG_VPN, "user-unlocked: auto start is not enabled, skipping auto-start")
            return
        }

        // Check if VPN was activated and should be auto-started
        // Similar logic to BraveAutoStartReceiver but for user unlock events
        if (VpnController.state().activationRequested) {
            val prepareVpnIntent: Intent? =
                try {
                    Logger.i(LOG_TAG_VPN, "user-unlocked: attempting to auto-start VPN for Private Space")
                    VpnService.prepare(context)
                } catch (e: NullPointerException) {
                    Logger.w(LOG_TAG_VPN, "user-unlocked: device does not support system-wide VPN mode")
                    return
                }

            if (prepareVpnIntent == null) {
                Logger.i(LOG_TAG_VPN, "user-unlocked: VPN is already prepared, invoking start")
                VpnController.start(context)
            } else {
                Logger.i(LOG_TAG_VPN, "user-unlocked: VPN needs preparation, cannot auto-start")
            }
        } else {
            Logger.v(LOG_TAG_VPN, "user-unlocked: VPN activation not requested, skipping auto-start")
        }
    }
}
