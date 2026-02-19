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
package com.celzero.bravedns.ui.dialog

import android.app.Activity
import com.celzero.bravedns.adapter.HopItem
import com.celzero.bravedns.service.WireguardManager
import android.app.Dialog
import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import androidx.recyclerview.widget.LinearLayoutManager
import com.celzero.bravedns.adapter.WgHopAdapter
import com.celzero.bravedns.databinding.DialogWgHopBinding
import com.celzero.bravedns.wireguard.Config

/**
 * Dialog for WireGuard configuration hopping
 * Now extends GenericHopDialog to reuse common hop logic
 */
class WgHopDialog(
    activity: Activity,
    themeID: Int,
    srcId: Int,
    configs: List<Config>,
    selectedId: Int,
    onHopChanged: ((Int) -> Unit)? = null
) : GenericHopDialog(
    activity,
    themeID,
    srcId,
    configs.map { config ->
        val mapping = WireguardManager.getConfigFilesById(config.getId())
        HopItem.WireGuardHop(config, mapping?.isActive ?: false)
    },
    selectedId,
    onHopChanged
) {
    companion object {
        /**
         * Create WireGuard hop dialog
         */
        fun create(
            activity: Activity,
            themeID: Int,
            srcConfigId: Int,
            availableConfigs: List<Config>,
            currentlySelectedConfigId: Int = -1,
            onHopChanged: ((Int) -> Unit)? = null
        ): WgHopDialog {
            return WgHopDialog(
                activity,
                themeID,
                srcConfigId,
                availableConfigs,
                currentlySelectedConfigId,
                onHopChanged
            )
        }
    }
}
