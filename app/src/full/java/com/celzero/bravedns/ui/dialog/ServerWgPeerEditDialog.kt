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
import android.os.Bundle
import android.view.Window
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.DialogServerWgPeerEditBinding
import com.celzero.bravedns.wireguard.Peer

/**
 * Dialog for viewing server WireGuard peer's persistent keepalive
 * Read-only view for server configurations
 */
class ServerWgPeerEditDialog(
    private val activity: Activity,
    themeId: Int,
    @Suppress("UNUSED_PARAMETER") configId: Int,
    private val peer: Peer
) : Dialog(activity, themeId) {

    private lateinit var b: DialogServerWgPeerEditBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        b = DialogServerWgPeerEditBinding.inflate(layoutInflater)
        setContentView(b.root)
        initView()
        setupClickListeners()
    }

    private fun initView() {
        // Pre-fill persistent keepalive if present
        if (peer.persistentKeepalive.isPresent) {
            b.persistentKeepaliveEditText.setText(peer.persistentKeepalive.get().toString())
        }
        // Make it read-only for server configs
        b.persistentKeepaliveEditText.isEnabled = false
    }

    private fun setupClickListeners() {
        b.dialogCancelBtn.text = activity.getString(R.string.lbl_dismiss)
        b.dialogCancelBtn.setOnClickListener { dismiss() }

        // Remove save button for server configs
        b.dialogSaveBtn.visibility = android.view.View.GONE
    }
}

