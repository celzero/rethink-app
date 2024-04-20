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
import Logger.LOG_TAG_SCHEDULER
import android.app.ApplicationExitInfo
import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.RequiresApi
import com.celzero.bravedns.BuildConfig
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Utilities
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
    private const val BUG_REPORT_DIR_NAME = "bugreport"
    private const val BUG_REPORT_ZIP_FILE_NAME = "rethinkdns.bugreport.zip"
    private const val BUG_REPORT_FILE_NAME = "bugreport_"

    // maximum number of files allowed as part of bugreport zip file
    private const val BUG_REPORT_MAX_FILES_ALLOWED = 30

    // secure sharing of files associated with an app, used in share bugreport file feature
    const val FILE_PROVIDER_NAME = BuildConfig.APPLICATION_ID + ".provider"

    @RequiresApi(Build.VERSION_CODES.O)
    fun prepare(dir: File): String {
        val filePath = dir.canonicalPath + File.separator + BUG_REPORT_DIR_NAME
        val file = File(filePath)

        if (file.exists()) {
            Utilities.deleteRecursive(file)
            file.mkdir()
        } else {
            file.mkdir()
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
        return try {
            ZipFile(getZipFileName(dir))
        } catch (e: FileNotFoundException) {
            Logger.w(LOG_TAG_SCHEDULER, "File not found exception while creating zip file", e)
            null
        } catch (e: ZipException) {
            Logger.w(LOG_TAG_SCHEDULER, "Zip exception while creating zip file", e)
            null
        }
    }

    private fun constructFileName(filePath: String, fileName: String?): String {
        if (fileName == null) return filePath + File.separator + "bugreport_0.txt"

        return filePath + File.separator + fileName + ".txt"
    }

    // Get the oldest file modified in the zip file to replace
    @RequiresApi(Build.VERSION_CODES.O)
    private fun getOldestEntry(directory: ZipFile?): String {
        if (directory?.entries() == null) return ""

        val entries = directory.entries().toList().sortedBy { it.lastModifiedTime.toMillis() }
        return entries[0].name ?: ""
    }

    fun getZipFileName(dir: File): String {
        return dir.canonicalPath + File.separator + BUG_REPORT_ZIP_FILE_NAME
    }

    private fun getTempZipFileName(dir: File): String {
        return dir.canonicalPath + File.separator + "temp_" + BUG_REPORT_ZIP_FILE_NAME
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun rezipAll(dir: File, file: File) {
        if (!file.exists() || file.length() <= 0) return

        val curZip = getZipFile(dir)
        if (curZip == null) {
            val zip = getZipFileName(dir)
            FileOutputStream(zip, true).use { zf ->
                ZipOutputStream(zf).use { zo ->
                    // Add new file to zip
                    addNewZipEntry(zo, file)
                }
            }
        } else {
            // cannot append to existing zip file, copy over and then append
            // ref=(https://stackoverflow.com/a/2265206)
            val tempZipFile = getTempZipFileName(dir)
            curZip.use { czf ->
                FileOutputStream(tempZipFile, true).use { tmp ->
                    ZipOutputStream(tmp).use { tzo ->
                        handleOlderFiles(tzo, czf, file.name)
                        addNewZipEntry(tzo, file)
                    }
                }
            }

            // delete the old zip file and rename the temp file to zip file
            val zipFile = File(getZipFileName(dir))
            zipFile.delete()
            File(tempZipFile).renameTo(zipFile)
        }
    }

    // delete the file created to store the bug report in zip
    fun deleteAll(dir: File) {
        val filePath = dir.canonicalPath + File.separator + BUG_REPORT_DIR_NAME
        Utilities.deleteRecursive(File(filePath))
    }

    private fun addNewZipEntry(zo: ZipOutputStream, file: File) {
        if (file.isDirectory) return

        Logger.i(LOG_TAG_SCHEDULER, "Add new file: ${file.name} to bug_report.zip")
        val entry = ZipEntry(file.name)
        zo.putNextEntry(entry)
        FileInputStream(file).use { inStream -> copy(inStream, zo) }
        zo.closeEntry()
    }

    private fun handleOlderFiles(zo: ZipOutputStream, zipFile: ZipFile?, ignoreFileName: String) {
        if (zipFile == null) return

        val entries: Enumeration<out ZipEntry> = zipFile.entries()

        while (entries.hasMoreElements()) {
            val e = entries.nextElement()
            if (ignoreFileName == e.name) {
                Logger.i(LOG_TAG_SCHEDULER, "Ignoring file to be replaced: ${e.name}")
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

        FileOutputStream(file, true).use { outputStream -> copy(inputStream, outputStream) }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun dumpAppExit(aei: ApplicationExitInfo, file: File) {
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
    }

    fun dumpPrefs(prefs: SharedPreferences, file: File) {
        val prefsMap = prefs.all
        val prefsDetails = StringBuilder()
        prefsMap.forEach { (key, value) -> prefsDetails.append("$key=$value\n") }
        file.appendText(prefsDetails.toString())
    }

    private fun copy(input: InputStream, output: OutputStream) {
        while (input.read() != -1) {
            output.write(input.readBytes())
        }
    }
}
