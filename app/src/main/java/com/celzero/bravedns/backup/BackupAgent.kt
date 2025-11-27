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

import Logger
import Logger.LOG_TAG_BACKUP_RESTORE
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.celzero.bravedns.backup.BackupHelper.Companion.BACKUP_WG_DIR
import com.celzero.bravedns.backup.BackupHelper.Companion.CREATED_TIME
import com.celzero.bravedns.backup.BackupHelper.Companion.DATA_BUILDER_BACKUP_URI
import com.celzero.bravedns.backup.BackupHelper.Companion.METADATA_FILENAME
import com.celzero.bravedns.backup.BackupHelper.Companion.PACKAGE_NAME
import com.celzero.bravedns.backup.BackupHelper.Companion.SHARED_PREFS_BACKUP_FILE_NAME
import com.celzero.bravedns.backup.BackupHelper.Companion.TEMP_ZIP_FILE_NAME
import com.celzero.bravedns.backup.BackupHelper.Companion.VERSION
import com.celzero.bravedns.backup.BackupHelper.Companion.deleteResidue
import com.celzero.bravedns.backup.BackupHelper.Companion.getFileNameFromPath
import com.celzero.bravedns.backup.BackupHelper.Companion.getRethinkDatabase
import com.celzero.bravedns.backup.BackupHelper.Companion.getTempDir
import com.celzero.bravedns.backup.BackupHelper.Companion.startVpn
import com.celzero.bravedns.service.EncryptedFileManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.copyWithStream
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.ObjectOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// ref:
// https://gavingt.medium.com/refactoring-my-backup-and-restore-feature-to-comply-with-scoped-storage-e2b6c792c3b
class BackupAgent(val context: Context, workerParams: WorkerParameters) :
    Worker(context, workerParams), KoinComponent {

    var filesPathToZip: MutableList<String> = ArrayList()
    private val persistentState by inject<PersistentState>()

    companion object {
        const val TAG = "BackupExport"
    }

    override fun doWork(): Result {
        val backupFileUri = inputData.getString(DATA_BUILDER_BACKUP_URI)?.toUri()
        if (backupFileUri == null) {
            Logger.w(LOG_TAG_BACKUP_RESTORE, "backup file uri is null, return failure")
            return Result.failure()
        }

        Logger.d(LOG_TAG_BACKUP_RESTORE, "begin backup process with file uri: $backupFileUri")
        val isBackupSucceed = startBackupProcess(backupFileUri)

        Logger.i(
            LOG_TAG_BACKUP_RESTORE,
            "completed backup process, is backup successful? $isBackupSucceed"
        )
        if (isBackupSucceed) {
            startVpn(context)
            return Result.success()
        }
        return Result.failure()
    }

    private fun startBackupProcess(backupFileUri: Uri): Boolean {
        var processCompleted: Boolean
        try {
            val tempDir = getTempDir(context)

            val prefsBackupFile = File(tempDir, SHARED_PREFS_BACKUP_FILE_NAME)

            Logger.d(
                    LOG_TAG_BACKUP_RESTORE,
                    "backup process, temp file dir: ${tempDir.path}, prefs backup file: ${prefsBackupFile.path}"
                )
            processCompleted = saveSharedPreferencesToFile(context, prefsBackupFile)

            if (processCompleted) {
                Logger.d(LOG_TAG_BACKUP_RESTORE, "shared pref backup is added to the temp dir")
            } else {
                Logger.w(
                    LOG_TAG_BACKUP_RESTORE,
                    "failed to add shared pref to temp backup dir, return failure"
                )
                return false
            }

            processCompleted = saveDatabasesToFile(tempDir.path)

            if (processCompleted) {
                Logger.d(LOG_TAG_BACKUP_RESTORE, "database backup is added to the temp dir")
            } else {
                Logger.w(
                    LOG_TAG_BACKUP_RESTORE,
                    "failed to add database to temp backup dir, return failure"
                )
                return false
            }

            // no need to check the return value, we can proceed even if the wireguard config backup
            // fails
            backupWireGuardConfig(tempDir)

            processCompleted = createMetaData(tempDir)

            if (processCompleted) {
                Logger.d(LOG_TAG_BACKUP_RESTORE, "metadata is added to the temp dir")
            } else {
                Logger.w(LOG_TAG_BACKUP_RESTORE, "failed to create metadata file, return failure")
                return false
            }

            return zipAndCopyToDestination(tempDir, backupFileUri)
        } catch (e: Exception) {
            Logger.crash(
                LOG_TAG_BACKUP_RESTORE,
                "exception during backup process, reason? ${e.message}",
                e
            )
            return false
        } finally {
            for (filePath in filesPathToZip) {
                val file = File(filePath)
                deleteResidue(file)
            }
            filesPathToZip.clear()
        }
    }

    private fun backupWireGuardConfig(tempDir: File): Boolean {
        // get all the wireguard config from the database,
        // loop through them and get the path from the database
        // create a copy of a encrypted file to normal file in the temp dir
        // add the file to the zip list

        Logger.d(LOG_TAG_BACKUP_RESTORE, "init backup wireguard configs")
        val dir = File(tempDir, BACKUP_WG_DIR)
        if (!dir.exists()) {
            Logger.d(LOG_TAG_BACKUP_RESTORE, "creating wireguard backup dir, ${dir.path}")
            dir.mkdirs()
        }

        try {
            val mappings = WireguardManager.getAllMappings()
            mappings.forEach { m ->
                val file = File(m.configPath)
                val content = EncryptedFileManager.read(context, file)
                if (content.isNotEmpty()) {
                    val tmpWgFile = File(dir, "${m.id}.conf")
                    tmpWgFile.writer().use {
                        writer -> writer.write(content)
                        writer.flush()
                    }
                    filesPathToZip.add(tmpWgFile.absolutePath)
                    Logger.v(LOG_TAG_BACKUP_RESTORE, "wg ${m.id}.conf added to backup, path: ${tmpWgFile.path}")
                } else {
                    Logger.w(LOG_TAG_BACKUP_RESTORE, "empty config for ${m.id}, ${m.configPath}")
                }
            }
            return true
        } catch (e: Exception) {
            Logger.w(LOG_TAG_BACKUP_RESTORE, "err while backing up wg config, ${e.message}", e)
        }
        return false
    }

    private fun createMetaData(backupDir: File): Boolean {
        Logger.d(LOG_TAG_BACKUP_RESTORE, "creating meta data file, path: ${backupDir.path}")
        // check if the file exists already, if yes, delete it
        val file = File(backupDir, METADATA_FILENAME)
        if (file.exists()) {
            Logger.d(LOG_TAG_BACKUP_RESTORE, "metadata file exists, deleting it")
            file.delete()
            filesPathToZip.remove(file.absolutePath)
        }
        val metadata = backupMetadata()
        try {
            val metadataFile = File(backupDir, METADATA_FILENAME)
            metadataFile.writer().use {
                writer -> writer.write(metadata)
                writer.flush()
            }
            // add the metadata file to the list of files to be zipped
            filesPathToZip.add(metadataFile.absolutePath)
            return true
        } catch (e: Exception) {
            Logger.crash(
                LOG_TAG_BACKUP_RESTORE,
                "exception while creating meta data file, ${e.message}",
                e
            )
            return false
        }
    }

    private fun backupMetadata(): String {
        return "$VERSION:${persistentState.appVersion}|$PACKAGE_NAME:${context.packageName}|$CREATED_TIME:${SystemClock.elapsedRealtime()}"
    }

    private fun zipAndCopyToDestination(tempDir: File, destUri: Uri): Boolean {
        val bZipSucceeded: Boolean = zip(filesPathToZip, tempDir.path)

        Logger.i(
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
                Logger.i(
                    LOG_TAG_BACKUP_RESTORE,
                    "Copy completed, delete the temp dir ${tempZipFile.path}"
                )
                deleteResidue(tempZipFile)
                true
            } else {
                Logger.w(
                    LOG_TAG_BACKUP_RESTORE,
                    "copy failed to destination dir, path: ${zipFileUri.path}"
                )
                false
            }
        } else {
            Logger.w(LOG_TAG_BACKUP_RESTORE, "backup zip failed, do not proceed")
            return false
        }
    }

    private fun saveDatabasesToFile(path: String): Boolean {
        val files = getRethinkDatabase(context)?.listFiles() ?: return false

        for (f in files) {
            Logger.d(
                    LOG_TAG_BACKUP_RESTORE,
                    "file ${f.name} found in database dir (${f.absolutePath})"
                )
            // looks like the journal, shm and wal files are needed for proper restore, so
            // commenting out the below code. still testing it out.
            // TODO: check if the journal files are needed for restore
            // skip journal files, they are not needed for restore
            /*if (f.path.endsWith("-journal") || f.path.endsWith("-shm") || f.path.endsWith("-wal")) {
                continue
            }*/
            val databaseFile =
                backUpFile(f.absolutePath, constructDbFileName(path, f.name)) ?: return false
            Logger.i(LOG_TAG_BACKUP_RESTORE, "file ${databaseFile.name} added to backup dir")
            filesPathToZip.add(databaseFile.absolutePath)
        }

        return true
    }

    private fun constructDbFileName(path: String, fileName: String): String {
        return path + File.separator + fileName
    }

    private fun saveSharedPreferencesToFile(context: Context, prefFile: File): Boolean {
        var output: ObjectOutputStream? = null

        Logger.i(LOG_TAG_BACKUP_RESTORE, "begin shared pref copy, file path:${prefFile.path}")
        val sharedPrefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

        try {
            output = ObjectOutputStream(FileOutputStream(prefFile))
            val allPrefs = sharedPrefs.all
            output.writeObject(allPrefs)
        } catch (e: Exception) {
            Logger.crash(LOG_TAG_BACKUP_RESTORE, "exception during shared pref backup, ${e.message}", e)
            return false
        } finally {
            try {
                if (output != null) {
                    output.flush()
                    output.close()
                }
            } catch (_: IOException) {
                // no-op
            }
        }
        filesPathToZip.add(prefFile.absolutePath)
        return true
    }

    private fun backUpFile(backupFilePath: String?, destFilePath: String?): File? {
        if (backupFilePath == null || destFilePath == null) {
            Logger.w(
                LOG_TAG_BACKUP_RESTORE,
                "invalid backup info during db backup, file: $backupFilePath, destination: $destFilePath"
            )
            return null
        }
        val isCopySuccess = Utilities.copy(backupFilePath, destFilePath)
        if (isCopySuccess) return File(destFilePath)

        return null
    }

    private fun zip(files: List<String>, zipDirectory: String): Boolean {
        val outputFileName = zipDirectory + File.separator + TEMP_ZIP_FILE_NAME
        Logger.d(LOG_TAG_BACKUP_RESTORE, "files: $files, output: $outputFileName")
        return try {
            val dest = FileOutputStream(outputFileName)
            val out = ZipOutputStream(BufferedOutputStream(dest))
            val bufferSize = 80000
            var origin: BufferedInputStream
            val data = ByteArray(bufferSize)
            for (file in files) {
                val fi = FileInputStream(file)
                origin = BufferedInputStream(fi, bufferSize)
                val entry = ZipEntry(getFileNameFromPath(file))
                out.putNextEntry(entry)
                var count: Int
                while (origin.read(data, 0, bufferSize).also { count = it } != -1) {
                    out.write(data, 0, count)
                }
                origin.close()
                Logger.d(LOG_TAG_BACKUP_RESTORE, "$file added to zip, path: $file")
            }
            out.close()
            out.close()
            Logger.i(LOG_TAG_BACKUP_RESTORE, "$files added to zip")
            true
        } catch (e: Exception) {
            Logger.e(LOG_TAG_BACKUP_RESTORE, "error while adding files to zip dir, ${e.message}", e)
            false
        }
    }
}
