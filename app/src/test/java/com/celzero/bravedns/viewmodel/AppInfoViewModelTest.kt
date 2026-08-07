package com.celzero.bravedns.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.celzero.bravedns.database.AppInfoDAO
import com.celzero.bravedns.ui.activity.AppListActivity
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.milliseconds
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AppInfoViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()
    private val appInfoDAO: AppInfoDAO = mockk(relaxed = true)
    private lateinit var viewModel: AppInfoViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        viewModel = AppInfoViewModel(appInfoDAO)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `test setFilter triggers debounce`() = runTest {
        val filters = AppListActivity.Filters().apply {
            searchString = "test"
        }
        
        viewModel.setFilter(filters)
        
        // Before debounce delay
        advanceTimeBy(100.milliseconds)
        // Wait for debounce delay (300ms)
        advanceTimeBy(300.milliseconds)
    }
}
