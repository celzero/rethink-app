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
import java.io.File

object PowerProfileArtifacts {
    fun loadArtifact(context: Context, profile: PowerProfileDefinition): BundledDomainProfileArtifact? {
        return loadRawArtifact(context, profile)?.let(BundledDomainProfileArtifact::fromJson)
    }

    fun loadPortableDocument(context: Context, profile: PowerProfileDefinition): PowerProfilePortableDocument? {
        val raw = loadRawArtifact(context, profile) ?: return null
        val artifact = BundledDomainProfileArtifact.fromJson(raw)
        return PowerProfilePortableDocument(
            id = profile.id,
            name = profile.resolveTitle(context),
            description = profile.resolveDescription(context),
            meta = profile.resolveMeta(context),
            provider = profile.sourceProvider.orEmpty(),
            sourceSummary = profile.sourceSummary.orEmpty(),
            sourceDocUrl = profile.sourceDocUrl.orEmpty(),
            sourceTokens = profile.sourceTokens,
            generatedAtEpochMs = artifact.generatedAtEpochMs,
            supportedRuleKind = artifact.supportedRuleKind,
            domains = artifact.domains,
            ips = artifact.ips
        )
    }

    private fun loadRawArtifact(context: Context, profile: PowerProfileDefinition): String? {
        profile.bundledArtifactAssetPath?.let { assetPath ->
            return context.assets.open(assetPath).bufferedReader().use { it.readText() }
        }
        profile.localArtifactFileName?.let { fileName ->
            val file = File(File(context.filesDir, "power-imported-profiles"), fileName)
            if (file.exists()) return file.readText()
        }
        return null
    }
}
