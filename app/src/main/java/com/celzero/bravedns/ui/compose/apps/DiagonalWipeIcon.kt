package com.celzero.bravedns.ui.compose.apps

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.material3.Icon
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.drawscope.clipPath

/**
 * Defaults for [DiagonalWipeIcon].
 *
 * Keep these values centralized so callers can reuse the same motion language or override
 * a single knob (for example only duration) without rewriting the full component.
 */
@Immutable
object DiagonalWipeIconDefaults {
    // "Enable" here means toggling from allowed -> blocked.
    const val EnableDurationMillis: Int = 530

    // "Disable" here means toggling from blocked -> allowed.
    const val DisableDurationMillis: Int = 610

    // Snappy entry to make blocked feel responsive.
    val EnableEasing: Easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

    // Slightly gentler exit for a softer return to allowed.
    val DisableEasing: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    // Small overlap avoids a visible seam between clipped layers on some densities.
    const val SeamOverlapPx: Float = 0.8f
}

/**
 * Two-layer icon morph using one diagonal wipe boundary.
 *
 * Layer model:
 * 1) Allowed icon exits from the non-revealed side.
 * 2) Blocked icon enters from the revealed side.
 *
 * Because both layers use the same path and same progress in the same frame, they remain synced.
 *
 * @param blocked Target state: true = blocked icon fully visible; false = allowed icon fully visible.
 * @param allowedIcon Icon used when state is allowed.
 * @param blockedIcon Icon used when state is blocked.
 * @param allowedTint Tint for [allowedIcon].
 * @param blockedTint Tint for [blockedIcon].
 * @param contentDescription Optional semantics description.
 * @param modifier Standard modifier.
 * @param enableDurationMillis Duration when moving from allowed -> blocked.
 * @param disableDurationMillis Duration when moving from blocked -> allowed.
 * @param enableEasing Easing for allowed -> blocked transition.
 * @param disableEasing Easing for blocked -> allowed transition.
 * @param seamOverlapPx Tiny overlap to prevent hairline seams.
 */
@Composable
fun DiagonalWipeIcon(
    blocked: Boolean,
    allowedIcon: ImageVector,
    blockedIcon: ImageVector,
    allowedTint: Color,
    blockedTint: Color,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    enableDurationMillis: Int = DiagonalWipeIconDefaults.EnableDurationMillis,
    disableDurationMillis: Int = DiagonalWipeIconDefaults.DisableDurationMillis,
    enableEasing: Easing = DiagonalWipeIconDefaults.EnableEasing,
    disableEasing: Easing = DiagonalWipeIconDefaults.DisableEasing,
    seamOverlapPx: Float = DiagonalWipeIconDefaults.SeamOverlapPx,
) {
    // Transition is keyed by the target blocked state.
    val transition = updateTransition(targetState = blocked, label = "diagonalWipeIcon")

    // Vector painters are reused across frames for efficient icon drawing in Canvas.
    val allowedPainter = rememberVectorPainter(allowedIcon)
    val blockedPainter = rememberVectorPainter(blockedIcon)

    // Shared progress for both layers. 0 = fully allowed, 1 = fully blocked.
    val blockedRevealProgress by transition.animateFloat(
        transitionSpec = {
            if (false isTransitioningTo true) {
                tween(durationMillis = enableDurationMillis, easing = enableEasing)
            } else {
                tween(durationMillis = disableDurationMillis, easing = disableEasing)
            }
        },
        label = "diagonalWipeReveal",
    ) { isBlocked ->
        if (isBlocked) 1f else 0f
    }

    // Clamp to protect against tiny numeric overshoots.
    val blockedProgress = blockedRevealProgress.coerceIn(0f, 1f)

    Box(
        modifier = modifier
            // Offscreen composition ensures clipping behaves consistently across draws.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            // Keep accessibility semantics attached to the whole icon.
            .semantics {
                if (contentDescription != null) {
                    this.contentDescription = contentDescription
                }
            },
    ) {
        // Fast path: no in-between draw work when fully allowed.
        if (blockedProgress <= 0.001f) {
            Icon(
                imageVector = allowedIcon,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                tint = allowedTint,
            )
            return@Box
        }

        // Fast path: no in-between draw work when fully blocked.
        if (blockedProgress >= 0.999f) {
            Icon(
                imageVector = blockedIcon,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                tint = blockedTint,
            )
            return@Box
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            // Convert normalized progress into diagonal coverage (x+y plane),
            // then add a tiny overlap so both clipped regions meet without a gap.
            val adjustedBlockedProgress = (
                    (blockedProgress * (size.width + size.height) + seamOverlapPx) /
                            (size.width + size.height)
                    ).coerceIn(0f, 1f)

            // This single path defines both entering and exiting regions.
            val blockedRevealPath = buildDiagonalRevealPath(
                width = size.width,
                height = size.height,
                progress = adjustedBlockedProgress,
            )

            // Allowed (first layer) exits while blocked (second layer) enters
            // with the exact same boundary from opposite clipped regions.
            clipPath(path = blockedRevealPath, clipOp = ClipOp.Difference) {
                with(allowedPainter) {
                    draw(size = size, colorFilter = ColorFilter.tint(allowedTint))
                }
            }
            clipPath(path = blockedRevealPath, clipOp = ClipOp.Intersect) {
                with(blockedPainter) {
                    draw(size = size, colorFilter = ColorFilter.tint(blockedTint))
                }
            }
        }
    }
}

/**
 * Builds a polygon that grows diagonally from top-left toward bottom-right.
 *
 * Geometry note:
 * - We drive progression along `x + y = constant`.
 * - At p=0, the polygon is empty.
 * - At p=1, the polygon covers the full icon bounds.
 */
private fun buildDiagonalRevealPath(width: Float, height: Float, progress: Float): Path {
    val p = progress.coerceIn(0f, 1f)
    return Path().apply {
        if (p <= 0f) return@apply
        if (p >= 1f) {
            moveTo(0f, 0f)
            lineTo(width, 0f)
            lineTo(width, height)
            lineTo(0f, height)
            close()
            return@apply
        }

        val diagonal = (width + height) * p
        moveTo(0f, 0f)
        lineTo(diagonal.coerceAtMost(width), 0f)
        if (diagonal > width) {
            lineTo(width, (diagonal - width).coerceAtMost(height))
        }
        if (diagonal > height) {
            lineTo((diagonal - height).coerceAtMost(width), height)
        }
        lineTo(0f, diagonal.coerceAtMost(height))
        close()
    }
}
