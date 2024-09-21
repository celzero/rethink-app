/*
 * Copyright 2024 RethinkDNS and its authors
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

import Logger.LOG_TAG_UI
import Logger.LOG_TAG_VPN
import Logger.updateConfigLevel
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.CompoundButton
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.backup.BackupHelper
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.databinding.ActivityAdvancedSettingBinding
import com.celzero.bravedns.net.go.GoVpnAdapter
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.PcapMode
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastR
import com.celzero.bravedns.util.Utilities.isAtleastS
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import org.koin.android.ext.android.inject

class AdvancedSettingActivity : AppCompatActivity(R.layout.activity_advanced_setting) {
    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()
    private val b by viewBinding(ActivityAdvancedSettingBinding::bind)

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    companion object {
        private const val SCHEME_PACKAGE = "package"
        private const val STORAGE_PERMISSION_CODE = 23
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)
        initView()
        setupClickListeners()
    }

    private fun initView() {
        b.dvWgListenPortSwitch.isChecked = !persistentState.randomizeListenPort
        // Auto start app after reboot
        b.settingsActivityAutoStartSwitch.isChecked = persistentState.prefAutoStartBootUp
        // check if the device is running on Android 12 or above for EIMF
        if (isAtleastS()) {
            // endpoint independent mapping (eim) / endpoint independent filtering (eif)
            b.dvEimfRl.visibility = View.VISIBLE
            b.dvEimfSwitch.isChecked = persistentState.endpointIndependence
        } else {
            b.dvEimfRl.visibility = View.GONE
        }
        if (DEBUG) {
            b.dvTcpKeepAliveRl.visibility = View.VISIBLE
            b.dvTcpKeepAliveSwitch.isChecked = persistentState.tcpKeepAlive
        } else {
            b.dvTcpKeepAliveRl.visibility = View.GONE
        }
        displayPcapUi()
    }

    private fun setupClickListeners() {

        b.dvWgListenPortSwitch.setOnCheckedChangeListener { _, isChecked ->
            persistentState.randomizeListenPort = !isChecked
        }

        b.dvWgListenPortRl.setOnClickListener {
            b.dvWgListenPortSwitch.isChecked = !b.dvWgListenPortSwitch.isChecked
        }

        b.dvEimfSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!isAtleastS()) {
                return@setOnCheckedChangeListener
            }

            persistentState.endpointIndependence = isChecked
        }

        b.dvEimfRl.setOnClickListener { b.dvEimfSwitch.isChecked = !b.dvEimfSwitch.isChecked }

        b.settingsAntiCensorshipRl.setOnClickListener {
            val intent = Intent(this, AntiCensorshipActivity::class.java)
            startActivity(intent)
        }

        b.settingsGoLogRl.setOnClickListener {
            enableAfterDelay(500, b.settingsGoLogRl)
            showGoLoggerDialog()
        }

        b.settingsConsoleLogRl.setOnClickListener { openConsoleLogActivity() }

        b.settingsActivityAutoStartRl.setOnClickListener {
            b.settingsActivityAutoStartSwitch.isChecked =
                !b.settingsActivityAutoStartSwitch.isChecked
        }

        b.settingsActivityAutoStartSwitch.setOnCheckedChangeListener { _: CompoundButton, b: Boolean
            ->
            persistentState.prefAutoStartBootUp = b
        }

        b.settingsActivityPcapRl.setOnClickListener {
            enableAfterDelay(TimeUnit.SECONDS.toMillis(1L), b.settingsActivityPcapRl)
            showPcapOptionsDialog()
        }

        b.dvTcpKeepAliveSwitch.setOnCheckedChangeListener { _, isChecked ->
            persistentState.tcpKeepAlive = isChecked
        }

        b.dvTcpKeepAliveRl.setOnClickListener { b.dvTcpKeepAliveSwitch.isChecked = !b.dvTcpKeepAliveSwitch.isChecked }
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

    private fun showPcapOptionsDialog() {
        val alertBuilder = MaterialAlertDialogBuilder(this)
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
            // set the file descriptor instead of fd, need to close the file descriptor
            // after tunnel creation
            appConfig.setPcap(PcapMode.EXTERNAL_FILE.id, file.absolutePath)
        } catch (e: Exception) {
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
                storageActivityResultLauncher.launch(intent)
            } catch (e: Exception) {
                val intent = Intent()
                intent.action = Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                storageActivityResultLauncher.launch(intent)
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
            if (!dir.exists()) {
                dir.mkdirs()
            }
            // filename format (rethink_pcap_<DATE_FORMAT>.pcap)
            val pcapFileName: String =
                Constants.PCAP_FILE_NAME_PART + sdf.format(Date()) + Constants.PCAP_FILE_EXTENSION
            val file = File(dir, pcapFileName)
            // just in case, create the parent dir if it doesn't exist
            if (file.parentFile?.exists() != true) file.parentFile?.mkdirs()
            // create the file if it doesn't exist
            if (!file.exists()) {
                file.createNewFile()
            }
            file
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "error creating pcap file ${e.message}", e)
            null
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

    private fun openConsoleLogActivity() {
        try {
            val intent = Intent(this, ConsoleLogActivity::class.java)
            startActivity(intent)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "error opening console log activity ${e.message}", e)
        }
    }

    private fun showGoLoggerDialog() {
        // show dialog with logger options, change log level in GoVpnAdapter based on selection
        val alertBuilder = MaterialAlertDialogBuilder(this)
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
        }
        alertBuilder.create().show()
    }

    private fun enableAfterDelay(ms: Long, vararg views: View) {
        for (v in views) v.isEnabled = false

        Utilities.delay(ms, lifecycleScope) {
            if (isFinishing) return@delay

            for (v in views) v.isEnabled = true
        }
    }
}
