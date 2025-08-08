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
package com.celzero.bravedns.ui

import Logger
import Logger.LOG_TAG_UI
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper.getMainLooper
import android.util.Log

object LauncherSwitcher {

    private const val TAG = "LauncherSwitcher"
    private const val DELAY_MILLIS = 1500L // delay in milliseconds for switching aliases

    /**
     * Switches the launcher entry from one activity alias to another.
     *
     * @param context Application context
     * @param toAlias Fully qualified name of the launcher alias to enable
     * @param fromAlias Fully qualified name of the current launcher alias to disable
     */
    fun switchLauncherAlias(context: Context, toAlias: String, fromAlias: String) {
        val pm = context.packageManager

        Logger.v(LOG_TAG_UI, "$TAG switching launcher alias from $fromAlias to $toAlias")
        if (toAlias == fromAlias) {
            Log.w(LOG_TAG_UI, "$TAG both aliases are the same. No switch needed.")
            return
        }

        try {
            Logger.i(LOG_TAG_UI, "$TAG disabling alias: $fromAlias, enabling alias: $toAlias")
            // temporarily enable the new one before disabling the old one
            pm.setComponentEnabledSetting(
                ComponentName(context.packageName, toAlias),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )

            // delay before disabling the old one; this is to ensure the new alias is registered
            Handler(getMainLooper()).postDelayed({
                pm.setComponentEnabledSetting(
                    ComponentName(context.packageName, fromAlias),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }, DELAY_MILLIS)
            Logger.i(LOG_TAG_UI, "$TAG launcher switched from $fromAlias to $toAlias")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "$TAG err to switch launcher alias", e)
        }
        Logger.i(LOG_TAG_UI, "$TAG launcher alias switch completed: $fromAlias -> $toAlias")
    }

    /**
     * Checks if a given alias is currently enabled in the launcher.
     */
    fun isAliasEnabled(context: Context, aliasName: String): Boolean {
        val pm = context.packageManager
        val state = pm.getComponentEnabledSetting(ComponentName(context.packageName, aliasName))
        val isEnabled = state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        Logger.i(LOG_TAG_UI, "$TAG alias $aliasName enabled state: $state, isEnabled: $isEnabled")
        return isEnabled
    }
}
