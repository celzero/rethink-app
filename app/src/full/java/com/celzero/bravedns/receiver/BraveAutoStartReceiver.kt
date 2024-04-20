/*
 * Copyright 2020 RethinkDNS and its authors
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
import Logger.LOG_TAG_VPN
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class BraveAutoStartReceiver : BroadcastReceiver(), KoinComponent {

    val persistentState by inject<PersistentState>()

    override fun onReceive(context: Context, intent: Intent) {

        if (!persistentState.prefAutoStartBootUp) {
            Logger.w(
                LOG_TAG_VPN,
                "Auto start is not enabled: ${persistentState.prefAutoStartBootUp}"
            )
            return
        }

        if (
            Intent.ACTION_REBOOT != intent.action &&
                Intent.ACTION_BOOT_COMPLETED != intent.action &&
                Intent.ACTION_LOCKED_BOOT_COMPLETED != intent.action
        ) {
            Logger.w(LOG_TAG_VPN, "unhandled broadcast ${intent.action}")
            return
        }

        // if vpn was running before reboot, attempt an auto-start
        // but: if always-on is enabled, then back-off, since android
        // is expected to kick-start the vpn up on its own
        if (VpnController.state().activationRequested && !VpnController.isAlwaysOn(context)) {
            val prepareVpnIntent: Intent? =
                try {
                    Logger.i(LOG_TAG_VPN, "Attempting to auto-start VPN")
                    VpnService.prepare(context)
                } catch (e: NullPointerException) {
                    Logger.w(LOG_TAG_VPN, "Device does not support system-wide VPN mode")
                    return
                }

            if (prepareVpnIntent == null) {
                Logger.i(LOG_TAG_VPN, "VPN is already prepared, invoking start")
                VpnController.start(context)
                return
            }
        }
    }
}
