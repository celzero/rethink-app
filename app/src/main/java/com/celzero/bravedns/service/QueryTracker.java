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

import androidx.annotation.NonNull;

import com.celzero.bravedns.net.doh.Transaction;
import com.celzero.bravedns.util.P2QuantileEstimation;

/**
 * A class for tracking DNS transactions.  This class counts the number of successful transactions,
 * records the last minute of query timestamps, and optionally maintains a history of recent
 * transactions.
 * Thread-safe.
 */
public class QueryTracker {

    private static final int HISTORY_SIZE = 1000;
    private static long numRequests = 0;
    private static P2QuantileEstimation quantileEstimator;
    @NonNull private final Context context;
    @NonNull private final PersistentState persistentState;

    QueryTracker(@NonNull PersistentState persistentState, @NonNull Context context) {
        this.context = context;
        this.persistentState = persistentState;
    }

    public void reinitializeQuantileEstimator(){
        quantileEstimator = new P2QuantileEstimation(0.5);
        numRequests = 1;
    }

    synchronized void recordTransaction(Transaction transaction) {
        ++numRequests;
        if (numRequests % HISTORY_SIZE == 0) {
            numRequests = 1;
            reinitializeQuantileEstimator();
        }
        sync(transaction);
    }

    public synchronized void sync(Transaction transaction) {
        if (transaction != null && transaction.blockList.isEmpty() && !transaction.serverIp.isEmpty()) {
            // Restore number of requests from storage, or 0 if it isn't defined yet.
            long val =  transaction.responseTime;
            if(quantileEstimator == null){
                quantileEstimator = new P2QuantileEstimation(0.5);
            }else{
                quantileEstimator.addValue((double)val);
            }
            long latencyVal = (long)quantileEstimator.getQuantile();
            persistentState.setMedianLatency(latencyVal);
        }
    }
}

