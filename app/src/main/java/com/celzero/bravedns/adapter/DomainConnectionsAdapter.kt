/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.adapter

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.ui.activity.AppInfoActivity
import com.celzero.bravedns.ui.activity.DomainConnectionsActivity
import com.celzero.bravedns.ui.activity.NetworkLogsActivity
import com.celzero.bravedns.ui.compose.appdetails.AppConnectionsPresenter
import com.celzero.bravedns.ui.compose.statistics.StatisticsRow
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DomainConnectionsAdapter(private val context: Context, private val type: DomainConnectionsActivity.InputType) :
    AppConnectionsPresenter(object : DiffUtil.ItemCallback<AppConnection>() {
        override fun areItemsTheSame(old: AppConnection, new: AppConnection) = old == new
        override fun areContentsTheSame(old: AppConnection, new: AppConnection) = old == new
    }) {
    @Composable
    override fun Row(index: Int, connection: AppConnection, revision: Int) {
        val identity by produceState<Pair<String?, android.graphics.drawable.Drawable?>?>(null, connection.uid) {
            value = withContext(Dispatchers.IO) {
                val appInfo = FirewallManager.getAppInfoByUid(connection.uid)
                appInfo?.appName to Utilities.getIcon(context, appInfo?.packageName.orEmpty(), appInfo?.appName.orEmpty())
            }
        }
        val title = connection.appOrDnsName?.takeIf { it.isNotEmpty() } ?: identity?.first
            ?: context.getString(R.string.network_log_app_name_unnamed, "(${connection.uid})")
        val hasUsage = connection.downloadBytes != null && connection.uploadBytes != null
        val subtitle = if (hasUsage) context.getString(R.string.two_argument,
            context.getString(R.string.symbol_upload, Utilities.humanReadableByteCount(connection.uploadBytes!!, true)),
            context.getString(R.string.symbol_download, Utilities.humanReadableByteCount(connection.downloadBytes!!, true))) else null
        StatisticsRow(title, subtitle, "", if (hasUsage) connection.count.toString() else "",
            icon = identity?.second ?: Utilities.getDefaultIcon(context), tag = "domain_connection_$index",
            onClick = if (hasUsage) ({ open(connection) }) else null)
    }
    private fun open(dc: AppConnection) {
        val owner = context as LifecycleOwner
        owner.lifecycleScope.launch(Dispatchers.IO) {
            val appInfo = FirewallManager.getAppInfoByUid(dc.uid)
            withContext(Dispatchers.Main.immediate) {
                if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@withContext
                if (appInfo == null) {
                    val intent = Intent(context, NetworkLogsActivity::class.java)
                    intent.putExtra(Constants.VIEW_PAGER_SCREEN_TO_LOAD, NetworkLogsActivity.Tabs.NETWORK_LOGS.screen)
                    intent.putExtra(Constants.SEARCH_QUERY, dc.appOrDnsName)
                    context.startActivity(intent)
                } else {
                    val intent = Intent(context, AppInfoActivity::class.java)
                    intent.putExtra(AppInfoActivity.INTENT_UID, dc.uid)
                    context.startActivity(intent)
                }
            }
        }
    }
}
