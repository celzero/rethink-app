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
import android.content.SharedPreferences;
import android.util.Log;

import com.celzero.bravedns.net.doh.Transaction;
import com.celzero.bravedns.ui.HomeScreenActivity;
import com.google.common.collect.Iterables;

import java.util.Collections;
import java.util.LinkedList;
import java.util.Queue;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;


import static android.content.Context.MODE_PRIVATE;

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
  private SortedSet<Integer> queryList = new TreeSet();
  private boolean historyEnabled = true;
  private Transaction transaction = null;

  QueryTracker(Context context) {
    sync(context, transaction);
  }

  public synchronized long getNumRequests() {
    return numRequests;
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

  synchronized void recordTransaction(Context context, Transaction transaction) {
    // Increment request counter on each successful resolution
    //if (transaction.status == Transaction.Status.) {
      ++numRequests;
      // HomeScreenActivity.GlobalVariable.INSTANCE.setLifeTimeQueries(numRequests);
      if (numRequests % HISTORY_SIZE != 0) {
        // Avoid losing too many requests in case of an unclean shutdown, but also avoid
        // excessive disk I/O from syncing the counter to disk after every request.
        sync(context, transaction);
      }
    //}

    recentActivity.add(transaction.queryTime);
    PersistentState.Companion.setNumOfReq(context);
    /*while (recentActivity.peek() + ACTIVITY_MEMORY_MS < transaction.queryTime) {
      recentActivity.remove();
    }*/

    if (historyEnabled) {
      recentTransactions.add(transaction);
      if (recentTransactions.size() > HISTORY_SIZE) {
        recentTransactions.remove();
      }
    }
  }

  public synchronized void sync(Context context, Transaction transaction) {
    if (transaction != null) {
      // Restore number of requests from storage, or 0 if it isn't defined yet.
      numRequests = PersistentState.Companion.getNumOfReq(context);
      if (!queryList.isEmpty()){
        if (queryList.size() > HISTORY_SIZE) {
          int val = queryList.last();
          queryList.remove(val);
        }
    }
      int val = (int) (transaction.responseTime - transaction.queryTime);
      queryList.add(val);
      int positionP50 = (int) (queryList.size() * 0.50);
      val = Iterables.get(queryList, positionP50);
      PersistentState.Companion.setMedianLatency(context, val);

    }
  }
}

