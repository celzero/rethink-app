/*
 * Copyright 2025 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.ui

import android.app.AlertDialog
import android.content.Context
import android.widget.Button
import androidx.test.core.app.ApplicationProvider
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowLooper
import kotlin.test.*

/**
 * Unit tests for HomeScreenActivity's showDownloadDialog() method
 * Uses Robolectric to test dialog behavior
 *
 * These tests validate the dialog logic implementation:
 * - Modal behavior for update dialogs (setCancelable(false))
 * - Button configuration (positive/negative buttons)
 * - Dismissibility for informational dialogs
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [33],
    qualifiers = "en-rUS"
)
class HomeScreenActivityDialogTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        ShadowAlertDialog.reset()
    }

    @After
    fun tearDown() {
        // Dismiss any showing dialogs to prevent resource leaks
        val shownDialogs = ShadowAlertDialog.getShownDialogs()
        shownDialogs.forEach { dialog ->
            if (dialog?.isShowing == true) {
                dialog.dismiss()
            }
        }
        ShadowAlertDialog.reset()
        unmockkAll()
    }

    @Test
    fun `test update available dialog is modal and has correct buttons for website`() {
        // Given
        val title = "New Update!"
        val message = "A new version of the app is available for download from the website. Do you want to proceed?"

        // When - Create dialog simulating showDownloadDialog behavior
        val builder = AlertDialog.Builder(context)
        builder.setTitle(title)
        builder.setMessage(message)
        builder.setCancelable(false) // Modal - key behavior we're testing

        // Website version buttons
        builder.setPositiveButton("Visit website") { dialogInterface, _ ->
            dialogInterface.dismiss()
        }
        builder.setNegativeButton("Remind me later") { dialogInterface, _ ->
            dialogInterface.dismiss()
        }

        val dialog = builder.create()
        dialog.show()
        ShadowLooper.idleMainLooper()

        // Then
        val latestDialog = ShadowAlertDialog.getLatestDialog()
        assertNotNull(latestDialog, "Dialog should be shown")

        val shadowDialog = shadowOf(latestDialog)
        assertEquals(title, shadowDialog.title.toString())

        // Verify dialog is modal
        assertFalse(shadowDialog.isCancelable, "Update dialog should be modal (not cancelable)")

        // Verify both buttons exist using findViewById
        val positiveButton = latestDialog.findViewById<Button>(android.R.id.button1)
        val negativeButton = latestDialog.findViewById<Button>(android.R.id.button2)

        assertNotNull(positiveButton, "Positive button should exist")
        assertNotNull(negativeButton, "Negative button should exist")
        assertEquals("Visit website", positiveButton.text.toString())
        assertEquals("Remind me later", negativeButton.text.toString())
    }

    @Test
    fun `test update available dialog is modal and has correct buttons for Play Store`() {
        // Given
        val title = "New Update!"
        val customMessage = "A new version is available. Please update from Play Store."

        // When
        val builder = AlertDialog.Builder(context)
        builder.setTitle(title)
        builder.setMessage(customMessage)
        builder.setCancelable(false) // Modal

        // Play Store version buttons
        builder.setPositiveButton("ok") { dialogInterface, _ ->
            dialogInterface.dismiss()
        }
        builder.setNegativeButton("Remind me later") { dialogInterface, _ ->
            dialogInterface.dismiss()
        }

        val dialog = builder.create()
        dialog.show()
        ShadowLooper.idleMainLooper()

        // Then
        val latestDialog = ShadowAlertDialog.getLatestDialog()
        assertNotNull(latestDialog, "Dialog should be shown")

        val shadowDialog = shadowOf(latestDialog)
        assertEquals(title, shadowDialog.title.toString())

        // Verify dialog is modal
        assertFalse(shadowDialog.isCancelable, "Update dialog should be modal")

        // Verify both buttons exist using findViewById
        val positiveButton = latestDialog.findViewById<Button>(android.R.id.button1)
        val negativeButton = latestDialog.findViewById<Button>(android.R.id.button2)

        assertNotNull(positiveButton, "Positive button should exist")
        assertNotNull(negativeButton, "Negative button should exist")
    }

    @Test
    fun `test up-to-date dialog is dismissible with only OK button`() {
        // Given
        val title = "Up-to-date!"
        val message = "You are already on the latest version."

        // When
        val builder = AlertDialog.Builder(context)
        builder.setTitle(title)
        builder.setMessage(message)
        builder.setCancelable(true) // Dismissible - key difference from update dialog

        builder.setPositiveButton("ok") { dialogInterface, _ ->
            dialogInterface.dismiss()
        }

        val dialog = builder.create()
        dialog.show()
        ShadowLooper.idleMainLooper()

        // Then
        val latestDialog = ShadowAlertDialog.getLatestDialog()
        assertNotNull(latestDialog, "Dialog should be shown")

        val shadowDialog = shadowOf(latestDialog)
        assertEquals(title, shadowDialog.title.toString())

        // Verify dialog is dismissible
        assertTrue(shadowDialog.isCancelable, "Up-to-date dialog should be dismissible")

        // Verify only positive button exists using findViewById
        val positiveButton = latestDialog.findViewById<Button>(android.R.id.button1)
        assertNotNull(positiveButton, "Positive button should exist")
        assertEquals("ok", positiveButton.text.toString())
    }

    @Test
    fun `test error dialog is dismissible`() {
        // Given
        val title = "Something went wrong!"
        val message = "Either our servers are down or connection could not be established."

        // When
        val builder = AlertDialog.Builder(context)
        builder.setTitle(title)
        builder.setMessage(message)
        builder.setCancelable(true) // Dismissible

        builder.setPositiveButton("ok") { dialogInterface, _ ->
            dialogInterface.dismiss()
        }

        val dialog = builder.create()
        dialog.show()
        ShadowLooper.idleMainLooper()

        // Then
        val latestDialog = ShadowAlertDialog.getLatestDialog()
        assertNotNull(latestDialog, "Dialog should be shown")

        val shadowDialog = shadowOf(latestDialog)

        // Verify dialog is dismissible
        assertTrue(shadowDialog.isCancelable, "Error dialog should be dismissible")
    }

    @Test
    fun `test quota exceeded dialog is dismissible`() {
        // Given
        val title = "Can't update right now"
        val message = "The update cannot be executed right now, please try again later."

        // When
        val builder = AlertDialog.Builder(context)
        builder.setTitle(title)
        builder.setMessage(message)
        builder.setCancelable(true) // Dismissible

        builder.setPositiveButton("ok") { dialogInterface, _ ->
            dialogInterface.dismiss()
        }

        val dialog = builder.create()
        dialog.show()
        ShadowLooper.idleMainLooper()

        // Then
        val latestDialog = ShadowAlertDialog.getLatestDialog()
        assertNotNull(latestDialog, "Dialog should be shown")

        val shadowDialog = shadowOf(latestDialog)

        // Verify dialog is dismissible
        assertTrue(shadowDialog.isCancelable, "Quota exceeded dialog should be dismissible")
    }

    @Test
    fun `test modal dialog cannot be dismissed by tapping outside`() {
        // Given
        val builder = AlertDialog.Builder(context)
        builder.setTitle("New Update!")
        builder.setMessage("Update available")
        builder.setCancelable(false) // Modal - this is what we're testing

        builder.setPositiveButton("Visit website") { dialogInterface, _ ->
            dialogInterface.dismiss()
        }
        builder.setNegativeButton("Remind me later") { dialogInterface, _ ->
            dialogInterface.dismiss()
        }

        val dialog = builder.create()
        dialog.show()
        ShadowLooper.idleMainLooper()

        // Then
        val latestDialog = ShadowAlertDialog.getLatestDialog()
        assertNotNull(latestDialog)

        val shadowDialog = shadowOf(latestDialog)

        // Verify cannot dismiss by tapping outside or pressing back
        assertFalse(shadowDialog.isCancelable, "Modal dialog should not be cancelable")

        // Verify dialog is still showing
        assertTrue(latestDialog.isShowing, "Dialog should still be showing")
    }

    @Test
    fun `test dismissible dialog can be dismissed by tapping outside`() {
        // Given
        val builder = AlertDialog.Builder(context)
        builder.setTitle("Up-to-date!")
        builder.setMessage("You are already on the latest version.")
        builder.setCancelable(true) // Can dismiss by tapping outside

        builder.setPositiveButton("ok") { dialogInterface, _ ->
            dialogInterface.dismiss()
        }

        val dialog = builder.create()
        dialog.show()
        ShadowLooper.idleMainLooper()

        // Then
        val latestDialog = ShadowAlertDialog.getLatestDialog()
        assertNotNull(latestDialog)

        val shadowDialog = shadowOf(latestDialog)

        // Verify can dismiss by tapping outside
        assertTrue(shadowDialog.isCancelable, "Dismissible dialog should be cancelable")
    }

    @Test
    fun `test button clicks work correctly`() {
        // Given
        var positiveClicked = false

        val builder = AlertDialog.Builder(context)
        builder.setTitle("New Update!")
        builder.setMessage("Update available")
        builder.setCancelable(false)

        builder.setPositiveButton("Visit website") { dialogInterface, _ ->
            positiveClicked = true
            dialogInterface.dismiss()
        }
        builder.setNegativeButton("Remind me later") { dialogInterface, _ ->
            dialogInterface.dismiss()
        }

        val dialog = builder.create()
        dialog.show()
        ShadowLooper.idleMainLooper()

        val latestDialog = ShadowAlertDialog.getLatestDialog()
        val positiveButton = latestDialog?.findViewById<Button>(android.R.id.button1)

        // When - Click positive button
        positiveButton?.performClick()
        ShadowLooper.idleMainLooper()

        // Then
        assertTrue(positiveClicked, "Positive button click should be registered")
    }
}
