/*
Copyright 2020 RethinkDNS developers

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
package com.celzero.bravedns.database

import androidx.lifecycle.LiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class AppInfoRepository(private val appInfoDAO: AppInfoDAO) {

    fun updateAsync(appInfo: AppInfo, coroutineScope: CoroutineScope = GlobalScope) {
        coroutineScope.launch {
            appInfoDAO.update(appInfo)
        }
    }

    fun delete(appInfo: AppInfo) {
        appInfoDAO.delete(appInfo)
    }

    fun insert(appInfo: AppInfo) {
        appInfoDAO.insert(appInfo)
    }

    fun deleteByPackageName(packageNames: List<String>) {
        appInfoDAO.deleteByPackageName(packageNames)
    }

    fun isRootUserAvailable(): String? {
        return appInfoDAO.isRootAvailable()
    }

    fun getAppInfo(): List<AppInfo> {
        return appInfoDAO.getAllAppDetails()
    }

    fun getNonAppCount(): Int {
        return appInfoDAO.getNonAppCount()
    }

    fun updateInternetForUID(uid: Int, isInternetAllowed: Boolean) {
        appInfoDAO.updateInternetPermissionForAlluid(uid, isInternetAllowed)
    }

    fun getAppListForUID(uid: Int): List<AppInfo> {
        return appInfoDAO.getAppListForUID(uid)
    }

    fun setInternetAllowedForCategory(categoryName: String, isInternetAllowed: Boolean) {
        appInfoDAO.updateInternetPermissionForCategory(categoryName, isInternetAllowed)
    }

    fun getAllAppDetailsForLiveData(): LiveData<List<AppInfo>> {
        return appInfoDAO.getAllAppDetailsForLiveData()
    }

    fun getAppDetailsForLiveData(input: String): LiveData<List<AppInfo>> {
        if (input.isEmpty()) {
            return appInfoDAO.getAllAppDetailsForLiveData()
        }
        return appInfoDAO.getAppDetailsForLiveData(input)
    }

    fun getUIDForUnivWhiteList(): LiveData<List<Int>> {
        return appInfoDAO.getUIDForUnivWhiteList()
    }

    fun getAppNameList(): MutableList<String> {
        return appInfoDAO.getAppNameList().toMutableList()
    }

    fun removeUninstalledPackage(packageName: String) {
        return appInfoDAO.removeUninstalledPackage(packageName)
    }

    fun getAppCategoryList(): List<String> {
        return appInfoDAO.getAppCategoryList()
    }

    fun getAppCategoryForAppName(appName: String): List<String> {
        return appInfoDAO.getAppCategoryForAppName(appName)
    }

    fun getAppCountForCategory(categoryName: String): Int {
        return appInfoDAO.getAppCountForCategory(categoryName)
    }

    fun getBlockedCountForCategory(categoryName: String): Int {
        return appInfoDAO.getBlockedCountForCategory(categoryName)
    }

    fun getPackageNameForAppName(appName: String): String {
        return appInfoDAO.getPackageNameForAppName(appName)
    }

    fun getPackageNameForUid(uid: Int): String {
        return appInfoDAO.getPackageNameForUid(uid)
    }

    fun updateWhitelist(uid: Int, isEnabled: Boolean) {
        appInfoDAO.updateWhitelist(uid, isEnabled)
    }

    fun updateWhitelistForAllApp(isEnabled: Boolean): Int {
        return appInfoDAO.updateWhitelistForAllApp(isEnabled)
    }

    fun updateWhitelistForCategories(category: String, isEnabled: Boolean): Int {
        return appInfoDAO.updateWhitelistForCategories(category, isEnabled)
    }

    fun updateExcludedForAllApp(isExcluded: Boolean, coroutineScope: CoroutineScope = GlobalScope) {
        coroutineScope.launch {
            appInfoDAO.updateExcludedForAllApp(isExcluded)
        }
    }

    fun updateExcludedForCategories(category: String, isExcluded: Boolean,
                                    coroutineScope: CoroutineScope = GlobalScope) {
        coroutineScope.launch {
            appInfoDAO.updateExcludedForCategories(category, isExcluded)
        }
    }

    fun updateExcludedList(uid: Int, isEnabled: Boolean) {
        appInfoDAO.updateExcludedList(uid, isEnabled)
    }

    fun getExcludedAppList(): List<String> {
        return appInfoDAO.getExcludedAppList()
    }

    fun getAppInfoForPackageName(packageName: String): AppInfo? {
        return appInfoDAO.getAppInfoForPackageName(packageName)
    }


    fun getWhitelistCountForCategory(categoryName: String): Int {
        return appInfoDAO.getWhitelistCount(categoryName)
    }

    fun getExcludedAppCountForCategory(categoryName: String): Int {
        return appInfoDAO.getExcludedAppCountForCategory(categoryName)
    }
}
