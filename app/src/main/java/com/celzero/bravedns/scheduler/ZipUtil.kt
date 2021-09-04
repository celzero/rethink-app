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

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.MAX_NUMBER_FILES_ALLOWED
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_SCHEDULER
import com.celzero.bravedns.util.Utilities
import com.google.common.io.Files
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ZipUtil {
    companion object {

        @RequiresApi(Build.VERSION_CODES.O)
        fun getBugRptFile(context: Context): String {
            val filePath = context.filesDir.canonicalPath + File.separator + Constants.BUG_REPORT_DIR_NAME
            val file = File(filePath)

            if (!file.isDirectory) {
                file.mkdir()
            }

            val zipFile = getZipFile(context) ?: return constructFileName(filePath, null)

            val nextFileNumber = zipFile.entries().toList().size

            if (nextFileNumber >= MAX_NUMBER_FILES_ALLOWED) {
                val f = File(getOlderFile(zipFile))
                val fileName = Files.getNameWithoutExtension(f.name)
                return constructFileName(filePath, fileName)
            }

            return constructFileName(filePath,
                                     Constants.BUG_REPORT_FILE_NAME + nextFileNumber)
        }

        private fun getZipFile(context: Context): ZipFile? {
            return try {
                ZipFile(getZipFilePath(context))
            } catch (ignored: Exception) { // FileNotFound, ZipException
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
            // TODO converting the entries into arrays just to sort based on modifiedTime, find
            // a better way to do it if any
            val entries: Array<out ZipEntry> = directory.entries().toList().toTypedArray()
            Arrays.sort(entries) { o1, o2 -> o2.lastModifiedTime.compareTo(o1.lastModifiedTime) }

            return entries[0].name
        }

        fun getZipFilePath(context: Context): String {
            return context.filesDir.canonicalPath + File.separator + Constants.BUG_REPORT_ZIP_FILE_NAME
        }

        @RequiresApi(Build.VERSION_CODES.O)
        fun zip(context: Context, file: File) {
            val zipPath = getZipFilePath(context)

            val zipFile = getZipFile(context)

            ZipOutputStream(FileOutputStream(zipPath, true)).use { zo ->
                // Issue when a new file is appended to the existing zip file.
                // Using the approach similar to this ref=(https://stackoverflow.com/a/2265206)
                handleOlderFiles(zo, zipFile, file.name)

                // Add new file to zip
                addNewFile(zo, file)
            }
            zipFile?.close()

            deleteBugReportFile(context)
        }

        // delete the file created to store the bug report in zip
        private fun deleteBugReportFile(context: Context) {
            val filePath = context.filesDir.canonicalPath + File.separator + Constants.BUG_REPORT_DIR_NAME
            Utilities.deleteRecursive(File(filePath))
        }

        private fun addNewFile(zo: ZipOutputStream, file: File) {
            Log.d(LOG_TAG_SCHEDULER, "Add new file: ${file.name} to bug_report.zip")
            val entry = ZipEntry(file.name)
            zo.putNextEntry(entry)
            val inStream = FileInputStream(file)
            writeZipContents(zo, inStream)
            inStream.close()
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
                    val inStream = zipFile.getInputStream(e)
                    writeZipContents(zo, inStream)
                }
                zo.closeEntry()
            }
        }

        fun writeTrace(file: File, inputStream: InputStream?) {
            if (inputStream == null) return

            FileOutputStream(file, true).use { outputStream ->
                while (inputStream.read() != -1) {
                    outputStream.write(inputStream.readBytes())
                }
            }
        }

        private fun writeZipContents(outputStream: ZipOutputStream, inputStream: InputStream) {
            while (inputStream.read() != -1) {
                outputStream.write(inputStream.readBytes())
            }
        }
    }

}
