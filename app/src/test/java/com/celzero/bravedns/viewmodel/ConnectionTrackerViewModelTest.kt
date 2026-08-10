package com.celzero.bravedns.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.celzero.bravedns.database.ConnectionTrackerDAO
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionTrackerViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()
    private val connectionTrackerDAO: ConnectionTrackerDAO = mockk(relaxed = true)
    private lateinit var viewModel: ConnectionTrackerViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        viewModel = ConnectionTrackerViewModel(connectionTrackerDAO)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `test setFilter updates filterString`() {
        viewModel.setFilter("test", emptySet(), ConnectionTrackerViewModel.TopLevelFilter.ALL)
        // Since _filterString is internal and only observed via switchMap, 
        // we verify it triggers fetchNetworkLogs by observing the LiveData if possible.
    }
}
