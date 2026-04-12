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

import androidx.test.core.app.ApplicationProvider
import com.celzero.bravedns.database.RethinkLocalFileTag
import com.celzero.bravedns.database.RethinkLocalFileTagRepository
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME
import com.celzero.bravedns.util.Utilities.blocklistDownloadBasePath
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PowerProfileLocalCatalogManagerTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var localFileTagRepository: RethinkLocalFileTagRepository
    private lateinit var persistentState: PersistentState

    @Before
    fun setUp() {
        if (GlobalContext.getOrNull() != null) {
            GlobalContext.stopKoin()
        }
        localFileTagRepository = mockk()
        persistentState = mockk(relaxed = true)
        every { persistentState.localBlocklistTimestamp } returns 0L
        PowerProfileLocalCatalogManager.loadLocalCatalog = { _, _ -> false }

        startKoin {
            modules(
                module {
                    single { localFileTagRepository }
                    single { persistentState }
                }
            )
        }
    }

    @After
    fun tearDown() {
        File(context.filesDir, LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME).deleteRecursively()
        PowerProfileLocalCatalogManager.loadLocalCatalog = { _, _ -> false }
        if (GlobalContext.getOrNull() != null) {
            GlobalContext.stopKoin()
        }
        unmockkAll()
    }

    @Test
    fun hasCatalog_returnsTrueWhenMatchingTagsExist() {
        coEvery { localFileTagRepository.getTagsByIds(listOf(1, 2)) } returns
            listOf(
                RethinkLocalFileTag(
                    value = 1,
                    uname = "tag-1",
                    vname = "Tag 1",
                    group = "privacy",
                    subg = "others",
                    pack = null,
                    level = null,
                    url = emptyList(),
                    show = 1,
                    entries = 1,
                    simpleTagId = 1
                )
            )

        val result = kotlinx.coroutines.runBlocking {
            PowerProfileLocalCatalogManager.hasCatalog(listOf(1, 2))
        }

        assertTrue(result)
    }

    @Test
    fun hydrateIfDownloaded_returnsFalseWhenTimestampMissing() {
        every { persistentState.localBlocklistTimestamp } returns 0L

        val result = kotlinx.coroutines.runBlocking {
            PowerProfileLocalCatalogManager.hydrateIfDownloaded(context)
        }

        assertFalse(result)
    }

    @Test
    fun hydrateIfDownloaded_loadsCatalogWhenFilesExist() {
        val timestamp = 12345L
        every { persistentState.localBlocklistTimestamp } returns timestamp
        PowerProfileLocalCatalogManager.loadLocalCatalog = { _, ts -> ts == timestamp }
        val dir = File(blocklistDownloadBasePath(context, LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME, timestamp))
        dir.mkdirs()
        Constants.ONDEVICE_BLOCKLISTS_ADM.forEach {
            File(dir, it.filename).writeText("{}")
        }

        val result = kotlinx.coroutines.runBlocking {
            PowerProfileLocalCatalogManager.hydrateIfDownloaded(context)
        }

        assertTrue(result)
    }
}
