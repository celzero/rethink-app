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

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.bumptech.glide.util.Util
import com.celzero.bravedns.data.IPDetails
import com.celzero.bravedns.database.AppInfoRepository
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.database.ConnectionTrackerRepository
import com.celzero.bravedns.database.RefreshDatabase
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG_FIREWALL_LOG
import com.celzero.bravedns.util.FileSystemUID
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.getCountryCode
import com.celzero.bravedns.util.Utilities.Companion.getFlag
import com.google.common.net.InetAddresses
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.*

class IPTracker internal constructor(
    private val appInfoRepository: AppInfoRepository,
    private val connectionTrackerRepository: ConnectionTrackerRepository,
    private val refreshDatabase: RefreshDatabase,
    private val context: Context) {


    private val recentTrackers: Queue<IPDetails> = LinkedList()

    //private val recentIPActivity: Queue<Long> = LinkedList()
    private var historyEnabled = true

    fun getRecentIPTransactions(): Queue<IPDetails?>? {
        return LinkedList(recentTrackers)
    }

    fun recordTransaction(ipDetails: IPDetails?) {
        //Modified the call of the insert to database inside the coroutine scope.
        GlobalScope.launch(Dispatchers.IO) {
            if (ipDetails != null) {
                insertToDB(ipDetails)
            }
        }
    }

    private fun insertToDB(ipDetails: IPDetails) {
        val connTracker = ConnectionTracker()
        connTracker.ipAddress = ipDetails.destIP
        connTracker.isBlocked = ipDetails.isBlocked
        connTracker.uid = ipDetails.uid
        connTracker.port = ipDetails.destPort
        connTracker.protocol = ipDetails.protocol
        connTracker.timeStamp = ipDetails.timeStamp
        connTracker.blockedByRule = ipDetails.blockedByRule

        // InetAddresses - 'com.google.common.net.InetAddresses' is marked unstable with @Beta
        // Unlike InetAddress.getByName(), the methods of this class never cause DNS services
        // to be accessed
        val serverAddress = InetAddresses.forString(ipDetails.destIP)
        val countryCode: String = getCountryCode(serverAddress!!, context)
        connTracker.flag = getFlag(countryCode)


        //app-name
        val packageNameList = context.packageManager.getPackagesForUid(ipDetails.uid)

        if (packageNameList != null) {
            if (DEBUG) Log.d(LOG_TAG_FIREWALL_LOG, "Package for uid : ${ipDetails.uid}, ${packageNameList.size}")
            val packageName = packageNameList[0]
            val appDetails = HomeScreenActivity.GlobalVariable.appList[packageName]
            if (appDetails != null) {
                connTracker.appName = appDetails.appName
            }

            if (connTracker.appName.isNullOrBlank()) {
                connTracker.appName = appInfoRepository.getAppNameForUID(ipDetails.uid)
            }
            if (connTracker.appName.isNullOrEmpty()) {
                val appInfo = context.packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
                connTracker.appName = context.packageManager.getApplicationLabel(appInfo).toString()
            }
        } else {
            val fileSystemUID = FileSystemUID.fromFileSystemUID(ipDetails.uid)
            if (DEBUG) Log.d(LOG_TAG_FIREWALL_LOG, "Part : ${ipDetails.uid}, ${fileSystemUID.name}")
            if (ipDetails.uid == INVALID_UID) {
                connTracker.appName = "Unknown"
            } else {
                when (Utilities.isInvalidUid(fileSystemUID.uid)) {
                    true -> connTracker.appName = "Unnamed(${ipDetails.uid})"
                    false -> connTracker.appName = fileSystemUID.name
                }
                if (!isAvailableInDatabase(ipDetails.uid)) {
                    insertNonAppToAppInfo(ipDetails.uid, connTracker.appName.toString())
                }
            }
        }
        connectionTrackerRepository.insertAsync(connTracker)
    }

    private fun insertNonAppToAppInfo(uid: Int, appName: String) {
        refreshDatabase.insertNonAppToAppInfo(uid, appName)
    }

    private fun isAvailableInDatabase(uid: Int): Boolean {
        val appName = appInfoRepository.getAppNameForUID(uid)
        return !appName.isNullOrEmpty()
    }
}
