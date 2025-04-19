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

import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.celzero.bravedns.R
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.databinding.ListItemFirewallAppBinding
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.FirewallManager.updateFirewallStatus
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.ProxyManager.ID_NONE
import com.celzero.bravedns.ui.activity.AppInfoActivity
import com.celzero.bravedns.ui.activity.AppInfoActivity.Companion.INTENT_UID
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.getIcon
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.simplecityapps.recyclerview_fastscroll.views.FastScrollRecyclerView.SectionedAdapter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FirewallAppListAdapter(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) : PagingDataAdapter<AppInfo, FirewallAppListAdapter.AppListViewHolder>(DIFF_CALLBACK), SectionedAdapter {

    private val packageManager: PackageManager = context.packageManager
    // private val systemAppColor: Int by lazy { UIUtils.fetchColor(context, R.attr.textColorAccentBad) }
    // private val userAppColor: Int by lazy { UIUtils.fetchColor(context, R.attr.primaryTextColor) }

    companion object {
        private val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<AppInfo>() {
                override fun areItemsTheSame(
                    oldConnection: AppInfo,
                    newConnection: AppInfo
                ): Boolean {
                    return oldConnection.packageName == newConnection.packageName
                }

                override fun areContentsTheSame(
                    oldConnection: AppInfo,
                    newConnection: AppInfo
                ): Boolean {
                    return oldConnection == newConnection
                }
            }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppListViewHolder {
        val itemBinding =
            ListItemFirewallAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
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
            io {
                val appStatus = FirewallManager.appStatus(appInfo.uid)
                val connStatus = FirewallManager.connectionStatus(appInfo.uid)
                uiCtx {
                    b.firewallAppLabelTv.text = appInfo.appName
                    // setting the appname with different color for system and user apps
                    // causes conflict with the firewall status like blocked and isolated
                    // so removing the color change for now
                    /* if (appInfo.isSystemApp) {
                        b.firewallAppLabelTv.setTextColor(systemAppColor)
                    } else {
                        b.firewallAppLabelTv.setTextColor(userAppColor)
                    } */
                    b.firewallAppToggleOther.text = getFirewallText(appStatus, connStatus)
                    displayIcon(
                        getIcon(context, appInfo.packageName, appInfo.appName), b.firewallAppIconIv)
                    // set the alpha based on internet permission
                    if (appInfo.hasInternetPermission(packageManager)) {
                        b.firewallAppLabelTv.alpha = 1f
                        b.firewallAppIconIv.alpha = 1f
                    } else {
                        b.firewallAppLabelTv.alpha = 0.4f
                        b.firewallAppIconIv.alpha = 0.4f
                    }
                    if (appInfo.packageName == context.packageName) {
                        b.firewallAppToggleWifi.visibility = View.GONE
                        b.firewallAppToggleMobileData.visibility = View.GONE
                        b.firewallAppToggleOther.text =
                            context.getString(R.string.firewall_status_allow)
                        return@uiCtx
                    }

                    b.firewallAppToggleWifi.visibility = View.VISIBLE
                    b.firewallAppToggleMobileData.visibility = View.VISIBLE
                    displayConnectionStatus(appStatus, connStatus)
                    displayDataUsage(appInfo)
                    maybeDisplayProxyStatus(appInfo)
                }
            }
        }

        private fun displayDataUsage(appInfo: AppInfo) {
            val u = Utilities.humanReadableByteCount(appInfo.uploadBytes, true)
            val uploadBytes = context.getString(R.string.symbol_upload, u)
            val d = Utilities.humanReadableByteCount(appInfo.downloadBytes, true)
            val downloadBytes = context.getString(R.string.symbol_download, d)
            b.firewallAppDataUsage.text =
                context.getString(R.string.two_argument, uploadBytes, downloadBytes)
        }

        private fun maybeDisplayProxyStatus(appInfo: AppInfo) {
            if (appInfo.isProxyExcluded) {
                return
            }

            // show key icon in drawable right of b.firewallAppDataUsage
            val proxy = ProxyManager.getProxyIdForApp(appInfo.uid)
            if (proxy.isEmpty() || proxy == ID_NONE) {
                return
            }
            b.firewallAppLabelTv.append(context.getString(R.string.symbol_key))
        }

        private fun getFirewallText(
            aStat: FirewallManager.FirewallStatus,
            cStat: FirewallManager.ConnectionStatus
        ): String {
            return when (aStat) {
                FirewallManager.FirewallStatus.NONE ->
                    when (cStat) {
                        FirewallManager.ConnectionStatus.ALLOW ->
                            context.getString(R.string.firewall_status_allow)
                        FirewallManager.ConnectionStatus.METERED ->
                            context.getString(R.string.firewall_status_block_metered)
                        FirewallManager.ConnectionStatus.UNMETERED ->
                            context.getString(R.string.firewall_status_block_unmetered)
                        FirewallManager.ConnectionStatus.BOTH ->
                            context.getString(R.string.firewall_status_blocked)
                    }
                FirewallManager.FirewallStatus.EXCLUDE ->
                    context.getString(R.string.firewall_status_excluded)
                FirewallManager.FirewallStatus.ISOLATE ->
                    context.getString(R.string.firewall_status_isolate)
                FirewallManager.FirewallStatus.BYPASS_UNIVERSAL ->
                    context.getString(R.string.firewall_status_whitelisted)
                FirewallManager.FirewallStatus.BYPASS_DNS_FIREWALL ->
                    context.getString(R.string.firewall_status_bypass_dns_firewall)
                FirewallManager.FirewallStatus.UNTRACKED ->
                    context.getString(R.string.firewall_status_unknown)
            }
        }

        private fun displayConnectionStatus(
            firewallStatus: FirewallManager.FirewallStatus,
            connStatus: FirewallManager.ConnectionStatus
        ) {
            when (firewallStatus) {
                FirewallManager.FirewallStatus.NONE -> {
                    when (connStatus) {
                        FirewallManager.ConnectionStatus.ALLOW -> {
                            showWifiEnabled()
                            showMobileDataEnabled()
                        }
                        FirewallManager.ConnectionStatus.UNMETERED -> {
                            showWifiDisabled()
                            showMobileDataEnabled()
                        }
                        FirewallManager.ConnectionStatus.METERED -> {
                            showWifiEnabled()
                            showMobileDataDisabled()
                        }
                        FirewallManager.ConnectionStatus.BOTH -> {
                            showWifiDisabled()
                            showMobileDataDisabled()
                        }
                    }
                }
                FirewallManager.FirewallStatus.EXCLUDE -> {
                    showMobileDataUnused()
                    showWifiUnused()
                }
                FirewallManager.FirewallStatus.BYPASS_UNIVERSAL -> {
                    showMobileDataUnused()
                    showWifiUnused()
                }
                FirewallManager.FirewallStatus.ISOLATE -> {
                    showMobileDataUnused()
                    showWifiUnused()
                }
                FirewallManager.FirewallStatus.BYPASS_DNS_FIREWALL -> {
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

        private fun displayIcon(drawable: Drawable?, mIconImageView: ImageView) {
            ui {
                Glide.with(context)
                    .load(drawable)
                    .error(Utilities.getDefaultIcon(context))
                    .into(mIconImageView)
            }
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

            b.firewallAppDetailsLl.setOnClickListener {
                enableAfterDelay(TimeUnit.SECONDS.toMillis(1L), b.firewallAppIconIv)
                openAppDetailActivity(appInfo.uid)
            }

            b.firewallAppToggleWifi.setOnClickListener {
                enableAfterDelay(TimeUnit.SECONDS.toMillis(1L), b.firewallAppToggleWifi)
                io {
                    val appNames = FirewallManager.getAppNamesByUid(appInfo.uid)
                    val connStatus = FirewallManager.connectionStatus(appInfo.uid)
                    uiCtx {
                        if (appNames.count() > 1) {
                            showDialog(appNames, appInfo, isWifi = true, connStatus)
                            return@uiCtx
                        }
                        ioCtx { toggleWifi(appInfo, connStatus) }
                    }
                }
            }

            b.firewallAppToggleMobileData.setOnClickListener {
                enableAfterDelay(TimeUnit.SECONDS.toMillis(1L), b.firewallAppToggleMobileData)
                io {
                    val appNames = FirewallManager.getAppNamesByUid(appInfo.uid)
                    val connStatus = FirewallManager.connectionStatus(appInfo.uid)
                    uiCtx {
                        if (appNames.count() > 1) {
                            showDialog(appNames, appInfo, isWifi = false, connStatus)
                            return@uiCtx
                        }
                        ioCtx { toggleMobileData(appInfo, connStatus) }
                    }
                }
            }
        }

        private suspend fun toggleMobileData(
            appInfo: AppInfo,
            connStatus: FirewallManager.ConnectionStatus
        ) {
            if (appInfo.packageName == context.packageName) {
                return
            }
            when (connStatus) {
                FirewallManager.ConnectionStatus.METERED -> {
                    updateFirewallStatus(
                        appInfo.uid,
                        FirewallManager.FirewallStatus.NONE,
                        FirewallManager.ConnectionStatus.ALLOW)
                }
                FirewallManager.ConnectionStatus.UNMETERED -> {
                    updateFirewallStatus(
                        appInfo.uid,
                        FirewallManager.FirewallStatus.NONE,
                        FirewallManager.ConnectionStatus.BOTH)
                }
                FirewallManager.ConnectionStatus.BOTH -> {
                    updateFirewallStatus(
                        appInfo.uid,
                        FirewallManager.FirewallStatus.NONE,
                        FirewallManager.ConnectionStatus.UNMETERED)
                }
                FirewallManager.ConnectionStatus.ALLOW -> {
                    updateFirewallStatus(
                        appInfo.uid,
                        FirewallManager.FirewallStatus.NONE,
                        FirewallManager.ConnectionStatus.METERED)
                }
            }
        }

        private suspend fun toggleWifi(
            appInfo: AppInfo,
            connStatus: FirewallManager.ConnectionStatus
        ) {
            if (appInfo.packageName == context.packageName) {
                return
            }
            when (connStatus) {
                FirewallManager.ConnectionStatus.METERED -> {
                    updateFirewallStatus(
                        appInfo.uid,
                        FirewallManager.FirewallStatus.NONE,
                        FirewallManager.ConnectionStatus.BOTH)
                }
                FirewallManager.ConnectionStatus.UNMETERED -> {
                    updateFirewallStatus(
                        appInfo.uid,
                        FirewallManager.FirewallStatus.NONE,
                        FirewallManager.ConnectionStatus.ALLOW)
                }
                FirewallManager.ConnectionStatus.BOTH -> {
                    updateFirewallStatus(
                        appInfo.uid,
                        FirewallManager.FirewallStatus.NONE,
                        FirewallManager.ConnectionStatus.METERED)
                }
                FirewallManager.ConnectionStatus.ALLOW -> {
                    updateFirewallStatus(
                        appInfo.uid,
                        FirewallManager.FirewallStatus.NONE,
                        FirewallManager.ConnectionStatus.UNMETERED)
                }
            }
        }

        private fun openAppDetailActivity(uid: Int) {
            val intent = Intent(context, AppInfoActivity::class.java)
            intent.putExtra(INTENT_UID, uid)
            context.startActivity(intent)
        }

        private fun showDialog(
            packageList: List<String>,
            appInfo: AppInfo,
            isWifi: Boolean,
            connStatus: FirewallManager.ConnectionStatus
        ) {

            val builderSingle = MaterialAlertDialogBuilder(context)

            builderSingle.setIcon(R.drawable.ic_firewall_block_grey)
            val count = packageList.count()
            builderSingle.setTitle(
                context.getString(
                    R.string.ctbs_block_other_apps, appInfo.appName, count.toString()))

            val arrayAdapter =
                ArrayAdapter<String>(context, android.R.layout.simple_list_item_activated_1)
            arrayAdapter.addAll(packageList)
            builderSingle.setCancelable(false)

            builderSingle.setItems(packageList.toTypedArray(), null)

            builderSingle
                .setPositiveButton(context.getString(R.string.lbl_proceed)) {
                    _: DialogInterface,
                    _: Int ->
                    io {
                        if (isWifi) {
                            toggleWifi(appInfo, connStatus)
                            return@io
                        }

                        toggleMobileData(appInfo, connStatus)
                    }
                }
                .setNeutralButton(context.getString(R.string.ctbs_dialog_negative_btn)) {
                    _: DialogInterface,
                    _: Int ->
                }

            val alertDialog: AlertDialog = builderSingle.create()
            alertDialog.listView.setOnItemClickListener { _, _, _, _ -> }
            alertDialog.show()
        }
    }

    private fun enableAfterDelay(delay: Long, vararg views: View) {
        for (v in views) v.isEnabled = false

        Utilities.delay(delay, lifecycleOwner.lifecycleScope) {
            for (v in views) v.isEnabled = true
        }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }

    private fun ui(f: suspend () -> Unit) {
        lifecycleOwner.lifecycleScope.launch { withContext(Dispatchers.Main) { f() } }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleOwner.lifecycleScope.launch { withContext(Dispatchers.IO) { f() } }
    }

    private suspend fun ioCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.IO) { f() }
    }

    override fun getSectionName(position: Int): String {
        val appInfo = getItem(position) ?: return ""
        return appInfo.appName.substring(0, 1)
    }
}
