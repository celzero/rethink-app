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
package com.celzero.bravedns.service

import Logger
import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.celzero.bravedns.util.Constants.Companion.WIREGUARD_FOLDER_NAME
import com.celzero.bravedns.wireguard.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

object EncryptedFileManager {
    // Although you can define your own key generation parameter specification, it's
    // recommended that you use the value specified here.
    // private val keyGenParameterSpec = MasterKeys.AES256_GCM_SPEC
    // private val mainKeyAlias = MasterKeys.getOrCreate(keyGenParameterSpec)

    fun readWireguardConfig(ctx: Context, fileToRead: String): Config? {
        var config: Config? = null
        try {
            val dir = File(fileToRead)
            Logger.d(Logger.LOG_TAG_PROXY, "Encrypted File Read1: $fileToRead, ${dir.absolutePath}")
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

            Logger.d(Logger.LOG_TAG_PROXY, "Encrypted File Read: ${dir.absolutePath}, $fileToRead")
            val inputStream = encryptedFile.openFileInput()
            val byteArrayOutputStream = ByteArrayOutputStream()
            var nextByte: Int = inputStream.read()
            while (nextByte != -1) {
                byteArrayOutputStream.write(nextByte)
                nextByte = inputStream.read()
            }

            inputStream.close()
            val plaintext: ByteArray = byteArrayOutputStream.toByteArray()
            // convert output stream to input stream
            val ist = ByteArrayInputStream(plaintext)

            config = Config.parse(ist)
            ist.close()
            byteArrayOutputStream.close()
        } catch (e: Exception) {
            Logger.e(Logger.LOG_TAG_PROXY, "Encrypted File Read: ${e.message}", e)
        }

        return config
    }

    fun read(ctx: Context, file: File): String {
        var content = ""
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

            // Open the file and read its content and return it as a string.
            val inputStream = encryptedFile.openFileInput()
            content = inputStream.readBytes().toString(Charset.defaultCharset())
        } catch (e: Exception) {
            Logger.e(Logger.LOG_TAG_PROXY, "Encrypted File Read: ${e.message}")
        }
        return content
    }

    fun writeWireguardConfig(ctx: Context, cfg: String, fileName: String): Boolean {
        try {
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
            return write(ctx, cfg, fileToWrite)
        } catch (e: Exception) {
            Logger.e(Logger.LOG_TAG_PROXY, "Encrypted File Write: ${e.message}")
        }
        return false
    }

    fun writeTcpConfig(ctx: Context, cfg: String, fileName: String): Boolean {
        try {
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
        } catch (e: Exception) {
            Logger.e(Logger.LOG_TAG_PROXY, "Encrypted File Write: ${e.message}")
        }
        return false
    }

    fun write(ctx: Context, data: String, file: File): Boolean {
        try {
            Logger.d(Logger.LOG_TAG_PROXY, "write into $file")
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

            val fileContent = data.toByteArray(StandardCharsets.UTF_8)
            encryptedFile.openFileOutput().apply {
                write(fileContent)
                flush()
                close()
            }
            return true
        } catch (e: Exception) {
            Logger.e(Logger.LOG_TAG_PROXY, "Encrypted File Write: ${e.message}")
        }
        return false
    }
}
