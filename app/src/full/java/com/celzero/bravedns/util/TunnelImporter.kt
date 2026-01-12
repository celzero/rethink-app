/*
 * Copyright 2023 RethinkDNS and its authors
 *
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
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
package com.celzero.bravedns.util

import Logger
import Logger.LOG_TAG_PROXY
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.celzero.bravedns.R
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.wireguard.Config
import com.celzero.bravedns.wireguard.util.ErrorMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object TunnelImporter : KoinComponent {

    val context: Context by inject()

    suspend fun importTunnel(
        contentResolver: ContentResolver,
        uri: Uri,
        messageCallback: (CharSequence) -> Unit
    ) =
        withContext(Dispatchers.IO) {
            val throwables = ArrayList<Throwable>()
            try {
                var config: Config? = null
                val columns = arrayOf(OpenableColumns.DISPLAY_NAME)
                var name = ""
                contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) {
                        name = cursor.getString(0)
                    }
                }
                if (name.isEmpty()) {
                    val lastPathSegment = uri.lastPathSegment
                    if (lastPathSegment.isNullOrEmpty()) {
                        throw IllegalArgumentException(context.getString(R.string.illegal_filename_error, uri.toString()))
                    }
                    name = Uri.decode(lastPathSegment)
                }
                if (name.isEmpty()) {
                    throw IllegalArgumentException(context.getString(R.string.illegal_filename_error, uri.toString()))
                }
                var idx = name.lastIndexOf('/')
                if (idx >= 0) {
                    require(idx < name.length - 1) {
                        context.getString(R.string.illegal_filename_error, name)
                    }
                    name = name.substring(idx + 1)
                }
                val isZip = name.lowercase().endsWith(".zip")
                if (name.lowercase().endsWith(".conf")) {
                    name = name.dropLast(".conf".length)
                } else {
                    require(isZip) { context.getString(R.string.bad_extension_error) }
                }

                if (isZip) {
                    val inputStream =
                        contentResolver.openInputStream(uri) ?: throw IllegalArgumentException(
                            context.getString(R.string.import_error, "Cannot open file: $name")
                        )
                    ZipInputStream(inputStream).use { zip ->
                        val reader = BufferedReader(InputStreamReader(zip, StandardCharsets.UTF_8))
                        var entry: ZipEntry?
                        while (true) {
                            entry = zip.nextEntry ?: break
                            name = entry.name
                            idx = name.lastIndexOf('/')
                            if (idx >= 0) {
                                if (idx >= name.length - 1) {
                                    continue
                                }
                                name = name.substring(name.lastIndexOf('/') + 1)
                            }
                            if (name.lowercase().endsWith(".conf")) {
                                name = name.dropLast(".conf".length)
                            } else {
                                continue
                            }
                            try {
                                    Config.parse(reader)
                                } catch (e: Throwable) {
                                    throwables.add(e)
                                    null
                                }
                                ?.let {
                                    config = it
                                    WireguardManager.addConfig(config, name)
                                }
                        }
                    }
                } else {
                    val inputStream =
                        contentResolver.openInputStream(uri) ?: throw IllegalArgumentException(
                            context.getString(R.string.import_error, "Cannot open file: $name")
                        )
                    config = Config.parse(inputStream)
                    WireguardManager.addConfig(config, name)
                }

                if (config == null) {
                    if (throwables.size == 1) {
                        throw throwables[0]
                    } else {
                        require(throwables.isNotEmpty()) {
                            context.getString(R.string.no_configs_error)
                        }
                    }
                }

                withContext(Dispatchers.Main.immediate) {
                    onTunnelImportFinished(throwables, messageCallback)
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main.immediate) {
                    onTunnelImportFinished(listOf(e), messageCallback)
                }
            }
        }

    suspend fun importTunnel(configText: String, messageCallback: (CharSequence) -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                Logger.d(LOG_TAG_PROXY, "Importing tunnel: $configText")
                val config =
                    Config.parse(
                        ByteArrayInputStream(configText.toByteArray(StandardCharsets.UTF_8))
                    )
                WireguardManager.addConfig(config)
            } catch (e: Throwable) {
                onTunnelImportFinished(listOf(e), messageCallback)
            }
        }
    }

    private fun onTunnelImportFinished(
        throwables: Collection<Throwable>,
        messageCallback: (CharSequence) -> Unit
    ) {
        var message = ""

        for (throwable in throwables) {
            val error = ErrorMessages[context, throwable]
            message = context.getString(R.string.import_error, error)
            val ex = Logger.throwableToException(throwable)
            Logger.e(LOG_TAG_PROXY, message, ex)
        }
        // trim the message and take string after ":"
        val idx = message.indexOf(':')
        if (idx >= 0) {
            message = message.substring(idx + 1).trim { it <= ' ' }
        }
        message =
            if (throwables.isEmpty()) context.getString(R.string.config_add_success_toast)
            else message

        messageCallback(message)
    }
}
