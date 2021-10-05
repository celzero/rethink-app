/*
 * Copyright 2021 RethinkDNS and its authors
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

import android.app.ActivityManager
import android.content.Context
import android.content.DialogInterface
import android.graphics.drawable.Drawable
import android.net.VpnService
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagedListAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.databinding.ListItemFirewallAppBinding
import com.celzero.bravedns.glide.GlideApp
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.getIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class FirewallAppListAdapter(private val context: Context,
                             private val lifecycleOwner: LifecycleOwner,
                             private val persistentState: PersistentState) :
        PagedListAdapter<AppInfo, FirewallAppListAdapter.AppListViewHolder>(DIFF_CALLBACK) {

    private var activityManager: ActivityManager = context.getSystemService(
        VpnService.ACTIVITY_SERVICE) as ActivityManager

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<AppInfo>() {
            override fun areItemsTheSame(oldConnection: AppInfo, newConnection: AppInfo): Boolean {
                return (oldConnection.packageInfo == newConnection.packageInfo && oldConnection.isInternetAllowed == newConnection.isInternetAllowed)
            }

            override fun areContentsTheSame(oldConnection: AppInfo,
                                            newConnection: AppInfo): Boolean {
                return (oldConnection.packageInfo == newConnection.packageInfo && oldConnection.isInternetAllowed != newConnection.isInternetAllowed)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppListViewHolder {
        val itemBinding = ListItemFirewallAppBinding.inflate(LayoutInflater.from(parent.context),
                                                             parent, false)
        return AppListViewHolder(itemBinding)
    }

    override fun onBindViewHolder(holder: AppListViewHolder, position: Int) {
        val appInfo: AppInfo = getItem(position) ?: return
        holder.update(appInfo)
    }

    inner class AppListViewHolder(private val b: ListItemFirewallAppBinding) :
            RecyclerView.ViewHolder(b.root) {

        fun update(appInfo: AppInfo) {
            displayChildDetails(appInfo)
            setupChildClickListeners(appInfo)
        }

        private fun displayChildDetails(appInfo: AppInfo) {
            b.firewallApkLabelTv.text = appInfo.appName
            b.firewallApkProgressBar.visibility = View.GONE

            // To disable the app from selecting into firewall
            b.firewallToggleWifi.isClickable = appInfo.canFirewall()
            b.firewallToggleWifi.isEnabled = appInfo.canFirewall()
            b.firewallToggleWifi.isChecked = !appInfo.isInternetAllowed

            displayIcon(getIcon(context, appInfo.packageInfo, appInfo.appName), b.firewallApkIconIv)
            showAppHint(b.firewallStatusIndicator, appInfo)

        }

        private fun showAppHint(mIconIndicator: TextView, appInfo: AppInfo) {
            if (appInfo.isInternetAllowed) {
                mIconIndicator.setBackgroundColor(context.getColor(R.color.colorGreen_900))
            } else {
                mIconIndicator.setBackgroundColor(context.getColor(R.color.colorAmber_900))
            }

            when {
                appInfo.whiteListUniv1 -> {
                    showAppTextualHint(b.firewallApkPackageTv,
                                       context.getString(R.string.firewall_app_added_in_whitelist))
                }
                appInfo.isExcluded -> {
                    showAppTextualHint(b.firewallApkPackageTv, context.getString(
                        R.string.firewall_app_added_in_excluded_list))
                }
                else -> {
                    hideAppTextualHint(b.firewallApkPackageTv)
                }
            }
        }

        private fun hideAppTextualHint(mPackageTextView: TextView) {
            mPackageTextView.visibility = View.GONE
        }

        private fun showAppTextualHint(mPackageTextView: TextView, message: String) {
            mPackageTextView.visibility = View.VISIBLE
            mPackageTextView.text = message
        }

        private fun displayIcon(drawable: Drawable?, mIconImageView: ImageView) {
            GlideApp.with(context).load(drawable).error(Utilities.getDefaultIcon(context)).into(
                mIconImageView)
        }

        private fun setupChildClickListeners(appInfo: AppInfo) {

            b.firewallToggleWifi.setOnCheckedChangeListener(null)
            b.firewallToggleWifi.setOnClickListener {
                enableAfterDelay(TimeUnit.SECONDS.toMillis(1L), b.firewallToggleWifi)

                val appUidList = FirewallManager.getAppNamesByUid(appInfo.uid)

                if (appUidList.count() > 1) {
                    b.firewallToggleWifi.isChecked = !appInfo.isInternetAllowed
                    // since isChecked is toggled above, notify the renderer
                    notifyDataSetChanged()
                    showDialog(appUidList, appInfo)
                    return@setOnClickListener
                }
                updateBlockApp(appInfo)
            }
        }

        private fun showDialog(packageList: List<String>, appInfo: AppInfo) {
            val positiveTxt: String

            val builderSingle: AlertDialog.Builder = AlertDialog.Builder(context)

            builderSingle.setIcon(R.drawable.spinner_firewall)
            val count = packageList.count()
            positiveTxt = if (appInfo.isInternetAllowed) {
                builderSingle.setTitle(
                    context.getString(R.string.ctbs_block_other_apps, appInfo.appName,
                                      count.toString()))
                context.getString(R.string.ctbs_block_other_apps_positive_text, count.toString())
            } else {
                builderSingle.setTitle(
                    context.getString(R.string.ctbs_unblock_other_apps, appInfo.appName,
                                      count.toString()))
                context.getString(R.string.ctbs_unblock_other_apps_positive_text, count.toString())
            }
            val arrayAdapter = ArrayAdapter<String>(context,
                                                    android.R.layout.simple_list_item_activated_1)
            arrayAdapter.addAll(packageList)
            builderSingle.setCancelable(false)

            builderSingle.setItems(packageList.toTypedArray(), null)

            builderSingle.setPositiveButton(positiveTxt) { _: DialogInterface, _: Int ->
                updateBlockApp(appInfo)
            }.setNeutralButton(context.getString(
                R.string.ctbs_dialog_negative_btn)) { _: DialogInterface, _: Int ->

            }

            val alertDialog: AlertDialog = builderSingle.create()
            alertDialog.listView.setOnItemClickListener { _, _, _, _ -> }
            alertDialog.show()
        }

        private fun updateBlockApp(appInfo: AppInfo) {
            val isInternetAllowed = appInfo.isInternetAllowed
            FirewallManager.updateFirewalledApps(appInfo.uid, !isInternetAllowed)
            if (!isInternetAllowed) b.firewallStatusIndicator.setBackgroundColor(
                context.getColor(R.color.colorGreen_900))
            else b.firewallStatusIndicator.setBackgroundColor(
                context.getColor(R.color.colorAmber_900))
            killApps(appInfo.uid)
        }

        private fun killApps(uid: Int) {
            if (!persistentState.killAppOnFirewall) return
            io {
                val apps = FirewallManager.getNonSystemAppsPackageNameByUid(uid)
                apps.forEach {
                    Utilities.killBg(activityManager, it)
                }
            }
        }

    }

    private fun enableAfterDelay(delay: Long, vararg views: View) {
        for (v in views) v.isEnabled = false

        Utilities.delay(delay, lifecycleOwner.lifecycleScope) {
            for (v in views) v.isEnabled = true
        }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                f()
            }
        }
    }
}