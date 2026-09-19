/*
Copyright 2026 RethinkDNS and its authors

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
import android.content.Context
import android.service.quicksettings.TileService
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Utilities

/**
 * Stands between callers and the API-24-only quick-settings tile machinery.
 *
 * fix lint error
 */
object QuickSettingsTileHelper {

    // rebind the tile so onStartListening() can get its current state. tile
    // updates pushed while the tile is not listening are silently dropped by
    // SystemUI; without this push, the tile keeps showing its last state.
    fun requestTileUpdate(context: Context) {
        // TileService.requestListeningState() is API 24+; minSdk is 23.
        if (!Utilities.isAtleastN()) return
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
