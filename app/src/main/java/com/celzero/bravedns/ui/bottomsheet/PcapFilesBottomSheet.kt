/*
 * Copyright 2026 RethinkDNS and its authors
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
package com.celzero.bravedns.ui.bottomsheet

import androidx.compose.runtime.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.themeColor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.core.content.FileProvider
import com.celzero.bravedns.R
import com.celzero.bravedns.scheduler.BugReportZipper
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import com.celzero.bravedns.util.PcapMode
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PcapFilesBottomSheet : BaseBottomSheetDialogFragment() {

    interface PcapModeSelectListener {
        fun onPcapModeSelected(mode: PcapMode)
    }

    // The listener is set programmatically before show(); after FragmentManager restoration
    // the field is null, so fall back to the host activity when it implements the callback.
    var modeSelectListener: PcapModeSelectListener? = null
        get() = field ?: activity as? PcapModeSelectListener

    private var selectedMode by mutableStateOf(PcapMode.NONE)
    private var folderPath by mutableStateOf("")
    private var pendingDelete by mutableStateOf<List<File>?>(null)

    private val persistentState by inject<PersistentState>()

    private val pcapFiles = mutableStateListOf<File>()
    private var loadJob: Job? = null

    companion object {
        private const val ALPHA_ENABLED = 1.0f
        private const val ALPHA_DISABLED = 0.5f
        private const val BYTES_IN_KB = 1024L
        private const val BYTES_IN_MB = 1024L * 1024L
        private const val MB_DIVISOR = 1024.0 * 1024.0
        private const val DETAILS_SEPARATOR = " · "
        private const val PCAP_MIME_TYPE = "application/vnd.tcpdump.pcap"
        private const val EXTERNAL_STORAGE_PROVIDER = "com.android.externalstorage.documents"
    }

    override fun getTheme(): Int =
        Themes.getBottomSheetCurrentTheme(isDarkThemeOn(), persistentState.theme)

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { RethinkTheme { PcapContent() } }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            Themes.applyBottomSheetSystemBarAppearance(window, isDarkThemeOn(), persistentState.theme)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        loadPcapFiles()
    }

    override fun onDestroyView() {
        loadJob?.cancel()
        loadJob = null
        pendingDelete = null
        super.onDestroyView()
    }

    private fun initView() {
        selectCurrentMode()
        folderPath = pcapDir()?.absolutePath.orEmpty()
    }

    private fun pcapDir(): File? {
        val base = requireContext().getExternalFilesDir(null) ?: return null
        return File(base, Constants.PCAP_FOLDER_NAME)
    }

    private fun loadPcapFiles() {
        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val files = withContext(Dispatchers.IO) {
                    val dir = pcapDir()
                    dir?.listFiles()?.filter { it.isFile }
                        ?.sortedByDescending { it.lastModified() }
                } ?: emptyList()

                pcapFiles.clear()
                pcapFiles.addAll(files)

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(LOG_TAG_UI, "err loading pcap files: ${e.message}", e)
            }
        }
    }

    private fun selectCurrentMode() {
        selectedMode = PcapMode.getPcapType(persistentState.pcapMode)
    }

    private fun onModePicked(mode: PcapMode) {
        if (PcapMode.getPcapType(persistentState.pcapMode) == mode) return

        modeSelectListener?.onPcapModeSelected(mode)
        // re-sync the radio state with whatever the listener actually persisted
        // (the requested mode may have been rejected, e.g. file creation failure)
        selectCurrentMode()
        loadPcapFiles()
    }

    private fun copyPcapFolderPath() {
        val dir = pcapDir() ?: return
        try {
            val clipboard =
                requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("pcap_folder", dir.absolutePath))
            showToastUiCentered(
                requireContext(),
                getString(R.string.config_add_success_toast),
                Toast.LENGTH_SHORT
            )
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "err copying pcap folder path: ${e.message}", e)
        }
    }

    private fun openPcapFolder() {
        val dirUri = buildPcapFolderUri() ?: return
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(dirUri, DocumentsContract.Document.MIME_TYPE_DIR)
            }
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Logger.e(LOG_TAG_UI, "no activity to open pcap folder: ${e.message}")
            showToastUiCentered(
                requireContext(),
                getString(R.string.pcap_open_failed_toast),
                Toast.LENGTH_SHORT
            )
        } catch (e: SecurityException) {
            Logger.e(LOG_TAG_UI, "denied opening pcap folder: ${e.message}")
            // provider refused the deep link; the path row above still allows copy
            showToastUiCentered(
                requireContext(),
                getString(R.string.pcap_open_failed_toast),
                Toast.LENGTH_SHORT
            )
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "err opening pcap folder: ${e.message}", e)
            showToastUiCentered(
                requireContext(),
                getString(R.string.pcap_open_failed_toast),
                Toast.LENGTH_SHORT
            )
        }
    }

    private fun buildPcapFolderUri(): Uri? {
        return try {
            val dir = pcapDir() ?: return null
            val storageRoot =
                Environment.getExternalStorageDirectory()?.absolutePath ?: return null
            val relativePath = dir.absolutePath
                .removePrefix("$storageRoot/")
                .trimStart('/')
            if (relativePath.isEmpty()) return null
            DocumentsContract.buildDocumentUri(
                EXTERNAL_STORAGE_PROVIDER,
                "primary:$relativePath"
            )
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "err building pcap folder uri: ${e.message}")
            null
        }
    }

    private fun showDeleteConfirmationDialog(file: File) { pendingDelete = listOf(file) }

    private fun showDeleteAllConfirmationDialog() {
        if (pcapFiles.isNotEmpty()) pendingDelete = pcapFiles.toList()
    }

    private fun deletePcapFiles(files: List<File>) {
        if (files.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val (failed, activeDeleted) = withContext(Dispatchers.IO) {
                val failures = mutableListOf<File>()
                files.forEach { f ->
                    if (!f.delete()) failures.add(f)
                }
                // capture is stopped when the active pcap file no longer exists
                val isActiveFile = files.any { it.absolutePath == persistentState.pcapFilePath }
                Pair(failures, isActiveFile)
            }

            if (activeDeleted && PcapMode.getPcapType(persistentState.pcapMode) == PcapMode.EXTERNAL_FILE) {
                modeSelectListener?.onPcapModeSelected(PcapMode.NONE)
                selectCurrentMode()
            }

            if (files.size == 1) {
                val file = files[0]
                if (failed.isEmpty()) {
                    showToastUiCentered(
                        requireContext(),
                        getString(R.string.univ_ip_delete_individual_toast, file.name),
                        Toast.LENGTH_SHORT
                    )
                } else {
                    showToastUiCentered(
                        requireContext(),
                        getString(R.string.pcap_file_delete_failed_toast, file.name),
                        Toast.LENGTH_SHORT
                    )
                }
            } else if (failed.isNotEmpty()) {
                showToastUiCentered(
                    requireContext(),
                    getString(R.string.pcap_file_delete_failed_toast, failed[0].name),
                    Toast.LENGTH_SHORT
                )
            }
            loadPcapFiles()
        }
    }

    private fun formatFileSize(size: Long): String {
        return when {
            size < BYTES_IN_KB -> "$size B"
            size < BYTES_IN_MB -> "${size / BYTES_IN_KB} KB"
            else -> String.format(Locale.US, "%.1f MB", size / MB_DIVISOR)
        }
    }

    private fun formatDate(timestamp: Long): String {
        return SimpleDateFormat("MMM d, yyyy HH:mm", Locale.US).format(Date(timestamp))
    }

    private fun openFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                requireContext(),
                BugReportZipper.FILE_PROVIDER_NAME,
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, PCAP_MIME_TYPE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, file.name))
        } catch (e: IllegalArgumentException) {
            // file path is not covered by the FileProvider config
            Logger.e(LOG_TAG_UI, "err sharing pcap file(${file.name}): ${e.message}", e)
            showToastUiCentered(
                requireContext(),
                getString(R.string.pcap_open_failed_toast),
                Toast.LENGTH_SHORT
            )
        } catch (e: ActivityNotFoundException) {
            // no viewer installed that handles pcap files
            Logger.e(LOG_TAG_UI, "no viewer for pcap file(${file.name}): ${e.message}")
            showToastUiCentered(
                requireContext(),
                getString(R.string.pcap_open_failed_toast),
                Toast.LENGTH_SHORT
            )
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "err opening pcap file(${file.name}): ${e.message}", e)
            showToastUiCentered(
                requireContext(),
                getString(R.string.pcap_open_failed_toast),
                Toast.LENGTH_SHORT
            )
        }
    }

    @Composable
    private fun PcapContent() {
        LazyColumn(Modifier.fillMaxWidth().background(themeColor(R.attr.background)).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 20.dp)) {
            item { Text(stringResource(R.string.settings_pcap_dialog_title), style = MaterialTheme.typography.titleLarge) }
            item { Text(stringResource(R.string.pcap_files_desc)) }
            item { Text(stringResource(R.string.pcap_mode_section_title), style = MaterialTheme.typography.titleMedium) }
            item {
                listOf(PcapMode.NONE to R.string.settings_pcap_dialog_option_1, PcapMode.LOGCAT to R.string.settings_pcap_dialog_option_2, PcapMode.EXTERNAL_FILE to R.string.settings_pcap_dialog_option_3).forEach { (mode, title) ->
                    Row(Modifier.fillMaxWidth().testTag("pcap_mode_${mode.name}").selectable(selected = selectedMode == mode, role = Role.RadioButton, onClick = { onModePicked(mode) }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selectedMode == mode, onClick = null)
                        Text(stringResource(title), Modifier.padding(start = 12.dp))
                    }
                }
            }
            item { Text(stringResource(R.string.pcap_files_section_title), style = MaterialTheme.typography.titleMedium) }
            item { Text(stringResource(R.string.two_argument_dot, pcapFiles.size.toString(), formatFileSize(pcapFiles.sumOf { it.length() }))) }
            item { Text(folderPath, Modifier.fillMaxWidth().clickable { copyPcapFolderPath() }.padding(vertical = 8.dp)) }
            if (pcapFiles.isEmpty()) item { Text(stringResource(R.string.pcap_empty_state)) }
            items(pcapFiles, key = { it.absolutePath }) { file ->
                val active = selectedMode == PcapMode.EXTERNAL_FILE && file.absolutePath == persistentState.pcapFilePath
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(file.name, style = MaterialTheme.typography.titleSmall)
                        Text(listOfNotNull(if (active) stringResource(R.string.pcap_active_file_label) else null, formatFileSize(file.length()), formatDate(file.lastModified())).joinToString(DETAILS_SEPARATOR), style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { openFile(file) }) { Icon(painterResource(R.drawable.ic_logs), stringResource(R.string.pcap_view_file_desc)) }
                    TextButton(onClick = { showDeleteConfirmationDialog(file) }) { Text(stringResource(R.string.lbl_delete)) }
                }
            }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { openPcapFolder() }) { Text(stringResource(R.string.pcap_open_folder)) }
                TextButton(onClick = { showDeleteAllConfirmationDialog() }, enabled = pcapFiles.isNotEmpty()) { Text(stringResource(R.string.univ_ip_delete_dialog_positive)) }
            } }
        }
        pendingDelete?.let { files ->
            AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text(stringResource(R.string.lbl_delete)) },
                text = { Text(if (files.size == 1) stringResource(R.string.pcap_delete_file_confirmation, files.single().name) else stringResource(R.string.pcap_delete_all_confirmation)) },
                confirmButton = { TextButton(onClick = { pendingDelete?.let { requested -> pendingDelete = null; deletePcapFiles(requested) } }) { Text(stringResource(R.string.lbl_delete)) } },
                dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.lbl_cancel)) } })
        }
    }
}
