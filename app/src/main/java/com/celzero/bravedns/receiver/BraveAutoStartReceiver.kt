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

import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_VPN
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.celzero.bravedns.scheduler.BootStartWorker
import com.celzero.bravedns.scheduler.BootStartWorker.Companion.BOOT_COMPLETE_EVENT
import com.celzero.bravedns.scheduler.BootStartWorker.Companion.PACKAGE_REPLACED_EVENT
import com.celzero.bravedns.scheduler.BootStartWorker.Companion.REBOOT_EVENT
import com.celzero.bravedns.scheduler.BootStartWorker.Companion.USER_UNLOCKED_EVENT
import com.celzero.bravedns.service.PersistentState
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class BraveAutoStartReceiver : BroadcastReceiver(), KoinComponent {

    val persistentState by inject<PersistentState>()

    override fun onReceive(context: Context, intent: Intent) {
        if (
            Intent.ACTION_REBOOT != intent.action &&
                Intent.ACTION_BOOT_COMPLETED != intent.action &&
                Intent.ACTION_USER_UNLOCKED != intent.action &&
                Intent.ACTION_MY_PACKAGE_REPLACED != intent.action
        ) {
            Logger.w(LOG_TAG_VPN, "unhandled broadcast ${intent.action}")
            return
        }

        // MY_PACKAGE_REPLACED is intentionally exempt: after an app update we want to
        // restore a previously-running VPN regardless of the boot-up toggle.
        if (!persistentState.prefAutoStartBootUp &&
            (intent.action == Intent.ACTION_BOOT_COMPLETED ||
                    intent.action == Intent.ACTION_REBOOT ||
                    intent.action == Intent.ACTION_USER_UNLOCKED)
        ) {
            Logger.w(LOG_TAG_VPN, "auto-start not enabled: ${persistentState.prefAutoStartBootUp}, skipping")
            return
        }

        val eventType = when (intent.action) {
            Intent.ACTION_USER_UNLOCKED -> USER_UNLOCKED_EVENT
            Intent.ACTION_MY_PACKAGE_REPLACED -> PACKAGE_REPLACED_EVENT
            Intent.ACTION_REBOOT -> REBOOT_EVENT
            Intent.ACTION_BOOT_COMPLETED -> BOOT_COMPLETE_EVENT
            else -> "boot(${intent.action})"
        }

        Logger.i(LOG_TAG_VPN, "scheduling boot-start worker after $eventType")
        BootStartWorker.enqueue(context, eventType)
    }
}
