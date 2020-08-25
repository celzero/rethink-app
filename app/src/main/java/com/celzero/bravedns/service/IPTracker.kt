package com.celzero.bravedns.service

import android.content.Context
import com.celzero.bravedns.data.IPDetails
import com.celzero.bravedns.net.doh.Transaction
import com.celzero.bravedns.service.PersistentState.Companion.getNumOfReq
import com.celzero.bravedns.service.PersistentState.Companion.setMedianLatency
import com.celzero.bravedns.service.PersistentState.Companion.setNumOfReq
import com.google.common.collect.Iterables
import java.util.*

class IPTracker(var context: Context?) {

        private val HISTORY_SIZE = 10000

        private val recentTrackers: Queue<IPDetails> = LinkedList()
        private val recentIPActivity: Queue<Long> = LinkedList()
        private var historyEnabled = true

        @Synchronized
        fun getRecentIPTransactions(): Queue<IPDetails?>? {
            return LinkedList(recentTrackers)
        }


        @Synchronized
        fun recordTransaction(context: Context?, ipDetails: IPDetails ) {
            recentIPActivity.add(ipDetails.timeStamp)
            if (historyEnabled) {
                recentTrackers.add(ipDetails)
                if (recentTrackers.size > HISTORY_SIZE) {
                    recentTrackers.remove()
                }
            }
        }
}
