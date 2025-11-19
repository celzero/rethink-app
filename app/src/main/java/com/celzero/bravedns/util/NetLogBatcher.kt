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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

// channel buffer receives batched entries of batchsize or once every waitms from a batching
// producer or a time-based monitor (signal) running in a single-threaded co-routine context.
class NetLogBatcher<T, V>(
    private val tag: String,
    private val looper: CoroutineDispatcher,
    private val batchSize: Int,
    private val processor: suspend (List<T>) -> Unit,
    private val updator: suspend (List<V>) -> Unit = { _ -> },
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

    private var batches: AtomicReference<MutableList<T>> = AtomicReference(mutableListOf())
    private var updates: AtomicReference<MutableList<V>> = AtomicReference(mutableListOf())

    fun begin(scope: CoroutineScope) {
        // launch suspend fns sig and consume asynchronously
        // exceptions with async will close the scope, with launch only job is cancelled
        scope.launch { sig() }
        scope.launch { consumeAdd() }
        scope.launch { consumeUpdate() }
    }

    // stackoverflow.com/a/68905423
    suspend fun close() = withContext(NonCancellable) {
                if (closed.compareAndSet(false, true)) {
                    signal.close()
                    buffersCh.close()
                    updatesCh.close()
                    logd("end")
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
        if (DEBUG) Log.d(LOG_BATCH_LOGGER, "$tag; $msg")
    }

    private suspend fun txswap(reason: String) {
        // increment lsn before any potential suspension or delays; because add() and update()
        // only signals the current lsn when the buffer size is 1, and might end up racing
        lsn = (lsn + 1)
        // swap buffers
        val b = batches.getAndSet(mutableListOf())
        val u = updates.getAndSet(mutableListOf())

        if (b.isNotEmpty()) {
            buffersCh.send(b)
        }
        if (u.isNotEmpty()) {
            delay(waitms / 5)
            updatesCh.send(u)
        }

        logd( "txswap (${lsn}) b: ${b.size}, u: ${u.size}, lsn -> $lsn, reason: $reason")
    }

    suspend fun add(payload: T) =
        withContext(looper + nprod) {
            val b = batches.get()
            b.add(payload)
            // if the batch size is met, dispatch it to the consumer
            if (b.size >= batchSize) {
                txswap("add-full")
            } else if (b.size == 1) {
                signal.send(lsn) // start tracking 'lsn'
            }
        }

    suspend fun update(payload: V) =
        withContext(looper + nprod) {
            val u = updates.get()
            u.add(payload)
            if (u.size >= batchSize) {
                txswap("update-full")
            } else if (u.size == 1) {
                signal.send(lsn)
            }
        }

    private suspend fun sig() =
        withContext(looper + nsig) {
            // consume all signals
            for (tracklsn in signal) {
                if (tracklsn < lsn) {
                    logd("expired signal skip $tracklsn < $lsn")
                    continue
                }
                // do not honor the signal for 'l' if a[l] is empty
                // this can happen if the signal for 'l' is processed
                // after the fact that 'l' has been swapped out by 'batch'
                val b = batches.get()
                val u = updates.get()
                if (b.isEmpty() && u.isEmpty()) {
                    logd("signal continue, empty buf; cur: $lsn / track: $tracklsn")
                    continue
                } else {
                    logd("signal sleep $waitms ms, cur: $lsn / track: $tracklsn")
                }

                // wait for 'batch' to dispatch
                delay(waitms)
                logd("signal wait over, sz(b: ${b.size}, u: ${u.size}) / cur(${lsn}), track(${tracklsn})")

                // 'l' is the current buffer, that is, 'l == i',
                // and 'batch' hasn't dispatched it,
                // but time's up...
                if (lsn == tracklsn) {
                    txswap("signal-timeout")
                }
            }
        }
}
