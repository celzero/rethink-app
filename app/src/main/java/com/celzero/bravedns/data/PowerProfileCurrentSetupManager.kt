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
import com.celzero.bravedns.R
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.RethinkBlocklistManager
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

object PowerProfileCurrentSetupManager : KoinComponent {
    private val persistentState by inject<PersistentState>()

    suspend fun saveCurrentSetupAsImportedProfile(
        context: Context,
        profileOrdinal: Int
    ): PowerProfileDefinition? {
        val localBlocklistTagIds =
            RethinkBlocklistManager.getTagsFromStamp(
                persistentState.localBlocklistStamp,
                RethinkBlocklistManager.RethinkBlocklistType.LOCAL
            ).toList().sorted()
        if (localBlocklistTagIds.isEmpty()) return null

        val now = System.currentTimeMillis()
        val name = context.getString(R.string.power_saved_profile_default_name, profileOrdinal)
        val doc =
            PowerProfilePortableDocument(
                id = "saved-setup-$now",
                name = name,
                description = context.getString(R.string.power_saved_profile_reusable_desc),
                meta =
                    context.getString(
                        R.string.power_saved_profile_reusable_meta,
                        localBlocklistTagIds.size
                    ),
                provider = context.getString(R.string.app_name),
                sourceSummary =
                    context.getString(
                        R.string.power_saved_profile_reusable_summary,
                        localBlocklistTagIds.size
                    ),
                sourceDocUrl = "",
                sourceTokens = listOf("rethink-local-blocklists"),
                generatedAtEpochMs = now,
                supportedRuleKind = "rethink-local-blocklists",
                domains = emptyList(),
                ips = emptyList(),
                localBlocklistTagIds = localBlocklistTagIds
            )
        return ImportedPowerProfileStore.saveDocument(context, doc)
    }
}
