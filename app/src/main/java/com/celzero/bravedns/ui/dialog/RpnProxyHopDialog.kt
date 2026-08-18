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
package com.celzero.bravedns.ui.dialog

import android.app.Activity
import com.celzero.bravedns.adapter.HopItem
import com.celzero.bravedns.database.CountryConfig

/**
 * Dialog for RPN proxy country-based hopping
 * Extends GenericHopDialog to reuse common hop logic
 */
class RpnProxyHopDialog(
    activity: Activity,
    themeID: Int,
    srcCountryCode: String,
    availableCountries: List<CountryConfig>,
    selectedCountryCode: String?,
    onHopChanged: ((Int) -> Unit)? = null
) : GenericHopDialog(
    activity,
    themeID,
    srcCountryCode.hashCode(),
    availableCountries.map { countryConfig ->
        HopItem.RpnProxyHop(countryConfig, countryConfig.isActive)
    },
    selectedCountryCode?.hashCode() ?: -1,
    onHopChanged
) {
    companion object {
        /**
         * Create RPN proxy hop dialog
         */
        fun create(
            activity: Activity,
            themeID: Int,
            srcCountryCode: String,
            availableCountries: List<CountryConfig>,
            currentlySelectedCountryCode: String? = null,
            onHopChanged: ((Int) -> Unit)? = null
        ): RpnProxyHopDialog {
            return RpnProxyHopDialog(
                activity,
                themeID,
                srcCountryCode,
                availableCountries,
                currentlySelectedCountryCode,
                onHopChanged
            )
        }
    }
}

