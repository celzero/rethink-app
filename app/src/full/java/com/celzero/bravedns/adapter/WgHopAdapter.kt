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
import com.celzero.bravedns.database.WgConfigFilesImmutable
import com.celzero.bravedns.databinding.ListItemWgHopBinding
import com.celzero.bravedns.service.ProxyManager.ID_WG_BASE
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.fetchColor
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.wireguard.Config
import com.celzero.bravedns.wireguard.WgHopManager
import com.celzero.bravedns.wireguard.WgInterface
import kotlinx.coroutines.Dispatchers
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
    }

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

    inner class HopViewHolder(private val b: ListItemWgHopBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun update(config: Config) {
            val mapping = WireguardManager.getConfigFilesById(config.getId()) ?: return
            b.wgHopListNameTv.text = config.getName() + "(" + config.getId() + ")"
            b.wgHopListCheckbox.isChecked = config.getId() == selectedId
            setCardStroke(config.getId() == selectedId, mapping.isActive)
            updateStatusUi(config)
            showChips(config)
            setupClickListeners(config, mapping.isActive)
        }

        private fun updateStatusUi(config: Config) {
            io {
                val srcConfig = WireguardManager.getConfigById(srcId)
                if (srcConfig == null) {
                    Logger.i(LOG_TAG_UI, "$TAG; source config($srcId) not found to hop")
                    uiCtx {
                        b.wgHopListDescTv.text = context.getString(R.string.lbl_inactive)
                    }
                    return@io
                }

                val src = ID_WG_BASE + srcConfig.getId()
                val via = ID_WG_BASE + config.getId()
                val testHop = VpnController.hopStatus(src, via)
                uiCtx {
                    b.wgHopListDescTv.text = if (testHop.first != null) {
                        val status = UIUtils.getProxyStatusStringRes(testHop.first)
                        context.getString(status)
                    } else {
                        testHop.second
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
                    updateAmzChip(config)
                    updateProtocolChip(pair)
                    updateSplitTunnelChip(isSplitTunnel)
                    updateHopChip(config)
                    updateViaChip(config)
                }
            }
        }

        private fun updateAmzChip(config: Config) {
            config.getInterface()?.let {
                if (it.isAmnezia()) {
                    b.chipAmnezia.visibility = View.VISIBLE
                } else {
                    b.chipAmnezia.visibility = View.GONE
                }
            }
        }

        private fun updateProtocolChip(pair: Pair<Boolean, Boolean>?) {
            if (pair == null) return

            if (!pair.first && !pair.second) {
                b.chipGroup.visibility = View.GONE
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
                b.chipSplitTunnel.visibility = View.VISIBLE
            } else {
                b.chipSplitTunnel.visibility = View.GONE
            }
        }

        private fun updateHopChip(config: Config) {
            val id = ID_WG_BASE + config.getId()
            val hop = WgHopManager.getMapBySrc(id)
            if (hop.isNotEmpty()) {
                b.chipHop.visibility = View.VISIBLE
            } else {
                b.chipHop.visibility = View.GONE
            }
        }

        private fun updateViaChip(config: Config) {
            val id = ID_WG_BASE + config.getId()
            val via = WgHopManager.isAlreadyVia(id)
            if (via) {
                b.chipVia.visibility = View.VISIBLE
            } else {
                b.chipVia.visibility = View.GONE
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

            if (srcConfig == null) {
                Logger.i(LOG_TAG_UI, "$TAG; source config($srcId) not found to hop")
                return
            }
            Logger.d(LOG_TAG_UI, "$TAG; init, hop: ${srcConfig.getId()} -> ${config.getId()}, isChecked? $isChecked")
            val src = ID_WG_BASE + srcConfig.getId()
            val via = ID_WG_BASE + config.getId()
            if (isChecked) {
                val hopTestRes = VpnController.testHop(src, via)
                if (!hopTestRes.first) {
                    b.wgHopListCheckbox.isChecked = false
                    Utilities.showToastUiCentered(context, hopTestRes.second, Toast.LENGTH_LONG)
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
                Utilities.showToastUiCentered(context, res.second, Toast.LENGTH_LONG)
                WgHopManager.printMaps()
                if (!res.first) {
                    b.wgHopListCheckbox.isChecked = false
                    setCardStroke(false, false)
                } else {
                    b.wgHopListCheckbox.isChecked = true
                    setCardStroke(true, isActive)
                }
                notifyDataSetChanged()
            }
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

    private fun ui(f: () -> Unit) {
        (context as LifecycleOwner).lifecycleScope.launch { withContext(Dispatchers.Main) { f() } }
    }

    private fun io(f: suspend () -> Unit) {
        (context as LifecycleOwner).lifecycleScope.launch { withContext(Dispatchers.IO) { f() } }
    }
}
