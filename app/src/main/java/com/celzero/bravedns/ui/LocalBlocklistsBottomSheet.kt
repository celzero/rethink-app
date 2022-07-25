/*
 * Copyright 2022 RethinkDNS and its authors
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
package com.celzero.bravedns.ui

import android.content.DialogInterface
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.BottomSheetLocalBlocklistsBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.RETHINK_SEARCH_URL
import com.celzero.bravedns.util.Themes.Companion.getBottomsheetCurrentTheme
import com.celzero.bravedns.util.Utilities
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class LocalBlocklistsBottomSheet(private val context: DnsConfigureFragment) :
        BottomSheetDialogFragment() {
    private var _binding: BottomSheetLocalBlocklistsBinding? = null

    // This property is only valid between onCreateView and onDestroyView.
    private val b get() = _binding!!

    private val persistentState by inject<PersistentState>()
    private var dismissListener: OnBottomSheetDialogFragmentDismiss? = null

    override fun getTheme(): Int = getBottomsheetCurrentTheme(isDarkThemeOn(),
                                                              persistentState.theme)

    interface OnBottomSheetDialogFragmentDismiss {
        fun onBtmSheetDismiss()
    }

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        _binding = BottomSheetLocalBlocklistsBinding.inflate(inflater, container, false)
        dismissListener = context
        return b.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        dismissListener?.onBtmSheetDismiss()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        updateLocalBlocklistUi()
        initializeClickListeners()
    }

    private fun updateLocalBlocklistUi() {
        if (Utilities.isPlayStoreFlavour()) {
            return
        }

        if (persistentState.blocklistEnabled) {
            enableBlocklistUi()
            return
        }

        disableBlocklistUi()
    }

    private fun enableBlocklistUi() {
        b.lbbsEnable.text = getString(R.string.lbbs_enabled)
        b.lbbsHeading.text = getString(R.string.settings_local_blocklist_in_use,
                                       persistentState.numberOfLocalBlocklists.toString())
        setDrawable(R.drawable.ic_tick, b.lbbsEnable)

        b.lbbsConfigure.isEnabled = true
        b.lbbsCopy.isEnabled = true
        b.lbbsSearch.isEnabled = true

        b.lbbsConfigure.alpha = 1f
        b.lbbsCopy.alpha = 1f
        b.lbbsSearch.alpha = 1f
    }

    private fun disableBlocklistUi() {
        b.lbbsEnable.text = getString(R.string.lbbs_enable)
        b.lbbsHeading.text = getString(R.string.lbbs_heading)
        setDrawable(R.drawable.ic_cross, b.lbbsEnable)

        b.lbbsConfigure.isEnabled = false
        b.lbbsCopy.isEnabled = false
        b.lbbsSearch.isEnabled = false

        b.lbbsConfigure.alpha = 0.5f
        b.lbbsCopy.alpha = 0.5f
        b.lbbsSearch.alpha = 0.5f
    }

    private fun initializeClickListeners() {
        b.lbbsEnable.setOnClickListener {
            enableBlocklist()
        }

        b.lbbsConfigure.setOnClickListener {
            invokeRethinkActivity()
        }

        b.lbbsCopy.setOnClickListener {
            val url = Constants.RETHINK_BASE_URL + persistentState.localBlocklistStamp
            Utilities.clipboardCopy(requireContext(), url,
                                    context.getString(R.string.copy_clipboard_label))
            Utilities.showToastUiCentered(requireContext(), context.getString(
                R.string.info_dialog_rethink_toast_msg), Toast.LENGTH_SHORT)
        }

        b.lbbsSearch.setOnClickListener {
            // https://rethinkdns.com/search?s=<uri-encoded-stamp>
            this.dismiss()
            val url = RETHINK_SEARCH_URL + Uri.encode(persistentState.localBlocklistStamp)
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            startActivity(intent)
        }
    }

    private fun enableBlocklist() {
        if (persistentState.blocklistEnabled) {
            removeBraveDnsLocal()
            updateLocalBlocklistUi()
            return
        }

        go {
            uiCtx {
                val blocklistsExist = withContext(Dispatchers.Default) {
                    Utilities.hasLocalBlocklists(requireContext(),
                                                 persistentState.localBlocklistTimestamp)
                }
                if (blocklistsExist && isLocalBlocklistStampAvailable()) {
                    setBraveDnsLocal()
                    updateLocalBlocklistUi()
                } else {
                    invokeRethinkActivity()
                }
            }
        }
    }

    private fun invokeRethinkActivity() {
        this.dismiss()
        val intent = Intent(requireContext(), ConfigureRethinkBasicActivity::class.java)
        intent.putExtra(ConfigureRethinkBasicActivity.INTENT,
                        ConfigureRethinkBasicActivity.FragmentLoader.LOCAL.ordinal)
        context.startActivity(intent)
    }

    private fun isLocalBlocklistStampAvailable(): Boolean {
        if (persistentState.localBlocklistStamp.isEmpty()) {
            return false
        }

        return true
    }

    // FIXME: Verification of BraveDns object should be added in future.
    private fun setBraveDnsLocal() {
        persistentState.blocklistEnabled = true
    }

    private fun removeBraveDnsLocal() {
        persistentState.blocklistEnabled = false
    }

    private fun setDrawable(drawable: Int, txt: AppCompatTextView) {
        val end = ContextCompat.getDrawable(requireContext(), drawable)
        txt.setCompoundDrawablesWithIntrinsicBounds(null, null, end, null)
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) {
            f()
        }
    }

    private fun go(f: suspend () -> Unit) {
        lifecycleScope.launch {
            f()
        }
    }

}
