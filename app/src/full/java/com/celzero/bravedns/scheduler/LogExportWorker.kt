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
package com.celzero.bravedns.scheduler

import Logger
import Logger.LOG_TAG_BUG_REPORT
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.celzero.bravedns.database.ConsoleLogDAO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LogExportWorker(context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams), KoinComponent {

    private val consoleLogDao by inject<ConsoleLogDAO>()

    companion object {
        private const val CHUNK_SIZE = 5_000
    }

    override suspend fun doWork(): Result {
        return try {
            val filePath = inputData.getString("filePath") ?: return Result.failure()
            Logger.i(LOG_TAG_BUG_REPORT, "Exporting logs to $filePath")
            val ok = withContext(Dispatchers.IO) { exportLogsChunked(filePath) }
            if (ok) Result.success() else Result.failure()
        } catch (e: Exception) {
            Logger.e(LOG_TAG_BUG_REPORT, "doWork failed: ${e.message}", e)
            Result.failure()
        }
    }

    /**
     * Streams log rows from the DB in [CHUNK_SIZE] batches directly into the ZipOutputStream.
     * This avoids building a single giant StringBuilder in memory, which previously caused
     * GC pauses / OOM when log tables held millions of rows.
     */
    private fun exportLogsChunked(filePath: String): Boolean {
        return try {
            val file = File(filePath)

            // ensure the parent directory exists before creating the file.
            file.parentFile?.mkdirs()

            if (file.exists()) {
                Logger.v(LOG_TAG_BUG_REPORT, "Deleting existing zip file: ${file.absolutePath}")
                // log a warning if deletion fails so it is observable.
                if (!file.delete()) {
                    Logger.w(LOG_TAG_BUG_REPORT, "Failed to delete existing file: ${file.absolutePath}")
                }
            }

            ZipOutputStream(BufferedOutputStream(FileOutputStream(file), 128 * 1024)).use { zos ->
                val zipEntry = ZipEntry("log_${System.currentTimeMillis()}.txt")
                zos.putNextEntry(zipEntry)

                val writer = BufferedWriter(OutputStreamWriter(zos, Charsets.UTF_8), 64 * 1024)
                var offset = 0
                var totalRows = 0

                while (true) {
                    val chunk = consoleLogDao.getLogsChunked(CHUNK_SIZE, offset)
                    if (chunk.isEmpty()) break

                    for (log in chunk) {
                        val safeMessage = log.message.replace("\n", "\\n").replace("\r", "\\r")
                        writer.write(log.timestamp.toString())
                        writer.write(",")
                        writer.write(log.level.toString())
                        writer.write(",")
                        writer.write(safeMessage)
                        writer.newLine()
                    }
                    // Flush writer periodically so zos can compress incrementally
                    writer.flush()

                    totalRows += chunk.size
                    offset += chunk.size

                    // Exit early if last chunk was smaller than requested (no more rows)
                    if (chunk.size < CHUNK_SIZE) break
                }

                writer.flush()
                zos.closeEntry()
                Logger.i(LOG_TAG_BUG_REPORT, "Exported $totalRows rows to ${file.absolutePath}")
            }
            true
        } catch (e: Exception) {
            Logger.e(LOG_TAG_BUG_REPORT, "Error exporting logs: ${e.message}", e)
            false
        }
    }
}
