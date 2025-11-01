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

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.databinding.ActivityAdvancedSettingBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import org.koin.android.ext.android.inject

class AdvancedSettingActivity : AppCompatActivity(R.layout.activity_advanced_setting) {
    private val persistentState by inject<PersistentState>()
    private val b by viewBinding(ActivityAdvancedSettingBinding::bind)

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)

        handleFrostEffectIfNeeded(persistentState.theme)
        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = false
            window.isNavigationBarContrastEnforced = false
        }
        initView()
        setupClickListeners()
    }

    private fun initView() {

        if (DEBUG) {
            b.settingsExperimentalRl.visibility = View.VISIBLE
            b.dvExperimentalSwitch.isChecked = persistentState.nwEngExperimentalFeatures
            b.settingsAutoDialRl.visibility = View.VISIBLE
            b.dvAutoDialSwitch.isChecked = persistentState.autoDialsParallel
            b.settingsPanicRandRl.visibility = View.VISIBLE
            b.dvPanicRandSwitch.isChecked = persistentState.panicRandom
        } else {
            b.settingsExperimentalRl.visibility = View.GONE
            b.settingsAutoDialRl.visibility = View.GONE
            b.settingsPanicRandRl.visibility = View.GONE
        }

    }

    private fun setupClickListeners() {

        b.settingsExperimentalRl.setOnClickListener {
            b.dvExperimentalSwitch.isChecked = !b.dvExperimentalSwitch.isChecked
        }

        b.dvExperimentalSwitch.setOnCheckedChangeListener { _, isChecked ->
            persistentState.nwEngExperimentalFeatures = isChecked
        }

        b.settingsClearResidueRl.setOnClickListener {
            // TODO: show list of files to be deleted and confirm deletion
            // clearResidueAfterConfirmation()
        }

        b.settingsAutoDialRl.setOnClickListener {
            b.dvAutoDialSwitch.isChecked = !b.dvAutoDialSwitch.isChecked
        }

        b.dvAutoDialSwitch.setOnCheckedChangeListener { _, isChecked ->
            persistentState.autoDialsParallel = isChecked
        }

        b.settingsPanicRandRl.setOnClickListener {
            b.dvPanicRandSwitch.isChecked = !b.dvPanicRandSwitch.isChecked
        }

        b.dvPanicRandSwitch.setOnCheckedChangeListener { _, isChecked ->
            persistentState.panicRandom = isChecked
        }
    }

    /*private fun clearResidueAfterConfirmation() {
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

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }*/
}
