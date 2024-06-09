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
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipOutputStream

object EnhancedBugReport {

    private const val TOMBSTONE_DIR_NAME = "tombstone"
    private const val TOMBSTONE_FILE_NAME = "tombstone_"
    private const val MAX_TOMBSTONE_FILES = 5 // maximum files allowed as part of tombstone zip file
    private const val MAX_FILE_SIZE = 1024 * 1024 // 1MB
    private const val FILE_EXTENSION = ".txt"
    private const val ZIP_FILE_NAME = "rethinkdns.tombstone.zip"

    @RequiresApi(Build.VERSION_CODES.O)
    fun addLogsToZipFile(context: Context) {
        try {
            // create a new zip file named rethinkdns.tombstone.zip
            // add the logs to the zip file
            // close the zip file
            val zipFilePath = File(context.filesDir.canonicalPath + File.separator + ZIP_FILE_NAME)
            Logger.d(LOG_TAG_BUG_REPORT, "zip file path: $zipFilePath")
            val zipOutputStream = ZipOutputStream(FileOutputStream(zipFilePath))
            val folder = getFolderPath(context.filesDir) ?: return
            val files = File(folder).listFiles()
            Logger.d(LOG_TAG_BUG_REPORT, "files to add to zip: ${files?.size}")
            files?.forEach { file ->
                val inputStream = FileInputStream(file)
                val zipEntry = ZipEntry(file.name)
                zipOutputStream.putNextEntry(zipEntry)
                inputStream.copyTo(zipOutputStream)
                inputStream.close()
            }
            zipOutputStream.close()
        } catch (e: FileNotFoundException) {
            Logger.e(LOG_TAG_BUG_REPORT, "err adding logs to zip file: ${e.message}", e)
        } catch (e: ZipException) {
            Logger.e(LOG_TAG_BUG_REPORT, "err adding logs to zip file: ${e.message}", e)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_BUG_REPORT, "err adding logs to zip file: ${e.message}", e)
        }
        Logger.i(LOG_TAG_BUG_REPORT, "logs added to zip file")
    }

    fun writeLogsToFile(context: Context, logs: String) {
        try {
            val file = getFileToWrite(context)
            if (file == null) {
                Logger.e(LOG_TAG_BUG_REPORT, "file name is null, cannot write logs to file")
                return
            }
            val l = logs + "\n" // append a new line character
            file.appendText(l, Charset.defaultCharset())
        } catch (e: Exception) {
            Logger.e(LOG_TAG_BUG_REPORT, "err writing logs to file: ${e.message}", e)
        }
    }

    private fun getFileToWrite(context: Context): File? {
        val file = getTombstoneFile(context)
        Logger.d(LOG_TAG_BUG_REPORT, "file to write logs: ${file?.name}")
        return file
    }

    private fun getTombstoneFile(context: Context): File? {
        try {
            val folderPath = getFolderPath(context.filesDir)
            if (folderPath == null) {
                Logger.e(LOG_TAG_BUG_REPORT, "folder path is null, cannot get tombstone file")
                return null
            }
            val folder = File(folderPath)
            val files = folder.listFiles()
            if (files.isNullOrEmpty()) {
                Logger.d(LOG_TAG_BUG_REPORT, "no files found in the tombstone folder")
                return createTombstoneFile(folderPath)
            } else {
                Logger.d(LOG_TAG_BUG_REPORT, "files found in the tombstone folder")
                return getLatestFile(files) ?: createTombstoneFile(folderPath)
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_BUG_REPORT, "err getting tombstone file: ${e.message}", e)
            return null
        }
    }

    private fun findLatestFile(files: Array<File>): File? {
        if (files.isEmpty()) {
            return null
        }
        files.sortByDescending { it.name }
        var latestFile = files[0]
        var latestTimestamp = latestFile.lastModified()
        Logger.vv(LOG_TAG_BUG_REPORT, "latest timestamp: $latestTimestamp, ${latestFile.name}")
        for (file in files) {
            Logger.vv(LOG_TAG_BUG_REPORT, "file timestamp: ${file.lastModified()}, ${file.name}")
            if (file.lastModified() > latestTimestamp) {
                latestFile = file
                latestTimestamp = file.lastModified()
                Logger.vv(LOG_TAG_BUG_REPORT, "updated timestamp: $latestTimestamp, ${latestFile.name}")
            }
        }

        return latestFile
    }

    private fun findOldestFile(files: Array<File>): File? {
        if (files.isEmpty()) {
            return null
        }
        files.sortBy { it.name }
        var oldestFile = files[0]
        var oldestTimestamp = oldestFile.lastModified()
        Logger.vv(LOG_TAG_BUG_REPORT, "oldest timestamp: $oldestTimestamp, ${oldestFile.name}")
        for (file in files) {
            Logger.vv(LOG_TAG_BUG_REPORT, "file timestamp: ${file.lastModified()}, ${file.name}")
            if (file.lastModified() < oldestTimestamp) {
                oldestFile = file
                oldestTimestamp = file.lastModified()
                Logger.vv(LOG_TAG_BUG_REPORT, "updated timestamp: $oldestTimestamp, ${oldestFile.name}")
            }
        }

        return oldestFile
    }

    fun getTombstoneZipFile(context: Context): File? {
        try {
            val zipFile = File(context.filesDir.canonicalPath + File.separator + ZIP_FILE_NAME)
            if (!zipFile.exists()) {
                Logger.w(LOG_TAG_BUG_REPORT, "zip file is null, cannot add logs to zip file")
                return null
            }
            return zipFile
        } catch (e: Exception) {
            Logger.e(LOG_TAG_BUG_REPORT, "err getting tombstone zip file: ${e.message}", e)
        }
        return null
    }

    private fun getLatestFile(files: Array<File>): File? {
        try {
            if (files.isEmpty()) {
                Logger.w(LOG_TAG_BUG_REPORT, "no files found in the tombstone folder")
                return null
            }
            val latestFile = findLatestFile(files) ?: return null
            if (latestFile.length() > MAX_FILE_SIZE) {
                Logger.d(LOG_TAG_BUG_REPORT, "file size is more than 1MB, ${latestFile.name}")
                // create a new file
                val parent = latestFile.parent ?: return null
                return createTombstoneFile(parent)
            }
            // if the file count is more than MAX_TOMBSTONE_FILES, delete the oldest file
            if (files.size > MAX_TOMBSTONE_FILES) {
                val fileToDelete = findOldestFile(files) ?: return null
                Logger.i(LOG_TAG_BUG_REPORT, "deleted the oldest file ${fileToDelete.name}, file count: ${files.size}")
                fileToDelete.delete()
            }
            return latestFile
        } catch (e: Exception) {
            Logger.e(LOG_TAG_BUG_REPORT, "err getting latest file: ${e.message}", e)
        }
        return null
    }

    private fun createTombstoneFile(folderPath: String): File? {
        try {
            // create the tombstone file
            val ts = System.currentTimeMillis()
            val file =
                File(folderPath + File.separator + TOMBSTONE_FILE_NAME + ts + FILE_EXTENSION)
            file.createNewFile()
            Logger.d(LOG_TAG_BUG_REPORT, "created tombstone file: ${file.name}")
            return file
        } catch (e: Exception) {
            Logger.e(LOG_TAG_BUG_REPORT, "err creating tombstone file: ${e.message}", e)
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
                Logger.vv(LOG_TAG_BUG_REPORT, "folder exists: $path")
            } else {
                folder.mkdir()
                Logger.vv(LOG_TAG_BUG_REPORT, "folder created: $path")
            }
            return path
        } catch (e: Exception) {
            Logger.e(LOG_TAG_BUG_REPORT, "err getting folder path: ${e.message}", e)
        }
        return null
    }
}
