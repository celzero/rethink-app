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
package com.celzero.bravedns.ui.tour

import com.celzero.bravedns.R
import com.celzero.bravedns.service.PersistentState

/**
 * Singleton that owns the premium RPN (Server Selection) onboarding tour.
 *
 * Shown once right after the user lands on the RPN dashboard — which is exactly
 * where the purchase flow drops them after the confetti dialog — so new
 * subscribers immediately discover the features that are easy to miss:
 *
 *  1. Hero card          — welcome + what RPN is
 *  2. Add-location tile  — up to [com.celzero.bravedns.ui.fragment.ServerSelectionFragment]
 *                          MAX_SELECTIONS (5) countries can be active at once
 *  3. Relay tile         — AUTO → chosen-country multi-hop chaining
 *  4. Bypass-apps tile   — exclude apps whose traffic must skip RPN
 *  5. Selected servers   — per-app, per-country routing (app A via country X,
 *                          app B via country Y simultaneously)
 *  6. Stats tile         — live throughput + 6-hour activity heat map
 *  7. Settings gear      — Privacy / Security / Family DNS blocklists,
 *                          identity mode, custom ports
 *
 * Mirrors [GuidedTourManager]: ordered [TourStep]s, version-gated one-shot
 * display, persisted via [PersistentState]. Every step carries the golden
 * "Premium" badge and the golden spotlight glow so the whole flow feels
 * premium.
 *
 * To bump the tour for a new dashboard revision, increment
 * [PersistentState.RPN_ONBOARDING_CURRENT_VERSION]. Users whose stored
 * [PersistentState.rpnOnboardingVersion] is lower will see it again.
 */
object RpnOnboardingManager {

    private val RPN_STEPS_RAW = listOf(
        // 1. Hero / welcome — premium badge on the very first frame
        TourStep(
            targetViewId   = R.id.status_card,
            titleRes       = R.string.rpn_tour_welcome_title,
            descRes        = R.string.rpn_tour_welcome_desc,
            tooltipSide    = TooltipSide.BELOW,
            spotlightShape = SpotlightShape.ROUNDED_RECT,
            isPremium      = true,
        ),
        // 2. Add-location quick tile — the "more than one country" discovery
        TourStep(
            targetViewId   = R.id.qs_add_location_tile,
            titleRes       = R.string.rpn_tour_add_location_title,
            descRes        = R.string.rpn_tour_add_location_desc,
            tooltipSide    = TooltipSide.BELOW,
            spotlightShape = SpotlightShape.ROUNDED_RECT,
            isPremium      = true,
        ),
        // 3. Relay quick tile — AUTO → chosen country chaining
        TourStep(
            targetViewId   = R.id.qs_relay_tile,
            titleRes       = R.string.rpn_tour_relay_title,
            descRes        = R.string.rpn_tour_relay_desc,
            tooltipSide    = TooltipSide.BELOW,
            spotlightShape = SpotlightShape.ROUNDED_RECT,
            isPremium      = true,
        ),
        // 4. Bypass-apps quick tile
        TourStep(
            targetViewId   = R.id.qs_bypass_apps_tile,
            titleRes       = R.string.rpn_tour_bypass_title,
            descRes        = R.string.rpn_tour_bypass_desc,
            tooltipSide    = TooltipSide.BELOW,
            spotlightShape = SpotlightShape.ROUNDED_RECT,
            isPremium      = true,
        ),
        // 5. Selected servers list — per-app, per-country routing
        TourStep(
            targetViewId   = R.id.rv_selected_servers,
            titleRes       = R.string.rpn_tour_per_app_title,
            descRes        = R.string.rpn_tour_per_app_desc,
            tooltipSide    = TooltipSide.AUTO,
            spotlightShape = SpotlightShape.ROUNDED_RECT,
            isPremium      = true,
        ),
        // 6. Stats quick tile — live throughput + heat map.
        // AUTO (not ABOVE): the tile sits low on screen and a forced ABOVE
        // tooltip floats far away over the hero card; AUTO keeps the card
        // adjacent (below, flipping to above only when out of space).
        TourStep(
            targetViewId   = R.id.qs_stats_tile,
            titleRes       = R.string.rpn_tour_stats_title,
            descRes        = R.string.rpn_tour_stats_desc,
            tooltipSide    = TooltipSide.AUTO,
            spotlightShape = SpotlightShape.ROUNDED_RECT,
            isPremium      = true,
        ),
        // 7. Settings gear — Privacy / Security / Family blocklists
        TourStep(
            targetViewId   = R.id.settings_btn,
            titleRes       = R.string.rpn_tour_settings_title,
            descRes        = R.string.rpn_tour_settings_desc,
            tooltipSide    = TooltipSide.BELOW,
            spotlightShape = SpotlightShape.CIRCLE,
            isPremium      = true,
        ),
    )

    /**
     * Returns the indexed, size-annotated steps for the RPN onboarding flow.
     * Call this once per [TourOverlayController] session, not on every step advance.
     */
    fun rpnOnboardingSteps(): List<TourStep> {
        val total = RPN_STEPS_RAW.size
        return RPN_STEPS_RAW.mapIndexed { i, step ->
            step.copy(index = i, total = total)
        }
    }

    /**
     * Returns `true` when the RPN onboarding should be shown.
     *
     * The tour is (re-)shown when:
     *  a) It has never been completed ([rpnOnboardingCompleted] == false), OR
     *  b) A newer version exists ([rpnOnboardingVersion] < [RPN_ONBOARDING_CURRENT_VERSION]).
     *
     * Simple SharedPreferences read, O(1); safe on the main thread.
     */
    fun shouldShowOnboarding(state: PersistentState): Boolean {
        if (!state.rpnOnboardingCompleted) return true
        return state.rpnOnboardingVersion < PersistentState.RPN_ONBOARDING_CURRENT_VERSION
    }

    /**
     * Persists the onboarding as completed and records the current version.
     * Call from the main thread after [TourOverlayController] fires its completion callback.
     */
    fun markCompleted(state: PersistentState) {
        state.rpnOnboardingCompleted = true
        state.rpnOnboardingVersion   = PersistentState.RPN_ONBOARDING_CURRENT_VERSION
    }

    /**
     * Resets the onboarding so it will be shown again on next dashboard visit.
     * For use in debug/test mode only.
     */
    fun resetForDebug(state: PersistentState) {
        state.rpnOnboardingCompleted = false
        state.rpnOnboardingVersion   = 0
    }
}
