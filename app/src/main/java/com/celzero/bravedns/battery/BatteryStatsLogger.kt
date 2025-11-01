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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.Dispatcher
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Periodically writes formatted battery stats (from [BatteryStatsProvider]) to a single
 * rotating file under: <filesDir>/data/battery/battery_<timestamp>.txt
 *
 * - Keeps only one file at any point in time.
 * - Ensures file size never exceeds 256 KB; rolls over (deletes old, creates new) before exceeding.
 */
object BatteryStatsLogger {
    private const val MAX_BYTES: Long = 256 * 1024 // 256 KB

    private val started = AtomicBoolean(false)

    private const val BATTERY_FOLDER = "battery"
    private const val BATTERY_FILE_PREFIX = "battery_"
    private const val BATTERY_FILE_SUFFIX = ".txt"

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
                started.set(true)
            } catch (_: Throwable) {
                // best effort only
            }
        }
    }

    private fun prepareLogFile(context: Context) {
        val dir = File(File(context.filesDir, "data"), BATTERY_FOLDER)
        if (!dir.exists()) dir.mkdirs()
        // Remove any stray older files (keep none as per requirement)
        dir.listFiles { f -> f.isFile && f.name.startsWith(BATTERY_FILE_PREFIX) && f.name.endsWith(BATTERY_FILE_SUFFIX) }?.forEach { it.delete() }
        logFile = File(dir, fileName())
    }

    private fun fileName(): String = "$BATTERY_FILE_PREFIX${System.currentTimeMillis()}$BATTERY_FILE_SUFFIX"

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

    fun writeOnce(snapshot: String): Job = CoroutineScope(Dispatchers.IO).launch{
        val f = logFile ?: return@launch

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
        } catch (_: Throwable) {
            null
        }
    }
}
