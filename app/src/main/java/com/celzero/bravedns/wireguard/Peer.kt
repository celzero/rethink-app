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

import com.celzero.bravedns.RethinkDnsApplication
import com.celzero.bravedns.wireguard.BadConfigException.Location
import com.celzero.bravedns.wireguard.BadConfigException.Reason
import com.celzero.bravedns.wireguard.BadConfigException.Section
import com.celzero.firestack.backend.Backend
import com.celzero.firestack.backend.WgKey
import inet.ipaddr.IPAddressString
import java.util.Collections
import java.util.Locale
import java.util.Objects
import java.util.Optional
import java.util.function.Consumer

/**
 * Represents the configuration for a WireGuard peer (a [Peer] block). Peers must have a public key,
 * and may optionally have several other attributes.
 *
 * Instances of this class are immutable.
 */
class Peer private constructor(builder: Builder) {
    val id: Int = 0
    private val allowedIps: Set<InetNetwork>
    private val endpoint: Optional<InetEndpoint>
    private val unresolvedEndpoint: Optional<String>

    /**
     * Returns the peer's persistent keepalive.
     *
     * @return the persistent keepalive, or `Optional.empty()` if none is configured
     */
    val persistentKeepalive: Optional<Int>
    private val preSharedKey: Optional<WgKey>
    private val publicKey: WgKey

    init {
        // Defensively copy to ensure immutability even if the Builder is reused.
        @Suppress("UNCHECKED_CAST")
        allowedIps =
            Collections.unmodifiableSet(LinkedHashSet<Any?>(builder.allowedIps)) as Set<InetNetwork>
        endpoint = builder.endpoint
        unresolvedEndpoint = builder.unresolvedEndpoint
        persistentKeepalive = builder.persistentKeepalive
        preSharedKey = builder.preSharedKey
        publicKey = Objects.requireNonNull(builder.publicKey, "Peers must have a public key")!!
    }

    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun equals(obj: Any?): Boolean {
        if (obj !is Peer) return false
        return allowedIps == obj.allowedIps &&
                endpoint == obj.endpoint &&
                unresolvedEndpoint == obj.unresolvedEndpoint &&
                persistentKeepalive == obj.persistentKeepalive &&
                preSharedKey == obj.preSharedKey &&
                publicKey == obj.publicKey
    }

    /**
     * Returns the peer's set of allowed IPs.
     *
     * @return the set of allowed IPs
     */
    fun getAllowedIps(): Set<InetNetwork> {
        // The collection is already immutable.
        return allowedIps
    }

    /**
     * Returns the peer's endpoint.
     *
     * @return the endpoint, or `Optional.empty()` if none is configured
     */
    fun getEndpoint(): Optional<InetEndpoint> {
        return endpoint
    }

    fun getEndpointText(): Optional<String> {
        return unresolvedEndpoint
    }

    /**
     * Returns the peer's pre-shared key.
     *
     * @return the pre-shared key, or `Optional.empty()` if none is configured
     */
    fun getPreSharedKey(): Optional<WgKey> {
        return preSharedKey
    }

    /**
     * Returns the peer's public key.
     *
     * @return the public key
     */
    fun getPublicKey(): WgKey {
        return publicKey
    }

    override fun hashCode(): Int {
        var hash = 1
        hash = 31 * hash + allowedIps.hashCode()
        hash = 31 * hash + endpoint.hashCode()
        hash = 31 * hash + unresolvedEndpoint.hashCode()
        hash = 31 * hash + persistentKeepalive.hashCode()
        hash = 31 * hash + preSharedKey.hashCode()
        hash = 31 * hash + publicKey.hashCode()
        return hash
    }

    /**
     * Converts the `Peer` into a string suitable for debugging purposes. The `Peer` is identified
     * by its public key and (if known) its endpoint.
     *
     * @return a concise single-line identifier for the `Peer`
     */
    override fun toString(): String {
        val sb = StringBuilder("(Peer ")
        sb.append(publicKey.base64())
        endpoint.ifPresent(
            Consumer<InetEndpoint> { ep: InetEndpoint? -> sb.append(" @").append(ep) }
        )
        sb.append(')')
        return sb.toString()
    }

    /**
     * Converts the `Peer` into a string suitable for inclusion in a `wg-quick` configuration file.
     *
     * @return the `Peer` represented as a series of "Key = Value" lines
     */
    fun toWgQuickString(): String {
        val sb = StringBuilder()
        if (allowedIps.isNotEmpty())
            sb.append("AllowedIPs = ").append(Attribute.join(allowedIps)).append('\n')
        endpoint.ifPresent(
            Consumer<InetEndpoint> { ep: InetEndpoint? ->
                sb.append("Endpoint = ").append(ep).append('\n')
            }
        )
        unresolvedEndpoint.ifPresent { sb.append("Endpoint = ").append(it).append('\n') }
        persistentKeepalive.ifPresent { pk: Int? ->
            sb.append("PersistentKeepalive = ").append(pk).append('\n')
        }
        preSharedKey.ifPresent(
            Consumer<WgKey> { psk: WgKey ->
                sb.append("PreSharedKey = ").append(psk.base64()).append('\n')
            }
        )
        sb.append("PublicKey = ").append(publicKey.base64()).append('\n')
        return sb.toString()
    }

    /**
     * Serializes the `Peer` for use with the WireGuard cross-platform userspace API. Note that not
     * all attributes are included in this representation.
     *
     * @return the `Peer` represented as a series of "key=value" lines
     */
    fun toWgUserspaceString(isAmz: Boolean): String {
        val sb = StringBuilder()
        // The order here is important: public_key signifies the beginning of a new peer.
        sb.append("public_key=").append(publicKey.hex()).append('\n')
        // for testing purposes, make sure the allowed_ips is set to 0.0.0.0/0 for all peers
        // in amz + debug mode
        if (shouldAmzUseBaseAllowedIps(isAmz)) {
            sb.append("allowed_ip=").append("0.0.0.0/0").append('\n')
        } else {
            for (allowedIp in allowedIps) sb.append("allowed_ip=").append(allowedIp).append('\n')
        }
        endpoint.flatMap<Any>(InetEndpoint::getResolved).ifPresent { ep: Any? ->
            sb.append("endpoint=").append(ep).append('\n')
        }
        unresolvedEndpoint.ifPresent { sb.append("endpoint=").append(it).append('\n') }
        persistentKeepalive.ifPresent { pk: Int? ->
            sb.append("persistent_keepalive_interval=").append(pk).append('\n')
        }
        preSharedKey.ifPresent(
            Consumer<WgKey> { psk: WgKey ->
                sb.append("preshared_key=").append(psk.hex()).append('\n')
            }
        )
        return sb.toString()
    }

    private fun shouldAmzUseBaseAllowedIps(amz: Boolean): Boolean {
        // future some more decision making logic can be added here
        return amz && RethinkDnsApplication.DEBUG
    }

    class Builder {
        // Defaults to an empty set.
        val allowedIps: MutableSet<InetNetwork> = LinkedHashSet<InetNetwork>()

        // Defaults to not present.
        var endpoint: Optional<InetEndpoint> = Optional.empty<InetEndpoint>()

        // Defaults to not present.
        var unresolvedEndpoint: Optional<String> = Optional.empty<String>()

        // Defaults to not present.
        var persistentKeepalive = Optional.empty<Int>()

        // Defaults to not present.
        var preSharedKey: Optional<WgKey> = Optional.empty<WgKey>()

        // No default; must be provided before building.
        var publicKey: WgKey? = null

        fun addAllowedIp(allowedIp: InetNetwork): Builder {
            allowedIps.add(allowedIp)
            return this
        }

        fun addAllowedIps(allowedIps: Collection<InetNetwork>?): Builder {
            this.allowedIps.addAll(allowedIps!!)
            return this
        }

        @Throws(BadConfigException::class)
        fun build(): Peer {
            if (publicKey == null)
                throw BadConfigException(
                    Section.PEER,
                    Location.PUBLIC_KEY,
                    Reason.MISSING_ATTRIBUTE,
                    null
                )
            return Peer(this)
        }

        @Throws(BadConfigException::class)
        fun parseAllowedIPs(allowedIps: CharSequence?): Builder {
            return try {
                for (allowedIp in Attribute.split(allowedIps)) addAllowedIp(
                    InetNetwork.parse(allowedIp)
                )
                this
            } catch (e: ParseException) {
                throw BadConfigException(Section.PEER, Location.ALLOWED_IPS, e)
            }
        }

        @Throws(BadConfigException::class)
        fun parseEndpoint(endpoint: String): Builder {
            return try {
                setEndpoint(InetEndpoint.parse(endpoint))
                // add the domain name to the unresolved endpoint
                parseUnresolvedEndpoint(endpoint)
            } catch (e: ParseException) {
                throw BadConfigException(Section.PEER, Location.ENDPOINT, e)
            }
        }

        @Throws(BadConfigException::class)
        fun parseUnresolvedEndpoint(d: String): Builder {
            return try {
                if (d.isEmpty()) return this

                val ip = IPAddressString(d)
                if (ip.isIPv4 || ip.isIPv6) return this

                setUnresolvedEndpoint(d)
                this
            } catch (e: Exception) {
                setUnresolvedEndpoint(d)
                this
            }
        }

        @Throws(BadConfigException::class)
        fun parsePersistentKeepalive(persistentKeepalive: String): Builder {
            return try {
                setPersistentKeepalive(persistentKeepalive.toInt())
            } catch (e: NumberFormatException) {
                throw BadConfigException(
                    Section.PEER,
                    Location.PERSISTENT_KEEPALIVE,
                    persistentKeepalive,
                    e
                )
            }
        }

        @Throws(BadConfigException::class)
        fun parsePreSharedKey(preSharedKey: String): Builder {
            return try {
                val k = Backend.newWgPrivateKeyOf(preSharedKey)
                setPreSharedKey(k)
            } catch (e: Exception) {
                throw BadConfigException(Section.PEER, Location.PRE_SHARED_KEY, e)
            }
        }

        @Throws(BadConfigException::class)
        fun parsePublicKey(publicKey: String): Builder {
            return try {
                val k = Backend.newWgPrivateKeyOf(publicKey)
                setPublicKey(k)
            } catch (e: Exception) {
                throw BadConfigException(Section.PEER, Location.PUBLIC_KEY, e)
            }
        }

        fun setEndpoint(endpoint: InetEndpoint): Builder {
            this.endpoint = Optional.of<InetEndpoint>(endpoint)
            return this
        }

        fun setUnresolvedEndpoint(endpointText: String): Builder {
            this.unresolvedEndpoint = Optional.of<String>(endpointText)
            return this
        }

        @Throws(BadConfigException::class)
        fun setPersistentKeepalive(persistentKeepalive: Int): Builder {
            if (persistentKeepalive < 0 || persistentKeepalive > MAX_PERSISTENT_KEEPALIVE)
                throw BadConfigException(
                    Section.PEER,
                    Location.PERSISTENT_KEEPALIVE,
                    Reason.INVALID_VALUE,
                    persistentKeepalive.toString()
                )
            this.persistentKeepalive =
                if (persistentKeepalive == 0) Optional.empty() else Optional.of(persistentKeepalive)
            return this
        }

        fun setPreSharedKey(preSharedKey: WgKey): Builder {
            this.preSharedKey = Optional.of<WgKey>(preSharedKey)
            return this
        }

        fun setPublicKey(publicKey: WgKey?): Builder {
            this.publicKey = publicKey
            return this
        }

        companion object {
            // See wg(8)
            private const val MAX_PERSISTENT_KEEPALIVE = 65535
        }
    }

    companion object {
        /**
         * Parses an series of "KEY = VALUE" lines into a `Peer`. Throws [ParseException] if the
         * input is not well-formed or contains unknown attributes.
         *
         * @param lines an iterable sequence of lines, containing at least a public key attribute
         * @return a `Peer` with all of its attributes set from `lines`
         */
        @Throws(BadConfigException::class)
        fun parse(lines: Iterable<CharSequence?>): Peer {
            val builder = Builder()
            for (line in lines) {
                val attribute: Attribute =
                    Attribute.parse(line).orElseThrow {
                        BadConfigException(
                            Section.PEER,
                            Location.TOP_LEVEL,
                            Reason.SYNTAX_ERROR,
                            line
                        )
                    }
                when (attribute.key.lowercase(Locale.ENGLISH)) {
                    "allowedips" -> builder.parseAllowedIPs(attribute.value)
                    "endpoint" -> builder.parseEndpoint(attribute.value)
                    "persistentkeepalive" -> builder.parsePersistentKeepalive(attribute.value)
                    "presharedkey" -> builder.parsePreSharedKey(attribute.value)
                    "publickey" -> builder.parsePublicKey(attribute.value)
                    else ->
                        throw BadConfigException(
                            Section.PEER,
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
