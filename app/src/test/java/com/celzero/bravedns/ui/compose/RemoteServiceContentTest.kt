/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.rpn.RemoteServiceContent
import com.celzero.bravedns.ui.compose.rpn.RemoteServiceState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Exercises rendered action semantics, not the live tunnel or clipboard. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class RemoteServiceContentTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var host: ActivityController<ComponentActivity>? = null
    private val shown = mutableStateOf(true)

    @After fun teardown() {
        host?.pause()?.stop()?.destroy()
    }

    private fun show(state: RemoteServiceState) {
        compose.runOnUiThread {
            host = Robolectric.buildActivity(ComponentActivity::class.java).apply {
                get().setTheme(R.style.AppThemeWhite)
                create().start().resume().visible()
                get().setContent { RethinkTheme { if (shown.value) RemoteServiceContent(state) } }
            }
        }
        compose.waitForIdle()
    }

    @Test fun busyStartStopRejectsRepeatedTapThenAllowsNextAction() {
        val state = RemoteServiceState()
        var calls = 0
        state.rmtStartStop.isVisible = true
        state.rmtStartStop.text = "Start"
        state.rmtStartStop.setOnClickListener { calls++; state.busy = true }
        show(state)
        compose.onNodeWithTag("remote_service_start_stop").performScrollTo().performClick()
        compose.onNodeWithTag("remote_service_start_stop").assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, calls); state.busy = false; state.rmtStartStop.text = "Stop" }
        compose.onNodeWithTag("remote_service_start_stop").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, calls) }
    }

    @Test fun addressCopyDisappearsOnStoppedStateAndDisposedContentHasNoActions() {
        val state = RemoteServiceState()
        var copies = 0
        state.rmtCopy.setOnClickListener { copies++ }
        show(state)
        compose.onNodeWithTag("remote_service_copy").assertDoesNotExist()
        compose.runOnIdle {
            state.rmtAddr.text = "192.168.1.23:43117"
            state.rmtAddrCard.isVisible = true
        }
        compose.onNodeWithTag("remote_service_address").assertTextEquals("192.168.1.23:43117")
        compose.onNodeWithTag("remote_service_copy").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, copies); state.rmtAddrCard.isVisible = false }
        compose.onNodeWithTag("remote_service_copy").assertDoesNotExist()
        compose.runOnIdle {
            state.rmtAddrCard.isVisible = true
            shown.value = false
        }
        compose.onNodeWithTag("remote_service_copy").assertDoesNotExist()
        compose.onNodeWithTag("remote_service_start_stop").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, copies) }
    }
}
