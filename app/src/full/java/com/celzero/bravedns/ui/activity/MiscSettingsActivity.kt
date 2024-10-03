/*
 * Copyright 2020 RethinkDNS and its authors
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
package com.celzero.bravedns.ui.activity

import Logger
import Logger.LOG_TAG_APP_OPS
import Logger.LOG_TAG_UI
import Logger.LOG_TAG_VPN
import android.Manifest
import android.app.LocaleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.provider.Settings
import android.view.View
import android.widget.CompoundButton
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.biometric.BiometricManager
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.ActivityMiscSettingsBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.bottomsheet.BackupRestoreBottomSheet
import com.celzero.bravedns.util.BackgroundAccessibilityService
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.NotificationActionType
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Themes.Companion.getCurrentTheme
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.delay
import com.celzero.bravedns.util.Utilities.isAtleastT
import com.celzero.bravedns.util.Utilities.isFdroidFlavour
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.koin.android.ext.android.inject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.util.concurrent.TimeUnit


class MiscSettingsActivity : AppCompatActivity(R.layout.activity_misc_settings) {
    private val b by viewBinding(ActivityMiscSettingsBinding::bind)

    private val persistentState by inject<PersistentState>()

    private lateinit var notificationPermissionResult: ActivityResultLauncher<String>

    enum class BioMetricType(val action: Int, val mins: Long) {
        OFF(0, -1L),
        IMMEDIATE(1, 0L),
        FIVE_MIN(2, 5L),
        FIFTEEN_MIN(3, 15L);

        companion object {
            fun fromValue(action: Int): BioMetricType {
                return entries.firstOrNull { it.action == action } ?: OFF
            }
        }

        fun enabled(): Boolean {
            return this != OFF
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)
        registerForActivityResult()
        initView()
        setupClickListeners()
    }

    private fun initView() {

        if (isFdroidFlavour()) {
            b.settingsActivityCheckUpdateRl.visibility = View.GONE
        }

        // enable logs
        b.settingsActivityEnableLogsSwitch.isChecked = persistentState.logsEnabled
        // check for app updates
        b.settingsActivityCheckUpdateSwitch.isChecked = persistentState.checkForAppUpdate
        // camera and microphone access
        b.settingsMicCamAccessSwitch.isChecked = persistentState.micCamAccess

        // for app locale (default system/user selected locale)
        if (isAtleastT()) {
            val currentAppLocales: LocaleList =
                getSystemService(LocaleManager::class.java).applicationLocales
            b.settingsLocaleDesc.text =
                currentAppLocales[0]?.displayName ?: getString(R.string.settings_locale_desc)
        } else {
            b.settingsLocaleDesc.text =
                AppCompatDelegate.getApplicationLocales().get(0)?.displayName
                    ?: getString(R.string.settings_locale_desc)
        }
        // biometric authentication
        if (
            BiometricManager.from(this)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS
        ) {
            b.settingsBiometricDesc.text = getString(R.string.settings_biometric_desc) + "; " +
                    BioMetricType.fromValue(persistentState.biometricAuthType).name
        } else {
            b.settingsBiometricRl.visibility = View.GONE
        }

        displayAppThemeUi()
        displayNotificationActionUi()
    }

    private fun displayNotificationActionUi() {
        b.settingsActivityNotificationRl.isEnabled = true
        when (
            NotificationActionType.getNotificationActionType(persistentState.notificationActionType)
        ) {
            NotificationActionType.PAUSE_STOP -> {
                b.genSettingsNotificationDesc.text =
                    getString(
                        R.string.settings_notification_desc,
                        getString(R.string.settings_notification_desc1)
                    )
            }

            NotificationActionType.DNS_FIREWALL -> {
                b.genSettingsNotificationDesc.text =
                    getString(
                        R.string.settings_notification_desc,
                        getString(R.string.settings_notification_desc2)
                    )
            }

            NotificationActionType.NONE -> {
                b.genSettingsNotificationDesc.text =
                    getString(
                        R.string.settings_notification_desc,
                        getString(R.string.settings_notification_desc3)
                    )
            }
        }
    }



    private fun displayAppThemeUi() {
        b.settingsActivityThemeRl.isEnabled = true
        when (persistentState.theme) {
            Themes.SYSTEM_DEFAULT.id -> {
                b.genSettingsThemeDesc.text =
                    getString(
                        R.string.settings_selected_theme,
                        getString(R.string.settings_theme_dialog_themes_1)
                    )
            }

            Themes.LIGHT.id -> {
                b.genSettingsThemeDesc.text =
                    getString(
                        R.string.settings_selected_theme,
                        getString(R.string.settings_theme_dialog_themes_2)
                    )
            }

            Themes.DARK.id -> {
                b.genSettingsThemeDesc.text =
                    getString(
                        R.string.settings_selected_theme,
                        getString(R.string.settings_theme_dialog_themes_3)
                    )
            }

            else -> {
                b.genSettingsThemeDesc.text =
                    getString(
                        R.string.settings_selected_theme,
                        getString(R.string.settings_theme_dialog_themes_4)
                    )
            }
        }
    }

    private fun setupClickListeners() {
        b.settingsActivityEnableLogsRl.setOnClickListener {
            b.settingsActivityEnableLogsSwitch.isChecked =
                !b.settingsActivityEnableLogsSwitch.isChecked
        }

        b.settingsActivityEnableLogsSwitch.setOnCheckedChangeListener { _: CompoundButton,
                                                                        b: Boolean ->
            persistentState.logsEnabled = b
        }

        b.settingsActivityCheckUpdateRl.setOnClickListener {
            b.settingsActivityCheckUpdateSwitch.isChecked =
                !b.settingsActivityCheckUpdateSwitch.isChecked
        }

        b.settingsActivityCheckUpdateSwitch.setOnCheckedChangeListener { _: CompoundButton,
                                                                         b: Boolean ->
            persistentState.checkForAppUpdate = b
        }

        b.settingsActivityThemeRl.setOnClickListener {
            enableAfterDelay(500, b.settingsActivityThemeRl)
            showThemeDialog()
        }

        // Ideally this property should be part of VPN category / section.
        // As of now the VPN section will be disabled when the
        // VPN is in lockdown mode.
        // TODO - Find a way to place this property to place in correct section.
        b.settingsActivityNotificationRl.setOnClickListener {
            enableAfterDelay(TimeUnit.SECONDS.toMillis(1L), b.settingsActivityNotificationRl)
            showNotificationActionDialog()
        }

        b.settingsActivityAppNotificationPersistentRl.setOnClickListener {
            b.settingsActivityAppNotificationPersistentSwitch.isChecked =
                !b.settingsActivityAppNotificationPersistentSwitch.isChecked
        }

        b.settingsActivityAppNotificationPersistentSwitch.setOnCheckedChangeListener { _: CompoundButton,
                                                                                       b: Boolean ->
            persistentState.persistentNotification = b
        }

        b.settingsActivityImportExportRl.setOnClickListener { invokeImportExport() }

        b.settingsActivityAppNotificationSwitch.setOnClickListener {
            b.settingsActivityAppNotificationSwitch.isChecked =
                !b.settingsActivityAppNotificationSwitch.isChecked
            invokeNotificationPermission()
        }

        b.settingsLocaleRl.setOnClickListener { invokeChangeLocaleDialog() }

        b.settingsBiometricRl.setOnClickListener {
            showBiometricDialog()
        }

        b.settingsBiometricImg.setOnClickListener {
            showBiometricDialog()
        }


        b.settingsMicCamAccessRl.setOnClickListener {
            b.settingsMicCamAccessSwitch.isChecked = !b.settingsMicCamAccessSwitch.isChecked
        }

        b.settingsMicCamAccessSwitch.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean
            ->
            if (!checked) {
                b.settingsMicCamAccessSwitch.isChecked = false
                persistentState.micCamAccess = false
                return@setOnCheckedChangeListener
            }

            // check for the permission and enable the switch
            handleAccessibilityPermission()
        }
    }


    private fun showBiometricDialog() {
        val alertBuilder = MaterialAlertDialogBuilder(this)
        alertBuilder.setTitle(getString(R.string.settings_biometric_dialog_heading))
        // show an list of options disable, enable immediate, ask after 5 min, ask after 15 min
        val item0 = getString(R.string.settings_biometric_dialog_option_0)
        val item1 = getString(R.string.settings_biometric_dialog_option_1)
        val item2 = getString(R.string.settings_biometric_dialog_option_2)
        val item3 = getString(R.string.settings_biometric_dialog_option_3)
        val items = arrayOf(item0, item1, item2, item3)

        val checkedItem = persistentState.biometricAuthType
        alertBuilder.setSingleChoiceItems(items, checkedItem) { dialog, which ->
            dialog.dismiss()
            if (persistentState.biometricAuthType == which) {
                return@setSingleChoiceItems
            }

            when (BioMetricType.fromValue(which)) {
                BioMetricType.OFF -> {
                    b.settingsBiometricDesc.text = b.settingsBiometricDesc.text.toString() + "; Off"
                    persistentState.biometricAuthType = BioMetricType.OFF.action
                    persistentState.biometricAuthTime = Constants.INIT_TIME_MS
                }

                BioMetricType.IMMEDIATE -> {
                    b.settingsBiometricDesc.text =  b.settingsBiometricDesc.text.toString() + "; Immediate"
                    persistentState.biometricAuthType = BioMetricType.IMMEDIATE.action
                    persistentState.biometricAuthTime = Constants.INIT_TIME_MS
                }

                BioMetricType.FIVE_MIN -> {
                    b.settingsBiometricDesc.text =  b.settingsBiometricDesc.text.toString() + "; 5 min"
                    persistentState.biometricAuthType = BioMetricType.FIVE_MIN.action
                    persistentState.biometricAuthTime = System.currentTimeMillis()
                }

                BioMetricType.FIFTEEN_MIN -> {
                    b.settingsBiometricDesc.text =  b.settingsBiometricDesc.text.toString() + "; 15 min"
                    persistentState.biometricAuthType = BioMetricType.FIFTEEN_MIN.action
                    persistentState.biometricAuthTime = System.currentTimeMillis()
                }
            }
        }
        alertBuilder.create().show()
    }

    private fun handleAccessibilityPermission() {
        try {
            val isAccessibilityServiceRunning =
                Utilities.isAccessibilityServiceEnabled(
                    this,
                    BackgroundAccessibilityService::class.java
                )
            val isAccessibilityServiceEnabled =
                Utilities.isAccessibilityServiceEnabledViaSettingsSecure(
                    this,
                    BackgroundAccessibilityService::class.java
                )
            val isAccessibilityServiceFunctional =
                isAccessibilityServiceRunning && isAccessibilityServiceEnabled

            if (isAccessibilityServiceFunctional) {
                persistentState.micCamAccess = true
                b.settingsMicCamAccessSwitch.isChecked = true
                return
            }

            showPermissionAlert()
            b.settingsMicCamAccessSwitch.isChecked = false
            persistentState.micCamAccess = false
        } catch (e: PackageManager.NameNotFoundException) {
            Logger.e(LOG_TAG_APP_OPS, "error checking usage stats permission ${e.message}", e)
            return
        }
    }

    private fun checkMicCamAccessRule() {
        if (!persistentState.micCamAccess) return

        val running =
            Utilities.isAccessibilityServiceEnabled(
                this,
                BackgroundAccessibilityService::class.java
            )
        val enabled =
            Utilities.isAccessibilityServiceEnabledViaSettingsSecure(
                this,
                BackgroundAccessibilityService::class.java
            )

        Logger.d(LOG_TAG_APP_OPS, "cam/mic access - running: $running, enabled: $enabled")

        val isAccessibilityServiceFunctional = running && enabled

        if (!isAccessibilityServiceFunctional) {
            persistentState.micCamAccess = false
            b.settingsMicCamAccessSwitch.isChecked = false
            showToastUiCentered(
                this,
                getString(R.string.accessibility_failure_toast),
                Toast.LENGTH_SHORT
            )
            return
        }

        if (running) {
            b.settingsMicCamAccessSwitch.isChecked = persistentState.micCamAccess
            return
        }
    }

    private fun showPermissionAlert() {
        val builder = MaterialAlertDialogBuilder(this)
        builder.setTitle(R.string.alert_permission_accessibility)
        builder.setMessage(R.string.alert_firewall_accessibility_explanation)
        builder.setPositiveButton(getString(R.string.univ_accessibility_dialog_positive)) { _, _ ->
            openAccessibilitySettings()
        }
        builder.setNegativeButton(getString(R.string.univ_accessibility_dialog_negative)) { _, _ ->
        }
        builder.setCancelable(false)
        builder.create().show()
    }

    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            showToastUiCentered(
                this,
                getString(R.string.alert_firewall_accessibility_exception),
                Toast.LENGTH_SHORT
            )
            Logger.e(LOG_TAG_APP_OPS, "Failure accessing accessibility settings: ${e.message}", e)
        }
    }

    private fun invokeChangeLocaleDialog() {
        val alertBuilder = MaterialAlertDialogBuilder(this)
        alertBuilder.setTitle(getString(R.string.settings_locale_dialog_title))
        val languages = getLocaleEntries()
        val items = languages.keys.toTypedArray()
        val selectedKey = AppCompatDelegate.getApplicationLocales().get(0)?.toLanguageTag()
        var checkedItem = 0
        languages.values.forEachIndexed { index, s ->
            if (s == selectedKey) {
                checkedItem = index
            }
        }
        alertBuilder.setSingleChoiceItems(items, checkedItem) { dialog, which ->
            dialog.dismiss()
            val item = items[which]
            // https://developer.android.com/guide/topics/resources/app-languages#app-language-settings
            val locale = languages.getOrDefault(item, "en-US")
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(locale))
        }
        alertBuilder.setNeutralButton(getString(R.string.settings_locale_dialog_neutral)) { dialog,
                                                                                            _ ->
            dialog.dismiss()
            openActionViewIntent(getString(R.string.about_translate_link).toUri())
        }
        alertBuilder.create().show()
    }

    private fun openActionViewIntent(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, uri)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            showToastUiCentered(
                this,
                getString(R.string.intent_launch_error, intent.data),
                Toast.LENGTH_SHORT
            )
            Logger.w(LOG_TAG_UI, "activity not found ${e.message}", e)
        }
    }

    // read the list of supported languages from locale_config.xml
    private fun getLocalesFromLocaleConfig(): LocaleListCompat {
        val tagsList = mutableListOf<CharSequence>()
        try {
            val xpp: XmlPullParser = resources.getXml(R.xml.locale_config)
            while (xpp.eventType != XmlPullParser.END_DOCUMENT) {
                if (xpp.eventType == XmlPullParser.START_TAG) {
                    if (xpp.name == "locale") {
                        tagsList.add(xpp.getAttributeValue(0))
                    }
                }
                xpp.next()
            }
        } catch (e: XmlPullParserException) {
            Logger.e(LOG_TAG_UI, "error parsing locale_config.xml", e)
        } catch (e: IOException) {
            Logger.e(LOG_TAG_UI, "error parsing locale_config.xml", e)
        }

        return LocaleListCompat.forLanguageTags(tagsList.joinToString(","))
    }

    private fun getLocaleEntries(): Map<String, String> {
        val localeList = getLocalesFromLocaleConfig()
        val map = mutableMapOf<String, String>()

        for (a in 0 until localeList.size()) {
            localeList[a].let { it?.let { it1 -> map.put(it1.displayName, it1.toLanguageTag()) } }
        }
        return map
    }

    private fun invokeImportExport() {
        if (this.isFinishing || this.isDestroyed) {
            Logger.w(LOG_TAG_UI, "err opening bkup btmsheet, activity is destroyed")
            return
        }

        val bottomSheetFragment = BackupRestoreBottomSheet()
        bottomSheetFragment.show(this.supportFragmentManager, bottomSheetFragment.tag)
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
    }

    private fun showThemeDialog() {
        val alertBuilder = MaterialAlertDialogBuilder(this)
        alertBuilder.setTitle(getString(R.string.settings_theme_dialog_title))
        val items =
            arrayOf(
                getString(R.string.settings_theme_dialog_themes_1),
                getString(R.string.settings_theme_dialog_themes_2),
                getString(R.string.settings_theme_dialog_themes_3),
                getString(R.string.settings_theme_dialog_themes_4)
            )
        val checkedItem = persistentState.theme
        alertBuilder.setSingleChoiceItems(items, checkedItem) { dialog, which ->
            dialog.dismiss()
            if (persistentState.theme == which) {
                return@setSingleChoiceItems
            }

            persistentState.theme = which
            when (which) {
                Themes.SYSTEM_DEFAULT.id -> {
                    if (isDarkThemeOn()) {
                        setThemeRecreate(R.style.AppTheme)
                    } else {
                        setThemeRecreate(R.style.AppThemeWhite)
                    }
                }

                Themes.LIGHT.id -> {
                    setThemeRecreate(R.style.AppThemeWhite)
                }

                Themes.DARK.id -> {
                    setThemeRecreate(R.style.AppTheme)
                }

                Themes.TRUE_BLACK.id -> {
                    setThemeRecreate(R.style.AppThemeTrueBlack)
                }
            }
        }
        alertBuilder.create().show()
    }

    private fun setThemeRecreate(theme: Int) {
        setTheme(theme)
        recreate()
    }

    private fun showNotificationActionDialog() {
        val alertBuilder = MaterialAlertDialogBuilder(this)
        alertBuilder.setTitle(getString(R.string.settings_notification_dialog_title))
        val items =
            arrayOf(
                getString(R.string.settings_notification_dialog_option_1),
                getString(R.string.settings_notification_dialog_option_2),
                getString(R.string.settings_notification_dialog_option_3)
            )
        val checkedItem = persistentState.notificationActionType
        alertBuilder.setSingleChoiceItems(items, checkedItem) { dialog, which ->
            dialog.dismiss()
            if (persistentState.notificationActionType == which) {
                return@setSingleChoiceItems
            }

            when (NotificationActionType.getNotificationActionType(which)) {
                NotificationActionType.PAUSE_STOP -> {
                    b.genSettingsNotificationDesc.text =
                        getString(
                            R.string.settings_notification_desc,
                            getString(R.string.settings_notification_desc1)
                        )
                    persistentState.notificationActionType =
                        NotificationActionType.PAUSE_STOP.action
                }

                NotificationActionType.DNS_FIREWALL -> {
                    b.genSettingsNotificationDesc.text =
                        getString(
                            R.string.settings_notification_desc,
                            getString(R.string.settings_notification_desc2)
                        )
                    persistentState.notificationActionType =
                        NotificationActionType.DNS_FIREWALL.action
                }

                NotificationActionType.NONE -> {
                    b.genSettingsNotificationDesc.text =
                        getString(
                            R.string.settings_notification_desc,
                            getString(R.string.settings_notification_desc3)
                        )
                    persistentState.notificationActionType = NotificationActionType.NONE.action
                }
            }
        }
        alertBuilder.create().show()
    }

    override fun onResume() {
        super.onResume()
        // app notification permission android 13
        showEnableNotificationSettingIfNeeded()
        checkMicCamAccessRule()
    }

    private fun registerForActivityResult() {
        // app notification permission android 13
        if (!isAtleastT()) return

        // Sets up permissions request launcher.
        notificationPermissionResult =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {
                persistentState.shouldRequestNotificationPermission = it
                if (it) {
                    Logger.i(LOG_TAG_VPN, "User allowed notification permission for the app")
                    b.settingsActivityAppNotificationRl.visibility = View.VISIBLE
                    b.settingsActivityAppNotificationSwitch.isChecked = true
                } else {
                    Logger.w(LOG_TAG_VPN, "User rejected notification permission for the app")
                    b.settingsActivityAppNotificationRl.visibility = View.VISIBLE
                    b.settingsActivityAppNotificationSwitch.isChecked = false
                    invokeAndroidNotificationSetting()
                }
            }
    }

    private fun invokeNotificationPermission() {
        if (!isAtleastT()) {
            // notification permission is needed for version 13 or above
            return
        }

        if (isNotificationPermissionGranted()) {
            // notification already granted
            invokeAndroidNotificationSetting()
            return
        }

        notificationPermissionResult.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun invokeAndroidNotificationSetting() {
        val packageName = this.packageName
        try {
            val intent = Intent()
            if (Utilities.isAtleastO()) {
                intent.action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            } else {
                intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                intent.addCategory(Intent.CATEGORY_DEFAULT)
                intent.data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            showToastUiCentered(
                this,
                getString(R.string.notification_screen_error),
                Toast.LENGTH_SHORT
            )
            Logger.w(LOG_TAG_UI, "activity not found ${e.message}", e)
        }
    }

    private fun showEnableNotificationSettingIfNeeded() {
        if (!isAtleastT()) {
            // notification permission and persistent is only needed for version 13 or above
            b.settingsActivityAppNotificationRl.visibility = View.GONE
            b.settingsActivityAppNotificationPersistentRl.visibility = View.GONE
            return
        }

        if (isNotificationPermissionGranted()) {
            // notification permission is granted to the app, enable switch
            b.settingsActivityAppNotificationRl.visibility = View.VISIBLE
            b.settingsActivityAppNotificationSwitch.isChecked = true
            if (!persistentState.shouldRequestNotificationPermission) {
                // if the user has already granted the permission, set the flag to true
                persistentState.shouldRequestNotificationPermission = true
            }
        } else {
            b.settingsActivityAppNotificationRl.visibility = View.VISIBLE
            b.settingsActivityAppNotificationSwitch.isChecked = false
            persistentState.shouldRequestNotificationPermission = false
        }
        b.settingsActivityAppNotificationPersistentRl.visibility = View.VISIBLE
        b.settingsActivityAppNotificationPersistentSwitch.isChecked =
            persistentState.persistentNotification
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun isNotificationPermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun enableAfterDelay(ms: Long, vararg views: View) {
        for (v in views) v.isEnabled = false

        delay(ms, lifecycleScope) { for (v in views) v.isEnabled = true }
    }
}
