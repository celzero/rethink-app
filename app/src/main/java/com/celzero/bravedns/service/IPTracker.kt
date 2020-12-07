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
import com.celzero.bravedns.data.IPDetails
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.util.FileSystemUID
import com.celzero.bravedns.util.Utilities.Companion.getCountryCode
import com.celzero.bravedns.util.Utilities.Companion.getFlag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.util.*

class IPTracker(var context: Context?) {

        private val HISTORY_SIZE = 10000

        private val recentTrackers: Queue<IPDetails> = LinkedList()
        //private val recentIPActivity: Queue<Long> = LinkedList()
        private var historyEnabled = true

        @Synchronized
        fun getRecentIPTransactions(): Queue<IPDetails?>? {
            return LinkedList(recentTrackers)
        }


        @Synchronized
        fun recordTransaction(context: Context?, ipDetails: IPDetails? ) {
            if(context != null && ipDetails != null) {
                insertToDB(context, ipDetails)
            }
            //recentIPActivity.add(ipDetails.timeStamp)
            //if (HomeScreenActivity.GlobalVariable.DEBUG) Log.d(LOG_TAG,"Record Transaction")
            /*if (HomeScreenActivity.GlobalVariable.DEBUG) Log.d(Constants.LOG_TAG, "Conn tracker Record Transaction: ${ipDetails.uid},${recentTrackers.size}")
            if(context != null) {
                if (PersistentState.getBackgroundEnabled(context)) {
                    recentTrackers.add(ipDetails)

                    if (recentTrackers.size >= 10) {
                        val insertValues = recentTrackers
                        insertToDB(context, insertValues)
                        //recentTrackers.clear()
                    }
                } else {
                    recentTrackers.add(ipDetails)
                    insertToDB(context, recentTrackers)
                    //recentTrackers.clear()
                }
            }*/
            /*if (historyEnabled) {
                recentTrackers.add(ipDetails)
                if (recentTrackers.size > HISTORY_SIZE) {
                    recentTrackers.remove()
                }
            }*/
        }

        private fun insertToDB(context:Context, ipDetails : IPDetails ){
            GlobalScope.launch(Dispatchers.IO) {
                val mDb = AppDatabase.invoke(context.applicationContext)
                val appInfoRepository = mDb.appInfoRepository()
                val connTrackRepository = mDb.connectionTrackerRepository()
                //var connTrackerList: MutableList<ConnectionTracker> = ArrayList()
                //ipDetailsList.forEach { ipDetails ->
                    val connTracker = ConnectionTracker()
                    connTracker.ipAddress = ipDetails.destIP
                    connTracker.isBlocked = ipDetails.isBlocked
                    connTracker.uid = ipDetails.uid
                    connTracker.port = ipDetails.destPort
                    connTracker.protocol = ipDetails.protocol
                    connTracker.timeStamp = ipDetails.timeStamp
                    connTracker.blockedByRule = ipDetails.blockedByRule

                    var serverAddress: InetAddress? = null
                    //var resolver : String? = null

                    serverAddress = InetAddress.getByName(ipDetails.destIP)
                    val countryCode: String = getCountryCode(serverAddress!!, context)
                    connTracker.flag = getFlag(countryCode)

                    //appname
                    var packageNameList = context.packageManager.getPackagesForUid(ipDetails.uid)
                    //val appName = context.packageManager.getNameForUid(ipDetails.uid)

                    if (packageNameList != null) {
                        //connTracker.appName = appName
                        val packageName = packageNameList[0]
                        val appDetails = HomeScreenActivity.GlobalVariable.appList[packageName]
                        if(appDetails != null){
                            connTracker.appName = appDetails.appName
                        }
                        /*HomeScreenActivity.GlobalVariable.appList.forEach {
                            if (it.value.uid == ipDetails.uid) {
                                connTracker.appName = it.value.appName
                            }
                        }*/
                        if (connTracker.appName.isNullOrBlank()) {
                            connTracker.appName = appInfoRepository.getAppNameForUID(ipDetails.uid)
                        }
                        if(connTracker.appName.isNullOrEmpty()){
                            val appInfo = context.packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
                            connTracker.appName = context.packageManager.getApplicationLabel(appInfo).toString()
                        }
                    } else {
                        val fileSystemUID = FileSystemUID.fromFileSystemUID(ipDetails.uid)
                        if(ipDetails.uid == -1){
                            connTracker.appName = "Unknown"
                        } else if (fileSystemUID.uid == -1)
                            connTracker.appName = "Unnamed(${ipDetails.uid})"
                        else
                            connTracker.appName = fileSystemUID.name
                    }
                    //connTrackerList.add(connTracker)
                    connTrackRepository.insertAsync(connTracker)
                }

                //connTrackRepository.insertBulkAsync(connTrackerList as ArrayList<ConnectionTracker>)

                //mDb.close()


        }
}
