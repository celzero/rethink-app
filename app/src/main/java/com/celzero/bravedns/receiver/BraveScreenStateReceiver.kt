/*
Copyright 2020 RethinkDNS and its authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package com.celzero.bravedns.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.util.Log
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_VPN
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject


class BraveScreenStateReceiver : BroadcastReceiver(), KoinComponent {

    val persistentState by inject<PersistentState>()

    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_SCREEN_OFF == intent.action) {
            if (DEBUG) Log.d(LOG_TAG_VPN, "BraveScreenStateReceiver : Action_screen_off detected from the receiver")
            if (persistentState.screenState && !persistentState.isScreenOff) {
                if (DEBUG) Log.d(LOG_TAG_VPN, "BraveScreenStateReceiver : Screen lock data not true, calling DeviceLockService service")
                val newIntent = Intent(context, DeviceLockService::class.java)
                newIntent.action = DeviceLockService.ACTION_CHECK_LOCK
                newIntent.putExtra(DeviceLockService.EXTRA_STATE, intent.action)
                context.startService(newIntent)
            }
        } else if (intent.action.equals(Intent.ACTION_USER_PRESENT) || intent.action.equals(Intent.ACTION_SCREEN_ON)) {
            if (DEBUG) Log.d(LOG_TAG_VPN, "BraveScreenStateReceiver : ACTION_USER_PRESENT/ACTION_SCREEN_ON detected from the receiver")
            if (persistentState.screenState) {
                val state = persistentState.isScreenOff
                if (state) {
                    persistentState.isScreenOff = false
                    val connectivityManager: ConnectivityManager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    connectivityManager.reportNetworkConnectivity(connectivityManager.activeNetwork, true)
                }
            }
        }
    }
}
