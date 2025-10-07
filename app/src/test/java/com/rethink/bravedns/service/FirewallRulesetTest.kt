package com.rethinkdns.retrixed.service

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals

/**
 * Test for FirewallRuleset enum to ensure proper rule IDs for newly installed apps.
 * This test verifies the fix for issue #2112 where the counter for "block newly installed apps by default"
 * was not increasing due to incorrect rule ID filtering.
 */
class FirewallRulesetTest {

    /**
     * Test that RULE1B and RULE8 have different IDs.
     * RULE1B should be used for blocking newly installed apps.
     * RULE8 is a whitelist rule and should not be used for counting blocks.
     */
    @Test
    fun testNewlyInstalledAppRuleIds() {
        // RULE1B is the actual blocking rule for newly installed apps
        assertEquals("Rule #1B", FirewallRuleset.RULE1B.id)
        
        // RULE8 is a whitelist rule, not for blocking newly installed apps
        assertEquals("Whitelist", FirewallRuleset.RULE8.id)
        
        // They should be different rules
        assertNotEquals(FirewallRuleset.RULE1B.id, FirewallRuleset.RULE8.id)
    }

    /**
     * Test that RULE1B is a blocking rule and RULE8 is an allow rule.
     * This confirms that RULE1B should be used for counting blocked newly installed apps.
     */
    @Test
    fun testRuleActions() {
        // RULE1B should be a blocking rule (not an allow rule)
        assertEquals(com.rethinkdns.retrixed.R.integer.block, FirewallRuleset.RULE1B.act)
        
        // RULE8 should be an allow rule
        assertEquals(com.rethinkdns.retrixed.R.integer.allow, FirewallRuleset.RULE8.act)
        
        // Verify RULE1B is indeed a grounding (blocking) rule
        assertEquals(true, FirewallRuleset.ground(FirewallRuleset.RULE1B))
        
        // Verify RULE8 is not a grounding (blocking) rule
        assertEquals(false, FirewallRuleset.ground(FirewallRuleset.RULE8))
    }

    /**
     * Test to document the expected behavior for the fix to issue #2112.
     * When new apps are blocked, the connection should be tagged with RULE1B.id,
     * and the UI should filter by RULE1B.id to count these blocks.
     */
    @Test
    fun testNewAppBlockingRuleDocumentation() {
        // This test documents that RULE1B should be used for:
        // 1. Returning from BraveVPNService when blocking newly installed apps
        // 2. Filtering in UniversalFirewallSettingsActivity for counting blocks
        // 3. Click listener in UniversalFirewallSettingsActivity for viewing blocked connections
        
        val expectedBlockingRuleId = "Rule #1B"
        val expectedWhitelistRuleId = "Whitelist"
        
        assertEquals(expectedBlockingRuleId, FirewallRuleset.RULE1B.id)
        assertEquals(expectedWhitelistRuleId, FirewallRuleset.RULE8.id)
        
        // The fix ensures that the UI components use RULE1B.id instead of RULE8.id
        // for filtering and counting blocked newly installed app connections.
    }
}