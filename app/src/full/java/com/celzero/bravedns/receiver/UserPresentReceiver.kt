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

import Logger.LOG_TAG_UI
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.celzero.bravedns.service.VpnController

/**
 * BroadcastReceiver to handle screen state changes (on/off) and user presence.
 * Informs the VpnController about these events.
 */
class UserPresentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_SCREEN_OFF == intent.action) {
            VpnController.screenLock()
            Logger.v(LOG_TAG_UI, "user-present: action screen off, inform vpn service")
        } else if (Intent.ACTION_SCREEN_ON == intent.action || Intent.ACTION_USER_PRESENT == intent.action) {
            VpnController.screenUnlock()
            Logger.v(LOG_TAG_UI, "user-present: action screen on, inform vpn service")
        } else {
            Logger.v(LOG_TAG_UI, "user-present: unknown action ${intent.action}, skipping")
        }
    }
}
