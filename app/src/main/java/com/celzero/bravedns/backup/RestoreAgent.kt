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
import android.content.pm.PackageInfo
import android.net.Uri
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.celzero.bravedns.backup.BackupHelper.Companion.BACKUP_WG_DIR
import com.celzero.bravedns.backup.BackupHelper.Companion.DATA_BUILDER_RESTORE_URI
import com.celzero.bravedns.backup.BackupHelper.Companion.METADATA_FILENAME
import com.celzero.bravedns.backup.BackupHelper.Companion.SHARED_PREFS_BACKUP_FILE_NAME
import com.celzero.bravedns.backup.BackupHelper.Companion.TEMP_WG_DIR
import com.celzero.bravedns.backup.BackupHelper.Companion.VERSION
import com.celzero.bravedns.backup.BackupHelper.Companion.deleteResidue
import com.celzero.bravedns.backup.BackupHelper.Companion.getTempDir
import com.celzero.bravedns.backup.BackupHelper.Companion.stopVpn
import com.celzero.bravedns.backup.BackupHelper.Companion.unzip
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.database.LogDatabase
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.deleteRecursive
import kotlinx.coroutines.delay
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.ObjectInputStream

class RestoreAgent(val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams), KoinComponent {

    private val logDatabase by inject<LogDatabase>()
    private val appDatabase by inject<AppDatabase>()
    private val appConfig by inject<AppConfig>()
    private val persistentState by inject<PersistentState>()

    companion object {
        const val TAG = "RestoreAgent"

        // sidecar files sqlite may place next to the main database file: WAL + shared
        // memory (WAL mode) and the rollback journal (TRUNCATE/PERSIST journal modes
        // seen on low-RAM devices or some OEM builds)
        private val DB_SIDECAR_SUFFIXES = listOf("-wal", "-shm", "-journal")

        // vpn stop is fire-and-forget (signalStopService); the service keeps flushing
        // connection summaries to the log database during async teardown. retries give
        // that teardown time to finish before the database files are replaced.
        private const val CLOSE_ATTEMPTS = 5
        private const val CLOSE_RETRY_DELAY_MS = 500L

        /**
         * Clears SubscriptionStatus and SubscriptionStateHistory tables after a restore.
         */
        suspend fun clearSubscriptionEntries(appDb: AppDatabase) {
            try {
                appDb.subscriptionStatusDao().deleteAll()
                appDb.subscriptionStateHistoryDao().deleteAll()
                Logger.i(
                    LOG_TAG_BACKUP_RESTORE,
                    "cleared subscription status and history entries during restore"
                )
            } catch (e: Exception) {
                // non-fatal: reconcileWithPlayBilling() will expire orphaned rows on the
                // next Play snapshot even if this cleanup fails
                Logger.crash(
                    LOG_TAG_BACKUP_RESTORE,
                    "err while clearing subscription entries during restore, reason? ${e.message}",
                    e
                )
            }
        }
    }

    override suspend fun doWork(): Result {
        Logger.i(LOG_TAG_BACKUP_RESTORE, "restore worker started, workId? $id, isStopped? $isStopped")
        val restoreUri = inputData.getString(DATA_BUILDER_RESTORE_URI)?.toUri()
        if (restoreUri == null) {
            Logger.w(LOG_TAG_BACKUP_RESTORE, "restore uri is null, return failure")
            return Result.failure()
        }

        Logger.d(LOG_TAG_BACKUP_RESTORE, "begin restore process with file uri: $restoreUri")
        val result = startRestore(restoreUri)

        Logger.i(LOG_TAG_BACKUP_RESTORE, "completed restore process, is successful? $result")
        return if (result) {
            Result.success()
        } else {
            Result.failure()
        }
    }

    private suspend fun startRestore(importUri: Uri): Boolean {
        var inputStream: InputStream? = null
        stopVpn(context)
        try {
            val tempDir = getTempDir(context)
            inputStream = context.contentResolver.openInputStream(importUri)

            Logger.d(LOG_TAG_BACKUP_RESTORE, "restore process, temp file dir: ${tempDir.path}")
            // unzip the backup files to tempDir
            if (!unzip(inputStream, tempDir.path)) {
                Logger.w(
                    LOG_TAG_BACKUP_RESTORE,
                    "failed to unzip the uri to temp dir $importUri, ${tempDir.path}, return failure"
                )
                return false
            } else {
                Logger.d(LOG_TAG_BACKUP_RESTORE, "restore process, unzipped the files to temp dir")
                // proceed
            }

            if (!validateMetadata(tempDir.path)) {
                Logger.w(
                    LOG_TAG_BACKUP_RESTORE,
                    "invalid meta-data or metadata not found. maybe earlier version backup"
                )
                return false
            } else {
                Logger.i(LOG_TAG_BACKUP_RESTORE, "metadata file validation complete")
                // no-op; proceed
            }

            // copy SharedPreferences file to its directory,
            // if shared pref copy is succeeds then proceed to database restore else
            // return failed
            if (!restoreSharedPreferencesFromFile(tempDir.path)) {
                Logger.w(LOG_TAG_BACKUP_RESTORE, "failed to restore shared pref, return failure")
                return false
            } else {
                Logger.i(LOG_TAG_BACKUP_RESTORE, "shared pref restored to the temp dir")
                // proceed
            }

            // Copy new database file into its final directory
            if (!restoreDatabaseFile(tempDir)) {
                Logger.w(LOG_TAG_BACKUP_RESTORE, "failed to restore database, return failure")
                return false
            } else {
                Logger.i(LOG_TAG_BACKUP_RESTORE, "database restored to the temp dir")
                // proceed
            }

            // copy wireguard contents into temp_wg folder
            // if wireguard copy failed, the proceed with cleanup
            if (!restoreWireGuardFiles(tempDir)) {
                Logger.w(LOG_TAG_BACKUP_RESTORE, "failed to restore wireguard files, return failure")
                // clear WireGuard related entries from database
                wireGuardCleanup()
            } else {
                Logger.i(LOG_TAG_BACKUP_RESTORE, "wireguard files restored to the temp dir")
            }

            // open log database if its not open
            handleDatabaseInit()

            // NOTE: do NOT touch any DAO beyond this point in this process.
            // RoomDatabase.close() (called above to swap the files) permanently cancels
            // Room's internal transaction SupervisorJob and invalidates the pooled
            // connection of the Koin-singleton instance; a reopen restores the file
            // level (migrations, isOpen) but not those. Any DAO call here fails with
            // JobCancellationException / "Error code: 21, connection is closed" and
            // every later retry in this process fails the same way.
            // Post-restore DB work (subscription cleanup, blocklist tag seeding, wg
            // configs) therefore runs after the caller restarts the app, in
            // HomeScreenActivity's INTENT_RESTART_APP branch ->
            // RefreshDatabase.ACTION_REFRESH_RESTORE.

            // update app version after the restore process
            updateLatestVersion()

            // clean up the temp directory
            deleteRecursive(tempDir)

            // WG configs are restored during RefreshDatabase.ACTION_REFRESH_RESTORE
            // which runs after app restart with fresh Room connections.
            // The caller (HomeScreenActivity.observeRestoreWorker) triggers the
            // restart after this worker returns success.

            return true
        } catch (e: Exception) {
            Logger.crash(
                LOG_TAG_BACKUP_RESTORE,
                "exception during restore process, reason? ${e.message}",
                e
            )
            // a failure between closeDatabases() and handleDatabaseInit() (e.g. a failed
            // migration during reopen) leaves both Koin-singleton databases closed; the
            // next restore attempt would then fail at checkPoint() with
            // "Error code: 21, connection is closed". best-effort reopen so the app and
            // any retry start from open, consistent databases.
            reopenDatabases()
            return false
        } finally {
            inputStream?.close()
        }
    }

    private fun handleDatabaseInit() {
        // get writable database for logs
        if (!logDatabase.isOpen) {
            Logger.i(
                LOG_TAG_BACKUP_RESTORE,
                "log database is not open, perform writableDatabase operation"
            )
            logDatabase.openHelper.writableDatabase
        } else {
            // no-op
            Logger.vv(LOG_TAG_BACKUP_RESTORE, "log database is already open, no-op")
        }

        // get writable database for app
        if (!appDatabase.isOpen) {
            Logger.i(
                LOG_TAG_BACKUP_RESTORE,
                "app database is not open, perform writableDatabase operation"
            )
            appDatabase.openHelper.writableDatabase
        } else {
            // no-op
            Logger.vv(LOG_TAG_BACKUP_RESTORE, "app database is already open, no-op")
        }
    }


    // Restore database file stored at tempDir/nameOfFileToRestore.
    private suspend fun restoreDatabaseFile(tempDir: File): Boolean {
        checkPoint()

        // databases must be closed before their files are replaced on disk. Copying over
        // an open database leaves the live sqlite connection serving pages of the old
        // file: Room never re-checks the version/identity hash on an already-open db,
        // so a backup made by an older app version (smaller schema) is served as-is and
        // the first "select *" fails with "column does not exist" (no migration runs).
        // close is verified: if any connection survives the retries (e.g. a long
        // transaction in flight during vpn teardown), abort instead of copying over a
        // live database.
        if (!closeDatabases()) {
            Logger.w(
                LOG_TAG_BACKUP_RESTORE,
                "databases still open after retries; aborting database restore"
            )
            return false
        }

        Logger.d(LOG_TAG_BACKUP_RESTORE, "begin restore database to temp dir: ${tempDir.path}")

        val files = tempDir.listFiles()
        if (files == null) {
            Logger.w(LOG_TAG_BACKUP_RESTORE, "files to restore is empty, path: ${tempDir.path}")
            reopenDatabases()
            return false
        }

        val mainDbNames = listOf(AppDatabase.DATABASE_NAME, LogDatabase.LOGS_DATABASE_NAME)

        // validate the backup's main database files BEFORE touching the current ones.
        // users restore backups created long ago (and by uninstalled installs), so a
        // corrupt/truncated/0-byte file in the zip must not destroy the working
        // database; abort instead and let the caller surface the failure.
        files.filter { it.name in mainDbNames }.forEach { backupMain ->
            if (!AppDatabase.isValidSQLiteFile(backupMain)) {
                Logger.w(
                    LOG_TAG_BACKUP_RESTORE,
                    "backup db file is not a valid sqlite file: ${backupMain.name}, " +
                        "size: ${backupMain.length()}; aborting database restore"
                )
                reopenDatabases()
                return false
            }
        }

        // remove stale sidecar files of the current databases so they cannot be
        // recovered onto the restored main file. Sidecar handling is tolerant of
        // every on-disk state: WAL (normal), TRUNCATE journal (low-RAM devices,
        // "-journal" suffix), PERSIST journal (some OEMs), or no sidecars at all.
        deleteDatabaseSidecarFiles()

        val mainFiles = files.filter { it.name in mainDbNames }
        // a sidecar from the backup is only restored together with its own main file,
        // so a stray -wal/-shm in an old backup can never be recovered onto a main
        // file it does not belong to (SQLite checksums would discard it anyway, but
        // do not rely on that for data from an unknown device)
        val sidecarFiles = files.filter { file ->
            file.name != AppDatabase.DATABASE_NAME &&
                file.name != LogDatabase.LOGS_DATABASE_NAME &&
                mainDbNames.any { name -> file.name.startsWith(name) } &&
                DB_SIDECAR_SUFFIXES.any { file.name.endsWith(it) }
        }

        Logger.d(
            LOG_TAG_BACKUP_RESTORE,
            "restore db files, main: ${mainFiles.map { it.name }}, " +
                "sidecars: ${sidecarFiles.map { it.name }}"
        )

        for (file in mainFiles + sidecarFiles) {
            val currentDbFile = File(context.getDatabasePath(file.name).path)
            if (!Utilities.copy(file.path, currentDbFile.path)) {
                Logger.w(
                    LOG_TAG_BACKUP_RESTORE,
                    "restore process, failure copying database file: ${file.path} to ${currentDbFile.path}"
                )
                reopenDatabases()
                return false
            }
            Logger.i(
                LOG_TAG_BACKUP_RESTORE,
                "database file: ${file.name} backed up from ${file.path} to ${currentDbFile.path}"
            )
        }

        // close anything that may have auto-reopened on the old file during the copy
        // window (a DAO call from the UI or a background worker). the subsequent open
        // in handleDatabaseInit() must read the restored files fresh so Room runs its
        // version check and the migrations needed to bring an older backup's schema
        // (e.g. a CustomIp table without proxyId/proxyCC) up to the current version.
        // this pass is best-effort: the files are already swapped, a survivor gets
        // closed by handleDatabaseInit()'s reopen path below.
        closeDatabasesQuietly()

        return true
    }

    // attempts to close both databases until neither reports open. returns true only
    // when a full close was observed on the final check; on false the caller must not
    // assume the files are safe to replace.
    private suspend fun closeDatabases(): Boolean {
        for (attempt in 1..CLOSE_ATTEMPTS) {
            closeDatabasesQuietly()
            if (!appDatabase.isOpen && !logDatabase.isOpen) {
                return true
            }
            Logger.w(
                LOG_TAG_BACKUP_RESTORE,
                "databases still open (attempt $attempt/$CLOSE_ATTEMPTS), retrying"
            )
            delay(CLOSE_RETRY_DELAY_MS)
        }
        return !appDatabase.isOpen && !logDatabase.isOpen
    }

    private fun closeDatabasesQuietly() {
        // stack trace identifies exactly which code path (this worker, a concurrent
        // restore attempt, or anything else) is closing the databases
        val closer = Exception("closeDatabasesQuietly call site")
        try {
            if (appDatabase.isOpen) {
                appDatabase.close()
                Logger.w(LOG_TAG_BACKUP_RESTORE, "app database closed before restore", closer)
            }
        } catch (e: Exception) {
            Logger.w(LOG_TAG_BACKUP_RESTORE, "err closing app database before restore", e)
        }
        try {
            if (logDatabase.isOpen) {
                logDatabase.close()
                Logger.w(LOG_TAG_BACKUP_RESTORE, "log database closed before restore", closer)
            }
        } catch (e: Exception) {
            Logger.w(LOG_TAG_BACKUP_RESTORE, "err closing log database before restore", e)
        }
    }

    // remove stale wal/shm/journal sidecars of the current databases so they cannot be
    // recovered onto the restored main file. Sidecars shipped inside the backup (if any)
    // are copied afterwards and form a consistent set with the restored db.
    private fun deleteDatabaseSidecarFiles() {
        val names = listOf(AppDatabase.DATABASE_NAME, LogDatabase.LOGS_DATABASE_NAME)
        names.forEach { name ->
            DB_SIDECAR_SUFFIXES.forEach { suffix ->
                val sidecar = context.getDatabasePath(name + suffix)
                if (sidecar.exists() && !sidecar.delete()) {
                    Logger.w(
                        LOG_TAG_BACKUP_RESTORE,
                        "failed to delete database sidecar file: ${sidecar.path}"
                    )
                }
            }
        }
    }

    // reopen both databases after the files were replaced; Room will run the version
    // check on open and execute the migrations needed to bring an older backup's
    // schema (e.g. a CustomIp table without proxyId/proxyCC) up to the current version
    private fun reopenDatabases() {
        try {
            handleDatabaseInit()
        } catch (e: Exception) {
            Logger.crash(
                LOG_TAG_BACKUP_RESTORE,
                "err reopening databases during restore, reason? ${e.message}",
                e
            )
        }
    }

    private fun checkPoint() {
        Logger.i(LOG_TAG_BACKUP_RESTORE, "database checkpoint() during restore process")
        appDatabase.checkPoint()
        logDatabase.checkPoint()
        return
    }

    private fun restoreWireGuardFiles(dir: File): Boolean {
        if (!dir.exists()) {
            // no files to restore
            Logger.i(LOG_TAG_BACKUP_RESTORE, "no wireguard files to restore")
            return true
        }

        // store the wireguard files in the temp_wireguard folder and then copy it to the wireguard
        // folder during db updates, database update is handled in RefreshDatabase
        // clear if temp_wireguard folder is already there if not, create the folder
        val tempWgDir = File(context.filesDir, TEMP_WG_DIR)
        if (tempWgDir.exists()) {
            Logger.d(LOG_TAG_BACKUP_RESTORE, "$TEMP_WG_DIR folder exists, delete")
            tempWgDir.deleteRecursively()
        }

        if (!tempWgDir.mkdirs()) {
            Logger.w(LOG_TAG_BACKUP_RESTORE, "failed to create $TEMP_WG_DIR folder")
            return false
        }

        var totalCopied = 0

        // collect .conf files from the root of the temp dir
        val confFiles = mutableListOf<File>()
        dir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".conf")) {
                confFiles.add(file)
            } else {
                Logger.d(LOG_TAG_BACKUP_RESTORE, "not wg file, file name: ${file.name}")
            }
        }

        // also scan the wireguard/ subdirectory (where backup saves wg files)
        val backupWgSubDir = File(dir, BACKUP_WG_DIR)
        if (backupWgSubDir.exists() && backupWgSubDir.isDirectory) {
            Logger.i(LOG_TAG_BACKUP_RESTORE, "scanning wireguard/ subdirectory for .conf files")
            backupWgSubDir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".conf") && !confFiles.any { it.name == file.name }) {
                    confFiles.add(file)
                }
            }
        }

        Logger.i(LOG_TAG_BACKUP_RESTORE, "found ${confFiles.size} .conf files to restore")

        confFiles.forEach { file ->
            val currentWgFile = File(tempWgDir, file.name)
            if (!Utilities.copy(file.path, currentWgFile.path)) {
                Logger.w(
                    LOG_TAG_BACKUP_RESTORE,
                    "restore process, failure copying wireguard file: ${file.path} to ${currentWgFile.path}"
                )
                // no need to return false, proceed with the next file
                // missing files database entry will be handled (deleted) in RefreshDatabase
            } else {
                Logger.i(
                    LOG_TAG_BACKUP_RESTORE,
                    "wireguard file: ${file.name} backed up from ${file.path} to ${currentWgFile.path}"
                )
                totalCopied++
            }
        }
        Logger.i(LOG_TAG_BACKUP_RESTORE, "copied $totalCopied wireguard files to temp_wireguard")
        return true
    }

    private fun updateLatestVersion() {
        if (isNewVersion()) {
            persistentState.appVersion = getLatestVersion()
            Logger.i(LOG_TAG_BACKUP_RESTORE, "app version updated to ${persistentState.appVersion}")
        } else {
            Logger.i(LOG_TAG_BACKUP_RESTORE, "no need to update app version")
        }
    }

    private fun isNewVersion(): Boolean {
        val versionStored = persistentState.appVersion
        val version = getLatestVersion()
        return (version != 0 && version != versionStored)
    }

    @Suppress("DEPRECATION")
    private fun getLatestVersion(): Int {
        val pInfo: PackageInfo? =
            Utilities.getPackageMetadata(context.packageManager, context.packageName)
        return pInfo?.versionCode ?: 0
    }

    private fun validateMetadata(tempDirectory: String?): Boolean {
        // TODO: revisit this after v055 release
        if (isMetadataCompatible(tempDirectory)) {
            return true
        } else {
            // proceed with META_DATA_FILE validation
        }

        val file = File(tempDirectory, METADATA_FILENAME)
        var stream: InputStream? = null
        return try {
            stream = file.inputStream()
            val metadata = stream.bufferedReader().use { it.readText() }
            isVersionSupported(metadata)
        } catch (ex: Exception) {
            Logger.crash(
                LOG_TAG_BACKUP_RESTORE,
                "err while restoring metadata, reason? ${ex.message}",
                ex
            )
            false
        } finally {
            try {
                stream?.close()
            } catch (ex: IOException) {
                Logger.e(
                    LOG_TAG_BACKUP_RESTORE,
                    "err while restoring metadata, reason? ${ex.message}",
                    ex
                )
            }
        }
    }

    private fun wireGuardCleanup() {
        if (appConfig.isWireGuardEnabled()) {
            Logger.i(LOG_TAG_BACKUP_RESTORE, "wireGuard is enabled, reset the wireguard entries")
            appConfig.removeAllProxies()
        }
        // cleaning up the wireguard entries are handled in RefreshDatabase
    }

    private fun isMetadataCompatible(tempDirectory: String?): Boolean {

        val minVersionSupported = 24

        val input: ObjectInputStream?
        val prefsBackupFile = File(tempDirectory, SHARED_PREFS_BACKUP_FILE_NAME)
        try {
            input = ObjectInputStream(FileInputStream(prefsBackupFile))

            @Suppress("UNCHECKED_CAST")
            val pref: Map<String, *> = input.readObject() as Map<String, *>

            for (e in pref.entries) {
                val v: Any? = e.value
                val key: String = e.key

                if (key == PersistentState.APP_VERSION) {
                    val appVersion = v as Int
                if (appVersion >= minVersionSupported) {
                    Logger.d(
                        LOG_TAG_BACKUP_RESTORE,
                        "app version satisfies minAppVersion ($minVersionSupported), proceed with restore"
                    )
                    return true
                    } else {
                        // no-op
                    }
                } else {
                    // no-op
                }
            }
            return false
        } catch (e: Exception) {
            Logger.crash(
                LOG_TAG_BACKUP_RESTORE,
                "exception while restoring shared pref, reason? ${e.message}",
                e
            )
            return false
        }
    }

    private fun isVersionSupported(metadata: String): Boolean {
        try {
            val minVersionSupported = 24

            if (!metadata.contains(VERSION)) return false

            val versionDetails = metadata.split("|")
            if (versionDetails[0].isEmpty()) return false

            val version = versionDetails[0].split(":")[1].toIntOrNull() ?: 0

            // backup version should be equal to minVersionSupported (prior to that version
            // there is only one database), so do not consider the backups prior to that
            return version >= minVersionSupported && persistentState.appVersion >= version
        } catch (e: Exception) {
            Logger.crash(
                LOG_TAG_BACKUP_RESTORE,
                "error while reading metadata, reason? ${e.message}",
                e
            )
            return false
        }
    }

    private fun restoreSharedPreferencesFromFile(tempDirectory: String?): Boolean {
        var input: ObjectInputStream? = null
        val prefsBackupFile = File(tempDirectory, SHARED_PREFS_BACKUP_FILE_NAME)
        val currentSharedPreferences: SharedPreferences =
            PreferenceManager.getDefaultSharedPreferences(context)

        Logger.d(LOG_TAG_BACKUP_RESTORE, "shared pref file path: ${prefsBackupFile.path}")
        try {
            input = ObjectInputStream(FileInputStream(prefsBackupFile))
            val prefsEditor = currentSharedPreferences.edit()
            prefsEditor.clear()
            @Suppress("UNCHECKED_CAST")
            val pref: Map<String, *> = input.readObject() as Map<String, *>

            for (e in pref.entries) {
                if (!shouldRestorePref(e.key)) {
                    Logger.i(LOG_TAG_BACKUP_RESTORE, "Skipping security pref: ${e.key}")
                    continue
                }
                Logger.i(LOG_TAG_BACKUP_RESTORE, "Restoring shared pref: ${e.key}")
                val v: Any? = e.value
                val key: String = e.key

                when (v) {
                    is Boolean -> prefsEditor.putBoolean(key, (v as Boolean?)!!)
                    is Float -> prefsEditor.putFloat(key, (v as Float?)!!)
                    is Int -> prefsEditor.putInt(key, (v as Int?)!!)
                    is Long -> prefsEditor.putLong(key, (v as Long?)!!)
                    is String -> prefsEditor.putString(key, v as String?)
                }
            }
            prefsEditor.apply()
            Logger.i(
                LOG_TAG_BACKUP_RESTORE,
                "completed restore of shared pref values, ${pref.entries}"
            )
            return true
        } catch (e: Exception) {
            Logger.crash(
                LOG_TAG_BACKUP_RESTORE,
                "exception while restoring shared pref, reason? ${e.message}",
                e
            )
            return false
        } finally {
            deleteResidue(prefsBackupFile)
            try {
                input?.close()
            } catch (e: IOException) {
                // no-op
            }
        }
    }

    private fun shouldRestorePref(key: String): Boolean {
        return !key.contains("androidx.security", ignoreCase = true) &&
                !key.contains("keyset", ignoreCase = true) &&
                !key.contains("master_key", ignoreCase = true)
    }
}
