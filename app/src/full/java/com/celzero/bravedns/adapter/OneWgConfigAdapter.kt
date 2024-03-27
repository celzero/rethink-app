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
import com.celzero.bravedns.R
import com.celzero.bravedns.database.WgConfigFiles
import com.celzero.bravedns.databinding.ListItemWgOneInterfaceBinding
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.WireguardManager
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
        private const val ONE_SEC = 1000L

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
        lifecycleOwner = parent.findViewTreeLifecycleOwner()
        return WgInterfaceViewHolder(itemBinding)
    }

    inner class WgInterfaceViewHolder(private val b: ListItemWgOneInterfaceBinding) :
        RecyclerView.ViewHolder(b.root) {
        private var statusCheckJob: Job? = null

        fun update(config: WgConfigFiles) {
            b.interfaceNameText.text = config.name
            b.oneWgCheck.isChecked = config.isActive
            updateStatus(config)
            setupClickListeners(config)
            if (config.oneWireGuard) {
                keepStatusUpdated(config)
            } else {
                b.interfaceDetailCard.strokeWidth = 0
                b.interfaceAppsCount.visibility = View.GONE
                b.protocolInfoChipGroup.visibility = View.GONE
                b.oneWgCheck.isChecked = false
                b.interfaceStatus.text =
                    context.getString(R.string.lbl_disabled).replaceFirstChar(Char::titlecase)
            }
        }

        private fun keepStatusUpdated(config: WgConfigFiles) {
            statusCheckJob = ui {
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

        private fun updateStatus(config: WgConfigFiles) {
            // if the view is not active then cancel the job
            if (
                lifecycleOwner
                    ?.lifecycle
                    ?.currentState
                    ?.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) == false
            ) {
                statusCheckJob?.cancel()
                return
            }

            val id = ProxyManager.ID_WG_BASE + config.id
            val statusId = VpnController.getProxyStatusById(id)
            val pair = VpnController.getSupportedIpVersion(id)
            val c = WireguardManager.getConfigById(config.id)
            val isSplitTunnel =
                if (c?.getPeers()?.isNotEmpty() == true) {
                    VpnController.isSplitTunnelProxy(id, pair)
                } else {
                    false
                }
            updateStatusUi(config, statusId)
            updateProtocolChip(pair)
            updateSplitTunnelChip(isSplitTunnel)
        }

        private fun updateStatusUi(config: WgConfigFiles, statusId: Long?) {
            if (config.isActive) {
                b.interfaceDetailCard.strokeColor = fetchColor(context, R.color.accentGood)
                b.interfaceDetailCard.strokeWidth = 2
                b.oneWgCheck.isChecked = true
                b.interfaceAppsCount.visibility = View.VISIBLE
                b.interfaceAppsCount.text = context.getString(R.string.one_wg_apps_added)
                if (statusId != null) {
                    val resId = UIUtils.getProxyStatusStringRes(statusId)
                    // change the color based on the status
                    if (statusId == Backend.TOK) {
                        b.interfaceDetailCard.strokeColor =
                            fetchColor(context, R.attr.chipTextPositive)
                        // cancel the job, as the status is connected
                        statusCheckJob?.cancel()
                    } else if (statusId == Backend.TUP || statusId == Backend.TZZ) {
                        b.interfaceDetailCard.strokeColor =
                            fetchColor(context, R.attr.chipTextNeutral)
                    } else {
                        b.interfaceDetailCard.strokeColor =
                            fetchColor(context, R.attr.chipTextNegative)
                    }
                    b.interfaceStatus.text =
                        context.getString(resId).replaceFirstChar(Char::titlecase)
                } else {
                    b.interfaceDetailCard.strokeColor = fetchColor(context, R.attr.chipTextNegative)
                    b.interfaceDetailCard.strokeWidth = 2
                    b.interfaceStatus.text =
                        context.getString(R.string.status_waiting).replaceFirstChar(Char::titlecase)
                }
            } else {
                b.interfaceDetailCard.strokeWidth = 0
                b.interfaceAppsCount.visibility = View.GONE
                b.oneWgCheck.isChecked = false
                b.interfaceStatus.text =
                    context.getString(R.string.lbl_disabled).replaceFirstChar(Char::titlecase)
            }
        }

        fun setupClickListeners(config: WgConfigFiles) {
            b.interfaceDetailCard.setOnClickListener { launchConfigDetail(config.id) }

            b.oneWgCheck.setOnClickListener {
                val isChecked = b.oneWgCheck.isChecked
                io {
                    if (isChecked) {
                        if (WireguardManager.canEnableConfig(config)) {
                            config.oneWireGuard = true
                            WireguardManager.updateOneWireGuardConfig(config.id, owg = true)
                            WireguardManager.enableConfig(config)
                            uiCtx { listener.onDnsStatusChanged() }
                        } else {
                            uiCtx {
                                b.oneWgCheck.isChecked = false
                                Utilities.showToastUiCentered(
                                    context,
                                    context.getString(R.string.wireguard_enabled_failure),
                                    Toast.LENGTH_LONG
                                )
                            }
                        }
                    } else {
                        config.oneWireGuard = false
                        b.oneWgCheck.isChecked = false
                        WireguardManager.updateOneWireGuardConfig(config.id, owg = false)
                        WireguardManager.disableConfig(config)
                        uiCtx { listener.onDnsStatusChanged() }
                    }
                }
            }
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

    private fun ui(f: suspend () -> Unit): Job? {
        return lifecycleOwner?.lifecycleScope?.launch(Dispatchers.Main) { f() }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleOwner?.lifecycleScope?.launch(Dispatchers.IO) { f() }
    }
}
