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
import android.database.Cursor
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.celzero.bravedns.database.ConsoleLogDAO
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LogExportWorker(context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams), KoinComponent {

    private val consoleLogDao by inject<ConsoleLogDAO>()

    companion object {
        private const val QUERY = "SELECT * FROM ConsoleLog order by id"
    }

    override suspend fun doWork(): Result {
        return try {
            val filePath = inputData.getString("filePath") ?: return Result.failure()
            Logger.i(LOG_TAG_BUG_REPORT, "Exporting logs to $filePath")
            exportLogsToCsvStream(filePath)
            Result.success()
        } catch (e: Exception) {
            Result.failure()
        }
    }

    private fun exportLogsToCsvStream(filePath: String): Boolean {
        var cursor: Cursor? = null
        try {
            val query = SimpleSQLiteQuery(QUERY)
            cursor = consoleLogDao.getLogsCursor(query)

            val file = File(filePath)
            if (file.exists()) {
                Logger.v(LOG_TAG_BUG_REPORT, "Deleting existing zip file, ${file.absolutePath}")
                file.delete()
            }

            val stringBuilder = StringBuilder()
            cursor.let {
                if (it.moveToFirst()) {
                    do {
                        val timestamp = it.getLong(it.getColumnIndexOrThrow("timestamp"))
                        val message = it.getString(it.getColumnIndexOrThrow("message"))
                        stringBuilder.append("$timestamp,$message\n")
                    } while (it.moveToNext())
                }
            }

            ZipOutputStream(BufferedOutputStream(FileOutputStream(filePath))).use { zos ->
                val zipEntry = ZipEntry("log_${System.currentTimeMillis()}.txt")
                zos.putNextEntry(zipEntry)
                zos.write(stringBuilder.toString().toByteArray())
                zos.closeEntry()
            }

            Logger.i(LOG_TAG_BUG_REPORT, "Logs exported to ${file.absolutePath}")
            return true
        } catch (e: Exception) {
            Logger.e(LOG_TAG_BUG_REPORT, "Error exporting logs", e)
        } finally {
            cursor?.close()
        }
        return false
    }
}
