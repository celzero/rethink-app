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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.BottomSheetPcapFilesBinding
import com.celzero.bravedns.databinding.ItemPcapFileBinding
import com.celzero.bravedns.scheduler.BugReportZipper
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import com.celzero.bravedns.util.PcapMode
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pcap management sheet: pick the capture mode (off / logcat / file) and view or
 * delete the pcap files collected under the app-specific external pcap folder.
 * Mode changes and capture-file lifecycle are owned by the listener (usually the
 * settings activity); this sheet only reports the requested mode and mirrors the
 * persisted state afterwards.
 */
class PcapFilesBottomSheet : BaseBottomSheetDialogFragment() {

    interface PcapModeSelectListener {
        fun onPcapModeSelected(mode: PcapMode)
    }

    var modeSelectListener: PcapModeSelectListener? = null

    private var _binding: BottomSheetPcapFilesBinding? = null
    private val b
        get() = checkNotNull(_binding)
        { "Binding accessed outside of view lifecycle" }

    private val persistentState by inject<PersistentState>()

    private val pcapFiles = mutableListOf<File>()
    private lateinit var adapter: PcapFilesAdapter

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
        _binding = BottomSheetPcapFilesBinding.inflate(inflater, container, false)
        return b.root
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
        super.onDestroyView()
        _binding = null
    }

    private fun initView() {
        adapter = PcapFilesAdapter(pcapFiles)
        b.pcapFilesRecycler.layoutManager = LinearLayoutManager(requireContext())
        b.pcapFilesRecycler.adapter = adapter

        b.pcapModeOffRl.setOnClickListener { onModePicked(PcapMode.NONE) }
        b.pcapModeLogcatRl.setOnClickListener { onModePicked(PcapMode.LOGCAT) }
        b.pcapModeFileRl.setOnClickListener { onModePicked(PcapMode.EXTERNAL_FILE) }

        b.pcapFolderPath.setOnClickListener {
            copyPcapFolderPath()
        }

        b.pcapOpenFolderBtn.setOnClickListener {
            openPcapFolder()
        }

        b.pcapDeleteAllBtn.setOnClickListener {
            showDeleteAllConfirmationDialog()
        }

        selectCurrentMode()
        showFolderPath()
    }

    private fun showFolderPath() {
        val dir = pcapDir() ?: return
        b.pcapFolderPath.text = dir.absolutePath
    }

    private fun pcapDir(): File? {
        val base = requireContext().getExternalFilesDir(null) ?: return null
        return File(base, Constants.PCAP_FOLDER_NAME)
    }

    private fun loadPcapFiles() {
        lifecycleScope.launch {
            try {
                val files = withContext(Dispatchers.IO) {
                    val dir = pcapDir()
                    dir?.listFiles()?.filter { it.isFile }
                        ?.sortedByDescending { it.lastModified() }
                } ?: emptyList()

                pcapFiles.clear()
                pcapFiles.addAll(files)
                adapter.notifyDataSetChanged()
                updateFilesHeader()
            } catch (e: Exception) {
                Logger.e(LOG_TAG_UI, "err loading pcap files: ${e.message}", e)
            }
        }
    }

    private fun updateFilesHeader() {
        val totalSize = pcapFiles.sumOf { it.length() }
        b.pcapFilesSummary.text =
            getString(R.string.two_argument_dot, pcapFiles.size.toString(), formatFileSize(totalSize))
        val hasFiles = pcapFiles.isNotEmpty()
        b.pcapEmptyLayout.visibility = if (hasFiles) View.GONE else View.VISIBLE
        b.pcapFilesRecycler.visibility = if (hasFiles) View.VISIBLE else View.GONE
        b.pcapDeleteAllBtn.isEnabled = hasFiles
        b.pcapDeleteAllBtn.alpha = if (hasFiles) ALPHA_ENABLED else ALPHA_DISABLED
    }

    private fun selectCurrentMode() {
        val current = PcapMode.getPcapType(persistentState.pcapMode)
        b.pcapModeOffRb.isChecked = current == PcapMode.NONE
        b.pcapModeLogcatRb.isChecked = current == PcapMode.LOGCAT
        b.pcapModeFileRb.isChecked = current == PcapMode.EXTERNAL_FILE
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
            // IMPORTANT: no FLAG_GRANT_*_URI_PERMISSION here. Requesting a grant makes
            // the system verify that this app may share the (Android/data) document uri,
            // which the externalstorage provider denies for our uid. The handling
            // documents ui resolves the uri with its own provider access instead.
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

    private fun showDeleteConfirmationDialog(file: File) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.lbl_delete))
            .setMessage(getString(R.string.pcap_delete_file_confirmation, file.name))
            .setPositiveButton(getString(R.string.lbl_delete)) { _, _ ->
                deletePcapFiles(listOf(file))
            }
            .setNegativeButton(getString(R.string.lbl_cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .create()
            .show()
    }

    private fun showDeleteAllConfirmationDialog() {
        if (pcapFiles.isEmpty()) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.univ_ip_delete_dialog_positive))
            .setMessage(getString(R.string.pcap_delete_all_confirmation))
            .setPositiveButton(getString(R.string.lbl_delete)) { _, _ ->
                deletePcapFiles(pcapFiles.toList())
            }
            .setNegativeButton(getString(R.string.lbl_cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .create()
            .show()
    }

    private fun deletePcapFiles(files: List<File>) {
        if (files.isEmpty()) return
        lifecycleScope.launch {
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

    inner class PcapFilesAdapter(
        private val files: List<File>
    ) : RecyclerView.Adapter<PcapFilesAdapter.ViewHolder>() {

        inner class ViewHolder(private val itemBinding: ItemPcapFileBinding) :
            RecyclerView.ViewHolder(itemBinding.root) {

            fun bind(file: File) {
                itemBinding.itemPcapFileName.text = file.name
                val isActive =
                    PcapMode.getPcapType(persistentState.pcapMode) == PcapMode.EXTERNAL_FILE &&
                        file.absolutePath == persistentState.pcapFilePath
                val details =
                    listOfNotNull(
                        if (isActive) getString(R.string.pcap_active_file_label) else null,
                        formatFileSize(file.length()),
                        formatDate(file.lastModified())
                    )
                itemBinding.itemPcapFileDetails.text = details.joinToString(DETAILS_SEPARATOR)

                itemBinding.itemPcapFileViewBtn.setOnClickListener {
                    openFile(file)
                }

                itemBinding.itemPcapFileDeleteBtn.setOnClickListener {
                    showDeleteConfirmationDialog(file)
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemPcapFileBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(files[position])
        }

        override fun getItemCount(): Int = files.size
    }
}
