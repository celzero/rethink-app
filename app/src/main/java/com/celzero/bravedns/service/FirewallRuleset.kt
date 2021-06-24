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
