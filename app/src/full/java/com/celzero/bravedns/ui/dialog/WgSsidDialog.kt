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
package com.celzero.bravedns.ui.dialog

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.SsidAdapter
import com.celzero.bravedns.data.SsidItem
import com.celzero.bravedns.databinding.DialogWgSsidBinding
import com.celzero.bravedns.util.Utilities
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class WgSsidDialog(
    private val activity: Activity,
    private val themeId: Int,
    private val currentSsids: String,
    private val onSave: (String) -> Unit
) : Dialog(activity, themeId) {

    private lateinit var b: DialogWgSsidBinding
    private lateinit var ssidAdapter: SsidAdapter
    private val ssidItems = mutableListOf<SsidItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        b = DialogWgSsidBinding.inflate(layoutInflater)
        setContentView(b.root)
        setCancelable(false)

        setupDialog()
        setupRecyclerView()
        loadCurrentSsids()
        setupClickListeners()
    }

    private fun setupDialog() {
        window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        )

        window?.setGravity(Gravity.CENTER)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { view, insets ->
            val sysInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, sysInsets.top, 0, sysInsets.bottom)
            insets
        }
    }

    private fun setupRecyclerView() {
        ssidAdapter = SsidAdapter(ssidItems) { ssidItem ->
            showDeleteConfirmation(ssidItem)
        }

        b.ssidRecyclerView.apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = ssidAdapter
        }
    }

    private fun loadCurrentSsids() {
        val parsedSsids = SsidItem.parseStorageList(currentSsids)
        ssidItems.clear()
        ssidItems.addAll(parsedSsids)
        ssidAdapter.notifyDataSetChanged()
    }

    private fun setupClickListeners() {
        b.addSsidBtn.setOnClickListener {
            addSsid()
        }

        b.ssidEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addSsid()
                true
            } else {
                false
            }
        }

        b.cancelBtn.setOnClickListener {
            dismiss()
        }

        b.saveBtn.setOnClickListener {
            saveSsids()
        }
    }

    private fun addSsid() {
        val ssidName = b.ssidEditText.text?.toString()?.trim()

        if (ssidName.isNullOrBlank()) {
            Utilities.showToastUiCentered(
                activity,
                activity.getString(R.string.wg_ssid_empty_error),
                Toast.LENGTH_SHORT
            )
            return
        }

        // Validate SSID name
        if (!isValidSsidName(ssidName)) {
            Utilities.showToastUiCentered(
                activity,
                activity.getString(R.string.wg_ssid_invalid_error),
                Toast.LENGTH_SHORT
            )
            return
        }

        val selectedType = if (b.radioString.isChecked) {
            SsidItem.SsidType.STRING
        } else {
            SsidItem.SsidType.WILDCARD
        }

        val newSsidItem = SsidItem(ssidName, selectedType)

        // Check for duplicates
        if (ssidItems.any { it.name.equals(ssidName, ignoreCase = true) }) {
            Utilities.showToastUiCentered(
                activity,
                activity.getString(R.string.wg_ssid_duplicate_error),
                Toast.LENGTH_SHORT
            )
            return
        }

        ssidAdapter.addSsidItem(newSsidItem)
        b.ssidEditText.text?.clear()

        // Reset to string type as default
        b.radioString.isChecked = true
    }

    private fun isValidSsidName(ssidName: String): Boolean {
        // Basic validation - no commas, no ## (our delimiter), reasonable length
        return ssidName.length <= 32 &&
                !ssidName.contains(",") &&
                !ssidName.contains("##") &&
                ssidName.isNotBlank()
    }

    private fun showDeleteConfirmation(ssidItem: SsidItem) {
        val builder = MaterialAlertDialogBuilder(activity)
        builder.setTitle(activity.getString(R.string.lbl_delete))
        builder.setMessage(
            activity.getString(R.string.wg_ssid_delete_confirmation, ssidItem.name)
        )
        builder.setCancelable(true)
        builder.setPositiveButton(activity.getString(R.string.lbl_delete)) { _, _ ->
            ssidAdapter.removeSsidItem(ssidItem)
        }
        builder.setNegativeButton(activity.getString(R.string.lbl_cancel)) { _, _ ->
            // no-op
        }
        builder.create().show()
    }

    private fun saveSsids() {
        val finalSsids = SsidItem.toStorageList(ssidAdapter.getSsidItems())
        onSave(finalSsids)
        dismiss()
    }
}