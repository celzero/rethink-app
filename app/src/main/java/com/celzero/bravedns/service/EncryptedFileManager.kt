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
import com.celzero.bravedns.wireguard.Config
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
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
     * Reads and parses a WireGuard config from encrypted storage.
     *
     * @throws EncryptionException.KeyInvalidated if encryption key was invalidated
     * @throws EncryptionException.DecryptionFailed if decryption fails
     * @throws EncryptionException.KeystoreError for other crypto failures
     * @throws EncryptionException.IOError for file I/O failures
     * @return Parsed WireGuard config or null if parsing fails (not encryption failure)
     */
    @Throws(EncryptionException::class)
    fun readWireguardConfig(ctx: Context, fileToRead: String): Config? {
        var inputStream: InputStream? = null
        var ist: ByteArrayInputStream? = null
        var bos: ByteArrayOutputStream? = null
        try {
            val dir = File(fileToRead)
            val masterKey =
                MasterKey.Builder(ctx.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
            val encryptedFile =
                EncryptedFile.Builder(
                        ctx.applicationContext,
                        dir,
                        masterKey,
                        EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
                    )
                    .build()

            Logger.d(LOG_TAG, "Reading encrypted WireGuard config: ${dir.absolutePath}")
            inputStream = encryptedFile.openFileInput()
            bos = ByteArrayOutputStream()
            var nextByte: Int = inputStream.read()
            while (nextByte != -1) {
                bos.write(nextByte)
                nextByte = inputStream.read()
            }

            val plaintext: ByteArray = bos.toByteArray()
            ist = ByteArrayInputStream(plaintext)

            // Config parsing failure is not an encryption error
            return Config.parse(ist)
        } catch (e: Exception) {
            throw handleCriticalException(e, "Read WireGuard config", fileToRead)
        } finally {
            try {
                inputStream?.close()
                ist?.close()
                bos?.flush()
                bos?.close()
            } catch (_: Exception) {
                // Ignore cleanup errors
            }
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
        return bytes.toString(Charset.defaultCharset())
    }

    /**
     * Reads encrypted file as a ByteArray.
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
        if (!fileToWrite.exists()) {
            fileToWrite.createNewFile()
        }
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
        if (!fileToWrite.exists()) {
            fileToWrite.createNewFile()
        }
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
     * @throws EncryptionException.KeyInvalidated if encryption key was invalidated
     * @throws EncryptionException.AuthRequired if authentication is required
     * @throws EncryptionException.KeystoreError for other crypto failures
     * @throws EncryptionException.IOError for file I/O failures
     */
    @Throws(EncryptionException::class)
    fun write(ctx: Context, data: ByteArray, file: File): Boolean {
        try {
            Logger.d(LOG_TAG, "write into $file")
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

            if (file.exists()) {
                file.delete()
            }

            encryptedFile.openFileOutput().apply {
                write(data)
                flush()
                close()
            }
            return true
        } catch (e: Exception) {
            throw handleCriticalException(e, "Write file", file.absolutePath)
        }
    }
}
