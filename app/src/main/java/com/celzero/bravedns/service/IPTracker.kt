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
import com.celzero.bravedns.R
import com.celzero.bravedns.data.IPDetails
import com.celzero.bravedns.database.AppInfoRepository
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.database.ConnectionTrackerRepository
import com.celzero.bravedns.database.RefreshDatabase
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.AndroidUidConfig
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import com.celzero.bravedns.util.LoggerConstants
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_FIREWALL_LOG
import com.celzero.bravedns.util.Utilities.Companion.getCountryCode
import com.celzero.bravedns.util.Utilities.Companion.getFlag
import com.google.common.net.InetAddresses
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.net.InetAddress
import java.util.*

class IPTracker internal constructor(private val appInfoRepository: AppInfoRepository,
                                     private val connectionTrackerRepository: ConnectionTrackerRepository,
                                     private val refreshDatabase: RefreshDatabase,
                                     private val context: Context) : KoinComponent {


    private val recentTrackers: Queue<IPDetails> = LinkedList()

    private var historyEnabled = true
    private val persistentState by inject<PersistentState>()

    fun getRecentIPTransactions(): Queue<IPDetails?>? {
        return LinkedList(recentTrackers)
    }

    fun recordTransaction(ipDetails: IPDetails?) {
        if (!persistentState.logsEnabled) return

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
        connTracker.timeStamp = ipDetails.timestamp
        connTracker.blockedByRule = ipDetails.blockedByRule

        // InetAddresses - 'com.google.common.net.InetAddresses' is marked unstable with @Beta
        // Unlike InetAddress.getByName(), the methods of this class never cause DNS services
        // to be accessed
        var serverAddress: InetAddress? = null
        try {
            serverAddress = InetAddresses.forString(ipDetails.destIP)
        } catch (e: IllegalArgumentException) {
            Log.e(LoggerConstants.LOG_TAG_DNS_LOG,
                  "Failure converting string to InetAddresses: ${e.message}", e)
        }
        val countryCode: String = getCountryCode(serverAddress, context)
        connTracker.flag = getFlag(countryCode)

        connTracker.appName = getApplicationName(connTracker.uid)
        connectionTrackerRepository.insert(connTracker)
    }

    private fun getApplicationName(uid: Int): String {
        val packageNameList = context.packageManager.getPackagesForUid(uid)
        val appName: String?

        if (packageNameList != null) {
            if (DEBUG) Log.d(LOG_TAG_FIREWALL_LOG,
                             "Package for uid : ${uid}, ${packageNameList.size}")
            val packageName = packageNameList[0]
            appName = getValidAppName(packageName, uid)
        } else { // For UNKNOWN or Non-App.
            val fileSystemUID = AndroidUidConfig.fromFileSystemUid(uid)
            Log.i(LOG_TAG_FIREWALL_LOG,
                  "App name for the uid: ${uid}, AndroidUid: ${fileSystemUID.uid}, fileName: ${fileSystemUID.name}")
            if (uid == INVALID_UID) {
                appName = context.getString(R.string.network_log_app_name_unknown)
            } else {
                if (fileSystemUID.uid == INVALID_UID) {
                    appName = context.getString(R.string.network_log_app_name_unnamed,
                                                uid.toString())
                } else {
                    appName = fileSystemUID.name
                    registerNonApp(uid, appName)
                }
            }
        }
        return appName
    }

    private fun getValidAppName(packageName: String, uid: Int): String {
        var appName: String? = null
        val appDetails = HomeScreenActivity.GlobalVariable.appList[packageName]
        if (appDetails != null) {
            appName = appDetails.appName
        }

        if (appName.isNullOrEmpty()) {
            appName = appInfoRepository.getAppNameForUID(uid)
        }
        if (appName.isNullOrEmpty()) {
            val appInfo = context.packageManager.getApplicationInfo(packageName,
                                                                    PackageManager.GET_META_DATA)
            appName = context.packageManager.getApplicationLabel(appInfo).toString()
        }
        return appName
    }

    private fun registerNonApp(uid: Int, appName: String) {
        if (!isUidRegistered(uid)) {
            refreshDatabase.registerNonApp(uid, appName)
        }
    }

    private fun isUidRegistered(uid: Int): Boolean {
        val appName = appInfoRepository.getAppNameForUID(uid)
        return !appName.isNullOrEmpty()
    }
}
