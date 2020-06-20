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
import com.celzero.bravedns.ui.HomeScreenFragment

@RequiresApi(api = Build.VERSION_CODES.N)
class BraveTileService : TileService() {

    override fun onStartListening() {
        val vpnState: VpnState = VpnController.getInstance()!!.getState(this)!!

        val tile = qsTile

        if (vpnState.activationRequested) {
            tile.state = Tile.STATE_ACTIVE
        } else {
            tile.state = Tile.STATE_INACTIVE
        }

        tile.updateTile()
    }

    override fun onClick() {
        val vpnState: VpnState = VpnController.getInstance()!!.getState(this)!!

        if (vpnState.activationRequested) {
            VpnController.getInstance()!!.stop(this)
        } else {
            if (VpnService.prepare(this) == null) {
                // Start VPN service when VPN permission has been granted.
                VpnController.getInstance()!!.start(this)
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
        requestListeningState(
            this,
            ComponentName(this, BraveTileService::class.java)
        )
        return super.onBind(intent)
    }


}