package com.celzero.bravedns.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.celzero.bravedns.database.WgConfigFiles
import com.celzero.bravedns.database.WgConfigFilesDAO
import io.mockk.mockk
import io.mockk.verify
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
class WgConfigViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()
    private val wgConfigFilesDAO: WgConfigFilesDAO = mockk(relaxed = true)
    private lateinit var viewModel: WgConfigViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        viewModel = WgConfigViewModel(wgConfigFilesDAO)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `test insert calls DAO`() {
        val config = WgConfigFiles(
            name = "Test",
            configPath = "/path",
            serverResponse = "{}",
            isActive = false,
            isCatchAll = false,
            isLockdown = false,
            oneWireGuard = false,
            useOnlyOnMetered = false,
            isDeletable = true
        )
        viewModel.insert(config)
        verify { wgConfigFilesDAO.insert(config) }
    }
}
