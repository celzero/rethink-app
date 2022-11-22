/*
 * Copyright 2022 RethinkDNS and its authors
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
package com.celzero.bravedns.backup

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.preference.PreferenceManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.celzero.bravedns.backup.BackupHelper.Companion.DATA_BUILDER_BACKUP_URI
import com.celzero.bravedns.backup.BackupHelper.Companion.SHARED_PREFS_BACKUP_FILE_NAME
import com.celzero.bravedns.backup.BackupHelper.Companion.TEMP_ZIP_FILE_NAME
import com.celzero.bravedns.backup.BackupHelper.Companion.deleteResidue
import com.celzero.bravedns.backup.BackupHelper.Companion.getFileNameFromPath
import com.celzero.bravedns.backup.BackupHelper.Companion.getRethinkDatabase
import com.celzero.bravedns.backup.BackupHelper.Companion.getTempDir
import com.celzero.bravedns.backup.BackupHelper.Companion.startVpn
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_BACKUP_RESTORE
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.copyWithStream
import org.koin.core.component.KoinComponent
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// ref:
// https://gavingt.medium.com/refactoring-my-backup-and-restore-feature-to-comply-with-scoped-storage-e2b6c792c3b
class BackupAgent(val context: Context, workerParams: WorkerParameters) :
    Worker(context, workerParams), KoinComponent {

    var filesToZip: MutableList<File> = ArrayList()

    companion object {
        const val TAG = "BackupExport"
    }

    override fun doWork(): Result {
        val backupFileUri = Uri.parse(inputData.getString(DATA_BUILDER_BACKUP_URI))
        if (DEBUG)
            Log.d(LOG_TAG_BACKUP_RESTORE, "begin backup process with file uri: $backupFileUri")
        val isBackupSucceed = startBackupProcess(backupFileUri)

        Log.i(
            LOG_TAG_BACKUP_RESTORE,
            "completed backup process, is backup successful? $isBackupSucceed"
        )
        if (isBackupSucceed) {
            // start vpn on backup success
            startVpn(context)
            return Result.success()
        }
        return Result.failure()
    }

    private fun startBackupProcess(backupFileUri: Uri): Boolean {
        var successFull: Boolean
        try {
            val tempDir = getTempDir(context)

            val prefsBackupFile = File(tempDir, SHARED_PREFS_BACKUP_FILE_NAME)

            if (DEBUG)
                Log.d(
                    LOG_TAG_BACKUP_RESTORE,
                    "backup process, temp file dir: ${tempDir.path}, prefs backup file: ${prefsBackupFile.path}"
                )
            successFull = saveSharedPreferencesToFile(context, prefsBackupFile)

            if (successFull) {
                if (DEBUG)
                    Log.d(LOG_TAG_BACKUP_RESTORE, "shared pref backup is added to the temp dir")
                filesToZip.add(prefsBackupFile)
            } else {
                Log.w(
                    LOG_TAG_BACKUP_RESTORE,
                    "failed to add shared pref to temp backup dir, return failure"
                )
                return false
            }

            successFull = saveDatabasesToFile(tempDir.path)

            if (DEBUG)
                Log.d(
                    LOG_TAG_BACKUP_RESTORE,
                    "completed db backup to temp dir, isSuccessful? $successFull"
                )
            if (!successFull) {
                Log.w(
                    LOG_TAG_BACKUP_RESTORE,
                    "failed to add database backup to temp dir (${tempDir.path}), return failure"
                )
                return false
            }

            return zipAndCopyToDestination(tempDir, backupFileUri)
        } catch (e: Exception) {
            Log.e(
                LOG_TAG_BACKUP_RESTORE,
                "exception during backup process, reason? ${e.message}",
                e
            )
            return false
        } finally {
            for (file in filesToZip) {
                deleteResidue(file)
            }
        }
    }

    private fun zipAndCopyToDestination(tempDir: File, destUri: Uri): Boolean {
        val bZipSucceeded: Boolean = zip(filesToZip, tempDir.path)

        Log.i(
            LOG_TAG_BACKUP_RESTORE,
            "backup zip completed, is success? $bZipSucceeded, proceed to copy $destUri"
        )

        if (bZipSucceeded) {
            val tempZipFile = File(tempDir, TEMP_ZIP_FILE_NAME)
            val zipFileUri: Uri = Uri.fromFile(tempZipFile)
            val inputStream: InputStream =
                context.contentResolver.openInputStream(zipFileUri) ?: return false
            val outputStream: OutputStream =
                context.contentResolver.openOutputStream(destUri) ?: return false

            // we are passing the streams instead of actual files because we do not have
            // write access to the destination dir.
            val copySucceeded: Boolean = copyWithStream(inputStream, outputStream)
            return if (copySucceeded) {
                Log.i(
                    LOG_TAG_BACKUP_RESTORE,
                    "Copy completed, delete the temp dir ${tempZipFile.path}"
                )
                deleteResidue(tempZipFile)
                true
            } else {
                Log.w(
                    LOG_TAG_BACKUP_RESTORE,
                    "copy failed to destination dir, path: ${zipFileUri.path}"
                )
                false
            }
        } else {
            Log.w(LOG_TAG_BACKUP_RESTORE, "backup zip failed, do not proceed")
            return false
        }
    }

    private fun saveDatabasesToFile(path: String): Boolean {
        val files = getRethinkDatabase(context)?.listFiles() ?: return false

        for (f in files) {
            val databaseFile =
                backUpDatabaseFile(f.absolutePath, constructDbFileName(path, f.name))
                    ?: return false
            if (DEBUG)
                Log.d(LOG_TAG_BACKUP_RESTORE, "file ${databaseFile.name} added to backup dir")
            filesToZip.add(databaseFile)
        }

        return true
    }

    private fun constructDbFileName(path: String, fileName: String): String {
        return path + File.separator + fileName
    }

    private fun saveSharedPreferencesToFile(context: Context, prefFile: File): Boolean {
        var output: ObjectOutputStream? = null

        if (DEBUG)
            Log.d(LOG_TAG_BACKUP_RESTORE, "begin shared pref copy, file path:${prefFile.path}")
        val sharedPrefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

        try {
            output = ObjectOutputStream(FileOutputStream(prefFile))
            val allPrefs = sharedPrefs.all
            output.writeObject(allPrefs)
        } catch (e: Exception) {
            Log.e(LOG_TAG_BACKUP_RESTORE, "exception during shared pref backup, ${e.message}", e)
            return false
        } finally {
            try {
                if (output != null) {
                    output.flush()
                    output.close()
                }
            } catch (e: IOException) {
                // no-op
            }
        }
        return true
    }

    private fun backUpDatabaseFile(backupFilePath: String?, destFilePath: String?): File? {
        if (backupFilePath == null || destFilePath == null) {
            Log.w(
                LOG_TAG_BACKUP_RESTORE,
                "invalid backup info during db backup, file: $backupFilePath, destination: $destFilePath"
            )
            return null
        }
        val isCopySuccess = Utilities.copy(backupFilePath, destFilePath)
        if (isCopySuccess) return File(destFilePath)

        return null
    }

    private fun zip(files: List<File>, zipDirectory: String): Boolean {
        val outputFileName = zipDirectory + File.separator + TEMP_ZIP_FILE_NAME
        return try {
            val dest = FileOutputStream(outputFileName)
            val out = ZipOutputStream(BufferedOutputStream(dest))
            val BUFFER = 80000
            var origin: BufferedInputStream
            val data = ByteArray(BUFFER)
            for (file in files) {
                val fi = FileInputStream(file)
                origin = BufferedInputStream(fi, BUFFER)
                val entry = ZipEntry(getFileNameFromPath(file))
                out.putNextEntry(entry)
                var count: Int
                while (origin.read(data, 0, BUFFER).also { count = it } != -1) {
                    out.write(data, 0, count)
                }
                origin.close()
                if (DEBUG) Log.d(LOG_TAG_BACKUP_RESTORE, "$file added to zip, path: ${file.path}")
            }
            out.close()
            out.close()
            Log.i(LOG_TAG_BACKUP_RESTORE, "$files added to zip")
            true
        } catch (e: Exception) {
            Log.e(LOG_TAG_BACKUP_RESTORE, "error while adding files to zip dir, ${e.message}", e)
            false
        }
    }
}
