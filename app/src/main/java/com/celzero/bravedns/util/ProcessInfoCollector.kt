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

import android.content.Context
import android.os.SystemClock
import com.celzero.bravedns.net.go.GoVpnAdapter
import com.celzero.bravedns.util.Logger.LOG_TAG_BUG_REPORT
import com.celzero.bravedns.util.UIUtils.formatNetMetrics

/**
 * Collects the *complete* process / memory / thread snapshot for attachment to
 * bug-report emails. Mirrors the data shown in AboutFragment's "Proc" and
 * "Stacktrace" dialogs:
 *
 *  - THREADS      : per-thread scheduler data via [KernelProc.parseSchedAllThreads]
 *  - STATUS       : /proc/self/status via [KernelProc.getStatus]
 *  - SMAPS        : /proc/self/smaps_rollup via [KernelProc.getSmaps]
 *  - AUXV         : /proc/self/auxv via [KernelProc.getStats]
 *  - METRICS      : [MemoryUtils.getMemoryStats] + Go net metrics
 *  - JVM STACK    : full frames of every live JVM thread
 *  - GO STACK     : goroutine stacks via [GoVpnAdapter.printStack]
 *
 * Every section is independently guarded: a failure in one section degrades
 * that section to an error note instead of failing the whole report.
 */
object ProcessInfoCollector {

    private const val TAG = "ProcInfoCollector"

    suspend fun collect(context: Context): String {
        val heavy = "===========================================================\n"
        val light = "-----------------------------------------------------------\n"
        val sb = StringBuilder()
        val now =
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", java.util.Locale.getDefault())
                .format(java.util.Date())

        sb.append(heavy)
        sb.append("  RETHINK PROCESS / MEMORY / THREAD SNAPSHOT\n")
        sb.append(heavy)
        sb.append("Generated : $now\n")
        sb.append("pid       : ${android.os.Process.myPid()}\n")
        sb.append("package   : ${context.packageName}\n")
        sb.append("uptime    : ${formatElapsed(SystemClock.elapsedRealtime())}\n")
        sb.append("\n")

        // -- what About > Proc shows -----------------------------------------
        sb.append(light)
        sb.append("=== PROC / MEM ===\n")
        sb.append(light)
        sb.append(threadsSection())
        sb.append("\n")
        sb.append(procSection("STATUS  (/proc/self/status)") { KernelProc.getStatus(forceRefresh = true) })
        sb.append("\n")
        sb.append(procSection("SMAPS  (/proc/self/smaps_rollup)") { KernelProc.getSmaps(forceRefresh = true) })
        sb.append("\n")
        sb.append(procSection("AUXV  (/proc/self/auxv)") { KernelProc.getStats(forceRefresh = true) })
        sb.append("\n")

        sb.append(light)
        sb.append("=== METRICS ===\n")
        sb.append(light)
        sb.append("Memory Metrics\n")
        sb.append(memorySection(context))
        sb.append("\n")
        sb.append(goNetMetricsSection())
        sb.append("\n")

        // -- what About > Stacktrace shows -----------------------------------
        sb.append(light)
        sb.append("=== JVM STACK ===\n")
        sb.append(light)
        sb.append(jvmStackSection())
        sb.append("\n")

        sb.append(light)
        sb.append("=== GO STACK ===\n")
        sb.append(light)
        sb.append(goStackSection())
        sb.append("\n")

        sb.append(heavy)
        sb.append("  END OF SNAPSHOT\n")
        sb.append(heavy)

        return sb.toString()
    }

    private inline fun procSection(title: String, read: () -> String): String {
        return try {
            "$title\n${read()}\n"
        } catch (e: Exception) {
            Logger.w(LOG_TAG_BUG_REPORT, "$TAG $title error: ${e.message}", e)
            "$title: error (${e.message})\n"
        }
    }

    /** Per-thread scheduler data; same content as About > Proc, plain-text. */
    private fun threadsSection(): String {
        return try {
            val threads = KernelProc.parseSchedAllThreads()
            if (threads.isEmpty()) {
                return "/proc/self/task not available or empty\n"
            }
            buildString {
                append("THREADS (${threads.size} total)\n\n")
                threads.forEach { t ->
                    append("${t.tid}  [${t.name}]  ${t.state}\n")

                    val hasSchedstat = t.timeslices > 0 || t.runningNs > 0
                    if (hasSchedstat) {
                        append("  run=${fmtNs(t.runningNs)}  wait=${fmtNs(t.waitingNs)}" +
                                "  slices=${fmtNum(t.timeslices)}\n")
                    }

                    val hasSchedFields = t.waitMax > 0 || t.nrWakeups > 0 ||
                            t.nrInvoluntarySwitches > 0 || t.nrVoluntarySwitches > 0
                    if (hasSchedFields) {
                        val line = StringBuilder("  ")
                        if (t.waitMax > 0)               line.append("wait_max=${fmtNs(t.waitMax)}  ")
                        if (t.nrWakeups > 0)             line.append("wakeups=${fmtNum(t.nrWakeups)}  ")
                        if (t.nrMigrations > 0)          line.append("mig=${fmtNum(t.nrMigrations)}  ")
                        if (t.nrInvoluntarySwitches > 0) line.append("inv_sw=${fmtNum(t.nrInvoluntarySwitches)}  ")
                        if (t.nrVoluntarySwitches > 0)   line.append("vol_sw=${fmtNum(t.nrVoluntarySwitches)}")
                        append(line.toString().trimEnd()).append("\n")
                    }

                    if (t.schedstatRaw.isNotBlank()) {
                        append("  schedstat: ${t.schedstatRaw}\n")
                    }
                    append("\n")
                }
            }
        } catch (e: Exception) {
            Logger.w(LOG_TAG_BUG_REPORT, "$TAG threads error: ${e.message}", e)
            "threads: error (${e.message})\n"
        }
    }

    /** Full detailed memory stats; same as About > Proc > Metrics tab. */
    private fun memorySection(context: Context): String {
        return try {
            MemoryUtils.getMemoryStats(context)
        } catch (e: Exception) {
            Logger.w(LOG_TAG_BUG_REPORT, "$TAG memory error: ${e.message}", e)
            "memory: error (${e.message})\n"
        }
    }

    /** Go network metrics; same as About > Proc > Metrics tab. */
    private fun goNetMetricsSection(): String {
        return try {
            formatNetMetrics(GoVpnAdapter.getGoMetrics()).orEmpty().ifEmpty { "go metrics: not available\n" }
        } catch (e: Exception) {
            Logger.w(LOG_TAG_BUG_REPORT, "$TAG go metrics error: ${e.message}", e)
            "go metrics: error (${e.message})\n"
        }
    }

    /** Full stack frames of every live JVM thread; same as About > Stacktrace. */
    private fun jvmStackSection(): String {
        return try {
            buildString {
                Thread.getAllStackTraces().entries
                    .sortedBy { it.key.name }
                    .forEach { (thread, frames) ->
                        appendLine(
                            "Thread: ${thread.name}" +
                                    "  [id=${thread.id}" +
                                    "  state=${thread.state}" +
                                    "  daemon=${thread.isDaemon}" +
                                    "  priority=${thread.priority}]"
                        )
                        if (frames.isEmpty()) {
                            appendLine("  (no stack frames)")
                        } else {
                            frames.forEach { frame -> appendLine("  at $frame") }
                        }
                        appendLine()
                    }
            }.ifBlank { "jvm stack: not available\n" }
        } catch (e: Exception) {
            Logger.w(LOG_TAG_BUG_REPORT, "$TAG jvm stack error: ${e.message}", e)
            "jvm stack: error (${e.message})\n"
        }
    }

    /** Goroutine stacks via the Go bridge; same as About > Stacktrace. */
    private suspend fun goStackSection(): String {
        return try {
            GoVpnAdapter.printStack().ifBlank { "go stack: not available\n" }
        } catch (e: Exception) {
            Logger.w(LOG_TAG_BUG_REPORT, "$TAG go stack error: ${e.message}", e)
            "go stack: error (${e.message})\n"
        }
    }

    /** Format nanoseconds into a human-readable string (mirrors AboutFragment.fmtNs). */
    private fun fmtNs(ns: Long): String = when {
        ns <= 0 -> "-"
        ns < 1_000L -> "$ns ns"
        ns < 1_000_000L -> "${"%.1f".format(ns / 1_000.0)} µs"
        ns < 1_000_000_000L -> "${"%.2f".format(ns / 1_000_000.0)} ms"
        else -> "${"%.3f".format(ns / 1_000_000_000.0)} s"
    }

    /** Format a long counter; returns "-" for non-positive values (mirrors AboutFragment.fmtNum). */
    private fun fmtNum(v: Long): String = if (v <= 0) "-" else "%,d".format(v)

    private fun formatElapsed(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return String.format(java.util.Locale.US, "%02d:%02d:%02d", h, m, s)
    }
}
