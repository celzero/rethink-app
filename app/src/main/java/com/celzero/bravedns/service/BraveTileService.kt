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

import com.celzero.bravedns.util.Logger
import android.app.PendingIntent
import android.app.UiModeManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import androidx.lifecycle.Observer
import com.celzero.bravedns.ui.activity.AppLockActivity
import com.celzero.bravedns.ui.activity.MiscSettingsActivity
import com.celzero.bravedns.util.Utilities
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

@RequiresApi(api = Build.VERSION_CODES.N)
class BraveTileService : TileService(), KoinComponent {

    private val persistentState by inject<PersistentState>()

    // Single stable Observer instance so addObserver/removeObserver actually
    // match the same callback. Kotlin method references like `this::updateTile`
    // generate a new function-reference object per `::` expression — meaning
    // removeObserver(this::updateTile) does NOT find the observer previously
    // registered with observeForever(this::updateTile), and the observer leaks.
    private val tileObserver = Observer<Boolean> { updateTile() }

    companion object {
        // rebind this tile so onStartListening() can get its current
        // state. updateTile() calls made while the tile is not listening are
        // silently dropped; without this, the tile keeps showing its last state
        fun requestTileUpdate(context: Context) {
            try {
                TileService.requestListeningState(
                    context,
                    ComponentName(context, BraveTileService::class.java)
                )
            } catch (e: Exception) {
                Logger.w(Logger.LOG_TAG_VPN, "Tile: err in requesting listening state", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Logger.v(Logger.LOG_TAG_VPN, "Tile: on create")
    }

    // The tile is only bound between onStartListening() and onStopListening()
    override fun onStartListening() {
        super.onStartListening()
        try {
            persistentState.vpnEnabledLiveData.observeForever(tileObserver)
        } catch (e: Exception) {
            Logger.w(Logger.LOG_TAG_UI, "Tile: err in observing VPN state", e)
        }
        updateTile()
    }

    override fun onStopListening() {
        try {
            persistentState.vpnEnabledLiveData.removeObserver(tileObserver)
        } catch (e: Exception) {
            Logger.w(Logger.LOG_TAG_UI, "Tile: err in removing observer", e)
        }
        super.onStopListening()
    }

    // get the tile state from the actual vpn state, not from the persistent state alone
    private fun isVpnActive(): Boolean {
        val state = VpnController.state()
        return state.activationRequested && (state.on || state.connectionState != null)
    }

    private fun updateTile() {
        val enabled = isVpnActive()
        qsTile?.apply {
            state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
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

    private fun isVpnPrepared(): Boolean {
        return try {
            VpnService.prepare(this) == null
        } catch (e: NullPointerException) {
            Logger.w(Logger.LOG_TAG_VPN, "Tile: device does not support system-wide VPN mode", e)
            false
        } catch (e: IllegalStateException) {
            // VpnService.prepare() throws IllegalStateException("Unavailable in lockdown mode")
            // when another VPN app is set as Always-on VPN with "Block connections without VPN"
            // enabled. See ConnectivityService.throwIfLockdownEnabled(). Fall through to the
            // else-branch (opens the app) so the user can resolve it there.
            Logger.w(Logger.LOG_TAG_VPN, "Tile: vpn unavailable, in lockdown mode", e)
            false
        } catch (e: Exception) {
            Logger.w(Logger.LOG_TAG_VPN, "Tile: err while preparing vpn service", e)
            false
        }
    }

    override fun onClick() {
        super.onClick()
        // do not start or stop VPN if app lock is enabled
        if (VpnController.state().activationRequested && !isAppLockEnabled()) {
            if (VpnController.isAlwaysOn(this)) {
                Logger.i(Logger.LOG_TAG_VPN, "Tile: vpn is always-on, opening app instead of stop")
                openApp()
            } else {
                VpnController.stop("tile", this)
            }
        } else if (isVpnPrepared() && !isAppLockEnabled()) {
            // Start VPN service when VPN permission has been granted
            VpnController.start(this)
        } else {
            // open the app to handle the VPN start or stop
            openApp()
        }
    }

    private fun openApp() {
        val intent = Intent(this, AppLockActivity::class.java)
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
