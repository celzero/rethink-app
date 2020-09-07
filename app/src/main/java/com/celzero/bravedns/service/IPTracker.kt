package com.celzero.bravedns.service

import android.content.Context
import com.celzero.bravedns.data.IPDetails
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.util.FileSystemUID
import com.celzero.bravedns.util.Utilities.Companion.getCountryCode
import com.celzero.bravedns.util.Utilities.Companion.getFlag
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
        fun recordTransaction(context: Context?, ipDetails: IPDetails ) {
            //recentIPActivity.add(ipDetails.timeStamp)
            //if (HomeScreenActivity.GlobalVariable.DEBUG) Log.d("BraveDNS","Record Transaction")
            insertToDB(context!!, ipDetails)
            if (historyEnabled) {
                recentTrackers.add(ipDetails)
                if (recentTrackers.size > HISTORY_SIZE) {
                    recentTrackers.remove()
                }
            }
        }

        private fun insertToDB(context:Context, ipDetails: IPDetails){
            val mDb = AppDatabase.invoke(context.applicationContext)
            val connTrackRepository = mDb.connectionTrackerRepository()
            val connTracker = ConnectionTracker()
            connTracker.ipAddress = ipDetails.destIP
            connTracker.isBlocked = ipDetails.isBlocked
            connTracker.uid = ipDetails.uid
            connTracker.port = ipDetails.destPort.toInt()
            connTracker.protocol = ipDetails.protocol
            connTracker.timeStamp = ipDetails.timeStamp

            var serverAddress: InetAddress? = null
            //var resolver : String? = null

            if (ipDetails.destIP != null) {
                serverAddress = InetAddress.getByName(ipDetails.destIP)
            } else {
                serverAddress = null
            }
            val countryCode: String = getCountryCode(serverAddress!!, context)
            connTracker.flag =  getFlag(countryCode)


            //appname
            var packageName = context.packageManager.getPackagesForUid(ipDetails.uid)

            if (packageName != null) {
                HomeScreenActivity.GlobalVariable.appList.forEach {
                    if (it.value.uid == ipDetails.uid) {
                        connTracker.appName = it.value.appName
                    }
                }
            } else {
                var packageName = FileSystemUID.fromFileSystemUID(ipDetails.uid)
                if (packageName.uid == -1)
                    connTracker.appName = "Unknown"
                else
                    connTracker.appName = packageName.name
            }

            connTrackRepository.insertAsync(connTracker)
        }
}
