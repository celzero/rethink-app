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

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKeys
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.util.Constants.Companion.WIREGUARD_FOLDER_NAME
import com.celzero.bravedns.util.LoggerConstants
import com.celzero.bravedns.wireguard.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets

object EncryptedFileManager {
    // Although you can define your own key generation parameter specification, it's
    // recommended that you use the value specified here.
    private val keyGenParameterSpec = MasterKeys.AES256_GCM_SPEC
    private val mainKeyAlias = MasterKeys.getOrCreate(keyGenParameterSpec)

    fun read(ctx: Context, fileToRead: String): Config? {
        var config: Config? = null
        try {
            val dir = File(fileToRead)
            val encryptedFile =
                EncryptedFile.Builder(
                        dir,
                        ctx.applicationContext,
                        mainKeyAlias,
                        EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
                    )
                    .build()

            Log.d(
                LoggerConstants.LOG_TAG_WIREGUARD,
                "Encrypted File Read: ${dir.absolutePath}, $fileToRead"
            )
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
            if (DEBUG)
                Log.d(
                    LoggerConstants.LOG_TAG_WIREGUARD,
                    "read config: ${config.getName()}, ${config.toWgQuickString()}"
                )
            ist.close()
            byteArrayOutputStream.close()
        } catch (e: Exception) {
            Log.e(LoggerConstants.LOG_TAG_WIREGUARD, "Encrypted File Read: ${e.message}")
        }

        return config
    }

    fun write(ctx: Context, data: String, fileName: String) {
        try {

            // Create a file with this name or replace an entire existing file
            // that has the same name. Note that you cannot append to an existing file,
            // and the filename cannot contain path separators.
            val dir =
                File(ctx.filesDir.canonicalPath + File.separator + WIREGUARD_FOLDER_NAME + File.separator)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val fileToWrite = File(dir, fileName)
            if (DEBUG) Log.d(LoggerConstants.LOG_TAG_WIREGUARD, "write into $fileToWrite, $data")
            val encryptedFile =
                EncryptedFile.Builder(
                        fileToWrite,
                        ctx.applicationContext,
                        mainKeyAlias,
                        EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
                    )
                    .build()

            if (fileToWrite.exists()) {
                fileToWrite.delete()
            }

            val fileContent = data.toByteArray(StandardCharsets.UTF_8)
            encryptedFile.openFileOutput().apply {
                write(fileContent)
                flush()
                close()
            }
        } catch (e: Exception) {
            Log.e(LoggerConstants.LOG_TAG_WIREGUARD, "Encrypted File Write: ${e.message}")
        }
    }
}
