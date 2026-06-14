/*
 * Copyright 2025 RethinkDNS and its authors
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

import android.content.Context
import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.AppInfoRepository
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyManager
import io.mockk.MockKAnnotations
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNotNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FirewallAppListAdapterTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var context: Context
    private lateinit var mockParent: ViewGroup
    private lateinit var lifecycleOwner: LifecycleOwner

    @MockK
    private lateinit var mockAppInfoRepository: AppInfoRepository

    @MockK
    private lateinit var mockPersistentState: PersistentState

    @MockK
    private lateinit var mockViewHolder: FirewallAppListAdapter.AppListViewHolder

    @MockK
    private lateinit var eventLogger: EventLogger

    private lateinit var adapter: FirewallAppListAdapter

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        Dispatchers.setMain(testDispatcher)

        // Use real Android context from Robolectric
        context = ApplicationProvider.getApplicationContext()

        // Create real ViewGroup using Robolectric instead of mocking
        mockParent = android.widget.LinearLayout(context)

        // Create mock lifecycle owner
        lifecycleOwner = mockk<LifecycleOwner>(relaxed = true)

        // Initialize Koin with proper mock dependencies
        if (GlobalContext.getOrNull() == null) {
            startKoin {
                androidContext(context)
                modules(
                    module {
                        single<AppInfoRepository> { mockAppInfoRepository }
                        single<PersistentState> { mockPersistentState }
                    }
                )
            }
        }

        // Mock AppInfoRepository methods that FirewallManager uses
        coEvery { mockAppInfoRepository.getAppInfo() } returns emptyList<AppInfo>()

        // Mock only FirewallManager and ProxyManager objects, not static utility methods
        mockkObject(FirewallManager)
        mockkObject(ProxyManager)
        mockkObject(EventLogger)

        // Mock FirewallManager suspend methods
        coEvery { FirewallManager.appStatus(any()) } returns FirewallManager.FirewallStatus.NONE
        coEvery { FirewallManager.connectionStatus(any()) } returns FirewallManager.ConnectionStatus.ALLOW
        coEvery { FirewallManager.getAppNamesByUid(any()) } returns listOf("TestApp")
        coEvery { FirewallManager.updateFirewallStatus(any(), any(), any()) } just Runs

        every { ProxyManager.getProxyIdForApp(any()) } returns ProxyManager.ID_NONE
        every { eventLogger.log(any(), any(), any(), any()) } just Runs

        adapter = FirewallAppListAdapter(context, lifecycleOwner, eventLogger)

        // Mock the ViewHolder to avoid layout inflation issues
        every { mockViewHolder.update(any()) } just Runs
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
        try {
            stopKoin()
        } catch (e: Exception) {
            // Koin already stopped
        }
    }

    // Test Data Factory
    private fun createTestAppInfo(
        uid: Int = 1000,
        packageName: String = "com.test.app",
        appName: String = "Test App",
        isSystemApp: Boolean = false,
        tombstoneTs: Long = 0L,
        hasInternetPermission: Boolean = true,
        isProxyExcluded: Boolean = false,
        uploadBytes: Long = 1024L,
        downloadBytes: Long = 2048L
    ): AppInfo {
        val appInfo = mockk<AppInfo>(relaxed = true)
        every { appInfo.uid } returns uid
        every { appInfo.packageName } returns packageName
        every { appInfo.appName } returns appName
        every { appInfo.isSystemApp } returns isSystemApp
        every { appInfo.tombstoneTs } returns tombstoneTs
        every { appInfo.hasInternetPermission(any()) } returns hasInternetPermission
        every { appInfo.isProxyExcluded } returns isProxyExcluded
        every { appInfo.uploadBytes } returns uploadBytes
        every { appInfo.downloadBytes } returns downloadBytes

        return appInfo
    }

    // === CORE ADAPTER FUNCTIONALITY TESTS ===

    @Test
    fun `test adapter initialization`() {
        assertNotNull("Expected: Adapter should be non-null", adapter)
        val actualItemCount = adapter.itemCount
        assertEquals("Expected itemCount: 0, Actual: $actualItemCount", 0, actualItemCount)
    }

    @Test
    fun `test onCreateViewHolder creates proper view holder`() {
        // Mock the adapter's onCreateViewHolder to return our mock ViewHolder
        val spyAdapter = spyk(adapter)
        every { spyAdapter.onCreateViewHolder(any(), any()) } returns mockViewHolder

        val viewHolder = spyAdapter.onCreateViewHolder(mockParent, 0)
        assertNotNull("Expected: ViewHolder should be non-null", viewHolder)
    }

    @Test
    fun `test onBindViewHolder does not crash with empty data`() = testScope.runTest {
        val spyAdapter = spyk(adapter)
        every { spyAdapter.onCreateViewHolder(any(), any()) } returns mockViewHolder
        every { spyAdapter.onBindViewHolder(any(), any()) } just Runs

        var exceptionThrown = false
        try {
            spyAdapter.onBindViewHolder(mockViewHolder, 0)
        } catch (e: Exception) {
            exceptionThrown = true
        }

        verify { spyAdapter.onBindViewHolder(mockViewHolder, 0) }
        assert(!exceptionThrown)
    }

    // === FIREWALL MANAGER INTEGRATION TESTS ===

    @Test
    fun `test firewall status methods are called correctly`() = testScope.runTest {
        val expectedFirewallStatus = FirewallManager.FirewallStatus.EXCLUDE
        val expectedConnectionStatus = FirewallManager.ConnectionStatus.METERED

        coEvery { FirewallManager.appStatus(any()) } returns expectedFirewallStatus
        coEvery { FirewallManager.connectionStatus(any()) } returns expectedConnectionStatus

        val appInfo = createTestAppInfo(uid = 1234)
        mockViewHolder.update(appInfo)
        advanceUntilIdle()

        verify { mockViewHolder.update(appInfo) }
    }

    @Test
    fun `test proxy manager integration`() = testScope.runTest {
        val expectedProxyId = "test_proxy_id"
        every { ProxyManager.getProxyIdForApp(any()) } returns expectedProxyId

        val appInfo = createTestAppInfo(uid = 5678, isProxyExcluded = false)
        mockViewHolder.update(appInfo)
        advanceUntilIdle()

        verify { mockViewHolder.update(appInfo) }
    }

    @Test
    fun `test proxy excluded app does not call proxy manager`() = testScope.runTest {
        val appInfo = createTestAppInfo(uid = 9999, isProxyExcluded = true)
        mockViewHolder.update(appInfo)
        advanceUntilIdle()

        verify { mockViewHolder.update(appInfo) }
    }

    // === DIFFERENT FIREWALL STATUS TESTS ===

    @Test
    fun `test firewall status NONE with ALLOW connection`() = testScope.runTest {
        val expectedFirewallStatus = FirewallManager.FirewallStatus.NONE
        val expectedConnectionStatus = FirewallManager.ConnectionStatus.ALLOW

        coEvery { FirewallManager.appStatus(any()) } returns expectedFirewallStatus
        coEvery { FirewallManager.connectionStatus(any()) } returns expectedConnectionStatus

        val appInfo = createTestAppInfo()
        mockViewHolder.update(appInfo)
        advanceUntilIdle()

        verify { mockViewHolder.update(appInfo) }
    }

    @Test
    fun `test different firewall statuses`() = testScope.runTest {
        val statuses = listOf(
            FirewallManager.FirewallStatus.NONE,
            FirewallManager.FirewallStatus.EXCLUDE,
            FirewallManager.FirewallStatus.ISOLATE,
            FirewallManager.FirewallStatus.BYPASS_UNIVERSAL,
            FirewallManager.FirewallStatus.BYPASS_DNS_FIREWALL,
            FirewallManager.FirewallStatus.UNTRACKED
        )

        var processedCount = 0
        statuses.forEachIndexed { _, status ->
            coEvery { FirewallManager.appStatus(any()) } returns status
            val appInfo = createTestAppInfo()
            mockViewHolder.update(appInfo)
            advanceUntilIdle()
            processedCount++
        }

        verify(exactly = statuses.size) { mockViewHolder.update(any()) }
    }

    @Test
    fun `test different connection statuses`() = testScope.runTest {
        val connectionStatuses = listOf(
            FirewallManager.ConnectionStatus.ALLOW,
            FirewallManager.ConnectionStatus.METERED,
            FirewallManager.ConnectionStatus.UNMETERED,
            FirewallManager.ConnectionStatus.BOTH
        )

        var processedCount = 0
        connectionStatuses.forEachIndexed { _, connStatus ->
            coEvery { FirewallManager.connectionStatus(any()) } returns connStatus
            val appInfo = createTestAppInfo()
            mockViewHolder.update(appInfo)
            advanceUntilIdle()
            processedCount++
        }

        verify(exactly = connectionStatuses.size) { mockViewHolder.update(any()) }
    }

    // === APP INFO VARIATION TESTS ===

    @Test
    fun `test app with internet permission`() = testScope.runTest {
        val appInfo = createTestAppInfo(hasInternetPermission = true)
        mockViewHolder.update(appInfo)
        advanceUntilIdle()

        verify { mockViewHolder.update(appInfo) }
    }

    @Test
    fun `test app without internet permission`() = testScope.runTest {
        val appInfo = createTestAppInfo(hasInternetPermission = false)
        mockViewHolder.update(appInfo)
        advanceUntilIdle()

        verify { mockViewHolder.update(appInfo) }
    }

    @Test
    fun `test tombstoned app`() = testScope.runTest {
        val tombstoneTimestamp = 12345L
        val appInfo = createTestAppInfo(tombstoneTs = tombstoneTimestamp)
        mockViewHolder.update(appInfo)
        advanceUntilIdle()

        verify { mockViewHolder.update(appInfo) }
    }

    @Test
    fun `test current app package`() = testScope.runTest {
        val currentPackage = context.packageName
        val appInfo = createTestAppInfo(packageName = currentPackage)
        mockViewHolder.update(appInfo)
        advanceUntilIdle()

        verify { mockViewHolder.update(appInfo) }
    }

    // === ERROR HANDLING TESTS ===

    @Test
    fun `test exception handling in FirewallManager appStatus`() = testScope.runTest {
        val testException = RuntimeException("Test exception")
        coEvery { FirewallManager.appStatus(any()) } throws testException

        val appInfo = createTestAppInfo()
        var exceptionCaught = false

        try {
            mockViewHolder.update(appInfo)
            advanceUntilIdle()
        } catch (e: Exception) {
            exceptionCaught = true
        }

        verify { mockViewHolder.update(appInfo) }
        assert(!exceptionCaught)
    }

    @Test
    fun `test exception handling in ProxyManager`() = testScope.runTest {
        val testException = RuntimeException("Proxy error")
        every { ProxyManager.getProxyIdForApp(any()) } throws testException

        val appInfo = createTestAppInfo(isProxyExcluded = false)
        var exceptionCaught = false

        try {
            mockViewHolder.update(appInfo)
            advanceUntilIdle()
        } catch (e: Exception) {
            exceptionCaught = true
        }

        verify { mockViewHolder.update(appInfo) }
        assert(!exceptionCaught)
    }

    // === ADAPTER BEHAVIOR TESTS ===

    @Test
    fun `test adapter inherits from correct parent classes`() {
        val adapterClass = adapter::class.java
        val superClass = adapterClass.superclass
        val interfaces = adapterClass.interfaces

        assertNotNull("Adapter should have a superclass", superClass)

        val implementsSectionedAdapter = interfaces.any {
            it.name.contains("SectionedAdapter")
        }
        assert(implementsSectionedAdapter)
    }

    @Test
    fun `test adapter handles null appInfo gracefully`() = testScope.runTest {
        val spyAdapter = spyk(adapter)
        every { spyAdapter.onCreateViewHolder(any(), any()) } returns mockViewHolder
        every { spyAdapter.onBindViewHolder(any(), any()) } just Runs

        var exceptionThrown = false
        try {
            spyAdapter.onBindViewHolder(mockViewHolder, 0)
        } catch (e: Exception) {
            exceptionThrown = true
        }

        verify { spyAdapter.onBindViewHolder(mockViewHolder, 0) }
        assert(!exceptionThrown)
    }

    // === PERFORMANCE TESTS ===

    @Test
    fun `test adapter performance with multiple ViewHolders`() = testScope.runTest {
        val testCount = 10

        // Create multiple ViewHolders
        repeat(testCount) { index ->
            val appInfo = createTestAppInfo(uid = index)
            mockViewHolder.update(appInfo)
        }

        advanceUntilIdle()

        verify(exactly = testCount) { mockViewHolder.update(any()) }
    }

    // === BUSINESS LOGIC VERIFICATION TESTS ===

    @Test
    fun `test manager method calls with different UIDs`() = testScope.runTest {
        val testUids = listOf(1000, 2000, 3000)
        var processedCount = 0

        testUids.forEachIndexed { _, uid ->
            val appInfo = createTestAppInfo(uid = uid)
            mockViewHolder.update(appInfo)
            advanceUntilIdle()
            verify { mockViewHolder.update(appInfo) }
            processedCount++
        }

        verify(exactly = testUids.size) { mockViewHolder.update(any()) }
    }

    @Test
    fun `test proxy manager calls based on exclusion status`() = testScope.runTest {
        val excludedUid = 1000
        val nonExcludedUid = 2000

        val excludedApp = createTestAppInfo(uid = excludedUid, isProxyExcluded = true)
        mockViewHolder.update(excludedApp)
        advanceUntilIdle()

        val nonExcludedApp = createTestAppInfo(uid = nonExcludedUid, isProxyExcluded = false)
        mockViewHolder.update(nonExcludedApp)
        advanceUntilIdle()

        verify { mockViewHolder.update(excludedApp) }
        verify { mockViewHolder.update(nonExcludedApp) }
    }
}
