/*
 * Copyright 2026 ezelab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.celzero.bravedns.tv.ui.rpn

import androidx.compose.runtime.Composable
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import com.celzero.bravedns.tv.ui.common.TvScreenScaffold
import androidx.tv.material3.Text

/**
 * fdroid stub for the RPN checkout. The fdroid channel has no
 * billing backend, so this route is unreachable (the RPN nav entry
 * is gated off) — it exists only so the shared TV nav graph compiles
 * against the same symbol the play/website source sets provide.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RpnPurchaseRoute(navController: androidx.navigation.NavController? = null) {
    TvScreenScaffold(
        title = "Get Rethink Plus",
        subtitle = "Rethink Plus is not available on this build.",
    ) {
        Text(
            text = "Use a Play Store or website build of Rethink to subscribe.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
