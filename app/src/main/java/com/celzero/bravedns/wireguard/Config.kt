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
package com.celzero.bravedns.wireguard

import com.celzero.bravedns.wireguard.BadConfigException.Location
import com.celzero.bravedns.wireguard.BadConfigException.Reason
import com.celzero.bravedns.wireguard.BadConfigException.Section
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Collections
import java.util.Objects

/**
 * Represents the contents of a wg-quick configuration file, made up of one or more "Interface"
 * sections (combined together), and zero or more "Peer" sections (treated individually).
 *
 * Instances of this class are immutable.
 */
class Config private constructor(builder: Builder) {
    private val id: Int
    private val name: String
    /**
     * Returns the interface section of the configuration.
     *
     * @return the interface configuration
     */
    private val wgInterface: WgInterface?
    private val peers: List<Peer>?

    init {
        id = builder.id
        name = builder.name
        wgInterface =
            Objects.requireNonNull(builder.wgInterface, "An [Interface] section is required")
        // Defensively copy to ensure immutability even if the Builder is reused.
        peers = Collections.unmodifiableList(ArrayList(builder.peers))
    }

    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun equals(obj: Any?): Boolean {
        if (obj !is Config) return false

        return wgInterface?.equals(obj.wgInterface) == true && peers?.equals(obj.peers) == true
    }

    /**
     * Returns the interface section of the configuration.
     *
     * @return the interface configuration
     */
    fun getInterface(): WgInterface? {
        return wgInterface
    }

    /**
     * Returns a list of the configuration's peer sections.
     *
     * @return a list of [Peer]s
     */
    fun getPeers(): List<Peer>? {
        return peers
    }

    fun getId(): Int {
        return id
    }

    fun getName(): String {
        return name
    }

    override fun hashCode(): Int {
        return 31 * wgInterface.hashCode() + peers.hashCode()
    }

    /**
     * Converts the `Config` into a string suitable for debugging purposes. The `Config` is
     * identified by its interface's public key and the number of peers it has.
     *
     * @return a concise single-line identifier for the `Config`
     */
    override fun toString(): String {
        return "(Config " + wgInterface + " (" + (peers?.size ?: 0) + " peers))"
    }

    /**
     * Converts the `Config` into a string suitable for use as a `wg-quick` configuration file.
     *
     * @return the `Config` represented as one [WgInterface] and zero or more [Peer] sections
     */
    fun toWgQuickString(): String {
        val sb = StringBuilder()
        sb.append("[Interface]\n").append(wgInterface?.toWgQuickString() ?: "")
        if (peers != null) {
            for (peer in peers) sb.append("\n[Peer]\n").append(peer.toWgQuickString())
        }
        return sb.toString()
    }

    /**
     * Serializes the `Config` for use with the WireGuard cross-platform userspace API.
     *
     * @return the `Config` represented as a series of "key=value" lines
     */
    fun toWgUserspaceString(skipListenPort: Boolean = false, isAmz: Boolean = false): String {
        // Skip the listen port if we're in advanced mode or randomize (adv) setting is enabled.
        val sb = StringBuilder()
        sb.append(wgInterface?.toWgUserspaceString(skipListenPort) ?: "")
        sb.append("replace_peers=true\n")
        if (peers != null) {
            for (peer in peers) sb.append(peer.toWgUserspaceString(isAmz))
        }
        return sb.toString()
    }

    class Builder {
        var id = -1
        var name = ""
        // Defaults to an empty set.
        val peers: ArrayList<Peer> = ArrayList<Peer>()

        // No default; must be provided before building.
        var wgInterface: WgInterface? = null

        fun addPeer(peer: Peer): Builder {
            peers.add(peer)
            return this
        }

        fun addPeers(peers: Collection<Peer>?): Builder {
            this.peers.addAll(peers!!)
            return this
        }

        fun setId(id: Int): Builder {
            this.id = id
            return this
        }

        fun setName(name: String): Builder {
            this.name = name
            return this
        }

        fun build(): Config {
            requireNotNull(wgInterface) { "An [Interface] section is required" }
            return Config(this)
        }

        @Throws(BadConfigException::class)
        fun parseInterface(lines: Iterable<CharSequence?>?): Builder {
            return setInterface(WgInterface.parse(lines!!))
        }

        @Throws(BadConfigException::class)
        fun parsePeer(lines: Iterable<CharSequence?>): Builder {
            return addPeer(Peer.parse(lines))
        }

        fun setInterface(i: WgInterface?): Builder {
            this.wgInterface = i
            return this
        }
    }

    companion object {
        /**
         * Parses an series of "Interface" and "Peer" sections into a `Config`. Throws
         * [BadConfigException] if the input is not well-formed or contains data that cannot be
         * parsed.
         *
         * @param stream a stream of UTF-8 text that is interpreted as a WireGuard configuration
         * @return a `Config` instance representing the supplied configuration
         */
        @Throws(IOException::class, BadConfigException::class)
        fun parse(stream: InputStream?): Config {
            return parse(BufferedReader(InputStreamReader(stream)))
        }

        /**
         * Parses an series of "Interface" and "Peer" sections into a `Config`. Throws
         * [BadConfigException] if the input is not well-formed or contains data that cannot be
         * parsed.
         *
         * @param reader a BufferedReader of UTF-8 text that is interpreted as a WireGuard
         *   configuration
         * @return a `Config` instance representing the supplied configuration
         */
        @Throws(IOException::class, BadConfigException::class)
        fun parse(reader: BufferedReader): Config {
            val builder = Builder()
            val interfaceLines: MutableCollection<String?> = ArrayList()
            val peerLines: MutableCollection<String?> = ArrayList()
            var inInterfaceSection = false
            var inPeerSection = false
            var seenInterfaceSection = false
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val commentIndex = line!!.indexOf('#')
                if (commentIndex != -1) line = line!!.substring(0, commentIndex)
                line = line!!.trim { it <= ' ' }
                if (line!!.isEmpty()) continue
                if (line!!.startsWith("[")) {
                    // Consume all [Peer] lines read so far.
                    if (inPeerSection) {
                        builder.parsePeer(peerLines)
                        peerLines.clear()
                    }
                    if ("[Interface]".equals(line, ignoreCase = true)) {
                        inInterfaceSection = true
                        inPeerSection = false
                        seenInterfaceSection = true
                    } else if ("[Peer]".equals(line, ignoreCase = true)) {
                        inInterfaceSection = false
                        inPeerSection = true
                    } else {
                        throw BadConfigException(
                            Section.CONFIG,
                            Location.TOP_LEVEL,
                            Reason.UNKNOWN_SECTION,
                            line
                        )
                    }
                } else if (inInterfaceSection) {
                    interfaceLines.add(line)
                } else if (inPeerSection) {
                    peerLines.add(line)
                } else {
                    throw BadConfigException(
                        Section.CONFIG,
                        Location.TOP_LEVEL,
                        Reason.UNKNOWN_SECTION,
                        line
                    )
                }
            }
            if (inPeerSection) builder.parsePeer(peerLines)
            if (!seenInterfaceSection)
                throw BadConfigException(
                    Section.CONFIG,
                    Location.TOP_LEVEL,
                    Reason.MISSING_SECTION,
                    null
                )
            // Combine all [Interface] sections in the file.
            builder.parseInterface(interfaceLines)
            return builder.build()
        }
    }
}
