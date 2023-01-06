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
package com.celzero.bravedns.service

import android.content.Context
import android.util.Log
import com.celzero.bravedns.R
import com.celzero.bravedns.data.FileTag
import com.celzero.bravedns.data.FileTagDeserializer
import com.celzero.bravedns.database.RethinkLocalFileTag
import com.celzero.bravedns.database.RethinkLocalFileTagRepository
import com.celzero.bravedns.database.RethinkRemoteFileTag
import com.celzero.bravedns.database.RethinkRemoteFileTagRepository
import com.celzero.bravedns.util.Constants.Companion.LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME
import com.celzero.bravedns.util.Constants.Companion.ONDEVICE_BLOCKLIST_FILE_TAG
import com.celzero.bravedns.util.Constants.Companion.REMOTE_BLOCKLIST_DOWNLOAD_FOLDER_NAME
import com.celzero.bravedns.util.LoggerConstants
import com.celzero.bravedns.util.Utilities
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import dnsx.BraveDNS
import dnsx.Dnsx
import java.io.IOException
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

object RethinkBlocklistManager : KoinComponent {

    private var braveDnsLocal: BraveDNS? = null
    private var braveDnsRemote: BraveDNS? = null

    private val remoteFileTagRepository by inject<RethinkRemoteFileTagRepository>()
    private val localFileTagRepository by inject<RethinkLocalFileTagRepository>()
    private val persistentState by inject<PersistentState>()

    private const val EMPTY_SUBGROUP = "others"

    data class SimpleViewPacksTag(
        val name: String,
        val desc: String,
        val tags: MutableList<Int>,
        val group: String
    )

    data class RethinkBlockType(val name: String, val label: Int, val desc: Int)

    enum class RethinkBlocklistType {
        LOCAL,
        REMOTE;

        companion object {
            fun getType(id: Int): RethinkBlocklistType {
                if (id == LOCAL.ordinal) return LOCAL

                return REMOTE
            }
        }

        fun isLocal(): Boolean {
            return this == LOCAL
        }

        fun isRemote(): Boolean {
            return this == REMOTE
        }
    }

    enum class DownloadType(val id: Int) {
        LOCAL(0),
        REMOTE(1);

        fun isLocal(): Boolean {
            return this == LOCAL
        }

        fun isRemote(): Boolean {
            return this == REMOTE
        }
    }

    suspend fun getSimpleViewPacksTags(type: RethinkBlocklistType): List<SimpleViewPacksTag> {
        val fileTags =
            if (type.isRemote()) {
                remoteFileTagRepository.fileTags()
            } else {
                localFileTagRepository.fileTags()
            }

        val uniquePacks: MutableSet<String> = mutableSetOf()
        fileTags.forEach {
            if (it.pack.isEmpty()) return@forEach

            it.pack.let { it1 -> uniquePacks.addAll(it1) }
        }

        uniquePacks.removeIf { it.isEmpty() }
        val packs: MutableList<SimpleViewPacksTag> = mutableListOf()

        uniquePacks.forEach { p ->
            val tags: MutableList<Int> = mutableListOf()
            var group = ""
            var count = 0
            fileTags.forEach {
                if (it.pack.contains(p)) {
                    group = it.group
                    tags.add(it.value)
                    count++
                }
            }
            val pack = SimpleViewPacksTag(p, count.toString(), tags, group)
            packs.add(pack)
        }
        packs.sortBy { it.group }

        return packs
    }

    // TODO: move this strings to strings.xml
    val PARENTAL_CONTROL =
        RethinkBlockType(
            "ParentalControl",
            R.string.rbl_parental_control,
            R.string.rbl_parental_control_desc
        )
    val SECURITY = RethinkBlockType("Security", R.string.rbl_privacy, R.string.rbl_security_desc)
    val PRIVACY = RethinkBlockType("Privacy", R.string.rbl_security, R.string.rbl_privacy_desc)

    // read and parse the json file, either remote or local blocklist
    // returns the parsed FileTag list, on error return empty array list
    suspend fun readJson(context: Context, type: DownloadType, timestamp: Long): Boolean {
        // TODO: merge both the remote and local json parsing into one
        return if (type.isRemote()) {
            readRemoteJson(context, timestamp)
        } else {
            readLocalJson(context, timestamp)
        }
    }

    private suspend fun readLocalJson(context: Context, timestamp: Long): Boolean {
        try {
            val dbFileTagLocal: MutableList<RethinkLocalFileTag> = mutableListOf()
            val dir =
                Utilities.blocklistDownloadBasePath(
                    context,
                    LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME,
                    timestamp
                )

            val file = Utilities.blocklistFile(dir, ONDEVICE_BLOCKLIST_FILE_TAG) ?: return false

            val jsonString = file.bufferedReader().use { it.readText() }
            // register the type adapter to deserialize the class.
            // see FileTag.kt for more info (FileTagDeserializer)
            val gson =
                GsonBuilder()
                    .registerTypeAdapter(FileTag::class.java, FileTagDeserializer())
                    .create()
            val entries: JsonObject = Gson().fromJson(jsonString, JsonObject::class.java)
            entries.entrySet().forEach {
                val t = gson.fromJson(it.value, FileTag::class.java)
                // add subg tag as "others" if its empty
                if (t.subg.isEmpty()) {
                    t.subg = EMPTY_SUBGROUP
                }
                t.group = t.group.lowercase()
                val l = getRethinkLocalObj(t)

                dbFileTagLocal.add(l)
            }
            val selectedTags = localFileTagRepository.getSelectedTags()
            // edge case: found a residual block list entry still available in the database
            // during the insertion of new block list entries. This occurred when the number of
            // block lists in the preceding list is greater than the current list. Always
            // empty the data base entries before creating new entries.
            localFileTagRepository.deleteAll()
            localFileTagRepository.insertAll(dbFileTagLocal.toList())
            localFileTagRepository.updateTags(selectedTags.toSet(), 1)
            Log.i(LoggerConstants.LOG_TAG_DNS, "New Local blocklist files inserted into database")
            return true
        } catch (ioException: IOException) {
            Log.e(
                LoggerConstants.LOG_TAG_DNS,
                "Failure reading json file, blocklist type: remote, timestamp: $timestamp",
                ioException
            )
        }
        return false
    }

    private suspend fun readRemoteJson(context: Context, timestamp: Long): Boolean {
        try {
            val dbFileTagRemote: MutableList<RethinkRemoteFileTag> = mutableListOf()

            val dir =
                Utilities.blocklistDownloadBasePath(
                    context,
                    REMOTE_BLOCKLIST_DOWNLOAD_FOLDER_NAME,
                    timestamp
                )

            val file = Utilities.blocklistFile(dir, ONDEVICE_BLOCKLIST_FILE_TAG) ?: return false
            // register typeadapter to enable custom deserialization of the FileTag object.
            // see FileTag.kt for more info (FileTagDeserializer)
            val gson =
                GsonBuilder()
                    .registerTypeAdapter(FileTag::class.java, FileTagDeserializer())
                    .create()

            val jsonString = file.bufferedReader().use { it.readText() }
            val entries: JsonObject = Gson().fromJson(jsonString, JsonObject::class.java)
            entries.entrySet().forEach {
                val t = gson.fromJson(it.value, FileTag::class.java)
                // add subg tag as "others" if its empty
                if (t.subg.isEmpty()) {
                    t.subg = EMPTY_SUBGROUP
                }
                t.group = t.group.lowercase()
                val r = getRethinkRemoteObj(t)
                dbFileTagRemote.add(r)
            }
            val selectedTags = remoteFileTagRepository.getSelectedTags()
            // edge case: found a residual block list entry still available in the database
            // during the insertion of new block list entries. This occurred when the number of
            // block lists in the preceding list is greater than the current list. Always
            // empty the data base entries before creating new entries.
            remoteFileTagRepository.deleteAll()
            remoteFileTagRepository.insertAll(dbFileTagRemote.toList())
            remoteFileTagRepository.updateTags(selectedTags.toSet(), 1)
            Log.i(LoggerConstants.LOG_TAG_DNS, "New Remote blocklist files inserted into database")
            return true
        } catch (ioException: IOException) {
            Log.e(
                LoggerConstants.LOG_TAG_DNS,
                "Failure reading json file, blocklist type: remote, timestamp: $timestamp",
                ioException
            )
        }
        return false
    }

    private fun getRethinkLocalObj(t: FileTag): RethinkLocalFileTag {
        return RethinkLocalFileTag(
            t.value,
            t.uname,
            t.vname,
            t.group,
            t.subg,
            t.pack,
            t.urls,
            t.show,
            t.entries,
            t.simpleTagId,
            t.isSelected
        )
    }

    private fun getRethinkRemoteObj(t: FileTag): RethinkRemoteFileTag {
        return RethinkRemoteFileTag(
            t.value,
            t.uname,
            t.vname,
            t.group,
            t.subg,
            t.pack,
            t.urls,
            t.show,
            t.entries,
            t.simpleTagId,
            t.isSelected
        )
    }

    suspend fun updateFiletagRemote(remote: RethinkRemoteFileTag) {
        remoteFileTagRepository.update(remote)
    }

    suspend fun updateFiletagLocal(local: RethinkLocalFileTag) {
        localFileTagRepository.update(local)
    }

    suspend fun updateFiletagsRemote(values: Set<Int>, isSelected: Int) {
        remoteFileTagRepository.updateTags(values, isSelected)
    }

    fun updateFiletagsLocal(values: Set<Int>, isSelected: Int) {
        localFileTagRepository.updateTags(values, isSelected)
    }

    suspend fun getSelectedFileTagsLocal(): List<Int> {
        return localFileTagRepository.getSelectedTags()
    }

    suspend fun getSelectedFileTagsRemote(): List<Int> {
        return remoteFileTagRepository.getSelectedTags()
    }

    suspend fun clearTagsSelectionRemote() {
        remoteFileTagRepository.clearSelectedTags()
    }

    suspend fun clearTagsSelectionLocal() {
        // FIXME: removed below code for testing, add it back
        // localFileTagRepository.clearSelectedTags()
    }

    fun getStamp(context: Context, fileValues: Set<Int>, type: RethinkBlocklistType): String {
        return try {
            val flags = convertListToCsv(fileValues)
            getBraveDns(context, blocklistTimestamp(type), type)?.flagsToStamp(flags) ?: ""
        } catch (e: java.lang.Exception) {
            Log.e(
                LoggerConstants.LOG_TAG_VPN,
                "Exception while fetching stamp from tags: ${e.message}, $e "
            )
            ""
        }
    }

    private fun blocklistTimestamp(type: RethinkBlocklistType): Long {
        return if (type.isLocal()) {
            persistentState.localBlocklistTimestamp
        } else {
            persistentState.remoteBlocklistTimestamp
        }
    }

    fun getTagsFromStamp(context: Context, stamp: String, type: RethinkBlocklistType): Set<Int> {
        return try {
            convertCsvToList(
                getBraveDns(context, blocklistTimestamp(type), type)?.stampToFlags(stamp)
            )
        } catch (e: Exception) {
            Log.e(
                LoggerConstants.LOG_TAG_VPN,
                "Exception while fetching tags from stamp: ${e.message}, $e "
            )
            setOf()
        }
    }

    private fun convertCsvToList(csv: String?): Set<Int> {
        if (csv == null) return setOf()

        val regex = "\\s*,\\s*".toRegex()
        return csv.split(regex).map { it.toInt() }.toSet()
    }

    private fun convertListToCsv(csv: Set<Int>): String {
        return csv.joinToString(",")
    }

    private fun getBraveDnsRemote(context: Context, timestamp: Long): BraveDNS? {
        if (braveDnsRemote != null) {
            return braveDnsRemote
        }

        val dir =
            Utilities.remoteBlocklistFile(context, REMOTE_BLOCKLIST_DOWNLOAD_FOLDER_NAME, timestamp)
                ?: return null
        val file =
            Utilities.blocklistFile(dir.absolutePath, ONDEVICE_BLOCKLIST_FILE_TAG) ?: return null

        braveDnsRemote =
            try {
                if (file.exists()) {
                    Dnsx.newBraveDNSRemote(file.absolutePath)
                } else {
                    Log.e(
                        LoggerConstants.LOG_TAG_VPN,
                        "File does not exist in path: ${file.absolutePath}"
                    )
                    null
                }
            } catch (e: Exception) {
                Log.e(
                    LoggerConstants.LOG_TAG_VPN,
                    "Exception creating BraveDNS object, ${e.message}, $e "
                )
                null
            }
        return braveDnsRemote
    }

    private fun getBraveDnsLocal(context: Context, timestamp: Long): BraveDNS? {
        if (braveDnsLocal != null) {
            return braveDnsLocal
        }

        val dir =
            Utilities.remoteBlocklistFile(context, LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME, timestamp)
                ?: return null
        val file =
            Utilities.blocklistFile(dir.absolutePath, ONDEVICE_BLOCKLIST_FILE_TAG) ?: return null
        braveDnsLocal =
            try {
                if (file.exists()) {
                    Dnsx.newBraveDNSRemote(file.absolutePath)
                } else {
                    Log.e(
                        LoggerConstants.LOG_TAG_VPN,
                        "File does not exist in path: ${file.absolutePath}"
                    )
                    null
                }
            } catch (e: Exception) {
                Log.e(
                    LoggerConstants.LOG_TAG_VPN,
                    "Exception creating BraveDNS object, ${e.message}, $e "
                )
                null
            }
        return braveDnsLocal
    }

    private fun getBraveDns(
        context: Context,
        timestamp: Long,
        type: RethinkBlocklistType
    ): BraveDNS? {
        if (type.isRemote()) {
            return getBraveDnsRemote(context, timestamp)
        }

        return getBraveDnsLocal(context, timestamp)
    }

    fun createBraveDns(context: Context, timestamp: Long, type: RethinkBlocklistType) {
        getBraveDns(context, timestamp, type)
    }
}
