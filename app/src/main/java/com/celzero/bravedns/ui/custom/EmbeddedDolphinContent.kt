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
package com.celzero.bravedns.ui.custom

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.celzero.bravedns.R

/**
 * Source of dolphin/quote pairings for the [EmbeddedDolphinSignature].
 *
 * The pool of dolphins and the pool of quotes are independent: any dolphin
 * can be paired with any quote. [random] picks a fresh combination on every
 * call, so each visit to a screen shows a different signature, and [next]
 * provides the tap-to-cycle successor for a given pairing. To add a new
 * screen, show [random] when wiring the fragment/activity.
 */
object EmbeddedDolphinContent {

    /** A dolphin artwork paired with a quote to render beneath it. */
    data class Signature(@DrawableRes val image: Int, @StringRes val quote: Int)

    // --- the five dolphins (extracted from the sprite sheet) ---
    // dolphin_waves  : riding a wave line
    // dolphin_relay  : passing a curling wave
    // dolphin_dive   : steep nose-down descent
    // dolphin_free   : arched, playful leap
    // dolphin_secure : clean glide with spray

    /** The full dolphin pool. */
    val DOLPHINS: List<Int> = listOf(
        R.drawable.dolphin_waves,
        R.drawable.dolphin_relay,
        R.drawable.dolphin_dive,
        R.drawable.dolphin_free,
        R.drawable.dolphin_secure
    )

    /** The full quote pool; any dolphin can be paired with any of these. */
    val QUOTES: List<Int> = listOf(
        R.string.dolphin_quote_no_trails,
        R.string.dolphin_quote_take_the_long_way,
        R.string.dolphin_quote_beneath_the_surface,
        R.string.dolphin_quote_move_freely,
        R.string.dolphin_quote_surf_without_tracks,
        R.string.dolphin_quote_not_the_obvious_path,
        R.string.dolphin_quote_let_traffic_wander,
        R.string.dolphin_quote_privacy_in_motion,
        R.string.dolphin_quote_go_further,
        R.string.dolphin_quote_your_current,
        R.string.dolphin_quote_swim_quietly,
        R.string.dolphin_quote_leave_only_ripples,
        R.string.dolphin_quote_hard_to_catch,
        R.string.dolphin_quote_gone_with_the_current,
        R.string.dolphin_quote_keep_wake_private,
        R.string.dolphin_quote_swim_without_a_trace,
        R.string.dolphin_quote_out_of_sight_online,
        R.string.dolphin_quote_privacy_likes_the_deep,
        R.string.dolphin_quote_internet_neednt_know,
        R.string.dolphin_quote_ride_private_wave,
        R.string.dolphin_quote_browse_swim_repeat,
        R.string.dolphin_quote_keep_swimming_keep_private,
        R.string.dolphin_quote_your_current_your_rules,
        R.string.dolphin_quote_quieter_way_online,
        R.string.dolphin_quote_surf_freely,
        R.string.dolphin_quote_stay_private_keep_moving,
        R.string.dolphin_quote_web_wide_swim_freely,
        R.string.dolphin_quote_one_hop_at_a_time,
        R.string.dolphin_quote_hop_skip_swim,
        R.string.dolphin_quote_why_direct_route,
        R.string.dolphin_quote_little_detour,
        R.string.dolphin_quote_this_way_nope,
        R.string.dolphin_quote_not_going_straight,
        R.string.dolphin_quote_scenic_route,
        R.string.dolphin_quote_no_shortcuts,
        R.string.dolphin_quote_just_keep_swimming,
        R.string.dolphin_quote_no_breadcrumbs,
        R.string.dolphin_quote_nice_try_tracker,
        R.string.dolphin_quote_catch_me_if_you_can,
        R.string.dolphin_quote_where_did_i_swim_off,
        R.string.dolphin_quote_avoiding_obvious_route,
        R.string.dolphin_quote_swimming_somewhere_else,
        R.string.dolphin_quote_lost_we_meant_it,
        R.string.dolphin_quote_nothing_suspicious
    )

    /** A random dolphin paired with a random quote; different on every call. */
    fun random(): Signature = Signature(DOLPHINS.random(), QUOTES.random())

    /**
     * The tap-to-cycle successor of [s]: next dolphin (cyclic) paired with
     * the next quote (cyclic).
     */
    fun next(s: Signature): Signature =
        Signature(
            DOLPHINS[(DOLPHINS.indexOf(s.image) + 1).mod(DOLPHINS.size)],
            QUOTES[(QUOTES.indexOf(s.quote) + 1).mod(QUOTES.size)]
        )
}
