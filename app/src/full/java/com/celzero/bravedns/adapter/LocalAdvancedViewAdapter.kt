/*
 * Copyright 2023 RethinkDNS and its authors
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
package com.celzero.bravedns.adapter

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.celzero.bravedns.database.RethinkLocalFileTag
import com.celzero.bravedns.util.UIUtils.openUrl

@Composable
fun LocalAdvancedBlocklistRow(
    filetag: RethinkLocalFileTag,
    showHeader: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val context = LocalContext.current
    BlocklistAdvancedRow(
        group = filetag.group,
        subGroup = filetag.subg,
        name = filetag.vname,
        entries = filetag.entries,
        level = filetag.level?.firstOrNull(),
        entryUrl = filetag.url.firstOrNull(),
        isSelected = filetag.isSelected,
        showHeader = showHeader,
        onToggle = onToggle,
        onEntryClick = { url -> openUrl(context, url) }
    )
}
