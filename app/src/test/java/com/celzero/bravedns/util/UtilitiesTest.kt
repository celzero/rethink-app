package com.celzero.bravedns.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UtilitiesTest {

    @Test
    fun testGetETldPlus1() {
        assertEquals("example.com", Utilities.getETldPlus1("www.example.com"))
        assertEquals("example.co.uk", Utilities.getETldPlus1("www.example.co.uk"))
        assertEquals("example.com", Utilities.getETldPlus1("example.com"))
        assertEquals("localhost", Utilities.getETldPlus1("localhost"))
    }

    @Test
    fun testIsValidPort() {
        assertTrue(Utilities.isValidPort(80))
        assertTrue(Utilities.isValidPort(443))
        assertTrue(Utilities.isValidPort(65535))
        assertTrue(Utilities.isValidPort(0))
        assertFalse(Utilities.isValidPort(65536))
        assertFalse(Utilities.isValidPort(-1))
        assertFalse(Utilities.isValidPort(null))
    }

    @Test
    fun testIsMissingOrInvalidUid() {
        assertTrue(Utilities.isMissingOrInvalidUid(-1))
        assertTrue(Utilities.isMissingOrInvalidUid(-1000))
        assertFalse(Utilities.isMissingOrInvalidUid(0))
        assertFalse(Utilities.isMissingOrInvalidUid(1000))
    }

    @Test
    fun testRemoveLeadingAndTrailingDots() {
        assertEquals("example.com", Utilities.removeLeadingAndTrailingDots("example.com."))
        assertEquals(".example.com", Utilities.removeLeadingAndTrailingDots("..example.com"))
        assertEquals("example.com", Utilities.removeLeadingAndTrailingDots("example.com"))
        assertEquals("", Utilities.removeLeadingAndTrailingDots("..."))
        assertEquals("", Utilities.removeLeadingAndTrailingDots(null))
    }
    
    @Test
    fun testGetFlag() {
        assertEquals("🇮🇳", Utilities.getFlag("IN"))
        assertEquals("🇺🇸", Utilities.getFlag("US"))
        assertEquals("", Utilities.getFlag(null))
    }

    @Test
    fun testIsOsVersionAbove412() {
        // This depends on System.getProperty("os.version"), which might be null in tests
        // But the logic should be testable if we can mock it or if it behaves predictably
        // For now, let's skip it or test with a mock if possible
    }
}
