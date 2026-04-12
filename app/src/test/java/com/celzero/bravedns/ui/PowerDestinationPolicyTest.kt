/*
 * Copyright 2026 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package com.celzero.bravedns.ui

import com.celzero.bravedns.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerDestinationPolicyTest {

    @Test
    fun deepPowerDestinations_hideBottomNav() {
        assertTrue(PowerDestinationPolicy.isDeepPowerDestination(R.id.activeProfilesFragment))
        assertTrue(PowerDestinationPolicy.isDeepPowerDestination(R.id.discoverProfilesFragment))
        assertTrue(PowerDestinationPolicy.isDeepPowerDestination(R.id.powerProfileDetailFragment))
        assertTrue(PowerDestinationPolicy.isDeepPowerDestination(R.id.powerProfileEntriesFragment))
        assertTrue(PowerDestinationPolicy.isDeepPowerDestination(R.id.powerProfileAppsFragment))
        assertFalse(PowerDestinationPolicy.isDeepPowerDestination(R.id.powerFragment))
    }

    @Test
    fun topLevelDestinations_mapToCorrectBottomNavItems() {
        assertEquals(R.id.powerFragment, PowerDestinationPolicy.topLevelMenuItem(R.id.powerFragment))
        assertEquals(R.id.powerFragment, PowerDestinationPolicy.topLevelMenuItem(R.id.homeScreenFragment))
        assertEquals(
            R.id.summaryStatisticsFragment,
            PowerDestinationPolicy.topLevelMenuItem(R.id.summaryStatisticsFragment)
        )
        assertEquals(R.id.configureFragment, PowerDestinationPolicy.topLevelMenuItem(R.id.configureFragment))
        assertEquals(R.id.aboutFragment, PowerDestinationPolicy.topLevelMenuItem(R.id.aboutFragment))
        assertEquals(null, PowerDestinationPolicy.topLevelMenuItem(R.id.powerProfileDetailFragment))
    }
}
