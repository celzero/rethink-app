/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.bottomsheet

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.fragment.app.FragmentActivity
import com.celzero.bravedns.R
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.PcapMode
import io.mockk.every
import io.mockk.mockk
import org.junit.*
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PcapFilesComposeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var host: ActivityController<FragmentActivity>? = null
    private var persistedMode = PcapMode.NONE

    @Before fun setup() {
        val persistent = mockk<PersistentState>(relaxed = true)
        every { persistent.theme } returns 0
        every { persistent.pcapMode } answers { persistedMode.id }
        stopKoin()
        startKoin { modules(module { single<PersistentState> { persistent } }) }
    }

    @After fun teardown() {
        host?.pause()?.stop()?.destroy()
        stopKoin()
    }

    private fun show(onPick: (PcapMode) -> Unit) {
        compose.runOnUiThread {
            host = Robolectric.buildActivity(FragmentActivity::class.java).apply {
                get().setTheme(R.style.AppThemeWhite)
                create().start().resume().visible()
            }
            PcapFilesBottomSheet().apply {
                modeSelectListener = object : PcapFilesBottomSheet.PcapModeSelectListener {
                    override fun onPcapModeSelected(mode: PcapMode) = onPick(mode)
                }
            }.showNow(checkNotNull(host).get().supportFragmentManager, "pcap_test")
        }
        compose.waitForIdle()
    }

    @Test fun rejectedFileModeResyncsToPersistedMode() {
        var calls = 0
        show { calls++ }
        compose.onNodeWithTag("pcap_mode_NONE").assertIsSelected()
        compose.onNodeWithTag("pcap_mode_EXTERNAL_FILE").performClick().assertIsNotSelected()
        compose.onNodeWithTag("pcap_mode_NONE").assertIsSelected()
        Assert.assertEquals(1, calls)
    }

    @Test fun selectingCurrentModeDoesNotRepeatCallback() {
        var calls = 0
        show { persistedMode = it; calls++ }
        compose.onNodeWithTag("pcap_mode_LOGCAT").performClick().assertIsSelected().performClick()
        Assert.assertEquals(1, calls)
        Assert.assertEquals(PcapMode.LOGCAT, persistedMode)
    }
}
