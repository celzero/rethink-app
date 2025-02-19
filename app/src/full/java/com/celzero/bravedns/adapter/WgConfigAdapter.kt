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
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import backend.RouterStats
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.OneWgConfigAdapter.DnsStatusListener
import com.celzero.bravedns.database.WgConfigFiles
import com.celzero.bravedns.database.WgConfigFilesImmutable
import com.celzero.bravedns.databinding.ListItemWgGeneralInterfaceBinding
import com.celzero.bravedns.net.doh.Transaction
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.service.WireguardManager.ERR_CODE_OTHER_WG_ACTIVE
import com.celzero.bravedns.service.WireguardManager.ERR_CODE_VPN_NOT_ACTIVE
import com.celzero.bravedns.service.WireguardManager.ERR_CODE_VPN_NOT_FULL
import com.celzero.bravedns.service.WireguardManager.ERR_CODE_WG_INVALID
import com.celzero.bravedns.service.WireguardManager.WG_HANDSHAKE_TIMEOUT
import com.celzero.bravedns.service.WireguardManager.WG_UPTIME_THRESHOLD
import com.celzero.bravedns.ui.activity.WgConfigDetailActivity
import com.celzero.bravedns.ui.activity.WgConfigEditorActivity.Companion.INTENT_EXTRA_WG_ID
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.fetchColor
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.wireguard.WgInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WgConfigAdapter(private val context: Context, private val listener: DnsStatusListener, private val splitDns: Boolean) :
    PagingDataAdapter<WgConfigFiles, WgConfigAdapter.WgInterfaceViewHolder>(DIFF_CALLBACK) {

    private var lifecycleOwner: LifecycleOwner? = null

    companion object {
        private const val ONE_SEC_MS = 1500L
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
        val item = getItem(position)
        val wgConfigFiles: WgConfigFiles = item ?: return
        holder.update(wgConfigFiles)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WgInterfaceViewHolder {
        val itemBinding =
            ListItemWgGeneralInterfaceBinding.inflate(
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

    inner class WgInterfaceViewHolder(private val b: ListItemWgGeneralInterfaceBinding) :
        RecyclerView.ViewHolder(b.root) {
        private var job: Job? = null

        fun update(config: WgConfigFiles) {
            b.interfaceNameText.text = config.name.take(12)
            b.interfaceIdText.text = context.getString(R.string.single_argument_parenthesis, config.id.toString())
            b.interfaceSwitch.isChecked = config.isActive && VpnController.hasTunnel()
            setupClickListeners(config)
            updateStatusJob(config)
        }

        private fun updateStatusJob(config: WgConfigFiles) {
            if (config.isActive && VpnController.hasTunnel()) {
                job = updateProxyStatusContinuously(config)
            } else {
                cancelJobIfAny()
                disableInactiveConfig(config)
            }
        }

        private fun disableInactiveConfig(config: WgConfigFiles) {
            // if lockdown is enabled, then show the lockdown card even if config is disabled
            if (config.isLockdown) {
                b.protocolInfoChipGroup.visibility = View.GONE
                b.interfaceActiveLayout.visibility = View.GONE
                b.interfaceStatus.visibility = View.GONE
                val id = ProxyManager.ID_WG_BASE + config.id
                val appsCount = ProxyManager.getAppCountForProxy(id)
                updateUi(config, appsCount)
            } else {
                b.interfaceConfigStatus.visibility = View.GONE
                b.interfaceAppsCount.visibility = View.GONE
                b.interfaceActiveLayout.visibility = View.GONE
                b.interfaceDetailCard.strokeColor = fetchColor(context, R.attr.background)
                b.interfaceDetailCard.strokeWidth = 0
                b.interfaceSwitch.isChecked = false
                b.protocolInfoChipGroup.visibility = View.GONE
                b.interfaceStatus.visibility = View.VISIBLE
                b.interfaceStatus.text =
                    context.getString(R.string.lbl_disabled).replaceFirstChar(Char::titlecase)
            }
        }

        private fun updateProxyStatusContinuously(config: WgConfigFiles): Job? {
            return io {
                while (true) {
                    updateStatus(config)
                    delay(ONE_SEC_MS)
                }
            }
        }

        private fun updateProtocolChip(pair: Pair<Boolean, Boolean>?) {
            if (pair == null) return

            if (!pair.first && !pair.second) {
                b.protocolInfoChipGroup.visibility = View.GONE
                b.protocolInfoChipIpv4.visibility = View.GONE
                b.protocolInfoChipIpv6.visibility = View.GONE
                return
            }
            b.protocolInfoChipGroup.visibility = View.VISIBLE
            b.protocolInfoChipIpv4.visibility = View.GONE
            b.protocolInfoChipIpv6.visibility = View.GONE
            if (pair.first) {
                b.protocolInfoChipIpv4.visibility = View.VISIBLE
                b.protocolInfoChipIpv4.text = context.getString(R.string.settings_ip_text_ipv4)
            } else {
                b.protocolInfoChipIpv4.visibility = View.GONE
            }
            if (pair.second) {
                b.protocolInfoChipIpv6.visibility = View.VISIBLE
                b.protocolInfoChipIpv6.text = context.getString(R.string.settings_ip_text_ipv6)
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

        fun cancelJobIfAny() {
            if (job?.isActive == true) {
                job?.cancel()
            }
        }

        private suspend fun updateStatus(config: WgConfigFiles) {
            val id = ProxyManager.ID_WG_BASE + config.id
            val appsCount = ProxyManager.getAppCountForProxy(id)
            val statusId = VpnController.getProxyStatusById(id)
            val pair = VpnController.getSupportedIpVersion(id)
            val c = WireguardManager.getConfigById(config.id)
            val stats = VpnController.getProxyStats(id)
            val dnsStatusId = if (splitDns) {
                VpnController.getDnsStatus(id)
            } else {
                null
            }
            val isSplitTunnel =
                if (c?.getPeers()?.isNotEmpty() == true) {
                    VpnController.isSplitTunnelProxy(id, pair)
                } else {
                    false
                }

            // if the view is not active then cancel the job
            if (
                lifecycleOwner != null &&
                    lifecycleOwner
                        ?.lifecycle
                        ?.currentState
                        ?.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) == false
            ) {
                cancelJobIfAny()
                return
            }
            uiCtx {
                updateStatusUi(config, statusId, dnsStatusId, stats)
                updateUi(config, appsCount)
                updateProtocolChip(pair)
                updateSplitTunnelChip(isSplitTunnel)
                updateAmneziaChip(config)
            }
        }

        private fun updateAmneziaChip(config: WgConfigFiles) {
            val c = WireguardManager.getConfigById(config.id) ?: return

            c.getInterface()?.let {
                if (isAmneziaConfig(it)) {
                    b.chipAmnezia.visibility = View.VISIBLE
                } else {
                    b.chipAmnezia.visibility = View.GONE
                }
            }
        }

        private fun isAmneziaConfig(c: WgInterface): Boolean {
            // TODO: should we add more checks here?
            // consider the config values jc, jmin, jmax, h1, h2, h3, h4, s1, s2
            return c.getJc().isPresent || c.getJmin().isPresent || c.getJmax().isPresent ||
                    c.getH1().isPresent || c.getH2().isPresent || c.getH3().isPresent ||
                    c.getH4().isPresent || c.getS1().isPresent || c.getS2().isPresent
        }

        private fun updateUi(config: WgConfigFiles, appsCount: Int) {
            b.interfaceAppsCount.visibility = View.VISIBLE
            if (config.isCatchAll) {
                b.interfaceConfigStatus.visibility = View.VISIBLE
                b.interfaceAppsCount.text = context.getString(R.string.routing_remaining_apps)
                b.interfaceAppsCount.setTextColor(
                    fetchColor(context, R.attr.primaryLightColorText)
                )
                b.interfaceConfigStatus.text = context.getString(R.string.catch_all_wg_dialog_title)
                return // no need to update the apps count
            } else if (config.isLockdown) {
                if (!config.isActive) {
                    b.interfaceDetailCard.strokeWidth = 2
                    b.interfaceDetailCard.strokeColor = fetchColor(context, R.attr.accentBad)
                }
                b.interfaceConfigStatus.visibility = View.VISIBLE
                b.interfaceConfigStatus.text =
                    context.getString(R.string.firewall_rule_global_lockdown)
            } else {
                b.interfaceConfigStatus.visibility = View.GONE
            }
            if (!config.isActive) {
                // no need to update the apps count if the config is disabled
                b.interfaceAppsCount.visibility = View.GONE
                b.interfaceActiveLayout.visibility = View.GONE
                return
            }

            b.interfaceAppsCount.text =
                context.getString(R.string.firewall_card_status_active, appsCount.toString())
            if (appsCount == 0) {
                b.interfaceAppsCount.setTextColor(fetchColor(context, R.attr.accentBad))
            } else {
                b.interfaceAppsCount.setTextColor(fetchColor(context, R.attr.primaryLightColorText))
            }
        }

        private fun updateStatusUi(config: WgConfigFiles, statusPair: Pair<Long?, String>, dnsStatusId: Long?, stats: RouterStats?) {
            if (config.isActive) {
                b.interfaceSwitch.isChecked = true
                b.interfaceDetailCard.strokeWidth = 2
                b.interfaceStatus.visibility = View.VISIBLE
                b.interfaceConfigStatus.visibility = View.VISIBLE
                b.interfaceActiveLayout.visibility = View.VISIBLE
                val time = getUpTime(stats)
                val rxtx = getRxTx(stats)
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

                if (dnsStatusId != null) {
                    // check for dns failure cases and update the UI
                    if (isDnsError(dnsStatusId)) {
                        b.interfaceDetailCard.strokeColor =
                            fetchColor(context, R.attr.chipTextNegative)
                        b.interfaceStatus.text =
                            context.getString(R.string.status_failing)
                                .replaceFirstChar(Char::titlecase)
                    } else {
                        // if dns status is not failing, then update the proxy status
                        updateProxyStatusUi(statusPair, stats)
                    }
                } else {
                    // in one wg mode, if dns status should be available, this is a fallback case
                    updateProxyStatusUi(statusPair, stats)
                }
            } else {
                b.interfaceActiveLayout.visibility = View.GONE
                b.interfaceDetailCard.strokeColor = fetchColor(context, R.attr.background)
                b.interfaceDetailCard.strokeWidth = 0
                b.interfaceSwitch.isChecked = false
                b.interfaceConfigStatus.visibility = View.GONE
                b.interfaceAppsCount.visibility = View.GONE
                b.interfaceStatus.visibility = View.VISIBLE
                b.interfaceStatus.text =
                    context.getString(R.string.lbl_disabled).replaceFirstChar(Char::titlecase)
            }
        }

        private fun getStrokeColorForStatus(status: UIUtils.ProxyStatus?, stats: RouterStats?): Int {
            return when (status) {
                UIUtils.ProxyStatus.TOK -> if (stats?.lastOK == 0L) return R.attr.chipTextNeutral else R.attr.accentGood
                UIUtils.ProxyStatus.TUP, UIUtils.ProxyStatus.TZZ -> R.attr.chipTextNeutral
                else -> R.attr.chipTextNegative // TNT, TKO, TEND
            }
        }

        private fun getStatusText(status: UIUtils.ProxyStatus?,
            handshakeTime: String? = null,
            stats: RouterStats?,
            errMsg: String? = null
        ): String {
            if (status == null) {
                val txt = if (errMsg != null) {
                    context.getString(R.string.status_waiting) + "($errMsg)"
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

        private fun getIdleStatusText(status: UIUtils.ProxyStatus?, stats: RouterStats?): String {
            if (status != UIUtils.ProxyStatus.TZZ && status != UIUtils.ProxyStatus.TNT) return ""
            if (stats == null || stats.lastOK == 0L) return ""
            if (System.currentTimeMillis() - stats.since >= WG_HANDSHAKE_TIMEOUT) return ""

            return context.getString(R.string.dns_connected).replaceFirstChar(Char::titlecase)
        }

        private fun updateProxyStatusUi(statusPair: Pair<Long?, String>, stats: RouterStats?) {
            val status = UIUtils.ProxyStatus.entries.find { it.id == statusPair.first } // Convert to enum

            val handshakeTime = getHandshakeTime(stats).toString()

            val strokeColor = getStrokeColorForStatus(status, stats)
            b.interfaceDetailCard.strokeColor = fetchColor(context, strokeColor)
            val statusText = getIdleStatusText(status, stats).ifEmpty {
                getStatusText(
                    status,
                    handshakeTime,
                    stats,
                    statusPair.second
                )
            }
            b.interfaceStatus.text = statusText
        }

        private fun isDnsError(statusId: Long?): Boolean {
            if (statusId == null) return true

            val s = Transaction.Status.fromId(statusId)
            return s == Transaction.Status.BAD_QUERY || s == Transaction.Status.BAD_RESPONSE || s == Transaction.Status.NO_RESPONSE || s == Transaction.Status.SEND_FAIL || s == Transaction.Status.CLIENT_ERROR || s == Transaction.Status.INTERNAL_ERROR || s == Transaction.Status.TRANSPORT_ERROR
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

        private fun getHandshakeTime(stats: RouterStats?): CharSequence {
            if (stats == null) {
                return ""
            }
            if (stats.lastOK <= 0L) {
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

            b.interfaceSwitch.setOnCheckedChangeListener(null)
            b.interfaceSwitch.setOnClickListener {
                val cfg = config.toImmutable()
                io {
                    if (b.interfaceSwitch.isChecked) {
                        enableWgIfPossible(cfg)
                    } else {
                        disableWgIfPossible(cfg)
                    }
                }
            }
        }

        private suspend fun disableWgIfPossible(cfg: WgConfigFilesImmutable) {
            if (!VpnController.hasTunnel()) {
                Logger.i(LOG_TAG_PROXY, "VPN not active, cannot enable WireGuard")
                uiCtx {
                    Utilities.showToastUiCentered(
                        context,
                        ERR_CODE_VPN_NOT_ACTIVE +
                            context.getString(R.string.settings_socks5_vpn_disabled_error),
                        Toast.LENGTH_LONG
                    )
                    // reset the check box
                    b.interfaceSwitch.isChecked = true
                }
                return
            }

            if (WireguardManager.canDisableConfig(cfg)) {
                WireguardManager.disableConfig(cfg)
            } else {
                uiCtx {
                    Utilities.showToastUiCentered(
                        context,
                        context.getString(R.string.wireguard_disable_failure),
                        Toast.LENGTH_LONG
                    )
                    b.interfaceSwitch.isChecked = true
                }
            }

            uiCtx { listener.onDnsStatusChanged() }
        }

        private suspend fun enableWgIfPossible(cfg: WgConfigFilesImmutable) {

            if (!VpnController.hasTunnel()) {
                Logger.i(LOG_TAG_PROXY, "VPN not active, cannot enable WireGuard")
                uiCtx {
                    Utilities.showToastUiCentered(
                        context,
                        ERR_CODE_VPN_NOT_ACTIVE +
                            context.getString(R.string.settings_socks5_vpn_disabled_error),
                        Toast.LENGTH_LONG
                    )
                    // reset the check box
                    b.interfaceSwitch.isChecked = false
                }
                return
            }

            if (!WireguardManager.canEnableProxy()) {
                Logger.i(LOG_TAG_PROXY, "not in DNS+Firewall mode, cannot enable WireGuard")
                uiCtx {
                    // reset the check box
                    b.interfaceSwitch.isChecked = false
                    Utilities.showToastUiCentered(
                        context,
                        ERR_CODE_VPN_NOT_FULL +
                            context.getString(R.string.wireguard_enabled_failure),
                        Toast.LENGTH_LONG
                    )
                }
                return
            }

            if (WireguardManager.oneWireGuardEnabled()) {
                // this should not happen, ui is disabled if one wireGuard is enabled
                Logger.w(LOG_TAG_PROXY, "one wireGuard is already enabled")
                uiCtx {
                    // reset the check box
                    b.interfaceSwitch.isChecked = false
                    Utilities.showToastUiCentered(
                        context,
                        ERR_CODE_OTHER_WG_ACTIVE +
                            context.getString(R.string.wireguard_enabled_failure),
                        Toast.LENGTH_LONG
                    )
                }
                return
            }

            if (!WireguardManager.isValidConfig(cfg.id)) {
                Logger.i(LOG_TAG_PROXY, "invalid WireGuard config")
                uiCtx {
                    // reset the check box
                    b.interfaceSwitch.isChecked = false
                    Utilities.showToastUiCentered(
                        context,
                        ERR_CODE_WG_INVALID + context.getString(R.string.wireguard_enabled_failure),
                        Toast.LENGTH_LONG
                    )
                }
                return
            }

            WireguardManager.enableConfig(cfg)
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
            intent.putExtra(
                WgConfigDetailActivity.INTENT_EXTRA_WG_TYPE,
                WgConfigDetailActivity.WgType.DEFAULT.value
            )
            context.startActivity(intent)
        }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }

    private fun io(f: suspend () -> Unit): Job? {
        if (lifecycleOwner == null) {
            return null
        }
        return lifecycleOwner?.lifecycleScope?.launch(Dispatchers.IO) { f() }
    }
}
