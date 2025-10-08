/*
 * Copyright 2023 RethinkDNS and its authors
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
package com.celzero.bravedns.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import com.celzero.bravedns.R
import Logger
import java.util.Locale

object OemBatteryOptimizationHelper {

    data class OemBatteryInfo(
        val oemName: String,
        val settingsIntents: List<String>,
        val instructionKey: String
    )

    private val oemBatterySettings = mapOf(
        "xiaomi" to OemBatteryInfo(
            "Xiaomi", 
            listOf(
                "miui.intent.action.POWER_HIDE_MODE_APP_LIST",
                "miui.intent.action.OP_AUTO_START",
                "com.miui.powerkeeper.configure.AUTO_START_ACTIVITY"
            ),
            "xiaomi_battery_instruction"
        ),
        "oppo" to OemBatteryInfo(
            "Oppo", 
            listOf(
                "com.coloros.safecenter.startupapp.StartupAppListActivity",
                "com.oppo.safe.permission.startup.StartupAppListActivity",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            ),
            "oppo_battery_instruction"
        ),
        "vivo" to OemBatteryInfo(
            "Vivo", 
            listOf(
                "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            ),
            "vivo_battery_instruction"
        ),
        "huawei" to OemBatteryInfo(
            "Huawei", 
            listOf(
                "com.huawei.systemmanager.optimize.process.ProtectActivity",
                "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"
            ),
            "huawei_battery_instruction"
        ),
        "oneplus" to OemBatteryInfo(
            "OnePlus", 
            listOf(
                "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
                "com.coloros.safecenter.startupapp.StartupAppListActivity"
            ),
            "oneplus_battery_instruction"
        ),
        "honor" to OemBatteryInfo(
            "Honor", 
            listOf(
                "com.hihonor.systemmanager.optimize.process.ProtectActivity",
                "com.huawei.systemmanager.optimize.process.ProtectActivity"
            ),
            "huawei_battery_instruction" // Reuse Huawei instructions as Honor is similar
        ),
        "realme" to OemBatteryInfo(
            "Realme", 
            listOf(
                "com.coloros.safecenter.startupapp.StartupAppListActivity",
                "com.oppo.safe.permission.startup.StartupAppListActivity"
            ),
            "oppo_battery_instruction" // Realme uses ColorOS like Oppo
        )
    )

    fun getDeviceOem(): String {
        return Build.MANUFACTURER.lowercase(Locale.ROOT)
    }

    fun isOemWithCustomBatteryOptimization(): Boolean {
        return oemBatterySettings.containsKey(getDeviceOem())
    }

    fun getOemBatteryInfo(): OemBatteryInfo? {
        return oemBatterySettings[getDeviceOem()]
    }

    fun openOemBatterySettings(context: Context): Boolean {
        val oemInfo = getOemBatteryInfo() ?: return false
        
        Logger.d(Logger.LOG_TAG_UI, "Attempting to open OEM battery settings for ${oemInfo.oemName}")
        
        for (intentAction in oemInfo.settingsIntents) {
            if (tryOpenOemSetting(context, intentAction)) {
                Logger.d(Logger.LOG_TAG_UI, "Successfully opened OEM setting: $intentAction")
                return true
            }
        }
        
        Logger.w(Logger.LOG_TAG_UI, "Failed to open any OEM battery settings for ${oemInfo.oemName}")
        return false
    }

    private fun tryOpenOemSetting(context: Context, intentAction: String): Boolean {
        return try {
            val intent = Intent()
            
            // Try as action first
            try {
                intent.action = intentAction
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                return true
            } catch (e: Exception) {
                // If action fails, try as component name
                try {
                    intent.action = null
                    intent.component = android.content.ComponentName.unflattenFromString(intentAction)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                    return true
                } catch (e2: Exception) {
                    // Continue to next intent
                    return false
                }
            }
        } catch (e: Exception) {
            Logger.w(Logger.LOG_TAG_UI, "Failed to open OEM setting: $intentAction", e)
            false
        }
    }

    fun getOemSpecificInstructions(context: Context): String {
        val oemInfo = getOemBatteryInfo()
        return if (oemInfo != null) {
            // Use OEM-specific instruction if available, otherwise fallback to generic
            try {
                context.getString(context.resources.getIdentifier(
                    oemInfo.instructionKey, "string", context.packageName))
            } catch (e: Exception) {
                getGenericOemInstructions(context, oemInfo.oemName)
            }
        } else {
            context.getString(R.string.generic_battery_optimization_instruction)
        }
    }

    private fun getGenericOemInstructions(context: Context, oemName: String): String {
        return context.getString(R.string.oem_battery_optimization_instruction, oemName)
    }
}