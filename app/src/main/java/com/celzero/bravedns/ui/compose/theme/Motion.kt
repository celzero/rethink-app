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
package com.celzero.bravedns.ui.compose.theme

import android.animation.ValueAnimator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
fun rememberReducedMotion(): Boolean {
    return remember {
        runCatching { !ValueAnimator.areAnimatorsEnabled() }.getOrDefault(false)
    }
}

@Composable
@Suppress("UNUSED_PARAMETER")
fun RethinkAnimatedSection(
    index: Int,
    content: @Composable () -> Unit
) {
    content()
}
