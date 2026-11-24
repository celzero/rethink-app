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

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.BottomsheetRpnRemoteServiceBinding
import com.celzero.bravedns.net.go.GoVpnAdapter
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils
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

class RpnRemoteServiceBottomSheet : BaseBottomSheetDialogFragment() {

    private var _binding: BottomsheetRpnRemoteServiceBinding? = null
    private val b
        get() = checkNotNull(_binding) { "Binding accessed outside of view lifecycle" }

    private val persistentState by inject<PersistentState>()

    private var mode: Mode = Mode.SOCKS5

    private val running = linkedMapOf<GoVpnAdapter.RemoteServiceType, Int?>()

    private val addresses = mutableMapOf<GoVpnAdapter.RemoteServiceType, GoVpnAdapter.RemoteServiceInfo>()

    private var hasTunnel = true
    private var isTransitioning = false
    private var isStopping = false

    private var lanIp = ""

    private var statusPollJob: Job? = null

    private var unselectedCardBg: Int? = null

    private val failed = mutableSetOf<GoVpnAdapter.RemoteServiceType>()

    enum class Mode(val types: List<GoVpnAdapter.RemoteServiceType>, val captionRes: Int) {
        SOCKS5(
            listOf(GoVpnAdapter.RemoteServiceType.SOCKS5),
            R.string.rpn_remote_service_caption_socks5
        ),
        HTTP(
            listOf(GoVpnAdapter.RemoteServiceType.HTTP),
            R.string.rpn_remote_service_caption_http
        ),
        BOTH(
            listOf(GoVpnAdapter.RemoteServiceType.SOCKS5, GoVpnAdapter.RemoteServiceType.HTTP),
            R.string.rpn_remote_service_caption_both
        )
    }

    companion object {
        const val TAG = "RpnRemoteSvcBtmSheet"

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
        _binding = BottomsheetRpnRemoteServiceBinding.inflate(inflater, container, false)
        return b.root
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

        b.rmtModeSocks5.setOnClickListener { onModeClicked(Mode.SOCKS5) }
        b.rmtModeHttp.setOnClickListener { onModeClicked(Mode.HTTP) }
        b.rmtModeBoth.setOnClickListener { onModeClicked(Mode.BOTH) }
        b.rmtSocks5Copy.setOnClickListener { onCopyClicked(GoVpnAdapter.RemoteServiceType.SOCKS5) }
        b.rmtHttpCopy.setOnClickListener { onCopyClicked(GoVpnAdapter.RemoteServiceType.HTTP) }
        b.rmtStartStop.setOnClickListener { onStartStopClicked() }

        probe()
    }

    override fun onStop() {
        stopPolling()
        super.onStop()
    }

    override fun onDestroyView() {
        stopPolling()
        _binding = null
        super.onDestroyView()
    }

    /**
     * Rebuilds the whole UI from live state: tunnel presence plus per-type statuses.
     */
    private fun probe() {
        viewLifecycleOwner.lifecycleScope.launch {
            val tunnel = withContext(Dispatchers.IO) { VpnController.hasTunnel() }
            val live = linkedMapOf<GoVpnAdapter.RemoteServiceType, Int?>()
            GoVpnAdapter.RemoteServiceType.entries.forEach { type ->
                val status = withContext(Dispatchers.IO) { VpnController.remoteServiceStatus(type) }
                if (status != null && status != GoVpnAdapter.SVC_STOPPED) live[type] = status
            }
            val infos = withContext(Dispatchers.IO) {
                live.keys.associateWith { VpnController.remoteServiceInfo(it) }
            }
            val lan = withContext(Dispatchers.IO) { Utilities.getLanIpv4() }
            if (!isAdded) return@launch
            hasTunnel = tunnel
            running.clear()
            running.putAll(live)
            addresses.clear()
            infos.forEach { (type, info) -> info?.let { addresses[type] = it } }
            failed.clear()
            lanIp = lan
            Logger.v(LOG_TAG_UI, "$TAG: probed tunnel=$tunnel, running=${live.keys}")
            render()
            if (running.isNotEmpty()) pollStatus() else stopPolling()
        }
    }

    private fun onModeClicked(newMode: Mode) {
        if (isTransitioning || !hasTunnel || newMode == mode) return

        if (running.isNotEmpty()) {
            // switching type while running: stop everything, start the new mode
            restartWithMode(newMode)
        } else {
            mode = newMode
            render()
        }
    }

    private fun onStartStopClicked() {
        if (isTransitioning || !hasTunnel) return
        if (running.isNotEmpty()) {
            stopService()
        } else {
            startService()
        }
    }

    private fun startService() {
        isTransitioning = true
        isStopping = false
        render()
        viewLifecycleOwner.lifecycleScope.launch {
            val started = linkedMapOf<GoVpnAdapter.RemoteServiceType, GoVpnAdapter.RemoteServiceInfo>()
            for (type in mode.types) {
                val info = withContext(Dispatchers.IO) { VpnController.startRemoteService(type) }
                if (info != null) started[type] = info
            }
            if (!isAdded) return@launch
            isTransitioning = false
            hasTunnel = true
            running.clear()
            addresses.clear()
            failed.clear()
            failed.addAll(mode.types - started.keys)
            started.forEach { (type, info) ->
                running[type] = null
                addresses[type] = info
            }
            if (started.isEmpty()) {
                Logger.w(LOG_TAG_UI, "$TAG: startRemoteService failed for mode $mode")
                Utilities.showToastUiCentered(
                    requireContext(),
                    getString(R.string.rpn_remote_service_start_failure),
                    Toast.LENGTH_SHORT
                )
            }
            render()
            if (running.isNotEmpty()) pollStatus()
        }
    }

    private fun stopService() {
        isTransitioning = true
        isStopping = true
        render()
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) { VpnController.stopAllRemoteServices() }
            if (!isAdded) return@launch
            isTransitioning = false
            isStopping = false
            running.clear()
            addresses.clear()
            failed.clear()
            stopPolling()
            render()
        }
    }

    private fun restartWithMode(newMode: Mode) {
        isTransitioning = true
        isStopping = false
        running.clear()
        addresses.clear()
        mode = newMode
        render()
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) { VpnController.stopAllRemoteServices() }
            val started = linkedMapOf<GoVpnAdapter.RemoteServiceType, GoVpnAdapter.RemoteServiceInfo>()
            for (type in mode.types) {
                val info = withContext(Dispatchers.IO) { VpnController.startRemoteService(type) }
                if (info != null) started[type] = info
            }
            if (!isAdded) return@launch
            isTransitioning = false
            running.clear()
            addresses.clear()
            failed.clear()
            failed.addAll(mode.types - started.keys)
            started.forEach { (type, info) ->
                running[type] = null
                addresses[type] = info
            }
            if (started.isEmpty()) {
                Utilities.showToastUiCentered(
                    requireContext(),
                    getString(R.string.rpn_remote_service_start_failure),
                    Toast.LENGTH_SHORT
                )
            }
            render()
            if (running.isNotEmpty()) pollStatus() else stopPolling()
        }
    }

    private fun pollStatus() {
        stopPolling()
        statusPollJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive && isAdded && running.isNotEmpty() && !isTransitioning) {
                val keys = running.keys.toList()
                val statuses = withContext(Dispatchers.IO) {
                    keys.associateWith { VpnController.remoteServiceStatus(it) }
                }
                if (!isAdded) return@launch
                statuses.forEach { (type, status) ->
                    if (status == GoVpnAdapter.SVC_STOPPED) {
                        // clear the adapter entry so a later start is not
                        // skipped as "already running"
                        withContext(Dispatchers.IO) { VpnController.stopRemoteService(type) }
                        running.remove(type)
                        addresses.remove(type)
                    } else {
                        running[type] = status
                    }
                }
                val allSilent = statuses.values.all {
                    it == null || it == GoVpnAdapter.SVC_STOPPED
                }
                if (allSilent) {
                    // nothing listening anywhere; re-derive the base state
                    hasTunnel = withContext(Dispatchers.IO) { VpnController.hasTunnel() }
                    if (!hasTunnel) {
                        running.clear()
                        addresses.clear()
                        failed.clear()
                        stopPolling()
                        render()
                        return@launch
                    }
                }
                render()
                delay(STATUS_POLL_INTERVAL_MS.milliseconds)
            }
        }
    }

    private fun stopPolling() {
        statusPollJob?.cancel()
        statusPollJob = null
    }

    /** Single render entry point; UI is a pure function of the fields above. */
    private fun render() {
        val anyRunning = running.isNotEmpty()

        // hero card: aggregate state
        when {
            !hasTunnel -> {
                b.rmtHeroDot.setBackgroundResource(R.drawable.dot_red)
                b.rmtHeroTitle.text = getString(R.string.rpn_remote_service_no_tunnel)
                b.rmtHeroHint.text = getString(R.string.rpn_remote_service_hero_stopped_hint)
            }
            isTransitioning && !isStopping -> {
                b.rmtHeroDot.setBackgroundResource(R.drawable.dot_yellow)
                b.rmtHeroTitle.text = getString(R.string.rpn_remote_service_hero_starting)
                b.rmtHeroHint.text = ""
            }
            isTransitioning && isStopping -> {
                b.rmtHeroDot.setBackgroundResource(R.drawable.dot_yellow)
                b.rmtHeroTitle.text = getString(R.string.rpn_remote_service_hero_stopping)
                b.rmtHeroHint.text = ""
            }
            anyRunning -> {
                b.rmtHeroDot.setBackgroundResource(R.drawable.dot_green)
                b.rmtHeroTitle.text = getString(R.string.rpn_remote_service_hero_listening)
                b.rmtHeroHint.text = getString(R.string.rpn_remote_service_hero_listening_hint)
            }
            else -> {
                b.rmtHeroDot.setBackgroundResource(R.drawable.dot_yellow)
                b.rmtHeroTitle.text = getString(R.string.rpn_remote_service_hero_stopped)
                b.rmtHeroHint.text = getString(R.string.rpn_remote_service_hero_stopped_hint)
            }
        }

        b.rmtProgress.isVisible = isTransitioning

        renderModeCards()

        renderEndpoint(GoVpnAdapter.RemoteServiceType.SOCKS5)
        renderEndpoint(GoVpnAdapter.RemoteServiceType.HTTP)

        val hints = buildList {
            if (running.containsKey(GoVpnAdapter.RemoteServiceType.SOCKS5)) {
                add(getString(R.string.rpn_remote_service_hint_socks5, displayUrl(GoVpnAdapter.RemoteServiceType.SOCKS5)))
            }
            if (running.containsKey(GoVpnAdapter.RemoteServiceType.HTTP)) {
                add(getString(R.string.rpn_remote_service_hint_http, displayUrl(GoVpnAdapter.RemoteServiceType.HTTP)))
            }
        }
        b.rmtEndpointsLabel.isVisible = anyRunning
        b.rmtUsageHint.isVisible = hints.isNotEmpty()
        b.rmtHintText.text = hints.joinToString("\n")

        // action button: start (idle) / stop (running); hidden without a tunnel
        b.rmtStartStop.isVisible = hasTunnel
        b.rmtStartStop.isEnabled = !isTransitioning
        b.rmtStartStop.alpha = if (isTransitioning) 0.5f else 1f
        if (anyRunning) {
            b.rmtStartStop.text = getString(R.string.rpn_remote_service_stop)
            b.rmtStartStop.backgroundTintList = ColorStateList.valueOf(resolveAttrColor(R.attr.accentBad))
        } else {
            b.rmtStartStop.text = getString(R.string.rpn_remote_service_start)
            b.rmtStartStop.backgroundTintList = ColorStateList.valueOf(resolveAttrColor(R.attr.accentGood))
        }
    }

    /** Selected mode card gets an accent stroke + tinted bg. */
    private fun renderModeCards() {
        if (unselectedCardBg == null) {
            unselectedCardBg = b.rmtModeSocks5.cardBackgroundColor.defaultColor
        }
        val modeEnabled = hasTunnel && !isTransitioning
        b.rmtModeCards.alpha = if (modeEnabled) 1f else 0.5f
        listOf(
            Mode.SOCKS5 to b.rmtModeSocks5,
            Mode.HTTP to b.rmtModeHttp,
            Mode.BOTH to b.rmtModeBoth,
        ).forEach { (m, card) ->
            val on = m == mode
            card.strokeWidth = if (on) dpToPx(1) else 0
            card.strokeColor = resolveAttrColor(R.attr.accentGood)
            card.setCardBackgroundColor(
                if (on) resolveAttrColor(R.attr.chipBgColorPositive) else unselectedCardBg!!
            )
            val icon = when (m) {
                Mode.SOCKS5 -> b.rmtModeSocks5Icon
                Mode.HTTP -> b.rmtModeHttpIcon
                Mode.BOTH -> b.rmtModeBothIcon
            }
            val label = when (m) {
                Mode.SOCKS5 -> b.rmtModeSocks5Label
                Mode.HTTP -> b.rmtModeHttpLabel
                Mode.BOTH -> b.rmtModeBothLabel
            }
            icon.setColorFilter(resolveAttrColor(if (on) R.attr.accentGood else R.attr.primaryLightColorText))
            icon.alpha = if (on) 1f else 0.6f
            label.setTextColor(resolveAttrColor(if (on) R.attr.accentGood else R.attr.primaryLightColorText))
        }
        b.rmtModeCaption.text = getString(mode.captionRes)
    }

    private fun renderEndpoint(type: GoVpnAdapter.RemoteServiceType) {
        val isSocks5 = type == GoVpnAdapter.RemoteServiceType.SOCKS5
        val card = if (isSocks5) b.rmtEndpointSocks5 else b.rmtEndpointHttp
        val addrView = if (isSocks5) b.rmtSocks5Addr else b.rmtHttpAddr
        val dot = if (isSocks5) b.rmtSocks5Dot else b.rmtHttpDot
        val statusText = if (isSocks5) b.rmtSocks5Status else b.rmtHttpStatus
        val copyBtn = if (isSocks5) b.rmtSocks5Copy else b.rmtHttpCopy

        val isFailed = type in failed
        val isVisible = running.containsKey(type) || isFailed
        card.isVisible = isVisible
        if (!isVisible) return

        copyBtn.isVisible = !isFailed
        addrView.text = when {
            isFailed -> "—"
            else -> displayUrl(type).ifEmpty { getString(R.string.rpn_remote_service_no_addr) }
        }

        val status = running[type]
        when {
            isFailed -> {
                dot.setBackgroundResource(R.drawable.dot_red)
                statusText.text = getString(R.string.rpn_remote_service_failed)
            }
            status == GoVpnAdapter.SVC_OK -> {
                dot.setBackgroundResource(R.drawable.dot_green)
                statusText.text = getString(R.string.rpn_remote_service_connected)
            }
            status == GoVpnAdapter.SVC_NOT_OK -> {
                dot.setBackgroundResource(R.drawable.dot_red)
                statusText.text = getString(R.string.rpn_remote_service_not_ok)
            }
            else -> {
                // not listening yet (transient after start, or go lost the listener)
                dot.setBackgroundResource(R.drawable.dot_yellow)
                statusText.text = getString(R.string.rpn_remote_service_waiting)
            }
        }
    }

    /** Display/copy string: scheme://<lan-ip>:<port>, falling back to the raw addr. */
    private fun displayUrl(type: GoVpnAdapter.RemoteServiceType): String {
        val info = addresses[type] ?: return ""
        if (info.addr.isEmpty() || info.port.isEmpty()) return ""
        val scheme = when (type) {
            GoVpnAdapter.RemoteServiceType.SOCKS5 -> "socks5"
            GoVpnAdapter.RemoteServiceType.HTTP -> "http"
        }
        // go may report a bind address ("0.0.0.0:port" / ":port"); prefer the lan ip
        val bindHost = info.addr.substringBeforeLast(':', missingDelimiterValue = "")
        val host = when {
            lanIp.isNotEmpty() -> lanIp
            bindHost.isNotEmpty() && bindHost != "0.0.0.0" -> bindHost
            else -> ""
        }
        return if (host.isNotEmpty()) "$scheme://$host:${info.port}" else ""
    }

    private fun onCopyClicked(type: GoVpnAdapter.RemoteServiceType) {
        viewLifecycleOwner.lifecycleScope.launch {
            val url = withContext(Dispatchers.IO) {
                if (lanIp.isEmpty()) {
                    val lan = Utilities.getLanIpv4()
                    if (lan.isNotEmpty() && isAdded) lanIp = lan
                }
                displayUrl(type)
            }
            if (!isAdded || url.isEmpty()) return@launch
            val copied = UIUtils.clipboardCopy(requireContext(), url, url)
            val toastRes = if (copied) {
                when (type) {
                    GoVpnAdapter.RemoteServiceType.SOCKS5 -> R.string.rpn_remote_service_copied_socks5
                    GoVpnAdapter.RemoteServiceType.HTTP -> R.string.rpn_remote_service_copied_http
                }
            } else {
                R.string.copy_failed_toast
            }
            Utilities.showToastUiCentered(requireContext(), getString(toastRes), Toast.LENGTH_SHORT)
        }
    }

    private fun resolveAttrColor(attr: Int): Int {
        val typedValue = TypedValue()
        requireContext().theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density + 0.5f).toInt()
    }
}
