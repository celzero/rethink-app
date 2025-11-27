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
import android.os.Debug
import java.text.DecimalFormat
import kotlin.math.log10
import kotlin.math.pow

object MemoryUtils {

    data class MemoryStats(
        val availMem: String, // Available memory in the system
        val threshold: String, // Threshold for low memory warning
        val usedMemPercent: Double, // Percentage of used memory
        val thresholdPercent: Double, // Percentage threshold for low memory warning
        val largeMemoryClassMB: Int, // Large memory class of the device
        val memoryClassMB: Int, // Normal memory class of the device

        // --- App Java Heap (Virtual Machine) ---
        val appHeapUsed: String,       // Actual objects in memory
        val appHeapAllocated: String,  // Total size of current heap (used + free inside heap)
        val appHeapMax: String,        // Hard limit before OOM

        // --- App Physical RAM (PSS Breakdown) ---
        val appTotalPss: String,       // Total Physical RAM used by app (The main "RAM Usage" number)
        val appNativeRamUsed: String,  // C++ / Native (often Bitmaps on Android 8+)
        val appGraphicsRamUsed: String,// OpenGL, Textures, SurfaceFlinger
        val appCodeRamUsed: String,    // .so, .jar, .apk, .dex code memory
        val appStackRamUsed: String,   // Thread stacks
        val appUnknownRamUsed: String, // Other/Unknown (Private Other)

        // --- System Wide Stats ---
        val systemTotalRam: String,
        val systemAvailableRam: String,
        val isLowMemory: Boolean,

        val coreCount: Int
    )

    /**
     * Returns detailed memory statistics.
     */
    fun getDetailedMemoryInfo(context: Context): MemoryStats {
        // 1. System-wide Memory Info
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val systemMemInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(systemMemInfo)

        // mem info values are in bytes
        val totalMemBytes = systemMemInfo.totalMem
        val availMemBytes = systemMemInfo.availMem
        val thresholdBytes = systemMemInfo.threshold
        val usedMemBytes = totalMemBytes - availMemBytes
        val usedMemPercent = (usedMemBytes.toDouble() / totalMemBytes.toDouble()) * 100.0
        val thresholdPercent = (thresholdBytes.toDouble() / totalMemBytes.toDouble()) * 100.0
        val largeMemoryClassMB = activityManager.largeMemoryClass
        val memoryClassMB = activityManager.memoryClass

        // 2. App-specific Physical RAM (PSS)
        val debugMemInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(debugMemInfo)

        val totalPssBytes = debugMemInfo.totalPss * 1024L

        // "summary.native-heap" etc return values in KB
        val nativePssBytes = (debugMemInfo.getMemoryStat("summary.native-heap")?.toLongOrNull() ?: 0L) * 1024
        val graphicsPssBytes = (debugMemInfo.getMemoryStat("summary.graphics")?.toLongOrNull() ?: 0L) * 1024
        val codePssBytes = (debugMemInfo.getMemoryStat("summary.code")?.toLongOrNull() ?: 0L) * 1024
        val stackPssBytes = (debugMemInfo.getMemoryStat("summary.stack")?.toLongOrNull() ?: 0L) * 1024
        val unknownPssBytes = (debugMemInfo.getMemoryStat("summary.private-other")?.toLongOrNull() ?: 0L) * 1024

        // 3. App-specific Java Heap
        val runtime = Runtime.getRuntime()
        val heapTotal = runtime.totalMemory() // Allocated size
        val heapFree = runtime.freeMemory()
        val heapUsed = heapTotal - heapFree
        val heapMax = runtime.maxMemory()

        val cores = runtime.availableProcessors()

        return MemoryStats(
            availMem = formatBytes(availMemBytes),
            threshold = formatBytes(thresholdBytes),
            usedMemPercent = usedMemPercent,
            thresholdPercent = thresholdPercent,
            largeMemoryClassMB = largeMemoryClassMB,
            memoryClassMB = memoryClassMB,

            // --- App Java Heap (Virtual Memory) ---
            appHeapUsed = formatBytes(heapUsed),
            appHeapAllocated = formatBytes(heapTotal),
            appHeapMax = formatBytes(heapMax),

            appTotalPss = formatBytes(totalPssBytes),
            appNativeRamUsed = formatBytes(nativePssBytes),
            appGraphicsRamUsed = formatBytes(graphicsPssBytes),
            appCodeRamUsed = formatBytes(codePssBytes),
            appStackRamUsed = formatBytes(stackPssBytes),
            appUnknownRamUsed = formatBytes(unknownPssBytes),

            systemTotalRam = formatBytes(systemMemInfo.totalMem),
            systemAvailableRam = formatBytes(systemMemInfo.availMem),
            isLowMemory = systemMemInfo.lowMemory,
            coreCount = cores
        )
    }

    fun getMemoryStats(context: Context): String {
        val stats = getDetailedMemoryInfo(context)
        val sb = StringBuilder()
        sb.appendLine("\nMem info:")
        sb.appendLine("   Available Memory: ${stats.availMem}")
        sb.appendLine("   Threshold Memory: ${stats.threshold}")
        sb.appendLine("   Used Memory: ${"%.2f".format(stats.usedMemPercent)}%")
        sb.appendLine("   Threshold Percent: ${"%.2f".format(stats.thresholdPercent)}%")
        sb.appendLine("   Large Memory Class: ${stats.largeMemoryClassMB} MB")
        sb.appendLine("   Memory Class: ${stats.memoryClassMB} MB")
        sb.appendLine("App (Physical RAM/PSS):")
        sb.appendLine("   Total Used: ${stats.appTotalPss}")
        sb.appendLine("   Native: ${stats.appNativeRamUsed}")
        sb.appendLine("   Graphics: ${stats.appGraphicsRamUsed}")
        sb.appendLine("   Code: ${stats.appCodeRamUsed}")
        sb.appendLine("   Stack/Other: ${stats.appUnknownRamUsed}")
        sb.appendLine("Java Heap (Virtual Memory):")
        sb.appendLine("   Used: ${stats.appHeapUsed}")
        sb.appendLine("   Allocated: ${stats.appHeapAllocated}")
        sb.appendLine("   Max Limit: ${stats.appHeapMax}")
        sb.appendLine("System Status:")
        sb.appendLine("   RAM Free: ${stats.systemAvailableRam} / ${stats.systemTotalRam}")
        sb.appendLine("   Is Low Memory: ${stats.isLowMemory}")
        sb.appendLine("   CPU Cores: ${stats.coreCount}")
        return sb.toString()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt()
        return DecimalFormat("#,##0.#").format(bytes / 1024.0.pow(digitGroups.toDouble())) + " " + units[digitGroups]
    }
}
