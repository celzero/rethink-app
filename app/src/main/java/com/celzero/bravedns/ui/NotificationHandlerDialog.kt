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

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.celzero.bravedns.R
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Constants

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

        when (determineTrampoline(intent)) {
            TrampolineType.ACCESSIBILITY_SERVICE_FAILURE_DIALOG -> {
                handleAccessibilitySettings()
            }
            TrampolineType.NEW_APP_INSTAL_DIALOG -> {
                // navigate to all apps screen
                launchFirewallActivity()
            }
            TrampolineType.HOMESCREEN_ACTIVITY -> {
                launchHomeScreen()
            }
            TrampolineType.PAUSE_ACTIVITY -> {
                showAppPauseDialog(receivedIntentType(intent))
            }
            TrampolineType.NONE -> {
                // no-op
            }
        }
    }

    private fun receivedIntentType(intent: Intent): TrampolineType {
        return if (isAccessibilityIntent(intent)) {
            TrampolineType.ACCESSIBILITY_SERVICE_FAILURE_DIALOG
        } else if (isNewAppInstalledIntent(intent)) {
            TrampolineType.NEW_APP_INSTAL_DIALOG
        } else {
            TrampolineType.NONE
        }
    }

    private fun determineTrampoline(intent: Intent): TrampolineType {
        // app not started launch home screen
        if (!VpnController.isOn()) {
            return TrampolineType.HOMESCREEN_ACTIVITY
        }

        if (VpnController.isAppPaused()) {
            return TrampolineType.PAUSE_ACTIVITY
        }

        return receivedIntentType(intent)
    }

    private fun launchHomeScreen() {
        startActivity(Intent(this, HomeScreenActivity::class.java))
        finish()
    }

    private fun launchFirewallActivity() {
        val intent = Intent(this, FirewallActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        intent.putExtra(Constants.VIEW_PAGER_SCREEN_TO_LOAD, FirewallActivity.FirewallTabs.ALL_APPS.screen)
        startActivity(intent)
        finish()
    }

    private fun handleAccessibilitySettings() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(R.string.alert_permission_accessibility_regrant)
        builder.setMessage(R.string.alert_firewall_accessibility_regrant_explanation)
        builder.setPositiveButton(
            getString(R.string.univ_accessibility_crash_dialog_positive)) { _, _ ->
            openRethinkAppInfo(this)
        }
        builder.setNegativeButton(
            getString(R.string.univ_accessibility_crash_dialog_negative)) { _, _ ->
            finish()
        }
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
        val builder = AlertDialog.Builder(this)

        builder.setTitle(R.string.notif_dialog_pause_dialog_title)
        builder.setMessage(R.string.notif_dialog_pause_dialog_message)

        builder.setCancelable(false)
        builder.setPositiveButton(R.string.notif_dialog_pause_dialog_positive) { _, _ ->
            VpnController.getBraveVpnService()?.resumeApp()
            when (trampolineType) {
                TrampolineType.ACCESSIBILITY_SERVICE_FAILURE_DIALOG -> {
                    handleAccessibilitySettings()
                }
                TrampolineType.NEW_APP_INSTAL_DIALOG -> {
                    launchFirewallActivity()
                }
                else -> {
                    // no-op
                }
            }
        }

        builder.setNegativeButton(R.string.notif_dialog_pause_dialog_negative) { _, _ ->
            openAppPausedActivity()
        }

        builder.create().show()
    }

    private fun openAppPausedActivity() {
        val intent = Intent()
        intent.setClass(this, PauseActivity::class.java)
        startActivity(intent)
        finish()
    }

    private fun isAccessibilityIntent(intent: Intent): Boolean {
        // Created as part of accessibility failure notification.
        val extras = intent.extras
        if (extras != null) {
            val value = extras.getString(Constants.NOTIF_INTENT_EXTRA_ACCESSIBILITY_NAME)
            if (!value.isNullOrEmpty() && value == Constants.NOTIF_INTENT_EXTRA_ACCESSIBILITY_VALUE) {
                return true
            }
        }
        return false
    }

    private fun isNewAppInstalledIntent(intent: Intent): Boolean {
        // check whether the intent is from new app installed notification
        val extras = intent.extras
        if (extras != null) {
            val value = extras.getString(Constants.NOTIF_INTENT_EXTRA_NEW_APP_NAME)
            if (!value.isNullOrEmpty() && value == Constants.NOTIF_INTENT_EXTRA_NEW_APP_VALUE) {
                return true
            }
        }
        return false
    }

}
