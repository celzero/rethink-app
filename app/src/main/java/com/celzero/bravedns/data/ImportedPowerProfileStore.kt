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
package com.celzero.bravedns.data

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File

object ImportedPowerProfileStore {
    private const val DIRECTORY_NAME = "power-imported-profiles"

    fun list(context: Context): List<PowerProfileDefinition> {
        val directory = profileDirectory(context)
        if (!directory.exists()) return emptyList()
        return directory
            .listFiles { file -> file.isFile && file.extension == "json" }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
            .mapNotNull { file ->
                runCatching {
                    val doc = PowerProfilePortableDocument.fromJson(file.readText())
                    doc.toDefinition(file.name)
                }.getOrNull()
            }
    }

    fun importFromUri(context: Context, uri: Uri): PowerProfileDefinition? {
        val raw = readProfileText(context, uri) ?: return null
        val doc = PowerProfilePortableDocument.fromJson(raw)
        if (doc.id.isBlank() || doc.name.isBlank()) return null

        val fileName = sanitizeFileName(doc.id) + ".json"
        val file = File(profileDirectory(context), fileName)
        file.parentFile?.let { parent ->
            if (!parent.exists()) parent.mkdirs()
        }
        file.writeText(doc.toJson())
        return doc.toDefinition(file.name)
    }

    fun saveDocument(context: Context, doc: PowerProfilePortableDocument): PowerProfileDefinition {
        val fileName = sanitizeFileName(doc.id) + ".json"
        val file = File(profileDirectory(context), fileName)
        file.parentFile?.let { parent ->
            if (!parent.exists()) parent.mkdirs()
        }
        file.writeText(doc.toJson())
        return doc.toDefinition(file.name)
    }

    fun exportToCache(context: Context, profile: PowerProfileDefinition): File? {
        val doc = PowerProfileArtifacts.loadPortableDocument(context, profile) ?: return null
        val directory = File(context.cacheDir, "power-profile-exports")
        if (!directory.exists()) directory.mkdirs()
        val fileName = sanitizeFileName(doc.id.ifBlank { doc.name }) + ".powerprofile.json"
        val file = File(directory, fileName)
        file.writeText(doc.toJson())
        return file
    }

    private fun profileDirectory(context: Context): File {
        return File(context.filesDir, DIRECTORY_NAME)
    }

    private fun sanitizeFileName(value: String): String {
        return value.lowercase().replace(Regex("[^a-z0-9._-]+"), "-")
    }

    private fun readProfileText(context: Context, uri: Uri): String? {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        input.use { stream ->
            val buffer = ByteArray(8 * 1024)
            val output = ByteArrayOutputStream()
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read == -1) break
                total += read
                if (total > PowerProfileSecurity.MAX_PROFILE_BYTES) return null
                output.write(buffer, 0, read)
            }
            return output.toString(Charsets.UTF_8.name())
        }
    }
}
