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

import Logger
import Logger.LOG_TAG_UI
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.ActivityAdvancedSettingBinding
import com.celzero.bravedns.scheduler.BugReportZipper.BUG_REPORT_DIR_NAME
import com.celzero.bravedns.scheduler.BugReportZipper.BUG_REPORT_ZIP_FILE_NAME
import com.celzero.bravedns.scheduler.EnhancedBugReport.TOMBSTONE_DIR_NAME
import com.celzero.bravedns.scheduler.EnhancedBugReport.TOMBSTONE_ZIP_FILE_NAME
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.util.Constants.Companion.LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME
import com.celzero.bravedns.util.Constants.Companion.REMOTE_BLOCKLIST_DOWNLOAD_FOLDER_NAME
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities.blocklistCanonicalPath
import com.celzero.bravedns.util.Utilities.deleteRecursive
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.Utilities.isAtleastS
import com.celzero.bravedns.util.Utilities.isPlayStoreFlavour
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import java.io.File

class AdvancedSettingActivity : AppCompatActivity(R.layout.activity_advanced_setting) {
    private val persistentState by inject<PersistentState>()
    private val b by viewBinding(ActivityAdvancedSettingBinding::bind)

    // Handler to update the dialer timeout value when the seekbar is moved
    private val handler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null

    companion object {
        private const val TAG = "AdvSetAct"
        private const val ONE_SEC = 1000L
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = false
            window.isNavigationBarContrastEnforced = false
        }
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

        b.dvTcpKeepAliveSwitch.isChecked = persistentState.tcpKeepAlive
        b.settingsActivitySlowdownSwitch.isChecked = persistentState.slowdownMode

        b.dvExperimentalSwitch.isChecked = persistentState.nwEngExperimentalFeatures
        b.dvAutoDialSwitch.isChecked = persistentState.autoDialsParallel
        b.dvIpInfoSwitch.isChecked = persistentState.downloadIpInfo
        updateDialerTimeOutUi()
    }

    private fun updateDialerTimeOutUi() {
        val valueMin = persistentState.dialTimeoutSec / 60
        Logger.d(LOG_TAG_UI, "$TAG; dialer timeout value: $valueMin, persistentState: ${persistentState.dialTimeoutSec}")
        val displayText = if (valueMin == 0) {
            getString(R.string.dialer_timeout_desc, getString(R.string.lbl_disabled))
        } else {
            getString(R.string.dialer_timeout_desc, "$valueMin ${getString(R.string.lbl_min)}")
        }
        b.dvTimeoutDesc.text = displayText
        Logger.d(LOG_TAG_UI, "$TAG; dialer timeout value: $valueMin, progress: ${b.dvTimeoutSeekbar.progress}")
        if (valueMin == b.dvTimeoutSeekbar.progress) return
        b.dvTimeoutSeekbar.progress = valueMin
    }

    private fun updateDialerTimeOut(valueMin: Int) {
        persistentState.dialTimeoutSec = valueMin * 60
        updateDialerTimeOutUi()
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

        b.settingsConsoleLogRl.setOnClickListener { openConsoleLogActivity() }

        b.settingsActivityAutoStartRl.setOnClickListener {
            b.settingsActivityAutoStartSwitch.isChecked =
                !b.settingsActivityAutoStartSwitch.isChecked
        }

        b.settingsActivityAutoStartSwitch.setOnCheckedChangeListener { _: CompoundButton, b: Boolean
            ->
            persistentState.prefAutoStartBootUp = b
        }

        b.dvTcpKeepAliveSwitch.setOnCheckedChangeListener { _, isChecked ->
            persistentState.tcpKeepAlive = isChecked
        }

        b.dvTcpKeepAliveRl.setOnClickListener { b.dvTcpKeepAliveSwitch.isChecked = !b.dvTcpKeepAliveSwitch.isChecked }

        b.settingsActivitySlowdownRl.setOnClickListener {
            b.settingsActivitySlowdownSwitch.isChecked = !b.settingsActivitySlowdownSwitch.isChecked
        }

        b.settingsActivitySlowdownSwitch.setOnCheckedChangeListener { _, isChecked ->
            persistentState.slowdownMode = isChecked
        }

        b.dvExperimentalSwitch.setOnCheckedChangeListener { _, isChecked ->
            persistentState.nwEngExperimentalFeatures = isChecked
        }

        b.settingsClearResidueRl.setOnClickListener {
            clearResidueAfterConfirmation()
        }

        b.settingsAutoDialRl.setOnClickListener {
            b.dvAutoDialSwitch.isChecked = !b.dvAutoDialSwitch.isChecked
        }

        b.dvAutoDialSwitch.setOnCheckedChangeListener { _, isChecked ->
            persistentState.autoDialsParallel = isChecked
        }

        b.settingsIpInfoRl.setOnClickListener {
            b.dvIpInfoSwitch.isChecked = !b.dvIpInfoSwitch.isChecked
        }

        b.dvIpInfoSwitch.setOnCheckedChangeListener { _, isChecked ->
            persistentState.downloadIpInfo = isChecked
        }

        b.settingsTaskerRl.setOnClickListener {
            showAppTriggerPackageDialog(this , onPackageSet = { packageName ->
                persistentState.appTriggerPackages = packageName
            })
        }

        b.dvTimeoutSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                handler.removeCallbacks(updateRunnable ?: Runnable {})

                updateRunnable = Runnable {
                    updateDialerTimeOut(progress)
                }

                handler.postDelayed(updateRunnable!!, ONE_SEC)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                handler.removeCallbacks(updateRunnable ?: Runnable {})
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                updateRunnable?.let {
                    handler.removeCallbacks(it)
                    handler.post(it)
                }
            }
        })
    }

    private fun clearResidueAfterConfirmation() {
        val alertBuilder = MaterialAlertDialogBuilder(this)
        alertBuilder.setTitle(getString(R.string.clear_residue_dialog_heading))
        alertBuilder.setMessage(getString(R.string.clear_residue_dialog_desc))
        alertBuilder.setCancelable(false)
        alertBuilder.setPositiveButton(getString(R.string.lbl_proceed)) { dialog, _ ->
            dialog.dismiss()
            clearResidue()
        }
        alertBuilder.setNegativeButton(getString(R.string.lbl_cancel)) { dialog, _ ->
            dialog.dismiss()
        }
        alertBuilder.create().show()
    }

    private fun clearResidue() {
        // when app is in play store version delete the local blocklists
        if (isPlayStoreFlavour()) {
            // delete the local blocklists
            deleteLocalBlocklists()
        }
        deleteUnusedBlocklists()
        deleteLogs()
        io { WireguardManager.deleteResidueWgs() }
    }

    private fun deleteLocalBlocklists() {
        // in play version so delete the local blocklists
        val path = blocklistCanonicalPath(this, LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME)
        val dir = File(path)
        val res = deleteRecursive(dir)
        // reset the local blocklists
        if (res) {
            persistentState.localBlocklistStamp = ""
            persistentState.localBlocklistTimestamp = 0L
        }
        Logger.i(LOG_TAG_UI, "$TAG; local blocklists deleted, path: $path")
    }

    private fun deleteUnusedBlocklists() {
        Logger.v(LOG_TAG_UI, "$TAG; deleting unused blocklists")
        // delete all the blocklists other than the one in the settings
        val localTsDir = persistentState.localBlocklistTimestamp
        val remoteTsDir = persistentState.remoteBlocklistTimestamp
        val localBlocklistDir = File(blocklistCanonicalPath(this, LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME))
        val remoteBlocklistDir = File(blocklistCanonicalPath(this, REMOTE_BLOCKLIST_DOWNLOAD_FOLDER_NAME))
        // all the blocklists are named with their timestamp as directory name
        if (localBlocklistDir.exists() && localBlocklistDir.isDirectory) {
            localBlocklistDir.listFiles()?.forEach { file ->
                if (file.isDirectory && file.name.toLongOrNull() != localTsDir) {
                    if (deleteRecursive(file)) {
                        Logger.i(LOG_TAG_UI, "$TAG; deleted unused local blocklist: ${file.name}")
                    } else {
                        Logger.w(LOG_TAG_UI, "$TAG; failed to delete unused local blocklist: ${file.name}")
                    }
                }
            }
        }
        if (remoteBlocklistDir.exists() && remoteBlocklistDir.isDirectory) {
            remoteBlocklistDir.listFiles()?.forEach { file ->
                if (file.isDirectory && file.name.toLongOrNull() != remoteTsDir) {
                    if (deleteRecursive(file)) {
                        Logger.i(LOG_TAG_UI, "$TAG; deleted unused remote blocklist: ${file.name}")
                    } else {
                        Logger.w(LOG_TAG_UI, "$TAG; failed to delete unused remote blocklist: ${file.name}")
                    }
                }
            }
        }
    }

    private fun deleteLogs(): Boolean {
        Logger.v(LOG_TAG_UI, "$TAG; deleting all log files before backup")
        try {
            // delete tombstone logs
            val tombstoneDir = File(filesDir.canonicalPath + File.separator + TOMBSTONE_DIR_NAME)
            if (tombstoneDir.exists() && tombstoneDir.isDirectory) {
                tombstoneDir.listFiles()?.forEach { file ->
                    if (file.delete()) {
                        Logger.i(LOG_TAG_UI, "$TAG; deleted log file: ${file.name}")
                    } else {
                        Logger.w(LOG_TAG_UI, "$TAG; failed to delete log file: ${file.name}")
                    }
                }
            }

            // delete bugreport logs
            val bugreportDir = File(filesDir.canonicalPath + File.separator + BUG_REPORT_DIR_NAME)
            if (bugreportDir.exists() && bugreportDir.isDirectory) {
                deleteRecursive(bugreportDir)
                Logger.i(LOG_TAG_UI, "$TAG; deleted bugreport logs from: ${bugreportDir.canonicalPath}")
            }

            // delete zip files
            val tombstoneZip = File(filesDir.canonicalPath + File.separator + TOMBSTONE_ZIP_FILE_NAME)
            if (tombstoneZip.exists()) {
                tombstoneZip.delete()
                Logger.i(LOG_TAG_UI, "$TAG; deleted tombstone zip file: ${tombstoneZip.canonicalPath}")
            }

            val bugreportZip = File(filesDir.canonicalPath + File.separator + BUG_REPORT_ZIP_FILE_NAME)
            if (bugreportZip.exists()) {
                bugreportZip.delete()
                Logger.i(LOG_TAG_UI, "$TAG; deleted bugreport zip file: ${bugreportZip.canonicalPath}")
            }

            Logger.i(LOG_TAG_UI, "$TAG; deleted all log files successfully")
            return true
        } catch (e: Exception) {
            Logger.w(LOG_TAG_UI, "$TAG; err while deleting log files: ${e.message}")
            return false
        }
    }

    private fun openConsoleLogActivity() {
        try {
            val intent = Intent(this, ConsoleLogActivity::class.java)
            startActivity(intent)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "$TAG; err opening console log activity ${e.message}", e)
        }
    }

    fun showAppTriggerPackageDialog(context: Context, onPackageSet: (String) -> Unit) {
        val editText = EditText(context).apply {
            hint = context.getString(R.string.adv_tasker_dialog_edit_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setLines(6)
            setHorizontallyScrolling(false)
            if (persistentState.appTriggerPackages.isNotEmpty()) {
                setText(persistentState.appTriggerPackages)
            }
            gravity = Gravity.TOP or Gravity.START
        }

        val selectableTextView = AppCompatTextView(context).apply {
            text = context.getString(R.string.adv_tasker_dialog_msg)
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
        }

        val scrollView = ScrollView(context).apply {
            setPadding(40, 10, 40, 0)
            addView(linearLayout)
        }

        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.adv_tasker_dialog_title))
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


    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }
}
