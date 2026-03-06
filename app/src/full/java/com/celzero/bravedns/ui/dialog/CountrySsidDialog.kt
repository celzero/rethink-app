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

import Logger
import Logger.LOG_TAG_UI
import android.animation.ObjectAnimator
import android.app.Activity
import android.app.Dialog
import android.os.Bundle
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.SsidAdapter
import com.celzero.bravedns.data.SsidItem
import com.celzero.bravedns.databinding.DialogCountrySsidPremiumBinding
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Premium SSID Dialog for Country-based VPN configurations
 * Features modern Material 3 design with smooth animations
 */
class CountrySsidDialog(
    private val activity: Activity,
    private val themeId: Int,
    private val countryCode: String,
    private val countryName: String,
    private val currentSsids: String,
    private val onSave: (String) -> Unit
) : Dialog(activity, themeId) {

    private lateinit var b: DialogCountrySsidPremiumBinding
    private lateinit var ssidAdapter: SsidAdapter
    private val ssidItems = mutableListOf<SsidItem>()

    companion object {
        private const val TAG = "CountrySsidDialog"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        b = DialogCountrySsidPremiumBinding.inflate(layoutInflater)
        setContentView(b.root)
        setCancelable(false)
        setupDialog()
        setupRecyclerView()
        loadCurrentSsids()
        setupClickListeners()
        animateEntry()
    }

    private fun setupDialog() {
        window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        )

        window?.setGravity(Gravity.CENTER)
        window?.setBackgroundDrawableResource(android.R.color.transparent)

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { view, insets ->
            val sysInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, sysInsets.top, 0, sysInsets.bottom)
            insets
        }

        // Set title with country name
        b.dialogTitle.text = context.getString(R.string.country_ssid_dialog_title, countryName)

        // Set description
        b.descriptionTextView.text = getDescTxt()

        // Set input hint
        b.ssidTextInputLayout.hint = context.getString(R.string.wg_ssid_input_hint, context.getString(R.string.lbl_ssids))

        // Set radio button text
        b.radioNotEqual.text = context.getString(R.string.notification_action_pause_vpn).lowercase()
            .replaceFirstChar { it.uppercase() }

        // Set initial state of add button to disabled
        b.addSsidBtn.isEnabled = false
        b.addSsidBtn.isClickable = false
        b.addSsidBtn.setTextColor(UIUtils.fetchColor(context, R.attr.primaryLightColorText))

        // Listeners to update description text when radio buttons change
        b.ssidConditionRadioGroup.setOnCheckedChangeListener { _, _ ->
            updateDescriptionText()
        }

        b.ssidMatchTypeRadioGroup.setOnCheckedChangeListener { _, _ ->
            updateDescriptionText()
        }
    }

    private fun animateEntry() {
        // Animate dialog card
        b.dialogCard.alpha = 0f
        b.dialogCard.scaleX = 0.9f
        b.dialogCard.scaleY = 0.9f
        b.dialogCard.translationY = 50f

        b.dialogCard.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(400)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        // Animate icon
        b.dialogIcon.scaleX = 0f
        b.dialogIcon.scaleY = 0f
        b.dialogIcon.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(100)
            .setDuration(500)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        // Rotate icon
        ObjectAnimator.ofFloat(b.dialogIcon, "rotation", 0f, 360f).apply {
            duration = 800
            startDelay = 100
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun getDescTxt(): String {
        val isEqual = b.radioEqual.isChecked
        val isExact = b.radioExact.isChecked

        val pauseTxt = context.getString(R.string.notification_action_pause_vpn).lowercase()
            .replaceFirstChar { it.uppercase() }
        val connectTxt = context.getString(R.string.lbl_connect).lowercase()
            .replaceFirstChar { it.uppercase() }
        val firstArg = if (isEqual) connectTxt else pauseTxt
        val secArg = context.getString(R.string.lbl_ssid)

        val exactMatchTxt = context.getString(R.string.wg_ssid_type_exact).lowercase()
        val partialMatchTxt = context.getString(R.string.wg_ssid_type_wildcard).lowercase()
        val thirdArg = if (isExact) exactMatchTxt else partialMatchTxt

        return context.getString(R.string.country_ssid_dialog_description, firstArg, countryName, secArg, thirdArg)
    }

    private fun updateDescriptionText() {
        b.descriptionTextView.text = getDescTxt()
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

        updateEmptyState()
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

        b.ssidEditText.addTextChangedListener { text ->
            val isNotEmpty = !text.isNullOrBlank()

            // Enable or disable button based on text
            b.addSsidBtn.isEnabled = isNotEmpty
            b.addSsidBtn.isClickable = isNotEmpty

            if (isNotEmpty) {
                b.addSsidBtn.setTextColor(UIUtils.fetchColor(context, R.attr.accentGood))
            } else {
                b.addSsidBtn.setTextColor(UIUtils.fetchColor(context, R.attr.primaryLightColorText))
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
        val ssidName = b.ssidEditText.text.toString().trim()
        if (ssidName.isEmpty()) {
            Utilities.showToastUiCentered(
                context,
                context.getString(R.string.wg_ssid_empty_error),
                Toast.LENGTH_SHORT
            )
            return
        }

        // Check for duplicate
        if (ssidItems.any { it.name.equals(ssidName, ignoreCase = true) }) {
            Utilities.showToastUiCentered(
                context,
                context.getString(R.string.wg_ssid_duplicate_error),
                Toast.LENGTH_SHORT
            )
            return
        }

        val isEqual = b.radioEqual.isChecked
        val isExact = b.radioExact.isChecked

        val ssidType = when {
            isEqual && isExact -> SsidItem.SsidType.EQUAL_EXACT
            isEqual && !isExact -> SsidItem.SsidType.EQUAL_WILDCARD
            !isEqual && isExact -> SsidItem.SsidType.NOTEQUAL_EXACT
            else -> SsidItem.SsidType.NOTEQUAL_WILDCARD
        }

        val newItem = SsidItem(ssidName, ssidType)
        ssidItems.add(newItem)
        ssidAdapter.notifyItemInserted(ssidItems.size - 1)

        // Clear input
        b.ssidEditText.text?.clear()

        // Scroll to new item with animation
        b.ssidRecyclerView.smoothScrollToPosition(ssidItems.size - 1)

        updateEmptyState()

        Logger.i(LOG_TAG_UI, "$TAG: added SSID: $ssidName, type: ${ssidType.id} for country: $countryCode")
    }

    private fun showDeleteConfirmation(ssidItem: SsidItem) {
        val builder = MaterialAlertDialogBuilder(context, R.style.App_Dialog_NoDim)
        builder.setTitle(context.getString(R.string.wg_ssid_delete_title))
        builder.setMessage(context.getString(R.string.wg_ssid_delete_message, ssidItem.name))
        builder.setCancelable(true)
        builder.setPositiveButton(context.getString(R.string.lbl_delete)) { dialog, _ ->
            val position = ssidItems.indexOf(ssidItem)
            if (position != -1) {
                ssidItems.removeAt(position)
                ssidAdapter.notifyItemRemoved(position)
                updateEmptyState()
                Logger.i(LOG_TAG_UI, "$TAG: deleted SSID: ${ssidItem.name} for country: $countryCode")
            }
            dialog.dismiss()
        }
        builder.setNegativeButton(context.getString(R.string.lbl_cancel)) { dialog, _ ->
            dialog.dismiss()
        }
        builder.create().show()
    }

    private fun updateEmptyState() {
        if (ssidItems.isEmpty()) {
            b.emptyStateView.visibility = android.view.View.VISIBLE
            b.ssidRecyclerView.visibility = android.view.View.GONE
        } else {
            b.emptyStateView.visibility = android.view.View.GONE
            b.ssidRecyclerView.visibility = android.view.View.VISIBLE
        }
    }

    private fun saveSsids() {
        val ssidsString = SsidItem.toStorageList(ssidItems)
        Logger.i(LOG_TAG_UI, "$TAG: saving ${ssidItems.size} SSIDs for country: $countryCode")
        onSave(ssidsString)
        dismiss()
    }
}

