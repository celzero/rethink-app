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
package com.celzero.bravedns.ui.tour

import com.celzero.bravedns.R
import com.celzero.bravedns.service.PersistentState

/**
 * Singleton that owns the Home-screen guided tour flow.
 *
 * Responsibilities:
 *  • Exposes the ordered list of [TourStep]s.
 *  • Decides whether the tour should be shown ([shouldShowTour]).
 *  • Persists completion state via [PersistentState].
 *  • Provides a [resetForDebug] helper that forces the tour to re-show.
 *
 * To bump the tour for a new UI revision, increment [PersistentState.GUIDED_TOUR_CURRENT_VERSION].
 * Users whose stored [PersistentState.guidedTourVersion] is lower will see the tour again.
 */
object GuidedTourManager {

    private val HOME_STEPS_RAW = listOf(
        TourStep(
            targetViewId   = R.id.fhs_dns_on_off_btn,
            titleRes       = R.string.tour_step_vpn_title,
            descRes        = R.string.tour_step_vpn_desc,
            tooltipSide    = TooltipSide.ABOVE,
            spotlightShape = SpotlightShape.ROUNDED_RECT,
        ),
        TourStep(
            targetViewId   = R.id.fhs_card_dns_ll,
            titleRes       = R.string.tour_step_dns_title,
            descRes        = R.string.tour_step_dns_desc,
            tooltipSide    = TooltipSide.BELOW,
            spotlightShape = SpotlightShape.ROUNDED_RECT,
        ),
        TourStep(
            targetViewId   = R.id.fhs_card_firewall_ll,
            titleRes       = R.string.tour_step_firewall_title,
            descRes        = R.string.tour_step_firewall_desc,
            tooltipSide    = TooltipSide.BELOW,
            spotlightShape = SpotlightShape.ROUNDED_RECT,
        ),
        TourStep(
            targetViewId   = R.id.fhs_card_proxy_ll,
            titleRes       = R.string.tour_step_proxy_title,
            descRes        = R.string.tour_step_proxy_desc,
            tooltipSide    = TooltipSide.AUTO,
            spotlightShape = SpotlightShape.ROUNDED_RECT,
        ),
        TourStep(
            targetViewId   = R.id.fhs_card_logs_ll,
            titleRes       = R.string.tour_step_logs_title,
            descRes        = R.string.tour_step_logs_desc,
            tooltipSide    = TooltipSide.AUTO,
            spotlightShape = SpotlightShape.ROUNDED_RECT,
        ),
        TourStep(
            targetViewId   = R.id.rethinkPlus,
            titleRes       = R.string.rpn_title,
            descRes        = R.string.tour_step_rpn_desc,
            tooltipSide    = TooltipSide.ABOVE,
            spotlightShape = SpotlightShape.CIRCLE,
            isPremium      = true,
        ),
    )

    /**
     * Returns the indexed, size-annotated steps for the Home-screen flow.
     * Call this once per [TourOverlayController] session, not on every step advance.
     */
    fun homeScreenSteps(): List<TourStep> {
        val total = HOME_STEPS_RAW.size
        return HOME_STEPS_RAW.mapIndexed { i, step ->
            step.copy(index = i, total = total)
        }
    }

    /**
     * Returns `true` when the guided tour should be shown to the user.
     *
     * The tour is (re-)shown when:
     *  a) It has never been completed ([guidedTourCompleted] == false), OR
     *  b) A newer version of the tour exists ([guidedTourVersion] < [GUIDED_TOUR_CURRENT_VERSION]).
     *
     * This runs on the calling thread, it is a simple SharedPreferences read, O(1).
     */
    fun shouldShowTour(state: PersistentState): Boolean {
        if (!state.guidedTourCompleted) return true
        return state.guidedTourVersion < PersistentState.GUIDED_TOUR_CURRENT_VERSION
    }

    /**
     * Persists the tour as completed and records the current version.
     * Call this from the main thread after [TourOverlayController] fires its completion callback.
     */
    fun markCompleted(state: PersistentState) {
        state.guidedTourCompleted = true
        state.guidedTourVersion   = PersistentState.GUIDED_TOUR_CURRENT_VERSION
    }

    /**
     * Resets the tour so it will be shown again on next launch.
     * For use in debug/test mode only, wire this to a hidden settings toggle.
     */
    fun resetForDebug(state: PersistentState) {
        state.guidedTourCompleted = false
        state.guidedTourVersion   = 0
    }
}

