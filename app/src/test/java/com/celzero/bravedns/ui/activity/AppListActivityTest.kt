/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.activity

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.paging.PagingData
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.database.RefreshDatabase
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.viewmodel.AppInfoViewModel
import io.mockk.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Original actual-host list contract, now isolated. No populated row may initialize FirewallManager.
 * The actual list adapter, Activity filtering and saved-instance search listeners remain mounted.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w360dp-h800dp")
class AppListActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<AppListActivity>
    private val data = MutableLiveData<PagingData<AppInfo>>()
    private val queries = mutableListOf<String>()
    private lateinit var refresh: RefreshDatabase
    private lateinit var events: EventLogger
    @Before fun setUp() {
        stopKoin()
        val prefs = mockk<PersistentState>()
        val vm = mockk<AppInfoViewModel>()
        // Only framework cleanup is a no-op: this strict, constructor-free VM has no resources.
        every { ViewModel::class.java.getDeclaredMethod("clear\$lifecycle_viewmodel").invoke(vm) } returns null
        refresh = mockk(); events = mockk()
        every { prefs.theme } returns Themes.LIGHT.id
        every { vm.appInfo } returns data
        every { vm.setFilter(any()) } answers { queries += firstArg<AppListActivity.Filters>().searchString }
        startKoin { modules(module { single { prefs }; single { vm }; single { refresh }; single { events } }) }
        host = Robolectric.buildActivity(AppListActivity::class.java).setup().visible()
        compose.waitForIdle()
    }
    @After fun tearDown() {
        try {
            if (::host.isInitialized && !host.get().isDestroyed) host.pause().stop().destroy()
            verify { listOf(refresh, events) wasNot Called }
        } finally {
            stopKoin()
        }
    }
    @Test fun testAppListDisplay() {
        compose.onNodeWithTag("ffa_app_list").assertIsDisplayed()
        compose.onNodeWithTag("firewall_search").assertIsDisplayed()
        assertTrue(queries.isNotEmpty())
    }
    @Test fun emptyMemoryPagingKeepsNativeListMounted() {
        compose.runOnIdle { data.value = PagingData.empty() }
        compose.onNodeWithTag("ffa_app_list").assertIsDisplayed()
        assertTrue(host.get().supportFragmentManager.fragments.isEmpty())
    }
    @Test fun actualActivityRecreationRestoresSearchText() {
        compose.onNodeWithTag("firewall_search").performTextReplacement("synthetic.example")
        host.recreate()
        compose.onNodeWithTag("firewall_search").assertTextContains("synthetic.example")
    }
}
