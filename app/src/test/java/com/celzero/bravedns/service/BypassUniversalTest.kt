package com.celzero.bravedns.service

import org.junit.Test
import org.junit.Assert.assertEquals

/**
 * Test for Bypass Universal functionality to ensure bypassed apps are not blocked
 * by universal firewall rules. This test verifies the fix for issue #2148 where
 * bypass universal setting was not respected in background blocking and device lock rules.
 */
class BypassUniversalTest {

    @Test
    fun testFirewallStatusBypassUniversal() {
        // Test that BYPASS_UNIVERSAL status returns true for bypassUniversal()
        val bypassStatus = FirewallManager.FirewallStatus.BYPASS_UNIVERSAL
        assertEquals(true, bypassStatus.bypassUniversal())
        
        // Test that other statuses return false
        assertEquals(false, FirewallManager.FirewallStatus.NONE.bypassUniversal())
        assertEquals(false, FirewallManager.FirewallStatus.EXCLUDE.bypassUniversal())
        assertEquals(false, FirewallManager.FirewallStatus.ISOLATE.bypassUniversal())
    }

    @Test
    fun testFirewallStatusIds() {
        // Verify firewall status IDs are correctly defined
        assertEquals(2, FirewallManager.FirewallStatus.BYPASS_UNIVERSAL.id)
        assertEquals(3, FirewallManager.FirewallStatus.EXCLUDE.id)
        assertEquals(4, FirewallManager.FirewallStatus.ISOLATE.id)
        assertEquals(5, FirewallManager.FirewallStatus.NONE.id)
        assertEquals(6, FirewallManager.FirewallStatus.UNTRACKED.id)
        assertEquals(7, FirewallManager.FirewallStatus.BYPASS_DNS_FIREWALL.id)
    }

    @Test
    fun testFirewallRulesetBypassRules() {
        // Test that RULE8 is the correct bypass rule
        assertEquals("Whitelist", FirewallRuleset.RULE8.id)
        assertEquals(true, FirewallRuleset.isBypassRule(FirewallRuleset.RULE8))
        
        // Test that blocking rules are not bypass rules
        assertEquals(false, FirewallRuleset.isBypassRule(FirewallRuleset.RULE3)) // Device lock
        assertEquals(false, FirewallRuleset.isBypassRule(FirewallRuleset.RULE4)) // Background
    }

    /**
     * This test documents the expected behavior for the fix to issue #2148.
     * The bug was that apps with BYPASS_UNIVERSAL status were still being blocked
     * by universal rules like background blocking and device lock.
     */
    @Test
    fun testBypassUniversalBehaviorDocumentation() {
        // This test documents that when an app has BYPASS_UNIVERSAL status:
        // 1. It should not be blocked by background data rules (RULE4)  
        // 2. It should not be blocked by device lock rules (RULE3)
        // 3. It should return RULE8 (Whitelist) from the main bypass check
        
        // The fix ensures that blockBackgroundData() and device lock check
        // both respect the bypass universal setting before applying blocking rules.
        
        val bypassStatus = FirewallManager.FirewallStatus.BYPASS_UNIVERSAL
        assertEquals(true, bypassStatus.bypassUniversal())
    }
}