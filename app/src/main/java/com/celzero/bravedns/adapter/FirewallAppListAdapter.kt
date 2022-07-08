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
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.VpnService
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagedListAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.automaton.FirewallManager.updateFirewallStatus
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.databinding.ListItemFirewallAppBinding
import com.celzero.bravedns.glide.GlideApp
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.AppInfoActivity
import com.celzero.bravedns.ui.AppInfoActivity.Companion.UID_INTENT_NAME
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
                return oldConnection == newConnection
            }

            override fun areContentsTheSame(oldConnection: AppInfo,
                                            newConnection: AppInfo): Boolean {
                return (oldConnection.packageInfo == newConnection.packageInfo && oldConnection.firewallStatus == newConnection.firewallStatus && oldConnection.metered == newConnection.metered)
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
            displayDetails(appInfo)
            setupClickListeners(appInfo)
        }

        private fun displayDetails(appInfo: AppInfo) {
            val appStatus = FirewallManager.appStatus(appInfo.uid)
            val connStatus = FirewallManager.connectionStatus(appInfo.uid)

            b.firewallAppLabelTv.text = appInfo.appName
            b.firewallAppToggleOther.text = getFirewallText(appStatus, connStatus)

            displayIcon(getIcon(context, appInfo.packageInfo, appInfo.appName), b.firewallAppIconIv)
            displayConnectionStatus(appStatus, connStatus)
            showAppHint(b.firewallAppStatusIndicator, appInfo)
        }

        private fun getFirewallText(aStat: FirewallManager.FirewallStatus,
                                    cStat: FirewallManager.ConnectionStatus): CharSequence {
            return when (aStat) {
                FirewallManager.FirewallStatus.ALLOW -> context.getString(
                    R.string.firewall_status_allow)
                FirewallManager.FirewallStatus.EXCLUDE -> context.getString(
                    R.string.firewall_status_excluded)
                FirewallManager.FirewallStatus.WHITELIST -> context.getString(
                    R.string.firewall_status_whitelisted)
                FirewallManager.FirewallStatus.BLOCK -> {
                    when {
                        cStat.mobileData() -> context.getString(
                            R.string.firewall_status_allow_unmetered)
                        cStat.wifi() -> context.getString(R.string.firewall_status_allow_metered)
                        else -> context.getString(R.string.firewall_status_blocked)
                    }
                }
                FirewallManager.FirewallStatus.UNTRACKED -> context.getString(
                    R.string.firewall_status_unknown)
            }
        }

        private fun displayConnectionStatus(firewallStatus: FirewallManager.FirewallStatus,
                                            connStatus: FirewallManager.ConnectionStatus) {
            when (firewallStatus) {
                FirewallManager.FirewallStatus.ALLOW -> {
                    showWifiEnabled()
                    showMobileDataEnabled()
                }
                FirewallManager.FirewallStatus.BLOCK -> {
                    when {
                        connStatus.both() -> {
                            showWifiDisabled()
                            showMobileDataDisabled()
                        }
                        connStatus.mobileData() -> {
                            showWifiEnabled()
                            showMobileDataDisabled()
                        }
                        else -> {
                            showWifiDisabled()
                            showMobileDataEnabled()
                        }
                    }
                }
                FirewallManager.FirewallStatus.EXCLUDE -> {
                    showMobileDataUnused()
                    showWifiUnused()
                }
                FirewallManager.FirewallStatus.WHITELIST -> {
                    showMobileDataUnused()
                    showWifiUnused()
                }
                else -> {
                    showWifiDisabled()
                    showMobileDataDisabled()
                }
            }
        }

        private fun showMobileDataDisabled() {
            b.firewallAppToggleMobileData.setImageDrawable(
                ContextCompat.getDrawable(context, R.drawable.ic_firewall_data_off))
        }

        private fun showMobileDataEnabled() {
            b.firewallAppToggleMobileData.setImageDrawable(
                ContextCompat.getDrawable(context, R.drawable.ic_firewall_data_on))
        }

        private fun showWifiDisabled() {
            b.firewallAppToggleWifi.setImageDrawable(
                ContextCompat.getDrawable(context, R.drawable.ic_firewall_wifi_off))
        }

        private fun showWifiEnabled() {
            b.firewallAppToggleWifi.setImageDrawable(
                ContextCompat.getDrawable(context, R.drawable.ic_firewall_wifi_on))
        }

        private fun showMobileDataUnused() {
            b.firewallAppToggleMobileData.setImageDrawable(
                ContextCompat.getDrawable(context, R.drawable.ic_firewall_data_on_grey))
        }

        private fun showWifiUnused() {
            b.firewallAppToggleWifi.setImageDrawable(
                ContextCompat.getDrawable(context, R.drawable.ic_firewall_wifi_on_grey))
        }

        private fun showAppHint(mIconIndicator: TextView, appInfo: AppInfo) {
            when (FirewallManager.appStatus(appInfo.uid)) {
                FirewallManager.FirewallStatus.ALLOW -> {
                    mIconIndicator.setBackgroundColor(context.getColor(R.color.colorGreen_900))
                }
                FirewallManager.FirewallStatus.EXCLUDE -> {
                    mIconIndicator.setBackgroundColor(
                        context.getColor(R.color.primaryLightColorText))
                }
                FirewallManager.FirewallStatus.WHITELIST -> {
                    mIconIndicator.setBackgroundColor(
                        context.getColor(R.color.primaryLightColorText))
                }
                FirewallManager.FirewallStatus.BLOCK -> {
                    mIconIndicator.setBackgroundColor(context.getColor(R.color.colorAmber_900))
                }
                FirewallManager.FirewallStatus.UNTRACKED -> { /* no-op */
                }
            }
        }

        private fun displayIcon(drawable: Drawable?, mIconImageView: ImageView) {
            GlideApp.with(context).load(drawable).error(Utilities.getDefaultIcon(context)).into(
                mIconImageView)
        }

        private fun setupClickListeners(appInfo: AppInfo) {

            b.firewallAppTextLl.setOnClickListener {
                enableAfterDelay(TimeUnit.SECONDS.toMillis(1L), b.firewallAppTextLl)
                openAppDetailActivity(appInfo.uid)
            }

            b.firewallAppIconIv.setOnClickListener {
                enableAfterDelay(TimeUnit.SECONDS.toMillis(1L), b.firewallAppIconIv)
                openAppDetailActivity(appInfo.uid)
            }

            b.indicator.setOnClickListener {
                enableAfterDelay(TimeUnit.SECONDS.toMillis(1L), b.firewallAppIconIv)
                openAppDetailActivity(appInfo.uid)
            }

            b.firewallAppToggleWifi.setOnClickListener {
                enableAfterDelay(TimeUnit.SECONDS.toMillis(1L), b.firewallAppToggleWifi)

                val appNames = FirewallManager.getAppNamesByUid(appInfo.uid)
                if (appNames.count() > 1) {
                    showDialog(appNames, appInfo, isWifi = true)
                    return@setOnClickListener
                }
                toggleWifi(appInfo)
            }

            b.firewallAppToggleMobileData.setOnClickListener {
                enableAfterDelay(TimeUnit.SECONDS.toMillis(1L), b.firewallAppToggleMobileData)
                val appNames = FirewallManager.getAppNamesByUid(appInfo.uid)
                if (appNames.count() > 1) {
                    showDialog(appNames, appInfo, isWifi = false)
                    return@setOnClickListener
                }

                toggleMobileData(appInfo)
            }
        }

        private fun toggleMobileData(appInfo: AppInfo) {
            val appStatus = FirewallManager.appStatus(appInfo.uid)

            when (FirewallManager.connectionStatus(appInfo.uid)) {
                FirewallManager.ConnectionStatus.MOBILE_DATA -> {
                    updateFirewallStatus(appInfo.uid, FirewallManager.FirewallStatus.ALLOW,
                                         FirewallManager.ConnectionStatus.BOTH)
                }
                FirewallManager.ConnectionStatus.WIFI -> {
                    updateFirewallStatus(appInfo.uid, FirewallManager.FirewallStatus.BLOCK,
                                         FirewallManager.ConnectionStatus.BOTH)
                    killApps(appInfo.uid)
                }
                FirewallManager.ConnectionStatus.BOTH -> {
                    if (appStatus.blocked()) {
                        updateFirewallStatus(appInfo.uid, FirewallManager.FirewallStatus.BLOCK,
                                             FirewallManager.ConnectionStatus.WIFI)
                        return
                    }
                    updateFirewallStatus(appInfo.uid, FirewallManager.FirewallStatus.BLOCK,
                                         FirewallManager.ConnectionStatus.MOBILE_DATA)
                }
            }
        }

        private fun toggleWifi(appInfo: AppInfo) {
            val appStatus = FirewallManager.appStatus(appInfo.uid)

            when (FirewallManager.connectionStatus(appInfo.uid)) {
                FirewallManager.ConnectionStatus.MOBILE_DATA -> {
                    updateFirewallStatus(appInfo.uid, FirewallManager.FirewallStatus.BLOCK,
                                         FirewallManager.ConnectionStatus.BOTH)
                    killApps(appInfo.uid)
                }
                FirewallManager.ConnectionStatus.WIFI -> {
                    updateFirewallStatus(appInfo.uid, FirewallManager.FirewallStatus.ALLOW,
                                         FirewallManager.ConnectionStatus.BOTH)
                }
                FirewallManager.ConnectionStatus.BOTH -> {
                    if (appStatus.blocked()) {
                        updateFirewallStatus(appInfo.uid, FirewallManager.FirewallStatus.BLOCK,
                                             FirewallManager.ConnectionStatus.MOBILE_DATA)
                        return
                    }
                    updateFirewallStatus(appInfo.uid, FirewallManager.FirewallStatus.BLOCK,
                                         FirewallManager.ConnectionStatus.WIFI)
                }
            }
        }

        private fun openAppDetailActivity(uid: Int) {
            val intent = Intent(context, AppInfoActivity::class.java)
            intent.putExtra(UID_INTENT_NAME, uid)
            context.startActivity(intent)
        }

        private fun showDialog(packageList: List<String>, appInfo: AppInfo, isWifi: Boolean) {

            val builderSingle: AlertDialog.Builder = AlertDialog.Builder(context)

            builderSingle.setIcon(R.drawable.spinner_firewall)
            val count = packageList.count()
            builderSingle.setTitle(
                context.getString(R.string.ctbs_block_other_apps, appInfo.appName,
                                  count.toString()))

            val arrayAdapter = ArrayAdapter<String>(context,
                                                    android.R.layout.simple_list_item_activated_1)
            arrayAdapter.addAll(packageList)
            builderSingle.setCancelable(false)

            builderSingle.setItems(packageList.toTypedArray(), null)

            builderSingle.setPositiveButton(context.getString(
                R.string.ctbs_proceed_positive_text)) { _: DialogInterface, _: Int ->
                if (isWifi) {
                    toggleWifi(appInfo)
                    return@setPositiveButton
                }

                toggleMobileData(appInfo)
            }.setNeutralButton(context.getString(
                R.string.ctbs_dialog_negative_btn)) { _: DialogInterface, _: Int ->
            }

            val alertDialog: AlertDialog = builderSingle.create()
            alertDialog.listView.setOnItemClickListener { _, _, _, _ -> }
            alertDialog.show()
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
