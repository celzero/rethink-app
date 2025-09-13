/*
 * Copyright 2024 RethinkDNS and its authors
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

import Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

object Daemons {

    fun make(tag: String) = Executors.newSingleThreadExecutor(Factory(tag)).asCoroutineDispatcher()
    fun <T> ioDispatcher(tag: String, default: T, s: CoroutineScope) = CoFactory(tag, default, s, make(tag))
}

class CoFactory<T>(
    private val tag: String,
    private val default: T,
    private val scope: CoroutineScope,
    private val d: CoroutineDispatcher = Dispatchers.IO
) {
    data class Msg<T>(val m: suspend () -> T, val reply: Channel<Deferred<T>>)

    private val taskChannel = Channel<Msg<T>>(Channel.UNLIMITED)

    init {
        tasks()
        monitorCancellation()
    }

    private fun monitorCancellation() = scope.launch(Dispatchers.Default) {
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) {
                // close the task channel to stop accepting new tasks
                taskChannel.close()
            }
        }
    }

    private fun <T> ioAsync(f: suspend () -> T): Deferred<T> {
        return scope.async(CoroutineName(tag) + d) { f() }
    }

    private fun io(f: suspend () -> Unit) =
        scope.launch(CoroutineName(tag) + d) { f() }

    private suspend fun dispatch(f: suspend () -> T): Deferred<T> {
        val reply: Channel<Deferred<T>> = takeChannel()
        taskChannel.send(Msg(f, reply))
        val d = reply.receive()
        recycleChannel(reply)
        return d
    }

    suspend fun tryDispatch(f: suspend() -> T): T {
        return try {
            dispatch(f).await()
        } catch (e: Exception) {
            Logger.v(Logger.LOG_TAG_VPN, "err in tryDispatch: ${e.message}")
            default
        }
    }

    private fun tasks() = io {
        for (task in taskChannel) {
            val d = ioAsync(task.m)
            task.reply.send(d)
        }
    }

    // create a stack pooling for reply channels, pool for 20 channels with get and recycle
    private val channels = ArrayDeque<Channel<Deferred<T>>>(20)
    private val channelsMutex: Mutex = Mutex()

    private suspend fun takeChannel(): Channel<Deferred<T>> {
        channelsMutex.lock()
        if (channels.isEmpty()) {
            channelsMutex.unlock()
            return Channel(Channel.RENDEZVOUS)
        }
        // changes for android 15, removeLast() is replaced with removeAt(size - 1)
        val x = channels.removeAt(channels.size - 1)
        channelsMutex.unlock()
        return x
    }

    // always recycle the exhausted channel, ie., the channel that is completed all the receives
    private suspend fun recycleChannel(c: Channel<Deferred<T>>) {
        channelsMutex.lock()
        if (channels.size < 20) {
            channels.add(c)
        }
        channelsMutex.unlock()
    }

}

// adopted from: java.util.concurrent.Executors.DefaultThreadFactory
class Factory(tag: String = "d"): ThreadFactory {
    private val group: ThreadGroup?
    private val threadNumber = AtomicInteger(1)
    private val namePrefix: String

    init {
        val s = System.getSecurityManager()
        group = if ((s != null)) s.threadGroup else Thread.currentThread().threadGroup
        namePrefix = tag + poolNumber.getAndIncrement() + "t"
    }

    override fun newThread(r: Runnable): Thread {
        val t = Thread(
            group, r,
            namePrefix + threadNumber.getAndIncrement(),
            0
        )
        if (t.isDaemon) t.isDaemon = false
        if (t.priority != Thread.NORM_PRIORITY) t.priority = Thread.NORM_PRIORITY
        return t
    }

    companion object {
        private val poolNumber = AtomicInteger(1)
    }
}
