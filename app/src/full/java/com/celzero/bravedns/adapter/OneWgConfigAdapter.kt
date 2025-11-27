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
package com.celzero.bravedns.adapter

import Logger.LOG_TAG_PROXY
import Logger.LOG_TAG_UI
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.firestack.backend.RouterStats
import com.celzero.bravedns.R
import com.celzero.bravedns.database.WgConfigFiles
import com.celzero.bravedns.databinding.ListItemWgOneInterfaceBinding
import com.celzero.bravedns.net.doh.Transaction
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.service.WireguardManager.ERR_CODE_OTHER_WG_ACTIVE
import com.celzero.bravedns.service.WireguardManager.ERR_CODE_VPN_NOT_ACTIVE
import com.celzero.bravedns.service.WireguardManager.ERR_CODE_VPN_NOT_FULL
import com.celzero.bravedns.service.WireguardManager.ERR_CODE_WG_INVALID
import com.celzero.bravedns.service.WireguardManager.WG_UPTIME_THRESHOLD
import com.celzero.bravedns.ui.activity.WgConfigDetailActivity
import com.celzero.bravedns.ui.activity.WgConfigDetailActivity.Companion.INTENT_EXTRA_WG_TYPE
import com.celzero.bravedns.ui.activity.WgConfigEditorActivity.Companion.INTENT_EXTRA_WG_ID
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.fetchColor
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OneWgConfigAdapter(private val context: Context, private val listener: DnsStatusListener) :
    PagingDataAdapter<WgConfigFiles, OneWgConfigAdapter.WgInterfaceViewHolder>(DIFF_CALLBACK) {

    private var lifecycleOwner: LifecycleOwner? = null

    interface DnsStatusListener {
        fun onDnsStatusChanged()
    }

    companion object {
        private const val DELAY_MS = 1500L
        private const val TAG = "OneWgCfgAdapter"
        private val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<WgConfigFiles>() {

                override fun areItemsTheSame(
                    oldConnection: WgConfigFiles,
                    newConnection: WgConfigFiles
                ): Boolean {
                    return oldConnection == newConnection
                }

                override fun areContentsTheSame(
                    oldConnection: WgConfigFiles,
                    newConnection: WgConfigFiles
                ): Boolean {
                    return oldConnection == newConnection
                }
            }
    }

    override fun onBindViewHolder(holder: WgInterfaceViewHolder, position: Int) {
        val wgConfigFiles: WgConfigFiles = getItem(position) ?: return
        holder.update(wgConfigFiles)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WgInterfaceViewHolder {
        val itemBinding =
            ListItemWgOneInterfaceBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        if (lifecycleOwner == null) {
            lifecycleOwner = parent.findViewTreeLifecycleOwner()
        }
        return WgInterfaceViewHolder(itemBinding)
    }

    override fun onViewDetachedFromWindow(holder: WgInterfaceViewHolder) {
        super.onViewDetachedFromWindow(holder)
        holder.cancelJobIfAny()
    }

    inner class WgInterfaceViewHolder(private val b: ListItemWgOneInterfaceBinding) :
        RecyclerView.ViewHolder(b.root) {
        private var job: Job? = null

        fun update(config: WgConfigFiles) {
            b.interfaceNameText.text = config.name
            b.interfaceNameText.isSelected = true
            b.interfaceIdText.text = context.getString(R.string.single_argument_parenthesis, config.id.toString())
            val isWgActive = config.isActive && VpnController.hasTunnel()
            b.oneWgCheck.isChecked = isWgActive
            setupClickListeners(config)
            if (isWgActive) {
                keepStatusUpdated(config)
            } else {
                cancelJobIfAny()
                disableInterface()
            }
        }

        fun cancelJobIfAny() {
            if (job?.isActive == true) {
                job?.cancel()
            }
        }

        private fun keepStatusUpdated(config: WgConfigFiles) {
            job = io {
                while (true) {
                    updateStatus(config)
                    delay(DELAY_MS)
                }
            }
        }

        private fun updateProtocolChip(pair: Pair<Boolean, Boolean>) {
            if (b.protocolInfoChipGroup.isVisible) return

            if (!pair.first && !pair.second) {
                b.protocolInfoChipGroup.visibility = View.GONE
                return
            }
            b.protocolInfoChipGroup.visibility = View.VISIBLE
            if (pair.first) {
                b.protocolInfoChipIpv4.visibility = View.VISIBLE
            } else {
                b.protocolInfoChipIpv4.visibility = View.GONE
            }
            if (pair.second) {
                b.protocolInfoChipIpv6.visibility = View.VISIBLE
            } else {
                b.protocolInfoChipIpv6.visibility = View.GONE
            }
        }

        private fun updateSplitTunnelChip(isSplitTunnel: Boolean) {
            if (isSplitTunnel) {
                b.chipSplitTunnel.visibility = View.VISIBLE
            } else {
                b.chipSplitTunnel.visibility = View.GONE
            }
        }

        private suspend fun updateStatus(config: WgConfigFiles) {
            // if the view is not active then cancel the job
            if (
                lifecycleOwner
                    ?.lifecycle
                    ?.currentState
                    ?.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) == false
            ) {
                job?.cancel()
                return
            }

            if (config.isActive && !VpnController.hasTunnel()) {
                // Fix: disableInterface() modifies UI, must run on main thread
                uiCtx { disableInterface() }
                return
            }

            val id = ProxyManager.ID_WG_BASE + config.id
            val statusPair = VpnController.getProxyStatusById(id)
            val pair = VpnController.getSupportedIpVersion(id)
            val c = WireguardManager.getConfigById(config.id)
            val stats = VpnController.getProxyStats(id)
            val dnsStatusId = VpnController.getDnsStatus(ProxyManager.ID_WG_BASE + config.id)
            val isSplitTunnel =
                if (c?.getPeers()?.isNotEmpty() == true) {
                    VpnController.isSplitTunnelProxy(id, pair)
                } else {
                    false
                }
            uiCtx {
                updateStatusUi(config, statusPair, dnsStatusId, stats)
                updateProtocolChip(pair)
                updateSplitTunnelChip(isSplitTunnel)
            }
        }

        private fun isDnsError(statusId: Long?): Boolean {
            if (statusId == null) return true

            val s = Transaction.Status.fromId(statusId)
            return s == Transaction.Status.BAD_QUERY || s == Transaction.Status.BAD_RESPONSE || s == Transaction.Status.NO_RESPONSE || s == Transaction.Status.SEND_FAIL || s == Transaction.Status.CLIENT_ERROR || s == Transaction.Status.INTERNAL_ERROR || s == Transaction.Status.TRANSPORT_ERROR
        }

        private fun updateStatusUi(config: WgConfigFiles, statusPair: Pair<Long?, String>, dnsStatusId: Long?, stats: RouterStats?) {
            if (config.isActive && VpnController.hasTunnel()) {
                b.interfaceDetailCard.strokeWidth = 2
                b.oneWgCheck.isChecked = true
                b.interfaceAppsCount.visibility = View.VISIBLE
                b.interfaceAppsCount.text = context.getString(R.string.one_wg_apps_added)

                if (dnsStatusId != null) {
                    // check for dns failure cases and update the UI
                    if (isDnsError(dnsStatusId)) {
                        b.interfaceDetailCard.strokeColor = fetchColor(context, R.attr.chipTextNegative)
                        b.interfaceStatus.text =
                            context.getString(R.string.status_failing).replaceFirstChar(Char::titlecase)
                    } else {
                        // if dns status is not failing, then update the proxy status
                        updateProxyStatusUi(statusPair, stats)
                    }
                } else {
                    // in one wg mode, if dns status should be available, this is a fallback case
                    updateProxyStatusUi(statusPair, stats)
                }

                b.interfaceActiveLayout.visibility = View.VISIBLE
                val rxtx = getRxTx(stats)
                val time = getUpTime(stats)

                if (time.isNotEmpty()) {
                    val t = context.getString(R.string.logs_card_duration, time)
                    b.interfaceActiveUptime.text =
                        context.getString(
                            R.string.two_argument_space,
                            context.getString(R.string.lbl_active),
                            t
                        )
                } else {
                    b.interfaceActiveUptime.text = context.getString(R.string.lbl_active)
                }
                b.interfaceActiveRxTx.text = rxtx
            } else {
                disableInterface()
            }
        }

        private fun getStrokeColorForStatus(status: UIUtils.ProxyStatus?, stats: RouterStats?): Int{
            val now = System.currentTimeMillis()
            val lastOk = stats?.lastOK ?: 0L
            val since = stats?.since ?: 0L
            val isFailing = now - since > WG_UPTIME_THRESHOLD && lastOk == 0L
            return when (status) {
                UIUtils.ProxyStatus.TOK -> if (isFailing) R.attr.chipTextNeutral else R.attr.accentGood
                UIUtils.ProxyStatus.TUP, UIUtils.ProxyStatus.TZZ, UIUtils.ProxyStatus.TNT -> R.attr.chipTextNeutral
                else -> R.attr.chipTextNegative // TKO, TEND
            }
        }

        private fun getStatusText(
            status: UIUtils.ProxyStatus?,
            handshakeTime: String? = null,
            stats: RouterStats?,
            errMsg: String? = null
        ): String {
            if (status == null) {
                val txt = if (errMsg != null) {
                    context.getString(R.string.status_waiting) + " ($errMsg)"
                } else {
                    context.getString(R.string.status_waiting)
                }
                return txt.replaceFirstChar(Char::titlecase)
            }

            val now = System.currentTimeMillis()
            val lastOk = stats?.lastOK ?: 0L
            val since = stats?.since ?: 0L
            if (now - since > WG_UPTIME_THRESHOLD && lastOk == 0L) {
                return context.getString(R.string.status_failing).replaceFirstChar(Char::titlecase)
            }

            val baseText = context.getString(UIUtils.getProxyStatusStringRes(status.id))
                .replaceFirstChar(Char::titlecase)

            return if (stats?.lastOK != 0L && handshakeTime != null) {
                context.getString(R.string.about_version_install_source, baseText, handshakeTime)
            } else {
                baseText
            }
        }

        private fun updateProxyStatusUi(statusPair: Pair<Long?, String>, stats: RouterStats?) {
            val status =
                UIUtils.ProxyStatus.entries.find { it.id == statusPair.first } // Convert to enum

            val handshakeTime = getHandshakeTime(stats).toString()

            val strokeColor = getStrokeColorForStatus(status, stats)
            b.interfaceDetailCard.strokeColor = fetchColor(context, strokeColor)
            val statusText = getStatusText(status, handshakeTime, stats, statusPair.second)
            b.interfaceStatus.text = statusText
        }

        private fun disableInterface() {
            b.interfaceDetailCard.strokeWidth = 0
            b.protocolInfoChipGroup.visibility = View.GONE
            b.interfaceAppsCount.visibility = View.GONE
            b.oneWgCheck.isChecked = false
            b.interfaceActiveLayout.visibility = View.GONE
            b.interfaceStatus.text =
                context.getString(R.string.lbl_disabled).replaceFirstChar(Char::titlecase)
        }

        private fun getUpTime(stats: RouterStats?): CharSequence {
            if (stats == null) {
                return ""
            }
            if (stats.since <= 0L) {
                return ""
            }
            val now = System.currentTimeMillis()
            // returns a string describing 'time' as a time relative to 'now'
            return DateUtils.getRelativeTimeSpanString(
                stats.since,
                now,
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            )
        }

        private fun getRxTx(stats: RouterStats?): String {
            if (stats == null) return ""
            val rx =
                context.getString(
                    R.string.symbol_download,
                    Utilities.humanReadableByteCount(stats.rx, true)
                )
            val tx =
                context.getString(
                    R.string.symbol_upload,
                    Utilities.humanReadableByteCount(stats.tx, true)
                )
            return context.getString(R.string.two_argument_space, tx, rx)
        }

        private fun getHandshakeTime(stats: RouterStats?): CharSequence {
            if (stats == null) {
                return ""
            }
            if (stats.lastOK == 0L) {
                return ""
            }
            val now = System.currentTimeMillis()
            // returns a string describing 'time' as a time relative to 'now'
            return DateUtils.getRelativeTimeSpanString(
                stats.lastOK,
                now,
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            )
        }

        fun setupClickListeners(config: WgConfigFiles) {
            b.interfaceDetailCard.setOnClickListener { launchConfigDetail(config.id) }

            b.oneWgCheck.setOnClickListener {
                val isChecked = b.oneWgCheck.isChecked
                io {
                    if (isChecked) {
                        enableWgIfPossible(config)
                    } else {
                        disableWgIfPossible(config)
                    }
                }
            }
        }

        private suspend fun enableWgIfPossible(config: WgConfigFiles) {
            if (!VpnController.hasTunnel()) {
                Logger.i(LOG_TAG_PROXY, "$TAG VPN not active, cannot enable WireGuard")
                uiCtx {
                    Utilities.showToastUiCentered(
                        context,
                        ERR_CODE_VPN_NOT_ACTIVE +
                            context.getString(R.string.settings_socks5_vpn_disabled_error),
                        Toast.LENGTH_LONG
                    )
                    // reset the check box
                    b.oneWgCheck.isChecked = false
                }
                return
            }

            if (!WireguardManager.canEnableProxy()) {
                Logger.i(LOG_TAG_PROXY, "not in DNS+Firewall mode, cannot enable WireGuard")
                uiCtx {
                    // reset the check box
                    b.oneWgCheck.isChecked = false
                    Utilities.showToastUiCentered(
                        context,
                        ERR_CODE_VPN_NOT_FULL +
                            context.getString(R.string.wireguard_enabled_failure),
                        Toast.LENGTH_LONG
                    )
                }
                return
            }

            if (WireguardManager.isAnyOtherOneWgEnabled(config.id)) {
                Logger.i(LOG_TAG_PROXY, "another WireGuard is already enabled")
                uiCtx {
                    // reset the check box
                    b.oneWgCheck.isChecked = false
                    Utilities.showToastUiCentered(
                        context,
                        ERR_CODE_OTHER_WG_ACTIVE +
                            context.getString(R.string.wireguard_enabled_failure),
                        Toast.LENGTH_LONG
                    )
                }
                return
            }

            if (!WireguardManager.isValidConfig(config.id)) {
                Logger.i(LOG_TAG_PROXY, "invalid WireGuard config")
                uiCtx {
                    // reset the check box
                    b.oneWgCheck.isChecked = false
                    Utilities.showToastUiCentered(
                        context,
                        ERR_CODE_WG_INVALID + context.getString(R.string.wireguard_enabled_failure),
                        Toast.LENGTH_LONG
                    )
                }
                return
            }

            Logger.i(LOG_TAG_PROXY, "enabling WireGuard, id: ${config.id}")
            WireguardManager.updateOneWireGuardConfig(config.id, owg = true)
            config.oneWireGuard = true
            WireguardManager.enableConfig(config.toImmutable())
            uiCtx { listener.onDnsStatusChanged() }
        }

        private suspend fun disableWgIfPossible(config: WgConfigFiles) {
            if (!VpnController.hasTunnel()) {
                Logger.i(LOG_TAG_PROXY, "VPN not active, cannot disable WireGuard")
                uiCtx {
                    // reset the check box
                    b.oneWgCheck.isChecked = true
                    Utilities.showToastUiCentered(
                        context,
                        ERR_CODE_VPN_NOT_ACTIVE +
                            context.getString(R.string.settings_socks5_vpn_disabled_error),
                        Toast.LENGTH_LONG
                    )
                }
                return
            }

            Logger.i(LOG_TAG_PROXY, "disabling WireGuard, id: ${config.id}")
            WireguardManager.updateOneWireGuardConfig(config.id, owg = false)
            config.oneWireGuard = false
            WireguardManager.disableConfig(config.toImmutable())
            uiCtx { listener.onDnsStatusChanged() }
        }

        private fun launchConfigDetail(id: Int) {
            if (!VpnController.hasTunnel()) {
                Utilities.showToastUiCentered(
                    context,
                    context.getString(R.string.ssv_toast_start_rethink),
                    Toast.LENGTH_SHORT
                )
                return
            }

            val intent = Intent(context, WgConfigDetailActivity::class.java)
            intent.putExtra(INTENT_EXTRA_WG_ID, id)
            intent.putExtra(INTENT_EXTRA_WG_TYPE, WgConfigDetailActivity.WgType.ONE_WG.value)
            context.startActivity(intent)
        }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }

    private fun io(f: suspend () -> Unit): Job? {
        return lifecycleOwner?.lifecycleScope?.launch(Dispatchers.IO) { f() }
    }
}
