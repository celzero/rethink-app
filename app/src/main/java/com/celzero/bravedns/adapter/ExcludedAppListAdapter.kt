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
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.databinding.ExcludedAppListItemBinding
import com.celzero.bravedns.glide.GlideApp
import com.celzero.bravedns.util.Utilities.Companion.getDefaultIcon
import com.celzero.bravedns.util.Utilities.Companion.getIcon

class ExcludedAppListAdapter(private val context: Context) :
        PagingDataAdapter<AppInfo, ExcludedAppListAdapter.ExcludedAppInfoViewHolder>(DIFF_CALLBACK) {

    companion object {

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<AppInfo>() {

            // based on the apps package info and excluded status
            override fun areItemsTheSame(oldConnection: AppInfo, newConnection: AppInfo): Boolean {
                return (oldConnection.packageInfo == newConnection.packageInfo && oldConnection.firewallStatus == newConnection.firewallStatus)
            }

            // return false, when there is difference in excluded status
            override fun areContentsTheSame(oldConnection: AppInfo,
                                            newConnection: AppInfo): Boolean {
                return (oldConnection.packageInfo == newConnection.packageInfo && oldConnection.firewallStatus != newConnection.firewallStatus)
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
            b.excludedAppListCheckbox.isChecked = FirewallManager.isUidExcluded(appInfo.uid)
            displayIcon(getIcon(context, appInfo.packageInfo, /*No app name */""))
            setupClickListeners(appInfo)
        }

        private fun setupClickListeners(appInfo: AppInfo) {
            b.excludedAppListContainer.setOnClickListener {
                b.excludedAppListCheckbox.isChecked = !b.excludedAppListCheckbox.isChecked
                handleExcludeApp(appInfo, b.excludedAppListCheckbox.isChecked)
            }

            b.excludedAppListCheckbox.setOnCheckedChangeListener(null)
            b.excludedAppListCheckbox.setOnClickListener {
                handleExcludeApp(appInfo, b.excludedAppListCheckbox.isChecked)
            }
        }

        private fun displayIcon(drawable: Drawable?) {
            GlideApp.with(context).load(drawable).error(getDefaultIcon(context)).into(
                b.excludedAppListApkIconIv)
        }

        private fun handleExcludeApp(appInfo: AppInfo, isExcluded: Boolean) {
            val appUidList = FirewallManager.getAppNamesByUid(appInfo.uid)

            if (appUidList.count() > 1) {
                showDialog(appUidList, appInfo, isExcluded)
            } else {
                FirewallManager.updateExcludedApps(appInfo, isExcluded)
            }
        }

        private fun showDialog(packageList: List<String>, appInfo: AppInfo, isExcluded: Boolean) {
            val positiveTxt: String

            val builderSingle: AlertDialog.Builder = AlertDialog.Builder(context)

            builderSingle.setIcon(R.drawable.ic_exclude_app)

            val count = packageList.count()
            positiveTxt = if (isExcluded) {
                builderSingle.setTitle(
                    context.getString(R.string.exclude_app_desc, appInfo.appName, count.toString()))
                context.getString(R.string.exclude_app_dialog_positive, count.toString())
            } else {
                builderSingle.setTitle(
                    context.getString(R.string.unexclude_app_desc, appInfo.appName,
                                      count.toString()))
                context.getString(R.string.unexclude_app_dialog_positive, count.toString())
            }
            val arrayAdapter = ArrayAdapter<String>(context,
                                                    android.R.layout.simple_list_item_activated_1)
            arrayAdapter.addAll(packageList)
            builderSingle.setCancelable(false)

            builderSingle.setItems(packageList.toTypedArray(), null)

            builderSingle.setPositiveButton(positiveTxt) { _: DialogInterface, _: Int ->
                FirewallManager.updateExcludedApps(appInfo, isExcluded)
            }.setNeutralButton(context.getString(
                R.string.ctbs_dialog_negative_btn)) { _: DialogInterface, _: Int -> }

            val alertDialog: AlertDialog = builderSingle.show()
            alertDialog.listView.setOnItemClickListener { _, _, _, _ -> }
            alertDialog.setCancelable(false)

        }
    }
}
