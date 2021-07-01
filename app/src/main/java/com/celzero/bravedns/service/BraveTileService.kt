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

import android.content.ComponentName
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.celzero.bravedns.ui.HomeScreenActivity

@RequiresApi(api = Build.VERSION_CODES.N)
class BraveTileService : TileService() {

    override fun onStartListening() {
        val vpnState: VpnState = VpnController.getInstance().getState()

        //Fix detected null pointer exception. Intra #415
        val tile = if (qsTile == null) {
            return
        } else {
            qsTile
        }

        if (vpnState.activationRequested) {
            tile.state = Tile.STATE_ACTIVE
        } else {
            tile.state = Tile.STATE_INACTIVE
        }

        tile.updateTile()
    }

    override fun onClick() {
        val vpnState: VpnState = VpnController.getInstance().getState()

        if (vpnState.activationRequested) {
            VpnController.getInstance().stop(this)
        } else {
            if (VpnService.prepare(this) == null) {
                // Start VPN service when VPN permission has been granted.
                VpnController.getInstance().start(this)
            } else {
                // Open Main activity when VPN permission has not been granted.
                val intent = Intent(this, HomeScreenActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivityAndCollapse(intent)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {

        // Update tile state on boot.
        requestListeningState(this, ComponentName(this, BraveTileService::class.java))
        return super.onBind(intent)
    }
}
