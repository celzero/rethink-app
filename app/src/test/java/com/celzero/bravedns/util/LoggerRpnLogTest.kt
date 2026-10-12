package com.celzero.bravedns.util

import com.celzero.bravedns.util.Logger.LoggerLevel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class LoggerRpnLogTest {

    @Test
    fun rpnLogsAtAllLevelsAreRoutedToRpnFactory() {
        withUiLogLevel(LoggerLevel.VERY_VERBOSE.id) {
            val info = Logger.makeGoRpnLog("wgrpn: tunnel started", LoggerLevel.INFO, true)
            val debug = Logger.makeGoRpnLog("rpn: tunnel ready", LoggerLevel.DEBUG, true)
            val error = Logger.makeGoRpnLog("wgrpn: failed", LoggerLevel.ERROR, true)

            assertNotNull(info)
            assertNotNull(debug)
            assertNotNull(error)
        }
    }

    @Test
    fun rpnLogsRespectSelectedSeverity() {
        withUiLogLevel(LoggerLevel.ERROR.id) {
            assertNull(Logger.makeGoRpnLog("wgrpn: tunnel started", LoggerLevel.INFO, true))
            assertNull(Logger.makeGoRpnLog("rpn: tunnel ready", LoggerLevel.DEBUG, true))
            assertNotNull(Logger.makeGoRpnLog("wgrpn: failed", LoggerLevel.ERROR, true))
        }
    }

    @Test
    fun rpnLogsAreExcludedFromConsoleFactory() {
        withUiLogLevel(LoggerLevel.VERY_VERBOSE.id) {
            assertNull(Logger.makeGoConsoleLog("wgrpn: tunnel started", LoggerLevel.INFO, true))
            assertNull(Logger.makeGoConsoleLog("ordinary Go log", LoggerLevel.ERROR, true))
        }
    }

    @Test
    fun nonRpnLogsAreNotRoutedToRpnFactory() {
        withUiLogLevel(LoggerLevel.VERY_VERBOSE.id) {
            assertNull(Logger.makeGoRpnLog("ordinary Go log", LoggerLevel.INFO, false))
        }
    }

    @Test
    fun nonRpnLogsRespectSelectedSeverityInConsoleFactory() {
        withUiLogLevel(LoggerLevel.ERROR.id) {
            assertNull(Logger.makeGoConsoleLog("ordinary Go log", LoggerLevel.INFO, false))
            assertNotNull(Logger.makeGoConsoleLog("ordinary Go log", LoggerLevel.ERROR, false))
        }
    }

    @Test
    fun routedRowsCarryLevelCharPrefixAndTimestamp() {
        withUiLogLevel(LoggerLevel.VERY_VERBOSE.id) {
            val rpn = Logger.makeGoRpnLog("wgrpn: tunnel started", LoggerLevel.INFO, true)!!
            val console = Logger.makeGoConsoleLog("ordinary Go log", LoggerLevel.WARN, false)!!

            assertTrue(rpn.message.startsWith("I ${Logger.LOG_GO_LOGGER}: "))
            assertTrue(rpn.level == LoggerLevel.INFO.id)
            assertTrue(rpn.timestamp > 0)

            assertFalse(console.message.startsWith("I "))
            assertTrue(console.message.startsWith("W ${Logger.LOG_GO_LOGGER}: "))
            assertTrue(console.timestamp > 0)
        }
    }

    @Test
    fun iabAndProxyTagsAreRpnLogs() {
        assertTrue(Logger.isRpnLog(Logger.LOG_IAB, "revoke failed"))
        assertTrue(Logger.isRpnLog(Logger.LOG_TAG_PROXY, "wgrpn: tunnel started"))
    }

    @Test
    fun rpnInTagIsRpnLog() {
        assertTrue(Logger.isRpnLog("RpnProxy", "ordinary message"))
        assertTrue(Logger.isRpnLog("wgrpn", "ordinary message"))
    }

    @Test
    fun rpnInMessageIsRpnLogOnlyForGoLogTags() {
        assertTrue(Logger.isRpnLog(Logger.LOG_GO_LOGGER, "wgrpn handshake failed"))
        assertTrue(Logger.isRpnLog(Logger.LOG_GO_LOGGER_V1, "rpn ready"))
        assertTrue(Logger.isRpnLog(Logger.LOG_GO_LOGGER_V2, "RPN dns request dropped"))

        // non-Go tags route by tag alone; the message is never scanned
        assertFalse(Logger.isRpnLog(Logger.LOG_TAG_VPN, "wgrpn handshake failed"))
        // "yegor" is no longer a needle
        assertFalse(Logger.isRpnLog(Logger.LOG_GO_LOGGER, "yegor: ready"))
        assertFalse(Logger.isRpnLog(Logger.LOG_TAG_DNS, "yegor: ready"))
    }

    @Test
    fun unrelatedTagAndMessageIsNotRpnLog() {
        assertFalse(Logger.isRpnLog(Logger.LOG_TAG_VPN, "tunnel established"))
        assertFalse(Logger.isRpnLog(Logger.LOG_TAG_DNS, "domain blocked"))
        assertFalse(Logger.isRpnLog(Logger.LOG_GO_LOGGER, "tunnel established"))
    }

    @Test
    fun payloadScanMatchesTextScanSemantics() {
        val cases = listOf(
            "wgrpn: tunnel started" to true,
            "rpn: tunnel ready" to true,
            "RPN dns request dropped" to true,
            "rRpN needle casing" to true,
            "needle at end rpn" to true,
            "yegor: tunnel ready" to false,
            "plain payload" to false,
            "r p n separated" to false,
            "rp" to false,
            "" to false
        )
        for ((payload, expected) in cases) {
            val bytes = payload.toByteArray(StandardCharsets.UTF_8)
            val result = Logger.isRpnPayload(bytes, 0, bytes.size)
            assertTrue("unexpected result for '$payload'", result == expected)
            assertTrue(
                "byte scan diverges from text scan for '$payload'",
                Logger.isRpnLog(Logger.LOG_GO_LOGGER, payload) == expected
            )
        }
    }

    @Test
    fun payloadScanRespectsRegionBounds() {
        val payload = "prefix\nrpn hidden in padding"
        val bytes = payload.toByteArray(StandardCharsets.UTF_8)
        val newlinePos = bytes.indexOf('\n'.code.toByte())

        // region limited to before the newline: the "rpn" after it must not match
        assertFalse(Logger.isRpnPayload(bytes, 0, newlinePos))
        // region including the newline: matches
        assertTrue(Logger.isRpnPayload(bytes, 0, bytes.size))
        // padded slot: garbage after the newline must never trigger a match
        val slot = ByteArray(800)
        val msg = "ordinary line\n".toByteArray(StandardCharsets.UTF_8)
        System.arraycopy(msg, 0, slot, 0, msg.size)
        System.arraycopy("rpn".toByteArray(StandardCharsets.UTF_8), 0, slot, msg.size, 3)
        assertFalse(Logger.isRpnPayload(slot, 0, msg.size - 1))
    }

    @Test
    fun payloadScanIgnoresMultibyteSequences() {
        // UTF-8 continuation bytes are >= 0x80 and must never ASCII-fold into needles
        val payload = "реизнос домогтеләр indeks"
        val bytes = payload.toByteArray(StandardCharsets.UTF_8)
        assertFalse(Logger.isRpnPayload(bytes, 0, bytes.size))
    }

    private inline fun withUiLogLevel(level: Long, block: () -> Unit) {
        val previous = Logger.uiLogLevel
        try {
            Logger.uiLogLevel = level
            block()
        } finally {
            Logger.uiLogLevel = previous
        }
    }
}
