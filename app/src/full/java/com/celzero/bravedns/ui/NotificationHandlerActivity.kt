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
import Logger.LOG_TAG_UI
import Logger.LOG_TAG_VPN
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.os.Bundle
import android.provider.Settings
import androidx.core.net.toUri
import androidx.core.view.WindowInsetsControllerCompat
import com.celzero.bravedns.R
import com.celzero.bravedns.iab.DeviceNotRegisteredNotifier
import com.celzero.bravedns.iab.InAppBillingHandler
import com.celzero.bravedns.iab.PurchaseConflictNotifier
import com.celzero.bravedns.iab.ServerApiError
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.activity.AppInfoActivity
import com.celzero.bravedns.ui.activity.AppInfoActivity.Companion.INTENT_UID
import com.celzero.bravedns.ui.activity.AppListActivity
import com.celzero.bravedns.ui.activity.AppLockActivity
import com.celzero.bravedns.ui.activity.FragmentHostActivity
import com.celzero.bravedns.ui.activity.MiscSettingsActivity.BioMetricType
import com.celzero.bravedns.ui.activity.PauseActivity
import com.celzero.bravedns.ui.activity.WgMainActivity
import com.celzero.bravedns.ui.fragment.RethinkPlusDashboardFragment
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.NOTIF_INTENT_EXTRA_IAB_CONFLICT_NAME
import com.celzero.bravedns.util.Constants.Companion.NOTIF_INTENT_EXTRA_IAB_CONFLICT_VALUE
import com.celzero.bravedns.util.Constants.Companion.NOTIF_INTENT_EXTRA_IAB_DEVICE_NOT_REGISTERED_NAME
import com.celzero.bravedns.util.Constants.Companion.NOTIF_INTENT_EXTRA_IAB_DEVICE_NOT_REGISTERED_VALUE
import com.celzero.bravedns.util.Themes.Companion.getCurrentTheme
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.koin.android.ext.android.inject

class NotificationHandlerActivity: BaseActivity() {

    private val persistentState by inject<PersistentState>()

    companion object {
        private const val SCHEME_PACKAGE = "package"

        // Biometric authentication timeout durations (in milliseconds)
        private const val BIOMETRIC_TIMEOUT_FIVE_MIN_MS = 5L * 60L * 1000L
        private const val BIOMETRIC_TIMEOUT_FIFTEEN_MIN_MS = 15L * 60L * 1000L
    }

    enum class TrampolineType {
        ACCESSIBILITY_SERVICE_FAILURE_DIALOG,
        NEW_APP_INSTALL_DIALOG,
        HOME_SCREEN_ACTIVITY,
        PAUSE_ACTIVITY,
        WIREGUARD_ACTIVITY,
        PURCHASE_CONFLICT,
        RPN_DEVICE_NOT_REGISTERED,
        NONE
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // apply theme before super.onCreate to ensure proper dialog inflation
        theme.applyStyle(getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)
        handleFrostEffectIfNeeded(persistentState.theme)

        // This is a trampoline activity that shows dialogs but no actual UI
        setContentView(android.R.layout.activity_list_item)
        window.decorView.alpha = 0f // Make it invisible

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = false
            window.isNavigationBarContrastEnforced = false
        }
        handleNotificationIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationIntent(intent)
    }

    // In two-cases (accessibility failure/new app install action), the app directly launches
    // firewall activity from notification action. Need to handle the pause state for those cases
    @Suppress("DEPRECATION")
    private fun handleNotificationIntent(intent: Intent) {
        // app not started launch home screen
        if (!VpnController.isOn()) {
            trampoline(TrampolineType.NONE, intent)
            return
        }

        if (isAppLocked()) {
            trampoline(TrampolineType.NONE, intent)
            return
        }

        if (VpnController.isAppPaused()) {
            trampoline(TrampolineType.PAUSE_ACTIVITY, intent)
            return
        }

        val t =
            if (isAccessibilityIntent(intent)) {
                TrampolineType.ACCESSIBILITY_SERVICE_FAILURE_DIALOG
            } else if (isNewAppInstalledIntent(intent)) {
                TrampolineType.NEW_APP_INSTALL_DIALOG
            } else if (isWireGuardIntent(intent)) {
                TrampolineType.WIREGUARD_ACTIVITY
            } else if (isPurchaseConflictIntent(intent)) {
                TrampolineType.PURCHASE_CONFLICT
            } else if (isDeviceNotRegisteredIntent(intent)) {
                TrampolineType.RPN_DEVICE_NOT_REGISTERED
            } else {
                TrampolineType.NONE
            }
        trampoline(t, intent)
    }

    private fun trampoline(trampolineType: TrampolineType, intent: Intent) {
        Logger.i(LOG_TAG_UI, "act on notification, notification type: $trampolineType")
        when (trampolineType) {
            TrampolineType.ACCESSIBILITY_SERVICE_FAILURE_DIALOG -> {
                handleAccessibilitySettings()
            }
            TrampolineType.NEW_APP_INSTALL_DIALOG -> {
                // navigate to all apps screen
                launchFirewallActivityAndFinish(intent)
            }
            TrampolineType.HOME_SCREEN_ACTIVITY -> {
                launchHomeScreenAndFinish()
            }
            TrampolineType.PAUSE_ACTIVITY -> {
                showAppPauseDialog(trampolineType, intent)
            }
            TrampolineType.WIREGUARD_ACTIVITY -> {
                launchWireGuardActivityAndFinish()
            }
            TrampolineType.PURCHASE_CONFLICT -> {
                launchPurchaseConflictAndFinish(intent)
            }
            TrampolineType.RPN_DEVICE_NOT_REGISTERED -> {
                launchDeviceNotRegisteredAndFinish(intent)
            }
            TrampolineType.NONE -> {
                launchHomeScreenAndFinish()
            }
        }
    }

    /**
     * Rebuilds the [ServerApiError.Conflict409] from the notification intent extras,
     * re-posts it to [InAppBillingHandler.serverApiErrorLiveData], cancels the notification,
     * then opens [RethinkPlusDashboardFragment]
     *
     * [RethinkPlusDashboardFragment.setupServerErrorObserver] observes the LiveData and will
     * immediately show [com.celzero.bravedns.ui.bottomsheet.PurchaseConflictBottomSheet]
     */
    private fun launchPurchaseConflictAndFinish(intent: Intent) {
        try {
            val operationName = intent.getStringExtra(PurchaseConflictNotifier.EXTRA_OPERATION)
                ?: ServerApiError.Operation.CANCEL.name
            val operation = try {
                ServerApiError.Operation.valueOf(operationName)
            } catch (_: IllegalArgumentException) {
                ServerApiError.Operation.CANCEL
            }

            val error = ServerApiError.Conflict409(
                endpoint = intent.getStringExtra(PurchaseConflictNotifier.EXTRA_ENDPOINT)
                                    ?: operation.endpoint,
                operation = operation,
                serverMessage = intent.getStringExtra(PurchaseConflictNotifier.EXTRA_SERVER_MSG),
                accountId = intent.getStringExtra(PurchaseConflictNotifier.EXTRA_ACCOUNT_ID) ?: "",
                purchaseToken = intent.getStringExtra(PurchaseConflictNotifier.EXTRA_PURCHASE_TOKEN) ?: "",
                sku = intent.getStringExtra(PurchaseConflictNotifier.EXTRA_SKU) ?: ""
            )

            InAppBillingHandler.serverApiErrorLiveData.value = error

            PurchaseConflictNotifier.cancel(this)

            Logger.i(LOG_TAG_UI, "launchPurchaseConflictAndFinish: re-posted conflict409, op=$operation")

            val hostIntent = FragmentHostActivity.createIntent(
                context = this,
                fragmentClass = RethinkPlusDashboardFragment::class.java,
                args = Bundle()
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(hostIntent)
            finish()
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "launchPurchaseConflictAndFinish: error: ${e.message}", e)
            launchHomeScreenAndFinish()
        }
    }

    /**
     * Rebuilds [ServerApiError.DeviceNotRegistered] from the notification intent extras,
     * re-posts it to [InAppBillingHandler.serverApiErrorLiveData], cancels the notification,
     * then opens [RethinkPlusDashboardFragment]
     *
     * [RethinkPlusDashboardFragment.setupServerErrorObserver] will immediately show
     * [com.celzero.bravedns.ui.bottomsheet.DeviceNotRegisteredBottomSheet]
     */
    private fun launchDeviceNotRegisteredAndFinish(intent: Intent) {
        try {
            val error = ServerApiError.DeviceNotRegistered(
                entitlementCid = intent.getStringExtra(DeviceNotRegisteredNotifier.EXTRA_ENTITLEMENT_CID) ?: "",
                storedCid = intent.getStringExtra(DeviceNotRegisteredNotifier.EXTRA_STORED_CID)       ?: "",
                deviceIdPrefix = intent.getStringExtra(DeviceNotRegisteredNotifier.EXTRA_DEVICE_ID_PREFIX) ?: ""
            )

            // Re-post to LiveData on main thread so ManageRpnPurchaseBtmSht's observer
            // picks it up and shows DeviceNotRegisteredBottomSheet automatically.
            InAppBillingHandler.serverApiErrorLiveData.value = error

            DeviceNotRegisteredNotifier.cancel(this)

            Logger.i(LOG_TAG_UI, "launchDeviceNotRegisteredAndFinish: re-posted DeviceNotRegistered error")

            val hostIntent = FragmentHostActivity.createIntent(
                context = this,
                fragmentClass = RethinkPlusDashboardFragment::class.java,
                args = Bundle()
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(hostIntent)
            finish()
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "launchDeviceNotRegisteredAndFinish: error: ${e.message}", e)
            launchHomeScreenAndFinish()
        }
    }

    private fun isAppLocked(): Boolean {
        val authType = persistentState.biometricAuthType
        if (authType == BioMetricType.OFF.action) return false

        val lastAuthTime = persistentState.biometricAuthTime
        if (BioMetricType.FIVE_MIN.action == authType) {
            if (System.currentTimeMillis() - lastAuthTime < BIOMETRIC_TIMEOUT_FIVE_MIN_MS) return false
        } else if (BioMetricType.FIFTEEN_MIN.action == authType) {
            if (System.currentTimeMillis() - lastAuthTime < BIOMETRIC_TIMEOUT_FIFTEEN_MIN_MS) return false
        }

        return true
    }

    private fun launchHomeScreenAndFinish() {
        // handle the app lock state then launch home screen
        val intent = Intent(this, AppLockActivity::class.java)
        intent.setPackage(this.packageName)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun launchWireGuardActivityAndFinish() {
        val intent = Intent(this, WgMainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun launchFirewallActivityAndFinish(recvIntent: Intent) {
        // TODO: handle the app lock state then launch firewall activity
        val uid = recvIntent.getIntExtra(Constants.NOTIF_INTENT_EXTRA_APP_UID, Int.MIN_VALUE)
        Logger.d(LOG_TAG_VPN, "notification intent - new app installed, uid: $uid")
        if (uid > 0) {
            val intent = Intent(this, AppInfoActivity::class.java)
            intent.putExtra(INTENT_UID, uid)
            startActivity(intent)
            finish()
        } else {
            val intent = Intent(this, AppListActivity::class.java)
            startActivity(intent)
            finish()
        }
    }

    private fun handleAccessibilitySettings() {
        val builder = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
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
        intent.data = "$SCHEME_PACKAGE:$packageName".toUri()
        startActivity(intent)
    }

    private fun showAppPauseDialog(trampolineType: TrampolineType, intent: Intent) {
        val builder = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)

        builder.setTitle(R.string.notif_dialog_pause_dialog_title)
        builder.setMessage(R.string.notif_dialog_pause_dialog_message)

        builder.setCancelable(false)
        builder.setPositiveButton(R.string.notif_dialog_pause_dialog_positive) { _, _ ->
            VpnController.resumeApp()

            trampoline(trampolineType, intent)
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

    private fun isWireGuardIntent(intent: Intent): Boolean {
        if (intent.extras == null) return false
        val what = intent.extras?.getString(Constants.NOTIF_WG_PERMISSION_NAME)
        return Constants.NOTIF_WG_PERMISSION_VALUE == what
    }

    /* checks if it's a purchase-conflict (HTTP 409) intent sent from notification */
    private fun isPurchaseConflictIntent(intent: Intent): Boolean {
        if (intent.extras == null) return false
        val what = intent.extras?.getString(NOTIF_INTENT_EXTRA_IAB_CONFLICT_NAME)
        return NOTIF_INTENT_EXTRA_IAB_CONFLICT_VALUE == what
    }

    /* checks if it's a device-not-registered intent sent from notification */
    private fun isDeviceNotRegisteredIntent(intent: Intent): Boolean {
        if (intent.extras == null) return false
        val what = intent.extras?.getString(NOTIF_INTENT_EXTRA_IAB_DEVICE_NOT_REGISTERED_NAME)
        return NOTIF_INTENT_EXTRA_IAB_DEVICE_NOT_REGISTERED_VALUE == what
    }
}
