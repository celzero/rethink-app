/*
 * Copyright 2026 ezelab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.ezelab.rethinktv.ui.apps

import androidx.compose.runtime.Composable
import com.ezelab.rethinktv.ui.common.PlaceholderScreen

/**
 * Apps destination — Phase E will replace this placeholder with the
 * full per-app rules surface (lazy-grid of installed apps,
 * focus-driven detail pane with firewall-status segmented control,
 * proxy mapping, per-app domain/IP rules, app-wise log shortcuts).
 *
 * The deleted Phase-6 "Streams" tab is folded into this destination —
 * Streams was just a curated filter on top of the same FirewallManager
 * data, which Apps will expose in full.
 */
@Composable
fun AppsScreen() = PlaceholderScreen("Apps")
