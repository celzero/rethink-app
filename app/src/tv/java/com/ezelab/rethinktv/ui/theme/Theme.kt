/*
 * Copyright 2026 ezelab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.ezelab.rethinktv.ui.theme

import androidx.compose.runtime.Composable
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/**
 * Root theme for the `tv` flavor.
 *
 * Android TV apps are almost universally dark-themed — the "ten-foot UI"
 * pattern assumes the device is in a living room and bright surfaces are
 * uncomfortable to look at from the couch. We therefore lock the TV
 * flavor to TV-Material 3's dark color scheme (no system-following
 * light/dark toggle).
 *
 * Typography and component shapes are inherited from TV-Material 3
 * defaults, which already use larger text sizes than the phone Material
 * defaults — appropriate for 10-foot reading distance.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RethinkTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(),
        content = content,
    )
}
