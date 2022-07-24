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
enum class FirewallRuleset(val id: String, val title: Int, val desc: Int, val act: Int) {

    RULE0("No Rule", R.string.firewall_rule_no_rule, R.string.firewall_rule_no_rule_desc,
          FirewallRuleset.allow),
    RULE1("Rule #1", R.string.firewall_rule_block_app, R.string.firewall_rule_block_app_desc,
          FirewallRuleset.stall),
    RULE1B("Rule #1B", R.string.firewall_rule_block_app_new_install,
           R.string.firewall_rule_block_app_desc, FirewallRuleset.block),
    RULE1C("Rule #1C", R.string.firewall_rule_block_app_exception,
           R.string.firewall_rule_block_app_desc, FirewallRuleset.stall),
    RULE1D("Rule #1D", R.string.firewall_rule_block_unmetered,
           R.string.firewall_rule_block_app_unmetered_desc, FirewallRuleset.stall),
    RULE1E("Rule #1E", R.string.firewall_rule_block_metered,
           R.string.firewall_rule_block_app_mobile_desc, FirewallRuleset.stall),
    RULE1F("Rule #1F", R.string.firewall_rule_univ_block_metered,
           R.string.firewall_rule_block_app_univ_mobile_desc, FirewallRuleset.stall),
    RULE2("Rule #2", R.string.firewall_rule_block_ip, R.string.firewall_rule_block_ip_desc,
          FirewallRuleset.stall),
    RULE2B("Rule #2B", R.string.firewall_rule_bypass_apprule_ip,
           R.string.firewall_rule_bypass_app_rules_ip_desc, FirewallRuleset.allow),
    RULE2C("Rule #2C", R.string.firewall_rule_bypass_universal_ip,
           R.string.firewall_rule_bypass_universal_ip_desc, FirewallRuleset.allow),
    RULE3("Rule #3", R.string.firewall_rule_device_lock, R.string.firewall_rule_device_lock_desc,
          FirewallRuleset.stall),
    RULE4("Rule #4", R.string.firewall_rule_foreground, R.string.firewall_rule_foreground_desc,
          FirewallRuleset.block),
    RULE5("Rule #5", R.string.firewall_rule_unknown, R.string.firewall_rule_unknown_desc,
          FirewallRuleset.stall),
    RULE6("Rule #6", R.string.firewall_rule_block_udp_ntp,
          R.string.firewall_rule_block_udp_ntp_desc, FirewallRuleset.stall),
    RULE7("Rule #7", R.string.firewall_rule_block_dns_bypass,
          R.string.firewall_rule_block_dns_bypass_desc, FirewallRuleset.block),
    RULE8("Whitelist", R.string.firewall_rule_exempt_app_bypass_univ,
          R.string.firewall_rule_exempt_app_bypass_univ_desc, FirewallRuleset.allow),
    RULE9("Proxied", R.string.firewall_rule_exempt_dns_proxied,
          R.string.firewall_rule_exempt_dns_proxied_desc, FirewallRuleset.allow),
    RULE9B("Orbot setup", R.string.firewall_rule_exempt_orbot_setup,
           R.string.firewall_rule_exempt_orbot_setup_desc, FirewallRuleset.allow),
    RULE10("Http block", R.string.firewall_rule_block_http, R.string.firewall_rule_block_http_desc,
           FirewallRuleset.block);

    companion object {

        private const val allow = 0
        private const val block = 1
        private const val stall = 2

        fun getFirewallRule(ruleId: String): FirewallRuleset? {
            return when (ruleId) {
                RULE0.id -> RULE0
                RULE1.id -> RULE1
                RULE1B.id -> RULE1B
                RULE1C.id -> RULE1C
                RULE1D.id -> RULE1D
                RULE1E.id -> RULE1E
                RULE1F.id -> RULE1F
                RULE2.id -> RULE2
                RULE2B.id -> RULE2B
                RULE2C.id -> RULE2C
                RULE3.id -> RULE3
                RULE4.id -> RULE4
                RULE5.id -> RULE5
                RULE6.id -> RULE6
                RULE7.id -> RULE7
                RULE8.id -> RULE8
                RULE9.id -> RULE9
                RULE9B.id -> RULE9B
                RULE10.id -> RULE10
                else -> null
            }
        }

        // TODO: Move ico to enum var like for label and desc
        fun getRulesIcon(ruleId: String?): Int {
            return when (ruleId) {
                RULE0.id -> R.drawable.ic_whats_new
                RULE1.id -> R.drawable.ic_app_info
                RULE1B.id -> R.drawable.ic_auto_start
                RULE1C.id -> R.drawable.ic_filter_error
                RULE1D.id -> R.drawable.ic_firewall_wifi_on_grey
                RULE1E.id -> R.drawable.ic_firewall_data_on_grey_alpha
                RULE1F.id -> R.drawable.ic_univ_metered
                RULE2.id -> R.drawable.spinner_firewall
                RULE2B.id -> R.drawable.ic_bypass
                RULE2C.id -> R.drawable.ic_bypass
                RULE3.id -> R.drawable.ic_device_lock
                RULE4.id -> R.drawable.ic_foreground
                RULE5.id -> R.drawable.ic_unknown_app
                RULE6.id -> R.drawable.ic_udp
                RULE7.id -> R.drawable.ic_prevent_dns_leaks
                RULE8.id -> R.drawable.bs_firewall_home_screen
                RULE9.id -> R.drawable.bs_dns_home_screen
                RULE9B.id -> R.drawable.ic_orbot
                RULE10.id -> R.drawable.ic_http
                else -> R.drawable.bs_dns_home_screen
            }
        }

        // TODO: return a Set
        fun getAllRules(): List<FirewallRuleset> {
            return values().toList()
        }

        fun getAllowedRules(): List<FirewallRuleset> {
            return values().toList().filter { it.act == allow }
        }

        fun getBlockedRules(): List<FirewallRuleset> {
            return values().toList().filter { it.act != allow }
        }

        fun stall(rule: FirewallRuleset): Boolean {
            return rule.act == stall
        }

        fun ground(rule: FirewallRuleset): Boolean {
            return rule.act != allow
        }

        fun shouldShowHint(rule: String?): Boolean {
            if (rule == null) return false

            return when (rule) {
                RULE8.id ->  true
                RULE9.id -> true
                RULE7.id -> true
                RULE2C.id -> true
                RULE2B.id -> true
                else -> false
            }
        }
    }

}
