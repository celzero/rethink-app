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

enum class FirewallRuleset(val ruleName: String) {
    RULE1("Rule #1"),
    RULE2("Rule #2"),
    RULE3("Rule #3"),
    RULE4("Rule #4"),
    RULE5("Rule #5"),
    RULE6("Rule #6"),

    // FIXME: #298 - Fix the rule7 - find a way out for the whitelist.
    RULE7("Whitelist")
}
