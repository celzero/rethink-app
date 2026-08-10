/*
 * Copyright 2024 RethinkDNS and its authors
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
package com.celzero.bravedns.ui.fragment

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.celzero.bravedns.database.CustomDomain
import com.celzero.bravedns.database.CustomIp
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.shadows.ShadowBackend
import inet.ipaddr.IPAddress
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Comprehensive unit tests for [RulesImportHelper].
 *
 * Strategy
 * --------
 * Both [IpRulesManager] and [DomainRulesManager] are Kotlin `object` singletons whose
 * initializer blocks call native [com.celzero.firestack.backend.Backend] methods.
 * [ShadowBackend] intercepts those calls so the objects can be created without loading
 * the JNI library.  Once created, [mockkObject] wraps each singleton so that test code
 * controls all method responses.
 *
 * The file-I/O surface ([Context] / [ContentResolver]) is mocked with MockK, feeding
 * controlled byte streams to [RulesImportHelper.parseFile].
 *
 * Test groups
 * -----------
 * 1. parseFile — file access failures (null stream, exception)
 * 2. parseFile — file name resolution (cursor, URI path segment fallback)
 * 3. parseFile — IP import type: blank/comment skipping, valid/invalid entries
 * 4. parseFile — DOMAIN import type: blank/comment skipping, valid/invalid entries
 * 5. importRules — IP rules: insertion, duplicate skipping, status mapping
 * 6. importRules — DOMAIN rules: insertion, duplicate skipping, type detection, status mapping
 * 7. Data-class contracts (equality, toString)
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [ShadowBackend::class])
class RulesImportHelperTest {

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private val mockContext: Context = mockk(relaxed = true)
    private val mockContentResolver: ContentResolver = mockk(relaxed = true)

    /** A content URI whose last path segment is "rules.txt". */
    private val testUri: Uri = Uri.parse("content://com.test.provider/documents/rules.txt")

    /** UID_EVERYBODY (-1000) — global / universal rules. */
    private val globalUid = -1000

    /** A typical app UID for app-specific rule tests. */
    private val appUid = 10042

    // ── Setup ────────────────────────────────────────────────────────────────

    @Before
    fun setup() {
        // Wire context → content resolver
        every { mockContext.contentResolver } returns mockContentResolver
        // Default: no display-name cursor → resolveFileName falls back to URI lastPathSegment
        every { mockContentResolver.query(any(), any(), any(), any(), any()) } returns null

        // Mock the two service singletons so tests never touch native code or the database.
        mockkObject(IpRulesManager)
        mockkObject(DomainRulesManager)
    }

    @After
    fun tearDown() {
        // Restore original object behaviour between test classes
        unmockkAll()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Set up the ContentResolver to surface [content] as the file's bytes. */
    private fun givenFileContent(content: String) {
        every { mockContentResolver.openInputStream(testUri) } returns
            content.byteInputStream(Charsets.UTF_8)
    }

    /** Simulate a ContentResolver that returns null when asked to open the file. */
    private fun givenStreamIsNull() {
        every { mockContentResolver.openInputStream(testUri) } returns null
    }

    /** Simulate a ContentResolver that throws when asked to open the file. */
    private fun givenStreamThrows(ex: Exception) {
        every { mockContentResolver.openInputStream(testUri) } throws ex
    }

    /**
     * Configure [IpRulesManager.getIpNetPort] so that a line matching [validPattern] returns
     * a non-null [IPAddress] (simulating a valid IP entry), and all other input returns null.
     */
    private fun givenIpValidationFor(vararg validEntries: String) {
        val mockIp = mockk<IPAddress>(relaxed = true)
        // Return a real-looking IPAddress for valid entries
        every { IpRulesManager.getIpNetPort(match { it in validEntries }) } returns Pair(mockIp, 0)
        // Return null for everything else
        every { IpRulesManager.getIpNetPort(match { it !in validEntries }) } returns Pair(null, 0)
    }

    /**
     * Configure [DomainRulesManager] validators so that [validDomains] pass and everything
     * else fails.
     */
    private fun givenDomainValidationFor(vararg validDomains: String) {
        val valid = validDomains.toSet()
        every { DomainRulesManager.extractHost(match { it in valid }) } answers { firstArg() }
        every { DomainRulesManager.extractHost(match { it !in valid }) } returns null
        every { DomainRulesManager.isValidDomain(any()) } answers { firstArg<String>() in valid }
        every { DomainRulesManager.isWildCardEntry(any()) } answers { firstArg<String>() in valid }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 1. parseFile — file access failures
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `parseFile returns null when ContentResolver openInputStream returns null`() = runTest {
        givenStreamIsNull()
        val result = RulesImportHelper.parseFile(mockContext, testUri, RulesImportHelper.ImportType.IP)
        assertNull("Expected null when stream cannot be opened", result)
    }

    @Test
    fun `parseFile returns null on IOException from ContentResolver`() = runTest {
        givenStreamThrows(java.io.IOException("disk error"))
        val result = RulesImportHelper.parseFile(mockContext, testUri, RulesImportHelper.ImportType.IP)
        assertNull("Expected null on IOException", result)
    }

    @Test
    fun `parseFile returns null on SecurityException from ContentResolver`() = runTest {
        givenStreamThrows(SecurityException("no read permission"))
        val result = RulesImportHelper.parseFile(mockContext, testUri, RulesImportHelper.ImportType.IP)
        assertNull("Expected null on SecurityException", result)
    }

    @Test
    fun `parseFile returns null on generic RuntimeException from ContentResolver`() = runTest {
        givenStreamThrows(RuntimeException("unexpected error"))
        val result = RulesImportHelper.parseFile(mockContext, testUri, RulesImportHelper.ImportType.IP)
        assertNull("Expected null on RuntimeException", result)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 2. parseFile — file name resolution
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `parseFile uses URI lastPathSegment when ContentResolver query returns null`() = runTest {
        givenIpValidationFor()
        givenFileContent("")
        // Default setup: query returns null → fallback to lastPathSegment = "rules.txt"
        val result = RulesImportHelper.parseFile(mockContext, testUri, RulesImportHelper.ImportType.IP)!!
        assertEquals("rules.txt", result.fileName)
    }

    @Test
    fun `parseFile uses display name from ContentResolver cursor when available`() = runTest {
        givenIpValidationFor()
        givenFileContent("")
        val mockCursor = mockk<Cursor>(relaxed = true)
        every { mockContentResolver.query(testUri, null, null, null, null) } returns mockCursor
        every { mockCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME) } returns 0
        every { mockCursor.moveToFirst() } returns true
        every { mockCursor.getString(0) } returns "my-blocklist.txt"

        val result = RulesImportHelper.parseFile(mockContext, testUri, RulesImportHelper.ImportType.IP)!!
        assertEquals("my-blocklist.txt", result.fileName)
    }

    @Test
    fun `parseFile falls back gracefully when cursor has no display name column`() = runTest {
        givenIpValidationFor()
        givenFileContent("")
        val mockCursor = mockk<Cursor>(relaxed = true)
        every { mockContentResolver.query(testUri, null, null, null, null) } returns mockCursor
        every { mockCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME) } returns -1
        every { mockCursor.moveToFirst() } returns true

        val result = RulesImportHelper.parseFile(mockContext, testUri, RulesImportHelper.ImportType.IP)!!
        assertEquals("rules.txt", result.fileName)  // falls back to URI segment
    }

    @Test
    fun `parseFile falls back to URI segment when cursor query throws`() = runTest {
        givenIpValidationFor()
        givenFileContent("")
        every { mockContentResolver.query(testUri, null, null, null, null) } throws RuntimeException("query error")

        val result = RulesImportHelper.parseFile(mockContext, testUri, RulesImportHelper.ImportType.IP)!!
        assertEquals("rules.txt", result.fileName)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 3. parseFile — IP import type
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `parseFile IP type - empty file returns empty valid list and zero invalidCount`() = runTest {
        every { IpRulesManager.getIpNetPort(any()) } returns Pair(null, 0)
        givenFileContent("")
        val result = RulesImportHelper.parseFile(mockContext, testUri, RulesImportHelper.ImportType.IP)!!
        assertTrue(result.valid.isEmpty())
        assertEquals(0, result.invalidCount)
    }

    @Test
    fun `parseFile IP type - blank lines are skipped and not counted as invalid`() = runTest {
        givenIpValidationFor("1.1.1.1")
        givenFileContent("\n\n\n1.1.1.1\n\n")
        val result = RulesImportHelper.parseFile(mockContext, testUri, RulesImportHelper.ImportType.IP)!!
        assertEquals(1, result.valid.size)
        assertEquals(0, result.invalidCount)
    }

    @Test
    fun `parseFile IP type - comment lines starting with hash are skipped and not counted`() = runTest {
        givenIpValidationFor("8.8.8.8")
        givenFileContent("# Google DNS\n8.8.8.8\n# Another comment")
        val result = RulesImportHelper.parseFile(mockContext, testUri, RulesImportHelper.ImportType.IP)!!
        assertEquals(1, result.valid.size)
        assertEquals(0, result.invalidCount)
    }

    @Test
    fun `parseFile IP type - whitespace-only lines are skipped`() = runTest {
        givenIpValidationFor("1.1.1.1")
        givenFileContent("   \n\t\n1.1.1.1\n   ")
        val result = RulesImportHelper.parseFile(mockContext, testUri, RulesImportHelper.ImportType.IP)!!
        assertEquals(1, result.valid.size)
        assertEquals(0, result.invalidCount)
    }

    @Test
    fun `parseFile IP type - file with only comments returns empty valid list and zero invalid`() = runTest {
        every { IpRulesManager.getIpNetPort(any()) } returns Pair(null, 0)
        givenFileContent("# comment 1\n# comment 2\n# comment 3\n\n")
        val result = RulesImportHelper.parseFile(mockContext, testUri, RulesImportHelper.ImportType.IP)!!
        assertTrue(result.valid.isEmpty())
        assertEquals(0, result.invalidCount)
    }

    @Test
    fun `parseFile IP type - valid entry returned by getIpNetPort is included in valid list`() = runTest {
        givenIpValidationFor("192.168.1.1")
        givenFileContent("192.168.1.1")
        val result = RulesImportHelper.parseFile(mockContext, testUri, RulesImportHelper.ImportType.IP)!!
        assertEquals(1, result.valid.size)
        assertEquals("192.168.1.1", result.valid[0])
        assertEquals(0, result.invalidCount)
    }

    @Test
    fun `parseFile IP type - entry that getIpNetPort returns null for is counted as invalid`() = runTest {
        every { IpRulesManager.getIpNetPort("not-an-ip") } returns Pair(null, 0)
        givenFileContent("not-an-ip")
        val result = RulesImportHelper.parseFile(mockContext, testUri, RulesImportHelper.ImportType.IP)!!
        assertEquals(0, result.valid.size)
        assertEquals(1, result.invalidCount)
    }

    @Test
    fun `parseFile IP type - multiple valid entries are all included`() = runTest {
        givenIpValidationFor("1.1.1.1", "8.8.8.8", "9.9.9.9")
        givenFileContent("1.1.1.1\n8.8.8.8\n9.9.9.9")
        val result = RulesImportHelper.parseFile(mockContext, testUri, RulesImportHelper.ImportType.IP)!!
        assertEquals(3, result.valid.size)
        assertEquals(0, result.invalidCount)
    }

    @Test
    fun `parseFile IP type - mixed valid and invalid entries counted independently`() = runTest {
        givenIpValidationFor("1.1.1.1", "8.8.8.8", "192.168.0.0")
        givenFileContent(
            "# Office network\n" +
            "1.1.1.1\n" +
            "not-valid\n" +
            "8.8.8.8\n" +
            "also.not.valid\n" +
            "192.168.0.0\n"
        )
        val result = RulesImportHelper.parseFile(mockContext, testUri, RulesImportHelper.ImportType.IP)!!
        assertEquals(3, result.valid.size)
        assertEquals(2, result.invalidCount)
    }

    @Test
    fun `parseFile IP type - leading and trailing whitespace on a line is trimmed before validation`() = runTest {
        // getIpNetPort is called with the trimmed string, not the raw line
        every { IpRulesManager.getIpNetPort("1.1.1.1") } returns Pair(mockk(relaxed = true), 0)
        givenFileContent("  1.1.1.1  ")
        val result = RulesImportHelper.parseFile(mockContext, testUri, RulesImportHelper.ImportType.IP)!!
        assertEquals(1, result.valid.size)
        // raw trimmed string is preserved
        assertEquals("1.1.1.1", result.valid[0])
    }

    @Test
    fun `parseFile IP type - Windows CRLF line endings are handled correctly`() = runTest {
        givenIpValidationFor("1.1.1.1", "8.8.8.8")
        givenFileContent("1.1.1.1\r\n8.8.8.8\r\nnot-valid\r\n")
        val result = RulesImportHelper.parseFile(mockContext, testUri, RulesImportHelper.ImportType.IP)!!
        assertEquals(2, result.valid.size)
        assertEquals(1, result.invalidCount)
    }

    @Test
    fun `parseFile IP type - large file with many entries processes all of them`() = runTest {
        val entries = (1..500).map { "$it.$it.$it.$it" }
        val invalidEntries = (1..100).map { "bad-$it" }
        val allLines = (entries + invalidEntries).shuffled().joinToString("\n")
        givenIpValidationFor(*entries.toTypedArray())
        givenFileContent(allLines)
        val result = RulesImportHelper.parseFile(mockContext, testUri, RulesImportHelper.ImportType.IP)!!
        assertEquals(500, result.valid.size)
        assertEquals(100, result.invalidCount)
    }

    @Test
    fun `parseFile IP type - hash at start of line is comment, hash mid-line is NOT skipped`() = runTest {
        // Only lines starting with # are comments; "1.1.1.1#something" passes to getIpNetPort
        every { IpRulesManager.getIpNetPort("# comment") } returns Pair(null, 0)
        every { IpRulesManager.getIpNetPort("1.1.1.1#tag") } returns Pair(mockk(relaxed = true), 0)
        givenFileContent("# comment\n1.1.1.1#tag")
        val result = RulesImportHelper.parseFile(mockContext, testUri, RulesImportHelper.ImportType.IP)!!
        // "#comment" line is skipped; "1.1.1.1#tag" is passed to validation (valid here)
        assertEquals(1, result.valid.size)
        assertEquals(0, result.invalidCount)
    }

    // ── Port validation (IP type) ──────────────────────────────────────────

    @Test
    fun `parseFile IP type - entry with valid explicit port is accepted`() = runTest {
        val mockIp = mockk<IPAddress>(relaxed = true)
        every { IpRulesManager.getIpNetPort("1.2.3.4:8080") } returns Pair(mockIp, 8080)
        givenFileContent("1.2.3.4:8080")
        val result = RulesImportHelper.parseFile(mockContext, testUri, RulesImportHelper.ImportType.IP)!!
        assertEquals(1, result.valid.size)
        assertEquals("1.2.3.4:8080", result.valid[0])
        assertEquals(0, result.invalidCount)
    }

    @Test
    fun `parseFile IP type - entry with non-numeric explicit port is rejected`() = runTest {
        val mockIp = mockk<IPAddress>(relaxed = true)
        // getIpNetPort silently defaults non-numeric port to 0 (UNSPECIFIED_PORT)
        every { IpRulesManager.getIpNetPort("1.2.3.4:abc") } returns Pair(mockIp, 0)
        givenFileContent("1.2.3.4:abc")
        val result = RulesImportHelper.parseFile(mockContext, testUri, RulesImportHelper.ImportType.IP)!!
        assertTrue(result.valid.isEmpty())
        assertEquals(1, result.invalidCount)
    }

    @Test
    fun `parseFile IP type - entry with out-of-range explicit port is rejected`() = runTest {
        val mockIp = mockk<IPAddress>(relaxed = true)
        every { IpRulesManager.getIpNetPort("1.2.3.4:99999") } returns Pair(mockIp, 99999)
        givenFileContent("1.2.3.4:99999")
        val result = RulesImportHelper.parseFile(mockContext, testUri, RulesImportHelper.ImportType.IP)!!
        assertTrue(result.valid.isEmpty())
        assertEquals(1, result.invalidCount)
    }

    @Test
    fun `parseFile IP type - entry with explicit port zero is accepted`() = runTest {
        val mockIp = mockk<IPAddress>(relaxed = true)
        every { IpRulesManager.getIpNetPort("1.2.3.4:0") } returns Pair(mockIp, 0)
        givenFileContent("1.2.3.4:0")
        val result = RulesImportHelper.parseFile(mockContext, testUri, RulesImportHelper.ImportType.IP)!!
        assertEquals(1, result.valid.size)
        assertEquals("1.2.3.4:0", result.valid[0])
        assertEquals(0, result.invalidCount)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 4. parseFile — DOMAIN import type
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `parseFile DOMAIN type - empty file returns empty valid list and zero invalidCount`() = runTest {
        every { DomainRulesManager.extractHost(any()) } returns null
        givenFileContent("")
        val result = RulesImportHelper.parseFile(mockContext, testUri, RulesImportHelper.ImportType.DOMAIN)!!
        assertTrue(result.valid.isEmpty())
        assertEquals(0, result.invalidCount)
    }

    @Test
    fun `parseFile DOMAIN type - blank and comment lines are skipped and not counted`() = runTest {
        every { DomainRulesManager.extractHost("google.com") } returns "google.com"
        every { DomainRulesManager.isValidDomain("google.com") } returns true
        givenFileContent("# Blocklist\n\ngoogle.com\n\n# More")
        val result = RulesImportHelper.parseFile(mockContext, testUri, RulesImportHelper.ImportType.DOMAIN)!!
        assertEquals(1, result.valid.size)
        assertEquals(0, result.invalidCount)
    }

    @Test
    fun `parseFile DOMAIN type - valid domain accepted and stored as-extracted`() = runTest {
        every { DomainRulesManager.extractHost("google.com") } returns "google.com"
        every { DomainRulesManager.isValidDomain("google.com") } returns true
        givenFileContent("google.com")
        val result = RulesImportHelper.parseFile(mockContext, testUri, RulesImportHelper.ImportType.DOMAIN)!!
        assertEquals(1, result.valid.size)
        assertEquals("google.com", result.valid[0])
        assertEquals(0, result.invalidCount)
    }

    @Test
    fun `parseFile DOMAIN type - entry where extractHost returns null is counted as invalid`() = runTest {
        every { DomainRulesManager.extractHost("foo bar") } returns null
        givenFileContent("foo bar")
        val result = RulesImportHelper.parseFile(mockContext, testUri, RulesImportHelper.ImportType.DOMAIN)!!
        assertEquals(0, result.valid.size)
        assertEquals(1, result.invalidCount)
    }

    @Test
    fun `parseFile DOMAIN type - valid wildcard domain accepted`() = runTest {
        every { DomainRulesManager.extractHost("*.example.com") } returns "*.example.com"
        every { DomainRulesManager.isWildCardEntry("*.example.com") } returns true
        givenFileContent("*.example.com")
        val result = RulesImportHelper.parseFile(mockContext, testUri, RulesImportHelper.ImportType.DOMAIN)!!
        assertEquals(1, result.valid.size)
        assertEquals("*.example.com", result.valid[0])
    }

    @Test
    fun `parseFile DOMAIN type - URL with schema is normalised to host by extractHost`() = runTest {
        // extractHost strips the schema and www. prefix — simulate that behavior
        every { DomainRulesManager.extractHost("https://www.google.com") } returns "google.com"
        every { DomainRulesManager.isValidDomain("google.com") } returns true
        givenFileContent("https://www.google.com")
        val result = RulesImportHelper.parseFile(mockContext, testUri, RulesImportHelper.ImportType.DOMAIN)!!
        assertEquals(1, result.valid.size)
        assertEquals("google.com", result.valid[0])
    }

    @Test
    fun `parseFile DOMAIN type - entry where isValidDomain and isWildCardEntry both return false is invalid`() = runTest {
        every { DomainRulesManager.extractHost("notadomain!!") } returns "notadomain!!"
        every { DomainRulesManager.isValidDomain("notadomain!!") } returns false
        every { DomainRulesManager.isWildCardEntry("notadomain!!") } returns false
        givenFileContent("notadomain!!")
        val result = RulesImportHelper.parseFile(mockContext, testUri, RulesImportHelper.ImportType.DOMAIN)!!
        assertEquals(0, result.valid.size)
        assertEquals(1, result.invalidCount)
    }

    @Test
    fun `parseFile DOMAIN type - wildcard validated by isWildCardEntry not isValidDomain`() = runTest {
        every { DomainRulesManager.extractHost("*.ads.net") } returns "*.ads.net"
        every { DomainRulesManager.isWildCardEntry("*.ads.net") } returns true
        // isValidDomain might return false for wildcards — the helper uses isWildCardEntry first
        every { DomainRulesManager.isValidDomain("*.ads.net") } returns false
        givenFileContent("*.ads.net")
        val result = RulesImportHelper.parseFile(mockContext, testUri, RulesImportHelper.ImportType.DOMAIN)!!
        assertEquals(1, result.valid.size)
    }

    @Test
    fun `parseFile DOMAIN type - multiple valid domains are all accepted`() = runTest {
        listOf("google.com", "github.com", "reddit.com").forEach { domain ->
            every { DomainRulesManager.extractHost(domain) } returns domain
            every { DomainRulesManager.isValidDomain(domain) } returns true
        }
        givenFileContent("google.com\ngithub.com\nreddit.com")
        val result = RulesImportHelper.parseFile(mockContext, testUri, RulesImportHelper.ImportType.DOMAIN)!!
        assertEquals(3, result.valid.size)
        assertEquals(0, result.invalidCount)
    }

    @Test
    fun `parseFile DOMAIN type - mixed valid and invalid entries counted independently`() = runTest {
        listOf("google.com", "github.com", "*.example.com").forEach { domain ->
            every { DomainRulesManager.extractHost(domain) } returns domain
            every { DomainRulesManager.isValidDomain(domain) } returns !domain.startsWith("*")
            every { DomainRulesManager.isWildCardEntry(domain) } returns domain.startsWith("*")
        }
        every { DomainRulesManager.extractHost("not a domain") } returns null
        every { DomainRulesManager.extractHost("https://*.bad") } returns null
        givenFileContent(
            "# Blocklist\n" +
            "google.com\n" +
            "not a domain\n" +
            "github.com\n" +
            "https://*.bad\n" +
            "*.example.com\n"
        )
        val result = RulesImportHelper.parseFile(mockContext, testUri, RulesImportHelper.ImportType.DOMAIN)!!
        assertEquals(3, result.valid.size)
        assertEquals(2, result.invalidCount)
    }

    @Test
    fun `parseFile DOMAIN type - whitespace around domain is trimmed before validation`() = runTest {
        every { DomainRulesManager.extractHost("google.com") } returns "google.com"
        every { DomainRulesManager.isValidDomain("google.com") } returns true
        givenFileContent("  google.com  ")
        val result = RulesImportHelper.parseFile(mockContext, testUri, RulesImportHelper.ImportType.DOMAIN)!!
        assertEquals(1, result.valid.size)
        assertEquals("google.com", result.valid[0])
    }

    @Test
    fun `parseFile DOMAIN type - large file with many entries is fully processed`() = runTest {
        val validDomains = (1..400).map { "domain$it.com" }
        val invalidLines = (1..80).map { "bad entry $it" }
        validDomains.forEach { d ->
            every { DomainRulesManager.extractHost(d) } returns d
            every { DomainRulesManager.isValidDomain(d) } returns true
        }
        invalidLines.forEach { l -> every { DomainRulesManager.extractHost(l) } returns null }
        givenFileContent((validDomains + invalidLines).shuffled().joinToString("\n"))
        val result = RulesImportHelper.parseFile(mockContext, testUri, RulesImportHelper.ImportType.DOMAIN)!!
        assertEquals(400, result.valid.size)
        assertEquals(80, result.invalidCount)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 5. importRules — IP rules
    // ═════════════════════════════════════════════════════════════════════════

    /** Returns a fresh IPAddress mock for use as a stand-in for a real parsed IP. */
    private fun mockIp(): IPAddress = mockk(relaxed = true)

    /** Configures [IpRulesManager] so that [entry] maps to [ip] and [port] and is non-duplicate. */
    private fun givenNewIpEntry(entry: String, ip: IPAddress = mockIp(), port: Int = 0) {
        every { IpRulesManager.getIpNetPort(entry) } returns Pair(ip, port)
        coEvery { IpRulesManager.isIpRuleExists(any(), ip, port) } returns false
        coEvery { IpRulesManager.addIpRule(any(), ip, port, any(), any(), any()) } returns mockk(relaxed = true)
    }

    /** Configures [IpRulesManager] so that [entry] maps to [ip] / [port] but is already a duplicate. */
    private fun givenDuplicateIpEntry(entry: String, ip: IPAddress = mockIp(), port: Int = 0) {
        every { IpRulesManager.getIpNetPort(entry) } returns Pair(ip, port)
        coEvery { IpRulesManager.isIpRuleExists(any(), ip, port) } returns true
    }

    @Test
    fun `importRules IP type - inserts new rule with BLOCK status`() = runTest {
        val ip = mockIp()
        givenNewIpEntry("1.1.1.1", ip)

        val result = RulesImportHelper.importRules(
            entries = listOf("1.1.1.1"),
            importType = RulesImportHelper.ImportType.IP,
            uid = globalUid,
            ipStatus = IpRulesManager.IpRuleStatus.BLOCK
        )

        coVerify(exactly = 1) {
            IpRulesManager.addIpRule(globalUid, ip, 0, IpRulesManager.IpRuleStatus.BLOCK, "", "")
        }
        assertEquals(1, result.imported)
        assertEquals(0, result.duplicates)
        assertEquals(0, result.invalid)
    }

    @Test
    fun `importRules IP type - inserts new rule with TRUST status`() = runTest {
        val ip = mockIp()
        givenNewIpEntry("10.0.0.1", ip)

        val result = RulesImportHelper.importRules(
            entries = listOf("10.0.0.1"),
            importType = RulesImportHelper.ImportType.IP,
            uid = appUid,
            ipStatus = IpRulesManager.IpRuleStatus.TRUST
        )

        coVerify(exactly = 1) {
            IpRulesManager.addIpRule(appUid, ip, 0, IpRulesManager.IpRuleStatus.TRUST, "", "")
        }
        assertEquals(1, result.imported)
    }

    @Test
    fun `importRules IP type - inserts new rule with BYPASS_UNIVERSAL status`() = runTest {
        val ip = mockIp()
        givenNewIpEntry("8.8.8.8", ip)

        val result = RulesImportHelper.importRules(
            entries = listOf("8.8.8.8"),
            importType = RulesImportHelper.ImportType.IP,
            uid = globalUid,
            ipStatus = IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL
        )

        coVerify(exactly = 1) {
            IpRulesManager.addIpRule(globalUid, ip, 0, IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL, "", "")
        }
        assertEquals(1, result.imported)
    }

    @Test
    fun `importRules IP type - duplicate IP is skipped and counted as duplicate`() = runTest {
        val ip = mockIp()
        givenDuplicateIpEntry("1.1.1.1", ip)

        val result = RulesImportHelper.importRules(
            entries = listOf("1.1.1.1"),
            importType = RulesImportHelper.ImportType.IP,
            uid = globalUid
        )

        // addIpRule must NOT be called for a duplicate
        coVerify(exactly = 0) {
            IpRulesManager.addIpRule(any(), any(), any(), any(), any(), any())
        }
        assertEquals(0, result.imported)
        assertEquals(1, result.duplicates)
        assertEquals(0, result.invalid)
    }

    @Test
    fun `importRules IP type - null IPAddress from getIpNetPort is counted as invalid`() = runTest {
        // This exercises the guard path inside importRules
        every { IpRulesManager.getIpNetPort("bad-entry") } returns Pair(null, 0)

        val result = RulesImportHelper.importRules(
            entries = listOf("bad-entry"),
            importType = RulesImportHelper.ImportType.IP,
            uid = globalUid
        )

        coVerify(exactly = 0) {
            IpRulesManager.addIpRule(any(), any(), any(), any(), any(), any())
        }
        assertEquals(0, result.imported)
        assertEquals(0, result.duplicates)
        assertEquals(1, result.invalid)
    }

    @Test
    fun `importRules IP type - empty entries list returns all-zero ImportSummary`() = runTest {
        val result = RulesImportHelper.importRules(
            entries = emptyList(),
            importType = RulesImportHelper.ImportType.IP,
            uid = globalUid
        )

        coVerify(exactly = 0) { IpRulesManager.addIpRule(any(), any(), any(), any(), any(), any()) }
        assertEquals(RulesImportHelper.ImportSummary(0, 0, 0), result)
    }

    @Test
    fun `importRules IP type - port from getIpNetPort is forwarded to addIpRule`() = runTest {
        val ip = mockIp()
        every { IpRulesManager.getIpNetPort("1.2.3.4:8080") } returns Pair(ip, 8080)
        coEvery { IpRulesManager.isIpRuleExists(any(), ip, 8080) } returns false
        coEvery { IpRulesManager.addIpRule(any(), ip, 8080, any(), any(), any()) } returns mockk(relaxed = true)

        RulesImportHelper.importRules(
            entries = listOf("1.2.3.4:8080"),
            importType = RulesImportHelper.ImportType.IP,
            uid = globalUid,
            ipStatus = IpRulesManager.IpRuleStatus.BLOCK
        )

        coVerify(exactly = 1) {
            IpRulesManager.addIpRule(globalUid, ip, 8080, IpRulesManager.IpRuleStatus.BLOCK, "", "")
        }
    }

    @Test
    fun `importRules IP type - UID is forwarded to both isIpRuleExists and addIpRule`() = runTest {
        val ip = mockIp()
        every { IpRulesManager.getIpNetPort("5.5.5.5") } returns Pair(ip, 0)
        coEvery { IpRulesManager.isIpRuleExists(appUid, ip, 0) } returns false
        coEvery { IpRulesManager.addIpRule(appUid, ip, 0, any(), any(), any()) } returns mockk(relaxed = true)

        RulesImportHelper.importRules(
            entries = listOf("5.5.5.5"),
            importType = RulesImportHelper.ImportType.IP,
            uid = appUid
        )

        coVerify(exactly = 1) { IpRulesManager.isIpRuleExists(appUid, ip, 0) }
        coVerify(exactly = 1) { IpRulesManager.addIpRule(appUid, ip, 0, any(), any(), any()) }
    }

    @Test
    fun `importRules IP type - proxyId and proxyCC are always empty strings`() = runTest {
        val ip = mockIp()
        givenNewIpEntry("3.3.3.3", ip)

        RulesImportHelper.importRules(
            entries = listOf("3.3.3.3"),
            importType = RulesImportHelper.ImportType.IP,
            uid = globalUid
        )

        coVerify { IpRulesManager.addIpRule(any(), any(), any(), any(), proxyId = "", proxyCC = "") }
    }

    @Test
    fun `importRules IP type - mixed new, duplicate, and invalid entries produce correct counts`() = runTest {
        val ip1 = mockIp(); val ip2 = mockIp(); val ip3 = mockIp()
        givenNewIpEntry("1.1.1.1", ip1)
        givenDuplicateIpEntry("2.2.2.2", ip2)
        every { IpRulesManager.getIpNetPort("bad") } returns Pair(null, 0)
        givenNewIpEntry("3.3.3.3", ip3)

        val result = RulesImportHelper.importRules(
            entries = listOf("1.1.1.1", "2.2.2.2", "bad", "3.3.3.3"),
            importType = RulesImportHelper.ImportType.IP,
            uid = globalUid
        )

        assertEquals(2, result.imported)
        assertEquals(1, result.duplicates)
        assertEquals(1, result.invalid)
    }

    @Test
    fun `importRules IP type - all entries duplicate returns zero imported`() = runTest {
        val ips = (1..3).map { mockIp() }
        listOf("1.1.1.1", "2.2.2.2", "3.3.3.3").forEachIndexed { i, entry ->
            givenDuplicateIpEntry(entry, ips[i])
        }

        val result = RulesImportHelper.importRules(
            entries = listOf("1.1.1.1", "2.2.2.2", "3.3.3.3"),
            importType = RulesImportHelper.ImportType.IP,
            uid = globalUid
        )

        assertEquals(0, result.imported)
        assertEquals(3, result.duplicates)
        assertEquals(0, result.invalid)
    }

    @Test
    fun `importRules IP type - order of entries is processed sequentially`() = runTest {
        val calls = mutableListOf<String>()
        val ips = mapOf("1.1.1.1" to mockIp(), "2.2.2.2" to mockIp(), "3.3.3.3" to mockIp())
        ips.forEach { (entry, ip) ->
            every { IpRulesManager.getIpNetPort(entry) } returns Pair(ip, 0)
            coEvery { IpRulesManager.isIpRuleExists(any(), ip, 0) } returns false
            coEvery { IpRulesManager.addIpRule(any(), ip, 0, any(), any(), any()) } answers {
                calls.add(entry)
                mockk(relaxed = true)
            }
        }

        RulesImportHelper.importRules(
            entries = listOf("1.1.1.1", "2.2.2.2", "3.3.3.3"),
            importType = RulesImportHelper.ImportType.IP,
            uid = globalUid
        )

        assertEquals(listOf("1.1.1.1", "2.2.2.2", "3.3.3.3"), calls)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 6. importRules — DOMAIN rules
    // ═════════════════════════════════════════════════════════════════════════

    /** Returns a fake [CustomDomain] to simulate an existing database entry. */
    private fun fakeDomain(): CustomDomain = mockk(relaxed = true)

    @Test
    fun `importRules DOMAIN type - inserts new domain with BLOCK status and DOMAIN type`() = runTest {
        coEvery { DomainRulesManager.getObj(globalUid, "google.com") } returns null
        coEvery { DomainRulesManager.addDomainRule(any(), any(), any(), any()) } just Runs

        val result = RulesImportHelper.importRules(
            entries = listOf("google.com"),
            importType = RulesImportHelper.ImportType.DOMAIN,
            uid = globalUid,
            domainStatus = DomainRulesManager.Status.BLOCK
        )

        coVerify(exactly = 1) {
            DomainRulesManager.addDomainRule(
                "google.com",
                DomainRulesManager.Status.BLOCK,
                DomainRulesManager.DomainType.DOMAIN,
                globalUid
            )
        }
        assertEquals(1, result.imported)
        assertEquals(0, result.duplicates)
        assertEquals(0, result.invalid)
    }

    @Test
    fun `importRules DOMAIN type - inserts new domain with TRUST status`() = runTest {
        coEvery { DomainRulesManager.getObj(appUid, "github.com") } returns null
        coEvery { DomainRulesManager.addDomainRule(any(), any(), any(), any()) } just Runs

        val result = RulesImportHelper.importRules(
            entries = listOf("github.com"),
            importType = RulesImportHelper.ImportType.DOMAIN,
            uid = appUid,
            domainStatus = DomainRulesManager.Status.TRUST
        )

        coVerify(exactly = 1) {
            DomainRulesManager.addDomainRule(
                "github.com",
                DomainRulesManager.Status.TRUST,
                DomainRulesManager.DomainType.DOMAIN,
                appUid
            )
        }
        assertEquals(1, result.imported)
    }

    @Test
    fun `importRules DOMAIN type - duplicate domain is skipped and counted as duplicate`() = runTest {
        coEvery { DomainRulesManager.getObj(globalUid, "google.com") } returns fakeDomain()

        val result = RulesImportHelper.importRules(
            entries = listOf("google.com"),
            importType = RulesImportHelper.ImportType.DOMAIN,
            uid = globalUid
        )

        coVerify(exactly = 0) { DomainRulesManager.addDomainRule(any(), any(), any(), any()) }
        assertEquals(0, result.imported)
        assertEquals(1, result.duplicates)
        assertEquals(0, result.invalid)
    }

    @Test
    fun `importRules DOMAIN type - wildcard entry uses DomainType WILDCARD`() = runTest {
        coEvery { DomainRulesManager.getObj(globalUid, "*.example.com") } returns null
        coEvery { DomainRulesManager.addDomainRule(any(), any(), any(), any()) } just Runs

        RulesImportHelper.importRules(
            entries = listOf("*.example.com"),
            importType = RulesImportHelper.ImportType.DOMAIN,
            uid = globalUid,
            domainStatus = DomainRulesManager.Status.BLOCK
        )

        coVerify(exactly = 1) {
            DomainRulesManager.addDomainRule(
                "*.example.com",
                DomainRulesManager.Status.BLOCK,
                DomainRulesManager.DomainType.WILDCARD,  // ← key assertion
                globalUid
            )
        }
    }

    @Test
    fun `importRules DOMAIN type - plain domain uses DomainType DOMAIN`() = runTest {
        coEvery { DomainRulesManager.getObj(globalUid, "reddit.com") } returns null
        coEvery { DomainRulesManager.addDomainRule(any(), any(), any(), any()) } just Runs

        RulesImportHelper.importRules(
            entries = listOf("reddit.com"),
            importType = RulesImportHelper.ImportType.DOMAIN,
            uid = globalUid
        )

        coVerify(exactly = 1) {
            DomainRulesManager.addDomainRule(
                "reddit.com",
                any(),
                DomainRulesManager.DomainType.DOMAIN,  // ← key assertion
                globalUid
            )
        }
    }

    @Test
    fun `importRules DOMAIN type - wildcard detection uses startsWith dot-star not regex`() = runTest {
        // Entries that do NOT start with "*." must use DomainType.DOMAIN even if they look wildcard-ish
        coEvery { DomainRulesManager.getObj(any(), any()) } returns null
        coEvery { DomainRulesManager.addDomainRule(any(), any(), any(), any()) } just Runs

        RulesImportHelper.importRules(
            entries = listOf("prefix.*.example.com"),  // does NOT start with "*."
            importType = RulesImportHelper.ImportType.DOMAIN,
            uid = globalUid
        )

        coVerify(exactly = 1) {
            DomainRulesManager.addDomainRule(
                any(), any(),
                DomainRulesManager.DomainType.DOMAIN,  // DomainType.DOMAIN because no leading "*."
                any()
            )
        }
    }

    @Test
    fun `importRules DOMAIN type - empty entries list returns all-zero ImportSummary`() = runTest {
        val result = RulesImportHelper.importRules(
            entries = emptyList(),
            importType = RulesImportHelper.ImportType.DOMAIN,
            uid = globalUid
        )

        coVerify(exactly = 0) { DomainRulesManager.addDomainRule(any(), any(), any(), any()) }
        assertEquals(RulesImportHelper.ImportSummary(0, 0, 0), result)
    }

    @Test
    fun `importRules DOMAIN type - UID is forwarded to getObj and addDomainRule`() = runTest {
        coEvery { DomainRulesManager.getObj(appUid, "blocked.com") } returns null
        coEvery { DomainRulesManager.addDomainRule(any(), any(), any(), any()) } just Runs

        RulesImportHelper.importRules(
            entries = listOf("blocked.com"),
            importType = RulesImportHelper.ImportType.DOMAIN,
            uid = appUid
        )

        coVerify(exactly = 1) { DomainRulesManager.getObj(appUid, "blocked.com") }
        coVerify(exactly = 1) { DomainRulesManager.addDomainRule(any(), any(), any(), appUid) }
    }

    @Test
    fun `importRules DOMAIN type - mixed new, duplicate, wildcard entries produce correct counts`() = runTest {
        coEvery { DomainRulesManager.getObj(globalUid, "new.com") } returns null
        coEvery { DomainRulesManager.getObj(globalUid, "dupe.com") } returns fakeDomain()
        coEvery { DomainRulesManager.getObj(globalUid, "*.wildcard.net") } returns null
        coEvery { DomainRulesManager.addDomainRule(any(), any(), any(), any()) } just Runs

        val result = RulesImportHelper.importRules(
            entries = listOf("new.com", "dupe.com", "*.wildcard.net"),
            importType = RulesImportHelper.ImportType.DOMAIN,
            uid = globalUid
        )

        assertEquals(2, result.imported)    // new.com + *.wildcard.net
        assertEquals(1, result.duplicates)  // dupe.com
        assertEquals(0, result.invalid)
    }

    @Test
    fun `importRules DOMAIN type - all duplicates returns zero imported`() = runTest {
        listOf("a.com", "b.com", "c.com").forEach { d ->
            coEvery { DomainRulesManager.getObj(globalUid, d) } returns fakeDomain()
        }

        val result = RulesImportHelper.importRules(
            entries = listOf("a.com", "b.com", "c.com"),
            importType = RulesImportHelper.ImportType.DOMAIN,
            uid = globalUid
        )

        assertEquals(0, result.imported)
        assertEquals(3, result.duplicates)
        assertEquals(0, result.invalid)
    }

    @Test
    fun `importRules DOMAIN type - default domainStatus is BLOCK when not specified`() = runTest {
        coEvery { DomainRulesManager.getObj(any(), any()) } returns null
        coEvery { DomainRulesManager.addDomainRule(any(), any(), any(), any()) } just Runs

        // Call with only the required parameters — domainStatus defaults to BLOCK
        RulesImportHelper.importRules(
            entries = listOf("default-block.com"),
            importType = RulesImportHelper.ImportType.DOMAIN,
            uid = globalUid
        )

        coVerify(exactly = 1) {
            DomainRulesManager.addDomainRule(any(), DomainRulesManager.Status.BLOCK, any(), any())
        }
    }

    @Test
    fun `importRules IP type - default ipStatus is BLOCK when not specified`() = runTest {
        val ip = mockIp()
        givenNewIpEntry("1.2.3.4", ip)

        RulesImportHelper.importRules(
            entries = listOf("1.2.3.4"),
            importType = RulesImportHelper.ImportType.IP,
            uid = globalUid
        )

        coVerify(exactly = 1) {
            IpRulesManager.addIpRule(any(), any(), any(), IpRulesManager.IpRuleStatus.BLOCK, any(), any())
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 7. Data-class contracts
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `ParsedFile data class equality holds for identical instances`() {
        val a = RulesImportHelper.ParsedFile("file.txt", listOf("1.1.1.1", "8.8.8.8"), 3)
        val b = RulesImportHelper.ParsedFile("file.txt", listOf("1.1.1.1", "8.8.8.8"), 3)
        assertEquals(a, b)
    }

    @Test
    fun `ParsedFile instances with different valid lists are not equal`() {
        val a = RulesImportHelper.ParsedFile("f.txt", listOf("1.1.1.1"), 0)
        val b = RulesImportHelper.ParsedFile("f.txt", listOf("2.2.2.2"), 0)
        assertTrue(a != b)
    }

    @Test
    fun `ImportSummary data class equality holds for identical instances`() {
        val a = RulesImportHelper.ImportSummary(imported = 10, duplicates = 3, invalid = 1)
        val b = RulesImportHelper.ImportSummary(imported = 10, duplicates = 3, invalid = 1)
        assertEquals(a, b)
    }

    @Test
    fun `ImportSummary all-zero equals ImportSummary(0, 0, 0)`() {
        val result = RulesImportHelper.ImportSummary(0, 0, 0)
        assertEquals(RulesImportHelper.ImportSummary(0, 0, 0), result)
    }

    @Test
    fun `ImportSummary total is sum of imported plus duplicates plus invalid`() {
        val s = RulesImportHelper.ImportSummary(imported = 8, duplicates = 3, invalid = 2)
        assertEquals(13, s.imported + s.duplicates + s.invalid)
    }

    @Test
    fun `ImportType enum contains exactly IP and DOMAIN`() {
        val types = RulesImportHelper.ImportType.entries
        assertEquals(2, types.size)
        assertTrue(types.contains(RulesImportHelper.ImportType.IP))
        assertTrue(types.contains(RulesImportHelper.ImportType.DOMAIN))
    }

    @Test
    fun `ParsedFile has correct valid list size after construction`() {
        val pf = RulesImportHelper.ParsedFile("test.txt", listOf("a", "b", "c"), 5)
        assertEquals(3, pf.valid.size)
        assertEquals(5, pf.invalidCount)
        assertEquals("test.txt", pf.fileName)
    }

    @Test
    fun `ParsedFile valid list is immutable after construction`() {
        val source = mutableListOf("1.1.1.1")
        val pf = RulesImportHelper.ParsedFile("f.txt", source.toList(), 0)
        source.add("mutated")
        assertEquals(1, pf.valid.size)  // internal list was not mutated
    }
}
