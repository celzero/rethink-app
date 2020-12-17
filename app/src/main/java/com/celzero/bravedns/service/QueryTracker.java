/*
Copyright 2018 Jigsaw Operations LLC

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
package com.celzero.bravedns.service;

import android.content.Context;

import com.celzero.bravedns.net.doh.Transaction;
import com.celzero.bravedns.util.P2QuantileEstimation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

/**
 * A class for tracking DNS transactions.  This class counts the number of successful transactions,
 * records the last minute of query timestamps, and optionally maintains a history of recent
 * transactions.
 * Thread-safe.
 */
public class QueryTracker {

    public static final String NUM_REQUESTS = "numRequests";

    private static final int HISTORY_SIZE = 1000;
    //private static final int ACTIVITY_MEMORY_MS = 60 * 1000;  // One minute

    private long numRequests = 0;
    private Queue<Transaction> recentTransactions = new LinkedList<>();
    private Queue<Long> recentActivity = new LinkedList<>();
    private List<Integer> queryList = new ArrayList<Integer>();
    private boolean historyEnabled = true;
    private Transaction transaction = null;
    private static P2QuantileEstimation quantileEstimator = new P2QuantileEstimation(0.5);

    QueryTracker(Context context) {
        sync(context, transaction);
    }


    public synchronized Queue<Transaction> getRecentTransactions() {
        return new LinkedList<>(recentTransactions);
    }

    /**
     * Provide the receiver with temporary read-only access to the recent activity time-sequence.
     */
    public synchronized void showActivity(ActivityReceiver receiver) {
        receiver.receive(Collections.unmodifiableCollection(recentActivity));
    }

    public synchronized int countQueriesSince(long startTime) {
        // Linearly scan recent activity.  Due to the small scale (N ~ 300), a more efficient algorithm
        // does not appear to be necessary.
        int queries = recentActivity.size();
        for (long time : recentActivity) {
            if (time < startTime) {
                --queries;
            } else {
                break;
            }
        }
        return queries;
    }

    public synchronized void setHistoryEnabled(boolean enabled) {
        historyEnabled = enabled;
        if (!enabled) {
            recentTransactions.clear();
        }
    }

    public boolean isHistoryEnabled() {
        // No synchronization needed because booleans are atomic in Java.
        return historyEnabled;
    }

    public static
    void reinitializeQuantileEstimator(){
        quantileEstimator = new P2QuantileEstimation(0.5);
    }

    synchronized void recordTransaction(Context context, Transaction transaction) {
        // Increment request counter on each successful resolution
        //if (transaction.status == Transaction.Status.) {
        ++numRequests;
        // HomeScreenActivity.GlobalVariable.INSTANCE.setLifeTimeQueries(numRequests);
        if (numRequests % HISTORY_SIZE == 0) {
            reinitializeQuantileEstimator();
        }
        //}
        sync(context, transaction);

        //recentActivity.add(transaction.queryTime);
        //PersistentState.Companion.setNumOfReq(context);
        /*DnsPacket packet = null;
        String BLOCKED_RESPONSE_TYPE = "0.0.0.0";
        try {
            if (transaction.response != null)
                packet = new DnsPacket(transaction.response);
        } catch (ProtocolException e) {
            Log.e(LOG_TAG, e.getMessage(), e);
        }
        if (packet != null) {
            List<InetAddress> addresses = packet.getResponseAddresses();
            if (addresses.size() > 0) {
                InetAddress destination = addresses.get(0);
                //val countryCode: String = getCountryCode(destination, adapter!!.activity)
                if (destination.getHostAddress().contains(BLOCKED_RESPONSE_TYPE)) {
                    PersistentState.Companion.setBlockedReq(context);
                }
            }
        }*/

        /*if (historyEnabled) {
            recentTransactions.add(transaction);
            if (recentTransactions.size() > HISTORY_SIZE) {
                recentTransactions.remove();
            }
        }*/
    }

    public synchronized void sync(Context context, Transaction transaction) {
        if (transaction != null && transaction.blockList.isEmpty() && !transaction.serverIp.isEmpty()) {
            // Restore number of requests from storage, or 0 if it isn't defined yet.
            long val =  (transaction.responseTime - transaction.queryTime);
            if(quantileEstimator == null){
                quantileEstimator = new P2QuantileEstimation(0.5);
            }else{
                quantileEstimator.addValue((double)val);
            }

            long latencyVal = (long)quantileEstimator.getQuantile();

            PersistentState.Companion.setMedianLatency(context, latencyVal);

            /*if (transaction.blockList.isEmpty() && !transaction.serverIp.isEmpty()) {
                if (!queryList.isEmpty()) {
                    if (queryList.size() >= HISTORY_SIZE) {
                        queryList.remove(0);
                    }
                }

                queryList.add((int)val);
                List<Integer> _queryList = new ArrayList<Integer>(queryList);
                Collections.sort(_queryList);
                int positionP50 = (int) (_queryList.size() * 0.50);
                val = _queryList.get(positionP50);
                PersistentState.Companion.setMedianLatency(context, val);
            }*/
        }
    }
}

