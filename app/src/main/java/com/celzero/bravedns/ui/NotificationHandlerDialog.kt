package com.celzero.bravedns.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.celzero.bravedns.R
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.FIREWALL_SCREEN_ALL_APPS
import com.celzero.bravedns.util.Themes
import org.koin.android.ext.android.inject

class NotificationHandlerDialog : AppCompatActivity() {
    enum class DialogType {
        ACCESSIBILITY, NEWAPPINSTALL, NONE
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

        val isAccessibility = isAccessibilityIntent(intent)
        val isNewApp = isNewAppInstalledIntent(intent)

        val dialogType = if (isAccessibility) {
            DialogType.ACCESSIBILITY
        } else if(isNewApp) {
            DialogType.NEWAPPINSTALL
        } else {
            DialogType.NONE
        }

        // app not started launch home screen
        if (!VpnController.isOn()) {
            launchHomeScreen()
        }

        if (VpnController.isAppPaused()) {
            showAppPauseDialog(dialogType)
            return
        }

        if (isAccessibility) {
            handleAccessibilitySettings()
        } else if (isNewApp) {
            // navigate to all apps screen
            launchFirewallActivity()
        } else {
            // no-op
        }
    }

    private fun launchHomeScreen() {
        startActivity(Intent(this, HomeScreenActivity::class.java))
        finish()
    }

    private fun launchFirewallActivity() {
        val intent = Intent(this, FirewallActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        intent.putExtra(Constants.SCREEN_TO_LOAD, FIREWALL_SCREEN_ALL_APPS)
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
        builder.setNegativeButton(getString(R.string.univ_accessibility_crash_dialog_negative)) { _, _ ->
            finish()
        }
        builder.setCancelable(false)

        builder.create().show()
    }

    // FIXME: Add appropriate to ensure back button navigation.
    // Today, if user presses back from settings screen, it naivgates to launcher instead
    private fun openRethinkAppInfo(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        val packageName = context.packageName
        intent.data = Uri.parse("package:$packageName")
        ContextCompat.startActivity(context, intent, null)
    }

    private fun showAppPauseDialog(dialogType: DialogType) {
        val builder = AlertDialog.Builder(this)

        builder.setTitle(R.string.notif_dialog_pause_dialog_title)
        builder.setMessage(R.string.notif_dialog_pause_dialog_message)

        builder.setCancelable(false)
        builder.setPositiveButton(R.string.notif_dialog_pause_dialog_positive) { _, _ ->
            VpnController.getBraveVpnService()?.resumeApp()
            when (dialogType) {
                DialogType.ACCESSIBILITY -> { handleAccessibilitySettings() }
                DialogType.NEWAPPINSTALL -> { launchFirewallActivity() }
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