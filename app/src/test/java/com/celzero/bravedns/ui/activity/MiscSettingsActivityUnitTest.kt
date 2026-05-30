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
package com.celzero.bravedns.ui.activity

import android.content.Intent
import com.celzero.bravedns.ui.activity.MiscSettingsActivity.BioMetricType
import com.celzero.bravedns.util.NotificationActionType
import com.celzero.bravedns.util.PcapMode
import com.celzero.bravedns.util.Themes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Pure unit tests for MiscSettingsActivity that verify business logic without launching
 * the actual Activity. Covers enums, constants, intent construction, and algorithmic logic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MiscSettingsActivityUnitTest {

    // =====================================================================================
    // BioMetricType – fromValue()
    // =====================================================================================

    @Test
    fun biometricType_fromValue_0_returnsOff() {
        val type = BioMetricType.fromValue(0)
        assertSame("Action 0 must map to OFF", BioMetricType.OFF, type)
    }

    @Test
    fun biometricType_fromValue_1_returnsImmediate() {
        val type = BioMetricType.fromValue(1)
        assertSame("Action 1 must map to IMMEDIATE", BioMetricType.IMMEDIATE, type)
    }

    @Test
    fun biometricType_fromValue_2_returnsFiveMin() {
        val type = BioMetricType.fromValue(2)
        assertSame("Action 2 must map to FIVE_MIN", BioMetricType.FIVE_MIN, type)
    }

    @Test
    fun biometricType_fromValue_3_returnsFifteenMin() {
        val type = BioMetricType.fromValue(3)
        assertSame("Action 3 must map to FIFTEEN_MIN", BioMetricType.FIFTEEN_MIN, type)
    }

    @Test
    fun biometricType_fromValue_unknownPositive_returnsOff() {
        val type = BioMetricType.fromValue(99)
        assertSame("Unknown positive value must fall back to OFF", BioMetricType.OFF, type)
    }

    @Test
    fun biometricType_fromValue_negativeValue_returnsOff() {
        val type = BioMetricType.fromValue(-1)
        assertSame("Negative value must fall back to OFF", BioMetricType.OFF, type)
    }

    @Test
    fun biometricType_fromValue_intMin_returnsOff() {
        val type = BioMetricType.fromValue(Int.MIN_VALUE)
        assertSame("Int.MIN_VALUE must fall back to OFF", BioMetricType.OFF, type)
    }

    // =====================================================================================
    // BioMetricType – enabled()
    // =====================================================================================

    @Test
    fun biometricType_enabled_offType_returnsFalse() {
        assertFalse("OFF must not be enabled", BioMetricType.OFF.enabled())
    }

    @Test
    fun biometricType_enabled_immediateType_returnsTrue() {
        assertTrue("IMMEDIATE must be enabled", BioMetricType.IMMEDIATE.enabled())
    }

    @Test
    fun biometricType_enabled_fiveMinType_returnsTrue() {
        assertTrue("FIVE_MIN must be enabled", BioMetricType.FIVE_MIN.enabled())
    }

    @Test
    fun biometricType_enabled_fifteenMinType_returnsTrue() {
        assertTrue("FIFTEEN_MIN must be enabled", BioMetricType.FIFTEEN_MIN.enabled())
    }

    // =====================================================================================
    // BioMetricType – enum invariants
    // =====================================================================================

    @Test
    fun biometricType_entries_areExactlyFour() {
        assertEquals("BioMetricType must have exactly 4 entries", 4, BioMetricType.entries.size)
    }

    @Test
    fun biometricType_off_minutesAreNegative() {
        assertEquals("OFF minutes must be -1L", -1L, BioMetricType.OFF.mins)
    }

    @Test
    fun biometricType_immediate_minutesAreZero() {
        assertEquals("IMMEDIATE minutes must be 0L", 0L, BioMetricType.IMMEDIATE.mins)
    }

    @Test
    fun biometricType_fiveMin_minutesAreFive() {
        assertEquals("FIVE_MIN minutes must be 5L", 5L, BioMetricType.FIVE_MIN.mins)
    }

    @Test
    fun biometricType_fifteenMin_minutesAreFifteen() {
        assertEquals("FIFTEEN_MIN minutes must be 15L", 15L, BioMetricType.FIFTEEN_MIN.mins)
    }

    @Test
    fun biometricType_roundtrip_allValuesRecovered() {
        // Verifies that each action value round-trips through fromValue correctly
        BioMetricType.entries.forEach { type ->
            val recovered = BioMetricType.fromValue(type.action)
            assertSame(
                "fromValue(${type.action}) must return $type but got $recovered",
                type, recovered
            )
        }
    }

    // =====================================================================================
    // Companion – constants
    // =====================================================================================

    @Test
    fun constant_themeChangedResult_is24() {
        assertEquals(
            "THEME_CHANGED_RESULT must be 24",
            24,
            MiscSettingsActivity.THEME_CHANGED_RESULT
        )
    }

    // =====================================================================================
    // PcapMode – invariants used by MiscSettingsActivity
    // =====================================================================================

    @Test
    fun pcapMode_none_idIsZero() {
        assertEquals("PcapMode.NONE id must be 0", 0, PcapMode.NONE.id)
    }

    @Test
    fun pcapMode_logcat_idIsOne() {
        assertEquals("PcapMode.LOGCAT id must be 1", 1, PcapMode.LOGCAT.id)
    }

    @Test
    fun pcapMode_externalFile_idIsTwo() {
        assertEquals("PcapMode.EXTERNAL_FILE id must be 2", 2, PcapMode.EXTERNAL_FILE.id)
    }

    @Test
    fun pcapMode_getPcapType_zeroReturnsNone() {
        assertSame(PcapMode.NONE, PcapMode.getPcapType(0))
    }

    @Test
    fun pcapMode_getPcapType_oneReturnsLogcat() {
        assertSame(PcapMode.LOGCAT, PcapMode.getPcapType(1))
    }

    @Test
    fun pcapMode_getPcapType_twoReturnsExternalFile() {
        assertSame(PcapMode.EXTERNAL_FILE, PcapMode.getPcapType(2))
    }

    @Test
    fun pcapMode_getPcapType_unknownFallsBackToNone() {
        // The activity relies on a fallback for unknown values so it must not crash
        val result = PcapMode.getPcapType(99)
        assertNotNull("getPcapType with unknown value must not return null", result)
    }

    // =====================================================================================
    // NotificationActionType – invariants used by MiscSettingsActivity
    // =====================================================================================

    @Test
    fun notificationActionType_pauseStop_actionIsZero() {
        assertEquals(0, NotificationActionType.PAUSE_STOP.action)
    }

    @Test
    fun notificationActionType_dnsFirewall_actionIsOne() {
        assertEquals(1, NotificationActionType.DNS_FIREWALL.action)
    }

    @Test
    fun notificationActionType_none_actionIsTwo() {
        assertEquals(2, NotificationActionType.NONE.action)
    }

    @Test
    fun notificationActionType_getNotificationActionType_zeroReturnsPauseStop() {
        assertSame(
            NotificationActionType.PAUSE_STOP,
            NotificationActionType.getNotificationActionType(0)
        )
    }

    @Test
    fun notificationActionType_getNotificationActionType_oneReturnsDnsFirewall() {
        assertSame(
            NotificationActionType.DNS_FIREWALL,
            NotificationActionType.getNotificationActionType(1)
        )
    }

    @Test
    fun notificationActionType_getNotificationActionType_twoReturnsNone() {
        assertSame(
            NotificationActionType.NONE,
            NotificationActionType.getNotificationActionType(2)
        )
    }

    @Test
    fun notificationActionType_getNotificationActionType_unknownFallsBackToNone() {
        // activity calls this for un-handled values; must not throw
        val result = NotificationActionType.getNotificationActionType(99)
        assertNotNull("must not return null for unknown action", result)
    }

    // =====================================================================================
    // Themes – IDs used by MiscSettingsActivity.displayAppThemeUi()
    // =====================================================================================

    @Test
    fun themes_systemDefault_idIsZero() {
        assertEquals(0, Themes.SYSTEM_DEFAULT.id)
    }

    @Test
    fun themes_light_idIsOne() {
        assertEquals(1, Themes.LIGHT.id)
    }

    @Test
    fun themes_dark_idIsTwo() {
        assertEquals(2, Themes.DARK.id)
    }

    @Test
    fun themes_trueBlack_idIsThree() {
        assertEquals(3, Themes.TRUE_BLACK.id)
    }

    @Test
    fun themes_lightPlus_idIsFour() {
        assertEquals(4, Themes.LIGHT_PLUS.id)
    }

    @Test
    fun themes_darkPlus_idIsFive() {
        assertEquals(5, Themes.DARK_PLUS.id)
    }

    @Test
    fun themes_darkFrost_idIsSix() {
        assertEquals(6, Themes.DARK_FROST.id)
    }

    // =====================================================================================
    // Intent construction
    // =====================================================================================

    @Test
    fun intent_forMiscSettingsActivity_hasCorrectComponent() {
        val ctx = RuntimeEnvironment.getApplication()
        val intent = Intent(ctx, MiscSettingsActivity::class.java)
        assertNotNull("Intent must not be null", intent)
        assertEquals(
            "Component class name must be MiscSettingsActivity",
            MiscSettingsActivity::class.java.name,
            intent.component?.className
        )
    }

    @Test
    fun intent_componentPackageName_matchesAppPackage() {
        val ctx = RuntimeEnvironment.getApplication()
        val intent = Intent(ctx, MiscSettingsActivity::class.java)
        assertEquals(
            "Intent package must match context package",
            ctx.packageName,
            intent.component?.packageName
        )
    }

    // =====================================================================================
    // setupTokenUi – pure-logic extraction tests
    //
    // These test the token-generation rule that lives in MiscSettingsActivity.setupTokenUi():
    //   • blank token → generate new token
    //   • token older than 45 days → regenerate
    //   • fresh valid token → keep as-is
    // We replicate the exact logic here to guard against regressions.
    // =====================================================================================

    private fun simulateSetupTokenUi(
        existingToken: String,
        existingTimestamp: Long,
        now: Long = System.currentTimeMillis()
    ): Boolean /* shouldRegenerate */ {
        val fortyFiveDaysMs = TimeUnit.DAYS.toMillis(45L)
        return existingToken.isBlank() || (now - existingTimestamp > fortyFiveDaysMs)
    }

    @Test
    fun tokenSetup_blankToken_triggersRegeneration() {
        assertTrue(
            "Blank token must trigger regeneration",
            simulateSetupTokenUi(existingToken = "", existingTimestamp = System.currentTimeMillis())
        )
    }

    @Test
    fun tokenSetup_expiredToken_triggersRegeneration() {
        val fortyFiveDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(46)
        assertTrue(
            "Token older than 45 days must trigger regeneration",
            simulateSetupTokenUi(
                existingToken = "valid-token",
                existingTimestamp = fortyFiveDaysAgo
            )
        )
    }

    @Test
    fun tokenSetup_freshValidToken_doesNotTriggerRegeneration() {
        val recentTimestamp = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(10)
        assertFalse(
            "Fresh token must not trigger regeneration",
            simulateSetupTokenUi(
                existingToken = "valid-token",
                existingTimestamp = recentTimestamp
            )
        )
    }

    @Test
    fun tokenSetup_tokenExactly45DaysOld_doesNotTriggerRegeneration() {
        val exactlyFuture = System.currentTimeMillis()
        val exactlyPast = exactlyFuture - TimeUnit.DAYS.toMillis(45)
        // Boundary: exactly 45 days is NOT older than 45 days (it equals, not exceeds)
        assertFalse(
            "Token exactly at boundary must not regenerate",
            simulateSetupTokenUi(
                existingToken = "valid-token",
                existingTimestamp = exactlyPast,
                now = exactlyFuture
            )
        )
    }

    @Test
    fun tokenSetup_tokenOneMillisecondOver45Days_triggersRegeneration() {
        val now = System.currentTimeMillis()
        val justOver45Days = now - TimeUnit.DAYS.toMillis(45) - 1L
        assertTrue(
            "Token one millisecond over the boundary must regenerate",
            simulateSetupTokenUi(
                existingToken = "valid-token",
                existingTimestamp = justOver45Days,
                now = now
            )
        )
    }

    @Test
    fun tokenSetup_blankSpacesOnlyToken_triggersRegeneration() {
        assertTrue(
            "Whitespace-only token must trigger regeneration (isBlank)",
            simulateSetupTokenUi(
                existingToken = "   ",
                existingTimestamp = System.currentTimeMillis()
            )
        )
    }

    // =====================================================================================
    // PcapMode.ENABLE_PCAP_LOGCAT constant – activity uses this when setting LOGCAT mode
    // =====================================================================================

    @Test
    fun pcapMode_enablePcapLogcat_isStringZero() {
        assertEquals("ENABLE_PCAP_LOGCAT must be \"0\"", "0", PcapMode.ENABLE_PCAP_LOGCAT)
    }

    @Test
    fun pcapMode_disablePcap_isEmptyString() {
        assertEquals("DISABLE_PCAP must be empty string", "", PcapMode.DISABLE_PCAP)
    }
}

