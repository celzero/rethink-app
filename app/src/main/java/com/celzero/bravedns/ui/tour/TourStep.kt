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

import androidx.annotation.IdRes
import androidx.annotation.StringRes

/**
 * Describes a single step in the guided tour.
 *
 * @param targetViewId  The view ID of the element to spotlight.
 * @param titleRes      String resource for the tooltip title (short, bold).
 * @param descRes       String resource for the tooltip description (1–2 lines).
 * @param tooltipSide   Preferred side to show the tooltip relative to the spotlight.
 *                      [TooltipSide.AUTO] lets [TourOverlayController] decide based on
 *                      available vertical space at runtime.
 * @param spotlightShape Shape of the spotlight cutout drawn around the target view.
 * @param isPremium     When `true`, the tooltip card shows a golden "Premium Feature" badge.
 * @param index         0-based index of this step within its parent flow.
 * @param total         Total number of steps in the flow.
 */
data class TourStep(
    @IdRes val targetViewId: Int,
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int,
    val tooltipSide: TooltipSide = TooltipSide.AUTO,
    val spotlightShape: SpotlightShape = SpotlightShape.ROUNDED_RECT,
    val isPremium: Boolean = false,
    val index: Int = 0,
    val total: Int = 1,
) {
    /** True when this is the very last step in the flow. */
    val isLastStep: Boolean get() = index == total - 1

    /** 1-based position label (e.g. "2 of 5"). */
    val position: Int get() = index + 1
}

/** Preferred vertical placement of the tooltip card relative to the spotlight rect. */
enum class TooltipSide {
    /** Show the card above the spotlight. */
    ABOVE,
    /** Show the card below the spotlight. */
    BELOW,
    /** Runtime auto-detect: prefer [BELOW], flip to [ABOVE] when too close to screen bottom. */
    AUTO,
}

/** Shape of the cutout drawn by [SpotlightOverlayView] around the target. */
enum class SpotlightShape {
    /** Rounded rectangle: best for cards and buttons. */
    ROUNDED_RECT,
    /** Full circle: best for FABs or small icon buttons. */
    CIRCLE,
}

