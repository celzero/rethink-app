/*
 * Copyright 2025 RethinkDNS and its authors
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

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import java.text.DecimalFormat
import kotlin.math.log10
import kotlin.math.pow

object MemoryUtils {

    private const val BYTES_TO_KB = 1024L
    private const val MEMORY_UNIT_BASE = 1024.0

    data class MemoryStats(
        // ActivityManager.MemoryInfo
        val totalMem: String,
        val availMem: String,
        val threshold: String,
        val lowMemory: Boolean,

        // Debug.MemoryInfo direct fields
        val totalPss: String,
        val totalPrivateDirty: String,
        val totalSharedDirty: String,
        val totalPrivateClean: String,
        val totalSharedClean: String,
        val totalSwappablePss: String,

        val dalvikPss: String,
        val dalvikPrivateDirty: String,
        val dalvikSharedDirty: String,

        val nativePss: String,
        val nativePrivateDirty: String,
        val nativeSharedDirty: String,

        val otherPss: String,
        val otherPrivateDirty: String,
        val otherSharedDirty: String,

        // Debug.MemoryInfo.getMemoryStat() summary keys
        val summaryJavaHeap: String,
        val summaryNativeHeap: String,
        val summaryCode: String,
        val summaryStack: String,
        val summaryGraphics: String,
        val summaryPrivateOther: String,
        val summarySystem: String,
        val summaryTotalPss: String,
        val summaryTotalSwap: String,
        val summaryPrivateDirty: String,
        val summaryPrivateClean: String,
        val summarySwapPss: String,
        val summaryHeapSize: String,
        val summaryHeapAlloc: String,
        val summaryHeapFree: String,

        // Runtime
        val javaHeapUsed: String,
        val javaHeapAllocated: String,
        val javaHeapMax: String,

        val largeMemoryClassMB: Int,
        val memoryClassMB: Int,
        val coreCount: Int
    )

    /**
     * Returns detailed memory statistics.
     */
    fun getDetailedMemoryInfo(context: Context): MemoryStats {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val systemMemInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(systemMemInfo)

        val debugMemInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(debugMemInfo)

        fun summaryStat(key: String): Long =
            (debugMemInfo.getMemoryStat(key)?.toLongOrNull() ?: 0L) * BYTES_TO_KB

        val runtime = Runtime.getRuntime()
        val heapTotal = runtime.totalMemory()
        val heapFree = runtime.freeMemory()

        val totalSwappablePss = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            debugMemInfo.totalSwappablePss * BYTES_TO_KB
        } else {
            0L
        }

        return MemoryStats(
            totalMem = formatBytes(systemMemInfo.totalMem),
            availMem = formatBytes(systemMemInfo.availMem),
            threshold = formatBytes(systemMemInfo.threshold),
            lowMemory = systemMemInfo.lowMemory,

            totalPss = formatBytes(debugMemInfo.totalPss * BYTES_TO_KB),
            totalPrivateDirty = formatBytes(debugMemInfo.totalPrivateDirty * BYTES_TO_KB),
            totalSharedDirty = formatBytes(debugMemInfo.totalSharedDirty * BYTES_TO_KB),
            totalPrivateClean = formatBytes(debugMemInfo.totalPrivateClean * BYTES_TO_KB),
            totalSharedClean = formatBytes(debugMemInfo.totalSharedClean * BYTES_TO_KB),
            totalSwappablePss = formatBytes(totalSwappablePss),

            dalvikPss = formatBytes(debugMemInfo.dalvikPss * BYTES_TO_KB),
            dalvikPrivateDirty = formatBytes(debugMemInfo.dalvikPrivateDirty * BYTES_TO_KB),
            dalvikSharedDirty = formatBytes(debugMemInfo.dalvikSharedDirty * BYTES_TO_KB),

            nativePss = formatBytes(debugMemInfo.nativePss * BYTES_TO_KB),
            nativePrivateDirty = formatBytes(debugMemInfo.nativePrivateDirty * BYTES_TO_KB),
            nativeSharedDirty = formatBytes(debugMemInfo.nativeSharedDirty * BYTES_TO_KB),

            otherPss = formatBytes(debugMemInfo.otherPss * BYTES_TO_KB),
            otherPrivateDirty = formatBytes(debugMemInfo.otherPrivateDirty * BYTES_TO_KB),
            otherSharedDirty = formatBytes(debugMemInfo.otherSharedDirty * BYTES_TO_KB),

            summaryJavaHeap = formatBytes(summaryStat("summary.java-heap")),
            summaryNativeHeap = formatBytes(summaryStat("summary.native-heap")),
            summaryCode = formatBytes(summaryStat("summary.code")),
            summaryStack = formatBytes(summaryStat("summary.stack")),
            summaryGraphics = formatBytes(summaryStat("summary.graphics")),
            summaryPrivateOther = formatBytes(summaryStat("summary.private-other")),
            summarySystem = formatBytes(summaryStat("summary.system")),
            summaryTotalPss = formatBytes(summaryStat("summary.total-pss")),
            summaryTotalSwap = formatBytes(summaryStat("summary.total-swap")),
            summaryPrivateDirty = formatBytes(summaryStat("summary.private-dirty")),
            summaryPrivateClean = formatBytes(summaryStat("summary.private-clean")),
            summarySwapPss = formatBytes(summaryStat("summary.swap-pss")),
            summaryHeapSize = formatBytes(summaryStat("summary.heap-size")),
            summaryHeapAlloc = formatBytes(summaryStat("summary.heap-alloc")),
            summaryHeapFree = formatBytes(summaryStat("summary.heap-free")),

            javaHeapUsed = formatBytes(heapTotal - heapFree),
            javaHeapAllocated = formatBytes(heapTotal),
            javaHeapMax = formatBytes(runtime.maxMemory()),

            largeMemoryClassMB = activityManager.largeMemoryClass,
            memoryClassMB = activityManager.memoryClass,
            coreCount = runtime.availableProcessors()
        )
    }

    fun getMemoryStats(context: Context): String {
        val stats = getDetailedMemoryInfo(context)
        val sb = StringBuilder()
        sb.appendLine("\nMem info:")
        sb.appendLine("   totalMem: ${stats.totalMem}")
        sb.appendLine("   availMem: ${stats.availMem}")
        sb.appendLine("   threshold: ${stats.threshold}")
        sb.appendLine("   lowMemory: ${stats.lowMemory}")
        sb.appendLine("   largeMemoryClass: ${stats.largeMemoryClassMB} MB")
        sb.appendLine("   memoryClass: ${stats.memoryClassMB} MB")
        sb.appendLine("App (Debug.MemoryInfo):")
        sb.appendLine("   totalPss: ${stats.totalPss}")
        sb.appendLine("   totalPrivateDirty: ${stats.totalPrivateDirty}")
        sb.appendLine("   totalSharedDirty: ${stats.totalSharedDirty}")
        sb.appendLine("   totalPrivateClean: ${stats.totalPrivateClean}")
        sb.appendLine("   totalSharedClean: ${stats.totalSharedClean}")
        sb.appendLine("   totalSwappablePss: ${stats.totalSwappablePss}")
        sb.appendLine("   dalvikPss: ${stats.dalvikPss}")
        sb.appendLine("   dalvikPrivateDirty: ${stats.dalvikPrivateDirty}")
        sb.appendLine("   dalvikSharedDirty: ${stats.dalvikSharedDirty}")
        sb.appendLine("   nativePss: ${stats.nativePss}")
        sb.appendLine("   nativePrivateDirty: ${stats.nativePrivateDirty}")
        sb.appendLine("   nativeSharedDirty: ${stats.nativeSharedDirty}")
        sb.appendLine("   otherPss: ${stats.otherPss}")
        sb.appendLine("   otherPrivateDirty: ${stats.otherPrivateDirty}")
        sb.appendLine("   otherSharedDirty: ${stats.otherSharedDirty}")
        sb.appendLine("   summary.java-heap: ${stats.summaryJavaHeap}")
        sb.appendLine("   summary.native-heap: ${stats.summaryNativeHeap}")
        sb.appendLine("   summary.code: ${stats.summaryCode}")
        sb.appendLine("   summary.stack: ${stats.summaryStack}")
        sb.appendLine("   summary.graphics: ${stats.summaryGraphics}")
        sb.appendLine("   summary.private-other: ${stats.summaryPrivateOther}")
        sb.appendLine("   summary.system: ${stats.summarySystem}")
        sb.appendLine("   summary.total-pss: ${stats.summaryTotalPss}")
        sb.appendLine("   summary.total-swap: ${stats.summaryTotalSwap}")
        sb.appendLine("   summary.private-dirty: ${stats.summaryPrivateDirty}")
        sb.appendLine("   summary.private-clean: ${stats.summaryPrivateClean}")
        sb.appendLine("   summary.swap-pss: ${stats.summarySwapPss}")
        sb.appendLine("   summary.heap-size: ${stats.summaryHeapSize}")
        sb.appendLine("   summary.heap-alloc: ${stats.summaryHeapAlloc}")
        sb.appendLine("   summary.heap-free: ${stats.summaryHeapFree}")
        sb.appendLine("Java Heap (Runtime):")
        sb.appendLine("   used: ${stats.javaHeapUsed}")
        sb.appendLine("   allocated: ${stats.javaHeapAllocated}")
        sb.appendLine("   max: ${stats.javaHeapMax}")
        sb.appendLine("   cores: ${stats.coreCount}\n")
        return sb.toString()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = kotlin.math.min((log10(bytes.toDouble()) / log10(MEMORY_UNIT_BASE)).toInt(), units.size - 1)
        return DecimalFormat("#,##0.#").format(bytes / MEMORY_UNIT_BASE.pow(digitGroups.toDouble())) + " " + units[digitGroups]
    }
}