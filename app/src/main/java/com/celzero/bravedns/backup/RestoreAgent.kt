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
import android.content.pm.PackageInfo
import android.database.sqlite.SQLiteDatabase
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
import com.celzero.bravedns.backup.BackupHelper.Companion.getTempDir
import com.celzero.bravedns.backup.BackupHelper.Companion.stopVpn
import com.celzero.bravedns.backup.BackupHelper.Companion.unzip
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.database.LogDatabase
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_BACKUP_RESTORE
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.deleteRecursive
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.FileInputStream
import java.io.ObjectInputStream
import androidx.core.content.edit
import com.celzero.bravedns.BuildConfig
import com.celzero.bravedns.util.Logger.LOG_TAG_UI

class RestoreAgent(val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams), KoinComponent {

    private val logDatabase by inject<LogDatabase>()
    private val appDatabase by inject<AppDatabase>()
    private val appConfig by inject<AppConfig>()
    private val persistentState by inject<PersistentState>()

    companion object {
        const val TAG = "RestoreAgent"

        // backups older than this were made before the log database was split out
        // of the main database and cannot be restored
        private const val MIN_SUPPORTED_BACKUP_VERSION = 24

        // sidecar files sqlite may place next to a main database file: WAL + shared
        // memory (WAL mode) and the rollback journal (TRUNCATE/PERSIST journal modes
        // seen on low-RAM devices or some OEM builds)
        private val DB_SIDECAR_SUFFIXES = listOf("-wal", "-shm", "-journal")

        // file name prefix for the throwaway copies used to rehearse migrations
        private const val PROBE_PREFIX = "restore_probe_"

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
        stopVpn(context)
        try {
            val tempDir = getTempDir(context)
            Logger.d(LOG_TAG_BACKUP_RESTORE, "restore process, temp file dir: ${tempDir.path}")

            var unzipped = false
            context.contentResolver.openInputStream(importUri)?.use { input ->
                unzipped = unzip(input, tempDir.path)
            }
            if (!unzipped) {
                Logger.w(LOG_TAG_BACKUP_RESTORE, "failed to unzip the backup into ${tempDir.path}")
                return false
            }

            if (!validateMetadata(tempDir)) {
                Logger.w(LOG_TAG_BACKUP_RESTORE, "backup metadata missing or unsupported version")
                return false
            }

            if (!restoreSharedPreferencesFromFile(tempDir)) {
                Logger.w(LOG_TAG_BACKUP_RESTORE, "failed to restore shared preferences")
                return false
            }

            if (!restoreDatabaseFile(tempDir)) {
                Logger.w(LOG_TAG_BACKUP_RESTORE, "failed to restore databases")
                return false
            }

            if (!restoreWireGuardFiles(tempDir)) {
                Logger.w(LOG_TAG_BACKUP_RESTORE, "failed to restore wireguard files; clearing wireguard entries")
                wireGuardCleanup()
            }

            // HomeScreenActivity's INTENT_RESTART_APP branch ->
            // RefreshDatabase.ACTION_REFRESH_RESTORE.
            updateLatestVersion()

            deleteRecursive(tempDir)
            return true
        } catch (e: Exception) {
            Logger.crash(
                LOG_TAG_BACKUP_RESTORE,
                "exception during restore process, reason? ${e.message}",
                e
            )
            return false
        }
    }

    private fun validateMetadata(tempDir: File): Boolean {
        val prefsVersion = readPrefsBackupVersion(tempDir)
        if (prefsVersion != null && prefsVersion >= MIN_SUPPORTED_BACKUP_VERSION) return true

        val metadataVersion = readMetadataVersion(tempDir) ?: return false
        return metadataVersion >= MIN_SUPPORTED_BACKUP_VERSION &&
            persistentState.appVersion >= metadataVersion
    }

    private fun readPrefsBackupVersion(tempDir: File): Int? {
        return try {
            ObjectInputStream(FileInputStream(File(tempDir, SHARED_PREFS_BACKUP_FILE_NAME))).use { input ->
                @Suppress("UNCHECKED_CAST")
                val prefs = input.readObject() as? Map<String, *>
                prefs?.get(PersistentState.APP_VERSION) as? Int
            }
        } catch (e: Exception) {
            Logger.i(
                LOG_TAG_BACKUP_RESTORE,
                "no readable shared-prefs version in backup, will check metadata file: ${e.message}"
            )
            null
        }
    }

    private fun readMetadataVersion(tempDir: File): Int? {
        return try {
            val metadata = File(tempDir, METADATA_FILENAME).readText()
            if (!metadata.contains(VERSION)) return null
            // format: "version:<int>|package:<pkg>|createdTs:<ts>"
            metadata.split("|").first().split(":")[1].toIntOrNull()?.takeIf { it > 0 }
        } catch (e: Exception) {
            Logger.crash(
                LOG_TAG_BACKUP_RESTORE,
                "error while reading metadata, reason? ${e.message}",
                e
            )
            null
        }
    }

    private fun restoreSharedPreferencesFromFile(tempDir: File): Boolean {
        return try {
            val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
            ObjectInputStream(FileInputStream(File(tempDir, SHARED_PREFS_BACKUP_FILE_NAME))).use { input ->
                @Suppress("UNCHECKED_CAST")
                val backup = input.readObject() as Map<String, *>
                prefs.edit {
                    clear()
                    backup.forEach { (key, value) ->
                        if (!shouldRestorePref(key)) {
                            Logger.i(LOG_TAG_BACKUP_RESTORE, "skipping security pref: $key")
                            return@forEach
                        }
                        when (value) {
                            is Boolean -> putBoolean(key, value)
                            is Float -> putFloat(key, value)
                            is Int -> putInt(key, value)
                            is Long -> putLong(key, value)
                            is String -> putString(key, value)
                            else -> Logger.w(
                                LOG_TAG_BACKUP_RESTORE,
                                "skipping pref of unsupported type: $key"
                            )
                        }
                    }
                }
                Logger.i(LOG_TAG_BACKUP_RESTORE, "restored ${backup.size} shared pref entries")
            }
            true
        } catch (e: Exception) {
            Logger.crash(
                LOG_TAG_BACKUP_RESTORE,
                "exception while restoring shared pref, reason? ${e.message}",
                e
            )
            false
        }
    }

    // encryption key material must never come from a backup; the device generates
    // its own (androidx.security keyset, master key)
    private fun shouldRestorePref(key: String): Boolean {
        return !key.contains("androidx.security", ignoreCase = true) &&
                !key.contains("keyset", ignoreCase = true) &&
                !key.contains("master_key", ignoreCase = true)
    }

    // verdict for a single database file from the backup
    private enum class DbVerdict {
        // proven usable: swap it in
        RESTORE,

        // not usable by this build: keep the current database
        SKIP,

        // fatal: abort the whole database restore, change nothing on disk
        ABORT
    }

    // Replace the database files with the ones from the backup. Returns false only
    // for fatal problems (missing/corrupt files, or an app database newer than this
    // build); an individually unusable database is skipped and the rest proceeds.
    private fun restoreDatabaseFile(tempDir: File): Boolean {
        val files = tempDir.listFiles()
        if (files == null) {
            Logger.w(LOG_TAG_BACKUP_RESTORE, "files to restore is empty, path: ${tempDir.path}")
            return false
        }

        val mainNames = listOf(AppDatabase.DATABASE_NAME, LogDatabase.LOGS_DATABASE_NAME)
        val mainFiles = mainNames.map { name ->
            files.firstOrNull { it.name == name } ?: run {
                Logger.w(
                    LOG_TAG_BACKUP_RESTORE,
                    "backup is missing database file: $name, found: ${files.map { it.name }}"
                )
                return false
            }
        }

        // a file that is not even a sqlite database must never replace the working one
        mainFiles.forEach { file ->
            if (!AppDatabase.isValidSQLiteFile(file)) {
                Logger.w(
                    LOG_TAG_BACKUP_RESTORE,
                    "backup db file is not a valid sqlite file: ${file.name}, " +
                        "size: ${file.length()}; aborting database restore"
                )
                return false
            }
        }

        val verdicts = mainFiles.associateWith { assessBackupDb(it) }
        if (DbVerdict.ABORT in verdicts.values) return false

        val usable = verdicts.filterValues { it == DbVerdict.RESTORE }.keys

        // remove stale sidecar files of the current databases so they cannot be
        // recovered onto the restored main file. Sidecar handling is tolerant of
        // every on-disk state: WAL (normal), TRUNCATE journal (low-RAM devices,
        // "-journal" suffix), PERSIST journal (some OEMs), or no sidecars at all.
        deleteDatabaseSidecarFiles()

        usable.forEach { file ->
            if (!swapDatabaseFile(file)) return false
        }
        return true
    }

    private fun assessBackupDb(file: File): DbVerdict {
        val backupVersion = readUserVersion(file)
        val currentVersion = currentVersionOf(file.name)
        if (backupVersion > currentVersion) {
            Logger.w(
                LOG_TAG_BACKUP_RESTORE,
                "backup ${file.name} (v$backupVersion) is newer than the app (v$currentVersion)"
            )
            // Room cannot downgrade; a newer app database would brick the install,
            // a newer log database is simply not worth risking over current logs
            return if (file.name == AppDatabase.DATABASE_NAME) DbVerdict.ABORT else DbVerdict.SKIP
        }
        if (!probeMigrations(file)) {
            Logger.w(
                LOG_TAG_BACKUP_RESTORE,
                "backup ${file.name} cannot be migrated to the current schema; " +
                    "keeping the current database"
            )
            return DbVerdict.SKIP
        }
        return DbVerdict.RESTORE
    }

    private fun readUserVersion(dbFile: File): Int {
        return try {
            SQLiteDatabase.openDatabase(
                dbFile.path,
                null,
                SQLiteDatabase.OPEN_READONLY
            ).use { db ->
                db.rawQuery("PRAGMA user_version", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else -1
                }
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_BACKUP_RESTORE, "failed reading db version: ${dbFile.name}", e)
            -1
        }
    }

    // schema version this build is running, straight from the live singletons --
    // never hardcoded, so it can never drift from the migration chain
    private fun currentVersionOf(dbName: String): Int {
        return when (dbName) {
            AppDatabase.DATABASE_NAME -> appDatabase.openHelper.writableDatabase.version
            else -> logDatabase.openHelper.writableDatabase.version
        }
    }

    // Rehearse the restore on a throwaway copy: the backup file is copied to a
    // private probe name and opened through Room with the app's real migration
    // chain. Room migrates the copy from whatever version the backup carries up
    // to the current one and validates the resulting schema against the entities
    // on open.
    private fun probeMigrations(file: File): Boolean {
        val probeName = PROBE_PREFIX + file.name
        deleteDatabaseFilesByName(probeName)

        val probeFile = context.getDatabasePath(probeName)
        if (!Utilities.copy(file.path, probeFile.path)) {
            Logger.w(LOG_TAG_BACKUP_RESTORE, "failed to stage migration probe of ${file.name}")
            return false
        }

        try {
            val probeDb = when (file.name) {
                AppDatabase.DATABASE_NAME -> AppDatabase.restoreProbeBuilder(context, probeName)
                else -> LogDatabase.restoreProbeBuilder(context, probeName)
            }
            probeDb.openHelper.writableDatabase // runs migrations + Room schema validation
            probeDb.close()
            Logger.i(
                LOG_TAG_BACKUP_RESTORE,
                "backup ${file.name} passed migration probe (v${readUserVersion(file)})"
            )
            return true
        } catch (e: Exception) {
            Logger.w(
                LOG_TAG_BACKUP_RESTORE,
                "migration probe failed for ${file.name}: ${e.message}",
                e
            )
            return false
        } finally {
            deleteDatabaseFilesByName(probeName)
        }
    }

    // delete a database file and any sidecars sqlite may have created for it
    private fun deleteDatabaseFilesByName(name: String) {
        (listOf("") + DB_SIDECAR_SUFFIXES).forEach { suffix ->
            val file = context.getDatabasePath(name + suffix)
            if (file.exists() && !file.delete()) {
                Logger.w(LOG_TAG_BACKUP_RESTORE, "failed to delete database file: ${file.path}")
            }
        }
    }

    private fun deleteDatabaseSidecarFiles() {
        listOf(AppDatabase.DATABASE_NAME, LogDatabase.LOGS_DATABASE_NAME).forEach { name ->
            DB_SIDECAR_SUFFIXES.forEach { suffix ->
                deleteDatabaseFilesByName(name + suffix)
            }
        }
    }

    // Stage the backup file next to its destination and rename it into place
    private fun swapDatabaseFile(backupFile: File): Boolean {
        val dest = context.getDatabasePath(backupFile.name)
        val staged = File(dest.path + ".restore_staged")

        if (!Utilities.copy(backupFile.path, staged.path)) {
            Logger.w(
                LOG_TAG_BACKUP_RESTORE,
                "failed staging database file: ${backupFile.path} to ${staged.path}"
            )
            staged.delete()
            return false
        }

        if (staged.renameTo(dest)) {
            Logger.i(LOG_TAG_BACKUP_RESTORE, "database file: ${backupFile.name} restored to ${dest.path}")
            return true
        }

        // rename can fail if a stale file exists at the destination or the file
        // system refuses the swap; retry once without the old file, then fall
        // back to an in-place copy
        dest.delete()
        if (staged.renameTo(dest)) {
            Logger.i(LOG_TAG_BACKUP_RESTORE, "database file: ${backupFile.name} restored to ${dest.path}")
            return true
        }
        val copied = Utilities.copy(backupFile.path, dest.path)
        staged.delete()
        if (!copied) {
            Logger.w(LOG_TAG_BACKUP_RESTORE, "failed copying database file: ${backupFile.path} to ${dest.path}")
        }
        return copied
    }

    private fun restoreWireGuardFiles(dir: File): Boolean {
        // store the wireguard files in the temp_wireguard folder; copying them into
        // the live wireguard folder (and clearing stale database entries) is handled
        // by RefreshDatabase after the app restarts
        val tempWgDir = File(context.filesDir, TEMP_WG_DIR)
        tempWgDir.deleteRecursively()
        if (!tempWgDir.mkdirs()) {
            Logger.w(LOG_TAG_BACKUP_RESTORE, "failed to create $TEMP_WG_DIR folder")
            return false
        }

        // .conf files may sit at the root of old backups or in the wireguard/
        // subdirectory written by the current backup agent; accept both
        val confFiles = sequenceOf(dir, File(dir, BACKUP_WG_DIR))
            .flatMap { d -> d.listFiles()?.asSequence() ?: emptySequence() }
            .filter { it.isFile && it.name.endsWith(".conf") }
            .distinctBy { it.name }
            .toList()

        Logger.i(LOG_TAG_BACKUP_RESTORE, "found ${confFiles.size} wireguard config files to restore")
        confFiles.forEach { file ->
            val target = File(tempWgDir, file.name)
            if (!Utilities.copy(file.path, target.path)) {
                // keep going; RefreshDatabase drops config rows whose file is missing
                Logger.w(LOG_TAG_BACKUP_RESTORE, "failed copying wireguard file: ${file.path}")
            }
        }
        return true
    }

    private fun wireGuardCleanup() {
        if (appConfig.isWireGuardEnabled()) {
            Logger.i(LOG_TAG_BACKUP_RESTORE, "wireGuard is enabled, reset the wireguard entries")
            appConfig.removeAllProxies()
        }
        // cleaning up the wireguard entries are handled in RefreshDatabase
    }

    // endregion

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

    private fun getLatestVersion(): Int {
        Logger.i(LOG_TAG_UI, "base version code: ${BuildConfig.BASE_VERSION_CODE}")
        return BuildConfig.BASE_VERSION_CODE
    }
}
