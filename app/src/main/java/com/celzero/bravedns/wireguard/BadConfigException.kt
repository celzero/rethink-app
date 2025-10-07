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
package com.rethinkdns.retrixed.wireguard

class BadConfigException
private constructor(
    val section: Section,
    val location: Location,
    val reason: Reason,
    val text: CharSequence?,
    cause: Throwable?
) : Exception(cause) {

    constructor(
        section: Section,
        location: Location,
        reason: Reason,
        text: CharSequence?
    ) : this(section, location, reason, text, null) {}

    constructor(
        section: Section,
        location: Location,
        text: CharSequence?,
        cause: NumberFormatException?
    ) : this(section, location, Reason.INVALID_NUMBER, text, cause) {}

    constructor(
        section: Section,
        location: Location,
        cause: ParseException
    ) : this(section, location, Reason.INVALID_VALUE, cause.text, cause) {}

    constructor(
        section: Section,
        location: Location,
        cause: Throwable
    ) : this(section, location, Reason.SYNTAX_ERROR, null, cause) {}

    enum class Location(name: String) {
        TOP_LEVEL(""),
        ADDRESS("Address"),
        ALLOWED_IPS("AllowedIPs"),
        DNS("DNS"),
        ENDPOINT("Endpoint"),
        EXCLUDED_APPLICATIONS("ExcludedApplications"),
        INCLUDED_APPLICATIONS("IncludedApplications"),
        LISTEN_PORT("ListenPort"),
        MTU("MTU"),
        PERSISTENT_KEEPALIVE("PersistentKeepalive"),
        PRE_SHARED_KEY("PresharedKey"),
        PRIVATE_KEY("PrivateKey"),
        PUBLIC_KEY("PublicKey"),
        CLIENT_ID("ClientID"),
        AMNEZIA("Amnezia")
    }

    enum class Reason {
        INVALID_KEY,
        INVALID_NUMBER,
        INVALID_VALUE,
        MISSING_ATTRIBUTE,
        MISSING_SECTION,
        SYNTAX_ERROR,
        UNKNOWN_ATTRIBUTE,
        UNKNOWN_SECTION
    }

    enum class Section(name: String) {
        CONFIG("Config"),
        INTERFACE("Interface"),
        PEER("Peer")
    }
}
