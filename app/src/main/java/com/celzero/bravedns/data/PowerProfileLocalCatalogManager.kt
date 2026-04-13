/*
 * Copyright 2026 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package com.celzero.bravedns.data

import android.content.Context
import com.celzero.bravedns.database.RethinkLocalFileTagRepository
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.RethinkBlocklistManager
import com.celzero.bravedns.util.Utilities.hasLocalBlocklists
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

object PowerProfileLocalCatalogManager : KoinComponent {
    private val localFileTagRepository by inject<RethinkLocalFileTagRepository>()
    private val persistentState by inject<PersistentState>()

    internal var loadLocalCatalog: suspend (Context, Long) -> Boolean =
        { context, timestamp ->
            RethinkBlocklistManager.readJson(
                context,
                RethinkBlocklistManager.DownloadType.LOCAL,
                timestamp
            )
        }

    suspend fun hasCatalog(localBlocklistTagIds: List<Int>): Boolean {
        if (localBlocklistTagIds.isEmpty()) return true
        return localFileTagRepository.getTagsByIds(localBlocklistTagIds).isNotEmpty()
    }

    fun currentTimestamp(): Long {
        return persistentState.localBlocklistTimestamp
    }

    fun hasDownloadedFiles(context: Context): Boolean {
        val timestamp = currentTimestamp()
        if (timestamp <= 0L) return false
        return hasLocalBlocklists(context, timestamp)
    }

    suspend fun hydrateIfDownloaded(context: Context): Boolean {
        val timestamp = currentTimestamp()
        if (timestamp <= 0L) return false
        if (!hasLocalBlocklists(context, timestamp)) return false
        return loadLocalCatalog(context, timestamp)
    }
}
