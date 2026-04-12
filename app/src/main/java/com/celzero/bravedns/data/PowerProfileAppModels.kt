/*
 * Copyright 2026 RethinkDNS and its authors
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
package com.celzero.bravedns.data

import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.util.Constants

data class PowerProfileAppBlocklist(
    val packageName: String,
    val appName: String,
    val firewallStatus: Int = FirewallManager.FirewallStatus.NONE.id,
    val connectionStatus: Int = FirewallManager.ConnectionStatus.ALLOW.id,
    val domainRules: List<PowerProfileAppDomainRule> = emptyList(),
    val ipRules: List<PowerProfileAppIpRule> = emptyList()
) {
    fun supportedRuleCount(): Int = domainRules.size + ipRules.size
}

data class PowerProfileAppDomainRule(
    val domain: String,
    val status: Int = DomainRulesManager.Status.BLOCK.id,
    val type: Int = DomainRulesManager.DomainType.DOMAIN.id,
    val ips: String = "",
    val proxyId: String = "",
    val proxyCC: String = ""
)

data class PowerProfileAppIpRule(
    val ipAddress: String,
    val port: Int = Constants.UNSPECIFIED_PORT,
    val protocol: String = "",
    val status: Int = IpRulesManager.IpRuleStatus.BLOCK.id,
    val isActive: Boolean = true,
    val wildcard: Boolean = false,
    val proxyId: String = "",
    val proxyCC: String = ""
)

data class PowerProfileResolvedAppBlocklist(
    val packageName: String,
    val appName: String,
    val uid: Int,
    val firewallStatus: FirewallManager.FirewallStatus,
    val connectionStatus: FirewallManager.ConnectionStatus,
    val domainRules: List<PowerProfileAppDomainRule>,
    val ipRules: List<PowerProfileAppIpRule>
)

data class PowerProfileOwnedAppDomainRule(
    val packageName: String,
    val domain: String
) {
    fun key(): String = "${packageName.lowercase()}|${domain.lowercase()}"
}

data class PowerProfileOwnedAppIpRule(
    val packageName: String,
    val ipAddress: String,
    val port: Int
) {
    fun key(): String = "${packageName.lowercase()}|${ipAddress.lowercase()}|$port"
}
