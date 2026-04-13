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
package com.celzero.bravedns.data

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

data class PowerProfileDefinition(
    val id: String,
    @StringRes val titleRes: Int? = null,
    @StringRes val descriptionRes: Int? = null,
    @StringRes val metaRes: Int? = null,
    val titleText: String? = null,
    val descriptionText: String? = null,
    val metaText: String? = null,
    @DrawableRes val iconRes: Int,
    val bundledArtifactAssetPath: String? = null,
    val localArtifactFileName: String? = null,
    val localBlocklistTagIds: List<Int> = emptyList(),
    val sourceProvider: String? = null,
    val sourceSummary: String? = null,
    val sourceDocUrl: String? = null,
    val sourceTokens: List<String> = emptyList(),
    val readyForActivation: Boolean = false
) {
    fun resolveTitle(context: Context): String {
        return titleText ?: titleRes?.let(context::getString).orEmpty()
    }

    fun resolveDescription(context: Context): String {
        return descriptionText ?: descriptionRes?.let(context::getString).orEmpty()
    }

    fun resolveDescription(context: Context, maxWords: Int): String {
        return PowerProfileText.truncateWords(resolveDescription(context), maxWords)
    }

    fun resolveMeta(context: Context): String {
        return metaText ?: metaRes?.let(context::getString).orEmpty()
    }
}
