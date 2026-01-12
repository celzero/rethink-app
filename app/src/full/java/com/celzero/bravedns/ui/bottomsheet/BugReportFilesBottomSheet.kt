/*
 * Copyright 2025 RethinkDNS and its authors
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

import Logger
import Logger.LOG_TAG_UI
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.BottomSheetBugReportFilesBinding
import com.celzero.bravedns.databinding.ItemBugReportFileBinding
import com.celzero.bravedns.scheduler.BugReportZipper
import com.celzero.bravedns.scheduler.EnhancedBugReport
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastO
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.celzero.bravedns.util.useTransparentNoDimBackground
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class BugReportFilesBottomSheet : BottomSheetDialogFragment() {
    private var _binding: BottomSheetBugReportFilesBinding? = null
    private val b get() = _binding!!

    private val persistentState by inject<PersistentState>()

    private val bugReportFiles = mutableListOf<BugReportFile>()
    private lateinit var adapter: BugReportFilesAdapter

    companion object {
        private const val ALPHA_ENABLED = 1.0f
        private const val ALPHA_DISABLED = 0.5f
        private const val BYTES_IN_KB = 1024L
        private const val BYTES_IN_MB = 1024L * 1024L
        private const val MB_DIVISOR = 1024.0 * 1024.0
    }

    override fun getTheme(): Int =
        Themes.getBottomsheetCurrentTheme(isDarkThemeOn(), persistentState.theme)

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetBugReportFilesBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            if (isAtleastQ()) {
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                controller.isAppearanceLightNavigationBars = false
                window.isNavigationBarContrastEnforced = false
            }
        }
        dialog?.let {
            val bottomSheet = it.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.layoutParams?.height = ViewGroup.LayoutParams.MATCH_PARENT
        }
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
            isDraggable = true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dialog?.window?.let { window ->
            if (Utilities.isAtleastQ()) {
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                controller.isAppearanceLightNavigationBars = false
                window.isNavigationBarContrastEnforced = false
            }
        }
        initView()
        loadBugReportFiles()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun initView() {
        adapter = BugReportFilesAdapter(bugReportFiles)
        b.brbsRecycler.layoutManager = LinearLayoutManager(requireContext())
        b.brbsRecycler.adapter = adapter

        b.brbsSelectAllCheckbox.setOnCheckedChangeListener { _, isChecked ->
            updateSelectAllState(isChecked)
        }

        b.brbsSelectAllText.setOnClickListener {
            b.brbsSelectAllCheckbox.toggle()
        }

        b.brbsSendButton.setOnClickListener {
            sendBugReport()
        }
    }

    private fun loadBugReportFiles() {
        lifecycleScope.launch {
            try {
                val files = withContext(Dispatchers.IO) {
                    collectAllBugReportFiles()
                }

                bugReportFiles.clear()
                bugReportFiles.addAll(files)
                adapter.notifyDataSetChanged()

                updateTotalSize()
                updateSendButtonState()
            } catch (e: Exception) {
                Logger.e(LOG_TAG_UI, "err loading bug report: ${e.message}", e)
                showToastUiCentered(
                    requireContext(),
                    getString(R.string.bug_report_file_not_found),
                    Toast.LENGTH_SHORT
                )
            }
        }
    }

    private fun collectAllBugReportFiles(): List<BugReportFile> {
        val files = mutableListOf<BugReportFile>()
        val dir = requireContext().filesDir

        // bug report zip
        val bugReportZip = File(BugReportZipper.getZipFileName(dir))
        if (bugReportZip.exists() && bugReportZip.length() > 0) {
            files.add(
                BugReportFile(
                    file = bugReportZip,
                    name = bugReportZip.name,
                    type = FileType.ZIP,
                    isSelected = true
                )
            )
        }

        // tombstone zip
        if (isAtleastO()) {
            val tombstoneZip = EnhancedBugReport.getTombstoneZipFile(requireContext())
            if (tombstoneZip != null && tombstoneZip.exists() && tombstoneZip.length() > 0) {
                files.add(
                    BugReportFile(
                        file = tombstoneZip,
                        name = tombstoneZip.name,
                        type = FileType.ZIP,
                        isSelected = true
                    )
                )
            }
        }

        // individual files from bug report dir (if any)
        val bugReportDir = File(dir, BugReportZipper.BUG_REPORT_DIR_NAME)
        if (bugReportDir.exists() && bugReportDir.isDirectory) {
            bugReportDir.listFiles()?.forEach { file ->
                if (file.isFile && file.length() > 0) {
                    files.add(
                        BugReportFile(
                            file = file,
                            name = file.name,
                            type = getFileType(file),
                            isSelected = true
                        )
                    )
                }
            }
        }

        // individual tombstone files (if zip doesn't exist)
        if (isAtleastO()) {
            val tombstoneDir = File(dir, EnhancedBugReport.TOMBSTONE_DIR_NAME)
            if (tombstoneDir.exists() && tombstoneDir.isDirectory) {
                tombstoneDir.listFiles()?.forEach { file ->
                    if (file.isFile && file.length() > 0) {
                        files.add(
                            BugReportFile(
                                file = file,
                                name = file.name,
                                type = FileType.TEXT,
                                isSelected = true
                            )
                        )
                    }
                }
            }
        }

        return files.sortedByDescending { it.file.lastModified() }
    }

    private fun getFileType(file: File): FileType {
        return when (file.extension.lowercase()) {
            "zip" -> FileType.ZIP
            "txt", "log" -> FileType.TEXT
            else -> FileType.TEXT
        }
    }

    private fun updateSelectAllState(isChecked: Boolean) {
        bugReportFiles.forEach { it.isSelected = isChecked }
        adapter.notifyDataSetChanged()
        updateTotalSize()
        updateSendButtonState()

        b.brbsSelectAllText.text = if (isChecked) {
            getString(R.string.bug_report_deselect_all)
        } else {
            getString(R.string.lbl_select_all).replaceFirstChar(Char::titlecase)
        }
    }

    private fun updateTotalSize() {
        val totalSize = bugReportFiles.filter { it.isSelected }.sumOf { it.file.length() }
        b.brbsTotalSize.text = formatFileSize(totalSize)
    }

    private fun updateSendButtonState() {
        val hasSelection = bugReportFiles.any { it.isSelected }
        b.brbsSendButton.isEnabled = hasSelection
        b.brbsSendButton.alpha = if (hasSelection) ALPHA_ENABLED else ALPHA_DISABLED
    }

    private fun sendBugReport() {
        val selectedFiles = bugReportFiles.filter { it.isSelected }.map { it.file }

        if (selectedFiles.isEmpty()) {
            showToastUiCentered(
                requireContext(),
                getString(R.string.bug_report_no_files_selected),
                Toast.LENGTH_SHORT
            )
            return
        }

        b.brbsProgressLayout.visibility = View.VISIBLE
        b.brbsSendButton.isEnabled = false

        lifecycleScope.launch {
            try {
                val attachmentUri = withContext(Dispatchers.IO) {
                    if (selectedFiles.size == 1) {
                        // single file - attach directly
                        getFileUri(selectedFiles[0])
                    } else {
                        // multiple files - create a zip
                        b.brbsProgressText.text = getString(R.string.bug_report_creating_zip)
                        createCombinedZip(selectedFiles)
                    }
                }

                if (attachmentUri != null) {
                    val emailIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_EMAIL, arrayOf(getString(R.string.about_mail_to)))
                        putExtra(Intent.EXTRA_SUBJECT, getString(R.string.about_mail_bugreport_subject))
                        putExtra(Intent.EXTRA_STREAM, attachmentUri)
                        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    }
                    startActivity(
                        Intent.createChooser(
                            emailIntent,
                            getString(R.string.about_mail_bugreport_share_title)
                        )
                    )
                    dismiss()
                } else {
                    showToastUiCentered(
                        requireContext(),
                        getString(R.string.error_loading_log_file),
                        Toast.LENGTH_SHORT
                    )
                }
            } catch (e: Exception) {
                Logger.e(LOG_TAG_UI, "err sending bug report: ${e.message}", e)
                showToastUiCentered(
                    requireContext(),
                    getString(R.string.error_loading_log_file),
                    Toast.LENGTH_SHORT
                )
            } finally {
                b.brbsProgressLayout.visibility = View.GONE
                b.brbsSendButton.isEnabled = true
            }
        }
    }

    private fun createCombinedZip(files: List<File>): android.net.Uri? {
        val tempDir = requireContext().cacheDir
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val zipFile = File(tempDir, "rethinkdns_bugreport_$timestamp.zip")

        try {
            val addedEntries = mutableSetOf<String>() // Track added entry names to skip duplicates
            
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                files.forEach { file ->
                    if (file.extension == "zip") {
                        // If the file is already a zip, extract and add its contents
                        ZipFile(file).use { zf ->
                            val entries = zf.entries()
                            while (entries.hasMoreElements()) {
                                val entry = entries.nextElement()
                                if (!entry.isDirectory && !addedEntries.contains(entry.name)) {
                                    // Only add if not already present
                                    addedEntries.add(entry.name)
                                    
                                    val newEntry = ZipEntry(entry.name)
                                    zos.putNextEntry(newEntry)
                                    zf.getInputStream(entry).use { input ->
                                        input.copyTo(zos)
                                    }
                                    zos.closeEntry()
                                }
                            }
                        }
                    } else {
                        // regular file - skip if already added
                        if (!addedEntries.contains(file.name)) {
                            addedEntries.add(file.name)
                            
                            val entry = ZipEntry(file.name)
                            zos.putNextEntry(entry)
                            FileInputStream(file).use { input ->
                                input.copyTo(zos)
                            }
                            zos.closeEntry()
                        }
                    }
                }
            }

            return getFileUri(zipFile)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "err creating combined zip: ${e.message}", e)
            zipFile.delete()
            return null
        }
    }


    private fun getFileUri(file: File): android.net.Uri? {
        return try {
            FileProvider.getUriForFile(
                requireContext(),
                BugReportZipper.FILE_PROVIDER_NAME,
                file
            )
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "err getting file uri: ${e.message}", e)
            null
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

    inner class BugReportFilesAdapter(
        private val files: List<BugReportFile>
    ) : RecyclerView.Adapter<BugReportFilesAdapter.ViewHolder>() {

        inner class ViewHolder(private val itemBinding: ItemBugReportFileBinding) :
            RecyclerView.ViewHolder(itemBinding.root) {

            fun bind(fileItem: BugReportFile) {
                itemBinding.itemFileName.text = fileItem.name
                itemBinding.itemFileDetails.text = "${formatFileSize(fileItem.file.length())} - ${formatDate(fileItem.file.lastModified())}"
                itemBinding.itemFileCheckbox.isChecked = fileItem.isSelected

                // file icon based on type
                val iconRes = when (fileItem.type) {
                    FileType.ZIP -> R.drawable.ic_backup
                    FileType.TEXT -> R.drawable.ic_logs
                }
                itemBinding.itemFileIcon.setImageResource(iconRes)

                itemBinding.itemFileCheckbox.setOnCheckedChangeListener { _, isChecked ->
                    fileItem.isSelected = isChecked
                    updateSelectAllCheckboxState()
                    updateTotalSize()
                    updateSendButtonState()
                }

                itemBinding.root.setOnClickListener {
                    itemBinding.itemFileCheckbox.toggle()
                }

                itemBinding.itemFileViewBtn.setOnClickListener {
                    openFile(fileItem.file)
                }

                itemBinding.itemFileDeleteBtn.setOnClickListener {
                    showDeleteConfirmationDialog(fileItem)
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemBugReportFileBinding.inflate(
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

        private fun updateSelectAllCheckboxState() {
            val allSelected = files.all { it.isSelected }

            b.brbsSelectAllCheckbox.setOnCheckedChangeListener(null)
            b.brbsSelectAllCheckbox.isChecked = allSelected
            b.brbsSelectAllCheckbox.setOnCheckedChangeListener { _, isChecked ->
                updateSelectAllState(isChecked)
            }

            b.brbsSelectAllText.text = if (allSelected) {
                getString(R.string.bug_report_deselect_all)
            } else {
                getString(R.string.lbl_select_all).replaceFirstChar(Char::titlecase)
            }
        }
    }

    private fun showDeleteConfirmationDialog(fileItem: BugReportFile) {
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.lbl_delete))
            .setMessage(getString(R.string.bug_report_delete_confirmation, fileItem.name))
            .setPositiveButton(getString(R.string.lbl_delete)) { _, _ ->
                deleteFile(fileItem)
            }
            .setNegativeButton(getString(R.string.lbl_cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .create()

        dialog.show()
    }

    private fun deleteFile(fileItem: BugReportFile) {
        lifecycleScope.launch {
            try {
                val deleted = withContext(Dispatchers.IO) {
                    fileItem.file.delete()
                }

                if (deleted) {
                    bugReportFiles.remove(fileItem)
                    adapter.notifyDataSetChanged()
                    updateTotalSize()
                    updateSendButtonState()

                    showToastUiCentered(
                        requireContext(),
                        getString(R.string.bug_report_file_deleted, fileItem.name),
                        Toast.LENGTH_SHORT
                    )

                    // If no files left, dismiss the bottom sheet
                    if (bugReportFiles.isEmpty()) {
                        showToastUiCentered(
                            requireContext(),
                            getString(R.string.bug_report_no_files_available),
                            Toast.LENGTH_SHORT
                        )
                        dismiss()
                    }
                } else {
                    showToastUiCentered(
                        requireContext(),
                        getString(R.string.bug_report_delete_failed, fileItem.name),
                        Toast.LENGTH_SHORT
                    )
                }
            } catch (e: Exception) {
                Logger.e(LOG_TAG_UI, "err deleting file: ${e.message}", e)
                showToastUiCentered(
                    requireContext(),
                    getString(R.string.bug_report_delete_failed, fileItem.name),
                    Toast.LENGTH_SHORT
                )
            }
        }
    }

    private fun openFile(file: File) {
        try {
            val uri = getFileUri(file) ?: return

            val mimeType = when (file.extension.lowercase()) {
                "zip" -> "application/zip"
                "txt", "log" -> "text/plain"
                else -> "text/plain"
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            startActivity(Intent.createChooser(intent, getString(R.string.about_bug_report)))
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "err opening file: ${e.message}", e)
            showToastUiCentered(
                requireContext(),
                getString(R.string.bug_report_error_opening_file),
                Toast.LENGTH_SHORT
            )
        }
    }

    data class BugReportFile(
        val file: File,
        val name: String,
        val type: FileType,
        var isSelected: Boolean
    )

    enum class FileType {
        ZIP,
        TEXT
    }
}
