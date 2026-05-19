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
import com.celzero.bravedns.service.PersistentState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Secure, encrypted file-backed store for the customer identity pair
 * (accountId + deviceId).
 *
 * ### Storage
 * Identities are written to a single JSON file under:
 *   `<filesDir>/pip/identity.json`
 * The file is AES-256-GCM encrypted via [EncryptedFileManager] (Jetpack Security
 * [androidx.security.crypto.EncryptedFile] + [androidx.security.crypto.MasterKey]).
 * It is private to the app process and never stored in plain text or SharedPreferences.
 *
 * ### Atomicity
 * [save] writes both IDs together in a single encrypted write, so the file is never
 * left in a state where one ID is persisted but not the other.
 *
 * ### Corruption handling
 * If the encrypted file is missing, corrupted, or the keyset is invalidated
 * (e.g. after a factory reset without full data wipe), [get] returns `null` for
 * both IDs.  Callers should treat this as "IDs not available" and re-fetch from
 * the `/customer` server endpoint.
 *
 * ### Threading
 * All I/O is dispatched on [Dispatchers.IO].  No in-process caching is performed
 * here; callers own their own in-memory caching strategy.
 */
class SecureIdentityStore(private val context: Context) {

    companion object {
        private const val TAG = "SecureIdentityStore"

        private const val IDENTITY_FOLDER  = "pip"
        private const val IDENTITY_FILE    = "identity.json"

        private const val KEY_ACCOUNT_ID   = "accountId"
        private const val KEY_DEVICE_ID    = "deviceId"
    }

    /**
     * Saves [accountId] and [deviceId] atomically to encrypted storage.
     *
     * Both values must be non-blank.  If either is blank the call is a no-op
     * (logged at WARN level) to prevent writing a half-initialized identity.
     *
     */
    suspend fun save(accountId: String, deviceId: String) = withContext(Dispatchers.IO) {
        if (accountId.isBlank() || deviceId.isBlank()) {
            Logger.w(LOG_IAB, "$TAG save: skipping blank value(s) " +
                "(accBlank=${accountId.isBlank()}, devBlank=${deviceId.isBlank()})")
            return@withContext
        }
        try {
            val json = JSONObject().apply {
                put(KEY_ACCOUNT_ID, accountId)
                put(KEY_DEVICE_ID,  deviceId)
            }
            val file = identityFile()
            file.parentFile?.mkdirs()
            EncryptedFileManager.write(context, json.toString(), file)
            Logger.d(LOG_IAB, "$TAG save: identity written " +
                "(accLen=${accountId.length}, devLen=${deviceId.length})")
        } catch (e: EncryptionException) {
            Logger.e(LOG_IAB, "$TAG save: encryption error ${e.message}", e)
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG save: unexpected error ${e.message}", e)
        }
    }

    /**
     * Reads and returns the stored (accountId, deviceId) pair.
     *
     * Returns `Pair(null, null)` when:
     * - the identity file has never been written,
     * - the file is corrupted / the keyset has been invalidated,
     * - either stored value is blank (partial / corrupt record).
     *
     */
    suspend fun get(): Pair<String?, String?> = withContext(Dispatchers.IO) {
        val file = identityFile()
        if (!file.exists()) {
            Logger.d(LOG_IAB, "$TAG get: identity file does not exist")
            return@withContext Pair(null, null)
        }
        try {
            val raw = EncryptedFileManager.read(context, file)
            if (raw.isBlank()) {
                Logger.w(LOG_IAB, "$TAG get: identity file is empty")
                return@withContext Pair(null, null)
            }
            val json = JSONObject(raw)
            val accountId = json.optString(KEY_ACCOUNT_ID, "").takeIf { it.isNotBlank() }
            val deviceId  = json.optString(KEY_DEVICE_ID,  "").takeIf { it.isNotBlank() }

            if (accountId == null || deviceId == null) {
                Logger.w(LOG_IAB, "$TAG get: partial record " +
                    "accNull=${accountId == null}, devNull=${deviceId == null}")
                return@withContext Pair(null, null)
            }
            Logger.d(LOG_IAB, "$TAG get: identity loaded " +
                "(accLen=${accountId.length}, devLen=${deviceId.length})")
            Pair(accountId, deviceId)
        } catch (e: EncryptionException) {
            Logger.e(LOG_IAB, "$TAG get: decryption failure ${e.message}", e)
            Pair(null, null)
        } catch (e: org.json.JSONException) {
            Logger.e(LOG_IAB, "$TAG get: JSON parse failure ${e.message}", e)
            // Delete the corrupt file so the next save() starts fresh.
            safeDeleteFile(identityFile())
            Pair(null, null)
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG get: unexpected error ${e.message}", e)
            Pair(null, null)
        }
    }

    /**
     * Deletes the stored identity file.
     *
     * After this call [get] will return `Pair(null, null)` until a fresh
     * [save] is performed.  Typically called when a server-side account
     * reset is detected and new IDs must be fetched.
     *
     */
    suspend fun clear() = withContext(Dispatchers.IO) {
        safeDeleteFile(identityFile())
        Logger.i(LOG_IAB, "$TAG clear: identity file deleted")
    }

    private fun identityFile(): File {
        val dir = File(context.filesDir, IDENTITY_FOLDER)
        return File(dir, IDENTITY_FILE)
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

