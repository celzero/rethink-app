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
import Logger.updateConfigLevel
import android.Manifest
import android.R.attr.theme
import android.app.LocaleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.LocaleList
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatTextView
import androidx.biometric.BiometricManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.backup.BackupHelper
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.RefreshDatabase
import com.celzero.bravedns.databinding.ActivityMiscSettingsBinding
import com.celzero.bravedns.net.go.GoVpnAdapter
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.LauncherSwitcher
import com.celzero.bravedns.ui.activity.AppLockActivity.Companion.APP_LOCK_ALIAS
import com.celzero.bravedns.ui.activity.AppLockActivity.Companion.HOME_ALIAS
import com.celzero.bravedns.ui.bottomsheet.BackupRestoreBottomSheet
import com.celzero.bravedns.util.BackgroundAccessibilityService
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.FirebaseErrorReporting
import com.celzero.bravedns.util.FirebaseErrorReporting.TOKEN_LENGTH
import com.celzero.bravedns.util.FirebaseErrorReporting.TOKEN_REGENERATION_PERIOD_DAYS
import com.celzero.bravedns.util.NewSettingsManager
import com.celzero.bravedns.util.NotificationActionType
import com.celzero.bravedns.util.PcapMode
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Themes.Companion.getCurrentTheme
import com.celzero.bravedns.util.UIUtils.openUrl
import com.celzero.bravedns.util.UIUtils.setBadgeDotVisible
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.delay
import com.celzero.bravedns.util.Utilities.getRandomString
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.Utilities.isAtleastR
import com.celzero.bravedns.util.Utilities.isAtleastT
import com.celzero.bravedns.util.Utilities.isFdroidFlavour
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.java.KoinJavaComponent.inject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit


class MiscSettingsActivity : AppCompatActivity(R.layout.activity_misc_settings) {
    private val b by viewBinding(ActivityMiscSettingsBinding::bind)

    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()
    private val rdb by inject<RefreshDatabase>()

    private var isThemeChanged: Boolean = false
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

    companion object {
        private const val SCHEME_PACKAGE = "package"
        private const val STORAGE_PERMISSION_CODE = 23
        const val THEME_CHANGED_RESULT = 24
        private const val KEY_THEME_CHANGE = "key_theme_change"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            isThemeChanged = savedInstanceState.getBoolean(KEY_THEME_CHANGE, false)
        }

        handleFrostEffectIfNeeded(persistentState.theme)

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = false
            window.isNavigationBarContrastEnforced = false
        }

        registerForActivityResult()
        initView()
        setupClickListeners()
        setupOnBackPressed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_THEME_CHANGE, isThemeChanged)
    }

    private fun setupOnBackPressed() {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isThemeChanged) {
                    setResult(THEME_CHANGED_RESULT)
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
        onBackPressedDispatcher.addCallback(this, callback)
    }

    private fun initView() {

        if (isFdroidFlavour()) {
            b.settingsActivityCheckUpdateRl.visibility = View.GONE
            // Hide Firebase error reporting for F-Droid variant
            b.settingsFirebaseErrorReportingRl.visibility = View.GONE
        } else {
            // Show Firebase error reporting for play and website variants
            b.settingsFirebaseErrorReportingRl.visibility = View.VISIBLE
            // Firebase error reporting
            b.settingsFirebaseErrorReportingSwitch.isChecked = persistentState.firebaseErrorReportingEnabled
            b.settingsActivityCheckUpdateRl.visibility = View.VISIBLE
            // check for app updates
            b.settingsActivityCheckUpdateSwitch.isChecked = persistentState.checkForAppUpdate
        }

        // add ipInfo inc to the desc
        b.genSettingsIpInfoDesc.text =
            getString(R.string.download_ip_info_desc, getString(R.string.lbl_ipinfo_inc))

        // enable logs
        b.settingsActivityEnableLogsSwitch.isChecked = persistentState.logsEnabled
        // set log level name in the  description
        b.genSettingsGoLogDesc.text =
            Logger.LoggerLevel.fromId(persistentState.goLoggerLevel.toInt()).name.lowercase()
                .replaceFirstChar(Char::titlecase).replace("_", " ")

        // camera and microphone access
        b.settingsMicCamAccessSwitch.isChecked = persistentState.micCamAccess

        // for app locale (default system/user selected locale)
        if (isAtleastT()) {
            try {
                val localeManager = getSystemService(LocaleManager::class.java)
                val currentAppLocales: LocaleList = localeManager?.applicationLocales ?: LocaleList.getEmptyLocaleList()
                b.settingsLocaleDesc.text =
                    if (currentAppLocales.isEmpty) {
                        getString(R.string.settings_locale_desc)
                    } else {
                        currentAppLocales[0]?.displayName ?: getString(R.string.settings_locale_desc)
                    }
            } catch (e: Exception) {
                Logger.e(LOG_TAG_UI, "error getting app locales for Android T+", e)
                b.settingsLocaleDesc.text = getString(R.string.settings_locale_desc)
            }
        } else {
            val appLocales = AppCompatDelegate.getApplicationLocales()
            b.settingsLocaleDesc.text =
                if (appLocales.isEmpty) {
                    getString(R.string.settings_locale_desc)
                } else {
                    appLocales.get(0)?.displayName ?: getString(R.string.settings_locale_desc)
                }
        }
        // biometric authentication
        if (
            BiometricManager.from(this)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS
        ) {
            val bioMetricType = BioMetricType.fromValue(persistentState.biometricAuthType)
            when (bioMetricType) {
                BioMetricType.OFF -> {
                    val txt = getString(
                        R.string.two_argument_colon, getString(R.string.settings_biometric_desc),
                        getString(R.string.settings_biometric_dialog_option_0)
                    )
                    b.settingsBiometricDesc.text = txt
                }

                BioMetricType.IMMEDIATE -> {
                    val txt = getString(
                        R.string.two_argument_colon, getString(R.string.settings_biometric_desc),
                        getString(R.string.settings_biometric_dialog_option_1)
                    )
                    b.settingsBiometricDesc.text = txt
                }

                BioMetricType.FIVE_MIN -> {
                    val txt = getString(
                        R.string.two_argument_colon, getString(R.string.settings_biometric_desc),
                        getString(R.string.settings_biometric_dialog_option_2)
                    )
                    b.settingsBiometricDesc.text = txt
                }

                BioMetricType.FIFTEEN_MIN -> {
                    val txt = getString(
                        R.string.two_argument_colon, getString(R.string.settings_biometric_desc),
                        getString(R.string.settings_biometric_dialog_option_3)
                    )
                    b.settingsBiometricDesc.text = txt
                }
            }
        } else {
            b.settingsBiometricRl.visibility = View.GONE
        }

        // Auto start app after reboot
        b.settingsActivityAutoStartSwitch.isChecked = persistentState.prefAutoStartBootUp
        b.dvIpInfoSwitch.isChecked = persistentState.downloadIpInfo

        b.tombstoneAppSwitch.isChecked = persistentState.tombstoneApps

        displayPcapUi()
        displayAppThemeUi()
        displayNotificationActionUi()
        setupTokenUi()
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

    private fun showFileCreationErrorToast() {
        showToastUiCentered(this, getString(R.string.pcap_failure_toast), Toast.LENGTH_SHORT)
        // reset the pcap mode to NONE
        persistentState.pcapMode = PcapMode.NONE.id
        displayPcapUi()
    }

    private fun makePcapFile(): File? {
        return try {
            val sdf = SimpleDateFormat(BackupHelper.BACKUP_FILE_NAME_DATETIME, Locale.ROOT)
            // create folder in DOWNLOADS
            val dir =
                if (isAtleastR()) {
                    val downloadsDir =
                        Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS
                        )
                    // create folder in DOWNLOADS/Rethink
                    File(downloadsDir, Constants.PCAP_FOLDER_NAME)
                } else {
                    val downloadsDir = Environment.getExternalStorageDirectory()
                    // create folder in DOWNLOADS/Rethink
                    File(downloadsDir, Constants.PCAP_FOLDER_NAME)
                }

            // ensure directory exists
            if (!dir.exists()) {
                val created = dir.mkdirs()
                if (!created) {
                    Logger.e(LOG_TAG_VPN, "failed to create pcap directory: ${dir.absolutePath}")
                    return null
                }
            }

            // verify directory is writable
            if (!dir.canWrite()) {
                Logger.e(LOG_TAG_VPN, "pcap directory not writable: ${dir.absolutePath}")
                return null
            }

            // filename format (rethink_pcap_<DATE_FORMAT>.pcap)
            val pcapFileName: String =
                Constants.PCAP_FILE_NAME_PART + sdf.format(Date()) + Constants.PCAP_FILE_EXTENSION
            val file = File(dir, pcapFileName)

            // create the file if it doesn't exist
            if (!file.exists()) {
                val created = file.createNewFile()
                if (!created) {
                    Logger.e(LOG_TAG_VPN, "failed to create pcap file: ${file.absolutePath}")
                    return null
                }
            }

            Logger.i(LOG_TAG_VPN, "pcap file created at: ${file.absolutePath}")
            file
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "error creating pcap file ${e.message}", e)
            null
        }
    }

    private fun createAndSetPcapFile() {
        // check for storage permissions
        if (!checkStoragePermissions()) {
            // request for storage permissions
            Logger.i(LOG_TAG_VPN, "requesting for storage permissions")
            requestForStoragePermissions()
            return
        }

        Logger.i(LOG_TAG_VPN, "storage permission granted, creating pcap file")
        try {
            val file = makePcapFile()
            if (file == null) {
                showFileCreationErrorToast()
                return
            }

            // verify file was created successfully and is writable
            if (!file.exists() || !file.canWrite()) {
                Logger.e(LOG_TAG_VPN, "pcap file not writable: ${file.absolutePath}")
                showFileCreationErrorToast()
                return
            }

            Logger.i(LOG_TAG_VPN, "pcap file created successfully: ${file.absolutePath}")
            // set the file descriptor instead of fd, need to close the file descriptor
            // after tunnel creation
            appConfig.setPcap(PcapMode.EXTERNAL_FILE.id, file.absolutePath)
            displayPcapUi()
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "error in createAndSetPcapFile: ${e.message}", e)
            showFileCreationErrorToast()
        }
    }

    private val storageActivityResultLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (isAtleastR()) {
                // version 11 (R) or above
                if (Environment.isExternalStorageManager()) {
                    createAndSetPcapFile()
                } else {
                    showFileCreationErrorToast()
                }
            } else {
                // below ver 11 (R), the permission is handled via onRequestPermissionsResult
            }
        }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_CODE) {
            if (grantResults.isNotEmpty()) {
                val write = grantResults[0] == PackageManager.PERMISSION_GRANTED
                val read = grantResults[1] == PackageManager.PERMISSION_GRANTED
                if (read && write) {
                    createAndSetPcapFile()
                } else {
                    showFileCreationErrorToast()
                }
            }
        }
    }

    private fun requestForStoragePermissions() {
        // version 11 (R) or above
        if (isAtleastR()) {
            try {
                val intent = Intent()
                intent.action = Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
                val uri = Uri.fromParts(SCHEME_PACKAGE, this.packageName, null)
                intent.data = uri

                // Check if there's an activity that can handle this intent
                if (intent.resolveActivity(packageManager) != null) {
                    storageActivityResultLauncher.launch(intent)
                } else {
                    // Fallback to general settings
                    throw ActivityNotFoundException("No activity found for app-specific storage settings")
                }
            } catch (e: Exception) {
                Logger.e(LOG_TAG_VPN, "Error launching app-specific storage settings: ${e.message}")
                try {
                    val intent = Intent()
                    intent.action = Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION

                    // Check if there's an activity that can handle the fallback intent
                    if (intent.resolveActivity(packageManager) != null) {
                        storageActivityResultLauncher.launch(intent)
                    } else {
                        // No activity available to handle storage permission
                        Logger.e(LOG_TAG_VPN, "No activity found to handle storage permission request")
                        showToastUiCentered(
                            this,
                            getString(R.string.pcap_failure_toast),
                            Toast.LENGTH_LONG
                        )
                    }
                } catch (fallbackException: Exception) {
                    Logger.e(LOG_TAG_VPN, "Error launching storage settings: ${fallbackException.message}", fallbackException)
                    showToastUiCentered(
                        this,
                        getString(R.string.pcap_failure_toast),
                        Toast.LENGTH_LONG
                    )
                }
            }
        } else {
            // below version 11
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                ),
                STORAGE_PERMISSION_CODE,
            )
        }
    }


    private fun checkStoragePermissions(): Boolean {
        return if (isAtleastR()) {
            // version 11 (R) or above
            Environment.isExternalStorageManager()
        } else {
            // below version 11
            val write =
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            val read =
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            read == PackageManager.PERMISSION_GRANTED && write == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun showPcapOptionsDialog() {
        val alertBuilder = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
        alertBuilder.setTitle(getString(R.string.settings_pcap_dialog_title))
        val items =
            arrayOf(
                getString(R.string.settings_pcap_dialog_option_1),
                getString(R.string.settings_pcap_dialog_option_2),
                getString(R.string.settings_pcap_dialog_option_3),
            )
        val checkedItem = persistentState.pcapMode
        alertBuilder.setSingleChoiceItems(items, checkedItem) { dialog, which ->
            dialog.dismiss()
            if (persistentState.pcapMode == which) {
                return@setSingleChoiceItems
            }

            when (PcapMode.getPcapType(which)) {
                PcapMode.NONE -> {
                    b.settingsActivityPcapDesc.text =
                        getString(R.string.settings_pcap_dialog_option_1)
                    appConfig.setPcap(PcapMode.NONE.id)
                }

                PcapMode.LOGCAT -> {
                    b.settingsActivityPcapDesc.text =
                        getString(R.string.settings_pcap_dialog_option_2)
                    appConfig.setPcap(PcapMode.LOGCAT.id, PcapMode.ENABLE_PCAP_LOGCAT)
                }

                PcapMode.EXTERNAL_FILE -> {
                    b.settingsActivityPcapDesc.text =
                        getString(R.string.settings_pcap_dialog_option_3)
                    createAndSetPcapFile()
                }
            }
        }
        alertBuilder.create().show()
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

            Themes.TRUE_BLACK.id -> {
                b.genSettingsThemeDesc.text =
                    getString(
                        R.string.settings_selected_theme,
                        getString(R.string.settings_theme_dialog_themes_4)
                    )
            }

            Themes.LIGHT_PLUS.id -> {
                b.genSettingsThemeDesc.text =
                    getString(
                        R.string.settings_selected_theme,
                        getString(R.string.settings_theme_dialog_themes_5)
                    )
            }

            Themes.DARK_PLUS.id -> {
                b.genSettingsThemeDesc.text =
                    getString(
                        R.string.settings_selected_theme,
                        getString(R.string.settings_theme_dialog_themes_6)
                    )
            }

            Themes.DARK_FROST.id -> {
                b.genSettingsThemeDesc.text =
                    getString(
                        R.string.settings_selected_theme,
                        getString(R.string.settings_theme_dialog_themes_7)
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

    private fun displayPcapUi() {
        b.settingsActivityPcapRl.isEnabled = true
        when (PcapMode.getPcapType(persistentState.pcapMode)) {
            PcapMode.NONE -> {
                b.settingsActivityPcapDesc.text = getString(R.string.settings_pcap_dialog_option_1)
            }

            PcapMode.LOGCAT -> {
                b.settingsActivityPcapDesc.text = getString(R.string.settings_pcap_dialog_option_2)
            }

            PcapMode.EXTERNAL_FILE -> {
                b.settingsActivityPcapDesc.text = getString(R.string.settings_pcap_dialog_option_3)
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


        b.settingsGoLogRl.setOnClickListener {
            enableAfterDelay(500, b.settingsGoLogRl)
            showGoLoggerDialog()
        }


        b.settingsActivityPcapRl.setOnClickListener {
            enableAfterDelay(TimeUnit.SECONDS.toMillis(1L), b.settingsActivityPcapRl)
            showPcapOptionsDialog()
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

        b.settingsConsoleLogRl.setOnClickListener { openConsoleLogActivity() }

        b.settingsActivityAutoStartRl.setOnClickListener {
            b.settingsActivityAutoStartSwitch.isChecked =
                !b.settingsActivityAutoStartSwitch.isChecked
        }

        b.settingsActivityAutoStartSwitch.setOnCheckedChangeListener { _: CompoundButton, b: Boolean
            ->
            persistentState.prefAutoStartBootUp = b
            if (b) {
                // Enable experimental-dependent settings when experimental features are enabled
                persistentState.enableStabilityDependentSettings(this)
            }
        }

        b.settingsTaskerRl.setOnClickListener {
            showAppTriggerPackageDialog(this , onPackageSet = { packageName ->
                persistentState.appTriggerPackages = packageName
            })
        }

        b.settingsIpInfoRl.setOnClickListener {
            b.dvIpInfoSwitch.isChecked = !b.dvIpInfoSwitch.isChecked
        }

        b.dvIpInfoSwitch.setOnCheckedChangeListener { _, isChecked ->
            persistentState.downloadIpInfo = isChecked
        }

        // Firebase error reporting toggle
        b.settingsFirebaseErrorReportingRl.setOnClickListener {
            NewSettingsManager.markSettingSeen(NewSettingsManager.ERROR_REPORTING)
            b.settingsFirebaseErrorReportingSwitch.isChecked =
                !b.settingsFirebaseErrorReportingSwitch.isChecked
        }

        b.settingsFirebaseErrorReportingSwitch.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            NewSettingsManager.markSettingSeen(NewSettingsManager.ERROR_REPORTING)
            handleFirebaseErrorReportingToggle(isChecked)
        }

        b.tombstoneAppRl.setOnClickListener {
            NewSettingsManager.markSettingSeen(NewSettingsManager.TOMBSTONE_APP_SETTING)
            b.tombstoneAppSwitch.isChecked = !b.tombstoneAppSwitch.isChecked
        }

        b.tombstoneAppSwitch.setOnCheckedChangeListener { _, isChecked ->
            NewSettingsManager.markSettingSeen(NewSettingsManager.TOMBSTONE_APP_SETTING)
            persistentState.tombstoneApps = isChecked
            io { rdb.refresh(RefreshDatabase.ACTION_REFRESH_FORCE) }
        }

    }

    private fun handleFirebaseErrorReportingToggle(isChecked: Boolean) {
        if (isChecked) {
            // enable firebase error reporting
            FirebaseErrorReporting.setEnabled(true)
            b.settingsFirebaseErrorReportingSwitch.isChecked = true
            persistentState.firebaseErrorReportingEnabled = true
            setFirebaseUserId(persistentState.firebaseUserToken)
        } else {
            // disable firebase error reporting
            FirebaseErrorReporting.setEnabled(false)
            b.settingsFirebaseErrorReportingSwitch.isChecked = false
            persistentState.firebaseErrorReportingEnabled = false
        }
    }

    private fun showGoLoggerDialog() {
        // show dialog with logger options, change log level in GoVpnAdapter based on selection
        val alertBuilder = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
        alertBuilder.setTitle(getString(R.string.settings_go_log_heading))
        val items =
            arrayOf(
                getString(R.string.settings_gologger_dialog_option_0),
                getString(R.string.settings_gologger_dialog_option_1),
                getString(R.string.settings_gologger_dialog_option_2),
                getString(R.string.settings_gologger_dialog_option_3),
                getString(R.string.settings_gologger_dialog_option_4),
                getString(R.string.settings_gologger_dialog_option_5),
                getString(R.string.settings_gologger_dialog_option_6),
                getString(R.string.settings_gologger_dialog_option_7),
            )
        val checkedItem = persistentState.goLoggerLevel.toInt()
        alertBuilder.setSingleChoiceItems(items, checkedItem) { dialog, which ->
            dialog.dismiss()
            if (checkedItem == which) {
                return@setSingleChoiceItems
            }

            persistentState.goLoggerLevel = which.toLong()
            GoVpnAdapter.setLogLevel(persistentState.goLoggerLevel.toInt())
            updateConfigLevel(persistentState.goLoggerLevel)
            val logLevel = if (persistentState.goLoggerLevel.toInt() == 7) 8 else persistentState.goLoggerLevel.toInt()
            b.genSettingsGoLogDesc.text = Logger.LoggerLevel.fromId(logLevel).name.lowercase()
                    .replaceFirstChar(Char::titlecase).replace("_", " ")
        }
        alertBuilder.create().show()
    }


    private fun showBiometricDialog() {
        val alertBuilder = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
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
            val bioMetricType = BioMetricType.fromValue(which)
            when (bioMetricType) {
                BioMetricType.OFF -> {
                    val txt = getString(R.string.two_argument_colon, getString(R.string.settings_biometric_desc),
                        getString(R.string.settings_biometric_dialog_option_0))
                    b.settingsBiometricDesc.text = txt
                    persistentState.biometricAuthType = BioMetricType.OFF.action
                    persistentState.biometricAuthTime = Constants.INIT_TIME_MS
                }

                BioMetricType.IMMEDIATE -> {
                    val txt = getString(R.string.two_argument_colon, getString(R.string.settings_biometric_desc),
                        getString(R.string.settings_biometric_dialog_option_1))
                    b.settingsBiometricDesc.text = txt
                    persistentState.biometricAuthType = BioMetricType.IMMEDIATE.action
                    persistentState.biometricAuthTime = Constants.INIT_TIME_MS
                }

                BioMetricType.FIVE_MIN -> {
                    val txt = getString(R.string.two_argument_colon, getString(R.string.settings_biometric_desc),
                        getString(R.string.settings_biometric_dialog_option_2))
                    b.settingsBiometricDesc.text = txt
                    persistentState.biometricAuthType = BioMetricType.FIVE_MIN.action
                    persistentState.biometricAuthTime = System.currentTimeMillis()
                }

                BioMetricType.FIFTEEN_MIN -> {
                    val txt = getString(R.string.two_argument_colon, getString(R.string.settings_biometric_desc),
                        getString(R.string.settings_biometric_dialog_option_3))
                    b.settingsBiometricDesc.text = txt
                    persistentState.biometricAuthType = BioMetricType.FIFTEEN_MIN.action
                    persistentState.biometricAuthTime = System.currentTimeMillis()
                }
            }
            if (bioMetricType.enabled()) {
                Logger.i(LOG_TAG_UI, "biometric auth enabled, switching to app lock alias")
                LauncherSwitcher.switchLauncherAlias(applicationContext, APP_LOCK_ALIAS, HOME_ALIAS)
            } else {
                Logger.i(LOG_TAG_UI, "biometric auth disabled, switching to home alias")
                LauncherSwitcher.switchLauncherAlias(applicationContext, HOME_ALIAS, APP_LOCK_ALIAS)
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
        val builder = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
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
        val alertBuilder = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
        alertBuilder.setTitle(getString(R.string.settings_locale_dialog_title))
        val languages = getLocaleEntries()
        val items = languages.keys.toTypedArray()
        val selectedKey = AppCompatDelegate.getApplicationLocales()
            .takeIf { !it.isEmpty }
            ?.get(0)
            ?.toLanguageTag()
        var checkedItem = 0
        languages.values.forEachIndexed { index, s ->
            if (s == selectedKey) {
                checkedItem = index
            }
        }
        alertBuilder.setSingleChoiceItems(items, checkedItem) { dialog, which ->
            dialog.dismiss()
            val item = items[which]
            val tag = languages[item] ?: ""
            if (tag.isBlank()) {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
            } else {
                // https://developer.android.com/guide/topics/resources/app-languages#app-language-settings
                val locale = Locale.forLanguageTag(languages.getOrDefault(item, "en-US"))
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.create(locale))
            }
        }
        alertBuilder.setNeutralButton(getString(R.string.settings_locale_dialog_neutral)) { dialog, _ ->
            dialog.dismiss()
            openUrl(this, getString(R.string.about_translate_link))
        }
        alertBuilder.create().show()
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

          // Add default/system locale option first
          map[getString(R.string.settings_locale_dialog_default)] = ""
          
          // Add all available locales from locale_config.xml
          for (i in 0 until localeList.size()) {
              localeList[i]?.let { locale ->
                  map[locale.displayName] = locale.toLanguageTag()
              }
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

      private fun openConsoleLogActivity() {
          try {
              val intent = Intent(this, ConsoleLogActivity::class.java)
              startActivity(intent)
          } catch (e: Exception) {
              Logger.e(LOG_TAG_UI, "err opening console log activity ${e.message}", e)
          }
      }

      fun showAppTriggerPackageDialog(context: Context, onPackageSet: (String) -> Unit) {
          val editText = AppCompatEditText(context).apply {
              hint = context.getString(R.string.adv_tasker_dialog_edit_hint)
              inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
              setHorizontallyScrolling(true)
              if (persistentState.appTriggerPackages.isNotEmpty()) {
                  setText(persistentState.appTriggerPackages)
              }
              setPadding(50, 40, 50, 40)
              gravity = Gravity.TOP or Gravity.START
              android.R.style.Widget_Material_EditText
          }

          val selectableTextView = AppCompatTextView(context).apply {
              text = context.getString(R.string.adv_tasker_dialog_msg)
              setTextIsSelectable(true)
              setPadding(50, 40, 50, 0)
              textSize = 16f
          }

          val instructionsTextView = AppCompatTextView(context).apply {
              text = context.getString(R.string.adv_tasker_dialog_instructions)
              setTextIsSelectable(true)
              setPadding(50, 40, 50, 0)
              textSize = 16f
          }

          // add a LinearLayout as the single child of the ScrollView, then add the text view and
          // edit text to the LinearLayout.
          val linearLayout = LinearLayout(context).apply {
              orientation = LinearLayout.VERTICAL
              addView(selectableTextView)
              addView(editText)
              addView(instructionsTextView)
          }

          val scrollView = ScrollView(context).apply {
              setPadding(40, 10, 40, 0)
              addView(linearLayout)
          }

          AlertDialog.Builder(context)
              .setTitle(context.getString(R.string.adv_taster_title))
              .setView(scrollView)
              .setPositiveButton(context.getString(R.string.lbl_save)) { dialog, _ ->
                  val pkgName = editText.text.toString().trim()
                  if (pkgName.isNotEmpty()) {
                      onPackageSet(pkgName)
                  }
                  dialog.dismiss()
              }
              .setNegativeButton(context.getString(R.string.lbl_cancel)) { dialog, _ -> dialog.cancel() }
              .show()
      }


    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
    }

    private fun showThemeDialog() {
        val alertBuilder = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
        alertBuilder.setTitle(getString(R.string.settings_theme_dialog_title))
        val items = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                getString(R.string.settings_theme_dialog_themes_1),
                getString(R.string.settings_theme_dialog_themes_2),
                getString(R.string.settings_theme_dialog_themes_3),
                getString(R.string.settings_theme_dialog_themes_4),
                getString(R.string.settings_theme_dialog_themes_5),
                getString(R.string.settings_theme_dialog_themes_6),
                getString(R.string.settings_theme_dialog_themes_7)
            )
        } else {
            arrayOf(
                getString(R.string.settings_theme_dialog_themes_1),
                getString(R.string.settings_theme_dialog_themes_2),
                getString(R.string.settings_theme_dialog_themes_3),
                getString(R.string.settings_theme_dialog_themes_4),
                getString(R.string.settings_theme_dialog_themes_5),
                getString(R.string.settings_theme_dialog_themes_6)
            )
        }
        val checkedItem = persistentState.theme
        alertBuilder.setSingleChoiceItems(items, checkedItem) { dialog, which ->
            dialog.dismiss()
            if (persistentState.theme == which) {
                return@setSingleChoiceItems
            }

            persistentState.theme = which
            isThemeChanged = true
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
                Themes.LIGHT_PLUS.id -> {
                    setThemeRecreate(R.style.AppThemeWhitePlus)
                }
                Themes.DARK_PLUS.id -> {
                    setThemeRecreate(R.style.AppThemeTrueBlackPlus)
                }
                Themes.DARK_FROST.id -> {
                    setThemeRecreate(R.style.AppThemeTrueBlackFrost)
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
        val alertBuilder = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
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

    fun setFirebaseUserId(token: String) {
        try {
            FirebaseErrorReporting.setUserId(token)
        } catch (_: Exception) {
        }
    }

    private fun setupTokenUi() {
        val now = System.currentTimeMillis()
        val fortyFiveDaysMs = TimeUnit.DAYS.toMillis(TOKEN_REGENERATION_PERIOD_DAYS)

        var token = persistentState.firebaseUserToken
        var ts = persistentState.firebaseUserTokenTimestamp
        if (token.isBlank() || now - ts > fortyFiveDaysMs) {
            token = getRandomString(TOKEN_LENGTH)
            ts = now
            persistentState.firebaseUserToken = token
            persistentState.firebaseUserTokenTimestamp = ts
        }
    }

    override fun onResume() {
        super.onResume()
        // app notification permission android 13
        showEnableNotificationSettingIfNeeded()
        checkMicCamAccessRule()
        showNewBadgeIfNeeded()
    }

    private fun showNewBadgeIfNeeded() {
        val errorReporting = NewSettingsManager.shouldShowBadge(NewSettingsManager.ERROR_REPORTING)
        b.genSettingsFirebaseErrorReportingTxt.setBadgeDotVisible(this, errorReporting)
        val tombstoneSetting = NewSettingsManager.shouldShowBadge(NewSettingsManager.TOMBSTONE_APP_SETTING)
        b.tombstoneAppTxt.setBadgeDotVisible(this, tombstoneSetting)
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

        try {
            notificationPermissionResult.launch(Manifest.permission.POST_NOTIFICATIONS)
        } catch (e: ActivityNotFoundException) {
            Logger.e(
                LOG_TAG_VPN,
                "ActivityNotFoundException while requesting notification permission: ${e.message}",
                e
            )
            showToastUiCentered(
                this,
                getString(R.string.blocklist_update_check_failure),
                Toast.LENGTH_SHORT
            )
            // Fallback: try opening notification settings directly
            invokeAndroidNotificationSetting()
        } catch (e: Exception) {
            Logger.e(
                LOG_TAG_VPN,
                "Exception while requesting notification permission: ${e.message}",
                e
            )
            showToastUiCentered(
                this,
                getString(R.string.blocklist_update_check_failure),
                Toast.LENGTH_SHORT
            )
        }
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
                intent.data = "$SCHEME_PACKAGE:$packageName".toUri()
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

    private fun io(f: suspend () -> Unit) = lifecycleScope.launch(Dispatchers.IO) { f() }
}
