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
import com.celzero.bravedns.service.VpnController
import org.koin.core.component.KoinComponent

class VPNControlReceiver : BroadcastReceiver(), KoinComponent {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_START) {
            val prepareVpnIntent: Intent? =
                try {
                    Logger.i(LOG_TAG_VPN, "Attempting to prepare VPN before starting")
                    VpnService.prepare(context)
                } catch (e: NullPointerException) {
                    // This shouldn't happen normally as Broadcast Intent sender apps like Tasker won't come up as early as Always-on VPNs
                    // Context can be null in case of auto-restart VPNs: https://stackoverflow.com/questions/73147633/getting-null-in-context-while-auto-restart-with-broadcast-receiver-in-android-ap
                    Logger.w(LOG_TAG_VPN, "Device does not support system-wide VPN mode")
                    return
                }
            if (prepareVpnIntent == null) {
                Logger.i(LOG_TAG_VPN, "VPN is prepared, invoking start")
                VpnController.start(context)
                return
            }
        }
        if (intent.action == ACTION_STOP) {
            Logger.i(LOG_TAG_VPN, "VPN stopping")
            VpnController.stop(context)
            return
        }
    }

    companion object {
        private const val ACTION_START = "com.celzero.bravedns.intent.action.VPN_START"
        private const val ACTION_STOP = "com.celzero.bravedns.intent.action.VPN_STOP"
    }
}
