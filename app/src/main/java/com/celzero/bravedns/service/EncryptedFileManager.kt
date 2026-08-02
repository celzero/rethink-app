/*
 * Copyright 2023 RethinkDNS and its authors
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
@file:Suppress("DEPRECATION")
package com.celzero.bravedns.service

import com.celzero.bravedns.util.Logger
import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.Severity
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.FileNotFoundException
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import androidx.core.content.edit

/**
 * Critical encryption exceptions that indicate unrecoverable keystore failures.
 * These must be logged to event logs and handled at the call site.
 */
sealed class EncryptionException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /**
     * The encryption key was permanently invalidated (device lock screen removed, etc.)
     * Old encrypted data is unreadable. Requires re-encryption with new key.
     */
    class KeyInvalidated(cause: Throwable) : EncryptionException("Encryption key permanently invalidated", cause)

    /**
     * Authentication required but not provided (biometric/PIN)
     * This is usually a device policy issue.
     */
    class AuthRequired(cause: Throwable) : EncryptionException("User authentication required", cause)

    /**
     * Decryption failed - data is corrupted or encrypted with different key
     * This typically means key changed or data corrupted.
     */
    class DecryptionFailed(cause: Throwable) : EncryptionException("Decryption failed - data corrupted or key mismatch", cause)

    /**
     * General keystore/crypto failure
     */
    class KeystoreError(cause: Throwable) : EncryptionException("Keystore operation failed", cause)

    /**
     * File I/O error during encryption/decryption
     */
    class IOError(cause: Throwable) : EncryptionException("I/O error during encryption operation", cause)
}

object EncryptedFileManager : KoinComponent {
    private const val LOG_TAG = "EncryptedFileManager"

    // Constants for clearing Tink keyset stored in SharedPreferences.
    // These match the package-private constants in androidx.security.crypto.EncryptedFile
    // (KEYSET_PREF_NAME and KEYSET_ALIAS) used via AndroidKeysetManager.
    private const val TINK_KEYSET_PREF_NAME = "__androidx_security_crypto_encrypted_file_pref__"
    private const val TINK_KEYSET_KEY = "__androidx_security_crypto_encrypted_file_keyset__"

    // Suffix used for the temp-file fallback when a target cannot be deleted in place
    // (see [write] / [writeViaTempFile]).
    private const val TEMP_FILE_SUFFIX = ".write.tmp"

    // Inject EventLogger for critical failure logging
    private val eventLogger by inject<EventLogger>()

    /**
     * Maps raw exceptions to typed EncryptionException with event logging
     */
    private fun handleCriticalException(e: Exception, operation: String, file: String): EncryptionException {
        // Missing files are not crypto failures; return an I/O classification without spamming critical events.
        if (e is FileNotFoundException) {
            val ex = EncryptionException.IOError(e)
            Logger.w(LOG_TAG, "$operation missing file: $file; ${e.message}")
            return ex
        }
        val cryptoException = when (e) {
            is KeyPermanentlyInvalidatedException -> {
                EncryptionException.KeyInvalidated(e)
            }
            is UserNotAuthenticatedException -> {
                EncryptionException.AuthRequired(e)
            }
            is AEADBadTagException -> {
                // This means decryption failed - wrong key or corrupted data
                EncryptionException.DecryptionFailed(e)
            }
            is GeneralSecurityException -> {
                EncryptionException.KeystoreError(e)
            }
            is java.io.IOException -> {
                // Tink wraps streaming-AEAD decryption failures (ciphertext encrypted
                // with a key that is no longer in the keyset, e.g. after a keyset reset
                // caused by a dev/reinstall that wiped SharedPreferences) in plain
                // java.io.IOException with messages like: "No matching key found for the
                // ciphertext in the stream." That is a decryption/key-mismatch failure, NOT
                // a disk I/O failure, so classify it as DecryptionFailed so callers can treat the
                // file as a key mismatch file and recover from authoritative sources.
                if (isTinkDecryptionFailure(e)) {
                    EncryptionException.DecryptionFailed(e)
                } else {
                    EncryptionException.IOError(e)
                }
            }
            else -> {
                // Unknown exception - wrap as keystore error
                EncryptionException.KeystoreError(e)
            }
        }

        // a DecryptionFailed (key mismatch / corrupt ciphertext after a signing-key change,
        // keyset reset, or the cross-file cascade) or a generic I/O error is RECOVERABLE
        //
        // callers delete the stale file and re-derive from authoritative sources (DB / Play
        // billing / server).
        val isRecoverable = cryptoException is EncryptionException.DecryptionFailed
                || cryptoException is EncryptionException.IOError

        val verb = if (isRecoverable) "unreadable (recoverable, will regenerate)" else "failed"
        val message = "$operation $verb for file: $file"
        val details = "${cryptoException::class.simpleName}: ${cryptoException.message}\nCause: ${e::class.simpleName}: ${e.message}"

        if (isRecoverable) {
            // Expected during app install/update cycles; log at LOW for traceability,
            // not as an error.
            eventLogger.log(
                type = EventType.PROXY_ERROR,
                severity = Severity.LOW,
                message = message,
                source = EventSource.SYSTEM,
                userAction = false,
                details = details
            )
            Logger.w(LOG_TAG, "$message: ${cryptoException.message}")
        } else {
            eventLogger.log(
                type = EventType.PROXY_ERROR,
                severity = Severity.CRITICAL,
                message = message,
                source = EventSource.SYSTEM,
                userAction = false,
                details = details
            )
            Logger.e(LOG_TAG, "$message: ${cryptoException.message}")
        }
        return cryptoException
    }

    /**
     * Clears the corrupted Tink keyset from SharedPreferences so that subsequent
     * [write] operations can succeed with a freshly generated keyset.
     *
     * After clearing, all existing encrypted files become permanently unreadable
     * because they were encrypted with the old (now-discarded) keyset. Callers must
     * regenerate their data from authoritative sources (backend, Play billing, etc.).
     */
    private fun destroyCorruptedKeyset(ctx: Context) {
        try {
            val prefs = ctx.applicationContext.getSharedPreferences(
                TINK_KEYSET_PREF_NAME,
                Context.MODE_PRIVATE
            )
            if (prefs.contains(TINK_KEYSET_KEY)) {
                prefs.edit { remove(TINK_KEYSET_KEY) }
                Logger.w(LOG_TAG, "Cleared corrupted Tink keyset from SharedPreferences; " +
                        "future writes will create a fresh keyset, existing encrypted files are unrecoverable")
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG, "Failed to clear corrupted Tink keyset", e)
        }
    }

    /**
     * Reads encrypted file as a String.
     *
     * @throws EncryptionException for any encryption/decryption failures
     */
    @Throws(EncryptionException::class)
    fun read(ctx: Context, file: File): String {
        val bytes = readByteArray(ctx, file)
        return bytes.toString(StandardCharsets.UTF_8)
    }

    /**
     * Reads encrypted file as a ByteArray.
     *
     * If keyset corruption is detected ([AEADBadTagException]), the corrupted keyset
     * and file are cleared so that subsequent *write* operations can succeed with a
     * fresh keyset. The read itself still throws [EncryptionException.DecryptionFailed]
     * because the old data is unrecoverable, callers must regenerate from authoritative
     * sources (backend, Play billing, etc.).
     *
     * @throws EncryptionException.KeyInvalidated if encryption key was invalidated
     * @throws EncryptionException.DecryptionFailed if decryption fails
     * @throws EncryptionException.KeystoreError for other crypto failures
     * @throws EncryptionException.IOError for file I/O failures
     */
    @Throws(EncryptionException::class)
    fun readByteArray(ctx: Context, file: File): ByteArray {
        try {
            val masterKey =
                MasterKey.Builder(ctx.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
            val encryptedFile =
                EncryptedFile.Builder(
                        ctx.applicationContext,
                        file,
                        masterKey,
                        EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
                    )
                    .build()

            return encryptedFile.openFileInput().use {
                it.readBytes()
            }
        } catch (e: Exception) {
            if (isKeysetCorruption(e)) {
                Logger.w(LOG_TAG, "Keyset corruption detected during read of ${file.absolutePath}, " +
                        "clearing corrupted state for future writes", e)
                destroyCorruptedKeyset(ctx)
            }
            throw handleCriticalException(e, "Read file", file.absolutePath)
        }
    }

    /**
     * Writes TCP proxy config to encrypted file.
     *
     * @throws EncryptionException for any encryption failures
     */
    @Throws(EncryptionException::class)
    fun writeTcpConfig(ctx: Context, cfg: String, fileName: String) {
        val dir =
            File(
                ctx.filesDir.canonicalPath +
                    File.separator +
                    TcpProxyHelper.TCP_FOLDER_NAME +
                    File.separator +
                    TcpProxyHelper.getFolderName()
            )
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val fileToWrite = File(dir, fileName)
        write(ctx, cfg, fileToWrite)
    }

    /**
     * Writes String data to encrypted file.
     *
     * @throws EncryptionException for any encryption failures
     */
    @Throws(EncryptionException::class)
    fun write(ctx: Context, data: String, file: File): Boolean {
        val d = data.toByteArray(StandardCharsets.UTF_8)
        return write(ctx, d, file)
    }

    /**
     * Writes ByteArray data to encrypted file.
     *
     * androidx.security.crypto.EncryptedFile is *write-once*: [EncryptedFile.openFileOutput]
     * throws "output file already exists" if the target is already present. So any existing
     * file is deleted first (verified), and if that delete cannot take effect - e.g. an old
     * file lingering after a restore on external/FUSE storage - the write falls back to a
     * fresh temp file that is then atomically renamed over the target, guaranteeing the old
     * contents are replaced with the new ones.
     *
     * @throws EncryptionException.KeyInvalidated if encryption key was invalidated
     * @throws EncryptionException.AuthRequired if authentication is required
     * @throws EncryptionException.KeystoreError for other crypto failures
     * @throws EncryptionException.IOError for file I/O failures
     */
    @Throws(EncryptionException::class)
    fun write(ctx: Context, data: ByteArray, file: File): Boolean {
        Logger.d(LOG_TAG, "write into ${file.absolutePath}")
        return try {
            // Delete any existing file first; EncryptedFile refuses to overwrite it.
            deleteFile(file)
            writeInternal(ctx, data, file)
        } catch (e: Exception) {
            // The prior delete did not take effect (silent File.delete() failure on
            // external/FUSE storage). Replace the target via temp-file + atomic rename.
            if (isOutputFileAlreadyExists(e)) {
                Logger.w(LOG_TAG, "Target still exists after delete for ${file.absolutePath}; " +
                        "using temp-file rename fallback to replace it", e)
                return writeViaTempFile(ctx, data, file)
            }
            // Recoverable keyset-corruption error. AEADBadTagException happens inside
            // EncryptedFile.Builder.build(); reset the keyset and retry the write.
            if (isKeysetCorruption(e)) {
                return resetKeysetAndWrite(ctx, data, file)
            }
            throw handleCriticalException(e, "Write file", file.absolutePath)
        }
    }

    /**
     * Best-effort, *verified* deletion of [file]. [File.delete] only returns a boolean and
     * never throws, so the result must be checked; directories need [File.deleteRecursively].
     * Returns true when the file is gone afterwards.
     */
    private fun deleteFile(file: File): Boolean {
        if (!file.exists()) return true
        if (file.delete()) return true
        if (file.isDirectory && file.deleteRecursively()) return true
        // One more attempt - the first failure is sometimes transient on FUSE storage.
        if (file.delete()) return true
        val gone = !file.exists()
        if (!gone) {
            Logger.w(LOG_TAG, "Unable to delete existing file: ${file.absolutePath}")
        }
        return gone
    }

    /**
     * Writes [data] to a fresh temporary file, then atomically renames it over [file]. This
     * replaces a pre-existing [file] that [EncryptedFile] refuses to overwrite and that
     * [deleteFile] could not remove in place. The temp file is written by the same shared
     * keyset, so the renamed file remains a valid encrypted file.
     */
    private fun writeViaTempFile(ctx: Context, data: ByteArray, file: File): Boolean {
        val parent = file.parentFile
        if (parent == null || (!parent.exists() && !parent.mkdirs())) {
            throw EncryptionException.IOError(
                java.io.IOException("Cannot access parent dir for temp write: ${file.parent}")
            )
        }
        val temp = File(parent, file.name + TEMP_FILE_SUFFIX)
        // Clear any stale temp file left by a previously crashed attempt.
        deleteFile(temp)
        try {
            writeInternal(ctx, data, temp)
        } catch (e: Exception) {
            deleteFile(temp)
            // Keyset reset is handled by the caller; surface any other failure here.
            throw handleCriticalException(e, "Write file (temp fallback)", file.absolutePath)
        }

        // Replace the existing target with the freshly written temp file.
        deleteFile(file)
        if (temp.renameTo(file)) return true
        Logger.w(LOG_TAG, "First rename failed for ${file.absolutePath}; retrying after delete")
        deleteFile(file)
        if (temp.renameTo(file)) return true

        // Could not replace the target; clean up the temp file and surface the failure.
        deleteFile(temp)
        throw EncryptionException.IOError(
            java.io.IOException("Failed to replace file via temp rename: ${file.absolutePath}")
        )
    }

    /**
     * Clears a corrupted Tink keyset and retries the write to [file] with the fresh keyset.
     */
    private fun resetKeysetAndWrite(ctx: Context, data: ByteArray, file: File): Boolean {
        Logger.w(LOG_TAG, "Keyset corruption detected for ${file.absolutePath}, " +
                "clearing corrupted state and retrying write")
        destroyCorruptedKeyset(ctx)
        return try {
            deleteFile(file)
            writeInternal(ctx, data, file)
        } catch (retryEx: Exception) {
            Logger.e(LOG_TAG, "Retry after keyset reset also failed for ${file.absolutePath}", retryEx)
            throw handleCriticalException(retryEx, "Write file (retry)", file.absolutePath)
        }
    }

    /**
     * Detects the "output file already exists" error thrown by [EncryptedFile.openFileOutput]
     * when a target could not be removed prior to writing. Walks the cause chain since the
     * library may wrap the exception.
     */
    private fun isOutputFileAlreadyExists(e: Throwable?): Boolean {
        var current: Throwable? = e
        while (current != null) {
            if (current is java.io.IOException) {
                val msg = current.message
                if (msg != null && msg.contains("already exists")) {
                    return true
                }
            }
            current = current.cause
        }
        return false
    }

    /**
     * Core write logic: creates MasterKey + EncryptedFile, writes data.
     */
    private fun writeInternal(ctx: Context, data: ByteArray, file: File): Boolean {
        val masterKey =
            MasterKey.Builder(ctx.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        val encryptedFile =
            EncryptedFile.Builder(
                    ctx.applicationContext,
                    file,
                    masterKey,
                    EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
                )
                .build()

        encryptedFile.openFileOutput().apply {
            write(data)
            flush()
            close()
        }
        return true
    }

    /**
     * Detects whether an exception is caused by a corrupted/mismatched Tink keyset.
     *
     * The [AEADBadTagException] can be nested several levels deep inside the
     * [EncryptedFile.Builder.build] call chain:
     *   EncryptedFile.Builder.build()
     *     → AndroidKeysetManager.readMasterkeyDecryptAndParseKeyset()
     *       → AndroidKeysetManager.decrypt()
     *         → Cipher.doFinal() → AEADBadTagException
     *
     * We walk the full cause chain because R8/ProGuard can alter wrapping layers.
     */
    private fun isKeysetCorruption(e: Exception): Boolean {
        var current: Throwable? = e
        while (current != null) {
            if (current is AEADBadTagException) return true
            // android.security.KeyStoreException with VERIFICATION_FAILED
            // (API 33+ class: check by name to avoid minSdk compilation error)
            if (current.javaClass.name == "android.security.KeyStoreException") return true
            // GeneralSecurityException wrapping AEADBadTagException
            if (current is GeneralSecurityException && current.cause is AEADBadTagException) return true
            current = current.cause
        }
        return false
    }

    /**
     * Detects a Tink streaming-AEAD decryption failure that is reported as a plain
     * [java.io.IOException] (Tink's [InputStreamDecrypter] throws IOException when no key
     * in the keyset can decrypt the ciphertext stream). The canonical message is:
     *   "No matching key found for the ciphertext in the stream."
     *
     * This signals that the ciphertext was produced by a *different* (now-discarded)
     * keyset than the one currently in SharedPreferences - a key-mismatch, not a disk
     * error. Distinct from [isKeysetCorruption]: there the keyset itself is unreadable;
     * here the keyset is fine but the ciphertext is not.
     */
    private fun isTinkDecryptionFailure(e: Throwable?): Boolean {
        var current: Throwable? = e
        while (current != null) {
            if (current is java.io.IOException) {
                val msg = current.message
                // Match Tink's InputStreamDecrypter / StreamingAeadThroughIOConnection
                // signatures without being brittle to minor wording changes.
                if (msg != null && (msg.contains("No matching key")
                            || msg.contains("ciphertext in the stream")
                            || msg.contains("decryption failed"))) {
                    return true
                }
            }
            current = current.cause
        }
        return false
    }
}
