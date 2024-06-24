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
import backend.Backend
import backend.Stats
import com.celzero.bravedns.R
import com.celzero.bravedns.database.WgConfigFiles
import com.celzero.bravedns.databinding.ListItemWgOneInterfaceBinding
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.service.WireguardManager.ERR_CODE_OTHER_WG_ACTIVE
import com.celzero.bravedns.service.WireguardManager.ERR_CODE_VPN_NOT_ACTIVE
import com.celzero.bravedns.service.WireguardManager.ERR_CODE_VPN_NOT_FULL
import com.celzero.bravedns.service.WireguardManager.ERR_CODE_WG_INVALID
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
        private const val ONE_SEC = 1500L

        private val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<WgConfigFiles>() {

                override fun areItemsTheSame(
                    oldConnection: WgConfigFiles,
                    newConnection: WgConfigFiles
                ): Boolean {
                    return (oldConnection == newConnection)
                }

                override fun areContentsTheSame(
                    oldConnection: WgConfigFiles,
                    newConnection: WgConfigFiles
                ): Boolean {
                    return (oldConnection.id == newConnection.id &&
                        oldConnection.name == newConnection.name &&
                        oldConnection.isActive == newConnection.isActive &&
                        oldConnection.oneWireGuard == newConnection.oneWireGuard)
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
                    delay(ONE_SEC)
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
                disableInterface()
                return
            }

            val id = ProxyManager.ID_WG_BASE + config.id
            val statusId = VpnController.getProxyStatusById(id)
            val pair = VpnController.getSupportedIpVersion(id)
            val c = WireguardManager.getConfigById(config.id)
            val stats = VpnController.getProxyStats(id)
            val isSplitTunnel =
                if (c?.getPeers()?.isNotEmpty() == true) {
                    VpnController.isSplitTunnelProxy(id, pair)
                } else {
                    false
                }
            uiCtx {
                updateStatusUi(config, statusId, stats)
                updateProtocolChip(pair)
                updateSplitTunnelChip(isSplitTunnel)
            }
        }

        private fun updateStatusUi(config: WgConfigFiles, statusId: Long?, stats: Stats?) {
            if (config.isActive && VpnController.hasTunnel()) {
                b.interfaceDetailCard.strokeWidth = 2
                b.oneWgCheck.isChecked = true
                b.interfaceAppsCount.visibility = View.VISIBLE
                b.interfaceAppsCount.text = context.getString(R.string.one_wg_apps_added)
                var status: String
                val handShakeTime = getHandshakeTime(stats)
                if (statusId != null) {
                    var resId = UIUtils.getProxyStatusStringRes(statusId)
                    // change the color based on the status
                    if (statusId == Backend.TOK) {
                        if (stats?.lastOK == 0L) {
                            b.interfaceDetailCard.strokeColor =
                                fetchColor(context, R.attr.chipTextNeutral)
                            resId = R.string.status_waiting
                        } else {
                            b.interfaceDetailCard.strokeColor =
                                fetchColor(context, R.attr.accentGood)
                        }
                    } else if (statusId == Backend.TUP ||
                        statusId == Backend.TZZ ||
                        statusId == Backend.TNT
                    ) {
                        b.interfaceDetailCard.strokeColor =
                            fetchColor(context, R.attr.chipTextNeutral)
                    } else {
                        b.interfaceDetailCard.strokeColor =
                            fetchColor(context, R.attr.chipTextNegative)
                    }
                    status =
                        if (stats?.lastOK == 0L) {
                            context.getString(resId).replaceFirstChar(Char::titlecase)
                        } else {
                            context.getString(
                                R.string.about_version_install_source,
                                context.getString(resId).replaceFirstChar(Char::titlecase),
                                handShakeTime
                            )
                        }

                    if ((statusId == Backend.TZZ || statusId == Backend.TNT) && stats != null) {
                        // for idle state, if lastOk is less than 30 sec, then show as connected
                        if (
                            stats.lastOK != 0L &&
                                System.currentTimeMillis() - stats.lastOK <
                                    30 * DateUtils.SECOND_IN_MILLIS
                        ) {
                            status =
                                context
                                    .getString(R.string.dns_connected)
                                    .replaceFirstChar(Char::titlecase)
                        }
                    }
                } else {
                    b.interfaceDetailCard.strokeColor = fetchColor(context, R.attr.chipTextNegative)
                    b.interfaceDetailCard.strokeWidth = 2
                    status =
                        context.getString(R.string.status_waiting).replaceFirstChar(Char::titlecase)
                }
                b.interfaceStatus.text = status
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

        private fun disableInterface() {
            b.interfaceDetailCard.strokeWidth = 0
            b.protocolInfoChipGroup.visibility = View.GONE
            b.interfaceAppsCount.visibility = View.GONE
            b.oneWgCheck.isChecked = false
            b.interfaceActiveLayout.visibility = View.GONE
            b.interfaceStatus.text =
                context.getString(R.string.lbl_disabled).replaceFirstChar(Char::titlecase)
        }

        private fun getUpTime(stats: Stats?): CharSequence {
            if (stats == null) {
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

        private fun getRxTx(stats: Stats?): String {
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

        private fun getHandshakeTime(stats: Stats?): CharSequence {
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
                Logger.i(LOG_TAG_PROXY, "VPN not active, cannot enable WireGuard")
                uiCtx {
                    Utilities.showToastUiCentered(
                        context,
                        ERR_CODE_VPN_NOT_ACTIVE + context.getString(R.string.settings_socks5_vpn_disabled_error),
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
                        ERR_CODE_VPN_NOT_FULL + context.getString(R.string.wireguard_enabled_failure),
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
                        ERR_CODE_OTHER_WG_ACTIVE + context.getString(R.string.wireguard_enabled_failure),
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
                        ERR_CODE_VPN_NOT_ACTIVE + context.getString(R.string.settings_socks5_vpn_disabled_error),
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
