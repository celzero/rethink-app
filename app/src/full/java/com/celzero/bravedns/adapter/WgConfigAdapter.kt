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
import backend.Backend
import backend.Stats
import com.celzero.bravedns.R
import com.celzero.bravedns.database.WgConfigFiles
import com.celzero.bravedns.databinding.ListItemWgGeneralInterfaceBinding
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.ui.activity.WgConfigDetailActivity
import com.celzero.bravedns.ui.activity.WgConfigEditorActivity.Companion.INTENT_EXTRA_WG_ID
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class WgConfigAdapter(private val context: Context) :
    PagingDataAdapter<WgConfigFiles, WgConfigAdapter.WgInterfaceViewHolder>(DIFF_CALLBACK) {

    private var configs: ConcurrentHashMap<Int, Job> = ConcurrentHashMap()
    private var lifecycleOwner: LifecycleOwner? = null

    companion object {
        private const val ONE_SEC_MS = 1500L
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
                        oldConnection.isCatchAll == newConnection.isCatchAll &&
                        oldConnection.isLockdown == newConnection.isLockdown)
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
        lifecycleOwner = parent.findViewTreeLifecycleOwner()
        return WgInterfaceViewHolder(itemBinding)
    }

    override fun onViewDetachedFromWindow(holder: WgInterfaceViewHolder) {
        super.onViewDetachedFromWindow(holder)
        configs.values.forEach { it.cancel() }
        configs.clear()
    }

    inner class WgInterfaceViewHolder(private val b: ListItemWgGeneralInterfaceBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun update(config: WgConfigFiles) {
            b.interfaceNameText.text = config.name
            b.interfaceSwitch.isChecked = config.isActive
            setupClickListeners(config)
            updateStatusJob(config)
        }

        private fun updateStatusJob(config: WgConfigFiles) {
            if (config.isActive) {
                val job = updateProxyStatusContinuously(config)
                if (job != null) {
                    // cancel the job if it already exists for the same config
                    cancelJobIfAny(config.id)
                    configs[config.id] = job
                }
            } else {
                disableInactiveConfig(config)
            }
        }

        private fun disableInactiveConfig(config: WgConfigFiles) {
            // if lockdown is enabled, then show the lockdown card even if config is disabled
            if (config.isLockdown) {
                b.protocolInfoChipGroup.visibility = View.GONE
                b.interfaceActiveLayout.visibility = View.GONE
                b.interfaceConfigStatus.text =
                    context.getString(R.string.lbl_disabled).replaceFirstChar(Char::titlecase)
                val id = ProxyManager.ID_WG_BASE + config.id
                val appsCount = ProxyManager.getAppCountForProxy(id)
                updateUi(config, appsCount)
            } else {
                b.interfaceStatus.visibility = View.GONE
                b.interfaceAppsCount.visibility = View.GONE
                b.interfaceActiveLayout.visibility = View.GONE
                b.interfaceDetailCard.strokeColor = UIUtils.fetchColor(context, R.attr.background)
                b.interfaceDetailCard.strokeWidth = 0
                b.interfaceSwitch.isChecked = false
                b.protocolInfoChipGroup.visibility = View.GONE
                b.interfaceConfigStatus.visibility = View.VISIBLE
                b.interfaceConfigStatus.text =
                    context.getString(R.string.lbl_disabled).replaceFirstChar(Char::titlecase)
            }
            // cancel the job if it already exists for the config, as the config is disabled
            cancelJobIfAny(config.id)
        }

        private fun updateProxyStatusContinuously(config: WgConfigFiles): Job? {
            return ui {
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

        private fun cancelJobIfAny(id: Int) {
            val job = configs[id]
            job?.cancel()
            configs.remove(id)
        }

        private fun cancelAllJobs() {
            configs.values.forEach { it.cancel() }
            configs.clear()
        }

        private fun updateStatus(config: WgConfigFiles) {
            val id = ProxyManager.ID_WG_BASE + config.id
            val appsCount = ProxyManager.getAppCountForProxy(id)
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

            // if the view is not active then cancel the job
            if (
                lifecycleOwner != null &&
                    lifecycleOwner
                        ?.lifecycle
                        ?.currentState
                        ?.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) == false
            ) {
                cancelAllJobs()
                return
            }
            updateStatusUi(config, statusId, stats)
            updateUi(config, appsCount)
            updateProtocolChip(pair)
            updateSplitTunnelChip(isSplitTunnel)
        }

        private fun updateUi(config: WgConfigFiles, appsCount: Int) {
            b.interfaceAppsCount.visibility = View.VISIBLE
            if (config.isCatchAll) {
                b.interfaceConfigStatus.visibility = View.VISIBLE
                b.interfaceAppsCount.text = context.getString(R.string.routing_remaining_apps)
                b.interfaceAppsCount.setTextColor(
                    UIUtils.fetchColor(context, R.attr.primaryLightColorText)
                )
                b.interfaceConfigStatus.text = context.getString(R.string.catch_all_wg_dialog_title)
                return // no need to update the apps count
            } else if (config.isLockdown) {
                if (!config.isActive) {
                    b.interfaceDetailCard.strokeWidth = 2
                    b.interfaceDetailCard.strokeColor =
                        UIUtils.fetchColor(context, R.attr.accentBad)
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
                b.interfaceAppsCount.setTextColor(UIUtils.fetchColor(context, R.attr.accentBad))
            } else {
                b.interfaceAppsCount.setTextColor(
                    UIUtils.fetchColor(context, R.attr.primaryLightColorText)
                )
            }
        }

        private fun updateStatusUi(config: WgConfigFiles, statusId: Long?, stats: Stats?) {
            if (config.isActive) {
                b.interfaceSwitch.isChecked = true
                b.interfaceDetailCard.strokeWidth = 2
                b.interfaceStatus.visibility = View.VISIBLE
                b.interfaceConfigStatus.visibility = View.VISIBLE
                var status: String
                b.interfaceActiveLayout.visibility = View.VISIBLE
                val time = getUpTime(stats)
                val rxtx = getRxTx(stats)
                val handShakeTime = getHandshakeTime(stats)
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
                if (statusId != null) {
                    var resId = UIUtils.getProxyStatusStringRes(statusId)
                    // change the color based on the status
                    if (statusId == Backend.TOK) {
                        // if the lastOK is 0, then the handshake is not yet completed
                        // so show the status as waiting
                        if (stats?.lastOK == 0L) {
                            b.interfaceDetailCard.strokeColor =
                                UIUtils.fetchColor(context, R.attr.chipTextNeutral)
                            resId = R.string.status_waiting
                        } else {
                            b.interfaceDetailCard.strokeColor =
                                UIUtils.fetchColor(context, R.attr.accentGood)
                        }
                    } else if (
                        statusId == Backend.TUP ||
                            statusId == Backend.TZZ ||
                            statusId == Backend.TNT
                    ) {
                        b.interfaceDetailCard.strokeColor =
                            UIUtils.fetchColor(context, R.attr.chipTextNeutral)
                    } else {
                        b.interfaceDetailCard.strokeColor =
                            UIUtils.fetchColor(context, R.attr.accentBad)
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
                    b.interfaceDetailCard.strokeColor =
                        UIUtils.fetchColor(context, R.attr.accentBad)
                    status =
                        context.getString(R.string.status_waiting).replaceFirstChar(Char::titlecase)
                    b.interfaceActiveLayout.visibility = View.GONE
                }
                b.interfaceStatus.text = status
            } else {
                b.interfaceActiveLayout.visibility = View.GONE
                b.interfaceDetailCard.strokeColor = UIUtils.fetchColor(context, R.attr.background)
                b.interfaceDetailCard.strokeWidth = 0
                b.interfaceSwitch.isChecked = false
                b.interfaceStatus.visibility = View.GONE
                b.interfaceAppsCount.visibility = View.GONE
                b.interfaceConfigStatus.visibility = View.VISIBLE
                b.interfaceConfigStatus.text =
                    context.getString(R.string.lbl_disabled).replaceFirstChar(Char::titlecase)
            }
        }

        private fun getRxTx(stats: Stats?): String {
            if (stats == null) return ""
            val rx =
                context.getString(
                    R.string.symbol_upload,
                    Utilities.humanReadableByteCount(stats.rx, true)
                )
            val tx =
                context.getString(
                    R.string.symbol_download,
                    Utilities.humanReadableByteCount(stats.tx, true)
                )
            return context.getString(R.string.two_argument_space, rx, tx)
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

            b.interfaceSwitch.setOnCheckedChangeListener(null)
            b.interfaceSwitch.setOnClickListener {
                val cfg = config.toImmutable()
                if (b.interfaceSwitch.isChecked) {
                    if (WireguardManager.canEnableConfig(cfg)) {
                        WireguardManager.enableConfig(cfg)
                    } else {
                        Utilities.showToastUiCentered(
                            context,
                            context.getString(R.string.wireguard_enabled_failure),
                            Toast.LENGTH_LONG
                        )
                        b.interfaceSwitch.isChecked = false
                    }
                } else {
                    if (WireguardManager.canDisableConfig(cfg)) {
                        WireguardManager.disableConfig(cfg)
                    } else {
                        Utilities.showToastUiCentered(
                            context,
                            context.getString(R.string.wireguard_disable_failure),
                            Toast.LENGTH_LONG
                        )
                        b.interfaceSwitch.isChecked = true
                    }
                }
            }
        }

        private fun launchConfigDetail(id: Int) {
            val intent = Intent(context, WgConfigDetailActivity::class.java)
            intent.putExtra(INTENT_EXTRA_WG_ID, id)
            intent.putExtra(
                WgConfigDetailActivity.INTENT_EXTRA_WG_TYPE,
                WgConfigDetailActivity.WgType.DEFAULT.value
            )
            context.startActivity(intent)
        }
    }

    private fun ui(f: suspend () -> Unit): Job? {
        if (lifecycleOwner == null) {
            return null
        }
        return lifecycleOwner?.lifecycleScope?.launch(Dispatchers.Main) { f() }
    }
}
