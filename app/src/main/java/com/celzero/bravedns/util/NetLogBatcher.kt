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
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext

// channel buffer receives batched entries of batchsize or once every waitms from a batching
// producer or a time-based monitor (signal) running in a single-threaded co-routine context.
class NetLogBatcher<T>(val processor: suspend (List<T>) -> Unit) {
    // i keeps track of currently in-use buffer
    var lsn = 0

    // a single thread to run sig and batch co-routines in;
    // to avoid use of mutex/semaphores over shared-state
    @OptIn(DelicateCoroutinesApi::class) val looper = newSingleThreadContext("logLooper")

    private val nprod = CoroutineName("logProducer") // batches writes
    private val nsig = CoroutineName("logSignal")
    private val ncons = CoroutineName("logConsumer") // writes batches to db

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
        // launch suspend fns sig and consume asynchronously
        scope.async { sig() }
        scope.async { consume() }
        // monitor for cancellation on the default dispatcher
        scope.launch { monitorCancellation() }
    }

    // stackoverflow.com/a/68905423
    private suspend fun monitorCancellation() {
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) {
                looper.close()
                signal.close()
                buffers.close()
            }
        }
    }

    private suspend fun consume() =
        withContext(Dispatchers.IO + ncons) {
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
        withContext(looper + nprod) {
            batches.add(payload)
            // if the batch size is met, dispatch it to the consumer
            if (batches.size >= batchSize) {
                txswap()
            } else if (batches.size == 1) {
                signal.send(lsn) // start tracking 'lsn'
            }
        }

    private suspend fun sig() =
        withContext(looper + nsig) {
            // consume all signals
            for (tracklsn in signal) {
                // do not honor the signal for 'l' if a[l] is empty
                // this can happen if the signal for 'l' is processed
                // after the fact that 'l' has been swapped out by 'batch'
                if (batches.size <= 0) {
                    if (DEBUG) Log.d(LoggerConstants.LOG_BATCH_LOGGER, "signal continue")
                    continue
                } else {
                    if (DEBUG) Log.d(LoggerConstants.LOG_BATCH_LOGGER, "signal sleep $waitms ms")
                }

                // wait for 'batch' to dispatch
                delay(waitms)
                if (DEBUG)
                    Log.d(
                        LoggerConstants.LOG_BATCH_LOGGER,
                        "signal wait over, sz(${batches.size}) / cur-buf(${lsn})"
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
