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

package com.celzero.bravedns.service

import Logger
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.ui.PrepareVpnActivity
import com.celzero.bravedns.util.Utilities
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

@RequiresApi(api = Build.VERSION_CODES.N)
class BraveTileService : TileService(), KoinComponent {

    private val persistentState by inject<PersistentState>()

    override fun onCreate() {
        persistentState.vpnEnabledLiveData.observeForever(this::updateTile)
    }

    private fun updateTile(enabled: Boolean) {
        qsTile?.apply {
            state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()

        if (VpnController.isOn()) {
            VpnController.stop(this)
        } else if (VpnService.prepare(this) == null) {
            // Start VPN service when VPN permission has been granted.
            VpnController.start(this)
        } else {
            // open Main activity when VPN permission has not been granted.
            val intent =
                if (Utilities.isHeadlessFlavour()) {
                    Intent(this, PrepareVpnActivity::class.java)
                } else {
                    Intent(this, HomeScreenActivity::class.java)
                }
            val pendingIntent = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            try {
                if (Utilities.isAtleastU()) {
                    startActivityAndCollapse(pendingIntent)
                } else {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivityAndCollapse(intent)
                }
            } catch (e: UnsupportedOperationException) {
                // starting activity from TileService using an Intent is not allowed
                // use PendingIntent instead
                Logger.w(Logger.LOG_TAG_UI, "Tile: unsupported operation, use send()", e)
                pendingIntent.send()
            } catch (e: Exception) {
                Logger.w(Logger.LOG_TAG_UI, "Tile: err in starting activity", e)
            }
        }
    }
}
