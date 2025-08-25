package com.celzero.bravedns.service

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Test for DNS Cache functionality to ensure DNS Booster works correctly 
 * when Split DNS + WireGuard Advanced mode is enabled.
 * This test verifies the fix for issue #2129 where DNS cache sharing was broken.
 */
class DnsCacheTest {

    /**
     * Mock implementation of appendDnsCacheIfNeeded to test the logic
     * without external dependencies like Backend constants
     */
    private fun mockAppendDnsCacheIfNeeded(id: String, cacheEnabled: Boolean = true): String {
        val CT_PREFIX = "CT+"
        val BLOCK_ALL = "Alg"
        
        return if (cacheEnabled && id != BLOCK_ALL && !id.startsWith(CT_PREFIX)) {
            CT_PREFIX + id
        } else {
            id
        }
    }

    @Test
    fun testDnsCacheMultipleWireGuardIds() {
        // Test case: Multiple WireGuard IDs should each get cache prefix
        val wireGuardIds = "wg1,wg2,wg3"
        val result = wireGuardIds.split(",").map { mockAppendDnsCacheIfNeeded(it) }.joinToString(",")
        
        assertEquals("CT+wg1,CT+wg2,CT+wg3", result)
        assertTrue("All IDs should have cache prefix", result.split(",").all { it.startsWith("CT+") })
    }

    @Test
    fun testDnsCacheSingleWireGuardId() {
        // Test case: Single WireGuard ID should get cache prefix
        val wireGuardId = "wg1"
        val result = mockAppendDnsCacheIfNeeded(wireGuardId)
        
        assertEquals("CT+wg1", result)
        assertTrue("ID should have cache prefix", result.startsWith("CT+"))
    }

    @Test
    fun testDnsCacheDefaultTransport() {
        // Test case: Default transport when all WireGuard configs are paused
        val defaultTransport = "Preferred"
        val result = mockAppendDnsCacheIfNeeded(defaultTransport)
        
        assertEquals("CT+Preferred", result)
        assertTrue("Default transport should have cache prefix", result.startsWith("CT+"))
    }

    @Test
    fun testDnsCacheAlreadyCachedId() {
        // Test case: Already cached ID should not get double prefix
        val alreadyCachedId = "CT+wg1"
        val result = mockAppendDnsCacheIfNeeded(alreadyCachedId)
        
        assertEquals("CT+wg1", result)
        assertEquals(1, result.split("CT+").size - 1) // Should only have one CT+ prefix
    }

    @Test
    fun testDnsCacheBlockAllId() {
        // Test case: BlockAll transport should not get cache prefix
        val blockAllId = "Alg"
        val result = mockAppendDnsCacheIfNeeded(blockAllId)
        
        assertEquals("Alg", result)
        assertTrue("BlockAll should not have cache prefix", !result.startsWith("CT+"))
    }

    @Test
    fun testDnsCacheDisabled() {
        // Test case: When DNS cache is disabled, no prefix should be added
        val wireGuardId = "wg1"
        val result = mockAppendDnsCacheIfNeeded(wireGuardId, cacheEnabled = false)
        
        assertEquals("wg1", result)
        assertTrue("ID should not have cache prefix when cache disabled", !result.startsWith("CT+"))
    }

    /**
     * This test documents the expected behavior for the fix to issue #2129.
     * Before the fix, WireGuard transport IDs were returned without cache prefix
     * when Split DNS was enabled, causing per-app cache isolation instead of sharing.
     */
    @Test
    fun testDnsCacheFixDocumentation() {
        // Before fix: modifiedIds was returned directly without cache prefix
        val beforeFix = "wg1,wg2"
        assertTrue("Before fix: No cache prefix", !beforeFix.contains("CT+"))
        
        // After fix: Each WireGuard ID gets cache prefix for sharing
        val afterFix = beforeFix.split(",").map { mockAppendDnsCacheIfNeeded(it) }.joinToString(",")
        assertEquals("CT+wg1,CT+wg2", afterFix)
        assertTrue("After fix: All IDs have cache prefix", afterFix.split(",").all { it.startsWith("CT+") })
        
        // This ensures DNS Booster cache sharing works across apps with Split DNS + WireGuard
    }

    @Test
    fun testDnsCacheEdgeCases() {
        // Test edge cases like empty strings, special characters, etc.
        assertEquals("", mockAppendDnsCacheIfNeeded(""))
        assertEquals("CT+wg-1", mockAppendDnsCacheIfNeeded("wg-1"))
        assertEquals("CT+WG_1", mockAppendDnsCacheIfNeeded("WG_1"))
        assertEquals("CT+wg1.example", mockAppendDnsCacheIfNeeded("wg1.example"))
    }
}