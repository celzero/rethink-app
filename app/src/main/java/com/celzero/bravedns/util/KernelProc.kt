/*
 * Copyright 2026 RethinkDNS and its authors
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

import android.os.Process
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads /proc/self/auxv once and returns a human readable breakdown of the aux vector entries.
 * Result is cached so subsequent callers do not hit the filesystem again.
 */
object KernelProc {
    private const val AUXV_PATH = "/proc/self/auxv"
    private const val STATUS_PATH = "/proc/self/status"
    private const val SCHED_PATH = "/proc/self/sched"
    private const val NS_DIR = "/proc/self/ns"
    private const val NET_DIR = "/proc/self/net"
    private const val TASK_DIR = "/proc/self/task"
    private const val MEM_PATH = "/proc/self/mem" // usually unreadable; handled gracefully
    private const val SMAPS_PATH = "/proc/self/smaps"
    private const val MAPS_PATH = "/proc/self/maps"
    private const val MAX_READ_BYTES = 512 * 1024 // prevent OOM on large proc files

    // Lazy so we only read and parse once per process lifetime.
    private val cachedStats: String by lazy { readAuxvHumanReadable() }
    private val cachedText: MutableMap<String, String> = mutableMapOf()

    fun getStats(forceRefresh: Boolean = false): String = if (forceRefresh) readAuxvHumanReadable() else cachedStats

    fun getStatus(forceRefresh: Boolean = false): String = readProcText(STATUS_PATH, title = "status", forceRefresh = forceRefresh)
    fun getSched(forceRefresh: Boolean = false): String = readProcText(SCHED_PATH, title = "sched", forceRefresh = forceRefresh)
    fun getNs(forceRefresh: Boolean = false): String = readDirAsLines(NS_DIR, title = "ns", forceRefresh = forceRefresh)
    fun getNet(forceRefresh: Boolean = false): String = readDirAsLines(NET_DIR, title = "net", forceRefresh = forceRefresh)
    fun getTask(forceRefresh: Boolean = false): String = readDirAsLines(TASK_DIR, title = "task", forceRefresh = forceRefresh)
    fun getMem(forceRefresh: Boolean = false): String = readProcText(MEM_PATH, title = "mem", forceRefresh = forceRefresh)
    fun getSmaps(forceRefresh: Boolean = false): String = readProcText(SMAPS_PATH, title = "smaps", forceRefresh = forceRefresh)
    fun getMaps(forceRefresh: Boolean = false): String = readProcText(MAPS_PATH, title = "maps", forceRefresh = forceRefresh)

    private fun readProcText(path: String, title: String, forceRefresh: Boolean = false): String {
        if (forceRefresh) cachedText.remove(path)
        return cachedText.getOrPut(path) {
            runCatching {
                val file = File(path)
                if (!file.exists()) return@getOrPut "$title: missing"
                file.inputStream().buffered().use { stream ->
                    val limited = ByteArray(MAX_READ_BYTES)
                    val read = stream.read(limited)
                    if (read <= 0) return@getOrPut "$title: empty"
                    val content = String(limited, 0, read)
                    val body = if (stream.read() != -1) "$title (truncated to ${MAX_READ_BYTES}B)\n$content" else "$title\n$content"
                    body
                }
            }.getOrElse { err -> "$title: error: ${err.message ?: err::class.java.simpleName}" }
        }
    }

    private fun readDirAsLines(dirPath: String, title: String, forceRefresh: Boolean = false): String {
        // Directory contents (like /proc/self/task) change often; read live instead of caching.
        return runCatching {
            val dir = File(dirPath)
            if (!dir.exists()) return@runCatching "$title: missing"
            val files = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
            if (files.isEmpty()) return@runCatching "$title: empty"
            val body = files.joinToString(separator = "\n") { f ->
                val target = runCatching { f.canonicalPath }.getOrDefault(f.path)
                "${f.name} -> $target"
            }
            "$title\n$body"
        }.getOrElse { err -> "$title: error: ${err.message ?: err::class.java.simpleName}" }
    }

    private fun readAuxvHumanReadable(): String {
        return runCatching {
            val file = File(AUXV_PATH)
            if (!file.exists()) return@runCatching "auxv: missing ($AUXV_PATH)"

            val bytes = file.readBytes()
            val wordSize = if (Process.is64Bit()) 8 else 4
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())

            val typeNames = mapOf(
                0L to "AT_NULL",
                3L to "AT_PHDR",
                4L to "AT_PHENT",
                5L to "AT_PHNUM",
                6L to "AT_PAGESZ",
                7L to "AT_BASE",
                8L to "AT_FLAGS",
                9L to "AT_ENTRY",
                11L to "AT_UID",
                12L to "AT_EUID",
                13L to "AT_GID",
                14L to "AT_EGID",
                15L to "AT_PLATFORM",
                16L to "AT_HWCAP",
                17L to "AT_CLKTCK",
                20L to "AT_HWCAP2",
                23L to "AT_SECURE",
                25L to "AT_RANDOM",
                31L to "AT_EXECFN",
                33L to "AT_SECURE_PLATFORM"
            )

            fun readWord(): Long = if (wordSize == 8) buffer.long else buffer.int.toLong() and 0xffffffffL

            val entries = mutableListOf<String>()
            while (buffer.remaining() >= wordSize * 2) {
                val type = readWord()
                val value = readWord()
                if (type == 0L) break // AT_NULL terminator
                val name = typeNames[type] ?: "UNKNOWN"
                val annotated = "$name($type)=0x${value.toString(16)} ($value)"
                entries.add(annotated)
            }

            if (entries.isEmpty()) "auxv: empty" else entries.joinToString(separator = "\n")
        }.getOrElse { err -> "auxv: error: ${err.message ?: err::class.java.simpleName}" }
    }
}
