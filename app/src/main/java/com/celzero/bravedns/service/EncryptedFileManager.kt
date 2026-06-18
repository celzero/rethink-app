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

import Logger
import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.util.Constants.Companion.WIREGUARD_FOLDER_NAME
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.FileNotFoundException
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException

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
                EncryptionException.IOError(e)
            }
            else -> {
                // Unknown exception - wrap as keystore error
                EncryptionException.KeystoreError(e)
            }
        }

        // Log critical failures to event system
        val message = "$operation failed for file: $file"
        val details = "${cryptoException::class.simpleName}: ${cryptoException.message}\nCause: ${e::class.simpleName}: ${e.message}"

        eventLogger.log(
            type = EventType.PROXY_ERROR,
            severity = Severity.CRITICAL,
            message = message,
            source = EventSource.SYSTEM,
            userAction = false,
            details = details
        )

        Logger.e(LOG_TAG, "$message - ${cryptoException.message}", e)
        return cryptoException
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
            }
            throw handleCriticalException(e, "Read file", file.absolutePath)
        }
    }

    /**
     * Writes WireGuard config to encrypted file.
     *
     * @throws EncryptionException for any encryption failures
     */
    @Throws(EncryptionException::class)
    fun writeWireguardConfig(ctx: Context, cfg: String, fileName: String) {
        val dir =
            File(
                ctx.filesDir.canonicalPath +
                    File.separator +
                    WIREGUARD_FOLDER_NAME +
                    File.separator
            )
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val fileToWrite = File(dir, fileName)
        val bytes = cfg.toByteArray(StandardCharsets.UTF_8)
        write(ctx, bytes, fileToWrite)
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
     * The old encrypted data is permanently lost, but the alternative is a permanent
     * brick where *all* encrypted-file operations fail until the user clears app data.
     *
     * @throws EncryptionException.KeyInvalidated if encryption key was invalidated
     * @throws EncryptionException.AuthRequired if authentication is required
     * @throws EncryptionException.KeystoreError for other crypto failures
     * @throws EncryptionException.IOError for file I/O failures
     */
    @Throws(EncryptionException::class)
    fun write(ctx: Context, data: ByteArray, file: File): Boolean {
        try {
            Logger.d(LOG_TAG, "write into $file")
            return writeInternal(ctx, data, file)
        } catch (e: Exception) {
            // Check if this is a recoverable keyset-corruption error.
            // AEADBadTagException happens inside EncryptedFile.Builder.build()
            if (isKeysetCorruption(e)) {
                Logger.w(LOG_TAG, "Keyset corruption detected for ${file.absolutePath}, " +
                        "clearing corrupted state and retrying write", e)
                try {
                    return writeInternal(ctx, data, file)
                } catch (retryEx: Exception) {
                    Logger.e(LOG_TAG, "Retry after keyset reset also failed for ${file.absolutePath}", retryEx)
                    throw handleCriticalException(retryEx, "Write file (retry)", file.absolutePath)
                }
            }
            throw handleCriticalException(e, "Write file", file.absolutePath)
        }
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
}
