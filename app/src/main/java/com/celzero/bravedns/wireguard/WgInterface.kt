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
import com.celzero.firestack.backend.Backend
import java.net.InetAddress
import java.util.Collections
import java.util.Locale
import java.util.Objects
import java.util.Optional
import java.util.stream.Collectors

/**
 * Represents the configuration for a WireGuard interface (an [WgInterface] block). Interfaces must
 * have a private key (used to initialize a `KeyPair`), and may optionally have several other
 * attributes.
 *
 * Instances of this class are immutable.
 */
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

    private val clientId: Optional<String>
    private val jc: Optional<Int>
    private val jmin: Optional<Int>
    private val jmax: Optional<Int>
    private val s1: Optional<Int>
    private val s2: Optional<Int>
    private val h1: Optional<Long>
    private val h2: Optional<Long>
    private val h3: Optional<Long>
    private val h4: Optional<Long>

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
        clientId = builder.clientId
        jc = builder.jc
        jmin = builder.jmin
        jmax = builder.jmax
        s1 = builder.s1
        s2 = builder.s2
        h1 = builder.h1
        h2 = builder.h2
        h3 = builder.h3
        h4 = builder.h4
    }

    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun equals(obj: Any?): Boolean {
        if (obj !is WgInterface) return false
        return addresses == obj.addresses &&
            dnsServers == obj.dnsServers &&
            dnsSearchDomains == obj.dnsSearchDomains &&
            excludedApplications == obj.excludedApplications &&
            includedApplications == obj.includedApplications &&
            keyPair == obj.keyPair &&
            listenPort == obj.listenPort &&
            mtu == obj.mtu &&
            clientId == obj.clientId &&
            jc == obj.jc &&
            jmin == obj.jmin &&
            jmax == obj.jmax &&
            s1 == obj.s1 &&
            s2 == obj.s2 &&
            h1 == obj.h1 &&
            h2 == obj.h2 &&
            h3 == obj.h3 &&
            h4 == obj.h4
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

    fun isAmnezia(): Boolean {
        return jc.isPresent || jmin.isPresent || jmax.isPresent || s1.isPresent || s2.isPresent || h1.isPresent || h2.isPresent || h3.isPresent || h4.isPresent
    }

    fun getAmzProps(): String {
        // make all the amz props into a single string
        return "jc=${jc.orElse(0)}, jmin=${jmin.orElse(0)}, jmax=${jmax.orElse(0)}, s1=${s1.orElse(0)}, s2=${s2.orElse(0)}, h1=${h1.orElse(0)}, h2=${h2.orElse(0)}, h3=${h3.orElse(0)}, h4=${h4.orElse(0)}"
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
        hash = 31 * hash + clientId.hashCode()
        hash = 31 * hash + jc.hashCode()
        hash = 31 * hash + jmin.hashCode()
        hash = 31 * hash + jmax.hashCode()
        hash = 31 * hash + s1.hashCode()
        hash = 31 * hash + s2.hashCode()
        hash = 31 * hash + h1.hashCode()
        hash = 31 * hash + h2.hashCode()
        hash = 31 * hash + h3.hashCode()
        hash = 31 * hash + h4.hashCode()
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
     *
     * below is the sample file content for the Interface section, similar will be stored in the
     * wg[0-9].conf file
     * [Interface]
     * Address = 100.80.213.126/32
     * DNS = 10.255.255.3
     * PrivateKey = KBdV2Iv+poXZkCOZukSo30modVHqyO5+GCdfhP4n40I=
     *
     * [Peer]
     * AllowedIPs = 0.0.0.0/0, ::/0
     * Endpoint = yul-359-wg.whiskergalaxy.com:443
     * Endpoint = yul-359-wg.whiskergalaxy.com:443
     * PreSharedKey = VQoBVQxMmgttgGh9JCBwOPiwnFYV6/Py1tRi/f38DEI=
     * PublicKey = nfFRpFZ0ZXWVoz8C4gP5ti7V1snFT1gV8EcIxTWJtB4=
     *
     * below is converted to wg-quick format
     * [Interface]
     * Address = 100.80.213.126/32
     * DNS = 10.255.255.3
     * PrivateKey = KBdV2Iv+poXZkCOZukSo30modVHqyO5+GCdfhP4n40I=
     *
     * [Peer]
     * AllowedIPs = 0.0.0.0/0, ::/0
     * Endpoint = yul-359-wg.whiskergalaxy.com:443
     * Endpoint = yul-359-wg.whiskergalaxy.com:443
     * PreSharedKey = VQoBVQxMmgttgGh9JCBwOPiwnFYV6/Py1tRi/f38DEI=
     * PublicKey = nfFRpFZ0ZXWVoz8C4gP5ti7V1snFT1gV8EcIxTWJtB4=
     *
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
        clientId.ifPresent { c: String? -> sb.append("ClientID = ").append(c).append('\n') }
        jc.ifPresent { j: Int? -> sb.append("JC = ").append(j).append('\n') }
        jmin.ifPresent { j: Int? -> sb.append("JMin = ").append(j).append('\n') }
        jmax.ifPresent { j: Int? -> sb.append("JMax = ").append(j).append('\n') }
        s1.ifPresent { s: Int? -> sb.append("S1 = ").append(s).append('\n') }
        s2.ifPresent { s: Int? -> sb.append("S2 = ").append(s).append('\n') }
        h1.ifPresent { h: Long? -> sb.append("H1 = ").append(h).append('\n') }
        h2.ifPresent { h: Long? -> sb.append("H2 = ").append(h).append('\n') }
        h3.ifPresent { h: Long? -> sb.append("H3 = ").append(h).append('\n') }
        h4.ifPresent { h: Long? -> sb.append("H4 = ").append(h).append('\n') }
        return sb.toString()
    }

    /**
     * Serializes the `Interface` for use with the WireGuard cross-platform userspace API. Note that
     * not all attributes are included in this representation.
     *
     * @return the `Interface` represented as a series of "KEY=VALUE" lines
     *
     * see #toWgQuickString() for more details on the format
     *
     * below is the converted format for the userspace string
     *
     * private_key=281755d88bfea685d9902399ba44a8df49a87551eac8ee7e18275f84fe27e342
     * address=100.80.213.126/32
     * dns=10.255.255.3
     * mtu=1280
     * replace_peers=true
     * public_key=9df151a45674657595a33f02e203f9b62ed5d6c9c54f5815f04708c53589b41e
     * allowed_ip=0.0.0.0/0
     * allowed_ip=::/0
     * endpoint=172.98.68.207:443
     * endpoint=yul-359-wg.whiskergalaxy.com:443
     * preshared_key=550a01550c4c9a0b6d80687d24207038f8b09c5615ebf3f2d6d462fdfdfc0c42
     *
     * below is converted to amnezia userspace format
     *
     * private_key=281755d88bfea685d9902399ba44a8df49a87551eac8ee7e18275f84fe27e342
     * address=100.80.213.126/32
     * dns=10.255.255.3
     * mtu=1280
     * jc=0
     * jmin=0
     * jmax=0
     * s1=0
     * s2=0
     * h1=0
     * h2=0
     * h3=0
     * h4=0
     * replace_peers=true
     * public_key=9df151a45674657595a33f02e203f9b62ed5d6c9c54f5815f04708c53589b41e
     * allowed_ip=0.0.0.0/0
     * allowed_ip=::/0
     * endpoint=172.98.68.207:443
     * endpoint=yul-359-wg.whiskergalaxy.com:443
     * preshared_key=550a01550c4c9a0b6d80687d24207038f8b09c5615ebf3f2d6d462fdfdfc0c42
     *
     */
    fun toWgUserspaceString(skipListenPort: Boolean): String {
        val dnsServerStrings =
            dnsServers
                .stream()
                .map { obj: InetAddress -> obj.hostAddress }
                .collect(Collectors.toList())
        dnsServerStrings.addAll(dnsSearchDomains)

        val sb = StringBuilder()
        sb.append("private_key=").append(keyPair.getPrivateKey().hex()).append('\n')
        // Skip the listen port if it's not set or if we're in advanced mode.
        // In advanced mode, the port may already be in use by another interface.
        if (!skipListenPort) {
            listenPort.ifPresent { lp: Int? -> sb.append("listen_port=").append(lp).append('\n') }
        }
        // non-standard wireguard userspace format
        // address, dns, and mtu required by firestack
        sb.append("address=").append(Attribute.join(addresses)).append('\n')
        sb.append("dns=").append(Attribute.join(dnsServerStrings)).append('\n')
        sb.append("mtu=").append(mtu.orElse(DEFAULT_MTU)).append('\n') // -1 if not present
        // clientId required by warp
        clientId.ifPresent { c: String? -> sb.append("client_id=").append(c).append('\n') }
        // amnezia specific attributes
        jc.ifPresent { j: Int? -> sb.append("jc=").append(j).append('\n') }
        jmin.ifPresent { j: Int? -> sb.append("jmin=").append(j).append('\n') }
        jmax.ifPresent { j: Int? -> sb.append("jmax=").append(j).append('\n') }
        s1.ifPresent { s: Int? -> sb.append("s1=").append(s).append('\n') }
        s2.ifPresent { s: Int? -> sb.append("s2=").append(s).append('\n') }
        h1.ifPresent { h: Long? -> sb.append("h1=").append(h).append('\n') }
        h2.ifPresent { h: Long? -> sb.append("h2=").append(h).append('\n') }
        h3.ifPresent { h: Long? -> sb.append("h3=").append(h).append('\n') }
        h4.ifPresent { h: Long? -> sb.append("h4=").append(h).append('\n') }
        return sb.toString()
    }

    fun getClientId(): Optional<String> {
        return clientId
    }

    fun getJc(): Optional<Int> {
        return jc
    }

    fun getJmin(): Optional<Int> {
        return jmin
    }

    fun getJmax(): Optional<Int> {
        return jmax
    }

    fun getS1(): Optional<Int> {
        return s1
    }

    fun getS2(): Optional<Int> {
        return s2
    }

    fun getH1(): Optional<Long> {
        return h1
    }

    fun getH2(): Optional<Long> {
        return h2
    }

    fun getH3(): Optional<Long> {
        return h3
    }

    fun getH4(): Optional<Long> {
        return h4
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

        var clientId = Optional.empty<String>()

        var jc = Optional.empty<Int>()
        var jmin = Optional.empty<Int>()
        var jmax = Optional.empty<Int>()
        var s1 = Optional.empty<Int>()
        var s2 = Optional.empty<Int>()
        var h1 = Optional.empty<Long>()
        var h2 = Optional.empty<Long>()
        var h3 = Optional.empty<Long>()
        var h4 = Optional.empty<Long>()

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

        fun setClientId(clientId: Optional<String>): Builder {
            this.clientId = clientId
            return this
        }

        fun setJc(jc: Int): Builder {
            this.jc = Optional.of(jc)
            return this
        }

        fun setJmin(jmin: Int): Builder {
            this.jmin = Optional.of(jmin)
            return this
        }

        fun setJmax(jmax: Int): Builder {
            this.jmax = Optional.of(jmax)
            return this
        }

        fun setS1(s1: Int): Builder {
            this.s1 = Optional.of(s1)
            return this
        }

        fun setS2(s2: Int): Builder {
            this.s2 = Optional.of(s2)
            return this
        }

        fun setH1(h1: Long): Builder {
            this.h1 = Optional.of(h1)
            return this
        }

        fun setH2(h2: Long): Builder {
            this.h2 = Optional.of(h2)
            return this
        }

        fun setH3(h3: Long): Builder {
            this.h3 = Optional.of(h3)
            return this
        }

        fun setH4(h4: Long): Builder {
            this.h4 = Optional.of(h4)
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

        fun parseClientId(clientId: String): Builder {
            try {
                return setClientId(Optional.of(clientId))
            } catch (e: NullPointerException) {
                val throwable = Throwable(e)
                throw BadConfigException(Section.INTERFACE, Location.CLIENT_ID, throwable)
            }
        }

        fun parseJc(jc: String): Builder {
            return try {
                setJc(jc.toInt())
            } catch (e: NumberFormatException) {
                throw BadConfigException(Section.INTERFACE, Location.AMNEZIA, jc, e)
            }
        }

        fun parseJmin(jmin: String): Builder {
            return try {
                setJmin(jmin.toInt())
            } catch (e: NumberFormatException) {
                throw BadConfigException(Section.INTERFACE, Location.AMNEZIA, jmin, e)
            }
        }

        fun parseJmax(jmax: String): Builder {
            return try {
                setJmax(jmax.toInt())
            } catch (e: NumberFormatException) {
                throw BadConfigException(Section.INTERFACE, Location.AMNEZIA, jmax, e)
            }
        }

        fun parseS1(s1: String): Builder {
            return try {
                setS1(s1.toInt())
            } catch (e: NumberFormatException) {
                throw BadConfigException(Section.INTERFACE, Location.AMNEZIA, s1, e)
            }
        }

        fun parseS2(s2: String): Builder {
            return try {
                setS2(s2.toInt())
            } catch (e: NumberFormatException) {
                throw BadConfigException(Section.INTERFACE, Location.AMNEZIA, s2, e)
            }
        }

        fun parseH1(h1: String): Builder {
            return try {
                setH1(h1.toLong())
            } catch (e: NumberFormatException) {
                throw BadConfigException(Section.INTERFACE, Location.AMNEZIA, h1, e)
            }
        }

        fun parseH2(h2: String): Builder {
            return try {
                setH2(h2.toLong())
            } catch (e: NumberFormatException) {
                throw BadConfigException(Section.INTERFACE, Location.AMNEZIA, h2, e)
            }
        }

        fun parseH3(h3: String): Builder {
            return try {
                setH3(h3.toLong())
            } catch (e: NumberFormatException) {
                throw BadConfigException(Section.INTERFACE, Location.AMNEZIA, h3, e)
            }
        }

        fun parseH4(h4: String): Builder {
            return try {
                setH4(h4.toLong())
            } catch (e: NumberFormatException) {
                throw BadConfigException(Section.INTERFACE, Location.AMNEZIA, h4, e)
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
            if (mtu < -1)
                throw BadConfigException(
                    Section.INTERFACE,
                    Location.MTU,
                    Reason.INVALID_VALUE,
                    mtu.toString()
                )
            this.mtu = if (mtu == 0 || mtu == DEFAULT_MTU) Optional.empty() else Optional.of(mtu)
            return this
        }
    }

    companion object {
        private const val MAX_UDP_PORT = 65535
        private const val MIN_UDP_PORT = 0
        private const val DEFAULT_MTU = -1 // -1 means not set

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
                    "clientid" -> builder.parseClientId(attribute.value)
                    "jc" -> builder.parseJc(attribute.value)
                    "jmin" -> builder.parseJmin(attribute.value)
                    "jmax" -> builder.parseJmax(attribute.value)
                    "s1" -> builder.parseS1(attribute.value)
                    "s2" -> builder.parseS2(attribute.value)
                    "h1" -> builder.parseH1(attribute.value)
                    "h2" -> builder.parseH2(attribute.value)
                    "h3" -> builder.parseH3(attribute.value)
                    "h4" -> builder.parseH4(attribute.value)
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
