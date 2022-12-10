/*
 * Copyright 2022 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.celzero.bravedns.util

import android.util.Log
import com.celzero.bravedns.BuildConfig.DEBUG
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel

// channel buffer receives batched entries of batchsize or once every waitms from a batching
// producer or a time-based monitor (signal) running in a single-threaded co-routine context.
class NetLogBatcher<T>(val processor: suspend (List<T>) -> Unit) {
    // i keeps track of currently in-use buffer
    var lsn = 0

    // a single thread to run sig and batch co-routines in;
    // to avoid use of mutex/semaphores over shared-state
    @OptIn(DelicateCoroutinesApi::class) val looper = newSingleThreadContext("netlogprovider")

    private val n1 = CoroutineName("producer")
    private val n2 = CoroutineName("signal")

    // dispatch buffer to consumer if greater than batch size
    private val batchSize = 20

    // no of batch-sized buffers to hold in a channel
    private val qsize = 2

    // wait time before dispatching a batch, regardless of its size
    // signal waits min waitms and max waitms*2
    private val waitms = 2500L

    // buffer channel, holds at most 2 buffers, and drops the oldest
    private val buffers = Channel<List<T>>(qsize, BufferOverflow.DROP_OLDEST)

    // signal channel, holds at most 1 signal, and drops the oldest
    private val signal = Channel<Int>(Channel.Factory.CONFLATED)

    private var batches = mutableListOf<T>()

    fun begin(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            golooperAsync(scope) { sig() }
            golooperAsync(scope) { consume() }
            monitorCancellation()
        }
    }

    private suspend fun golooperAsync(s: CoroutineScope, f: suspend () -> Unit): Deferred<Unit> {
        return s.async(s.coroutineContext + looper + n2, CoroutineStart.DEFAULT) { f() }
    }

    // stackoverflow.com/a/68905423
    private suspend fun monitorCancellation() {
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) {
                signal.close()
                buffers.close()
                looper.close()
            }
        }
    }

    private suspend fun consume() {
        for (y in buffers) {
            processor(y)
        }
    }

    private suspend fun txswap() {
        val b = batches
        batches = mutableListOf() // swap buffers
        if (DEBUG) Log.d(LoggerConstants.LOG_BATCH_LOGGER, "transfer and swap (${lsn}) ${b.size}")
        lsn = (lsn + 1)
        buffers.send(b)
    }

    suspend fun add(payload: T) =
        withContext(looper + n1) {
            batches.add(payload)
            // if the batch size is met, dispatch it to the consumer
            if (batches.size >= batchSize) {
                txswap()
            } else if (batches.size == 1) {
                signal.send(lsn) // start tracking 'lsn'
            }
        }

    private suspend fun sig() {
        // consume all signals
        for (tracklsn in signal) {
            // do not honor the signal for 'l' if a[l] is empty
            // this can happen if the signal for 'l' is processed
            // after the fact that 'l' has been swapped out by 'batch'
            if (batches.size <= 0) {
                if (DEBUG) Log.d(LoggerConstants.LOG_BATCH_LOGGER, "signal continue for buffer")
                continue
            } else {
                if (DEBUG)
                    Log.d(LoggerConstants.LOG_BATCH_LOGGER, "signal sleep for $waitms for buffer")
            }

            // wait for 'batch' to dispatch
            delay(waitms)
            if (DEBUG)
                Log.d(
                    LoggerConstants.LOG_BATCH_LOGGER,
                    "signal wait over for buf, sz(${batches.size}) / cur-buf(${lsn})"
                )

            // 'l' is the current buffer, that is, 'l == i',
            // and 'batch' hasn't dispatched it,
            // but time's up...
            if (lsn == tracklsn && batches.size > 0) {
                txswap()
            }
        }
    }
}
