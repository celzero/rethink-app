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

import com.celzero.bravedns.ui.compose.wireguard.*
import android.app.Activity
import android.app.Dialog
import android.os.Bundle
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import android.widget.Toast
import androidx.core.widget.addTextChangedListener
import com.celzero.bravedns.R
import com.celzero.bravedns.data.SsidItem
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.ui.compose.wireguard.WgAlertDialogBuilder as MaterialAlertDialogBuilder

class WgSsidDialog(
    private val activity: Activity,
    private val themeId: Int,
    private val currentSsids: String,
    private val onSave: (String) -> Unit
) : Dialog(activity, themeId) {

    private lateinit var b: WgSsidState
    private lateinit var ssidAdapter: WgSsidPresenter
    private val ssidItems = mutableListOf<SsidItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        b = WgSsidState(context)
        setWgContent(activity) { WgSsidContent(b, if (::ssidAdapter.isInitialized) ssidAdapter else null) }
        setCancelable(false)
        setupDialog()
        setupRecyclerView()
        loadCurrentSsids()
        setupClickListeners()
        restoreInputs(savedInstanceState)
    }

    override fun onSaveInstanceState(): Bundle = super.onSaveInstanceState().also {
        b.saveForm(it, "wg.ssid.")
        it.putBoolean("wg.ssid.equal", b.radioEqual.isChecked)
        it.putBoolean("wg.ssid.exact", b.radioExact.isChecked)
    }
    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        restoreInputs(savedInstanceState)
    }
    private fun restoreInputs(state: Bundle?) {
        b.restoreForm(state, "wg.ssid.")
        if (state?.containsKey("wg.ssid.equal") == true) {
            if (state.getBoolean("wg.ssid.equal")) b.radioEqual.isChecked = true else b.radioNotEqual.isChecked = true
            if (state.getBoolean("wg.ssid.exact")) b.radioExact.isChecked = true else b.radioWildcard.isChecked = true
        }
    }

    private fun setupDialog() {
        window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        // keep the dialog within the app's max width on expanded windows (foldables/tablets)
        UIUtils.capDialogWidth(this)

        window?.setGravity(Gravity.CENTER)
        window?.setBackgroundDrawableResource(android.R.color.transparent)


        b.descriptionTextView.text = getDescTxt()
        b.ssidTextInputLayout.hint = context.getString(R.string.wg_ssid_input_hint, context.getString(R.string.lbl_ssids))
        b.radioNotEqual.text = context.getString(R.string.notification_action_pause_vpn).lowercase().replaceFirstChar { it.uppercase() }

        // set initial state of add button to disabled
        b.addSsidBtn.isEnabled = false
        b.addSsidBtn.isClickable = false
        b.addSsidBtn.setTextColor(UIUtils.fetchColor(context, R.attr.primaryLightColorText))
        disableOrEnableRadioButtons(false)

        // listeners to update description text when radio buttons change
        b.ssidConditionRadioGroup.setOnClickListener {
            updateDescriptionText()
        }

        b.ssidMatchTypeRadioGroup.setOnClickListener {
            updateDescriptionText()
        }
    }

    private fun getDescTxt(): String {
        val isEqual = b.radioEqual.isChecked
        val isExact = b.radioExact.isChecked

        val pauseTxt = context.getString(R.string.notification_action_pause_vpn).lowercase().replaceFirstChar { it.uppercase() }
        val connectTxt = context.getString(R.string.lbl_connect).lowercase().replaceFirstChar { it.uppercase() }
        val firstArg = if (isEqual) connectTxt else pauseTxt
        val secArg = context.getString(R.string.lbl_ssid)

        val exactMatchTxt = context.getString(R.string.wg_ssid_type_exact).lowercase()
        val partialMatchTxt = context.getString(R.string.wg_ssid_type_wildcard).lowercase()
        val thirdArg = if (isExact) exactMatchTxt else partialMatchTxt
        return context.getString(R.string.wg_ssid_dialog_description, firstArg, secArg, thirdArg)
    }

    private fun updateDescriptionText() {
        b.descriptionTextView.text = getDescTxt()
    }

    private fun setupRecyclerView() {
        ssidAdapter = WgSsidPresenter(ssidItems) { showDeleteConfirmation(it) }
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

        b.ssidEditText.onEditorDone = { addSsid() }

        b.ssidEditText.addTextChangedListener { text ->
            val isNotEmpty = !text.isNullOrBlank()

            // Enable or disable add button based on text
            b.addSsidBtn.isEnabled = isNotEmpty
            b.addSsidBtn.isClickable = isNotEmpty

            // Enable or disable radio buttons based on text
            // User should only be able to change settings when there's an SSID to apply them to
            disableOrEnableRadioButtons(isNotEmpty)

            // Change button background color based on state
            val context = this.context
            val enabledColor = UIUtils.fetchColor(context, R.attr.accentGood)
            val disabledColor = UIUtils.fetchColor(context, R.attr.primaryLightColorText)

            b.addSsidBtn.setTextColor(if (isNotEmpty) enabledColor else disabledColor)
        }

        b.cancelBtn.setOnClickListener {
            dismiss()
        }

        b.saveBtn.setOnClickListener {
            saveSsids()
        }
    }

    private fun disableOrEnableRadioButtons(enable: Boolean) {
        b.radioEqual.isEnabled = enable
        b.radioEqual.isClickable = enable
        b.radioNotEqual.isEnabled = enable
        b.radioNotEqual.isClickable = enable
        b.radioExact.isEnabled = enable
        b.radioExact.isClickable = enable
        b.radioWildcard.isEnabled = enable
        b.radioWildcard.isClickable = enable
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

        if (!isValidSsidName(ssidName)) {
            Utilities.showToastUiCentered(
                activity,
                activity.getString(R.string.wg_ssid_invalid_error, activity.getString(R.string.lbl_ssids)),
                Toast.LENGTH_SHORT
            )
            return
        }

        val isEqual = b.radioEqual.isChecked
        val isExact = b.radioExact.isChecked

        val selectedType = when {
            isEqual && isExact -> SsidItem.SsidType.EQUAL_EXACT
            isEqual && !isExact -> SsidItem.SsidType.EQUAL_WILDCARD
            !isEqual && isExact -> SsidItem.SsidType.NOTEQUAL_EXACT
            else -> SsidItem.SsidType.NOTEQUAL_WILDCARD
        }

        if (ssidAdapter.getSsidItems().any {
            it.name.equals(ssidName, ignoreCase = true) && it.type == selectedType
        }) {
            b.ssidEditText.setText("")
            resetToDefaultSelection()
            return
        }

        val existingWithDifferentType = ssidAdapter.getSsidItems().find {
            it.name.equals(ssidName, ignoreCase = true) && it.type != selectedType
        }

        if (existingWithDifferentType != null) {
            ssidAdapter.removeSsidItem(existingWithDifferentType)
        }

        ssidAdapter.addSsidItem(SsidItem(ssidName, selectedType))
        b.ssidEditText.setText("")
        resetToDefaultSelection()
        updateEmptyState()
    }

    private fun resetToDefaultSelection() {
        b.radioEqual.isChecked = true
        b.radioWildcard.isChecked = true
    }

    private fun isValidSsidName(ssidName: String): Boolean {
        // Basic validation - reasonable length
        return ssidName.length <= 32 &&
                ssidName.isNotBlank()
    }

    private fun showDeleteConfirmation(ssidItem: SsidItem) {
        val builder = MaterialAlertDialogBuilder(activity, R.style.App_Dialog_NoDim)
        builder.setTitle(activity.getString(R.string.wg_ssid_delete_title))
        builder.setMessage(
            activity.getString(R.string.two_argument_space, activity.getString(R.string.lbl_delete), ssidItem.name)
        )
        builder.setCancelable(true)
        builder.setPositiveButton(activity.getString(R.string.lbl_delete)) { _, _ ->
            ssidAdapter.removeSsidItem(ssidItem)
            updateEmptyState()
        }
        builder.setNegativeButton(activity.getString(R.string.lbl_cancel)) { _, _ ->
            // no-op
        }
        builder.create().show()
    }

    private fun updateEmptyState() {
        if (ssidAdapter.getSsidItems().isEmpty()) {
            b.emptyStateView.visibility = android.view.View.VISIBLE
            b.ssidRecyclerView.visibility = android.view.View.GONE
        } else {
            b.emptyStateView.visibility = android.view.View.GONE
            b.ssidRecyclerView.visibility = android.view.View.VISIBLE
        }
    }

    private fun saveSsids() {
        val finalSsids = SsidItem.toStorageList(ssidAdapter.getSsidItems())
        onSave(finalSsids)
        dismiss()
    }
}
