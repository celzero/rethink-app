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
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OneWgConfigAdapter(private val context: Context) :
    PagingDataAdapter<WgConfigFiles, OneWgConfigAdapter.WgInterfaceViewHolder>(DIFF_CALLBACK) {

    companion object {

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
        return WgInterfaceViewHolder(itemBinding)
    }

    inner class WgInterfaceViewHolder(private val b: ListItemWgOneInterfaceBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun update(config: WgConfigFiles) {
            b.interfaceNameText.text = config.name
            b.oneWgCheck.isChecked = config.isActive
            updateStatus(config)
            setupClickListeners(config)
        }

        private fun updateStatus(config: WgConfigFiles) {
            val id = ProxyManager.ID_WG_BASE + config.id
            val apps = ProxyManager.getAppCountForProxy(id).toString()
            val statusId = VpnController.getProxyStatusById(id)
            if (statusId == null) {
                WireguardManager.disableConfig(config)
            }
            updateStatusUi(config, statusId, apps)
        }

        private fun handleSwitchClick(config: WgConfigFiles) {
            val id = ProxyManager.ID_WG_BASE + config.id
            val apps = ProxyManager.getAppCountForProxy(id).toString()
            val statusId = VpnController.getProxyStatusById(id)
            updateStatusUi(config, statusId, apps)
        }

        private fun updateStatusUi(config: WgConfigFiles, statusId: Long?, apps: String) {
            val appsCount = context.getString(R.string.firewall_card_status_active, apps)
            if (config.isActive) {
                b.interfaceDetailCard.strokeColor = fetchColor(context, R.color.accentGood)
                b.interfaceDetailCard.strokeWidth = 2
                b.oneWgCheck.isChecked = true

                if (statusId != null) {
                    val resId = UIUtils.getProxyStatusStringRes(statusId)
                    b.interfaceStatus.text =
                        context.getString(
                            R.string.about_version_install_source,
                            context.getString(resId).replaceFirstChar(Char::titlecase),
                            appsCount
                        )
                } else {
                    b.interfaceStatus.text =
                        context.getString(
                            R.string.about_version_install_source,
                            context
                                .getString(R.string.status_failing)
                                .replaceFirstChar(Char::titlecase),
                            appsCount
                        )
                }
            } else {
                b.interfaceDetailCard.strokeWidth = 0
                b.oneWgCheck.isChecked = false
                b.interfaceStatus.text =
                    context.getString(
                        R.string.about_version_install_source,
                        context.getString(R.string.lbl_disabled).replaceFirstChar(Char::titlecase),
                        appsCount
                    )
            }
        }

        fun setupClickListeners(config: WgConfigFiles) {
            b.interfaceDetailCard.setOnClickListener { launchConfigDetail(config.id) }

            // b.oneWgCheck.setOnCheckedChangeListener(null)
            b.oneWgCheck.setOnClickListener {
                val isChecked = b.oneWgCheck.isChecked
                Log.d(
                    "OneWgConfigAdapter",
                    "Switch checked: $isChecked, ${b.oneWgCheck.isChecked} W: ${WireguardManager.canEnableConfig(config)}"
                )
                io {
                    if (isChecked) {
                        if (WireguardManager.canEnableConfig(config)) {
                            config.oneWireGuard = true
                            WireguardManager.updateOneWireGuardConfig(config.id, owg = true)
                            WireguardManager.enableConfig(config)
                            uiCtx { handleSwitchClick(config) }
                        } else {
                            uiCtx {
                                b.oneWgCheck.isChecked = false
                                Toast.makeText(
                                        context,
                                        context.getString(R.string.wireguard_enabled_failure),
                                        Toast.LENGTH_LONG
                                    )
                                    .show()
                            }
                        }
                    } else {
                        config.oneWireGuard = false
                        b.oneWgCheck.isChecked = false
                        WireguardManager.updateOneWireGuardConfig(config.id, owg = false)
                        WireguardManager.disableConfig(config)
                        uiCtx { handleSwitchClick(config) }
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

    private fun io(f: suspend () -> Unit) {
        (context as LifecycleOwner).lifecycleScope.launch { withContext(Dispatchers.IO) { f() } }
    }
}
