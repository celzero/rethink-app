/*
 * Copyright 2023 RethinkDNS and its authors
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
import com.celzero.bravedns.util.Logger
import android.app.Activity
import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.celzero.bravedns.R
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.getDurationInHumanReadableFormat
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.wireguard.Peer
import com.celzero.bravedns.wireguard.util.ErrorMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WgAddPeerDialog(
    private val activity: Activity,
    themeID: Int,
    private var configId: Int,
    private val wgPeer: Peer?
) : Dialog(activity, themeID) {

    private lateinit var b: WgPeerState

    private var isEditing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestWindowFeature(Window.FEATURE_NO_TITLE)
        b = WgPeerState(context)
        setWgContent(activity) { WgPeerContent(b) }
        initView()
        setupClickListener()
        b.restoreForm(savedInstanceState, "wg.peer.")
    }

    override fun onSaveInstanceState(): Bundle = super.onSaveInstanceState().also { b.saveForm(it, "wg.peer.") }
    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        b.restoreForm(savedInstanceState, "wg.peer.")
    }

    private fun initView() {
        window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
        // keep the dialog within the app's max width on expanded windows (foldables/tablets)
        UIUtils.capDialogWidth(this)

        if (wgPeer != null) {
            isEditing = true
            b.peerPublicKey.setText(wgPeer.getPublicKey().base64())
            if (wgPeer.getPreSharedKey().isPresent) {
                b.peerPresharedKey.setText(wgPeer.getPreSharedKey().get().base64())
            }
            b.peerAllowedIps.setText(wgPeer.getAllowedIps().joinToString { it.toString() })
            if (wgPeer.getEndpoint().isPresent) {
                b.peerEndpoint.setText(wgPeer.getEndpoint().get())
            }
            if (wgPeer.persistentKeepalive.isPresent) {
                val kas = wgPeer.persistentKeepalive.get()
                b.keepAliveHint.visibility = View.VISIBLE
                b.peerPersistentKeepAlive.setText(kas.toString())
                b.keepAliveHint.text = getDurationInHumanReadableFormat(activity, kas)
            } else {
                b.keepAliveHint.visibility = View.GONE
            }
        }
    }







    private fun setupClickListener() {
        b.customDialogDismissButton.setOnClickListener { this.dismiss() }

        b.peerPersistentKeepAlive.doOnTextChanged { text, _, _, _ ->
            if (text.toString().isNotEmpty()) {
                try {
                    val kas = text.toString().toInt()
                    b.keepAliveHint.visibility = View.VISIBLE
                    b.keepAliveHint.text = getDurationInHumanReadableFormat(activity, kas)
                } catch (_: NumberFormatException) {
                    b.keepAliveHint.visibility = View.GONE
                }
            } else {
                b.keepAliveHint.visibility = View.GONE
            }
        }

        b.customDialogOkButton.setOnClickListener {
            b.customDialogOkButton.isEnabled = false

            val peerPublicKey = b.peerPublicKey.text.toString()
            val presharedKey = b.peerPresharedKey.text.toString()
            val peerEndpoint = b.peerEndpoint.text.toString()
            val peerPersistentKeepAlive = b.peerPersistentKeepAlive.text.toString()
            val allowedIps = b.peerAllowedIps.text.toString()

            try {
                val builder = Peer.Builder()
                if (allowedIps.isNotEmpty()) builder.parseAllowedIPs(allowedIps)
                if (peerEndpoint.isNotEmpty()) builder.parseEndpoint(peerEndpoint)
                if (peerPersistentKeepAlive.isNotEmpty())
                    builder.parsePersistentKeepalive(peerPersistentKeepAlive)
                if (presharedKey.isNotEmpty()) builder.parsePreSharedKey(presharedKey)
                if (peerPublicKey.isNotEmpty()) builder.parsePublicKey(peerPublicKey)
                val newPeer = builder.build()

                ui {
                    showSaving()
                    ioCtx {
                        if (wgPeer != null && isEditing)
                            WireguardManager.deletePeer(configId, wgPeer)
                        WireguardManager.addPeer(configId, newPeer)
                    }
                    Utilities.showToastUiCentered(
                        activity,
                        context.getString(R.string.config_add_success_toast),
                        Toast.LENGTH_SHORT
                    )
                    this.dismiss()
                }
            } catch (e: Throwable) {
                resetSaveButton()
                val ex = Logger.throwableToException(e)
                Logger.e(Logger.LOG_TAG_PROXY, "Error while adding peer", ex)
                Utilities.showToastUiCentered(
                    activity,
                    ErrorMessages[activity, e],
                    Toast.LENGTH_SHORT
                )
                return@setOnClickListener
            }
        }
    }

    /** Switch the Save button into a loading state: spinner visible, text dimmed. */
    private fun showSaving() {
        b.customDialogOkButton.text = activity.getString(R.string.lbl_saving)
        b.customDialogOkButton.isEnabled = false
        b.customDialogOkProgress.visibility = View.VISIBLE
    }

    /** Restore the Save button to its normal, interactive state. */
    private fun resetSaveButton() {
        b.customDialogOkButton.text = activity.getString(R.string.lbl_save)
        b.customDialogOkButton.isEnabled = true
        b.customDialogOkProgress.visibility = View.GONE
    }

    private fun ui(f: suspend () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return

        (activity as LifecycleOwner).lifecycleScope.launch(Dispatchers.Main) { f() }
    }

    private suspend fun ioCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.IO) { f() }
    }
}
