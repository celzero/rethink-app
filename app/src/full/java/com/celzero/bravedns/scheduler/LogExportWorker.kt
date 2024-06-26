package com.celzero.bravedns.scheduler

import Logger
import Logger.LOG_TAG_BUG_REPORT
import android.content.Context
import android.database.Cursor
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.celzero.bravedns.database.ConsoleLogDAO
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.BufferedOutputStream
import java.util.zip.ZipFile

class LogExportWorker(context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams), KoinComponent {

    private val consoleLogDao by inject<ConsoleLogDAO>()

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
            val query = SimpleSQLiteQuery("SELECT * FROM ConsoleLog order by id")
            cursor = consoleLogDao.getLogsCursor(query)

            val file = File(filePath)
            if (file.exists()) {
                Logger.v(LOG_TAG_BUG_REPORT, "Deleting existing zip file, ${file.absolutePath}")
                file.delete()
            }

            ZipOutputStream(BufferedOutputStream(FileOutputStream(filePath))).use { zos ->
                val zipEntry = ZipEntry("log_${System.currentTimeMillis()}.txt")
                zos.putNextEntry(zipEntry)
                if (cursor.moveToFirst()) {
                    do {
                        val timestamp =
                            cursor.getLong(cursor.getColumnIndexOrThrow("timestamp"))
                        val message =
                            cursor.getString(cursor.getColumnIndexOrThrow("message"))
                        val logEntry = "$timestamp,$message\n"
                        zos.write(logEntry.toByteArray())
                    } while (cursor.moveToNext())
                }
                zos.closeEntry()
            }

            Logger.i(LOG_TAG_BUG_REPORT, "Logs exported to ${file.absolutePath}")
            return true
        } catch (e: Exception) {
            Logger.e(LOG_TAG_BUG_REPORT, "Error exporting logs", e)
        } finally{
            cursor?.close()
        }
        return false
    }
}
