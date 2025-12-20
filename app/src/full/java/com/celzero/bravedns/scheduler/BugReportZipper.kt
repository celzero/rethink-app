/*
 * Copyright 2021 RethinkDNS and its authors
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
import android.app.ApplicationExitInfo
import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.RequiresApi
import com.celzero.bravedns.BuildConfig
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Utilities
import com.celzero.firestack.intra.Intra
import com.google.common.io.Files
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Enumeration
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object BugReportZipper {

    // Bug report file and directory constants
    const val BUG_REPORT_DIR_NAME = "bugreport"
    const val BUG_REPORT_ZIP_FILE_NAME = "rethinkdns.bugreport.zip"
    private const val BUG_REPORT_FILE_NAME = "bugreport_"

    // maximum number of files allowed as part of bugreport zip file
    private const val BUG_REPORT_MAX_FILES_ALLOWED = 30

    // secure sharing of files associated with an app, used in share bugreport file feature
    const val FILE_PROVIDER_NAME = BuildConfig.APPLICATION_ID + ".provider"

    // ZIP file validation constants
    private const val ZIP_HEADER_SIZE = 4

    // File size limits (10MB in bytes)
    private const val MAX_ZIP_SIZE_BYTES = 10L * 1024L * 1024L // 10MB

    // Buffer size for ZIP operations
    private const val ZIP_BUFFER_SIZE = 8192

    // Entry retention constants
    private const val MAX_RECENT_ENTRIES_LARGE_ZIP = 10
    private const val MAX_RECENT_ENTRIES_CLEANUP = 5

    @RequiresApi(Build.VERSION_CODES.O)
    fun prepare(dir: File): String {
        val filePath = dir.canonicalPath + File.separator + BUG_REPORT_DIR_NAME
        val file = File(filePath)
        // Use atomic operation pattern with proper error handling
        val isDeleted = if (file.exists()) Utilities.deleteRecursive(file) else true
        if (!isDeleted) {
            Logger.w(LOG_TAG_BUG_REPORT, "failed to delete directory: ${file.absolutePath}")
        }
        // Use mkdirs() instead of mkdir() to handle parent directories too
        if (!file.mkdirs()) {
            Logger.w(LOG_TAG_BUG_REPORT, "failed to create directory: ${file.absolutePath}")
        }

        val zipFile = getZipFile(dir) ?: return constructFileName(filePath, null)
        zipFile.use { zf ->
            val nextFileNumber = zf.entries().toList().count()

            if (nextFileNumber >= BUG_REPORT_MAX_FILES_ALLOWED) {
                val f = File(getOldestEntry(zf))
                val fileName = Files.getNameWithoutExtension(f.name)
                return constructFileName(filePath, fileName)
            }
            return constructFileName(filePath, BUG_REPORT_FILE_NAME + nextFileNumber)
        }
    }

    private fun getZipFile(dir: File): ZipFile? {
        val file = File(getZipFileName(dir))

        if (!file.exists()) {
            Logger.w(LOG_TAG_BUG_REPORT, "zip file does not exist: ${file.absolutePath}")
            return null
        }

        if (file.length() == 0L) {
            Logger.w(LOG_TAG_BUG_REPORT, "zip file is empty: ${file.absolutePath}")
            return null
        }

        // check for ZIP magic bytes (PK\x03\x04)
        try {
            FileInputStream(file).use { fis ->
                val header = ByteArray(ZIP_HEADER_SIZE)
                val bytesRead = fis.read(header)
                if (bytesRead < ZIP_HEADER_SIZE ||
                    header[0] != 'P'.code.toByte() ||
                    header[1] != 'K'.code.toByte()
                ) {
                    Logger.w(
                        LOG_TAG_BUG_REPORT,
                        "zip file has invalid header: ${file.absolutePath}"
                    )
                    Utilities.deleteRecursive(file)
                    return null
                }
            }

            return ZipFile(file)
        } catch (e: FileNotFoundException) {
            Logger.w(LOG_TAG_BUG_REPORT, "file not found exception while creating zip file; ${e.message}")
        } catch (e: ZipException) {
            Logger.w(LOG_TAG_BUG_REPORT, "err while creating zip file; ${e.message}")
            Utilities.deleteRecursive(file) // delete corrupted zip file
        } catch (e: Exception) {
            Logger.w(LOG_TAG_BUG_REPORT, "err while creating zip file; ${e.message}")
        }
        return null
    }

    private fun constructFileName(filePath: String, fileName: String?): String {
        if (fileName == null) return filePath + File.separator + "bugreport_0.txt"

        return filePath + File.separator + fileName + ".txt"
    }

    // Get the oldest file modified in the zip file to replace
    @RequiresApi(Build.VERSION_CODES.O)
    fun getOldestEntry(directory: ZipFile?): String {
        if (directory?.entries() == null) return ""

        val entries = directory.entries().toList().sortedBy { it.lastModifiedTime.toMillis() }
        if (entries.isEmpty()) return ""
        return entries.firstOrNull()?.name.orEmpty()
    }

    fun getZipFileName(dir: File): String {
        return dir.canonicalPath + File.separator + BUG_REPORT_ZIP_FILE_NAME
    }

    private fun getTempZipFileName(dir: File): String {
        return dir.canonicalPath + File.separator + "temp_" + BUG_REPORT_ZIP_FILE_NAME
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun rezipAll(dir: File, file: File) {
        if (!file.exists()) {
            Logger.w(LOG_TAG_BUG_REPORT, "file to zip does not exist: ${file.absolutePath}")
            return
        }

        if (file.length() <= 0) {
            Logger.w(LOG_TAG_BUG_REPORT, "empty file, skipping: ${file.absolutePath}")
            return
        }

        val curZip = getZipFile(dir)
        val zipFile = File(getZipFileName(dir))
        val tempFile = File(getTempZipFileName(dir))

        try {
            if (curZip == null) {
                // create new zip file
                FileOutputStream(zipFile).use { fos ->
                    ZipOutputStream(fos).use { zos ->
                        addNewZipEntry(zos, file)
                    }
                }
            } else {
                // create temp zip with existing content + new file
                FileOutputStream(tempFile).use { fos ->
                    ZipOutputStream(fos).use { zos ->
                        curZip.use { czf ->
                            // Check total size before adding files
                            val currentSize = zipFile.length()

                            if (currentSize > MAX_ZIP_SIZE_BYTES) {
                                // If zip is too large, keep only recent entries
                                val recentEntries = czf.entries().toList()
                                    .sortedByDescending { it.lastModifiedTime.toMillis() }
                                    .take(MAX_RECENT_ENTRIES_LARGE_ZIP)
                                    .map { it.name }

                                czf.entries().toList().forEach { entry ->
                                    if (entry.name in recentEntries && entry.name != file.name) {
                                        copyEntryToZip(czf, entry, zos)
                                    }
                                }
                            } else {
                                handleOlderFiles(zos, czf, file.name)
                            }

                            addNewZipEntry(zos, file)
                        }
                    }
                }

                // Atomic replacement using file rename
                if (zipFile.exists() && !zipFile.delete()) {
                    Logger.e(LOG_TAG_BUG_REPORT, "failed to delete old zip file")
                    return
                }

                if (!tempFile.renameTo(zipFile)) {
                    Logger.e(LOG_TAG_BUG_REPORT, "failed to rename temp zip file")
                    // Try to recover by copying instead
                    tempFile.inputStream().use { input ->
                        zipFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    tempFile.delete()
                }
            }
        } catch (e: Exception) {
            Logger.w(LOG_TAG_BUG_REPORT, "err while updating zip file ${e.message}")
        } finally {
            // Clean up temp file if it exists
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    // copy entry from one zip to another
    private fun copyEntryToZip(sourceZip: ZipFile, entry: ZipEntry, targetZip: ZipOutputStream) {
        if (entry.isDirectory) {
            val zipEntry = ZipEntry(entry.name)
            targetZip.putNextEntry(zipEntry)
            targetZip.closeEntry()
        } else {
            try {
                val zipEntry = ZipEntry(entry.name)
                targetZip.putNextEntry(zipEntry)
                sourceZip.getInputStream(entry).use { input ->
                    input.copyTo(targetZip)
                }
                targetZip.closeEntry()
            } catch (e: Exception) {
                Logger.w(LOG_TAG_BUG_REPORT, "err while copying entry ${entry.name}", e)
            }
        }
    }

    // delete the file created to store the bug report in zip
    fun deleteAll(dir: File) {
        val filePath = dir.canonicalPath + File.separator + BUG_REPORT_DIR_NAME
        Utilities.deleteRecursive(File(filePath))
    }

    private fun addNewZipEntry(zo: ZipOutputStream, file: File) {
        if (!file.exists()) {
            Logger.w(LOG_TAG_BUG_REPORT, "file does not exist: ${file.absolutePath}")
            return
        }

        if (file.isDirectory) {
            Logger.w(LOG_TAG_BUG_REPORT, "dir cannot be added: ${file.absolutePath}")
            return
        }

        // skip empty files or files that exceed reasonable size
        if (file.length() == 0L) {
            Logger.w(LOG_TAG_BUG_REPORT, "empty file skipped: ${file.name}")
            return
        } else if (file.length() > MAX_ZIP_SIZE_BYTES) {
            Logger.w(LOG_TAG_BUG_REPORT, "file too large (${file.length()} bytes): ${file.name}")
            return
        }

        try {
            val entry = ZipEntry(file.name)
            zo.putNextEntry(entry)
            FileInputStream(file).use { input ->
                val buffer = ByteArray(ZIP_BUFFER_SIZE)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    zo.write(buffer, 0, bytesRead)
                }
            }
            zo.closeEntry()
            Logger.i(LOG_TAG_BUG_REPORT, "added new file to zip: ${file.name}")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_BUG_REPORT, "err adding file to zip: ${file.name}", e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun handleOlderFiles(zo: ZipOutputStream, zipFile: ZipFile?, ignoreFileName: String) {
        if (zipFile == null) return

        val entries: Enumeration<out ZipEntry> = zipFile.entries()

        // get file size by checking the actual file (more reliable than internal zip size)
        val zipFilePath = zipFile.name
        val zipFileSize = File(zipFilePath).length()

        // if zip file is more than 10 MB, only keep recent entries
        if (zipFileSize > MAX_ZIP_SIZE_BYTES) {
            Logger.i(LOG_TAG_BUG_REPORT, "Zip file size exceeds 10MB, keeping only recent entries")

            val entryList = zipFile.entries().toList().sortedByDescending {
                it.lastModifiedTime.toMillis()
            }
            // keep only the last 5 entries or less if there are not enough entries
            val keepEntries = entryList.take(MAX_RECENT_ENTRIES_CLEANUP).map { it.name }

            entryList.forEach { entry ->
                if (entry.name in keepEntries && entry.name != ignoreFileName) {
                    copyEntryToZip(zipFile, entry, zo)
                }
            }
            return
        }

        while (entries.hasMoreElements()) {
            val e = entries.nextElement()
            if (ignoreFileName == e.name) {
                Logger.i(LOG_TAG_BUG_REPORT, "ignoring file to be replaced: ${e.name}")
                continue
            }

            // entries.nextElement() returns the entry, which can be directly used
            // with putNextEntry(). But there is an issue "invalid entry compressed size
            // (expected 113177 but got 113312 bytes)". To avoid this again creating
            // zip entry.
            // ref: https://stackoverflow.com/a/37645519
            val zipEntry = ZipEntry(e.name)
            zo.putNextEntry(zipEntry)

            if (!e.isDirectory) {
                zipFile.getInputStream(e).use { inStream -> copy(inStream, zo) }
            }
            zo.closeEntry()
        }
    }

    fun fileWrite(inputStream: InputStream?, file: File) {
        if (inputStream == null) return

        try {
            FileOutputStream(file, true).use { outputStream ->
                copy(inputStream, outputStream)
            }
        } catch (e: Exception) {
            Logger.w(LOG_TAG_BUG_REPORT, "err writing to file: ${file.name}", e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun dumpAppExit(aei: ApplicationExitInfo, file: File) {
        try {
            val reportDetails =
                "${aei.packageUid}, reason: ${aei.reason}, desc: ${aei.description}, imp: ${aei.importance}, pss: ${aei.pss}, rss: ${aei.rss},${
                    Utilities.convertLongToTime(aei.timestamp, Constants.TIME_FORMAT_3)
                }\n"
            file.appendText(reportDetails)

            if (Utilities.isAtleastS()) {
                // above API 31, we can get the traceInputStream for native crashes
                if (aei.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                    aei.traceInputStream.use { ins -> fileWrite(ins, file) }
                }
            }
            // capture traces for ANR exit-infos
            if (aei.reason == ApplicationExitInfo.REASON_ANR) {
                aei.traceInputStream.use { ins -> fileWrite(ins, file) }
            }
        } catch (e: Exception) {
            Logger.w(LOG_TAG_BUG_REPORT, "err while dumping app exit info, ${e.message}")
        }
    }

    suspend fun dumpPrefs(prefs: SharedPreferences, file: File) {
        val prefsMap = prefs.all
        val prefsDetails = StringBuilder()
        prefsMap.forEach { (key, value) -> prefsDetails.append("\n$key=$value") }
        file.appendText(prefsDetails.toString())
        val separator = "--------------------------------------------\n"
        file.appendText(separator)
        val build = Intra.build(true)
        file.appendText(build)
        file.appendText(separator)
    }

    private fun copy(input: InputStream, output: OutputStream) {
        try {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var bytesRead: Int

            while (input.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
            }
            output.flush()
        } catch (e: OutOfMemoryError) {
            Logger.e(LOG_TAG_BUG_REPORT, "out of memory while copying file, ${e.message}")
        } catch (e: SecurityException) {
            Logger.e(LOG_TAG_BUG_REPORT, "security exception while copying file", e)
        } catch (e: Exception) {
            Logger.w(LOG_TAG_BUG_REPORT, "err while copying file", e)
        }
    }
}
