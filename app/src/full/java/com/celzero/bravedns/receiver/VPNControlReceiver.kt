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


import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.celzero.bravedns.service.VpnController
import org.koin.core.component.KoinComponent

class VPNControlReceiver : BroadcastReceiver(), KoinComponent {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_START) {
            VpnController.start(context)
        }
        if (intent.action == ACTION_STOP) {
            VpnController.stop(context)
        }
    }

    companion object {
        private const val ACTION_START = "com.celzero.bravedns.intent.action.VPN_START"
        private const val ACTION_STOP = "com.celzero.bravedns.intent.action.VPN_STOP"
    }
}
