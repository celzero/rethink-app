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
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.Toast
import com.celzero.bravedns.R
import com.celzero.bravedns.data.FileTag
import com.celzero.bravedns.download.AppDownloadManager
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.LoggerConstants
import com.celzero.bravedns.util.Utilities
import com.google.gson.Gson
import com.google.gson.JsonObject
import org.koin.core.component.KoinComponent
import java.io.IOException
import java.sql.Timestamp
import kotlin.math.floor

object RethinkBlocklistsManager : KoinComponent {

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

    // this is for version 1 of filetag.json
    // from version 2, packs will be introduced
    // for simple blocklist ui constructions
    fun getSimpleBlocklistDetails(name: String, subgroup: String): SimpleViewTag? {
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
        } else if (subgroup == "nextdns-recommended") {
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

    private val ADULT = SimpleViewTag(0, "Adult", "Blocks over 30,000 adult websites.")
    private val PIRACY = SimpleViewTag(1, "Piracy","Blocks torrent, dubious video streaming and file sharing websites.")
    private val GAMBLING = SimpleViewTag(2, "Gambling","Blocks over 2000+ online gambling websites.")
    private val DATING = SimpleViewTag(3, "Dating","Blocks over 3000+ online dating websites.")
    private val SOCIAL_MEDIA = SimpleViewTag(4,"Social Media","Blocks popular social media including Facebook, Instagram, and WhatsApp.")
    private val SEC_FULL = SimpleViewTag(5, "Security Full","Blocks over 150,000 malware, ransomware, phishing and other threats.")
    private val SEC_EXTRA = SimpleViewTag(6, "Security Extra","Blocks over 3000+ cryptoware websites")
    private val PRIVACY_LITE = SimpleViewTag(7, "Privacy Lite", "Blocks over 50,000+ adware and trackers through some of the most well-curated blocklists.")
    private val PRIVACY_AGGRESSIVE = SimpleViewTag(8, "Privacy Aggressive", "Blocks over 100,000+ adware, spyware, and trackers through some of the most extensive blocklists.")
    private val PRIVACY_EXTREME = SimpleViewTag(9, "Privacy Extreme", "Blocks over 1,000,000+ suspected websites.")

    data class SimpleViewTag(val id: Int, val name: String, val desc: String)

    // read and parse the json file, either remote or local blocklist
    // returns the parsed FileTag list
    // on error return empty array list
    fun readJson(context: Context, type: Int, timestamp: Long): MutableList<FileTag> {
        var fileTags: MutableList<FileTag> = ArrayList()

        try {
            val dir = if (type == AppDownloadManager.DownloadType.REMOTE.id) {
                Utilities.remoteBlocklistDownloadBasePath(context,
                                              Constants.REMOTE_BLOCKLIST_DOWNLOAD_FOLDER_NAME,
                                              timestamp)
            } else {
                Utilities.localBlocklistDownloadBasePath(context,
                                             Constants.LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME,
                                             timestamp)
            }

            val file = Utilities.blocklistFile(dir,
                                               Constants.ONDEVICE_BLOCKLIST_FILE_TAG) ?: return ArrayList()
            val jsonString = file.bufferedReader().use { it.readText() }
            val entries: JsonObject = Gson().fromJson(jsonString, JsonObject::class.java)

            entries.entrySet().forEach {
                val t = Gson().fromJson(it.value, FileTag::class.java)
                // add subg tag as "others" if its empty
                if (t.subg.isEmpty()) {
                    t.subg = "others"
                }
                t.simpleViewTag = getSimpleBlocklistDetails(t.uname, t.subg)
                fileTags.add(t)
            }

            fileTags = fileTags.distinctBy { it.simpleViewTag }.filter { it.simpleViewTag != null }.toMutableList()
            fileTags.sortBy { it.simpleViewTag?.id }

            return fileTags
        } catch (ioException: IOException) {
            Log.e(LoggerConstants.LOG_TAG_DNS,
                  "Failure reading json file, blocklist type: $type, timestamp: $timestamp",
                  ioException)
            return ArrayList()
        }
    }

    fun getStamp(fileValues: ArrayList<Int>): String {
        // fixme: make call to go lib to fetch the stamp from the fileValues
        return "https://basic.rethinkdns.com/1:IAAQAA=="
    }

}
