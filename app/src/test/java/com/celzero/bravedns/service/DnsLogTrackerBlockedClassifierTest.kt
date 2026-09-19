/*
 * Copyright 2026 RethinkDNS and its authors
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

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.celzero.bravedns.util.Constants.Companion.UNSPECIFIED_IP_IPV4
import com.celzero.bravedns.util.Constants.Companion.UNSPECIFIED_IP_IPV6
import com.celzero.firestack.backend.Backend
import com.celzero.bravedns.net.doh.Transaction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * Tests for [DnsLogTracker.isBlockedDnsAnswer], the arrival-time classifier
 * used by TunDnsManager for caller-level activity aggregation. Expectations
 * mirror the isBlocked assignments made by DnsLogTracker.makeDnsLogObj so the
 * aggregate stays consistent with what gets persisted.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
class DnsLogTrackerBlockedClassifierTest {

    private fun classify(
        transportId: String = "",
        statusCode: Int = Transaction.Status.COMPLETE.id,
        response: String = "",
        qType: Long = 1L, // A
        blocklists: String = "",
        upstreamBlock: Boolean = false
    ): Boolean {
        return DnsLogTracker.isBlockedDnsAnswer(
            transportId,
            statusCode,
            response,
            qType,
            blocklists,
            upstreamBlock
        )
    }

    @Test
    fun `blockall transport id is blocked`() {
        assertTrue(classify(transportId = Backend.BlockAll, response = ""))
        assertTrue(classify(transportId = Backend.Block))
    }

    @Test
    fun `complete answer resolving a public ipv4 is allowed`() {
        assertFalse(classify(response = "1.2.3.4"))
        assertFalse(classify(response = "1.2.3.4,5.6.7.8"))
    }

    @Test
    fun `complete a record answering unspecified ipv4 is blocked`() {
        assertTrue(classify(response = "$UNSPECIFIED_IP_IPV4"))
    }

    @Test
    fun `complete aaaa record answering unspecified ipv6 mirrors persisted classification`() {
        // parity note: normalizeIp("::").hostAddress is "0:0:0:0:0:0:0:0", so the
        // literal "::" comparison never fires - identical to makeDnsLogObj, which
        // this classifier must mirror exactly
        assertFalse(classify(qType = 28L, response = "$UNSPECIFIED_IP_IPV6"))
    }

    @Test
    fun `empty a record response with matching blocklist is blocked`() {
        assertTrue(classify(response = "--", blocklists = "ads.example,"))
        assertTrue(classify(response = "--", upstreamBlock = true))
    }

    @Test
    fun `empty a record response without blocks is allowed`() {
        assertFalse(classify(response = "--"))
    }

    @Test
    fun `complete valid ip overrides an earlier blockall marker`() {
        // documents makeDnsLogObj precedence: the destination check overwrites
        // the BlockAll marker when a real address is present
        assertFalse(classify(transportId = Backend.BlockAll, response = "1.2.3.4"))
    }

    @Test
    fun `blockall transport id is blocked regardless of status`() {
        // makeDnsLogObj marks BlockAll/Block before any status check
        assertTrue(
            classify(
                transportId = Backend.BlockAll,
                statusCode = Transaction.Status.SEND_FAIL.id
            )
        )
    }

    @Test
    fun `non-complete status is never classified as blocked by response heuristics`() {
        assertFalse(
            classify(
                statusCode = Transaction.Status.START.id,
                response = "--",
                blocklists = "ads.example,",
                upstreamBlock = true
            )
        )
    }

    @Test
    fun `non-ip record types are never blocked by response heuristics`() {
        assertFalse(classify(qType = 6L, response = "--", blocklists = "ads.example,")) // SOA
        assertFalse(classify(qType = 16L)) // TXT
    }
}
