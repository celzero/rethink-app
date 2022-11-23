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

import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.celzero.bravedns.BuildConfig
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_SCHEDULER
import com.celzero.bravedns.util.Utilities
import com.google.common.io.Files
import java.io.*
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object BugReportZipper {

    // Bug report file and directory constants
    private const val BUG_REPORT_DIR_NAME = "bugreport/"
    private const val BUG_REPORT_ZIP_FILE_NAME = "rethinkdns.bugreport.zip"
    private const val BUG_REPORT_FILE_NAME = "bugreport_"

    // maximum number of files allowed as part of bugreport zip file
    private const val BUG_REPORT_MAX_FILES_ALLOWED = 20

    // secure sharing of files associated with an app, used in share bugreport file feature
    const val FILE_PROVIDER_NAME = BuildConfig.APPLICATION_ID + ".provider"

    @RequiresApi(Build.VERSION_CODES.O)
    fun prepare(context: Context): String {
        val filePath = context.filesDir.canonicalPath + File.separator + BUG_REPORT_DIR_NAME
        val file = File(filePath)

        if (file.exists()) {
            Utilities.deleteRecursive(file)
        } else {
            file.mkdir()
        }

        val zipFile = getZipFile(context) ?: return constructFileName(filePath, null)
        zipFile.use { zf ->
            val nextFileNumber = zf.entries().toList().count()

            if (nextFileNumber >= BUG_REPORT_MAX_FILES_ALLOWED) {
                val f = File(getOlderFile(zf))
                val fileName = Files.getNameWithoutExtension(f.name)
                return constructFileName(filePath, fileName)
            }
            return constructFileName(filePath, BUG_REPORT_FILE_NAME + nextFileNumber)
        }
    }

    private fun getZipFile(context: Context): ZipFile? {
        return try {
            ZipFile(getZipFilePath(context))
        } catch (ignored: FileNotFoundException) {
            null
        } catch (ignored: ZipException) {
            null
        }
    }

    private fun constructFileName(filePath: String, fileName: String?): String {
        if (fileName == null) return filePath + File.separator + "bugreport_0.txt"

        return filePath + File.separator + fileName + ".txt"
    }

    // Get the oldest file modified in the zip file to replace
    @RequiresApi(Build.VERSION_CODES.O)
    private fun getOlderFile(directory: ZipFile?): String {
        if (directory?.entries() == null) return ""

        val entries = directory.entries().toList().sortedBy { it.lastModifiedTime.toMillis() }
        return entries[0].name ?: ""
    }

    fun getZipFilePath(context: Context): String {
        return context.filesDir.canonicalPath + File.separator + BUG_REPORT_ZIP_FILE_NAME
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun build(context: Context, file: File) {
        if (!file.exists() || file.length() <= 0) return

        val zipPath = getZipFilePath(context)

        val zipFile = getZipFile(context)
        zipFile.use { zf ->
            FileOutputStream(zipPath, true).use { fo ->
                ZipOutputStream(fo).use { zo ->
                    // Issue when a new file is appended to the existing zip file.
                    // Using the approach similar to this ref=(https://stackoverflow.com/a/2265206)
                    handleOlderFiles(zo, zf, file.name)

                    // Add new file to zip
                    addNewZipEntry(zo, file)
                }
            }
        }

        deleteBugReportFile(context)
    }

    // delete the file created to store the bug report in zip
    private fun deleteBugReportFile(context: Context) {
        val filePath = context.filesDir.canonicalPath + File.separator + BUG_REPORT_DIR_NAME
        Utilities.deleteRecursive(File(filePath))
    }

    private fun addNewZipEntry(zo: ZipOutputStream, file: File) {
        Log.i(LOG_TAG_SCHEDULER, "Add new file: ${file.name} to bug_report.zip")
        val entry = ZipEntry(file.name)
        zo.putNextEntry(entry)
        FileInputStream(file).use { inStream -> writeZipContents(zo, inStream) }
    }

    private fun handleOlderFiles(zo: ZipOutputStream, zipFile: ZipFile?, ignoreFileName: String) {
        if (zipFile == null) return

        val entries: Enumeration<out ZipEntry> = zipFile.entries()

        while (entries.hasMoreElements()) {
            val e = entries.nextElement()
            if (ignoreFileName == e.name) {
                Log.d(LOG_TAG_SCHEDULER, "Ignoring the old file: ${e.name} from bug_report.zip")
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
                zipFile.getInputStream(e).use { inStream -> writeZipContents(zo, inStream) }
            }
            zo.closeEntry()
        }
    }

    fun writeTrace(file: File, inputStream: InputStream?) {
        if (inputStream == null) return

        FileOutputStream(file, true).use { outputStream -> copy(inputStream, outputStream) }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun write(it: ApplicationExitInfo, file: File) {
        val reportDetails =
            "${it.packageUid},${it.reason},${it.description},${it.importance},${it.pss},${it.rss},${
            Utilities.convertLongToTime(it.timestamp, Constants.TIME_FORMAT_3)
        }\n"
        file.appendText(reportDetails)

        // capture traces for ANR exit-infos
        if (it.reason == ApplicationExitInfo.REASON_ANR) {
            it.traceInputStream.use { ins -> writeTrace(file, ins) }
        }
    }

    private fun writeZipContents(outputStream: ZipOutputStream, inputStream: InputStream) {
        copy(inputStream, outputStream)
    }

    fun copy(input: InputStream, output: OutputStream) {
        while (input.read() != -1) {
            output.write(input.readBytes())
        }
    }
}
