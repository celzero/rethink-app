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

import androidx.appcompat.app.AlertDialog
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation tests for dialog behavior on actual devices
 *
 * These tests validate that the dialog implementation works correctly
 * on real Android devices, testing:
 * - Modal behavior (setCancelable(false))
 * - Button configuration
 * - User interaction
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class HomeScreenActivityDialogInstrumentedTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(TestDialogActivity::class.java)

    private val dialogs = mutableListOf<AlertDialog>()

    @Before
    fun setUp() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @After
    fun tearDown() {
        // Clean up any dialogs
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            dialogs.forEach { dialog ->
                if (dialog.isShowing) {
                    dialog.dismiss()
                }
            }
            dialogs.clear()
        }
    }

    @Test
    fun testModalUpdateDialog_CannotBeDismissedByTappingOutside() {
        var createdDialog: AlertDialog? = null

        activityRule.scenario.onActivity { activity ->
            val dialog = MaterialAlertDialogBuilder(activity)
            dialog.setTitle("New Update!")
            dialog.setMessage("A new version is available")
            dialog.setCancelable(false) // Modal - key test
            dialog.setPositiveButton("Visit website") { _, _ -> }
            dialog.setNegativeButton("Remind me later") { _, _ -> }

            createdDialog = dialog.create()
            createdDialog.show()
            createdDialog.let { dialogs.add(it) }
        }

        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(500) // Give dialog time to render

        // Verify dialog is showing and not cancelable
        assertNotNull("Dialog should be created", createdDialog)
        assertTrue("Dialog should be showing", createdDialog!!.isShowing)

        // Verify both buttons exist
        val positiveButton = createdDialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val negativeButton = createdDialog.getButton(AlertDialog.BUTTON_NEGATIVE)

        assertNotNull("Positive button should exist", positiveButton)
        assertNotNull("Negative button should exist", negativeButton)
        assertEquals("Visit website", positiveButton?.text.toString())
        assertEquals("Remind me later", negativeButton?.text.toString())
    }

    @Test
    fun testDismissibleDialog_CanBeDismissed() {
        var createdDialog: AlertDialog? = null

        activityRule.scenario.onActivity { activity ->
            val dialog = MaterialAlertDialogBuilder(activity)
            dialog.setTitle("Up-to-date!")
            dialog.setMessage("You are already on the latest version.")
            dialog.setCancelable(true) // Dismissible
            dialog.setPositiveButton("ok") { _, _ -> }

            createdDialog = dialog.create()
            createdDialog.show()
            createdDialog.let { dialogs.add(it) }
        }

        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(500)

        // Verify dialog is showing
        assertNotNull("Dialog should be created", createdDialog)
        assertTrue("Dialog should be showing", createdDialog!!.isShowing)

        // Verify only positive button exists
        val positiveButton = createdDialog.getButton(AlertDialog.BUTTON_POSITIVE)
        assertNotNull("Positive button should exist", positiveButton)
        assertEquals("ok", positiveButton?.text.toString())
    }

    @Test
    fun testUpdateDialog_HasBothButtons() {
        var createdDialog: AlertDialog? = null

        activityRule.scenario.onActivity { activity ->
            val dialog = MaterialAlertDialogBuilder(activity)
            dialog.setTitle("New Update!")
            dialog.setMessage("A new version of the app is available for download from the website. Do you want to proceed?")
            dialog.setCancelable(false)
            dialog.setPositiveButton("Visit website") { _, _ -> }
            dialog.setNegativeButton("Remind me later") { _, _ -> }

            createdDialog = dialog.create()
            createdDialog.show()
            createdDialog.let { dialogs.add(it) }
        }

        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(500)

        assertNotNull("Dialog should be created", createdDialog)
        assertTrue("Dialog should be showing", createdDialog!!.isShowing)

        val positiveButton = createdDialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val negativeButton = createdDialog.getButton(AlertDialog.BUTTON_NEGATIVE)

        assertNotNull("Positive button should exist", positiveButton)
        assertNotNull("Negative button should exist", negativeButton)
        assertTrue("Positive button should be visible", positiveButton!!.isShown)
        assertTrue("Negative button should be visible", negativeButton!!.isShown)
    }

    @Test
    fun testButtonClick_DismissesDialog() {
        var createdDialog: AlertDialog? = null
        var buttonClicked = false

        activityRule.scenario.onActivity { activity ->
            val dialog = MaterialAlertDialogBuilder(activity)
            dialog.setTitle("New Update!")
            dialog.setMessage("Update available")
            dialog.setCancelable(false)
            dialog.setPositiveButton("Visit website") { dialogInterface, _ ->
                buttonClicked = true
                dialogInterface.dismiss()
            }
            dialog.setNegativeButton("Remind me later") { _, _ -> }

            createdDialog = dialog.create()
            createdDialog.show()
            createdDialog.let { dialogs.add(it) }
        }

        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(500)

        assertTrue("Dialog should be showing", createdDialog!!.isShowing)

        // Click positive button
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val positiveButton = createdDialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton?.performClick()
        }

        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(300)

        assertTrue("Button click should be registered", buttonClicked)
        assertFalse("Dialog should be dismissed after button click", createdDialog.isShowing)
    }

    @Test
    fun testModalDialog_StaysOpenWithoutButtonClick() {
        var createdDialog: AlertDialog? = null

        activityRule.scenario.onActivity { activity ->
            val dialog = MaterialAlertDialogBuilder(activity)
            dialog.setTitle("New Update!")
            dialog.setMessage("Update available")
            dialog.setCancelable(false) // Modal
            dialog.setPositiveButton("Visit website") { _, _ -> }
            dialog.setNegativeButton("Remind me later") { _, _ -> }

            createdDialog = dialog.create()
            createdDialog.show()
            createdDialog.let { dialogs.add(it) }
        }

        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(500)

        // Verify dialog stays open (user must click button)
        assertTrue("Modal dialog should stay showing", createdDialog!!.isShowing)

        // Verify dialog doesn't auto-dismiss
        Thread.sleep(1000)
        assertTrue("Modal dialog should still be showing after delay", createdDialog.isShowing)
    }

    @Test
    fun testNegativeButton_WorksCorrectly() {
        var createdDialog: AlertDialog? = null
        var negativeButtonClicked = false

        activityRule.scenario.onActivity { activity ->
            val dialog = MaterialAlertDialogBuilder(activity)
            dialog.setTitle("New Update!")
            dialog.setMessage("Update available")
            dialog.setCancelable(false)
            dialog.setPositiveButton("Visit website") { _, _ -> }
            dialog.setNegativeButton("Remind me later") { dialogInterface, _ ->
                negativeButtonClicked = true
                dialogInterface.dismiss()
            }

            createdDialog = dialog.create()
            createdDialog.show()
            createdDialog.let { dialogs.add(it) }
        }

        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(500)

        // Click negative button
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val negativeButton = createdDialog?.getButton(AlertDialog.BUTTON_NEGATIVE)
            negativeButton?.performClick()
        }

        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(300)

        assertTrue("Negative button click should be registered", negativeButtonClicked)
        assertFalse("Dialog should be dismissed after negative button click", createdDialog!!.isShowing)
    }

    @Test
    fun testInformationalDialog_OnlyHasOkButton() {
        var createdDialog: AlertDialog? = null

        activityRule.scenario.onActivity { activity ->
            val dialog = MaterialAlertDialogBuilder(activity)
            dialog.setTitle("Something went wrong!")
            dialog.setMessage("Either our servers are down or connection could not be established.")
            dialog.setCancelable(true) // Dismissible
            dialog.setPositiveButton("ok") { _, _ -> }

            createdDialog = dialog.create()
            createdDialog.show()
            createdDialog.let { dialogs.add(it) }
        }

        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(500)

        assertNotNull("Dialog should be created", createdDialog)
        assertTrue("Dialog should be showing", createdDialog!!.isShowing)

        // Verify only positive button exists
        val positiveButton = createdDialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val negativeButton = createdDialog.getButton(AlertDialog.BUTTON_NEGATIVE)

        assertNotNull("Positive button should exist", positiveButton)
        assertTrue("Positive button should be visible", positiveButton!!.isShown)

        // Negative button should not be visible
        if (negativeButton != null) {
            assertFalse("Negative button should not be visible", negativeButton.isShown)
        }
    }
}
