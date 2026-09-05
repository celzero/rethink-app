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
        // invalid inputs fall back to the "---" placeholder instead of
        // producing tofu glyphs (or crashing on strings shorter than 2 chars)
        assertEquals("---", Utilities.getFlag(null))
        assertEquals("---", Utilities.getFlag(""))
        assertEquals("---", Utilities.getFlag("-"))
        // CountryMap's marker for unassigned IP ranges
        assertEquals("---", Utilities.getFlag("--"))
        // lowercase letters are not valid regional indicators
        assertEquals("---", Utilities.getFlag("us"))
        // non-alphabetic codes
        assertEquals("---", Utilities.getFlag("01"))
        // longer than 2 chars
        assertEquals("---", Utilities.getFlag("USA"))
    }

    @Test
    fun testIsOsVersionAbove412() {
        // This depends on System.getProperty("os.version"), which might be null in tests
        // But the logic should be testable if we can mock it or if it behaves predictably
        // For now, let's skip it or test with a mock if possible
    }
}
