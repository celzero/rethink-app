package com.celzero.bravedns.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.celzero.bravedns.database.ConnectionTrackerDAO
import com.celzero.bravedns.database.StatsSummaryDao
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SummaryStatisticsViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()
    private val connectionTrackerDAO: ConnectionTrackerDAO = mockk(relaxed = true)
    private val statsDao: StatsSummaryDao = mockk(relaxed = true)
    private lateinit var viewModel: SummaryStatisticsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        viewModel = SummaryStatisticsViewModel(connectionTrackerDAO, statsDao)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `test timeCategoryChanged updates startTime`() {
        val initialStartTime = viewModel.getTimeCategory()
        assertEquals(SummaryStatisticsViewModel.TimeCategory.ONE_HOUR, initialStartTime)
        
        viewModel.timeCategoryChanged(SummaryStatisticsViewModel.TimeCategory.TWENTY_FOUR_HOUR)
        assertEquals(SummaryStatisticsViewModel.TimeCategory.TWENTY_FOUR_HOUR, viewModel.getTimeCategory())
    }
}
