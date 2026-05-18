/*
 * Copyright 2026 ezelab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.ezelab.rethinktv.ui.firewall

import androidx.compose.runtime.Composable
import com.ezelab.rethinktv.ui.common.PlaceholderScreen

/**
 * Firewall destination — Phase D will replace this placeholder with
 * the universal-firewall toggle screen (background / metered /
 * screen-off / etc.) plus the custom-domain and custom-IP rule
 * editors. Per-app rules live under the Apps destination.
 */
@Composable
fun FirewallScreen() = PlaceholderScreen("Firewall")
