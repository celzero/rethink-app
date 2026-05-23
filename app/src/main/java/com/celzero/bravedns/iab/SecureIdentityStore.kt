/*
 * Copyright 2026 RethinkDNS and its authors
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
package com.celzero.bravedns.iab

import Logger
import Logger.LOG_IAB
import android.content.Context
import com.celzero.bravedns.service.EncryptedFileManager
import com.celzero.bravedns.service.EncryptionException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Secure, encrypted file-backed store for the customer identity pair
 * (accountId + deviceId).
 *
 * ### Environment isolation
 * Identities are kept **strictly separate** per [Env]:
 * - Production → `<filesDir>/pip/identity_prod.json`
 * - Test       → `<filesDir>/pip/identity_test.json`
 *
 * A CID/DID obtained from the production backend is **never** returned for a
 * test request, and vice-versa.  Every [save], [get], and [clear] call must
 * supply the [Env] that matches the API endpoint used to obtain the IDs.
 *
 * ### Storage
 * Each file is AES-256-GCM encrypted via [EncryptedFileManager] (Jetpack Security
 * [androidx.security.crypto.EncryptedFile] + [androidx.security.crypto.MasterKey]).
 * Files are private to the app process and are never stored in plain text or
 * SharedPreferences.
 *
 * ### Atomicity
 * [save] writes both IDs together in a single encrypted write, so the file is never
 * left in a state where one ID is persisted but not the other.
 *
 * ### Corruption handling
 * If an encrypted file is missing, corrupted, or the keyset is invalidated
 * (e.g. after a factory reset without full data wipe), [get] returns `null` for
 * both IDs.  Callers should treat this as "IDs not available" and re-fetch from
 * the `/customer` server endpoint.
 *
 * ### Threading
 * All I/O is dispatched on [Dispatchers.IO].  No in-process caching is performed
 * here; callers own their own in-memory caching strategy.
 */
class SecureIdentityStore(private val context: Context) {

    /**
     * Distinguishes between the production and test billing backends.
     *
     * Every identity store operation is scoped to one of these environments so that
     * a CID/DID registered against the production server is never accidentally used
     * in a test request, and vice-versa.
     */
    enum class Env {
        /** Use when the CID/DID was (or will be) created via the production billing URL. */
        PROD,
        /** Use when the CID/DID was (or will be) created via the test billing URL. */
        TEST;

        /** Human-readable label used in log messages and file names. */
        val label: String get() = name.lowercase()
    }

    companion object {
        private const val TAG = "SecureIdentityStore"

        private const val IDENTITY_FOLDER        = "pip"
        private const val IDENTITY_FILE_PROD     = "identity_prod.json"
        private const val IDENTITY_FILE_TEST     = "identity_test.json"
        /**
         * Legacy file name used before environment-scoped storage was introduced.
         * Callers should use [migrateFromLegacy] to move its contents to the correct
         * env-scoped file and then delete it.  No code should write to this file.
         */
        internal const val IDENTITY_FILE_LEGACY  = "identity.json"

        private const val KEY_ACCOUNT_ID         = "accountId"
        private const val KEY_DEVICE_ID          = "deviceId"
    }

    /**
     * Returns `true` when the legacy pre-env identity file (`identity.json`) is still
     * present on disk.  Use this as a cheap guard before calling [migrateFromLegacy].
     */
    fun hasLegacyIdentity(): Boolean = legacyIdentityFile().exists()

    /**
     * Reads the legacy `identity.json` without deleting it or writing to any env file.
     *
     * Returns `Pair(null, null)` when the file is absent, empty, corrupt, or partially
     * written.  Callers should treat a null-pair as "nothing to migrate".
     */
    suspend fun readLegacy(): Pair<String?, String?> = withContext(Dispatchers.IO) {
        val file = legacyIdentityFile()
        if (!file.exists()) return@withContext Pair(null, null)
        try {
            val raw = EncryptedFileManager.read(context, file)
            if (raw.isBlank()) {
                Logger.w(LOG_IAB, "$TAG readLegacy: legacy file is empty")
                return@withContext Pair(null, null)
            }
            val json      = org.json.JSONObject(raw)
            val accountId = json.optString(KEY_ACCOUNT_ID, "").takeIf { it.isNotBlank() }
            val deviceId  = json.optString(KEY_DEVICE_ID,  "").takeIf { it.isNotBlank() }
            if (accountId == null || deviceId == null) {
                Logger.w(LOG_IAB, "$TAG readLegacy: partial record " +
                    "(accNull=${accountId == null}, devNull=${deviceId == null})")
                return@withContext Pair(null, null)
            }
            Logger.d(LOG_IAB, "$TAG readLegacy: read legacy identity " +
                "(accLen=${accountId.length}, devLen=${deviceId.length})")
            Pair(accountId, deviceId)
        } catch (e: EncryptionException) {
            Logger.e(LOG_IAB, "$TAG readLegacy: decryption failure ${e.message}", e)
            Pair(null, null)
        } catch (e: org.json.JSONException) {
            Logger.e(LOG_IAB, "$TAG readLegacy: JSON parse failure ${e.message}", e)
            Pair(null, null)
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG readLegacy: unexpected error ${e.message}", e)
            Pair(null, null)
        }
    }

    /**
     * One-time migration: moves the legacy `identity.json` identity into the env-scoped
     * file for [env], then deletes the legacy file.
     *
     * ### Migration policy
     * - If the env-scoped file already contains a **valid** (non-blank) identity the
     *   legacy data is **not** overwritten — the already-present env identity takes
     *   precedence and the legacy file is simply deleted.
     * - If the env-scoped file is absent or empty the legacy data is written into it.
     * - If the legacy file cannot be decrypted or is corrupt the legacy file is deleted
     *   with no env-scoped write so the next [refreshIdentity] fetches fresh IDs.
     * - The legacy file is always deleted at the end, regardless of outcome, so this
     *   method is safe to call multiple times (idempotent once the legacy file is gone).
     *
     * @param env The environment ([Env.PROD] or [Env.TEST]) that the stored identity
     *            belongs to, determined by the caller from the live entitlement or
     *            [PersistentState.appTestMode] as a fallback.
     */
    suspend fun migrateFromLegacy(env: Env) = withContext(Dispatchers.IO) {
        val legacyFile = legacyIdentityFile()
        if (!legacyFile.exists()) return@withContext  // already migrated or never existed

        val (legacyAcc, legacyDev) = readLegacy()

        if (legacyAcc.isNullOrBlank() || legacyDev.isNullOrBlank()) {
            // Legacy file is empty / corrupt — delete it, nothing to migrate.
            Logger.w(LOG_IAB, "$TAG migrateFromLegacy[${env.label}]: legacy identity blank/corrupt; deleting legacy file")
            safeDeleteFile(legacyFile)
            return@withContext
        }

        // Only write to the env-scoped file if it does not already have valid data.
        val envFile = identityFile(env)
        val alreadyPresent = try {
            if (envFile.exists()) {
                val raw  = EncryptedFileManager.read(context, envFile)
                val json = org.json.JSONObject(raw)
                val existingAcc = json.optString(KEY_ACCOUNT_ID, "")
                val existingDev = json.optString(KEY_DEVICE_ID,  "")
                existingAcc.isNotBlank() && existingDev.isNotBlank()
            } else {
                false
            }
        } catch (e: Exception) {
            Logger.w(LOG_IAB, "$TAG migrateFromLegacy[${env.label}]: could not read env file, will overwrite: ${e.message}")
            false
        }

        if (alreadyPresent) {
            Logger.i(LOG_IAB, "$TAG migrateFromLegacy[${env.label}]: env-scoped file already has valid identity; legacy identity discarded")
        } else {
            save(env, legacyAcc, legacyDev)
            Logger.i(LOG_IAB, "$TAG migrateFromLegacy[${env.label}]: migrated legacy identity " +
                "(accLen=${legacyAcc.length}, devLen=${legacyDev.length})")
        }

        safeDeleteFile(legacyFile)
        Logger.i(LOG_IAB, "$TAG migrateFromLegacy[${env.label}]: legacy file deleted")
    }

    private fun legacyIdentityFile(): File =
        File(File(context.filesDir, IDENTITY_FOLDER), IDENTITY_FILE_LEGACY)

    /**
     * Saves [accountId] and [deviceId] atomically to the encrypted store for [env].
     *
     * Both values must be non-blank.  If either is blank the call is a no-op
     * (logged at WARN level) to prevent writing a half-initialized identity.
     *
     * @param env         The environment ([Env.PROD] or [Env.TEST]) this identity belongs to.
     * @param accountId   The customer account ID (CID) obtained from the server.
     * @param deviceId    The device ID (DID) obtained from the server.
     */
    suspend fun save(env: Env, accountId: String, deviceId: String) = withContext(Dispatchers.IO) {
        if (accountId.isBlank() || deviceId.isBlank()) {
            Logger.w(LOG_IAB, "$TAG save[${env.label}]: skipping blank value(s) " +
                "(accBlank=${accountId.isBlank()}, devBlank=${deviceId.isBlank()})")
            return@withContext
        }
        try {
            val json = JSONObject().apply {
                put(KEY_ACCOUNT_ID, accountId)
                put(KEY_DEVICE_ID,  deviceId)
            }
            val file = identityFile(env)
            file.parentFile?.mkdirs()
            EncryptedFileManager.write(context, json.toString(), file)
            Logger.d(LOG_IAB, "$TAG save[${env.label}]: identity written " +
                "(accLen=${accountId.length}, devLen=${deviceId.length})")
        } catch (e: EncryptionException) {
            Logger.e(LOG_IAB, "$TAG save[${env.label}]: encryption error ${e.message}", e)
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG save[${env.label}]: unexpected error ${e.message}", e)
        }
    }

    /**
     * Reads and returns the stored (accountId, deviceId) pair for [env].
     *
     * Returns `Pair(null, null)` when:
     * - the identity file for [env] has never been written,
     * - the file is corrupted / the keyset has been invalidated,
     * - either stored value is blank (partial / corrupt record).
     *
     * @param env The environment whose identity should be retrieved.
     */
    suspend fun get(env: Env): Pair<String?, String?> = withContext(Dispatchers.IO) {
        val file = identityFile(env)
        if (!file.exists()) {
            Logger.d(LOG_IAB, "$TAG get[${env.label}]: identity file does not exist")
            return@withContext Pair(null, null)
        }
        try {
            val raw = EncryptedFileManager.read(context, file)
            if (raw.isBlank()) {
                Logger.w(LOG_IAB, "$TAG get[${env.label}]: identity file is empty")
                return@withContext Pair(null, null)
            }
            val json = JSONObject(raw)
            val accountId = json.optString(KEY_ACCOUNT_ID, "").takeIf { it.isNotBlank() }
            val deviceId  = json.optString(KEY_DEVICE_ID,  "").takeIf { it.isNotBlank() }

            if (accountId == null || deviceId == null) {
                Logger.w(LOG_IAB, "$TAG get[${env.label}]: partial record " +
                    "accNull=${accountId == null}, devNull=${deviceId == null}")
                return@withContext Pair(null, null)
            }
            Logger.d(LOG_IAB, "$TAG get[${env.label}]: identity loaded " +
                "(accLen=${accountId.length}, devLen=${deviceId.length})")
            Pair(accountId, deviceId)
        } catch (e: EncryptionException) {
            Logger.e(LOG_IAB, "$TAG get[${env.label}]: decryption failure ${e.message}", e)
            Pair(null, null)
        } catch (e: org.json.JSONException) {
            Logger.e(LOG_IAB, "$TAG get[${env.label}]: JSON parse failure ${e.message}", e)
            // Delete the corrupt file so the next save() starts fresh.
            safeDeleteFile(identityFile(env))
            Pair(null, null)
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG get[${env.label}]: unexpected error ${e.message}", e)
            Pair(null, null)
        }
    }

    /**
     * Deletes the stored identity file for [env].
     *
     * After this call [get] will return `Pair(null, null)` for [env] until a fresh
     * [save] is performed.  Typically called when a server-side account reset is
     * detected and new IDs must be fetched for that environment.
     *
     * @param env The environment whose identity should be cleared.
     */
    suspend fun clear(env: Env) = withContext(Dispatchers.IO) {
        safeDeleteFile(identityFile(env))
        Logger.i(LOG_IAB, "$TAG clear[${env.label}]: identity file deleted")
    }

    /**
     * Deletes identity files for **all** environments.
     *
     * Use only when a full identity reset is required (e.g. app data wipe or
     * unrecoverable keyset invalidation).  For a single-environment reset prefer [clear].
     */
    @Suppress("unused")
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        Env.entries.forEach { env ->
            safeDeleteFile(identityFile(env))
        }
        Logger.i(LOG_IAB, "$TAG clearAll: identity files deleted for all environments")
    }

    private fun identityFile(env: Env): File {
        val dir = File(context.filesDir, IDENTITY_FOLDER)
        val fileName = when (env) {
            Env.PROD -> IDENTITY_FILE_PROD
            Env.TEST -> IDENTITY_FILE_TEST
        }
        return File(dir, fileName)
    }

    private fun safeDeleteFile(file: File) {
        try {
            if (file.exists()) {
                val deleted = file.delete()
                Logger.d(LOG_IAB, "$TAG safeDeleteFile: ${file.name} deleted=$deleted")
            }
        } catch (e: Exception) {
            Logger.w(LOG_IAB, "$TAG safeDeleteFile: failed to delete ${file.name} ${e.message}")
        }
    }
}

