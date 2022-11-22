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
package com.celzero.bravedns.automaton

import android.content.Context
import android.util.Log
import com.celzero.bravedns.database.RethinkLocalFileTag
import com.celzero.bravedns.database.RethinkLocalFileTagRepository
import com.celzero.bravedns.database.RethinkRemoteFileTag
import com.celzero.bravedns.database.RethinkRemoteFileTagRepository
import com.celzero.bravedns.download.AppDownloadManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.RethinkBlocklistFragment
import com.celzero.bravedns.util.Constants.Companion.LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME
import com.celzero.bravedns.util.Constants.Companion.ONDEVICE_BLOCKLIST_FILE_TAG
import com.celzero.bravedns.util.Constants.Companion.REMOTE_BLOCKLIST_DOWNLOAD_FOLDER_NAME
import com.celzero.bravedns.util.LoggerConstants
import com.celzero.bravedns.util.Utilities
import com.google.gson.Gson
import com.google.gson.JsonObject
import dnsx.BraveDNS
import dnsx.Dnsx
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.IOException

object RethinkBlocklistManager : KoinComponent {

    private var braveDnsLocal: BraveDNS? = null
    private var braveDnsRemote: BraveDNS? = null

    private val remoteFileTagRepository by inject<RethinkRemoteFileTagRepository>()
    private val localFileTagRepository by inject<RethinkLocalFileTagRepository>()
    private val persistentState by inject<PersistentState>()

    private const val EMPTY_SUBGROUP = "others"
    private const val INVALID_SIMPLE_TAG_ID = -1

    data class SimpleViewTag(val id: Int, val name: String, val desc: String,
                             val tags: MutableList<Int>, val rethinkBlockType: RethinkBlockType)

    data class SimpleViewPacksTag(val name: String, val desc: String, val tags: MutableList<Int>, val group: String)

    data class RethinkBlockType(val name: String, val desc: String)

    data class SimpleViewMapping(val value: Int, val simpleTagId: Int)

    suspend fun getSimpleViewTags(
            type: RethinkBlocklistFragment.RethinkBlocklistType): List<SimpleViewTag> {

        val simpleTags = mutableListOf(ADULT, PIRACY, GAMBLING, DATING, SOCIAL_MEDIA, SEC_FULL,
                                       SEC_EXTRA, PRIVACY_LITE, PRIVACY_AGGRESSIVE, PRIVACY_EXTREME)

        val tags = if (type.isRemote()) {
            remoteFileTagRepository.getSimpleViewTags()
        } else {
            localFileTagRepository.getSimpleViewTags()
        }

        val groups = tags.groupBy { it.simpleTagId }

        groups.keys.forEach { key ->
            if (key == INVALID_SIMPLE_TAG_ID) return@forEach

            groups[key]?.map { it.value }?.let { it1 -> simpleTags[key].tags.addAll(it1) }
        }
        return simpleTags
    }

    private suspend fun getRemoteSimpleViewPacks(): List<SimpleViewPacksTag> {
        val fileTags = remoteFileTagRepository.fileTags()
        Log.d("TEST", "TEST: print all packs: $fileTags")
        val uniquePacks: MutableSet<String> = mutableSetOf()
        fileTags.forEach {
            if (it.pack?.isEmpty() == true) return@forEach

            it.pack?.let { it1 -> uniquePacks.addAll(it1) }
        }

        uniquePacks.removeIf { it.isEmpty() }
        val packs: MutableList<SimpleViewPacksTag> = mutableListOf()

        uniquePacks.forEach { p ->
            val tags: MutableList<Int> = mutableListOf()
            var group = ""
            var count = 0
            fileTags.forEach {
                if (it.pack?.contains(p) == true) {
                    group = it.group
                    tags.add(it.value)
                    count++
                }
            }
            Log.d("TEST", "TEST: print all tags for $p: $tags")
            val pack = SimpleViewPacksTag(p, count.toString(), tags, group)
            packs.add(pack)
        }

        Log.d("TEST", "TEST: print all unique packs: $uniquePacks")
        Log.d("TEST", "TEST: print all simpleview pack: $packs")

        packs.sortBy { it.group }

        return packs
    }

    private suspend fun getLocalSimpleViewPacks(): List<SimpleViewPacksTag> {
        val fileTags = localFileTagRepository.fileTags()
        Log.d("TEST", "TEST: print all packs: $fileTags")
        val uniquePacks: MutableSet<String> = mutableSetOf()
        fileTags.forEach {
            if (it.pack?.isEmpty() == true) return@forEach

            it.pack?.let { it1 -> uniquePacks.addAll(it1) }
        }

        uniquePacks.removeIf { it.isEmpty() }
        val packs: MutableList<SimpleViewPacksTag> = mutableListOf()

        uniquePacks.forEach { p ->
            val tags: MutableList<Int> = mutableListOf()
            var group = ""
            var count = 0
            fileTags.forEach {
                if (it.pack?.contains(p) == true) {
                    group = it.group
                    tags.add(it.value)
                    count++
                }
            }
            Log.d("TEST", "TEST: print all tags for $p: $tags")
            val pack = SimpleViewPacksTag(p, count.toString(), tags, group)
            packs.add(pack)
        }

        Log.d("TEST", "TEST: print all unique packs: $uniquePacks")
        Log.d("TEST", "TEST: print all simpleview pack: $packs")

        packs.sortBy { it.group }

        return packs
    }

    suspend fun getSimpleViewPacksTags(
            type: RethinkBlocklistFragment.RethinkBlocklistType): List<SimpleViewPacksTag> {
        return if (type.isRemote()) {
            getRemoteSimpleViewPacks()
        } else {
            getLocalSimpleViewPacks()
        }
    }

    // fixme: remove the below code when the filetag is updated from version 1 to 2.
    // TODO: move this strings to strings.xml
    val PARENTAL_CONTROL = RethinkBlockType("ParentalControl",
                                                    "Block adult & pirated content, online gambling & dating, and social media.")
    val SECURITY = RethinkBlockType("Security",
                                            "Block malware, ransomware, cryptoware, phishers, and other threats.")
    val PRIVACY = RethinkBlockType("Privacy", "Block attentionware, spyware, scareware.")

    private val ADULT = SimpleViewTag(0, "Adult", "Blocks over 30,000 adult websites.",
                                      mutableListOf(), PARENTAL_CONTROL)
    private val PIRACY = SimpleViewTag(1, "Piracy",
                                       "Blocks torrent, dubious video streaming and file sharing websites.",
                                       mutableListOf(), PARENTAL_CONTROL)
    private val GAMBLING = SimpleViewTag(2, "Gambling",
                                         "Blocks over 2000+ online gambling websites.",
                                         mutableListOf(), PARENTAL_CONTROL)
    private val DATING = SimpleViewTag(3, "Dating", "Blocks over 3000+ online dating websites.",
                                       mutableListOf(), PARENTAL_CONTROL)
    private val SOCIAL_MEDIA = SimpleViewTag(4, "Social Media",
                                             "Blocks popular social media including Facebook, Instagram, and WhatsApp.",
                                             mutableListOf(), PARENTAL_CONTROL)
    private val SEC_FULL = SimpleViewTag(5, "Full",
                                         "Blocks over 150,000 malware, ransomware, phishing and other threats.",
                                         mutableListOf(), SECURITY)
    private val SEC_EXTRA = SimpleViewTag(6, "Extra", "Blocks over 3000+ cryptoware websites",
                                          mutableListOf(), SECURITY)
    private val PRIVACY_LITE = SimpleViewTag(7, "Lite",
                                             "Blocks over 50,000+ attentionware through some of the most well-curated blocklists.",
                                             mutableListOf(), PRIVACY)
    private val PRIVACY_AGGRESSIVE = SimpleViewTag(8, "Aggressive",
                                                   "Blocks over 100,000+ attentionware, spyware through some of the most extensive blocklists.",
                                                   mutableListOf(), PRIVACY)
    private val PRIVACY_EXTREME = SimpleViewTag(9, "Extreme",
                                                "Blocks over 1,000,000+ suspected websites.",
                                                mutableListOf(), PRIVACY)

    // read and parse the json file, either remote or local blocklist
    // returns the parsed FileTag list, on error return empty array list
    suspend fun readJson(context: Context, type: AppDownloadManager.DownloadType, timestamp: Long): Boolean {
        return if (type.isRemote()) {
            readRemoteJson(context, timestamp)
        } else {
            readLocalJson(context, timestamp)
        }
    }

    private suspend fun readLocalJson(context: Context, timestamp: Long): Boolean {
        try {
            val dbFileTagLocal: MutableList<RethinkLocalFileTag> = mutableListOf()
            /*val dir = Utilities.blocklistDownloadBasePath(context,
                                                          LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME,
                                                          timestamp)*/

            val dir = Utilities.blocklistDownloadBasePath(context, "Test", timestamp)

            val file = Utilities.blocklistFile(dir, ONDEVICE_BLOCKLIST_FILE_TAG) ?: return false

            val jsonString = file.bufferedReader().use { it.readText() }
            val entries: JsonObject = Gson().fromJson(jsonString, JsonObject::class.java)
            entries.entrySet().forEach {
                val t = Gson().fromJson(it.value, RethinkLocalFileTag::class.java)
                // add subg tag as "others" if its empty
                if (t.subg.isEmpty()) {
                    t.subg = EMPTY_SUBGROUP
                }
                if (t.pack == null) {
                    t.pack = arrayListOf()
                }
                t.group = t.group.lowercase()
                val simpleViewTag = getSimpleBlocklistDetails(t.uname, t.subg)
                t.simpleTagId = simpleViewTag?.id ?: RethinkLocalFileTag.INVALID_SIMPLE_TAG_ID
                dbFileTagLocal.add(t)
            }
            localFileTagRepository.insertAll(dbFileTagLocal.toList())
            Log.i(LoggerConstants.LOG_TAG_DNS, "New Local blocklist files inserted into database")
            return true
        } catch (ioException: IOException) {
            Log.e(LoggerConstants.LOG_TAG_DNS,
                  "Failure reading json file, blocklist type: remote, timestamp: $timestamp",
                  ioException)
        }
        return false
    }

    private suspend fun readRemoteJson(context: Context, timestamp: Long): Boolean {
        try {
            val dbFileTagRemote: MutableList<RethinkRemoteFileTag> = mutableListOf()

            val dir = Utilities.blocklistDownloadBasePath(context, "Test", timestamp)

            /*val dir = Utilities.blocklistDownloadBasePath(context,
                                                          REMOTE_BLOCKLIST_DOWNLOAD_FOLDER_NAME,
                                                          timestamp)*/

            val file = Utilities.blocklistFile(dir, ONDEVICE_BLOCKLIST_FILE_TAG) ?: return false

            val jsonString = file.bufferedReader().use { it.readText() }
            val entries: JsonObject = Gson().fromJson(jsonString, JsonObject::class.java)

            Log.d("TEST","TEST for timestamp: $timestamp, $jsonString")
            entries.entrySet().forEach {
                Log.d("TEST","TEST ${it.value}")
                val t = Gson().fromJson(it.value, RethinkRemoteFileTag::class.java)
                Log.d("TEST","TEST ${t.pack}, ${t.entries}, ${t.simpleTagId}")
                // add subg tag as "others" if its empty
                if (t.subg.isEmpty()) {
                    t.subg = EMPTY_SUBGROUP
                }
                if (t.pack == null) {
                    t.pack = arrayListOf()
                }
                t.group = t.group.lowercase()
                val simpleViewTag = getSimpleBlocklistDetails(t.uname, t.subg)
                t.simpleTagId = simpleViewTag?.id ?: RethinkRemoteFileTag.INVALID_SIMPLE_TAG_ID
                dbFileTagRemote.add(t)
            }
            remoteFileTagRepository.insertAll(dbFileTagRemote.toList())
            Log.i(LoggerConstants.LOG_TAG_DNS, "New Remote blocklist files inserted into database")
            return true
        } catch (ioException: IOException) {
            Log.e(LoggerConstants.LOG_TAG_DNS,
                  "Failure reading json file, blocklist type: remote, timestamp: $timestamp",
                  ioException)
        }
        return false
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

    suspend fun updateFiletagsLocal(values: Set<Int>, isSelected: Int) {
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
        localFileTagRepository.clearSelectedTags()
    }

    fun getStamp(context: Context, fileValues: Set<Int>,
                 type: RethinkBlocklistFragment.RethinkBlocklistType): String {
        return try {
            val flags = convertListToCsv(fileValues)
            getBraveDns(context, blocklistTimestamp(type), type)?.flagsToStamp(flags) ?: ""
        } catch (e: java.lang.Exception) {
            Log.e(LoggerConstants.LOG_TAG_VPN,
                  "Exception while fetching stamp from tags: ${e.message}, $e ")
            ""
        }
    }

    private fun blocklistTimestamp(type: RethinkBlocklistFragment.RethinkBlocklistType): Long {
        return if (type.isLocal()) {
            persistentState.localBlocklistTimestamp
        } else {
            persistentState.remoteBlocklistTimestamp
        }
    }

    fun getTagsFromStamp(context: Context, stamp: String,
                         type: RethinkBlocklistFragment.RethinkBlocklistType): Set<Int> {
        return try {
            convertCsvToList(getBraveDns(context, blocklistTimestamp(type), type)?.stampToFlags(stamp))
        } catch (e: Exception) {
            Log.e(LoggerConstants.LOG_TAG_VPN,
                  "Exception while fetching tags from stamp: ${e.message}, $e ")
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

        val dir = Utilities.remoteBlocklistFile(context, REMOTE_BLOCKLIST_DOWNLOAD_FOLDER_NAME,
                                                timestamp) ?: return null
        val file = Utilities.blocklistFile(dir.absolutePath,
                                           ONDEVICE_BLOCKLIST_FILE_TAG) ?: return null

        braveDnsRemote = try {
            if (file.exists()) {
                Dnsx.newBraveDNSRemote(file.absolutePath)
            } else {
                Log.e(LoggerConstants.LOG_TAG_VPN,
                      "File does not exist in path: ${file.absolutePath}")
                null
            }
        } catch (e: Exception) {
            Log.e(LoggerConstants.LOG_TAG_VPN,
                  "Exception creating BraveDNS object, ${e.message}, $e ")
            null
        }
        return braveDnsRemote
    }

    private fun getBraveDnsLocal(context: Context, timestamp: Long): BraveDNS? {
        if (braveDnsLocal != null) {
            return braveDnsLocal
        }

        val dir = Utilities.remoteBlocklistFile(context, LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME,
                                                timestamp) ?: return null
        val file = Utilities.blocklistFile(dir.absolutePath,
                                           ONDEVICE_BLOCKLIST_FILE_TAG) ?: return null
        braveDnsLocal = try {
            if (file.exists()) {
                Dnsx.newBraveDNSRemote(file.absolutePath)
            } else {
                Log.e(LoggerConstants.LOG_TAG_VPN, "File does not exist in path: ${file.absolutePath}")
                null
            }
        } catch (e: Exception) {
            Log.e(LoggerConstants.LOG_TAG_VPN, "Exception creating BraveDNS object, ${e.message}, $e ")
            null
        }
        return braveDnsLocal
    }

    private fun getBraveDns(context: Context, timestamp: Long,
                            type: RethinkBlocklistFragment.RethinkBlocklistType): BraveDNS? {
        if (type.isRemote()) {
            return getBraveDnsRemote(context, timestamp)
        }

        return getBraveDnsLocal(context, timestamp)
    }

    fun createBraveDns(context: Context, timestamp: Long,
                       type: RethinkBlocklistFragment.RethinkBlocklistType) {
        getBraveDns(context, timestamp, type)
    }

    // this is for version 1 of filetag.json
    // from version 2, packs will be introduced
    // for simple blocklist ui constructions
    private fun getSimpleBlocklistDetails(name: String, subgroup: String): SimpleViewTag? {
        return if (subgroup == "threat-intelligence-feeds") {
            SEC_FULL
        } else if (subgroup == "cryptojacking" || subgroup == "parked-domains-cname") {
            SEC_EXTRA
        } else if (subgroup == "porn") {
            ADULT
        } else if (subgroup == "piracy") {
            PIRACY
        } else if (subgroup == "gambling") {
            GAMBLING
        } else if (subgroup == "dating") {
            DATING
        } else if (subgroup == "social-networks") {
            SOCIAL_MEDIA
        } else if (subgroup == "affiliate-tracking-domains") {
            PRIVACY_EXTREME
        } else if (subgroup == "notracking") {
            PRIVACY_EXTREME
        } else if (subgroup == "rethinkdns-recommended") {
            PRIVACY_LITE
        } else if (subgroup == "others") {
            if (name == "OHE") { // 1Hosts (Complete)
                PRIVACY_AGGRESSIVE
            } else if (name == "XAT") { // ABPVN
                PRIVACY_AGGRESSIVE
            } else if (name == "OSE") { // ADWars
                PRIVACY_AGGRESSIVE
            } else if (name == "IBB") { // ADAway
                PRIVACY_AGGRESSIVE
            } else if (name == "HZD") { // Adguard
                PRIVACY_LITE
            } else if (name == "FLW") { //AntiAd
                PRIVACY_AGGRESSIVE
            } else if (name == "OFY") { // Anudeep's Blacklist
                PRIVACY_LITE
            } else if (name == "IJO") { // Cameleon
                PRIVACY_EXTREME
            } else if (name == "EMY") { // Disconnect ads
                PRIVACY_LITE
            } else if (name == "XMM") { // Disconnect Malware
                SEC_FULL
            } else if (name == "CQT") { //Disconnect Tracking
                PRIVACY_EXTREME
            } else if (name == "ANW") { //EasyList China
                PRIVACY_AGGRESSIVE
            } else if (name == "DGE" || name == "BBS" || name == "OKW" || name == "ONV" || name == "CDE" || name == "PAL" || name == "MDE" || name == "EOO") {
                PRIVACY_EXTREME// EasyList (regional)
            } else if (name == "DBP" || name == "MHP") {
                PRIVACY_LITE // EasyList (privacy)
            } else if (name == "OUU") { // Energized Blu Go
                PRIVACY_LITE
            } else if (name == "YXS") { // Energized Blu
                PRIVACY_AGGRESSIVE
            } else if (name == "TXJ" || name == "DPY") { // Energized Extreme
                PRIVACY_EXTREME  // and Energized Ultimate
            } else if (name == "EOK") { // GoodbyeAds
                PRIVACY_EXTREME
            } else if (name == "HQL") { // HostsVN
                PRIVACY_LITE
            } else if (name == "QKN" || name == "MPR") { // Lightswitch
                PRIVACY_EXTREME
            } else if (name == "XIO") { // NoTrack Tracker
                PRIVACY_AGGRESSIVE
            } else if (name == "YBO" || name == "NML") { // Ru AdList
                PRIVACY_AGGRESSIVE // and Perflyst's SmartTV Blockers
            } else if (name == "TTW") { // Peter Lowe
                PRIVACY_LITE
            } else if (name == "AMI") { // someonewhocare.org
                PRIVACY_AGGRESSIVE
            } else if (name == "FHM" || name == "IAJ") { // dbl.oisd.nl
                PRIVACY_EXTREME // and 1Hosts (Pro)
            } else if (name == "MIN" || name == "IFD") {
                PRIVACY_EXTREME // Shalla's Blacklists
            } else if (name == "TTI") {  // MVPS Hosts
                PRIVACY_EXTREME
            } else if (name == "WWI") { // AdAway Japan
                PRIVACY_EXTREME
            } else {
                null
            }
        } else {
            null
        }
    }
}
