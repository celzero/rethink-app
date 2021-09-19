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
import androidx.appcompat.app.AlertDialog
import androidx.paging.PagedListAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.databinding.UnivWhitelistListItemBinding
import com.celzero.bravedns.glide.GlideApp
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_FIREWALL
import com.celzero.bravedns.util.Utilities.Companion.getDefaultIcon
import com.celzero.bravedns.util.Utilities.Companion.getIcon

// TODO: This class shares common functionality with ExcludeApplistAdapter.
// Consider creating an appropriate abstraction between the two classes.
class WhitelistedAppsAdapter(private val context: Context) :
        PagedListAdapter<AppInfo, WhitelistedAppsAdapter.WhitelistAppInfoViewHolder>(
            DIFF_CALLBACK) {

    companion object {

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<AppInfo>() {
            // account for both package-info and whitelist-flags when
            // determining if items-are-same. previously, whitelist-flag was
            // not part of the equation causing bugs where the ui wouldn't
            // reflect the toggles adding/removing apps to/from the whitelist
            override fun areItemsTheSame(oldConnection: AppInfo, newConnection: AppInfo): Boolean {
                return (oldConnection.packageInfo == newConnection.packageInfo && oldConnection.whiteListUniv1 == newConnection.whiteListUniv1)
            }

            override fun areContentsTheSame(oldConnection: AppInfo,
                                            newConnection: AppInfo): Boolean {
                return (oldConnection.packageInfo == newConnection.packageInfo && oldConnection.whiteListUniv1 != newConnection.whiteListUniv1)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WhitelistAppInfoViewHolder {
        val itemBinding = UnivWhitelistListItemBinding.inflate(LayoutInflater.from(parent.context),
                                                               parent, false)
        return WhitelistAppInfoViewHolder(itemBinding)
    }

    override fun onBindViewHolder(holder: WhitelistAppInfoViewHolder, position: Int) {
        val appInfo: AppInfo = getItem(position) ?: return
        holder.update(appInfo)
    }


    inner class WhitelistAppInfoViewHolder(private val b: UnivWhitelistListItemBinding) :
            RecyclerView.ViewHolder(b.root) {

        fun update(appInfo: AppInfo) {
            b.univWhitelistApkLabelTv.text = appInfo.appName
            b.univWhitelistCheckbox.isChecked = appInfo.whiteListUniv1

            displayIcon(getIcon(context, appInfo.packageInfo, appInfo.appName))
            setupClickListeners(appInfo)
        }

        private fun displayIcon(drawable: Drawable?) {
            GlideApp.with(context).load(drawable).error(getDefaultIcon(context)).into(
                b.univWhitelistApkIconIv)
        }

        private fun setupClickListeners(appInfo: AppInfo) {
            b.univWhitelistContainer.setOnClickListener {
                if (DEBUG) Log.d(LOG_TAG_FIREWALL,
                                 "is app - ${appInfo.appName} whitelisted? ${appInfo.whiteListUniv1}")
                b.univWhitelistCheckbox.isChecked = !appInfo.whiteListUniv1
                modifyWhitelistApps(appInfo)
            }

            b.univWhitelistCheckbox.setOnCheckedChangeListener(null)
            b.univWhitelistCheckbox.setOnClickListener {
                if (DEBUG) Log.d(LOG_TAG_FIREWALL,
                                 "is app ${appInfo.appName} whitelisted? ${appInfo.whiteListUniv1}")
                modifyWhitelistApps(appInfo)
            }
        }

        private fun modifyWhitelistApps(appInfo: AppInfo) {
            val isWhitelist = !appInfo.whiteListUniv1
            val appUidList = FirewallManager.getAppNamesByUid(appInfo.uid)
            Log.i(LOG_TAG_FIREWALL, "App ${appInfo.appName} whitelisted from vpn? $isWhitelist")

            if (appUidList.size > 1) {
                showDialog(appUidList, appInfo, isWhitelist)
            } else {
                FirewallManager.updateWhitelistedApps(appInfo, isWhitelist)
            }
        }

        private fun showDialog(packageList: List<String>, appInfo: AppInfo, isWhitelist: Boolean) {
            val positiveTxt: String
            val builderSingle: AlertDialog.Builder = AlertDialog.Builder(context)

            builderSingle.setIcon(R.drawable.ic_whitelist)
            var appNameEllipsis = appInfo.appName
            if (appNameEllipsis.length > 10) {
                appNameEllipsis = appNameEllipsis.substring(0, 10)
                appNameEllipsis = "$appNameEllipsis..."
            }

            positiveTxt = if (isWhitelist) {
                builderSingle.setTitle(
                    context.getString(R.string.whitelist_add_app, appNameEllipsis,
                                      packageList.size.toString()))
                context.getString(R.string.whitelist_add_positive, packageList.size.toString())
            } else {
                builderSingle.setTitle(
                    context.getString(R.string.whitelist_remove_app, appNameEllipsis,
                                      packageList.size.toString()))
                context.getString(R.string.whitelist_add_negative, packageList.size.toString())
            }
            builderSingle.setCancelable(false)
            builderSingle.setItems(packageList.toTypedArray(), null)

            builderSingle.setPositiveButton(positiveTxt) { _: DialogInterface, _: Int ->
                FirewallManager.updateWhitelistedApps(appInfo, isWhitelist)
            }.setNeutralButton(context.getString(
                R.string.ctbs_dialog_negative_btn)) { _: DialogInterface, _: Int ->
                /* no-op */
            }

            val alertDialog: AlertDialog = builderSingle.show()
            alertDialog.listView.setOnItemClickListener { _, _, _, _ -> }
            alertDialog.setCancelable(false)
        }
    }
}
