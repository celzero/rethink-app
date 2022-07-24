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

    private const val EMPTY_SUBGROUP = "others"
    private const val INVALID_SIMPLE_TAG_ID = -1

    data class SimpleViewTag(val id: Int, val name: String, val desc: String,
                             val tags: MutableList<Int>, val rethinkBlockType: RethinkBlockType)

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

    // fixme: remove the below code when the filetag is updated from version 1 to 2.
    // TODO: move this strings to strings.xml
    private val PARENTAL_CONTROL = RethinkBlockType("Parental Control",
                                                    "Block adult & pirated content, online gambling & dating, and social media.")
    private val SECURITY = RethinkBlockType("Security",
                                            "Block malware, ransomware, cryptoware, phishers, and other threats.")
    private val PRIVACY = RethinkBlockType("Privacy",
                                           "Block adware, spyware, scareware, and trackers.")

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
                                             "Blocks over 50,000+ adware and trackers through some of the most well-curated blocklists.",
                                             mutableListOf(), PRIVACY)
    private val PRIVACY_AGGRESSIVE = SimpleViewTag(8, "Aggressive",
                                                   "Blocks over 100,000+ adware, spyware, and trackers through some of the most extensive blocklists.",
                                                   mutableListOf(), PRIVACY)
    private val PRIVACY_EXTREME = SimpleViewTag(9, "Extreme",
                                                "Blocks over 1,000,000+ suspected websites.",
                                                mutableListOf(), PRIVACY)

    // read and parse the json file, either remote or local blocklist
    // returns the parsed FileTag list, on error return empty array list
    suspend fun readJson(context: Context, type: AppDownloadManager.DownloadType, timestamp: Long) {
        if (type.isRemote()) {
            readRemoteJson(context, timestamp)
        } else {
            readLocalJson(context, timestamp)
        }
    }

    private suspend fun readLocalJson(context: Context, timestamp: Long) {
        try {
            val dbFileTagLocal: MutableList<RethinkLocalFileTag> = mutableListOf()
            val dir = Utilities.localBlocklistDownloadBasePath(context,
                                                               LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME,
                                                               timestamp)

            val file = Utilities.blocklistFile(dir, ONDEVICE_BLOCKLIST_FILE_TAG) ?: return

            val jsonString = file.bufferedReader().use { it.readText() }
            val entries: JsonObject = Gson().fromJson(jsonString, JsonObject::class.java)
            entries.entrySet().forEach {
                val t = Gson().fromJson(it.value, RethinkLocalFileTag::class.java)
                // add subg tag as "others" if its empty
                if (t.subg.isEmpty()) {
                    t.subg = EMPTY_SUBGROUP
                }
                val simpleViewTag = getSimpleBlocklistDetails(t.uname, t.subg)
                t.simpleTagId = simpleViewTag?.id ?: RethinkLocalFileTag.INVALID_SIMPLE_TAG_ID
                dbFileTagLocal.add(t)
            }
            localFileTagRepository.insertAll(dbFileTagLocal.toList())
            Log.i(LoggerConstants.LOG_TAG_DNS, "New Local blocklist files inserted into database")
        } catch (ioException: IOException) {
            Log.e(LoggerConstants.LOG_TAG_DNS,
                  "Failure reading json file, blocklist type: remote, timestamp: $timestamp",
                  ioException)
        }
    }

    private suspend fun readRemoteJson(context: Context, timestamp: Long) {
        try {
            val dbFileTagRemote: MutableList<RethinkRemoteFileTag> = mutableListOf()

            val dir = Utilities.remoteBlocklistDownloadBasePath(context,
                                                                REMOTE_BLOCKLIST_DOWNLOAD_FOLDER_NAME,
                                                                timestamp)

            val file = Utilities.blocklistFile(dir, ONDEVICE_BLOCKLIST_FILE_TAG) ?: return

            val jsonString = file.bufferedReader().use { it.readText() }
            val entries: JsonObject = Gson().fromJson(jsonString, JsonObject::class.java)

            entries.entrySet().forEach {
                val t = Gson().fromJson(it.value, RethinkRemoteFileTag::class.java)
                // add subg tag as "others" if its empty
                if (t.subg.isEmpty()) {
                    t.subg = EMPTY_SUBGROUP
                }
                val simpleViewTag = getSimpleBlocklistDetails(t.uname, t.subg)
                t.simpleTagId = simpleViewTag?.id ?: RethinkRemoteFileTag.INVALID_SIMPLE_TAG_ID
                dbFileTagRemote.add(t)
            }
            remoteFileTagRepository.insertAll(dbFileTagRemote.toList())
            Log.i(LoggerConstants.LOG_TAG_DNS, "New Remote blocklist files inserted into database")
        } catch (ioException: IOException) {
            Log.e(LoggerConstants.LOG_TAG_DNS,
                  "Failure reading json file, blocklist type: remote, timestamp: $timestamp",
                  ioException)
        }
    }

    suspend fun updateSelectedFiletagRemote(remote: RethinkRemoteFileTag) {
        remoteFileTagRepository.update(remote)
    }

    suspend fun updateSelectedFiletagLocal(local: RethinkLocalFileTag) {
        localFileTagRepository.update(local)
    }

    suspend fun updateSelectedFiletagsRemote(values: Set<Int>, isSelected: Int) {
        remoteFileTagRepository.updateSelectedTags(values, isSelected)
    }

    suspend fun updateSelectedFiletagsLocal(values: Set<Int>, isSelected: Int) {
        localFileTagRepository.updateSelectedTags(values, isSelected)
    }

    suspend fun clearSelectedTagsRemote() {
        remoteFileTagRepository.clearSelectedTags()
    }

    suspend fun clearSelectedTagsLocal() {
        localFileTagRepository.clearSelectedTags()
    }

    fun getStamp(context: Context, timestamp: Long, fileValues: Set<Int>,
                 type: RethinkBlocklistFragment.RethinkBlocklistType): String {
        return try {
            val flags = convertListToCsv(fileValues)
            getBraveDns(context, timestamp, type)?.flagsToStamp(flags) ?: ""
        } catch (e: java.lang.Exception) {
            Log.e(LoggerConstants.LOG_TAG_VPN,
                  "Exception while fetching stamp from tags: ${e.message}, $e ")
            ""
        }
    }

    fun getSelectedFileTags(context: Context, timestamp: Long, stamp: String,
                            type: RethinkBlocklistFragment.RethinkBlocklistType): Set<Int> {
        // fixme: make go call to fetch the file tag list for the given stamp
        return try {
            convertCsvToList(getBraveDns(context, timestamp, type)?.stampToFlags(stamp))
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
        try {
            if (file.exists()) {
                braveDnsLocal = Dnsx.newBraveDNSRemote(file.absolutePath)
            } else {
                Log.e(LoggerConstants.LOG_TAG_VPN,
                      "File does not exist in path: ${file.absolutePath}")
                braveDnsLocal = null
            }
        } catch (e: Exception) {
            Log.e(LoggerConstants.LOG_TAG_VPN,
                  "Exception creating BraveDNS object, ${e.message}, $e ")
            braveDnsLocal = null
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

// below code is for stamp to tag/ tag to stamp conversion.Remove?
/*


private val MASK_BOTTOM = arrayOf(0xffff, 0xfffe, 0xfffc, 0xfff8, 0xfff0, 0xffe0, 0xffc0,
                                      0xff80, 0xff00, 0xfe00, 0xfc00, 0xf800, 0xf000, 0xe000,
                                      0xc000, 0x8000, 0x0000)

    private var BitsSetTable256: IntArray = IntArray(256)

    private fun initialize() {
        BitsSetTable256[0] = 0
        for (i in 0..255) {
            BitsSetTable256[i] = (i and 1) + BitsSetTable256[floor((i / 2).toDouble()).toInt()]
        }
    }

    fun stamp(s: Set<Int>) {
        initialize()
        val stamp = btoa(encodeToBinary(tagToFlag(s)))
        val a = stamp.replace("\\//g", "_").replace("\\+/g", "-")
        //return stamp
    }

    private fun encodeToBinary(s: String): UByteArray {
        val codeUnits = UShortArray(s.length)

        codeUnits.forEachIndexed { index, _ ->
            // The resulting `Short` value is represented by the least significant 16 bits of this `Int` value.
            codeUnits[index] = Character.codePointAt(s, index).toUShort()
        }
        return convertIntegersToBytes(codeUnits)
    }

    // ref: http://www.java2s.com/Code/Java/File-Input-Output/convertsanintintegerarraytoabytearray.htm
    // ref: http://www.java2s.com/Code/Java/Collections-Data-Structure/intarraytobytearray.htm
    private fun convertIntegersToBytes(value: UShortArray): UByteArray {
        val outputBytes = UByteArray(value.size * 2)
        var i = 0
        var k = 0
        while (i < value.size) {
            val temp = value[i].toInt()
            outputBytes[k++] = (temp ushr 0 and 0xFF).toUShort().toUByte()
            outputBytes[k++] = (temp ushr 8 and 0xFF).toUShort().toUByte()
            i++
        }
        return outputBytes
    }

    private fun tagToFlag(s: Set<Int>): String {
        var res = fromCharCode(0)

        s.forEach {
            val value = it
            val header = 0
            val index = ((value / 16) or 0)
            val position = value % 16

            var h: Int = Character.codePointAt(res, header)

            val dataIndex = numberOfSetBits((h and MASK_BOTTOM[16 - index])) + 1

            val n1 = (h ushr (15 - index)) and 0x1
            var n = if (n1 != 1) {
                0
            } else {
                Character.codePointAt(res, dataIndex)
            }

            val upsertData = n != 0

            h = h or (1 shl (15 - index))
            n = n or (1 shl (15 - position))

            val intRange = if (upsertData) {
                dataIndex + 1
            } else {
                dataIndex
            }

            val a = res.slice(IntRange(1, dataIndex - 1))
            val b = res.slice(IntRange(intRange, res.length - 1))
            res = fromCharCode(h) + a + fromCharCode(n) + b

        }
        return res
    }

    private fun fromCharCode(vararg codePoints: Int): String {
        return String(codePoints, 0, codePoints.size)
    }

    private fun numberOfSetBits(n: Int): Int {
        return BitsSetTable256[n and 0xff] + BitsSetTable256[n ushr 8 and 0xff] + BitsSetTable256[n ushr 16 and 0xff] + BitsSetTable256[n ushr 24]
    }

    private fun btoa(b: UByteArray): String {
        var s = ""
        b.forEach {
            s += it.toInt().toChar()
        }
        return Base64.encodeToString(s.toByteArray(), 0)
    }
*/
