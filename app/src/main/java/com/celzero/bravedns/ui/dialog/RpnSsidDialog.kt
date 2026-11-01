/* Copyright 2025 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.dialog

import android.app.Activity
import android.app.Dialog
import android.os.Bundle
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import android.widget.Toast
import com.celzero.bravedns.R
import com.celzero.bravedns.data.SsidItem
import com.celzero.bravedns.ui.compose.rpnconfig.RpnSsidContent
import com.celzero.bravedns.ui.compose.rpnconfig.RpnSsidState
import com.celzero.bravedns.ui.compose.wireguard.setWgContent
import com.celzero.bravedns.ui.compose.wireguard.WgAlertDialogBuilder as MaterialAlertDialogBuilder
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities

class RpnSsidDialog(
    private val activity: Activity,
    private val themeId: Int,
    private val countryCode: String,
    private val countryName: String,
    private val currentSsids: String,
    private val onSave: (String) -> Unit
) : Dialog(activity, themeId) {
    private val state = RpnSsidState()
    private val ssidItems get() = state.items
    companion object { private const val TAG = "RpnSsidDialog" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setCancelable(false)
        loadCurrentSsids()
        state.restore(savedInstanceState)
        setWgContent(activity) { RpnSsidContent(state, countryName, ::addSsid, ::showDeleteConfirmation, ::dismiss, ::saveSsids) }
        window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        UIUtils.capDialogWidth(this)
        window?.setGravity(Gravity.CENTER)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    override fun onSaveInstanceState(): Bundle = super.onSaveInstanceState().also(state::save)

    private fun loadCurrentSsids() {
        val parsedSsids = SsidItem.parseStorageList(currentSsids)
        ssidItems.clear()
        ssidItems.addAll(parsedSsids)
    }

    private fun addSsid() {
        when (state.add()) {
            RpnSsidState.AddResult.EMPTY -> Utilities.showToastUiCentered(activity, context.getString(R.string.wg_ssid_empty_error), Toast.LENGTH_SHORT)
            RpnSsidState.AddResult.DUPLICATE -> Utilities.showToastUiCentered(activity, context.getString(R.string.wg_ssid_duplicate_error), Toast.LENGTH_SHORT)
            RpnSsidState.AddResult.ADDED -> {
                val item = ssidItems.last()
                Logger.i(LOG_TAG_UI, "$TAG: added SSID: ${item.name}, type: ${item.type.id} for country: $countryCode")
            }
        }
    }

    private fun showDeleteConfirmation(ssidItem: SsidItem) {
        val builder = MaterialAlertDialogBuilder(context, R.style.App_Dialog_NoDim)
        builder.setTitle(context.getString(R.string.wg_ssid_delete_title))
        builder.setMessage(context.getString(R.string.bug_report_delete_confirmation, ssidItem.name))
        builder.setCancelable(true)
        builder.setPositiveButton(context.getString(R.string.lbl_delete)) { dialog, _ ->
            val position = ssidItems.indexOf(ssidItem)
            if (position != -1) {
                ssidItems.removeAt(position)
                Logger.i(LOG_TAG_UI, "$TAG: deleted SSID: ${ssidItem.name} for country: $countryCode")
            }
            dialog.dismiss()
        }
        builder.setNegativeButton(context.getString(R.string.lbl_cancel)) { dialog, _ -> dialog.dismiss() }
        builder.create().show()
    }

    private fun saveSsids() {
        val ssidsString = SsidItem.toStorageList(ssidItems)
        Logger.i(LOG_TAG_UI, "$TAG: saving ${ssidItems.size} SSIDs for country: $countryCode")
        onSave(ssidsString)
        dismiss()
    }
}
