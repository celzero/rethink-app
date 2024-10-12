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

import Logger.LOG_BATCH_LOGGER
import android.util.Log
import co.touchlab.stately.concurrency.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// channel buffer receives batched entries of batchsize or once every waitms from a batching
// producer or a time-based monitor (signal) running in a single-threaded co-routine context.
class NetLogBatcher<T, V>(
    private val tag: String,
    private val looper: CoroutineDispatcher,
    private val processor: suspend (List<T>) -> Unit,
    private val updator: suspend (List<V>) -> Unit = { _ -> }
) {
    companion object {
        private const val DEBUG = true
    }

    // i keeps track of currently in-use buffer
    var lsn = 0

    private val nprod = CoroutineName(tag + "Producer") // batches writes
    private val nsig = CoroutineName(tag + "Signal")
    private val ncons = CoroutineName(tag + "Consumer") // writes batches to db
    private val closed = AtomicBoolean(false)
    // dispatch buffer to consumer if greater than batch size
    private val batchSize = 20

    // no of batch-sized buffers to hold in a channel
    private val qsize = 2

    // wait time before dispatching a batch, regardless of its size
    // signal waits min waitms and max waitms*2
    private val waitms = 2500L

    // buffer channel, holds at most 2 buffers, and drops the oldest
    private val buffersCh = Channel<List<T>>(qsize, BufferOverflow.DROP_OLDEST)

    // update channel, holds at most 2 buffers, and drops the oldest
    private val updatesCh = Channel<List<V>>(qsize, BufferOverflow.DROP_OLDEST)

    // signal channel, holds at most 1 signal, and drops the oldest
    private val signal = Channel<Int>(Channel.Factory.CONFLATED)

    private var batches = mutableListOf<T>()
    private var updates = mutableListOf<V>()

    fun begin(scope: CoroutineScope) {
        // launch suspend fns sig and consume asynchronously
        scope.async { sig() }
        scope.async { consumeAdd() }
        scope.async { consumeUpdate() }
    }

    // stackoverflow.com/a/68905423
    suspend fun close() = withContext(NonCancellable) {
                if (closed.compareAndSet(expected = false, new = true)) {
                    signal.close()
                    buffersCh.close()
                    updatesCh.close()
                    logd("$tag end")
                }
    }

    private suspend fun consumeAdd() =
        withContext(Dispatchers.IO + ncons) {
            for (y in buffersCh) {
                processor(y)
            }
        }

    private suspend fun consumeUpdate() =
        withContext(Dispatchers.IO + ncons) {
            for (y in updatesCh) {
                updator(y)
            }
        }

    private fun logd(msg: String) {
        // write batcher logs only in DEBUG mode to avoid log spam
        if (DEBUG) Log.d(LOG_BATCH_LOGGER, msg)
    }

    private suspend fun txswap() {
        val b = batches
        batches = mutableListOf() // swap buffers
        buffersCh.send(b)

        val u = updates
        updates = mutableListOf() // swap buffers
        updatesCh.send(u)

        logd( "txswap (${lsn}) b: ${b.size}, u: ${u.size}")

        lsn = (lsn + 1)
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

    suspend fun update(payload: V) =
        withContext(looper + nprod) {
            updates.add(payload)
            if (updates.size >= batchSize) {
                txswap()
            } else if (updates.size == 1) {
                signal.send(lsn)
            }
        }

    private suspend fun sig() =
        withContext(looper + nsig) {
            // consume all signals
            for (tracklsn in signal) {
                if (tracklsn < lsn) {
                    logd("dup signal skip $tracklsn")
                    continue
                }
                // do not honor the signal for 'l' if a[l] is empty
                // this can happen if the signal for 'l' is processed
                // after the fact that 'l' has been swapped out by 'batch'
                if (batches.size <= 0 && updates.size <= 0) {
                    logd("signal continue")
                    continue
                } else {
                    logd("signal sleep $waitms ms")
                }

                // wait for 'batch' to dispatch
                delay(waitms)
                logd("signal wait over, sz(b: ${batches.size}, u: ${updates.size}) / cur-buf(${lsn})")

                // 'l' is the current buffer, that is, 'l == i',
                // and 'batch' hasn't dispatched it,
                // but time's up...
                if (lsn == tracklsn && (batches.size > 0 || updates.size > 0)) {
                    txswap()
                }
            }
        }
}
