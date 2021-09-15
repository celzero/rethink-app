/*
Copyright 2020 RethinkDNS and its authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.celzero.bravedns.adapter

import android.content.Context
import android.content.DialogInterface
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.paging.PagedListAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.databinding.ExcludedAppListItemBinding
import com.celzero.bravedns.glide.GlideApp
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_FIREWALL
import com.celzero.bravedns.util.Utilities.Companion.getDefaultIcon
import com.celzero.bravedns.util.Utilities.Companion.getIcon

class ExcludedAppListAdapter(private val context: Context) :
        PagedListAdapter<AppInfo, ExcludedAppListAdapter.ExcludedAppInfoViewHolder>(DIFF_CALLBACK) {

    companion object {

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<AppInfo>() {

            override fun areItemsTheSame(oldConnection: AppInfo, newConnection: AppInfo): Boolean {
                return (oldConnection.packageInfo == newConnection.packageInfo && oldConnection.isExcluded == newConnection.isExcluded)
            }

            override fun areContentsTheSame(oldConnection: AppInfo,
                                            newConnection: AppInfo): Boolean {
                return (oldConnection.packageInfo == newConnection.packageInfo && oldConnection.isExcluded != newConnection.isExcluded)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ExcludedAppInfoViewHolder {
        val itemBinding = ExcludedAppListItemBinding.inflate(LayoutInflater.from(parent.context),
                                                             parent, false)
        return ExcludedAppInfoViewHolder(itemBinding)
    }

    override fun onBindViewHolder(holder: ExcludedAppInfoViewHolder, position: Int) {
        val appInfo: AppInfo = getItem(position) ?: return
        holder.update(appInfo)
    }

    inner class ExcludedAppInfoViewHolder(private val b: ExcludedAppListItemBinding) :
            RecyclerView.ViewHolder(b.root) {

        fun update(appInfo: AppInfo) {
            b.excludedAppListApkLabelTv.text = appInfo.appName
            b.excludedAppListCheckbox.isChecked = appInfo.isExcluded
            displayIcon(getIcon(context, appInfo.packageInfo, appInfo.appName))
            setupClickListeners(appInfo)
        }

        private fun setupClickListeners(appInfo: AppInfo) {
            b.excludedAppListContainer.setOnClickListener {
                appInfo.isExcluded = !appInfo.isExcluded
                Log.i(LOG_TAG_FIREWALL, "is app excluded- ${appInfo.appName},${appInfo.isExcluded}")
                excludeAppsFromVpn(appInfo)
            }

            b.excludedAppListCheckbox.setOnCheckedChangeListener(null)
            b.excludedAppListCheckbox.setOnClickListener {
                appInfo.isExcluded = !appInfo.isExcluded
                Log.i(LOG_TAG_FIREWALL,
                      "is app excluded - ${appInfo.appName},${appInfo.isExcluded}")
                excludeAppsFromVpn(appInfo)
            }
        }

        private fun displayIcon(drawable: Drawable?) {
            GlideApp.with(context).load(drawable).error(getDefaultIcon(context)).into(
                b.excludedAppListApkIconIv)
        }

        private fun excludeAppsFromVpn(appInfo: AppInfo) {
            val appUidList = FirewallManager.getAppNamesByUid(appInfo.uid)

            if (appUidList.size > 1) {
                showDialog(appUidList, appInfo)
            } else {
                b.excludedAppListCheckbox.isChecked = appInfo.isExcluded
                FirewallManager.updateExcludedApps(appInfo, appInfo.isExcluded)
            }

            Log.i(LOG_TAG_FIREWALL,
                  "App ${appInfo.appName} excluded from vpn? - ${appInfo.isExcluded}")
        }

        private fun showDialog(packageList: List<String>, appInfo: AppInfo) {
            val positiveTxt: String

            val builderSingle: AlertDialog.Builder = AlertDialog.Builder(context)

            builderSingle.setIcon(R.drawable.ic_exclude_app)
            positiveTxt = if (appInfo.isExcluded) {
                builderSingle.setTitle(context.getString(R.string.exclude_app_desc, appInfo.appName,
                                                         packageList.size.toString()))
                context.getString(R.string.exclude_app_dialog_positive, packageList.size.toString())
            } else {
                builderSingle.setTitle(
                    context.getString(R.string.unexclude_app_desc, appInfo.appName,
                                      packageList.size.toString()))
                context.getString(R.string.unexclude_app_dialog_positive,
                                  packageList.size.toString())
            }
            val arrayAdapter = ArrayAdapter<String>(context,
                                                    android.R.layout.simple_list_item_activated_1)
            arrayAdapter.addAll(packageList)
            builderSingle.setCancelable(false)

            builderSingle.setItems(packageList.toTypedArray(), null)

            builderSingle.setPositiveButton(positiveTxt) { _: DialogInterface, _: Int ->
                FirewallManager.updateExcludedApps(appInfo, appInfo.isExcluded)
            }.setNeutralButton(context.getString(
                R.string.ctbs_dialog_negative_btn)) { _: DialogInterface, _: Int -> }

            val alertDialog: AlertDialog = builderSingle.show()
            alertDialog.listView.setOnItemClickListener { _, _, _, _ -> }
            alertDialog.setCancelable(false)

        }
    }
}
