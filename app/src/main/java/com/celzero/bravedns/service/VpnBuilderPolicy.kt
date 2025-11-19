/*
 * Copyright 2025 RethinkDNS and its authors
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

import java.util.concurrent.TimeUnit

enum class VpnBuilderPolicy(
    val vpnAdapterBehaviour: GoVpnAdapterBehaviour,
    val connectionMonitorBehaviour: ConnectionMonitorBehaviour
) {
    AUTO(
        vpnAdapterBehaviour = GoVpnAdapterBehaviour.NEVER_RESTART,
        connectionMonitorBehaviour = ConnectionMonitorBehaviour.TRANSPORTS
    ),
    SENSITIVE(
        vpnAdapterBehaviour = GoVpnAdapterBehaviour.NEVER_RESTART,
        connectionMonitorBehaviour = ConnectionMonitorBehaviour.VALIDATED_NETWORKS_AND_TRANSPORTS
    ),
    RELAXED(
        vpnAdapterBehaviour = GoVpnAdapterBehaviour.PREFER_RESTART,
        connectionMonitorBehaviour = ConnectionMonitorBehaviour.VALIDATED_NETWORKS
    ),
    FIXED(
        vpnAdapterBehaviour = GoVpnAdapterBehaviour.NEVER_RESTART,
        connectionMonitorBehaviour = ConnectionMonitorBehaviour.TRANSPORTS
    );

    enum class GoVpnAdapterBehaviour {
        NEVER_RESTART, // do not restart tun for any change (unlink / setLinksAndRoutes)
        PREFER_RESTART // restart tun based on vpn lockdown state
    }

    enum class ConnectionMonitorBehaviour {
        TRANSPORTS, // monitor transports
        VALIDATED_NETWORKS, // monitor validated networks
        VALIDATED_NETWORKS_AND_TRANSPORTS // monitor both validated networks and transports
    }

    companion object {
        fun fromOrdinalOrDefault(ordinal: Int, default: VpnBuilderPolicy = AUTO): VpnBuilderPolicy {
            return entries.getOrNull(ordinal) ?: default
        }

        fun getNetworkBehaviourDuration(policy: ConnectionMonitorBehaviour): Long {
            return when (policy) {
                ConnectionMonitorBehaviour.TRANSPORTS -> TimeUnit.SECONDS.toMillis(3L)
                ConnectionMonitorBehaviour.VALIDATED_NETWORKS -> TimeUnit.SECONDS.toMillis(1L)
                ConnectionMonitorBehaviour.VALIDATED_NETWORKS_AND_TRANSPORTS -> TimeUnit.SECONDS.toMillis(3L)
            }
        }
    }
}
