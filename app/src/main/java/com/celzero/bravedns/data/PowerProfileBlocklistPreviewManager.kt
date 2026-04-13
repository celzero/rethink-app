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
import com.celzero.bravedns.database.RethinkLocalFileTag
import com.celzero.bravedns.database.RethinkLocalFileTagRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

data class PowerProfileBlocklistPreviewGroup(
    val key: String,
    val label: String,
    val description: String,
    val entries: List<RethinkLocalFileTag>
)

object PowerProfileBlocklistPreviewManager : KoinComponent {
    private val localFileTagRepository by inject<RethinkLocalFileTagRepository>()

    suspend fun loadLocalGroups(
        context: Context,
        localBlocklistTagIds: List<Int>
    ): List<PowerProfileBlocklistPreviewGroup> {
        if (localBlocklistTagIds.isEmpty()) return emptyList()
        val tagsById = localFileTagRepository.getTagsByIds(localBlocklistTagIds).associateBy { it.value }
        val orderedTags = localBlocklistTagIds.mapNotNull(tagsById::get)
        return orderedTags
            .groupBy { it.group.lowercase() }
            .map { (groupKey, entries) ->
                PowerProfileBlocklistPreviewGroup(
                    key = groupKey,
                    label = groupLabel(context, groupKey),
                    description = groupDescription(context, groupKey),
                    entries = entries.sortedWith(compareBy({ it.subg.lowercase() }, { it.vname.lowercase() }))
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun groupLabel(context: Context, key: String): String {
        return when (key) {
            "parentalcontrol" -> context.getString(R.string.rbl_parental_control)
            "security" -> context.getString(R.string.rbl_security)
            "privacy" -> context.getString(R.string.rbl_privacy)
            else -> key.replaceFirstChar { it.titlecase() }
        }
    }

    private fun groupDescription(context: Context, key: String): String {
        return when (key) {
            "parentalcontrol" -> context.getString(R.string.rbl_parental_control_desc)
            "security" -> context.getString(R.string.rbl_security_desc)
            "privacy" -> context.getString(R.string.rbl_privacy_desc)
            else -> ""
        }
    }
}
