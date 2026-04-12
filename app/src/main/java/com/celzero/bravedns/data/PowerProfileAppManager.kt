/*
 * Copyright 2026 RethinkDNS and its authors
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
package com.celzero.bravedns.data

import android.content.Context
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.service.FirewallManager

data class PowerProfileAppSummary(
    val packageName: String,
    val appName: String,
    val uid: Int,
    val ipRuleCount: Int,
    val domainRuleCount: Int
)

data class PowerProfileAppPreview(
    val profile: PowerProfileDefinition,
    val appBlocklist: PowerProfileAppBlocklist,
    val installedAppInfo: AppInfo?
)

object PowerProfileAppManager {
    internal var lookupInstalledApp: suspend (String) -> AppInfo? = ::defaultLookupInstalledApp

    fun listApps(context: Context, profile: PowerProfileDefinition): List<PowerProfileAppBlocklist> {
        return PowerProfileArtifacts.loadArtifact(context, profile)?.apps.orEmpty()
    }

    fun getApp(
        context: Context,
        profile: PowerProfileDefinition,
        packageName: String
    ): PowerProfileAppBlocklist? {
        return listApps(context, profile).firstOrNull { it.packageName == packageName }
    }

    suspend fun buildAppSummaries(
        context: Context,
        profile: PowerProfileDefinition
    ): List<PowerProfileAppSummary> {
        return listApps(context, profile).map { app ->
            val installed = lookupInstalledApp(app.packageName)
            PowerProfileAppSummary(
                packageName = app.packageName,
                appName = installed?.appName ?: app.appName,
                uid = installed?.uid ?: -1,
                ipRuleCount = app.ipRules.size,
                domainRuleCount = app.domainRules.size
            )
        }
    }

    suspend fun buildPreview(
        context: Context,
        profileId: String,
        packageName: String
    ): PowerProfileAppPreview? {
        val profile = PowerProfileCatalog.get(context, profileId) ?: return null
        val appBlocklist = getApp(context, profile, packageName) ?: return null
        val installedAppInfo = lookupInstalledApp(packageName)
        return PowerProfileAppPreview(
            profile = profile,
            appBlocklist = appBlocklist,
            installedAppInfo = installedAppInfo
        )
    }

    private suspend fun defaultLookupInstalledApp(packageName: String): AppInfo? {
        return FirewallManager.getAppInfoByPackage(packageName)
    }
}
