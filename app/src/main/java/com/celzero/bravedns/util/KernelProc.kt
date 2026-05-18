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
 * Reads /proc/self/auxv once and returns a human-readable breakdown of the aux vector entries.
 * Result is cached so subsequent callers do not hit the filesystem again.
 */
object KernelProc {
    private const val AUXV_PATH = "/proc/self/auxv"
    private const val STATUS_PATH = "/proc/self/status"
    private const val SCHED_PATH = "/proc/self/sched"
    private const val SCHEDSTAT_PATH = "/proc/self/schedstat"
    private const val TASK_DIR = "/proc/self/task"
    private const val SMAPS_ROLLUP_PATH = "/proc/self/smaps_rollup"
    private const val MAX_READ_BYTES = 512 * 1024 // prevent OOM on large proc files

    // Lazy so we only read and parse once per process lifetime.
    private val cachedStats: String by lazy { readAuxvHumanReadable(title = "AUXV") }
    private val cachedText: MutableMap<String, String> = mutableMapOf()

    fun getStats(forceRefresh: Boolean = false): String = if (forceRefresh) readAuxvHumanReadable(title = "AUXV") else cachedStats

    fun getStatus(forceRefresh: Boolean = false): String = readProcText(STATUS_PATH, title = "Status", forceRefresh = forceRefresh)

    fun getSmaps(forceRefresh: Boolean = false): String = readProcText(SMAPS_ROLLUP_PATH, title = "SMAPS ROLLUP", forceRefresh = forceRefresh)

    /**
     * Compact per-thread scheduler data for every thread in /proc/self/task.
     *
     * For each thread we read:
     *  - /proc/self/task/<tid>/status   → Name + State
     *  - /proc/self/task/<tid>/schedstat → <running_ns> <waiting_ns> <timeslices>
     *  - /proc/self/task/<tid>/sched    → key numeric fields (best-effort; may be empty)
     *
     * Returns a list sorted by tid.
     */
    data class ThreadSchedInfo(
        val tid: String,
        val name: String,
        val state: String,
        /** schedstat raw line, e.g. "123456 78901 42" */
        val schedstatRaw: String,
        /** Parsed schedstat fields (0 if unavailable). */
        val runningNs: Long,
        val waitingNs: Long,
        val timeslices: Long,
        /** Key fields from /proc/self/task/<tid>/sched (0 if unavailable). */
        val waitMax: Long,
        val nrWakeups: Long,
        val nrMigrations: Long,
        val nrInvoluntarySwitches: Long,
        val nrVoluntarySwitches: Long,
        /** Raw /proc/self/task/<tid>/sched text (may be blank). */
        val schedRaw: String
    )

    fun parseSchedAllThreads(): List<ThreadSchedInfo> {
        return runCatching {
            val dir = File(TASK_DIR)
            if (!dir.exists()) return@runCatching emptyList()
            val tidDirs = dir.listFiles()
                ?.filter { it.isDirectory }
                ?.sortedBy { it.name.toIntOrNull() ?: Int.MAX_VALUE }
                ?: return@runCatching emptyList()

            tidDirs.map { tidDir ->
                val tid = tidDir.name
                var name = "?"
                var state = "?"

                // Read status for name + state
                runCatching {
                    File(tidDir, "status").useLines { seq ->
                        for (line in seq) {
                            when {
                                name == "?" && line.startsWith("Name:") ->
                                    name = line.removePrefix("Name:").trim()
                                state == "?" && line.startsWith("State:") ->
                                    state = line.removePrefix("State:").trim()
                            }
                            if (name != "?" && state != "?") break
                        }
                    }
                }

                // Read schedstat
                val schedstatRaw = runCatching {
                    File(tidDir, "schedstat").takeIf { it.exists() }
                        ?.readText(Charsets.UTF_8)?.trim() ?: ""
                }.getOrDefault("")
                val ssParts = schedstatRaw.split(Regex("\\s+"))
                val runningNs  = ssParts.getOrNull(0)?.toLongOrNull() ?: 0L
                val waitingNs  = ssParts.getOrNull(1)?.toLongOrNull() ?: 0L
                val timeslices = ssParts.getOrNull(2)?.toLongOrNull() ?: 0L

                // Read sched (best-effort; may not be accessible on all kernels)
                val schedRaw = runCatching {
                    File(tidDir, "sched").takeIf { it.exists() }
                        ?.readText(Charsets.UTF_8)?.trim() ?: ""
                }.getOrDefault("")

                val schedFields = mutableMapOf<String, Long>()
                schedRaw.lines().forEach { line ->
                    val colon = line.indexOf(':')
                    if (colon <= 0) return@forEach
                    val key = line.substring(0, colon).trim()
                    val valStr = line.substring(colon + 1).trim()
                    schedFields[key] = valStr.toLongOrNull()
                        ?: valStr.toDoubleOrNull()?.toLong()
                        ?: return@forEach
                }

                ThreadSchedInfo(
                    tid = tid,
                    name = name,
                    state = state,
                    schedstatRaw = schedstatRaw,
                    runningNs = runningNs,
                    waitingNs = waitingNs,
                    timeslices = timeslices,
                    waitMax = schedFields["se.statistics.wait_max"] ?: 0L,
                    nrWakeups = schedFields["se.statistics.nr_wakeups"] ?: 0L,
                    nrMigrations = schedFields["se.statistics.nr_migrations"] ?: 0L,
                    nrInvoluntarySwitches = schedFields["nr_involuntary_switches"] ?: 0L,
                    nrVoluntarySwitches = schedFields["nr_voluntary_switches"] ?: 0L,
                    schedRaw = schedRaw
                )
            }.sortedByDescending { it.runningNs }
        }.getOrElse { emptyList() }
    }

    /**
     * All numeric values that matter for scheduler analysis.
     * [raw] is the full /proc/self/sched text for reference.
     * [rawSchedstat] is /proc/self/schedstat (three space-separated values).
     *
     * Units:
     *  - time fields in /proc/self/sched are in nanoseconds (kernel >= 3.0)
     *  - schedstat fields:  <time_running_ns>  <time_waiting_ns>  <nr_timeslices>
     */
    data class SchedAnalysis(
        /** Total scheduler wait time (ns). Key: overall latency budget. */
        val waitSum: Long,
        /** Worst-case single wait (ns). Key: tail latency spike. */
        val waitMax: Long,
        /** Number of times the task waited (scheduling events). */
        val waitCount: Long,
        /** Worst-case single execution burst (ns). */
        val execMax: Long,
        /** Worst-case single scheduling slice (ns). */
        val sliceMax: Long,
        /** Wakeups that were synchronous (caller and callee on the same CPU best latency). */
        val wakeupSync: Long,
        /** Wakeups handled by the local runqueue (low-latency). */
        val wakeupLocal: Long,
        /** Wakeups that required an inter-processor interrupt (adds latency + power). */
        val wakeupRemote: Long,

        /** Total wakeup events. High count = wake-up heavy (battery concern). */
        val nrWakeups: Long,
        /** Total CPU migrations. High count = cache-unfriendly + power overhead. */
        val nrMigrations: Long,
        /** CFS utilisation average [0..1024]. 0=idle, 1024=fully loaded. */
        val utilAvg: Long,
        /** Estimated utilisation (EWMA). Tracks recent trend. */
        val utilEstEwma: Long,

        /** Virtual runtime (ns). Reflects CPU time share vs peers. */
        val vruntime: Long,
        /** Total actual CPU time consumed (ns). */
        val sumExecRuntime: Long,
        /** CFS load weight. Default = 1024 for normal priority. */
        val loadWeight: Long,

        /** Involuntary context switches preempted while runnable. High = CPU contention. */
        val nrInvoluntarySwitches: Long,
        /** Voluntary context switches task yielded (I/O, sleep, etc.). */
        val nrVoluntarySwitches: Long,

        /** Total ns the task was actually on-CPU (from schedstat). */
        val schedstatRunningNs: Long,
        /** Total ns the task was on the runqueue waiting (from schedstat). */
        val schedstatWaitingNs: Long,
        /** Total number of timeslices the task received. */
        val schedstatTimeslices: Long,

        val raw: String,
        val rawSchedstat: String
    ) {
        /** Average time on-CPU per timeslice (ns). -1 if data unavailable. */
        val avgRunPerSliceNs: Long get() =
            if (schedstatTimeslices > 0) schedstatRunningNs / schedstatTimeslices else -1L

        /** Average wait time per scheduling event (ns). -1 if data unavailable. */
        val avgWaitPerEventNs: Long get() =
            if (schedstatTimeslices > 0) schedstatWaitingNs / schedstatTimeslices else -1L

        /** Run/Wait ratio: >1 means mostly running; <1 means mostly waiting. -1 if N/A. */
        val runWaitRatio: Double get() =
            if (schedstatWaitingNs > 0) schedstatRunningNs.toDouble() / schedstatWaitingNs else -1.0

        /** True if the process looks wake-up heavy (>1000 wakeups or >200 remote wakeups). */
        val isWakeupHeavy: Boolean get() = nrWakeups > 1000 || wakeupRemote > 200

        /** True if migrations are frequent relative to wakeups (>20% wakeups cause migration). */
        val migratesOften: Boolean get() =
            nrWakeups > 0 && (nrMigrations.toDouble() / nrWakeups) > 0.20

        /** True if wait_max is notably high likely causes perceptible latency spikes. */
        val hasLatencySpike: Boolean get() = waitMax > 50_000_000L  // > 50 ms

        /** True if involuntary switches are high CPU contention / preemption pressure. */
        val hasPreemptionPressure: Boolean get() = nrInvoluntarySwitches > 500

        /** True if util_avg is high task is consuming significant CPU share. */
        val isHighUtilization: Boolean get() = utilAvg > 768  // > 75 % of 1024
    }

    /**
     * Parses /proc/self/sched and /proc/self/schedstat into a [SchedAnalysis].
     * Always reads live (these values change every millisecond).
     * Returns null only if both files are completely unreadable.
     */
    fun parseSched(): SchedAnalysis? {
        val schedText = runCatching {
            File(SCHED_PATH).takeIf { it.exists() }?.readText(Charsets.UTF_8) ?: ""
        }.getOrDefault("")

        val schedstatText = runCatching {
            File(SCHEDSTAT_PATH).takeIf { it.exists() }?.readText(Charsets.UTF_8)?.trim() ?: ""
        }.getOrDefault("")

        if (schedText.isBlank() && schedstatText.isBlank()) return null

        // Parse key:value lines from /proc/self/sched.
        // Format: "se.statistics.wait_sum : 123456789"
        val fields = mutableMapOf<String, Long>()
        schedText.lines().forEach { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) return@forEach
            val key = line.substring(0, colon).trim()
            val valStr = line.substring(colon + 1).trim()
            // values can be integers or floats (e.g. vruntime uses decimal notation)
            val long = valStr.toLongOrNull()
                ?: valStr.toDoubleOrNull()?.toLong()
                ?: return@forEach
            fields[key] = long
        }

        // Parse /proc/self/schedstat: "<running_ns> <waiting_ns> <timeslices>"
        val schedstatParts = schedstatText.split(Regex("\\s+"))
        val ssRunning   = schedstatParts.getOrNull(0)?.toLongOrNull() ?: 0L
        val ssWaiting   = schedstatParts.getOrNull(1)?.toLongOrNull() ?: 0L
        val ssSlices    = schedstatParts.getOrNull(2)?.toLongOrNull() ?: 0L

        fun f(key: String) = fields[key] ?: 0L

        return SchedAnalysis(
            // Latency
            waitSum            = f("se.statistics.wait_sum"),
            waitMax            = f("se.statistics.wait_max"),
            waitCount          = f("se.statistics.wait_count"),
            execMax            = f("se.statistics.exec_max"),
            sliceMax           = f("se.statistics.slice_max"),
            wakeupSync         = f("se.statistics.nr_wakeups_sync"),
            wakeupLocal        = f("se.statistics.nr_wakeups_local"),
            wakeupRemote       = f("se.statistics.nr_wakeups_remote"),
            // Power / wakeups
            nrWakeups          = f("se.statistics.nr_wakeups"),
            nrMigrations       = f("se.statistics.nr_migrations"),
            utilAvg            = f("se.avg.util_avg"),
            utilEstEwma        = f("se.avg.util_est.ewma"),
            // Fairness
            vruntime           = f("se.vruntime"),
            sumExecRuntime     = f("se.sum_exec_runtime"),
            loadWeight         = f("se.load.weight"),
            // Preemption
            nrInvoluntarySwitches = f("nr_involuntary_switches"),
            nrVoluntarySwitches   = f("nr_voluntary_switches"),
            // schedstat derived
            schedstatRunningNs  = ssRunning,
            schedstatWaitingNs  = ssWaiting,
            schedstatTimeslices = ssSlices,
            // raw
            raw           = schedText,
            rawSchedstat  = schedstatText
        )
    }

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

    private fun readDirAsLines(dirPath: String, title: String): String {
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

    private fun readAuxvHumanReadable(title: String): String {
        return runCatching {
            val file = File(AUXV_PATH)
            if (!file.exists()) return@runCatching "auxv: missing ($AUXV_PATH)"

            // some of the values in AUXV are pointers (e.g. AT_PLATFORM) which are not directly
            // human-readable, but we can at least show the raw hex value and decimal value for
            // reference.
            // 51L to "UNKNOWN(51)" // seen on some devices as a pointer to a string like
            // "com.google.android.runtime"
            // TODO: for known pointer types (e.g. AT_PLATFORM, AT_RANDOM) we could
            // attempt to read the pointed-to string from memory
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
            entries.add(title + "\n")
            while (buffer.remaining() >= wordSize * 2) {
                val type = readWord()
                val value = readWord()
                if (type == 0L) break
                val name = typeNames[type] ?: "UNKNOWN"
                val annotated = "$name($type)=0x${value.toString(16)} ($value)"
                entries.add(annotated)
            }

            if (entries.isEmpty()) "auxv: empty" else entries.joinToString(separator = "\n")
        }.getOrElse { err -> "auxv: error: ${err.message ?: err::class.java.simpleName}" }
    }
}
