package com.celzero.bravedns.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.celzero.bravedns.database.ConnectionTrackerDAO
import com.celzero.bravedns.database.StatsSummaryDao
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DetailedStatisticsViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val connectionTrackerDAO: ConnectionTrackerDAO = mockk(relaxed = true)
    private val statsDao: StatsSummaryDao = mockk(relaxed = true)

    @Test
    fun `timeCategoryChanged preserves supplied summary cutoff`() {
        val viewModel = DetailedStatisticsViewModel(connectionTrackerDAO, statsDao)
        val summaryCutoff = 1_700_000_000_000L

        viewModel.timeCategoryChanged(
            SummaryStatisticsViewModel.TimeCategory.SEVEN_DAYS,
            summaryCutoff
        )

        assertEquals(summaryCutoff, viewModel.getStartTime())
    }
}
