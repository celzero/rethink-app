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
    RULE0(
        "No Rule",
        R.string.firewall_rule_no_rule,
        R.string.firewall_rule_no_rule_desc,
        R.integer.allow
    ),
    RULE1(
        "Rule #1",
        R.string.firewall_rule_block_app,
        R.string.firewall_rule_block_app_desc,
        R.integer.stall
    ),
    RULE1B(
        "Rule #1B",
        R.string.firewall_rule_block_app_new_install,
        R.string.firewall_rule_new_app_desc,
        R.integer.block
    ),
    RULE1C(
        "Rule #1C",
        R.string.firewall_rule_block_app_exception,
        R.string.firewall_rule_block_app_desc,
        R.integer.stall
    ),
    RULE1D(
        "Rule #1D",
        R.string.firewall_rule_block_unmetered,
        R.string.firewall_rule_block_app_unmetered_desc,
        R.integer.stall
    ),
    RULE1E(
        "Rule #1E",
        R.string.firewall_rule_block_metered,
        R.string.firewall_rule_block_app_mobile_desc,
        R.integer.stall
    ),
    RULE1F(
        "Rule #1F",
        R.string.firewall_rule_univ_block_metered,
        R.string.firewall_rule_block_app_univ_mobile_desc,
        R.integer.stall
    ),
    RULE1G(
        "Rule #1G",
        R.string.firewall_rule_isolate,
        R.string.firewall_rule_isolate_desc,
        R.integer.stall
    ),
    RULE1H(
        "Rule #1H",
        R.string.firewall_rule_bypass_dns_firewall,
        R.string.firewall_rule_bypass_dns_firewall_desc,
        R.integer.allow
    ),
    RULE2(
        "Rule #2",
        R.string.firewall_rule_block_ip,
        R.string.firewall_rule_block_ip_desc,
        R.integer.stall
    ),
    RULE2B(
        "Rule #2B",
        R.string.firewall_rule_trusted_ip,
        R.string.firewall_rule_trusted_ip_desc,
        R.integer.allow
    ),
    RULE2C(
        "Rule #2C",
        R.string.firewall_rule_bypass_universal_ip,
        R.string.firewall_rule_bypass_universal_ip_desc,
        R.integer.allow
    ),
    RULE2D(
        "Rule #2D",
        R.string.firewall_rule_block_univ_ip,
        R.string.firewall_rule_block_ip_univ_desc,
        R.integer.stall
    ),
    RULE2E(
        "Rule #2E",
        R.string.firewall_rule_block_domain,
        R.string.firewall_rule_block_domain_desc,
        R.integer.stall
    ),
    RULE2F(
        "Rule #2F",
        R.string.firewall_rule_trusted_domain,
        R.string.firewall_rule_trusted_domain_desc,
        R.integer.allow
    ),
    RULE2G(
        "Rule #2G",
        R.string.firewall_rule_dns_blocked,
        R.string.firewall_rule_dns_blocked_desc,
        R.integer.stall
    ),
    RULE2H(
        "Rule #2H",
        R.string.firewall_rule_domain_blocked_univ,
        R.string.firewall_rule_domain_blocked_univ_desc,
        R.integer.stall
    ),
    RULE2I(
        "Rule #2I",
        R.string.firewall_rule_domain_trusted_univ,
        R.string.firewall_rule_domain_trusted_univ_desc,
        R.integer.allow
    ),
    RULE3(
        "Rule #3",
        R.string.firewall_rule_device_lock,
        R.string.firewall_rule_device_lock_desc,
        R.integer.stall
    ),
    RULE4(
        "Rule #4",
        R.string.firewall_rule_foreground,
        R.string.firewall_rule_foreground_desc,
        R.integer.block
    ),
    RULE5(
        "Rule #5",
        R.string.firewall_rule_unknown,
        R.string.firewall_rule_unknown_desc,
        R.integer.stall
    ),
    RULE6(
        "Rule #6",
        R.string.firewall_rule_block_udp_ntp,
        R.string.firewall_rule_block_udp_ntp_desc,
        R.integer.stall
    ),
    RULE7(
        "Rule #7",
        R.string.firewall_rule_block_dns_bypass,
        R.string.firewall_rule_block_dns_bypass_desc,
        R.integer.block
    ),
    RULE8(
        "Whitelist",
        R.string.firewall_rule_exempt_app_bypass_univ,
        R.string.firewall_rule_exempt_app_bypass_univ_desc,
        R.integer.allow
    ),
    RULE9(
        "Proxied",
        R.string.firewall_rule_exempt_dns_proxied,
        R.string.firewall_rule_exempt_dns_proxied_desc,
        R.integer.allow
    ),
    RULE9B(
        "Orbot setup",
        R.string.firewall_rule_exempt_orbot_setup,
        R.string.firewall_rule_exempt_orbot_setup_desc,
        R.integer.allow
    ),
    RULE10(
        "Http block",
        R.string.firewall_rule_block_http,
        R.string.firewall_rule_block_http_desc,
        R.integer.block
    ),
    RULE11(
        "Universal Lockdown",
        R.string.firewall_rule_global_lockdown,
        R.string.firewall_rule_global_lockdown_desc,
        R.integer.block
    ),
    RULE12(
        "Proxy",
        R.string.firewall_rule_proxied,
        R.string.firewall_rule_proxied_desc,
        R.integer.allow
    ),
    RULE13(
        "No route available",
        R.string.firewall_rule_block_app,
        R.string.firewall_rule_block_app_desc,
        R.integer.stall
    ),
    RULE14(
        "Private DNS",
        R.string.firewall_rule_private_dns,
        R.string.firewall_rule_private_dns_desc,
        R.integer.stall
    ),
    RULE15(
        "Bypass Proxy",
        R.string.firewall_rule_bypass_proxy,
        R.string.firewall_rule_bypass_proxy_desc,
        R.integer.allow
    ),
    RULE16(
        "App paused",
        R.string.firewall_rule_paused_app,
        R.string.firewall_rule_paused_app_desc,
        R.integer.allow
    ),
    RULE17(
        "Lockdown Proxy",
        R.string.firewall_rule_lockdown_wg,
        R.string.firewall_rule_lockdown_wg_desc,
        R.integer.stall
    ),
    RULE18(
        "Proxy Error",
        R.string.firewall_rule_proxy_error,
        R.string.firewall_rule_proxy_error_desc,
        R.integer.stall
    ),
    RULE19(
        "Temp Allow",
        R.string.firewall_rule_temp_allow,
        R.string.firewall_rule_temp_allow_desc ,
        R.integer.allow
    );

    companion object {
        fun getFirewallRule(ruleId: String): FirewallRuleset? {
            return when (ruleId) {
                RULE0.id -> RULE0
                RULE1.id -> RULE1
                RULE1B.id -> RULE1B
                RULE1C.id -> RULE1C
                RULE1D.id -> RULE1D
                RULE1E.id -> RULE1E
                RULE1F.id -> RULE1F
                RULE1G.id -> RULE1G
                RULE1H.id -> RULE1H
                RULE2.id -> RULE2
                RULE2B.id -> RULE2B
                RULE2C.id -> RULE2C
                RULE2D.id -> RULE2D
                RULE2E.id -> RULE2E
                RULE2F.id -> RULE2F
                RULE2G.id -> RULE2G
                RULE2H.id -> RULE2H
                RULE2I.id -> RULE2I
                RULE3.id -> RULE3
                RULE4.id -> RULE4
                RULE5.id -> RULE5
                RULE6.id -> RULE6
                RULE7.id -> RULE7
                RULE8.id -> RULE8
                RULE9.id -> RULE9
                RULE9B.id -> RULE9B
                RULE10.id -> RULE10
                RULE11.id -> RULE11
                RULE12.id -> RULE12
                RULE13.id -> RULE13
                RULE14.id -> RULE14
                RULE15.id -> RULE15
                RULE16.id -> RULE16
                RULE17.id -> RULE17
                RULE18.id -> RULE18
                RULE19.id -> RULE19
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
                RULE1G.id -> R.drawable.ic_firewall_lockdown_off
                RULE1H.id -> R.drawable.ic_bypass_dns_firewall_off
                RULE2.id -> R.drawable.ic_firewall_block_grey
                RULE2B.id -> R.drawable.ic_bypass
                RULE2C.id -> R.drawable.ic_bypass
                RULE2D.id -> R.drawable.ic_bypass
                RULE2E.id -> R.drawable.bs_dns_home_screen
                RULE2F.id -> R.drawable.bs_dns_home_screen
                RULE2G.id -> R.drawable.bs_dns_home_screen
                RULE2H.id -> R.drawable.bs_dns_home_screen
                RULE2I.id -> R.drawable.bs_dns_home_screen
                RULE3.id -> R.drawable.ic_device_lock
                RULE4.id -> R.drawable.ic_foreground
                RULE5.id -> R.drawable.ic_unknown_app
                RULE6.id -> R.drawable.ic_udp
                RULE7.id -> R.drawable.ic_prevent_dns_leaks
                RULE8.id -> R.drawable.bs_firewall_home_screen
                RULE9.id -> R.drawable.bs_dns_home_screen
                RULE9B.id -> R.drawable.ic_orbot
                RULE10.id -> R.drawable.ic_http
                RULE11.id -> R.drawable.ic_global_lockdown
                RULE12.id -> R.drawable.ic_proxy_white
                RULE13.id -> R.drawable.ic_proxy_white
                RULE14.id -> R.drawable.bs_dns_home_screen
                RULE15.id -> R.drawable.ic_bypass
                RULE16.id -> R.drawable.ic_proxy_white
                RULE17.id -> R.drawable.ic_proxy_white
                RULE18.id -> R.drawable.ic_filter_error
                else -> R.drawable.bs_dns_home_screen
            }
        }

        fun getAllowedRules(): List<FirewallRuleset> {
            return entries.filter { it.act == R.integer.allow }
        }

        fun getBlockedRules(): List<FirewallRuleset> {
            return entries.filter { it.act != R.integer.allow }
        }

        fun ground(rule: FirewallRuleset): Boolean {
            return rule.act != R.integer.allow
        }

        fun isBypassRule(rule: FirewallRuleset): Boolean {
            return rule.id == RULE1H.id || rule.id == RULE2B.id || rule.id == RULE2C.id ||
                rule.id == RULE2F.id || rule.id == RULE2H.id || rule.id == RULE2I.id ||
                rule.id == RULE8.id || rule.id == RULE9B.id
        }

        fun isProxied(rule: FirewallRuleset): Boolean {
            return rule.id == RULE12.id
        }

        fun isProxyError(rule: String?): Boolean {
            if (rule == null) return false

            return rule == RULE18.id
        }

        fun shouldShowHint(rule: String?): Boolean {
            if (rule == null) return false

            return when (rule) {
                RULE1H.id -> true
                RULE2B.id -> true
                RULE2C.id -> true
                RULE2F.id -> true
                RULE2H.id -> true
                RULE2I.id -> true
                RULE7.id -> true
                RULE8.id -> true
                RULE9.id -> true
                RULE9B.id -> true
                RULE15.id -> true
                RULE19.id -> true
                else -> false
            }
        }
    }
}
