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
import android.app.UiModeManager
import android.content.Intent
import android.content.res.Configuration
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.celzero.bravedns.ui.PrepareVpnActivity
import com.celzero.bravedns.ui.activity.AppLockActivity
import com.celzero.bravedns.ui.activity.MiscSettingsActivity
import com.celzero.bravedns.util.Utilities
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

@RequiresApi(api = Build.VERSION_CODES.N)
class BraveTileService : TileService(), KoinComponent {

    private val persistentState by inject<PersistentState>()

    override fun onCreate() {
        // may be called multiple times, but we only need to observe once
        // can't use onStartListening() as it is not called when the tile is added
        // to the quick settings panel
        super.onCreate()
        try {
            persistentState.vpnEnabledLiveData.observeForever(this::updateTile)
        } catch (e: Exception) {
            Logger.w(Logger.LOG_TAG_UI, "Tile: err in observing VPN state", e)
        }
    }

    private fun updateTile(enabled: Boolean) {
        qsTile?.apply {
            state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        try {
            persistentState.vpnEnabledLiveData.observeForever(this::updateTile)
        } catch (e: Exception) {
            Logger.w(Logger.LOG_TAG_UI, "Tile: err in observing VPN state", e)
        }
        updateTile(persistentState.getVpnEnabled())
    }

    override fun onStopListening() {
        super.onStopListening()
        try {
            persistentState.vpnEnabledLiveData.removeObserver(this::updateTile)
        } catch (e: Exception) {
            Logger.w(Logger.LOG_TAG_UI, "Tile: err in removing observer", e)
        }
    }

    private fun isAppRunningOnTv(): Boolean {
        return try {
            val uiModeManager: UiModeManager = getSystemService(UI_MODE_SERVICE) as UiModeManager
            uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        } catch (_: Exception) {
            false
        }
    }

    private fun isAppLockEnabled(): Boolean {
        if (isAppRunningOnTv()) return false

        // TODO: should we check for last unlock time here?
        MiscSettingsActivity.BioMetricType.fromValue(persistentState.biometricAuthType).let {
            return it.enabled()
        }
    }

    override fun onClick() {
        super.onClick()
        // do not start or stop VPN if app lock is enabled
        if (VpnController.state().activationRequested && !isAppLockEnabled()) {
            VpnController.stop("tile",this)
        } else if (VpnService.prepare(this) == null && !isAppLockEnabled()) {
            // Start VPN service when VPN permission has been granted
            VpnController.start(this)
        } else {
            // open the app to handle the VPN start or stop
            val intent =
                if (Utilities.isHeadlessFlavour()) {
                    Intent(this, PrepareVpnActivity::class.java)
                } else {
                    Intent(this, AppLockActivity::class.java)
                }
            val pendingIntent = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            try {
                if (Utilities.isAtleastU()) {
                    startActivityAndCollapse(pendingIntent)
                } else {
                    // For older versions, convert PendingIntent to Intent and start the activity
                    val newIntent = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(newIntent)
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
