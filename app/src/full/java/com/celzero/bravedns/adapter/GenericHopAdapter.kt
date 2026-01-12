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
package com.celzero.bravedns.adapter

import Logger
import Logger.LOG_TAG_UI
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.ListItemWgHopBinding
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.fetchColor
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.wireguard.WgHopManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Generic adapter for hopping between different proxy types
 * Supports both WireGuard configs and RPN proxies through HopItem sealed class
 */
class GenericHopAdapter(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val srcId: Int,
    private val hopItems: List<HopItem>,
    private var selectedId: Int,
    private val onHopChanged: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<GenericHopAdapter.HopViewHolder>() {

    companion object {
        private const val TAG = "GenericHopAdapter"
    }

    private var isAttached = false

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HopViewHolder {
        val itemBinding =
            ListItemWgHopBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return HopViewHolder(itemBinding)
    }

    override fun getItemCount(): Int = hopItems.size

    override fun onBindViewHolder(holder: HopViewHolder, position: Int) {
        if (position < 0 || position >= itemCount) {
            Logger.w(LOG_TAG_UI, "$TAG; Invalid position $position for itemCount $itemCount")
            return
        }
        holder.update(hopItems[position])
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        isAttached = true
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        isAttached = false
    }

    inner class HopViewHolder(private val b: ListItemWgHopBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun update(item: HopItem) {
            when (item) {
                is HopItem.WireGuardHop -> updateWireGuardHop(item)
                is HopItem.RpnProxyHop -> updateRpnProxyHop(item)
            }
        }

        private fun updateWireGuardHop(item: HopItem.WireGuardHop) {
            val config = item.config
            // Verify config exists in manager
            if (WireguardManager.getConfigFilesById(config.getId()) == null) return

            b.wgHopListNameTv.text = "${config.getName()} (${config.getId()})"
            b.wgHopListCheckbox.isChecked = config.getId() == selectedId
            setCardStroke(config.getId() == selectedId, item.active)
            showWgChips(item)
            updateWgStatusUi(item)
            setupWgClickListeners(item)
        }

        private fun updateRpnProxyHop(item: HopItem.RpnProxyHop) {
            val countryConfig = item.countryConfig

            b.wgHopListNameTv.text = countryConfig.cc
            b.wgHopListCheckbox.isChecked = countryConfig.cc.hashCode() == selectedId
            setCardStroke(countryConfig.cc.hashCode() == selectedId, item.active)
            showRpnChips(item)
            updateRpnStatusUi(item)
            setupRpnClickListeners(item)
        }

        private fun showWgChips(item: HopItem.WireGuardHop) {
            io {
                val config = item.config
                val id = ProxyManager.ID_WG_BASE + config.getId()
                val pair = VpnController.getSupportedIpVersion(id)
                val isSplitTunnel = if (config.getPeers()?.isNotEmpty() == true) {
                    VpnController.isSplitTunnelProxy(id, pair)
                } else {
                    false
                }
                uiCtx {
                    updateWgPropertiesChip(config)
                    updateAmzChip(config)
                    updateProtocolChip(pair)
                    updateSplitTunnelChip(isSplitTunnel)
                    updateHopSrcChip(config)
                    updateHoppingChip(config)
                }
            }
        }

        private fun showRpnChips(item: HopItem.RpnProxyHop) {
            io {
                val countryConfig = item.countryConfig
                uiCtx {
                    updateRpnPropertiesChip(countryConfig)
                    // Hide WG-specific chips
                    b.chipAmnezia.visibility = View.GONE
                    b.chipIpv4.visibility = View.GONE
                    b.chipIpv6.visibility = View.GONE
                    b.chipSplitTunnel.visibility = View.GONE
                    b.chipHopSrc.visibility = View.GONE
                    b.chipHopping.visibility = View.GONE
                }
            }
        }

        private fun updateWgPropertiesChip(config: com.celzero.bravedns.wireguard.Config) {
            val mapping = WireguardManager.getConfigFilesById(config.getId()) ?: return
            if (!mapping.isCatchAll && !mapping.useOnlyOnMetered && !mapping.ssidEnabled) {
                b.chipProperties.visibility = View.GONE
                return
            }
            b.chipProperties.text = ""
            if (mapping.isCatchAll) {
                b.chipProperties.visibility = View.VISIBLE
                b.chipProperties.text = context.getString(R.string.symbol_lightening)
            }
            if (mapping.useOnlyOnMetered) {
                b.chipProperties.visibility = View.VISIBLE
                b.chipProperties.text = context.getString(
                    R.string.two_argument_space,
                    b.chipProperties.text.toString(),
                    context.getString(R.string.symbol_mobile)
                )
            }
            if (mapping.ssidEnabled) {
                b.chipProperties.visibility = View.VISIBLE
                b.chipProperties.text = context.getString(
                    R.string.two_argument_space,
                    b.chipProperties.text.toString(),
                    context.getString(R.string.symbol_id)
                )
            }

            val visible = if (b.chipProperties.text.isNotEmpty()) View.VISIBLE else View.GONE
            b.chipProperties.visibility = visible
        }

        private fun updateRpnPropertiesChip(countryConfig: com.celzero.bravedns.database.CountryConfig) {
            // Use the countryConfig directly since it already has all the properties
            if (!countryConfig.catchAll && !countryConfig.lockdown && !countryConfig.mobileOnly && !countryConfig.ssidBased) {
                b.chipProperties.visibility = View.GONE
                return
            }
            b.chipProperties.text = ""
            if (countryConfig.catchAll) {
                b.chipProperties.visibility = View.VISIBLE
                b.chipProperties.text = context.getString(R.string.symbol_lightening)
            }
            if (countryConfig.lockdown) {
                b.chipProperties.visibility = View.VISIBLE
                b.chipProperties.text = context.getString(
                    R.string.two_argument_space,
                    b.chipProperties.text.toString(),
                    context.getString(R.string.symbol_lockdown)
                )
            }
            if (countryConfig.mobileOnly) {
                b.chipProperties.visibility = View.VISIBLE
                b.chipProperties.text = context.getString(
                    R.string.two_argument_space,
                    b.chipProperties.text.toString(),
                    context.getString(R.string.symbol_mobile)
                )
            }
            if (countryConfig.ssidBased) {
                b.chipProperties.visibility = View.VISIBLE
                b.chipProperties.text = context.getString(
                    R.string.two_argument_space,
                    b.chipProperties.text.toString(),
                    context.getString(R.string.symbol_id)
                )
            }

            val visible = if (b.chipProperties.text.isNotEmpty()) View.VISIBLE else View.GONE
            b.chipProperties.visibility = visible
        }

        private fun updateAmzChip(config: com.celzero.bravedns.wireguard.Config) {
            config.getInterface()?.let {
                if (it.isAmnezia()) {
                    b.chipGroup.visibility = View.VISIBLE
                    b.chipAmnezia.visibility = View.VISIBLE
                } else {
                    b.chipAmnezia.visibility = View.GONE
                }
            }
        }

        private fun updateProtocolChip(pair: Pair<Boolean, Boolean>?) {
            if (pair == null) return

            if (!pair.first && !pair.second) {
                b.chipIpv4.visibility = View.GONE
                b.chipIpv6.visibility = View.GONE
                return
            }
            b.chipGroup.visibility = View.VISIBLE
            b.chipIpv4.visibility = View.GONE
            b.chipIpv6.visibility = View.GONE
            if (pair.first) {
                b.chipIpv4.visibility = View.VISIBLE
                b.chipIpv4.text = context.getString(R.string.settings_ip_text_ipv4)
            } else {
                b.chipIpv4.visibility = View.GONE
            }
            if (pair.second) {
                b.chipIpv6.visibility = View.VISIBLE
                b.chipIpv6.text = context.getString(R.string.settings_ip_text_ipv6)
            } else {
                b.chipIpv6.visibility = View.GONE
            }
        }

        private fun updateSplitTunnelChip(isSplitTunnel: Boolean) {
            if (isSplitTunnel) {
                b.chipGroup.visibility = View.VISIBLE
                b.chipSplitTunnel.visibility = View.VISIBLE
            } else {
                b.chipSplitTunnel.visibility = View.GONE
            }
        }

        private fun updateHopSrcChip(config: com.celzero.bravedns.wireguard.Config) {
            val id = ProxyManager.ID_WG_BASE + config.getId()
            val hop = WgHopManager.getMapBySrc(id)
            if (hop.isNotEmpty()) {
                b.chipGroup.visibility = View.VISIBLE
                b.chipHopSrc.visibility = View.VISIBLE
            } else {
                b.chipHopSrc.visibility = View.GONE
            }
        }

        private fun updateHoppingChip(config: com.celzero.bravedns.wireguard.Config) {
            val id = ProxyManager.ID_WG_BASE + config.getId()
            val hop = WgHopManager.isAlreadyHop(id)
            if (hop) {
                b.chipGroup.visibility = View.VISIBLE
                b.chipHopping.visibility = View.VISIBLE
            } else {
                b.chipHopping.visibility = View.GONE
            }
        }

        private fun updateWgStatusUi(item: HopItem.WireGuardHop) {
            io {
                val config = item.config
                val map = WireguardManager.getConfigFilesById(config.getId())
                if (map == null) {
                    uiCtx {
                        b.wgHopListDescTv.text = context.getString(R.string.config_invalid_desc)
                    }
                    return@io
                }
                if (selectedId == config.getId()) {
                    val srcConfig = WireguardManager.getConfigById(srcId)
                    if (srcConfig == null) {
                        Logger.i(LOG_TAG_UI, "$TAG; source config($srcId) not found to hop")
                        uiCtx {
                            b.wgHopListDescTv.text = context.getString(R.string.lbl_inactive)
                        }
                        return@io
                    }
                    val src = ProxyManager.ID_WG_BASE + srcConfig.getId()
                    val hop = ProxyManager.ID_WG_BASE + config.getId()
                    val statusPair = VpnController.hopStatus(src, hop)
                    uiCtx {
                        val id = statusPair.first
                        if (statusPair.first != null) {
                            val txt = UIUtils.getProxyStatusStringRes(id)
                            b.wgHopListDescTv.text = context.getString(txt)
                        } else {
                            b.wgHopListDescTv.text = statusPair.second
                        }
                    }
                    return@io
                }
                if (map.isActive) {
                    uiCtx {
                        b.wgHopListDescTv.text = context.getString(R.string.lbl_active)
                    }
                    return@io
                } else {
                    uiCtx {
                        b.wgHopListDescTv.text = context.getString(R.string.lbl_inactive)
                    }
                }
            }
        }

        private fun updateRpnStatusUi(item: HopItem.RpnProxyHop) {
            io {
                uiCtx {
                    if (item.active) {
                        b.wgHopListDescTv.text = context.getString(R.string.lbl_active)
                    } else {
                        b.wgHopListDescTv.text = context.getString(R.string.lbl_inactive)
                    }
                }
            }
        }

        private fun setupWgClickListeners(item: HopItem.WireGuardHop) {
            b.wgHopListCard.setOnClickListener {
                io { handleWgHop(item, !b.wgHopListCheckbox.isChecked) }
            }

            b.wgHopListCheckbox.setOnClickListener {
                io { handleWgHop(item, b.wgHopListCheckbox.isChecked) }
            }
        }

        private fun setupRpnClickListeners(item: HopItem.RpnProxyHop) {
            b.wgHopListCard.setOnClickListener {
                io { handleRpnHop(item, !b.wgHopListCheckbox.isChecked) }
            }

            b.wgHopListCheckbox.setOnClickListener {
                io { handleRpnHop(item, b.wgHopListCheckbox.isChecked) }
            }
        }

        private suspend fun handleWgHop(item: HopItem.WireGuardHop, isChecked: Boolean) {
            val config = item.config
            val srcConfig = WireguardManager.getConfigById(srcId)
            val mapping = WireguardManager.getConfigFilesById(config.getId())
            if (srcConfig == null || mapping == null) {
                Logger.i(LOG_TAG_UI, "$TAG; source config($srcId) not found to hop")
                uiCtx {
                    if (!isAttached) return@uiCtx
                    Utilities.showToastUiCentered(context, context.getString(R.string.config_invalid_desc), Toast.LENGTH_LONG)
                }
                return
            }

            if (mapping.useOnlyOnMetered || mapping.ssidEnabled) {
                uiCtx {
                    if (!isAttached) return@uiCtx
                    Utilities.showToastUiCentered(
                        context,
                        context.getString(R.string.hop_error_toast_msg_3),
                        Toast.LENGTH_LONG
                    )
                }
                return
            }
            uiCtx {
                showProgressIndicator()
            }
            Logger.d(LOG_TAG_UI, "$TAG; init, hop: ${srcConfig.getId()} -> ${config.getId()}, isChecked? $isChecked")
            val src = ProxyManager.ID_WG_BASE + srcConfig.getId()
            val hop = ProxyManager.ID_WG_BASE + config.getId()
            val currMap = WgHopManager.getMapBySrc(src)
            if (currMap.isNotEmpty()) {
                var res = false
                currMap.forEach {
                    if (it.hop != hop && it.hop.isNotEmpty()) {
                        val id = it.hop.substring(ProxyManager.ID_WG_BASE.length).toIntOrNull() ?: return@forEach
                        res = WgHopManager.removeHop(srcConfig.getId(), id).first
                    }
                }
                if (res) {
                    selectedId = -1
                    uiCtx {
                        if (!isAttached) return@uiCtx
                        notifyDataSetChanged()
                    }
                }
            }
            delay(2000)
            if (isChecked) {
                val hopTestRes = VpnController.testHop(src, hop)
                if (!hopTestRes.first) {
                    uiCtx {
                        if (!isAttached) return@uiCtx

                        dismissProgressIndicator()
                        b.wgHopListCheckbox.isChecked = false
                        Utilities.showToastUiCentered(
                            context,
                            hopTestRes.second ?: context.getString(R.string.unknown_error),
                            Toast.LENGTH_LONG
                        )
                    }
                    return
                }
            }

            val res = if (!isChecked) {
                selectedId = -1
                WgHopManager.removeHop(srcConfig.getId(), config.getId())
            } else {
                selectedId = config.getId()
                WgHopManager.hop(srcConfig.getId(), config.getId())
            }
            uiCtx {
                if (!isAttached) return@uiCtx

                dismissProgressIndicator()
                Utilities.showToastUiCentered(context, res.second, Toast.LENGTH_LONG)
                if (!res.first) {
                    b.wgHopListCheckbox.isChecked = false
                    setCardStroke(isSelected = false, isActive = false)
                } else {
                    b.wgHopListCheckbox.isChecked = true
                    setCardStroke(isSelected = true, item.active)
                    onHopChanged?.invoke(config.getId())
                }
                notifyDataSetChanged()
            }
        }

        private suspend fun handleRpnHop(item: HopItem.RpnProxyHop, isChecked: Boolean) {
            val countryConfig = item.countryConfig
            uiCtx {
                showProgressIndicator()
            }
            Logger.d(LOG_TAG_UI, "$TAG; RPN hop: ${countryConfig.cc}, isChecked? $isChecked")

            // TODO: Implement RPN hop logic when hop manager is ready
            delay(1000)

            uiCtx {
                if (!isAttached) return@uiCtx

                dismissProgressIndicator()
                Utilities.showToastUiCentered(
                    context,
                    "RPN hopping not yet implemented",
                    Toast.LENGTH_SHORT
                )
                b.wgHopListCheckbox.isChecked = false
            }
        }

        fun showProgressIndicator() {
            if (!isAttached) return

            b.wgHopListCheckbox.isEnabled = false
            b.wgHopListProgress.visibility = View.VISIBLE
            b.wgHopListCard.isEnabled = false
        }

        fun dismissProgressIndicator() {
            if (!isAttached) return

            b.wgHopListCheckbox.isEnabled = true
            b.wgHopListProgress.visibility = View.GONE
            b.wgHopListCard.isEnabled = true
        }

        private fun setCardStroke(isSelected: Boolean, isActive: Boolean) {
            val strokeColor = if (isSelected && isActive) {
                b.wgHopListCard.strokeWidth = 2
                fetchColor(context, R.attr.chipTextPositive)
            } else if (isSelected) { // selected but not active
                b.wgHopListCard.strokeWidth = 2
                fetchColor(context, R.attr.chipTextNegative)
            } else {
                b.wgHopListCard.strokeWidth = 0
                fetchColor(context, R.attr.chipTextNegative)
            }
            b.wgHopListCard.strokeColor = strokeColor
        }

        private fun io(f: suspend () -> Unit) {
            lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) { f() }
        }

        private suspend fun uiCtx(f: suspend () -> Unit) {
            withContext(Dispatchers.Main) { f() }
        }
    }
}

