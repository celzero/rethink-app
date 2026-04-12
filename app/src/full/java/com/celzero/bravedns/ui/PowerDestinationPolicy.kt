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
package com.celzero.bravedns.ui

import com.celzero.bravedns.R

object PowerDestinationPolicy {
    fun isDeepPowerDestination(destinationId: Int): Boolean {
        return destinationId == R.id.activeProfilesFragment ||
            destinationId == R.id.discoverProfilesFragment ||
            destinationId == R.id.powerProfileDetailFragment ||
            destinationId == R.id.powerProfileEntriesFragment ||
            destinationId == R.id.powerProfileAppsFragment
    }

    fun topLevelMenuItem(destinationId: Int): Int? {
        return when (destinationId) {
            R.id.powerFragment, R.id.homeScreenFragment -> R.id.powerFragment
            R.id.summaryStatisticsFragment -> R.id.summaryStatisticsFragment
            R.id.configureFragment -> R.id.configureFragment
            R.id.aboutFragment -> R.id.aboutFragment
            else -> null
        }
    }
}
