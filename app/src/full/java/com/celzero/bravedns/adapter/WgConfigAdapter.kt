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
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import backend.Backend
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
import com.celzero.bravedns.util.Utilities.delay

class WgConfigAdapter(private val context: Context) :
    PagingDataAdapter<WgConfigFiles, WgConfigAdapter.WgInterfaceViewHolder>(DIFF_CALLBACK) {

    companion object {
        private const val DELAY = 1000L
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
        return WgInterfaceViewHolder(itemBinding)
    }

    inner class WgInterfaceViewHolder(private val b: ListItemWgGeneralInterfaceBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun update(config: WgConfigFiles) {
            b.interfaceNameText.text = config.name
            b.interfaceSwitch.isChecked = config.isActive
            updateStatus(config)
            setupClickListeners(config)
        }

        private fun updateStatus(config: WgConfigFiles) {
            val id = ProxyManager.ID_WG_BASE + config.id
            val appsCount = ProxyManager.getAppCountForProxy(id)
            val statusId = VpnController.getProxyStatusById(id)
            updateUi(config, appsCount)
            updateStatusUi(config, statusId)
        }

        private fun updateUi(config: WgConfigFiles, appsCount: Int) {
            if (config.isCatchAll) {
                b.interfaceCatchAll.visibility = View.VISIBLE
                b.interfaceCatchAll.text =
                    context.getString(
                        R.string.ci_ip_label,
                        context.getString(R.string.catch_all_wg_dialog_title),
                        context.getString(R.string.dc_local_block_enabled)
                    )
            } else if (config.isLockdown) {
                b.interfaceCatchAll.visibility = View.GONE
                b.interfaceLockdown.visibility = View.VISIBLE
                b.interfaceLockdown.text =
                    context.getString(
                        R.string.ci_ip_label,
                        context.getString(R.string.firewall_rule_global_lockdown),
                        context.getString(R.string.dc_local_block_enabled)
                    )
            } else {
                b.interfaceCatchAll.visibility = View.GONE
                b.interfaceLockdown.visibility = View.GONE
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

        private fun updateStatusUi(config: WgConfigFiles, statusId: Long?) {
            if (context !is LifecycleOwner) return

            if (config.isActive) {
                b.interfaceSwitch.isChecked = true
                b.interfaceDetailCard.strokeWidth = 2
                if (statusId != null) {
                    val resId = UIUtils.getProxyStatusStringRes(statusId)
                    // change the color based on the status
                    if (statusId == Backend.TOK) {
                        b.interfaceDetailCard.strokeColor =
                            UIUtils.fetchColor(context, R.attr.chipTextPositive)
                    } else if (statusId == Backend.TUP) {
                        b.interfaceDetailCard.strokeColor =
                            UIUtils.fetchColor(context, R.attr.chipTextNeutral)
                    } else {
                        b.interfaceDetailCard.strokeColor =
                            UIUtils.fetchColor(context, R.attr.chipTextNegative)
                    }
                    b.interfaceStatus.text =
                        context.getString(
                            R.string.ci_ip_label,
                            context.getString(R.string.lbl_status),
                            context.getString(resId).replaceFirstChar(Char::titlecase)
                        )
                } else {
                    b.interfaceDetailCard.strokeColor =
                        UIUtils.fetchColor(context, R.attr.chipBgColorNegative)
                    b.interfaceStatus.text =
                        context.getString(
                            R.string.ci_ip_label,
                            context.getString(R.string.lbl_status),
                            context
                                .getString(R.string.status_failing)
                                .replaceFirstChar(Char::titlecase)
                        )
                }
            } else {
                b.interfaceDetailCard.strokeColor = UIUtils.fetchColor(context, R.attr.background)
                b.interfaceDetailCard.strokeWidth = 0
                b.interfaceSwitch.isChecked = false
                b.interfaceStatus.text =
                    context.getString(
                        R.string.ci_ip_label,
                        context.getString(R.string.lbl_status),
                        context.getString(R.string.lbl_disabled).replaceFirstChar(Char::titlecase)
                    )
            }
        }

        fun setupClickListeners(config: WgConfigFiles) {
            b.interfaceDetailCard.setOnClickListener { launchConfigDetail(config.id) }

            b.interfaceSwitch.setOnCheckedChangeListener(null)
            b.interfaceSwitch.setOnClickListener {
                val scope = (context as LifecycleOwner).lifecycleScope
                if (b.interfaceSwitch.isChecked) {
                    if (WireguardManager.canEnableConfig(config)) {
                        WireguardManager.enableConfig(config)
                        // update the status after 1 second
                        delay(DELAY, scope) { updateStatus(config) }
                    } else {
                        Utilities.showToastUiCentered(
                            context,
                            context.getString(R.string.wireguard_enabled_failure),
                            Toast.LENGTH_LONG
                        )
                        b.interfaceSwitch.isChecked = false
                    }
                } else {
                    if (WireguardManager.canDisableConfig(config)) {
                        WireguardManager.disableConfig(config)
                        // update the status after 1 second
                        delay(DELAY, scope) { updateStatus(config) }
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
}
