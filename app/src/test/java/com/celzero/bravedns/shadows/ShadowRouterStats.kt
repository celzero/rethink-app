/*
 * Copyright 2025 RethinkDNS and its authors
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
package com.celzero.bravedns.shadows

import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Shadow class for RouterStats to prevent native library loading during tests.
 * This completely replaces the real RouterStats class in the test environment.
 *
 * Based on the WgConfigAdapter usage, RouterStats has the following properties:
 * - rx: Long (received bytes)
 * - tx: Long (transmitted bytes)
 * - since: Long (timestamp when connection started)
 * - lastOK: Long (timestamp of last successful operation)
 */
@Implements(className = "com.celzero.firestack.backend.RouterStats")
class ShadowRouterStats {

    // Mock data storage
    private var rxBytes: Long = 0L
    private var txBytes: Long = 0L
    private var sinceTimestamp: Long = System.currentTimeMillis()
    private var lastOKTimestamp: Long = System.currentTimeMillis()

    @Implementation
    fun __constructor__() {
        // Mock constructor that does nothing - prevents native library loading
        // Initialize with default test values
        this.rxBytes = 1024L
        this.txBytes = 2048L
        this.sinceTimestamp = System.currentTimeMillis() - 10000L // 10 seconds ago
        this.lastOKTimestamp = System.currentTimeMillis() - 5000L // 5 seconds ago
    }

    @Implementation
    fun __constructor__(rx: Long, tx: Long, since: Long, lastOK: Long) {
        // Mock constructor with parameters
        this.rxBytes = rx
        this.txBytes = tx
        this.sinceTimestamp = since
        this.lastOKTimestamp = lastOK
    }

    @Implementation
    fun getRx(): Long = rxBytes

    @Implementation
    fun getTx(): Long = txBytes

    @Implementation
    fun getSince(): Long = sinceTimestamp

    @Implementation
    fun getLastOK(): Long = lastOKTimestamp

    // Property accessors that match Kotlin property syntax
    @Implementation
    fun setRx(value: Long) {
        this.rxBytes = value
    }

    @Implementation
    fun setTx(value: Long) {
        this.txBytes = value
    }

    @Implementation
    fun setSince(value: Long) {
        this.sinceTimestamp = value
    }

    @Implementation
    fun setLastOK(value: Long) {
        this.lastOKTimestamp = value
    }

    // Additional methods that might be used
    @Implementation
    fun getRxBytes(): Long = rxBytes

    @Implementation
    fun getTxBytes(): Long = txBytes

    @Implementation
    fun getUptime(): Long = System.currentTimeMillis() - sinceTimestamp

    @Implementation
    fun getLastHandshake(): Long = lastOKTimestamp

    @Implementation
    fun isActive(): Boolean = lastOKTimestamp > 0L

    @Implementation
    fun reset() {
        rxBytes = 0L
        txBytes = 0L
        sinceTimestamp = System.currentTimeMillis()
        lastOKTimestamp = 0L
    }

    @Implementation
    override fun toString(): String {
        return "ShadowRouterStats(rx=$rxBytes, tx=$txBytes, since=$sinceTimestamp, lastOK=$lastOKTimestamp)"
    }

    @Implementation
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false

        val that = other as ShadowRouterStats
        return rxBytes == that.rxBytes &&
                txBytes == that.txBytes &&
                sinceTimestamp == that.sinceTimestamp &&
                lastOKTimestamp == that.lastOKTimestamp
    }

    @Implementation
    override fun hashCode(): Int {
        var result = rxBytes.hashCode()
        result = 31 * result + txBytes.hashCode()
        result = 31 * result + sinceTimestamp.hashCode()
        result = 31 * result + lastOKTimestamp.hashCode()
        return result
    }

    companion object {
        // Helper methods for creating test instances
        fun createTestStats(
            rx: Long = 1024L,
            tx: Long = 2048L,
            since: Long = System.currentTimeMillis() - 10000L,
            lastOK: Long = System.currentTimeMillis() - 5000L
        ): ShadowRouterStats {
            val stats = ShadowRouterStats()
            stats.rxBytes = rx
            stats.txBytes = tx
            stats.sinceTimestamp = since
            stats.lastOKTimestamp = lastOK
            return stats
        }

        fun createActiveStats(): ShadowRouterStats {
            return createTestStats(
                rx = 5120L,
                tx = 3072L,
                since = System.currentTimeMillis() - 30000L, // 30 seconds ago
                lastOK = System.currentTimeMillis() - 1000L   // 1 second ago
            )
        }

        fun createInactiveStats(): ShadowRouterStats {
            return createTestStats(
                rx = 0L,
                tx = 0L,
                since = System.currentTimeMillis() - 60000L, // 1 minute ago
                lastOK = 0L // Never connected
            )
        }

        fun createStaleStats(): ShadowRouterStats {
            return createTestStats(
                rx = 2048L,
                tx = 1024L,
                since = System.currentTimeMillis() - 300000L, // 5 minutes ago
                lastOK = System.currentTimeMillis() - 120000L // 2 minutes ago
            )
        }
    }
}
