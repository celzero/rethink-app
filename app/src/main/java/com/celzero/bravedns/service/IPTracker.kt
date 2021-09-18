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
import android.util.Log
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.data.IPDetails
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.database.ConnectionTrackerRepository
import com.celzero.bravedns.util.AndroidUidConfig
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_FIREWALL_LOG
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.getCountryCode
import com.celzero.bravedns.util.Utilities.Companion.getFlag
import com.celzero.bravedns.util.Utilities.Companion.getPackageInfoForUid
import com.google.common.net.InetAddresses
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.net.InetAddress
import java.util.*

class IPTracker internal constructor(
        private val connectionTrackerRepository: ConnectionTrackerRepository,
        private val context: Context) : KoinComponent {


    private val recentTrackers: Queue<IPDetails> = LinkedList()

    private val persistentState by inject<PersistentState>()

    fun getRecentIPTransactions(): Queue<IPDetails?>? {
        return LinkedList(recentTrackers)
    }

    fun recordTransaction(ipDetails: IPDetails?) {
        if (ipDetails == null) return
        if (!persistentState.logsEnabled) return

        //Modified the call of the insert to database inside the coroutine scope.
        io {
            insertToDB(ipDetails)
        }
    }

    private suspend fun insertToDB(ipDetails: IPDetails) {
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
            Log.e(LOG_TAG_FIREWALL_LOG, "Failure converting string to InetAddresses: ${e.message}",
                  e)
        }
        val countryCode: String? = getCountryCode(serverAddress, context)
        connTracker.flag = getFlag(countryCode)

        connTracker.appName = fetchApplicationName(connTracker.uid)
        connectionTrackerRepository.insert(connTracker)
    }

    private suspend fun fetchApplicationName(uid: Int): String {
        if (uid == INVALID_UID) {
            return context.getString(R.string.network_log_app_name_unknown)
        }

        val packageNameList = getPackageInfoForUid(context, uid)
        val appName: String?

        if (packageNameList != null) {
            val packageName = packageNameList[0]
            appName = getValidAppName(uid, packageName)
        } else { // For UNKNOWN or Non-App.
            val fileSystemUID = AndroidUidConfig.fromFileSystemUid(uid)
            Log.i(LOG_TAG_FIREWALL_LOG,
                  "App name for the uid: ${uid}, AndroidUid: ${fileSystemUID.uid}, fileName: ${fileSystemUID.name}")

            if (fileSystemUID.uid == INVALID_UID) {
                appName = context.getString(R.string.network_log_app_name_unnamed, uid.toString())
            } else {
                appName = fileSystemUID.name
            }
        }
        return appName
    }

    private fun getValidAppName(uid: Int, packageName: String): String {
        var appName: String? = null

        if (appName.isNullOrEmpty()) {
            appName = FirewallManager.getAppNameByUid(uid)
        }
        if (appName.isNullOrEmpty()) {
            val appInfo = Utilities.getApplicationInfo(context, packageName) ?: return ""

            Log.i(LOG_TAG_FIREWALL_LOG,
                  "app, $appName, in PackageManager's list not tracked by FirewallManager")
            appName = context.packageManager.getApplicationLabel(appInfo).toString()
        }
        return appName
    }

    private fun io(f: suspend () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            f()
        }
    }

}
