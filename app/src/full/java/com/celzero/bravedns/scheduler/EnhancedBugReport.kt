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
import android.util.Log
import androidx.annotation.RequiresApi
import com.celzero.bravedns.scheduler.EnhancedBugReport.MAX_TOTAL_FILES
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.FirebaseErrorReporting
import com.celzero.bravedns.util.Utilities
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.util.concurrent.CopyOnWriteArraySet
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipOutputStream

object EnhancedBugReport : KoinComponent {

    const val TOMBSTONE_DIR_NAME = "tombstone"
    const val TOMBSTONE_ZIP_FILE_NAME = "rethinkdns.tombstone.zip"


    // GoLogFileDescriptorReader2 → golog_<ts>.txt   (Go runtime log lines)
    const val PREFIX_GO_LOG = "golog_"
    // GoCrashFileDescriptorReader → gocrash_<ts>.txt (Go runtime panic dump)
    const val PREFIX_GO_CRASH = "gocrash_"
    // EnhancedBugReport (Kotlin/JVM) → kotlin_<ts>.txt
    const val PREFIX_KOTLIN = "kotlin_"

    const val FILE_EXTENSION = ".txt"
    const val FLIGHT_REC_EXT = ".pprof"

    // Total cap across ALL three file types in the tombstone folder.
    // When Firebase is OFF, files accumulate up to this limit before the oldest are deleted.
    private const val MAX_TOTAL_FILES = 20

    private const val MAX_BYTES = 64 * 1024

    private val persistentState by inject<PersistentState>()

    /**
     * Tracks file names that are currently being written by an active reader
     * (GoLogFileDescriptorReader or GoCrashFileDescriptorReader).
     *
     * A file is added here the moment it is created (before any reader touches it) and removed
     * only after the reader has fully closed the file.  reportTombstonesToFirebaseOnStartup
     * skips every file present in this set, regardless of when it was created, eliminating
     * the race condition that a timestamp-based guard cannot handle.
     */
    private val activeSessionFileNames = CopyOnWriteArraySet<String>()

    private val mutex = Mutex()

    /**
     * Marks [fileName] as belonging to the current session.
     * Called immediately after file creation, before handing the file to any reader.
     */
    fun markFileAsActive(fileName: String) {
        activeSessionFileNames.add(fileName)
        Log.d(LOG_TAG_BUG_REPORT, "tombstone active: $fileName")
    }

    /** Returns a new File for a Go log session (golog_<ts>.txt), creating the folder if needed. */
    fun newGoLogFile(context: Context): File? =
        newTombstoneFile(context, PREFIX_GO_LOG)

    /** Returns a new File for a Go crash dump (gocrash_<ts>.txt). */
    fun newGoCrashFile(context: Context): File? =
        newTombstoneFile(context, PREFIX_GO_CRASH)

    /** Returns a new File for a Kotlin/JVM crash log (kotlin_<ts>.txt). */
    fun newKotlinCrashFile(context: Context): File? =
        newTombstoneFile(context, PREFIX_KOTLIN)

    suspend fun writeLogsToFile(context: Context?, token: String, logs: String) {
        return mutex.withLock {
            if (context == null) {
                Log.e(LOG_TAG_BUG_REPORT, "context is null, cannot write logs to file")
                return
            }
            try {
                val file = newKotlinCrashFile(context) ?: run {
                    Log.e(LOG_TAG_BUG_REPORT, "failed to create kotlin crash file")
                    return
                }
                val time =
                    Utilities.convertLongToTime(System.currentTimeMillis(), Constants.TIME_FORMAT_3)
                file.writeText("$time\nToken: $token\n$logs\n", Charset.defaultCharset())
                Log.i(LOG_TAG_BUG_REPORT, "kotlin crash written: ${file.name} (${file.length()} B)")
                enforceMaxFiles(context, justWritten = file)
            } catch (e: Exception) {
                Log.e(LOG_TAG_BUG_REPORT, "err writing kotlin crash: ${e.message}", e)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun addLogsToZipFile(context: Context) {
        try {
            val zipFile = File(context.filesDir, TOMBSTONE_ZIP_FILE_NAME)
            val folder = tombstoneFolder(context.filesDir) ?: return
            val files = folder.listFiles { f ->
                f.isFile() && f.length() > 0L
            } ?: return

            Log.d(LOG_TAG_BUG_REPORT, "zipping ${files.size} non-empty tombstone file(s)")

            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                files.forEach { file ->
                    try {
                        FileInputStream(file).use { fis ->
                            zos.putNextEntry(ZipEntry(file.name))
                            fis.copyTo(zos)
                            zos.closeEntry()
                        }
                    } catch (_: FileNotFoundException) {
                        Log.e(LOG_TAG_BUG_REPORT, "file vanished: ${file.name}")
                    } catch (e: Exception) {
                        Log.e(LOG_TAG_BUG_REPORT, "err adding ${file.name} to zip: ${e.message}")
                    }
                }
            }
            Log.i(LOG_TAG_BUG_REPORT, "zip created: ${zipFile.absolutePath}")
        } catch (e: FileNotFoundException) {
            Log.e(LOG_TAG_BUG_REPORT, "zip: FileNotFound: ${e.message}")
        } catch (e: ZipException) {
            Log.e(LOG_TAG_BUG_REPORT, "zip: ZipException: ${e.message}")
        } catch (e: Exception) {
            Log.e(LOG_TAG_BUG_REPORT, "zip: unexpected error: ${e.message}")
        }
    }

    fun getTombstoneZipFile(context: Context): File? {
        return try {
            val f = File(context.filesDir, TOMBSTONE_ZIP_FILE_NAME)
            if (f.exists() && f.length() > 0) f else null
        } catch (e: Exception) {
            Log.e(LOG_TAG_BUG_REPORT, "err getting zip: ${e.message}")
            null
        }
    }

    //
    // Called once from RethinkDnsApplication.onCreate() (after FirebaseErrorReporting.initialize()).
    // Scans the tombstone folder for files not yet reported to Firebase and uploads them as
    // non-fatal exceptions.  Tracks the last reported file name in PersistentState so we never
    // re-upload the same file on the next restart.
    //
    // Rules:
    //   • Firebase enabled → report unreported files, delete each after a successful send.
    //   • Firebase disabled → do NOT report, do NOT delete (user may enable later).
    //   • Empty files → always delete, regardless of Firebase state.
    //   • File count > MAX_TOTAL_FILES (Firebase off) → delete oldest to stay at limit.
    //   • Files in activeSessionFiles → skip entirely; they are owned by a running reader
    //     (GoLogFileDescriptorReader / GoCrashFileDescriptorReader) in this process and are
    //     still being written to.  This guard is race-free: files are registered in
    //     activeSessionFiles the moment they are created, before any reader touches them.
    //
    fun reportTombstonesToFirebaseOnStartup(context: Context) {
        if (Utilities.isFdroidFlavour()) return

        val folder = tombstoneFolder(context.filesDir) ?: return
        val allFiles = folder.listFiles { f -> f.isFile } ?: return

        // delete all the empty files (but never touch active-session files even if empty)
        allFiles
            .filter { it.length() == 0L && !activeSessionFileNames.contains(it.name) }
            .forEach { empty ->
                val deleted = empty.delete()
                Log.d(LOG_TAG_BUG_REPORT, "deleted empty tombstone: ${empty.name}, ok=$deleted")
            }

        val nonEmptyFiles = folder.listFiles { f -> f.isFile && f.length() > 0 }
            ?.sortedBy { it.lastModified() }   // oldest first
            ?: return

        if (nonEmptyFiles.isEmpty()) return

        // Exclude files that are actively being written in this session.
        // activeSessionFiles is populated the instant a file is created (in newTombstoneFile),
        // so this exclusion is safe even when the reporter and the readers run concurrently.
        val snapshot = activeSessionFileNames.toSet()
        val previousSessionFiles = nonEmptyFiles.filter { f ->
            !snapshot.contains(f.name)
        }

        Log.d(LOG_TAG_BUG_REPORT,
            "tombstone startup: total=${nonEmptyFiles.size}, active=${snapshot.size}, candidates=${previousSessionFiles.size}")

        if (previousSessionFiles.isEmpty()) return

        val firebaseEnabled = persistentState.firebaseErrorReportingEnabled

        if (firebaseEnabled) {
            reportFilesToFirebase(previousSessionFiles)
        } else {
            // Firebase is currently disabled.
            val lastReportedName = persistentState.lastReportedTombstoneFile
            if (lastReportedName.isNotEmpty()) {
                val lastReportedTs = extractTimestampFromName(lastReportedName)
                // If no file in the current list has timestamp <= lastReportedTs, the referenced
                // file has been deleted. Reset so future comparisons start from scratch.
                val stillExists = previousSessionFiles.any { extractTimestampFromName(it.name) == lastReportedTs }
                if (!stillExists) {
                    Log.d(LOG_TAG_BUG_REPORT,
                        "tombstone: lastReported='$lastReportedName' no longer on disk, resetting")
                    persistentState.lastReportedTombstoneFile = ""
                }
            }
            enforceFileCountCap(previousSessionFiles, justWritten = null)
        }
    }

    /**
     * Reports each file in [files] (sorted oldest-first) to Firebase Crashlytics as a non-fatal
     * exception. After a successful send the file is deleted and [lastReportedTombstoneFile] is
     * updated so we skip it on the next restart.
     *
     */
    private fun reportFilesToFirebase(files: List<File>) {
        val lastReportedName = persistentState.lastReportedTombstoneFile
        // Extract the timestamp from the stored filename so we can compare by value, not by
        // list position. Returns 0 when no file has been reported yet → all files are new.
        val lastReportedTs = extractTimestampFromName(lastReportedName)
        Log.d(LOG_TAG_BUG_REPORT,
            "tombstone report: lastReported='$lastReportedName' ts=$lastReportedTs, candidates=${files.size}")

        for (file in files) {
            val fileTs = extractTimestampFromName(file.name)

            if (fileTs in 1..lastReportedTs) {
                // This file was already reported in a previous session (or IS the last reported
                // file). Delete it now that we know it has been sent.
                val deleted = file.delete()
                Log.d(LOG_TAG_BUG_REPORT,
                    "tombstone already-reported, deleting: ${file.name} ts=$fileTs, ok=$deleted")
                continue
            }

            // fileTs == 0 means we couldn't parse the timestamp (unexpected filename).
            // Treat it as unreported so it gets sent rather than silently dropped.
            val sent = sendFileToFirebase(file)
            if (sent) {
                persistentState.lastReportedTombstoneFile = file.name
                val deleted = file.delete()
                Log.i(LOG_TAG_BUG_REPORT,
                    "tombstone reported+deleted: ${file.name} ts=$fileTs, ok=$deleted")
            } else {
                // send failed; leave the file on disk for retry on next startup.
                // maybe consider deleting it if failed for a while?
                Log.w(LOG_TAG_BUG_REPORT,
                    "tombstone send failed, will retry: ${file.name}")
            }
        }
    }

    /**
     * Extracts the Unix-millisecond timestamp embedded in a tombstone filename.
     *
     * All three file types follow the pattern `<prefix><ts>.txt` where `<ts>` is the raw
     * [System.currentTimeMillis] value at file-creation time.  We strip the known prefix and
     * the `.txt` suffix to isolate the numeric string.
     *
     * Returns 0 if the name does not match any known prefix or the numeric part cannot be parsed.
     * Callers treat 0 as "unknown assume unreported" so the file gets sent rather than skipped.
     */
    private fun extractTimestampFromName(name: String): Long {
        if (name.isEmpty()) return 0L
        val prefix = when {
            name.startsWith(PREFIX_GO_CRASH) -> PREFIX_GO_CRASH
            name.startsWith(PREFIX_GO_LOG)   -> PREFIX_GO_LOG
            name.startsWith(PREFIX_KOTLIN)   -> PREFIX_KOTLIN
            else                             -> return 0L
        }
        // strip prefix and .txt suffix to get the numeric timestamp string.
        val tsStr = name.removePrefix(prefix).removeSuffix(FILE_EXTENSION)
        return tsStr.toLongOrNull() ?: 0L
    }

    /**
     * Reads [file] content, builds a synthetic exception and records it as a non-fatal event
     * in Firebase Crashlytics.  Content > 2 KB is also chunked into log() calls so the full
     * crash text is available in the Crashlytics log tab.
     *
     * Returns true if the record call did not throw.
     */
    private fun sendFileToFirebase(file: File): Boolean {
        return try {
            val content = readTruncatedContent(file)
            val type = when {
                file.name.startsWith(PREFIX_GO_CRASH) -> "GoCrash"
                file.name.startsWith(PREFIX_GO_LOG) -> "GoLog"
                file.name.startsWith(PREFIX_KOTLIN) -> "KotlinCrash"
                else -> "CrashLog"
            }
            Logger.d(LOG_TAG_BUG_REPORT, "err-rpting: sending $type ${file.name} (${content.length} chars)")
            // add 2 kb in the exception, rest to be added in corresponding log calls
            val messagePreview = content.take(2 * 1024)
            val ex = RuntimeException("[$type] ${file.name}\n$messagePreview")

            // log rest content in 8 KB chunks so nothing is lost.
            content.chunked(8 * 1024).forEachIndexed { idx, chunk ->
                FirebaseErrorReporting.log("[$type][${file.name}][$idx] $chunk")
            }
            // note: call the log before exception and then record exception
            FirebaseErrorReporting.recordException(ex)
            Log.d(LOG_TAG_BUG_REPORT, "err-rpting: sent $type ${file.name} (${content.length} chars)")
            true
        } catch (e: Exception) {
            Log.e(LOG_TAG_BUG_REPORT, "err-rpting: failed to send ${file.name}: ${e.message}")
            false
        }
    }

    private fun readTruncatedContent(file: File): String {
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(MAX_BYTES)
            val bytesRead = input.read(buffer)
            if (bytesRead <= 0) return ""

            return buffer.copyOf(bytesRead)
                .toString(Charsets.UTF_8)
        }
    }

    /**
     * Deletes the oldest files until the total count is ≤ [MAX_TOTAL_FILES].
     * [justWritten] is never deleted (it is the file being actively written by a reader).
     */
    private fun enforceFileCountCap(files: List<File>, justWritten: File?) {
        if (files.size <= MAX_TOTAL_FILES) return
        val deleteCount = files.size - MAX_TOTAL_FILES
        Log.i(LOG_TAG_BUG_REPORT,
            "tombstone cap: ${files.size} files, deleting $deleteCount oldest (max=$MAX_TOTAL_FILES)")
        files.filterNot { it == justWritten }
            .take(deleteCount)
            .forEach { f ->
                val ok = f.delete()
                Log.d(LOG_TAG_BUG_REPORT, "rotation: deleted ${f.name}, ok=$ok")
            }
    }

    /**
     * Called by each reader after creating/writing a new file.
     * Re-reads the full folder so rotation is always applied to the combined total.
     */
    fun enforceMaxFiles(context: Context, justWritten: File? = null) {
        try {
            val folder = tombstoneFolder(context.filesDir) ?: return
            // Delete empty files first.
            folder.listFiles { f -> f.isFile && f.length() == 0L }?.forEach { it.delete() }
            val files = folder.listFiles { f -> f.isFile && f.length() > 0 }
                ?.sortedBy { it.lastModified() } ?: return
            enforceFileCountCap(files, justWritten)
        } catch (e: Exception) {
            Log.e(LOG_TAG_BUG_REPORT, "enforceMaxFiles error: ${e.message}")
        }
    }

    private fun newTombstoneFile(context: Context, prefix: String): File? {
        val folder = tombstoneFolder(context.filesDir) ?: return null
        val file = File(folder, "$prefix${System.currentTimeMillis()}$FILE_EXTENSION")
        return try {
            if (file.createNewFile()) {
                // Register golog_ / gocrash_ files immediately so reportTombstonesToFirebaseOnStartup
                // never touches them, regardless of when it runs relative to file creation.
                if (prefix == PREFIX_GO_LOG || prefix == PREFIX_GO_CRASH) {
                    markFileAsActive(file.name)
                }
                file
            } else {
                Log.e(LOG_TAG_BUG_REPORT, "file already exists: ${file.name}")
                file
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG_BUG_REPORT, "failed to create ${file.name}: ${e.message}")
            null
        }
    }

    fun tombstoneFolder(filesDir: File): File? {
        val folder = File(filesDir, TOMBSTONE_DIR_NAME)
        if (!folder.exists()) {
            val created = folder.mkdirs()
            if (!created) {
                Log.e(LOG_TAG_BUG_REPORT, "failed to create tombstone dir: ${folder.absolutePath}")
                return null
            }
        }
        if (!folder.isDirectory) {
            Log.e(LOG_TAG_BUG_REPORT, "tombstone path is not a dir: ${folder.absolutePath}")
            return null
        }
        if (!folder.canWrite()) {
            Log.e(LOG_TAG_BUG_REPORT, "tombstone dir not writable: ${folder.absolutePath}")
            return null
        }
        return folder
    }
}
