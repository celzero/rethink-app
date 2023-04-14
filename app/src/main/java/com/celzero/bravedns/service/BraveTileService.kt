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

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.celzero.bravedns.ui.PrepareVpnActivity

@RequiresApi(api = Build.VERSION_CODES.N)
class BraveTileService : TileService(), LifecycleOwner {
    override val lifecycle = LifecycleRegistry(this)

    override fun onCreate() {
        VpnController.persistentState.vpnEnabledLiveData.observe(this, this::updateTile)
    }

    private fun updateTile(requested: Boolean) {
        // Fix detected null pointer exception. Intra #415
        qsTile?.apply {
            state = if (requested) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onStartListening() {
        updateTile(VpnController.state().activationRequested)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    override fun onStopListening() {
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    override fun onClick() {
        val vpnState: VpnState = VpnController.state()

        if (vpnState.activationRequested) {
            VpnController.stop(this)
        } else {
            if (VpnService.prepare(this) == null) {
                // Start VPN service when VPN permission has been granted.
                VpnController.start(this)
            } else {
                // Open Main activity when VPN permission has not been granted.
                val intent = Intent(this, PrepareVpnActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivityAndCollapse(intent)
            }
        }
    }
}
