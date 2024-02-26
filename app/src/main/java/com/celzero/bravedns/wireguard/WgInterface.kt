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

import backend.Backend
import com.celzero.bravedns.wireguard.BadConfigException.*
import java.net.InetAddress
import java.util.*
import java.util.stream.Collectors

/**
 * Represents the configuration for a WireGuard interface (an [WgInterface] block). Interfaces must
 * have a private key (used to initialize a `KeyPair`), and may optionally have several other
 * attributes.
 *
 * Instances of this class are immutable.
 */
@NonNullForAll
class WgInterface private constructor(builder: Builder) {
    private val addresses: Set<InetNetwork> // The collection is already immutable.

    /**
     * Returns the set of DNS servers associated with the interface.
     *
     * @return a set of [InetAddress]es
     */
    val dnsServers: Set<InetAddress>

    /**
     * Returns the set of DNS search domains associated with the interface.
     *
     * @return a set of strings
     */
    val dnsSearchDomains: Set<String>

    /**
     * Returns the set of applications excluded from using the interface.
     *
     * @return a set of package names
     */
    val excludedApplications: Set<String>

    /**
     * Returns the set of applications included exclusively for using the interface.
     *
     * @return a set of package names
     */
    val includedApplications: Set<String>
    private val keyPair: KeyPair

    /**
     * Returns the UDP port number that the WireGuard interface will listen on.
     *
     * @return a UDP port number, or `Optional.empty()` if none is configured
     */
    val listenPort: Optional<Int>

    /**
     * Returns the MTU used for the WireGuard interface.
     *
     * @return the MTU, or `Optional.empty()` if none is configured
     */
    val mtu: Optional<Int>

    init {
        // Defensively copy to ensure immutability even if the Builder is reused.
        addresses = Collections.unmodifiableSet(LinkedHashSet(builder.addresses))
        dnsServers = Collections.unmodifiableSet(LinkedHashSet(builder.dnsServers))
        dnsSearchDomains = Collections.unmodifiableSet(LinkedHashSet(builder.dnsSearchDomains))
        excludedApplications =
            Collections.unmodifiableSet(LinkedHashSet(builder.excludedApplications))
        includedApplications =
            Collections.unmodifiableSet(LinkedHashSet(builder.includedApplications))
        keyPair = Objects.requireNonNull(builder.keyPair, "Interfaces must have a private key")!!
        listenPort = builder.listenPort
        mtu = builder.mtu
    }

    override fun equals(obj: Any?): Boolean {
        if (obj !is WgInterface) return false
        return addresses == obj.addresses &&
            dnsServers == obj.dnsServers &&
            dnsSearchDomains == obj.dnsSearchDomains &&
            excludedApplications == obj.excludedApplications &&
            includedApplications == obj.includedApplications &&
            keyPair == obj.keyPair &&
            listenPort == obj.listenPort &&
            mtu == obj.mtu
    }

    /**
     * Returns the set of IP addresses assigned to the interface.
     *
     * @return a set of [InetNetwork]s
     */
    fun getAddresses(): Set<InetNetwork> {
        // The collection is already immutable.
        return addresses
    }

    /**
     * Returns the public/private key pair used by the interface.
     *
     * @return a key pair
     */
    fun getKeyPair(): KeyPair {
        return keyPair
    }

    override fun hashCode(): Int {
        var hash = 1
        hash = 31 * hash + addresses.hashCode()
        hash = 31 * hash + dnsServers.hashCode()
        hash = 31 * hash + excludedApplications.hashCode()
        hash = 31 * hash + includedApplications.hashCode()
        hash = 31 * hash + keyPair.hashCode()
        hash = 31 * hash + listenPort.hashCode()
        hash = 31 * hash + mtu.hashCode()
        return hash
    }

    /**
     * Converts the `Interface` into a string suitable for debugging purposes. The `Interface` is
     * identified by its public key and (if set) the port used for its UDP socket.
     *
     * @return A concise single-line identifier for the `Interface`
     */
    override fun toString(): String {
        val sb = StringBuilder("(Interface ")
        sb.append(keyPair.getPublicKey().base64())
        listenPort.ifPresent { lp: Int? -> sb.append(" @").append(lp) }
        sb.append(')')
        return sb.toString()
    }

    /**
     * Converts the `Interface` into a string suitable for inclusion in a `wg-quick` configuration
     * file.
     *
     * @return The `Interface` represented as a series of "Key = Value" lines
     */
    fun toWgQuickString(): String {
        val sb = StringBuilder()
        if (addresses.isNotEmpty())
            sb.append("Address = ").append(Attribute.join(addresses)).append('\n')
        if (dnsServers.isNotEmpty()) {
            val dnsServerStrings =
                dnsServers
                    .stream()
                    .map { obj: InetAddress -> obj.hostAddress }
                    .collect(Collectors.toList())
            dnsServerStrings.addAll(dnsSearchDomains)
            sb.append("DNS = ").append(Attribute.join(dnsServerStrings)).append('\n')
        }
        if (excludedApplications.isNotEmpty())
            sb.append("ExcludedApplications = ")
                .append(Attribute.join(excludedApplications))
                .append('\n')
        if (includedApplications.isNotEmpty())
            sb.append("IncludedApplications = ")
                .append(Attribute.join(includedApplications))
                .append('\n')
        listenPort.ifPresent { lp: Int? -> sb.append("ListenPort = ").append(lp).append('\n') }
        mtu.ifPresent { m: Int? -> sb.append("MTU = ").append(m).append('\n') }
        sb.append("PrivateKey = ").append(keyPair.getPrivateKey().base64()).append('\n')
        return sb.toString()
    }

    /**
     * Serializes the `Interface` for use with the WireGuard cross-platform userspace API. Note that
     * not all attributes are included in this representation.
     *
     * @return the `Interface` represented as a series of "KEY=VALUE" lines
     */
    fun toWgUserspaceString(): String {
        val dnsServerStrings =
            dnsServers
                .stream()
                .map { obj: InetAddress -> obj.hostAddress }
                .collect(Collectors.toList())
        dnsServerStrings.addAll(dnsSearchDomains)

        val sb = StringBuilder()
        sb.append("private_key=").append(keyPair.getPrivateKey().hex()).append('\n')
        sb.append("address=").append(Attribute.join(addresses)).append('\n')
        sb.append("dns=").append(Attribute.join(dnsServerStrings)).append('\n')
        sb.append("mtu=").append(mtu.orElse(1280)).append('\n')
        listenPort.ifPresent { lp: Int? -> sb.append("listen_port=").append(lp).append('\n') }
        return sb.toString()
    }

    class Builder {
        // Defaults to an empty set.
        val addresses: MutableSet<InetNetwork> = LinkedHashSet<InetNetwork>()

        // Defaults to an empty set.
        val dnsServers: MutableSet<InetAddress> = LinkedHashSet()

        // Defaults to an empty set.
        val dnsSearchDomains: MutableSet<String> = LinkedHashSet()

        // Defaults to an empty set.
        val excludedApplications: MutableSet<String> = LinkedHashSet()

        // Defaults to an empty set.
        val includedApplications: MutableSet<String> = LinkedHashSet()

        // No default; must be provided before building.
        var keyPair: KeyPair? = null

        // Defaults to not present.
        var listenPort = Optional.empty<Int>()

        // Defaults to not present.
        var mtu = Optional.empty<Int>()

        fun addAddress(addr: String): Builder {
            val address = InetNetwork.parse(addr)
            addresses.add(address)
            return this
        }

        fun addAddresses(addresses: Collection<InetNetwork>?): Builder {
            this.addresses.addAll(addresses!!)
            return this
        }

        fun addDnsServer(dnsServer: InetAddress): Builder {
            dnsServers.add(dnsServer)
            return this
        }

        fun addDnsServers(dnsServers: Collection<InetAddress>?): Builder {
            this.dnsServers.addAll(dnsServers!!)
            return this
        }

        fun addDnsSearchDomain(dnsSearchDomain: String): Builder {
            dnsSearchDomains.add(dnsSearchDomain)
            return this
        }

        fun addDnsSearchDomains(dnsSearchDomains: Collection<String>?): Builder {
            this.dnsSearchDomains.addAll(dnsSearchDomains!!)
            return this
        }

        @Throws(BadConfigException::class)
        fun build(): WgInterface {
            if (keyPair == null)
                throw BadConfigException(
                    Section.INTERFACE,
                    Location.PRIVATE_KEY,
                    Reason.MISSING_ATTRIBUTE,
                    null
                )
            if (includedApplications.isNotEmpty() && excludedApplications.isNotEmpty())
                throw BadConfigException(
                    Section.INTERFACE,
                    Location.INCLUDED_APPLICATIONS,
                    Reason.INVALID_KEY,
                    null
                )
            return WgInterface(this)
        }

        fun excludeApplication(application: String): Builder {
            excludedApplications.add(application)
            return this
        }

        fun excludeApplications(applications: Collection<String>?): Builder {
            excludedApplications.addAll(applications!!)
            return this
        }

        fun includeApplication(application: String): Builder {
            includedApplications.add(application)
            return this
        }

        fun includeApplications(applications: Collection<String>?): Builder {
            includedApplications.addAll(applications!!)
            return this
        }

        @Throws(BadConfigException::class)
        fun parseAddresses(addresses: CharSequence?): Builder {
            return try {
                for (address in Attribute.split(addresses)) addAddress(address)
                this
            } catch (e: ParseException) {
                throw BadConfigException(Section.INTERFACE, Location.ADDRESS, e)
            }
        }

        @Throws(BadConfigException::class)
        fun parseDnsServers(dnsServers: CharSequence?): Builder {
            return try {
                for (dnsServer in Attribute.split(dnsServers)) {
                    try {
                        addDnsServer(InetAddresses.parse(dnsServer))
                    } catch (e: ParseException) {
                        if (
                            e.parsingClass !== InetAddress::class.java ||
                                !InetAddresses.isHostname(dnsServer)
                        )
                            throw e
                        addDnsSearchDomain(dnsServer)
                    }
                }
                this
            } catch (e: ParseException) {
                throw BadConfigException(Section.INTERFACE, Location.DNS, e)
            }
        }

        fun parseExcludedApplications(apps: CharSequence?): Builder {
            return excludeApplications(null)
        }

        fun parseIncludedApplications(apps: CharSequence?): Builder {
            return includeApplications(null)
        }

        @Throws(BadConfigException::class)
        fun parseListenPort(listenPort: String): Builder {
            return try {
                setListenPort(listenPort.toInt())
            } catch (e: NumberFormatException) {
                throw BadConfigException(Section.INTERFACE, Location.LISTEN_PORT, listenPort, e)
            }
        }

        @Throws(BadConfigException::class)
        fun parseMtu(mtu: String): Builder {
            return try {
                setMtu(mtu.toInt())
            } catch (e: NumberFormatException) {
                throw BadConfigException(Section.INTERFACE, Location.MTU, mtu, e)
            }
        }

        @Throws(BadConfigException::class)
        fun parsePrivateKey(privateKey: String?): Builder {
            return try {
                val key = privateKey?.let { Backend.newWgPrivateKeyOf(privateKey) }
                val keyPair = key?.let { KeyPair(it) }
                setKeyPair(keyPair)
            } catch (e: Exception) {
                throw BadConfigException(Section.INTERFACE, Location.PRIVATE_KEY, e)
            }
        }

        fun setKeyPair(keyPair: KeyPair?): Builder {
            this.keyPair = keyPair
            return this
        }

        @Throws(BadConfigException::class)
        fun setListenPort(listenPort: Int): Builder {
            if (listenPort < MIN_UDP_PORT || listenPort > MAX_UDP_PORT)
                throw BadConfigException(
                    Section.INTERFACE,
                    Location.LISTEN_PORT,
                    Reason.INVALID_VALUE,
                    listenPort.toString()
                )
            this.listenPort = if (listenPort == 0) Optional.empty() else Optional.of(listenPort)
            return this
        }

        @Throws(BadConfigException::class)
        fun setMtu(mtu: Int): Builder {
            if (mtu < 0)
                throw BadConfigException(
                    Section.INTERFACE,
                    Location.LISTEN_PORT,
                    Reason.INVALID_VALUE,
                    mtu.toString()
                )
            this.mtu = if (mtu == 0) Optional.empty() else Optional.of(mtu)
            return this
        }
    }

    companion object {
        private const val MAX_UDP_PORT = 65535
        private const val MIN_UDP_PORT = 0

        /**
         * Parses an series of "KEY = VALUE" lines into an `Interface`. Throws [ParseException] if
         * the input is not well-formed or contains unknown attributes.
         *
         * @param lines An iterable sequence of lines, containing at least a private key attribute
         * @return An `Interface` with all of the attributes from `lines` set
         */
        @Throws(BadConfigException::class)
        fun parse(lines: Iterable<CharSequence?>): WgInterface {
            val builder = Builder()
            for (line in lines) {
                val attribute: Attribute =
                    Attribute.parse(line).orElseThrow {
                        BadConfigException(
                            Section.INTERFACE,
                            Location.TOP_LEVEL,
                            Reason.SYNTAX_ERROR,
                            line
                        )
                    }
                when (attribute.key.lowercase(Locale.ENGLISH)) {
                    "address" -> builder.parseAddresses(attribute.value)
                    "dns" -> builder.parseDnsServers(attribute.value)
                    "excludedapplications" -> builder.parseExcludedApplications(attribute.value)
                    "includedapplications" -> builder.parseIncludedApplications(attribute.value)
                    "listenport" -> builder.parseListenPort(attribute.value)
                    "mtu" -> builder.parseMtu(attribute.value)
                    "privatekey" -> builder.parsePrivateKey(attribute.value)
                    "publickey" -> {} // Ignore public key
                    else ->
                        throw BadConfigException(
                            Section.INTERFACE,
                            Location.TOP_LEVEL,
                            Reason.UNKNOWN_ATTRIBUTE,
                            attribute.key
                        )
                }
            }
            return builder.build()
        }
    }
}
