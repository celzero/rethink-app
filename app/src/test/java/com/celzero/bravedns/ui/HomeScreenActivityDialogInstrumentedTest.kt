/* Copyright 2025 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui

import android.app.Application
import android.view.MotionEvent
import androidx.compose.ui.test.*
import com.celzero.bravedns.R
import com.celzero.bravedns.service.AppUpdater
import com.celzero.bravedns.ui.fixture.SafeRootHostTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/** Seven original instrumented cases relocated to a safe actual Home host/native dialog.
 * This does not stand in for device TalkBack, screenshot or platform accessibility checks.
 * No hand-constructed MaterialAlertDialog or sleeps remain; production showDownloadDialog is used.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w360dp-h800dp")
class HomeScreenActivityDialogInstrumentedTest : SafeRootHostTest() {
    private fun show(update: Boolean = true) {
        launch()
        compose.runOnIdle {
            val title = activity.getString(if (update) R.string.download_update_dialog_title else R.string.download_update_dialog_message_ok_title)
            val message = activity.getString(if (update) R.string.download_update_dialog_message else R.string.download_update_dialog_message_ok)
            HomeScreenActivity::class.java.getDeclaredMethod("showDownloadDialog", AppUpdater.InstallSource::class.java, String::class.java, String::class.java)
                .apply { isAccessible = true }.invoke(activity, AppUpdater.InstallSource.STORE, title, message)
        }
        compose.onNodeWithTag("root_dialog_positive").assertIsDisplayed()
    }
    private fun touchOutside() {
        compose.runOnIdle {
            val dialog = checkNotNull(ShadowDialog.getLatestDialog())
            // Compose 1.12 tracks an outside press/release pair, not ACTION_OUTSIDE.
            // Exercise the actual window callback without directly dismissing the dialog.
            for ((action, time) in listOf(MotionEvent.ACTION_DOWN to 0L, MotionEvent.ACTION_UP to 16L)) {
                val event = MotionEvent.obtain(0, time, action, -100f, -100f, 0)
                try { dialog.onTouchEvent(event) } finally { event.recycle() }
            }
        }
    }
    @Test fun testModalUpdateDialog_CannotBeDismissedByTappingOutside() {
        show(); touchOutside()
        compose.onNodeWithTag("root_dialog_positive").assertIsDisplayed()
        compose.onNodeWithTag("root_dialog_negative").assertIsDisplayed()
        assertFalse(calls.contains("complete-update"))
    }
    @Test fun testDismissibleDialog_CanBeDismissed() {
        show(false); touchOutside()
        compose.onNodeWithTag("root_dialog_positive").assertDoesNotExist()
        assertFalse(calls.contains("complete-update"))
    }
    @Test fun testUpdateDialog_HasBothButtons() {
        show()
        compose.onNodeWithTag("root_dialog_positive").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("root_dialog_negative").assertIsDisplayed().assertHasClickAction()
    }
    @Test fun testButtonClick_DismissesDialog() {
        show()
        compose.onNodeWithTag("root_dialog_positive").performClick()
        assertEquals(1, calls.count { it == "complete-update" })
        compose.onNodeWithTag("root_dialog_positive").assertDoesNotExist()
    }
    @Test fun testModalDialog_StaysOpenWithoutButtonClick() {
        show()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("root_dialog_positive").assertIsDisplayed()
        assertFalse(calls.contains("complete-update"))
        assertFalse(calls.contains("postpone-update"))
    }
    @Test fun testNegativeButton_WorksCorrectly() {
        show()
        compose.onNodeWithTag("root_dialog_negative").performClick()
        assertEquals(1, calls.count { it == "postpone-update" })
        assertFalse(calls.contains("complete-update"))
        compose.onNodeWithTag("root_dialog_positive").assertDoesNotExist()
    }
    @Test fun testInformationalDialog_OnlyHasOkButton() {
        show(false)
        compose.onNodeWithTag("root_dialog_positive").assertTextEquals(activity.getString(R.string.hs_download_positive_default))
        compose.onNodeWithTag("root_dialog_negative").assertDoesNotExist()
        compose.onNodeWithTag("root_dialog_positive").performClick()
        compose.onNodeWithTag("root_dialog_positive").assertDoesNotExist()
    }
}
