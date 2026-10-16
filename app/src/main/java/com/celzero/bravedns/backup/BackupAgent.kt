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

import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_BACKUP_RESTORE
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import androidx.room.RoomDatabase
import androidx.sqlite.db.SimpleSQLiteQuery
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
import com.celzero.bravedns.backup.BackupHelper.Companion.getTempDir
import com.celzero.bravedns.backup.BackupHelper.Companion.startVpn
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.database.LogDatabase
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
    private val appDatabase by inject<AppDatabase>()
    private val logDatabase by inject<LogDatabase>()

    companion object {
        const val TAG = "BackupExport"

        // buffer size for zipping backup files
        private const val BUFFER_SIZE = 80000
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
        // the vpn was never stopped and the databases were never closed during the
        // backup, so there is no state to restore here
        startVpn(context)
        return if (isBackupSucceed) {
            Result.success()
        } else {
            Result.failure()
        }
    }

    private fun startBackupProcess(backupFileUri: Uri): Boolean {
        var processCompleted: Boolean
        try {
            // note: the vpn is NOT stopped and the Room instances are NOT closed for a
            // backup; snapshotDatabase() takes a consistent copy of the live databases.
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
                if (!file.exists()) {
                    Logger.w(LOG_TAG_BACKUP_RESTORE, "wg config file missing for ${m.id}, ${m.configPath}")
                    return@forEach
                }
                val content = file.readText(Charsets.UTF_8)
                if (content.isNotEmpty()) {
                    val tmpWgFile = File(dir, "${m.id}.conf")
                    tmpWgFile.writer().use { writer ->
                        writer.write(content)
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
        // snapshot the live databases without ever closing them
        if (!snapshotDatabase(appDatabase, AppDatabase.DATABASE_NAME, File(path))) return false
        if (!snapshotDatabase(logDatabase, LogDatabase.LOGS_DATABASE_NAME, File(path))) return false
        return true
    }

    // VACUUM INTO (sqlite >= 3.27, first shipped in Android 10 / API 29) writes a
    // consistent, fully checkpointed snapshot of the live database to a new file
    private fun snapshotDatabase(db: RoomDatabase, dbName: String, tempDir: File): Boolean {
        val dest = File(tempDir, dbName)
        if (dest.exists() && !dest.delete()) {
            Logger.w(LOG_TAG_BACKUP_RESTORE, "failed to delete stale snapshot: ${dest.path}")
            return false
        }
        try {
            db.openHelper.writableDatabase.execSQL("VACUUM INTO ?", arrayOf(dest.path))
            Logger.i(
                LOG_TAG_BACKUP_RESTORE,
                "$dbName snapshotted via VACUUM INTO, size: ${dest.length()}"
            )
            filesPathToZip.add(dest.absolutePath)
            return true
        } catch (e: Exception) {
            Logger.w(
                LOG_TAG_BACKUP_RESTORE,
                "VACUUM INTO failed for $dbName, falling back to checkpoint+copy: ${e.message}",
                e
            )
        }
        return try {
            db.openHelper.writableDatabase
                .query(SimpleSQLiteQuery("PRAGMA wal_checkpoint(TRUNCATE)"))
                .use { it.moveToFirst() }
            if (!Utilities.copy(context.getDatabasePath(dbName).path, dest.path)) {
                Logger.w(LOG_TAG_BACKUP_RESTORE, "failed to copy $dbName after checkpoint")
                false
            } else {
                Logger.i(
                    LOG_TAG_BACKUP_RESTORE,
                    "$dbName snapshotted via checkpoint+copy, size: ${dest.length()}"
                )
                filesPathToZip.add(dest.absolutePath)
                true
            }
        } catch (e: Exception) {
            Logger.w(LOG_TAG_BACKUP_RESTORE, "checkpoint+copy failed for $dbName: ${e.message}", e)
            false
        }
    }

    private fun zip(files: List<String>, zipDirectory: String): Boolean {
        val outputFileName = zipDirectory + File.separator + TEMP_ZIP_FILE_NAME
        Logger.d(LOG_TAG_BACKUP_RESTORE, "files: $files, output: $outputFileName")
        return try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(outputFileName))).use { out ->
                val buffer = ByteArray(BUFFER_SIZE)
                for (file in files) {
                    BufferedInputStream(FileInputStream(file), BUFFER_SIZE).use { origin ->
                        out.putNextEntry(ZipEntry(getFileNameFromPath(file)))
                        var count: Int
                        while (origin.read(buffer).also { count = it } != -1) {
                            out.write(buffer, 0, count)
                        }
                    }
                    Logger.d(LOG_TAG_BACKUP_RESTORE, "$file added to zip")
                }
            }
            Logger.i(LOG_TAG_BACKUP_RESTORE, "zipped ${files.size} files to $outputFileName")
            true
        } catch (e: Exception) {
            Logger.e(LOG_TAG_BACKUP_RESTORE, "error while adding files to zip dir, ${e.message}", e)
            false
        }
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
}
