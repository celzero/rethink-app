package com.celzero.bravedns.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.celzero.bravedns.database.DnsLogDAO
import com.celzero.bravedns.ui.fragment.DnsLogFragment
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class DnsLogViewModelTest {

    @get:Rule
    var rule: TestRule = InstantTaskExecutorRule()

    // Unconfined so the switchMap -> Pager chain runs eagerly on setFilter/observe
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var viewModel: DnsLogViewModel
    private val dnsLogDAO: DnsLogDAO = mockk(relaxed = true)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        viewModel = DnsLogViewModel(dnsLogDAO)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `test setFilter triggers DAO call`() {
        viewModel.setFilter("example", DnsLogFragment.DnsLogFilter.ALL)

        // Observe dnsLogsList to trigger the switchMap; the Pager invokes the DAO
        // lazily on its load dispatcher, so wait for the async call
        viewModel.dnsLogsList.observeForever {}

        verify(timeout = 5000) { dnsLogDAO.getDnsLogsByName("%example%") }
    }

    @Test
    fun `test setFilter with empty search triggers getAllDnsLogs`() {
        viewModel.setFilter("", DnsLogFragment.DnsLogFilter.ALL)

        viewModel.dnsLogsList.observeForever {}

        verify(timeout = 5000) { dnsLogDAO.getAllDnsLogs() }
    }

    @Test
    fun `test setFilter with ALLOWED type`() {
        viewModel.setFilter("example", DnsLogFragment.DnsLogFilter.ALLOWED)

        viewModel.dnsLogsList.observeForever {}

        verify(timeout = 5000) { dnsLogDAO.getAllowedDnsLogsByName("%example%") }
    }
}
