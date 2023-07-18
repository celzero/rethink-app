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
import android.content.DialogInterface
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.database.WgApplicationMapping
import com.celzero.bravedns.databinding.ListItemWgIncludeAppsBinding
import com.celzero.bravedns.glide.GlideApp
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_WIREGUARD
import com.celzero.bravedns.util.Utilities.getDefaultIcon
import com.celzero.bravedns.util.Utilities.getIcon

class WgIncludeAppsAdapter(private val context: Context, private val interfaceId: Int) :
    PagingDataAdapter<WgApplicationMapping, WgIncludeAppsAdapter.IncludedAppInfoViewHolder>(
        DIFF_CALLBACK
    ) {

    companion object {

        private val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<WgApplicationMapping>() {

                // based on the apps package info and excluded status
                override fun areItemsTheSame(
                    oldConnection: WgApplicationMapping,
                    newConnection: WgApplicationMapping
                ): Boolean {
                    return (oldConnection.wgInterfaceId == newConnection.wgInterfaceId &&
                        oldConnection.uid == newConnection.uid)
                }

                // return false, when there is difference in excluded status
                override fun areContentsTheSame(
                    oldConnection: WgApplicationMapping,
                    newConnection: WgApplicationMapping
                ): Boolean {
                    return (oldConnection.wgInterfaceId == newConnection.wgInterfaceId &&
                        oldConnection.uid == newConnection.uid)
                }
            }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): IncludedAppInfoViewHolder {
        val itemBinding =
            ListItemWgIncludeAppsBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return IncludedAppInfoViewHolder(itemBinding)
    }

    override fun onBindViewHolder(holder: IncludedAppInfoViewHolder, position: Int) {
        val apps: WgApplicationMapping = getItem(position) ?: return
        holder.update(apps)
    }

    inner class IncludedAppInfoViewHolder(private val b: ListItemWgIncludeAppsBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun update(mapping: WgApplicationMapping) {
            b.wgIncludeAppListApkLabelTv.text = mapping.appName
            Log.i(
                LOG_TAG_WIREGUARD,
                "update - ${mapping.appName}, ${mapping.wgInterfaceId}, $interfaceId, ${mapping.wgInterfaceName}"
            )
            if (mapping.wgInterfaceId == -1) {
                b.wgIncludeAppAppDescTv.text = ""
                b.wgIncludeAppAppDescTv.visibility = View.GONE
                b.wgIncludeAppListCheckbox.isChecked = false
            } else if (mapping.wgInterfaceId != interfaceId) {
                b.wgIncludeAppAppDescTv.text = "part of ${mapping.wgInterfaceName}"
                b.wgIncludeAppAppDescTv.visibility = View.VISIBLE
                b.wgIncludeAppListCheckbox.isChecked = false
            } else {
                b.wgIncludeAppAppDescTv.text = ""
                b.wgIncludeAppAppDescTv.visibility = View.GONE
                b.wgIncludeAppListCheckbox.isChecked = mapping.wgInterfaceId == interfaceId
            }

            val isIncluded = mapping.wgInterfaceId == interfaceId && mapping.wgInterfaceId != -1
            displayIcon(getIcon(context, mapping.packageName, mapping.appName))
            setupClickListeners(mapping, isIncluded)
        }

        private fun setupClickListeners(mapping: WgApplicationMapping, isIncluded: Boolean) {
            b.wgIncludeAppListContainer.setOnClickListener {
                Log.i(
                    LOG_TAG_WIREGUARD,
                    "wgIncludeAppListContainer- ${mapping.appName}, $isIncluded"
                )
                updateInterfaceDetails(mapping, !isIncluded)
            }

            b.wgIncludeAppListCheckbox.setOnCheckedChangeListener(null)
            b.wgIncludeAppListCheckbox.setOnClickListener {
                val isAdded = mapping.wgInterfaceId == interfaceId
                Log.i(LOG_TAG_WIREGUARD, "wgIncludeAppListCheckbox - ${mapping.appName}, $isAdded")
                updateInterfaceDetails(mapping, !isAdded)
            }
        }

        private fun displayIcon(drawable: Drawable?) {
            GlideApp.with(context)
                .load(drawable)
                .error(getDefaultIcon(context))
                .into(b.wgIncludeAppListApkIconIv)
        }

        private fun updateInterfaceDetails(mapping: WgApplicationMapping, include: Boolean) {
            val appUidList = FirewallManager.getAppNamesByUid(mapping.uid)

            if (appUidList.count() > 1) {
                showDialog(appUidList, mapping, include)
            } else {
                if (include) {
                    WireguardManager.updateConfigIdForApp(mapping.uid, interfaceId)
                    Log.i(LOG_TAG_WIREGUARD, "App ${mapping.appName} included in wg? $interfaceId")
                } else {
                    WireguardManager.updateConfigIdForApp(mapping.uid, -1)
                    Log.i(
                        LOG_TAG_WIREGUARD,
                        "App ${mapping.appName} removed from wg? prev id ${mapping.wgInterfaceId}}"
                    )
                }
            }
        }

        private fun showDialog(
            packageList: List<String>,
            mapping: WgApplicationMapping,
            isIncluded: Boolean
        ) {
            val positiveTxt: String

            val builderSingle: AlertDialog.Builder = AlertDialog.Builder(context)

            builderSingle.setIcon(R.drawable.ic_firewall_exclude_on)

            val count = packageList.count()
            positiveTxt =
                if (isIncluded) {
                    builderSingle.setTitle("Include apps - $count")
                    "Include"
                } else {
                    builderSingle.setTitle("Remove apps - $count")
                    "Remove"
                }
            val arrayAdapter =
                ArrayAdapter<String>(context, android.R.layout.simple_list_item_activated_1)
            arrayAdapter.addAll(packageList)
            builderSingle.setCancelable(false)

            builderSingle.setItems(packageList.toTypedArray(), null)

            builderSingle
                .setPositiveButton(positiveTxt) { _: DialogInterface, _: Int ->
                    if (isIncluded) {
                        WireguardManager.updateConfigIdForApp(mapping.uid, interfaceId)
                        Log.i(LOG_TAG_WIREGUARD, "Included apps: ${mapping.uid}")
                    } else {
                        WireguardManager.updateConfigIdForApp(mapping.uid, -1)
                        Log.i(LOG_TAG_WIREGUARD, "Removed apps: ${mapping.uid}")
                    }
                }
                .setNeutralButton(context.getString(R.string.ctbs_dialog_negative_btn)) {
                    _: DialogInterface,
                    _: Int ->
                }

            val alertDialog: AlertDialog = builderSingle.show()
            alertDialog.listView.setOnItemClickListener { _, _, _, _ -> }
            alertDialog.setCancelable(false)
        }
    }
}
