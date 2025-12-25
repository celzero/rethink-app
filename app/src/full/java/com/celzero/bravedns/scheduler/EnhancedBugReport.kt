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

import Logger.LOG_TAG_BUG_REPORT
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Utilities
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipOutputStream

object EnhancedBugReport {

    const val TOMBSTONE_DIR_NAME = "tombstone"
    private const val TOMBSTONE_FILE_NAME = "tombstone_"
    private const val MAX_TOMBSTONE_FILES = 20 // maximum files allowed as part of tombstone dir
    private const val FILE_EXTENSION = ".txt"
    const val TOMBSTONE_ZIP_FILE_NAME = "rethinkdns.tombstone.zip"

    @RequiresApi(Build.VERSION_CODES.O)
    fun addLogsToZipFile(context: Context) {
        try {
            // create a new zip file named rethinkdns.tombstone.zip
            // add the logs to the zip file
            // close the zip file
            val zipFilePath = File(context.filesDir.canonicalPath + File.separator + TOMBSTONE_ZIP_FILE_NAME)
            Log.d(LOG_TAG_BUG_REPORT, "zip file path: $zipFilePath")
            ZipOutputStream(FileOutputStream(zipFilePath)).use { zipOutputStream ->
                val folder = getFolderPath(context.filesDir) ?: return
                val files = File(folder).listFiles() ?: return

                Log.d(LOG_TAG_BUG_REPORT, "files to add to zip: ${files.size}")
                files.forEach { file ->
                    try {
                        FileInputStream(file).use { inputStream ->
                            Log.v(LOG_TAG_BUG_REPORT, "adding file to zip: ${file.name}, size: ${file.length()}")
                            val zipEntry = ZipEntry(file.name)
                            zipOutputStream.putNextEntry(zipEntry)
                            inputStream.copyTo(zipOutputStream)
                        }
                    } catch (e: FileNotFoundException) {
                        Log.e(LOG_TAG_BUG_REPORT, "file not found: ${file.name}, ${e.message}")
                    } catch (e: Exception) {
                        Log.e(LOG_TAG_BUG_REPORT, "err adding file to zip: ${file.name}, ${e.message}")
                    }
                }
            }
            Log.i(LOG_TAG_BUG_REPORT, "zip file created: ${zipFilePath.absolutePath}")
        } catch (e: FileNotFoundException) {
            Log.e(LOG_TAG_BUG_REPORT, "err adding logs to zip file: ${e.message}")
        } catch (e: ZipException) {
            Log.e(LOG_TAG_BUG_REPORT, "err adding logs to zip file: ${e.message}")
        } catch (e: Exception) {
            Log.e(LOG_TAG_BUG_REPORT, "err adding logs to zip file: ${e.message}")
        } finally {

        }
        Log.i(LOG_TAG_BUG_REPORT, "logs added to zip file")
    }

    @Synchronized
    fun writeLogsToFile(context: Context?, token: String, logs: String) {
        if (context == null) {
            Log.e(LOG_TAG_BUG_REPORT, "context is null, cannot write logs to file")
            return
        }
        try {
            // Always create a new file for each tombstone write
            val file = createNewTombstoneFile(context)
            if (file == null) {
                Log.e(LOG_TAG_BUG_REPORT, "failed to create new tombstone file")
                return
            }

            val time = Utilities.convertLongToTime(System.currentTimeMillis(), Constants.TIME_FORMAT_3)
            val content = "$time\nToken: $token\n$logs\n"

            // Write to the new file atomically
            file.writeText(content, Charset.defaultCharset())
            Log.i(LOG_TAG_BUG_REPORT, "logs written to new file: ${file.name}, size: ${file.length()} bytes")

            // Perform rotation to maintain file limit
            performRotation(context)
        } catch (e: Exception) {
            Log.e(LOG_TAG_BUG_REPORT, "err writing logs to file: ${e.message}", e)
        }
    }

    /**
     * Creates a new tombstone file with current timestamp.
     * Each write operation creates a fresh file to ensure data isolation.
     */
    private fun createNewTombstoneFile(context: Context): File? {
        try {
            val folderPath = getFolderPath(context.filesDir)
            if (folderPath == null) {
                Log.e(LOG_TAG_BUG_REPORT, "folder path is null, cannot create tombstone file")
                return null
            }
            return createTombstoneFile(folderPath)
        } catch (e: Exception) {
            Log.e(LOG_TAG_BUG_REPORT, "err creating new tombstone file: ${e.message}", e)
            return null
        }
    }



    fun getTombstoneZipFile(context: Context): File? {
        try {
            val zipFile = File(context.filesDir.canonicalPath + File.separator + TOMBSTONE_ZIP_FILE_NAME)
            if (!zipFile.exists()) {
                Log.w(LOG_TAG_BUG_REPORT, "zip file is null, cannot add logs to zip file")
                return null
            }
            return zipFile
        } catch (e: Exception) {
            Log.e(LOG_TAG_BUG_REPORT, "err getting tombstone zip file: ${e.message}", e)
        }
        return null
    }

    /**
     * Performs rotation to maintain the maximum file limit.
     * Deletes oldest files when count exceeds MAX_TOMBSTONE_FILES (20).
     */
    private fun performRotation(context: Context) {
        try {
            val folderPath = getFolderPath(context.filesDir) ?: return
            val folder = File(folderPath)
            val files = folder.listFiles() ?: return

            if (files.isEmpty()) {
                Log.d(LOG_TAG_BUG_REPORT, "no files to rotate")
                return
            }

            val fileCount = files.size
            Log.d(LOG_TAG_BUG_REPORT, "current tombstone file count: $fileCount")

            // If file count exceeds MAX_TOMBSTONE_FILES, delete the oldest files
            if (fileCount > MAX_TOMBSTONE_FILES) {
                val filesToDelete = fileCount - MAX_TOMBSTONE_FILES
                Log.i(LOG_TAG_BUG_REPORT, "file count ($fileCount) exceeds limit ($MAX_TOMBSTONE_FILES), deleting $filesToDelete oldest file(s)")

                // Sort by last modified time (ascending), oldest first
                files.sortedBy { it.lastModified() }
                    .take(filesToDelete)
                    .forEach { file ->
                        val deleted = file.delete()
                        if (deleted) {
                            Log.i(LOG_TAG_BUG_REPORT, "deleted old tombstone file: ${file.name}")
                        } else {
                            Log.w(LOG_TAG_BUG_REPORT, "failed to delete tombstone file: ${file.name}")
                        }
                    }

                val remainingCount = folder.listFiles()?.size ?: 0
                Log.i(LOG_TAG_BUG_REPORT, "rotation complete, remaining files: $remainingCount")
            } else {
                Log.d(LOG_TAG_BUG_REPORT, "file count within limit, no rotation needed")
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG_BUG_REPORT, "err performing rotation: ${e.message}", e)
        }
    }

    private fun createTombstoneFile(folderPath: String): File? {
        try {
            // create the tombstone file
            val ts = System.currentTimeMillis()
            val file =
                File(folderPath + File.separator + TOMBSTONE_FILE_NAME + ts + FILE_EXTENSION)
            val created = file.createNewFile()
            if (!created) {
                Log.e(LOG_TAG_BUG_REPORT, "failed to create tombstone file: ${file.name}, $folderPath")
                return null
            }
            Log.i(LOG_TAG_BUG_REPORT, "tombstone file created: ${file.absolutePath}")
            return file
        } catch (e: Exception) {
            Log.e(LOG_TAG_BUG_REPORT, "err creating tombstone file: ${e.message}", e)
        }
        return null
    }

    private fun getFolderPath(file: File): String? {
        try {
            // get the folder path to write the logs
            // if the folder does not exist, create the folder
            val path = file.canonicalPath + File.separator + TOMBSTONE_DIR_NAME
            val folder = File(path)
            if (folder.exists()) {
                Log.v(LOG_TAG_BUG_REPORT, "folder exists: $path")
            } else {
                val created = folder.mkdir()
                if (!created) {
                    Log.e(LOG_TAG_BUG_REPORT, "failed to create folder: $path")
                    return null
                }
                Log.v(LOG_TAG_BUG_REPORT, "folder created: $path")
            }
            if (!folder.isDirectory) {
                Log.e(LOG_TAG_BUG_REPORT, "path is not a directory: $path")
                return null
            }
            if (!folder.canWrite()) {
                Log.e(LOG_TAG_BUG_REPORT, "folder is not writable: $path")
                return null
            }

            return path
        } catch (e: Exception) {
            Log.e(LOG_TAG_BUG_REPORT, "err getting folder path: ${e.message}", e)
        }
        return null
    }
}
