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
package com.celzero.bravedns.battery

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Periodically writes formatted battery stats (from [BatteryStatsProvider]) to a single
 * rotating file under: <filesDir>/data/battery/battery_<timestamp>.txt
 *
 * - Keeps only one file at any point in time.
 * - Ensures file size never exceeds 1 MB; rolls over (deletes old, creates new) before exceeding.
 */
object BatteryStatsLogger {
    private const val MAX_BYTES: Long = 1024 * 1024 // 1 MB
    private const val LOG_INTERVAL_MINUTES: Long = 5 // adjust as needed

    private val started = AtomicBoolean(false)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "battery-stats-logger").apply { isDaemon = true }
    }
    private var scheduledTask: ScheduledFuture<*>? = null
    @Volatile private var logFile: File? = null

    private val tsFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun start(context: Context) {
        if (started.get()) return
        synchronized(this) {
            if (started.get()) return
            try {
                BatteryStatsProvider.init(context.applicationContext)
                prepareLogFile(context)
                scheduledTask = scheduler.scheduleWithFixedDelay({
                    try {
                        writeOnce("start")
                    } catch (_: Throwable) {
                        // ignore individual write errors
                    }
                }, 1, LOG_INTERVAL_MINUTES * 60, TimeUnit.SECONDS)
                started.set(true)
            } catch (_: Throwable) {
                // best effort only
            }
        }
    }

    private fun prepareLogFile(context: Context) {
        val dir = File(File(context.filesDir, "data"), "battery")
        if (!dir.exists()) dir.mkdirs()
        // Remove any stray older files (keep none as per requirement)
        dir.listFiles { f -> f.isFile && f.name.startsWith("battery_") && f.name.endsWith(".txt") }?.forEach { it.delete() }
        logFile = File(dir, fileName())
    }

    private fun fileName(): String = "battery_${System.currentTimeMillis()}.txt"

    private fun forceNewFile() {
        val current = logFile
        try { current?.delete() } catch (_: Throwable) {}
        val parent = current?.parentFile ?: return
        logFile = File(parent, fileName())
    }

    private fun rolloverIfNeeded() {
        val f = logFile ?: return
        if (f.length() >= MAX_BYTES) {
            forceNewFile()
        }
    }

    fun writeOnce(snapshot: String) {
        val f = logFile ?: return
        rolloverIfNeeded() // handle case where previous write filled file exactly
        val nowIso = tsFormatter.format(Date())
        val entry = buildString {
            appendLine("==== $nowIso ====")
            appendLine(snapshot.trimEnd())
            appendLine()
        }
        val bytesNeeded = entry.toByteArray().size.toLong()
        // If writing this entry would exceed limit, start a new file first.
        if (f.length() + bytesNeeded > MAX_BYTES) {
            forceNewFile()
        }
        try {
            FileWriter(logFile!!, true).use { it.write(entry) }
        } catch (_: Throwable) {
            // ignore
        }
    }

    fun readLogFile(): String? {
        val f = logFile ?: return null
        return try {
            f.readText()
        } catch (e: Throwable) {
            null
        }
    }
}
