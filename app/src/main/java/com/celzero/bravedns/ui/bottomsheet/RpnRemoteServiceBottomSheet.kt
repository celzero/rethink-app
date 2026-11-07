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
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.rpn.RemoteServiceContent
import com.celzero.bravedns.ui.compose.rpn.RemoteServiceState
import androidx.lifecycle.lifecycleScope
import com.celzero.bravedns.R
import com.celzero.bravedns.net.go.GoVpnAdapter
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities
import com.google.android.material.bottomsheet.BottomSheetBehavior
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import kotlin.time.Duration.Companion.milliseconds

/**
 * DEBUG-only sheet to connect a laptop (same Wi-Fi) to an on-device proxy that
 * egresses via the RPN tunnel. Shows the address/port from the tunnel service
 * and the live connection status.
 */
class RpnRemoteServiceBottomSheet : BaseBottomSheetDialogFragment() {

    private var presentation: RemoteServiceState? = null
    private val b
        get() = checkNotNull(presentation) { "Presentation accessed outside of view lifecycle" }

    private val persistentState by inject<PersistentState>()

    private var statusPollJob: Job? = null
    private var isRunning = false
    private var serviceGeneration = 0L
    private var runningInfo: GoVpnAdapter.RemoteServiceInfo? = null

    /** Address shown in the sheet; "ip:port" when start succeeds. */
    private var addrText: String = ""

    companion object {
        const val TAG = "RpnRemoteSvcBtmSheet"

        private const val SVC_OK = 1
        private const val SVC_NOT_OK = -1

        private const val STATUS_POLL_INTERVAL_MS = 1_500L

        fun newInstance(): RpnRemoteServiceBottomSheet = RpnRemoteServiceBottomSheet()
    }

    private fun isDarkThemeOn(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES

    override fun getTheme(): Int =
        Themes.getBottomSheetCurrentTheme(isDarkThemeOn(), persistentState.theme)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val state = RemoteServiceState()
        presentation = state
        runningInfo?.let(::showRunningState)
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { RethinkTheme { RemoteServiceContent(state) } }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dialog?.window?.let { window ->
            Themes.applyBottomSheetSystemBarAppearance(window, isDarkThemeOn(), persistentState.theme)
        }

        dialog?.setOnShowListener {
            val sheet = dialog?.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            ) ?: return@setOnShowListener
            BottomSheetBehavior.from(sheet).state = BottomSheetBehavior.STATE_EXPANDED
        }

        b.rmtCopy.setOnClickListener { copyAddress() }
        b.rmtStartStop.setOnClickListener { onStartStopClicked() }
        observeStatus()
    }

    override fun onDestroyView() {
        stopPolling()
        presentation = null
        super.onDestroyView()
    }

    private fun onStartStopClicked() {
        val state = presentation ?: return
        if (state.busy) return
        state.busy = true
        serviceGeneration++
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (isRunning) {
                    withContext(Dispatchers.IO) { VpnController.stopRemoteService() }
                    if (!isAdded || presentation == null) return@launch
                    showStoppedState()
                } else {
                    val info = withContext(Dispatchers.IO) { VpnController.startRemoteService() }
                    if (!isAdded || presentation == null) return@launch
                    if (info == null) {
                        Logger.w(LOG_TAG_UI, "$TAG: startRemoteService failed")
                        Utilities.showToastUiCentered(
                            requireContext(),
                            getString(R.string.rpn_remote_service_start_failure),
                            Toast.LENGTH_SHORT
                        )
                        return@launch
                    }
                    showRunningState(info)
                }
            } finally {
                state.busy = false
            }
        }
    }

    /** No-tunnel hint vs interactive Start state, re-evaluated on entry. */
    private suspend fun refreshTunnelUi() {
        val hasTunnel = withContext(Dispatchers.IO) { VpnController.hasTunnel() }
        if (!isAdded || presentation == null || isRunning || b.busy) return
        if (!hasTunnel) {
            b.rmtAddrCard.isVisible = false
            b.rmtStatusRow.isVisible = false
            b.rmtNoTunnel.isVisible = true
            b.rmtStartStop.isVisible = false
        } else {
            b.rmtNoTunnel.isVisible = false
            showStoppedState()
        }
    }

    private fun showStoppedState() {
        isRunning = false
        runningInfo = null
        addrText = ""
        b.rmtAddrCard.isVisible = false
        b.rmtStatusRow.isVisible = false
        b.rmtStartStop.isVisible = true
        b.rmtStartStop.text = getString(R.string.rpn_remote_service_start)
    }

    private fun showRunningState(info: GoVpnAdapter.RemoteServiceInfo) {
        isRunning = true
        runningInfo = info
        addrText = info.addr
        b.rmtNoTunnel.isVisible = false
        b.rmtStartStop.isVisible = true
        b.rmtStartStop.text = getString(R.string.rpn_remote_service_stop)
        b.rmtAddr.text = addrText.ifEmpty { getString(R.string.rpn_remote_service_no_addr) }
        b.rmtAddrCard.isVisible = true
        applyStatusUi(null)
    }

    /** Register once per view; repeatOnLifecycle cancels/restarts only the active loop. */
    private fun observeStatus() {
        statusPollJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (!isRunning) refreshTunnelUi()
                while (isActive) {
                    if (isRunning && presentation?.busy == false) {
                        val generation = serviceGeneration
                        val status = withContext(Dispatchers.IO) { VpnController.remoteServiceStatus() }
                        if (!isAdded || presentation == null) return@repeatOnLifecycle
                        // A start/stop request may have completed while the status read was in flight.
                        if (isRunning && !b.busy && generation == serviceGeneration) {
                            if (status == null) showStoppedState() else applyStatusUi(status)
                        }
                    }
                    // Stay registered while stopped so a later Start resumes polling without
                    // creating another lifecycle observer or restarting the fragment.
                    delay(STATUS_POLL_INTERVAL_MS.milliseconds)
                }
            }
        }
    }

    private fun stopPolling() {
        statusPollJob?.cancel()
        statusPollJob = null
    }

    private fun applyStatusUi(status: Int?) {
        b.rmtStatusRow.isVisible = true
        when (status) {
            SVC_OK -> {
                b.rmtStatusDot.setBackgroundResource(R.drawable.dot_green)
                b.rmtStatusText.text = getString(R.string.rpn_remote_service_connected)
            }
            SVC_NOT_OK -> {
                b.rmtStatusDot.setBackgroundResource(R.drawable.dot_red)
                b.rmtStatusText.text = getString(R.string.rpn_remote_service_not_ok)
            }
            else -> {
                b.rmtStatusDot.setBackgroundResource(R.drawable.dot_yellow)
                b.rmtStatusText.text = getString(R.string.rpn_remote_service_waiting)
            }
        }
    }

    private fun copyAddress() {
        if (addrText.isEmpty()) return
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(addrText, addrText))
        Utilities.showToastUiCentered(
            requireContext(),
            getString(R.string.rpn_remote_service_copied),
            Toast.LENGTH_SHORT
        )
    }
}
