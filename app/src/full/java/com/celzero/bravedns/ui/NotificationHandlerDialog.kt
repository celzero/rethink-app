/*
 * Copyright 2021 RethinkDNS and its authors
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

import Logger
import Logger.LOG_TAG_VPN
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.celzero.bravedns.R
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.activity.AppListActivity
import com.celzero.bravedns.ui.activity.PauseActivity
import com.celzero.bravedns.util.Constants
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class NotificationHandlerDialog : AppCompatActivity() {
    enum class TrampolineType {
        ACCESSIBILITY_SERVICE_FAILURE_DIALOG,
        NEW_APP_INSTAL_DIALOG,
        HOMESCREEN_ACTIVITY,
        PAUSE_ACTIVITY,
        NONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleNotificationIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationIntent(intent)
    }

    // In two-cases (accessibility failure/new app install action), the app directly launches
    // firewall activity from notification action. Need to handle the pause state for those cases
    private fun handleNotificationIntent(intent: Intent) {
        // app not started launch home screen
        if (!VpnController.isOn()) {
            trampoline(TrampolineType.NONE)
            return
        }

        if (VpnController.isAppPaused()) {
            trampoline(TrampolineType.PAUSE_ACTIVITY)
            return
        }

        val t =
            if (isAccessibilityIntent(intent)) {
                TrampolineType.ACCESSIBILITY_SERVICE_FAILURE_DIALOG
            } else if (isNewAppInstalledIntent(intent)) {
                TrampolineType.NEW_APP_INSTAL_DIALOG
            } else {
                TrampolineType.NONE
            }
        trampoline(t)
    }

    private fun trampoline(trampolineType: TrampolineType) {
        Logger.i(LOG_TAG_VPN, "act on notification, notification type: $trampolineType")
        when (trampolineType) {
            TrampolineType.ACCESSIBILITY_SERVICE_FAILURE_DIALOG -> {
                handleAccessibilitySettings()
            }
            TrampolineType.NEW_APP_INSTAL_DIALOG -> {
                // navigate to all apps screen
                launchFirewallActivityAndFinish()
            }
            TrampolineType.HOMESCREEN_ACTIVITY -> {
                launchHomeScreenAndFinish()
            }
            TrampolineType.PAUSE_ACTIVITY -> {
                showAppPauseDialog(trampolineType)
            }
            TrampolineType.NONE -> {
                launchHomeScreenAndFinish()
            }
        }
    }

    private fun launchHomeScreenAndFinish() {
        startActivity(Intent(this, HomeScreenActivity::class.java))
        finish()
    }

    private fun launchFirewallActivityAndFinish() {
        val intent = Intent(this, AppListActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        startActivity(intent)
        finish()
    }

    private fun handleAccessibilitySettings() {
        val builder = MaterialAlertDialogBuilder(this)
        builder.setTitle(R.string.lbl_action_required)
        builder.setMessage(R.string.alert_firewall_accessibility_regrant_explanation)
        builder.setPositiveButton(getString(R.string.univ_accessibility_crash_dialog_positive)) {
            _,
            _ ->
            openRethinkAppInfo(this)
        }
        builder.setNegativeButton(getString(R.string.lbl_cancel)) { _, _ -> finish() }
        builder.setCancelable(false)

        builder.create().show()
    }

    private fun openRethinkAppInfo(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        val packageName = context.packageName
        intent.data = Uri.parse("package:$packageName")
        ContextCompat.startActivity(context, intent, null)
    }

    private fun showAppPauseDialog(trampolineType: TrampolineType) {
        val builder = MaterialAlertDialogBuilder(this)

        builder.setTitle(R.string.notif_dialog_pause_dialog_title)
        builder.setMessage(R.string.notif_dialog_pause_dialog_message)

        builder.setCancelable(false)
        builder.setPositiveButton(R.string.notif_dialog_pause_dialog_positive) { _, _ ->
            VpnController.resumeApp()

            trampoline(trampolineType)
        }

        builder.setNegativeButton(R.string.notif_dialog_pause_dialog_negative) { _, _ -> finish() }

        builder.setNeutralButton(R.string.notif_dialog_pause_dialog_neutral) { _, _ ->
            startActivity(Intent().setClass(this, PauseActivity::class.java))
            finish()
        }

        builder.create().show()
    }

    /* checks if its accessibility failure intent sent from notification */
    private fun isAccessibilityIntent(intent: Intent): Boolean {
        if (intent.extras == null) return false

        val what = intent.extras?.getString(Constants.NOTIF_INTENT_EXTRA_ACCESSIBILITY_NAME)
        return Constants.NOTIF_INTENT_EXTRA_ACCESSIBILITY_VALUE == what
    }

    /* checks if its a new-app-installed intent sent from notification */
    private fun isNewAppInstalledIntent(intent: Intent): Boolean {
        if (intent.extras == null) return false

        val what = intent.extras?.getString(Constants.NOTIF_INTENT_EXTRA_NEW_APP_NAME)
        return Constants.NOTIF_INTENT_EXTRA_NEW_APP_VALUE == what
    }
}
