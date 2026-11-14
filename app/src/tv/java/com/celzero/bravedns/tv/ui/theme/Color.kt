/*
 * Copyright 2026 ezelab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.celzero.bravedns.tv.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * "Indigo Deep" — the TV flavor's expressive dark palette.
 *
 * Hierarchy rule: roughly 70–80% of any screen is deep neutral /
 * tonal surface (background → surface → the container ladder),
 * 10–15% indigo (primary emphasis and focus), 5–10% teal/lavender
 * (secondary + tertiary families), and under 5% warning/error.
 * Vibrancy comes from color + shape + type + spatial hierarchy, not
 * from glow or decoration.
 *
 * The ladder: [Background] #080D16 is the app floor, [Surface]
 * #111A29 the screen plane, [SurfaceContainer] / [SurfaceContainerHigh]
 * / [SurfaceContainerHighest] #162238 / #1C2942 / #243354 are the
 * card steps. Success, Warning and Info are custom semantic roles
 * (M3 has no native ones); they carry status meaning only — never
 * used as decoration.
 *
 * The legacy aliases [SurfaceCard] / [SurfaceCardHigh] /
 * [SurfaceCardHighest] / [Border] and the gradient / scrim roles are
 * kept under their historical names because other screens already
 * compile against them; their values simply point at the new tones.
 */
object TvColors {
    // Primary — bright indigo.
    val Primary = Color(0xFF7187FF)
    val OnPrimary = Color(0xFF0A102E)
    val PrimaryBright = Color(0xFF9AA8FF)
    val PrimaryContainer = Color(0xFF29376F)
    val OnPrimaryContainer = Color(0xFFE4E8FF)
    val InversePrimary = Color(0xFF29376F)

    // Secondary — teal (DNS / network information).
    val Secondary = Color(0xFF55D9CC)
    val OnSecondary = Color(0xFF062622)
    val SecondaryContainer = Color(0xFF174D4C)
    val OnSecondaryContainer = Color(0xFFC9F3EE)

    // Tertiary — lavender. Secondary highlights, firewall accents.
    val Tertiary = Color(0xFFB79CFF)
    val OnTertiary = Color(0xFF241A45)
    val TertiaryContainer = Color(0xFF3B3264)
    val OnTertiaryContainer = Color(0xFFEBE2FF)

    // Success — custom semantic role for connected/allowed/healthy.
    val Success = Color(0xFF63E6C5)
    val OnSuccess = Color(0xFF06251F)
    val SuccessContainer = Color(0xFF164B43)
    val OnSuccessContainer = Color(0xFFD7F6EE)

    // Warning — a distinct semantic role now (no longer a tertiary
    // alias): attention states, blocked-traffic highlights.
    val Warning = Color(0xFFFFB95E)
    val OnWarning = Color(0xFF2C1C00)
    val WarningContainer = Color(0xFF523A1C)
    val OnWarningContainer = Color(0xFFFFE5C7)

    // Error — blocked / failure.
    val Error = Color(0xFFFF7B72)
    val OnError = Color(0xFF370B08)
    val ErrorContainer = Color(0xFF572522)
    val OnErrorContainer = Color(0xFFFFDAD6)

    // Info — informational status that is neither good nor bad.
    val Info = Color(0xFF7CA6FF)

    // Background / surface — deep indigo-black (never pure black).
    val Background = Color(0xFF080D16)
    val OnBackground = Color(0xFFF2F4FF)
    val Surface = Color(0xFF111A29)
    val OnSurface = Color(0xFFE8ECFA)
    val SurfaceVariant = Color(0xFF162238)
    val OnSurfaceVariant = Color(0xFFB9C1D5)

    // The full tonal container ladder. tv-material 1.1.0's
    // darkColorScheme has no surface-container slots, so components
    // step through these tokens directly.
    val SurfaceContainer = Color(0xFF162238)
    val SurfaceContainerHigh = Color(0xFF1C2942)
    val SurfaceContainerHighest = Color(0xFF243354)
    val SurfaceContainerLow = Color(0xFF111A29)
    val SurfaceContainerLowest = Color(0xFF0B1220)
    val SurfaceDim = Color(0xFF080D16)
    val SurfaceBright = Color(0xFF2C3A5C)

    // Legacy card-step aliases (other screens reference these names).
    val SurfaceCard = Surface
    val SurfaceCardHigh = SurfaceContainer
    val SurfaceCardHighest = SurfaceContainerHigh

    // Inverse.
    val InverseSurface = Color(0xFFF2F4FF)
    val InverseOnSurface = Color(0xFF080D16)

    // Borders / scrim (TV roles; #46536B as the subtle border).
    val Border = Color(0xFF46536B)
    val BorderVariant = Color(0xFF29354A)
    val Scrim = Color(0xFF000000)

    // Dimmed states.
    val DisabledContainer = SurfaceVariant.copy(alpha = 0.38f)
    val DisabledContent = OnSurfaceVariant.copy(alpha = 0.38f)

    // Gradient accents — layered washes for expressive hero surfaces.
    val HeroGradientStart = Color(0xFF233159)
    val HeroGradientEnd = Color(0xFF162238)
    val FocusGlowScrim = Primary.copy(alpha = 0.55f)

    // Offset tonal layers for the hero composition: an indigo bloom
    // and a teal counterweight that fade to transparent so overlapping
    // gradient boxes read as depth on the tonal base.
    val HeroAccentStart = Primary.copy(alpha = 0.18f)
    val TealAccentStart = Secondary.copy(alpha = 0.14f)

    // Traffic semantics — data-direction tokens used by the network
    // activity visualization and stat tiles.
    val TrafficAllowed = Secondary
    val TrafficBlocked = Warning
    val TrafficReceived = Secondary
    val TrafficSent = PrimaryBright
    val TrafficBlockedConn = Error
    val TrafficActiveConn = PrimaryBright
}
