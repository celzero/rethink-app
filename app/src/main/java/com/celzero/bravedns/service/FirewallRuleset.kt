/*
 * Copyright 2021 RethinkDNS and its authors
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

import com.celzero.bravedns.R

// TODO: Add label and description from strings.xml
enum class FirewallRuleset(val id: String, val title: Int, val desc: Int) {
    RULE1("Rule #1", R.string.firewall_rule_block_app, R.string.firewall_rule_block_app_desc),
    RULE2("Rule #2",R.string.firewall_rule_block_ip,R.string.firewall_rule_block_ip_desc),
    RULE3("Rule #3", R.string.firewall_rule_device_lock , R.string.firewall_rule_device_lock_desc),
    RULE4("Rule #4", R.string.firewall_rule_foreground, R.string.firewall_rule_foreground_desc),
    RULE5("Rule #5", R.string.firewall_rule_unknown, R.string.firewall_rule_unknown_desc),
    RULE6("Rule #6", R.string.firewall_rule_block_udp_ntp, R.string.firewall_rule_block_udp_ntp_desc),
    RULE7("Rule #7", R.string.firewall_rule_block_dns_bypass, R.string.firewall_rule_block_dns_bypass_desc),

    // FIXME: #298 - Fix the rule8,9 - find a way out for the whitelist and proxy.
    RULE8("Whitelist", R.string.firewall_rule_exempt_app_whitelist, R.string.firewall_rule_exempt_app_whitelist_desc),
    RULE9("Proxied", R.string.firewall_rule_exempt_dns_proxied, R.string.firewall_rule_exempt_dns_proxied_desc);

    companion object {
        fun getFirewallRule(ruleId: String): FirewallRuleset? {
            return when(ruleId) {
                RULE1.id -> RULE1
                RULE2.id -> RULE2
                RULE3.id -> RULE3
                RULE4.id -> RULE4
                RULE5.id -> RULE5
                RULE6.id -> RULE6
                RULE7.id -> RULE7
                RULE8.id -> RULE8
                RULE9.id -> RULE9
                else -> null
            }
        }

        fun getRulesIcon(ruleId: String?): Int {
            return when (ruleId) {
                RULE1.id -> R.drawable.spinner_firewall
                RULE2.id -> R.drawable.spinner_firewall
                RULE3.id -> R.drawable.ic_device_lock
                RULE4.id -> R.drawable.ic_foreground
                RULE5.id -> R.drawable.ic_unknown_app
                RULE6.id -> R.drawable.ic_udp
                RULE7.id -> R.drawable.ic_prevent_dns_leaks
                RULE8.id -> R.drawable.bs_firewall_home_screen
                RULE9.id -> R.drawable.bs_dns_home_screen
                else -> R.drawable.bs_dns_home_screen
            }
        }
    }
}
