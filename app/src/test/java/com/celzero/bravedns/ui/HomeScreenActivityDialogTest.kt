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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/** The eight original update-dialog cases now invoke the actual Home controller and native window. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w360dp-h800dp")
class HomeScreenActivityDialogTest : SafeRootHostTest() {
    private fun show(source: AppUpdater.InstallSource, title: Int, message: Int) {
        launch()
        compose.runOnIdle {
            HomeScreenActivity::class.java.getDeclaredMethod("showDownloadDialog", AppUpdater.InstallSource::class.java, String::class.java, String::class.java)
                .apply { isAccessible = true }.invoke(activity, source, activity.getString(title), activity.getString(message))
        }
        compose.onNodeWithTag("root_dialog_positive").assertIsDisplayed()
    }
    private fun update(source: AppUpdater.InstallSource = AppUpdater.InstallSource.OTHER) =
        show(source, R.string.download_update_dialog_title, R.string.download_update_dialog_message)
    private fun outsideTouch() {
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

    @Test fun `test update available dialog is modal and has correct buttons for website`() {
        update()
        compose.onNodeWithTag("root_dialog_positive").assertTextEquals(activity.getString(R.string.hs_download_positive_website))
        compose.onNodeWithTag("root_dialog_negative").assertTextEquals(activity.getString(R.string.hs_download_negative_default))
        outsideTouch()
        compose.onNodeWithTag("root_dialog_positive").assertIsDisplayed()
        assertNull(shadowOf(activity).nextStartedActivity)
    }
    @Test fun `test update available dialog is modal and has correct buttons for Play Store`() {
        update(AppUpdater.InstallSource.STORE)
        compose.onNodeWithText("A new version is available. Please update from Play Store.").assertIsDisplayed()
        compose.onNodeWithTag("root_dialog_positive").assertTextEquals(activity.getString(R.string.hs_download_positive_default))
        compose.onNodeWithTag("root_dialog_negative").assertIsDisplayed()
        compose.onNodeWithTag("root_dialog_positive").performClick()
        assertEquals(1, calls.count { it == "complete-update" })
        compose.onNodeWithTag("root_dialog_positive").assertDoesNotExist()
    }
    @Test fun `test up-to-date dialog is dismissible with only OK button`() {
        show(AppUpdater.InstallSource.OTHER, R.string.download_update_dialog_message_ok_title, R.string.download_update_dialog_message_ok)
        compose.onNodeWithTag("root_dialog_positive").assertTextEquals(activity.getString(R.string.hs_download_positive_default))
        compose.onNodeWithTag("root_dialog_negative").assertDoesNotExist()
        compose.onNodeWithTag("root_dialog_positive").performClick()
        compose.onNodeWithTag("root_dialog_positive").assertDoesNotExist()
    }
    @Test fun `test error dialog is dismissible`() {
        show(AppUpdater.InstallSource.OTHER, R.string.download_update_dialog_failure_title, R.string.download_update_dialog_failure_message)
        compose.onNodeWithTag("root_dialog_negative").assertDoesNotExist()
        outsideTouch()
        compose.onNodeWithTag("root_dialog_positive").assertDoesNotExist()
    }
    @Test fun `test quota exceeded dialog is dismissible`() {
        show(AppUpdater.InstallSource.OTHER, R.string.download_update_dialog_trylater_title, R.string.download_update_dialog_trylater_message)
        compose.onNodeWithTag("root_dialog_negative").assertDoesNotExist()
        outsideTouch()
        compose.onNodeWithTag("root_dialog_positive").assertDoesNotExist()
    }
    @Test fun `test modal dialog cannot be dismissed by tapping outside`() {
        update()
        outsideTouch()
        compose.onNodeWithTag("root_dialog_positive").assertIsDisplayed()
        compose.onNodeWithTag("root_dialog_negative").performClick()
        assertEquals(1, calls.count { it == "postpone-update" })
        compose.onNodeWithTag("root_dialog_positive").assertDoesNotExist()
    }
    @Test fun `test dismissible dialog can be dismissed by tapping outside`() {
        show(AppUpdater.InstallSource.OTHER, R.string.download_update_dialog_message_ok_title, R.string.download_update_dialog_message_ok)
        outsideTouch()
        compose.onNodeWithTag("root_dialog_positive").assertDoesNotExist()
        assertFalse(calls.contains("complete-update"))
        assertFalse(calls.contains("postpone-update"))
    }
    @Test fun `test button clicks work correctly`() {
        update()
        compose.onNodeWithTag("root_dialog_positive").performClick()
        val intent = shadowOf(activity).nextStartedActivity
        assertNotNull(intent)
        assertEquals(android.content.Intent.ACTION_VIEW, intent.action)
        assertEquals(com.celzero.bravedns.util.Constants.RETHINK_APP_DOWNLOAD_LINK, intent.data.toString())
        assertNull(shadowOf(activity).nextStartedActivity)
        compose.onNodeWithTag("root_dialog_positive").assertDoesNotExist()
    }
}
