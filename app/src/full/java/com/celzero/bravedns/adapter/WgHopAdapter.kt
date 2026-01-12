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
import com.celzero.bravedns.service.ProxyManager.ID_WG_BASE
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.fetchColor
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.wireguard.Config
import com.celzero.bravedns.wireguard.WgHopManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WgHopAdapter(
    private val context: Context,
    private val srcId: Int,
    private val hopables: List<Config>,
    private var selectedId: Int
) : RecyclerView.Adapter<WgHopAdapter.HopViewHolder>() {

    companion object {
        private const val TAG = "HopAdapter"
        private const val HOP_TEST_DELAY_MS = 2000L // 2 seconds
    }

    private var isAttached = false

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HopViewHolder {
        val itemBinding =
            ListItemWgHopBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return HopViewHolder(itemBinding)
    }

    override fun getItemCount(): Int {
        return hopables.size
    }

    override fun onBindViewHolder(holder: HopViewHolder, position: Int) {
        holder.update(hopables[position])
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

        fun update(config: Config) {
            val mapping = WireguardManager.getConfigFilesById(config.getId()) ?: return
            b.wgHopListNameTv.text = config.getName() + " (" + config.getId() + ")"
            b.wgHopListCheckbox.isChecked = config.getId() == selectedId
            setCardStroke(config.getId() == selectedId, mapping.isActive)
            showChips(config)
            updateStatusUi(config)
            setupClickListeners(config, mapping.isActive)
        }

        private fun updateStatusUi(config: Config) {
            io {
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
                    val src = ID_WG_BASE + srcConfig.getId()
                    val hop = ID_WG_BASE + config.getId()
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

        private fun showChips(config: Config) {
            io {
                val id = ID_WG_BASE + config.getId()
                val pair = VpnController.getSupportedIpVersion(id)
                val isSplitTunnel = if (config.getPeers()?.isNotEmpty() == true) {
                        VpnController.isSplitTunnelProxy(id, pair)
                    } else {
                        false
                    }
                uiCtx {
                    updatePropertiesChip(config)
                    updateAmzChip(config)
                    updateProtocolChip(pair)
                    updateSplitTunnelChip(isSplitTunnel)
                    updateHopSrcChip(config)
                    updateHoppingChip(config)
                }
            }
        }

        private fun updatePropertiesChip(config: Config) {
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

        private fun updateAmzChip(config: Config) {
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

        private fun updateHopSrcChip(config: Config) {
            val id = ID_WG_BASE + config.getId()
            val hop = WgHopManager.getMapBySrc(id)
            if (hop.isNotEmpty()) {
                b.chipGroup.visibility = View.VISIBLE
                b.chipHopSrc.visibility = View.VISIBLE
            } else {
                b.chipHopSrc.visibility = View.GONE
            }
        }

        private fun updateHoppingChip(config: Config) {
            val id = ID_WG_BASE + config.getId()
            val hop = WgHopManager.isAlreadyHop(id)
            if (hop) {
                b.chipGroup.visibility = View.VISIBLE
                b.chipHopping.visibility = View.VISIBLE
            } else {
                b.chipHopping.visibility = View.GONE
            }
        }

        private fun setupClickListeners(config: Config, isActive: Boolean) {
            b.wgHopListCard.setOnClickListener {
                io { handleHop(config, !b.wgHopListCheckbox.isChecked, isActive) }
            }

            b.wgHopListCheckbox.setOnClickListener {
                io { handleHop(config, b.wgHopListCheckbox.isChecked, isActive) }
            }
        }

        private suspend fun handleHop(config: Config, isChecked: Boolean, isActive: Boolean) {
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
            val src = ID_WG_BASE + srcConfig.getId()
            val hop = ID_WG_BASE + config.getId()
            val currMap = WgHopManager.getMapBySrc(src)
            if (currMap.isNotEmpty()) {
                var res = false
                currMap.forEach {
                    if (it.hop != hop && it.hop.isNotEmpty()) {
                        val id = it.hop.substring(ID_WG_BASE.length).toIntOrNull() ?: return@forEach
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
            delay(HOP_TEST_DELAY_MS)
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
                    setCardStroke(isSelected = true, isActive)
                }
                notifyDataSetChanged()
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
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }

    private fun io(f: suspend () -> Unit) {
        (context as LifecycleOwner).lifecycleScope.launch { withContext(Dispatchers.IO) { f() } }
    }
}
