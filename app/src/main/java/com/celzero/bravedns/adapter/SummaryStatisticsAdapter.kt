/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.adapter

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.activity.AppInfoActivity
import com.celzero.bravedns.ui.activity.DomainConnectionsActivity
import com.celzero.bravedns.ui.activity.NetworkLogsActivity
import com.celzero.bravedns.ui.bottomsheet.AppDomainRulesBottomSheet
import com.celzero.bravedns.ui.fragment.SummaryStatisticsFragment.SummaryStatisticsType
import com.celzero.bravedns.ui.compose.appdetails.AppConnectionsPresenter
import com.celzero.bravedns.ui.compose.statistics.statisticsFavIcon
import com.celzero.bravedns.ui.compose.statistics.StatisticsRow
import com.celzero.bravedns.ui.compose.statistics.statisticsLabel
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_DNS
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.getFlag
import com.celzero.bravedns.viewmodel.SummaryStatisticsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.log2

/** Native paging presenter. Historical name and command API are retained for every caller. */
class SummaryStatisticsAdapter(private val context: Context, private val persistentState: PersistentState,
    private val appConfig: AppConfig, private val type: SummaryStatisticsType) :
    AppConnectionsPresenter(diffCallback(type)), AppDomainRulesBottomSheet.OnBottomSheetDialogFragmentDismiss {
    private var timeCategory = SummaryStatisticsViewModel.TimeCategory.ONE_HOUR
    private var uid: Int = Constants.INVALID_UID
    fun setUid(uid: Int) { this.uid = uid }
    private val identities = mutableMapOf<Int, Pair<String?, Drawable?>>()
    companion object {
        private const val PERCENTAGE_MULTIPLIER = 100

        // shield glyph shown in the icon slot of blocklist rows
        private const val BLOCKLIST_GLYPH = "\uD83D\uDEE1"

        private fun diffCallback(type: SummaryStatisticsType): DiffUtil.ItemCallback<AppConnection> =
            object : DiffUtil.ItemCallback<AppConnection>() {
                override fun areItemsTheSame(old: AppConnection, new: AppConnection): Boolean {
                    return keyOf(old, type) == keyOf(new, type)
                }

                override fun areContentsTheSame(old: AppConnection, new: AppConnection): Boolean {
                    // AppConnection is a data class; full equality covers every
                    // field rendered by bind()
                    return old == new
                }
            }

        /**
         * Stable per-type identity for DiffUtil. Several queries (domains, ASN,
         * countries) aggregate with constant uid/ip/port, so uid+ip+port would
         * give every row the same identity and diffing across tab switches
         * would misapply updates.
         */
        private fun keyOf(item: AppConnection, type: SummaryStatisticsType): String {
            return when (type) {
                SummaryStatisticsType.MOST_CONNECTED_APPS,
                SummaryStatisticsType.MOST_BLOCKED_APPS,
                SummaryStatisticsType.TOP_ACTIVE_CONNS -> "uid:${item.uid}"
                SummaryStatisticsType.MOST_CONNECTED_ASN,
                SummaryStatisticsType.MOST_BLOCKED_ASN,
                SummaryStatisticsType.MOST_CONTACTED_DOMAINS,
                SummaryStatisticsType.MOST_BLOCKED_DOMAINS -> "name:${item.appOrDnsName.orEmpty()}"
                SummaryStatisticsType.MOST_BLOCKED_BLOCKLISTS -> "blocklist:${item.appOrDnsName.orEmpty()}"
                SummaryStatisticsType.MOST_CONTACTED_COUNTRIES -> "flag:${item.flag}"
                SummaryStatisticsType.MOST_CONTACTED_IPS,
                SummaryStatisticsType.MOST_BLOCKED_IPS -> "ip:${item.uid}:${item.ipAddress}:${item.port}"
            }
        }
    }
    @Composable
    override fun Row(index: Int, connection: AppConnection, revision: Int) {
        val isApp = type == SummaryStatisticsType.TOP_ACTIVE_CONNS || type == SummaryStatisticsType.MOST_CONNECTED_APPS || type == SummaryStatisticsType.MOST_BLOCKED_APPS
        val identity by produceState(identities[connection.uid], connection.uid, isApp) {
            if (isApp && value == null) value = withContext(Dispatchers.IO) {
                val info = FirewallManager.getAppInfoByUid(connection.uid)
                info?.appName to (Utilities.getIcon(context, info?.packageName.orEmpty(), info?.appName.orEmpty()) ?: Utilities.getDefaultIcon(context))
            }.also { identities[connection.uid] = it }
        }
        val isDomain = type == SummaryStatisticsType.MOST_CONTACTED_DOMAINS || type == SummaryStatisticsType.MOST_BLOCKED_DOMAINS
        val favicon = if (isDomain) statisticsFavIcon(context, connection.appOrDnsName) else null
        val title = if (isApp) identity?.first?.takeIf { it.isNotEmpty() } ?: connection.appOrDnsName?.takeIf { it.isNotEmpty() }
            ?: context.getString(R.string.network_log_app_name_unnamed, connection.uid.toString())
            else if (type == SummaryStatisticsType.MOST_BLOCKED_BLOCKLISTS && (connection.flag.toIntOrNull() ?: 0) > 1)
                context.getString(R.string.ssv_blocklist_summary, connection.appOrDnsName.orEmpty(), connection.flag.toInt())
            else statisticsLabel(context, connection, type)
        val usage = if (type == SummaryStatisticsType.MOST_CONNECTED_APPS && connection.downloadBytes != null && connection.uploadBytes != null) {
            val download = context.getString(R.string.symbol_download, Utilities.humanReadableByteCount(connection.downloadBytes, true))
            val upload = context.getString(R.string.symbol_upload, Utilities.humanReadableByteCount(connection.uploadBytes, true))
            context.getString(R.string.two_argument, upload, download)
        } else null
        val flag = when (type) {
            SummaryStatisticsType.MOST_CONNECTED_ASN, SummaryStatisticsType.MOST_BLOCKED_ASN -> if (connection.flag.isNotEmpty()) getFlag(connection.flag) else "--"
            SummaryStatisticsType.MOST_BLOCKED_BLOCKLISTS -> BLOCKLIST_GLYPH
            else -> connection.flag
        }
        val primary = if (type == SummaryStatisticsType.MOST_CONNECTED_APPS) usage.orEmpty() else title
        val secondary = if (type == SummaryStatisticsType.MOST_CONNECTED_APPS && usage != null) title else null
        StatisticsRow(primary, secondary, flag, connection.count.toString(), calculatePercentage(connection),
            themeColor(if (connection.blocked) R.attr.accentBad else R.attr.accentGood),
            if (isApp) identity?.second ?: Utilities.getDefaultIcon(context) else favicon,
            downArrow = type == SummaryStatisticsType.MOST_BLOCKED_DOMAINS && uid != Constants.INVALID_UID,
            tag = "summary_row_${type.tid}_$index") { onRowClick(connection) }
    }
    private fun onRowClick(appConnection: AppConnection) {
                when (type) {
                    SummaryStatisticsType.TOP_ACTIVE_CONNS -> {
                        startAppInfoActivity(appConnection)
                    }
                    SummaryStatisticsType.MOST_CONNECTED_APPS -> {
                        io {
                            if (isUnknownApp(appConnection)) {
                                uiCtx {
                                    showNetworkLogs(
                                        appConnection,
                                        SummaryStatisticsType.MOST_CONNECTED_APPS
                                    )
                                }
                            } else {
                                uiCtx { startAppInfoActivity(appConnection) }
                            }
                        }
                    }
                    SummaryStatisticsType.MOST_BLOCKED_APPS -> {
                        io {
                            if (isUnknownApp(appConnection)) {
                                uiCtx {
                                    showNetworkLogs(
                                        appConnection,
                                        SummaryStatisticsType.MOST_BLOCKED_APPS
                                    )
                                }
                            } else {
                                uiCtx { startAppInfoActivity(appConnection) }
                            }
                        }
                    }
                    SummaryStatisticsType.MOST_CONNECTED_ASN -> {
                        startDomainConnectionsActivity(appConnection, DomainConnectionsActivity.InputType.ASN)
                    }
                    SummaryStatisticsType.MOST_BLOCKED_ASN -> {
                        startDomainConnectionsActivity(appConnection, DomainConnectionsActivity.InputType.ASN, true)
                    }
                    SummaryStatisticsType.MOST_CONTACTED_DOMAINS -> {
                        startDomainConnectionsActivity(appConnection, DomainConnectionsActivity.InputType.DOMAIN)
                    }
                    SummaryStatisticsType.MOST_BLOCKED_DOMAINS -> {
                        if (uid != Constants.INVALID_UID) {
                            showDomainRulesBottomSheet(appConnection)
                        } else {
                            startDomainConnectionsActivity(appConnection, DomainConnectionsActivity.InputType.DOMAIN, true)
                        }
                    }
                    SummaryStatisticsType.MOST_BLOCKED_BLOCKLISTS -> {
                        startDomainConnectionsActivity(appConnection, DomainConnectionsActivity.InputType.BLOCKLIST, true)
                    }
                    SummaryStatisticsType.MOST_CONTACTED_IPS -> {
                        startDomainConnectionsActivity(appConnection, DomainConnectionsActivity.InputType.IP)
                    }
                    SummaryStatisticsType.MOST_BLOCKED_IPS -> {
                        startDomainConnectionsActivity(appConnection, DomainConnectionsActivity.InputType.IP, true)
                    }
                    SummaryStatisticsType.MOST_CONTACTED_COUNTRIES -> {
                        startDomainConnectionsActivity(appConnection, DomainConnectionsActivity.InputType.FLAG)
                    }
                }
    }
    private fun calculatePercentage(item: AppConnection): Int {
        val current = (log2(progressValue(item)) * PERCENTAGE_MULTIPLIER).toInt()
        var max = current
        for (other in snapshot().items) {
            val v = progressValue(other)
            if (v > 0.0) {
                val pv = (log2(v) * PERCENTAGE_MULTIPLIER).toInt()
                if (pv > max) {
                    max = pv
                }
            }
        }
        return if (max == 0) 0 else (current * PERCENTAGE_MULTIPLIER / max)
    }
    private fun progressValue(item: AppConnection): Double {
        return if (type == SummaryStatisticsType.MOST_CONNECTED_APPS) {
            val d = item.downloadBytes ?: 0L
            val u = item.uploadBytes ?: 0L
            (d + u).toDouble()
        } else {
            item.count.toDouble()
        }
    }
    fun setTimeCategory(timeCategory: SummaryStatisticsViewModel.TimeCategory) {
        this.timeCategory = timeCategory
    }
    override fun notifyDataset(position: Int) {
        runCatching { refresh() }
            .onFailure {
                Logger.d(LOG_TAG_DNS, "refresh after domain rule change failed: ${it.message}")
            }
    }
    private suspend fun isUnknownApp(appConnection: AppConnection): Boolean {
            val appInfo = FirewallManager.getAppInfoByUid(appConnection.uid)
            return appInfo == null
        }
    private fun startDomainConnectionsActivity(appConnection: AppConnection, input: DomainConnectionsActivity.InputType, isBlocked: Boolean = false) {
            val intent = Intent(context, DomainConnectionsActivity::class.java)
            intent.putExtra(DomainConnectionsActivity.INTENT_EXTRA_TYPE, input.type)
            when (input) {
                DomainConnectionsActivity.InputType.DOMAIN -> {
                    intent.putExtra(DomainConnectionsActivity.INTENT_EXTRA_DOMAIN, appConnection.appOrDnsName)
                    intent.putExtra(DomainConnectionsActivity.INTENT_EXTRA_IS_BLOCKED, isBlocked)
                }
                DomainConnectionsActivity.InputType.ASN -> {
                    intent.putExtra(DomainConnectionsActivity.INTENT_EXTRA_ASN, appConnection.appOrDnsName)
                    intent.putExtra(DomainConnectionsActivity.INTENT_EXTRA_IS_BLOCKED, isBlocked)
                }
                DomainConnectionsActivity.InputType.FLAG -> {
                    intent.putExtra(DomainConnectionsActivity.INTENT_EXTRA_FLAG, appConnection.flag)
                }
                DomainConnectionsActivity.InputType.IP -> {
                    intent.putExtra(DomainConnectionsActivity.INTENT_EXTRA_IP, appConnection.ipAddress)
                    intent.putExtra(DomainConnectionsActivity.INTENT_EXTRA_IS_BLOCKED, isBlocked)
                }
                DomainConnectionsActivity.InputType.BLOCKLIST -> {
                    intent.putExtra(
                        DomainConnectionsActivity.INTENT_EXTRA_BLOCKLIST,
                        appConnection.appOrDnsName
                    )
                    intent.putExtra(DomainConnectionsActivity.INTENT_EXTRA_IS_BLOCKED, isBlocked)
                    if (uid != Constants.INVALID_UID) {
                        intent.putExtra(DomainConnectionsActivity.INTENT_EXTRA_UID, uid)
                    }
                }
            }
            intent.putExtra(DomainConnectionsActivity.INTENT_EXTRA_TIME_CATEGORY, timeCategory.value)
            context.startActivity(intent)
        }
    private fun startAppInfoActivity(appConnection: AppConnection) {
            val intent = Intent(context, AppInfoActivity::class.java)
            intent.putExtra(AppInfoActivity.INTENT_UID, appConnection.uid)
            context.startActivity(intent)
        }
    private fun showDomainRulesBottomSheet(appConnection: AppConnection) {
            if (context !is AppCompatActivity) {
                Logger.d(LOG_TAG_DNS, "domain rules bottom sheet needs an activity context")
                return
            }

            val bottomSheetFragment = AppDomainRulesBottomSheet()
            val bundle = Bundle()
            bundle.putInt(AppDomainRulesBottomSheet.UID, uid)
            bundle.putString(AppDomainRulesBottomSheet.DOMAIN, appConnection.appOrDnsName.orEmpty())
            bottomSheetFragment.arguments = bundle
            bottomSheetFragment.dismissListener(
                this@SummaryStatisticsAdapter,
                RecyclerView.NO_POSITION
            )
            bottomSheetFragment.show(context.supportFragmentManager, bottomSheetFragment.tag)
        }
    private fun showDnsLogs(appConnection: AppConnection) {
            if (!handleVpnState()) return

            if (appConfig.getBraveMode().isDnsActive()) {
                startActivity(NetworkLogsActivity.Tabs.DNS_LOGS.screen, appConnection.appOrDnsName)
            } else {
                Utilities.showToastUiCentered(
                    context,
                    context.getString(R.string.dns_card_latency_inactive),
                    Toast.LENGTH_SHORT
                )
            }
        }
    private fun showNetworkLogs(appConnection: AppConnection, type: SummaryStatisticsType) {
            if (!handleVpnState()) return

            if (!appConfig.getBraveMode().isFirewallActive()) {
                Utilities.showToastUiCentered(
                    context,
                    context.getString(R.string.firewall_card_text_inactive),
                    Toast.LENGTH_SHORT
                )
                return
            }

            when (type) {
                SummaryStatisticsType.MOST_CONTACTED_DOMAINS -> {
                    startActivity(
                        NetworkLogsActivity.Tabs.NETWORK_LOGS.screen,
                        appConnection.appOrDnsName
                    )
                }
                SummaryStatisticsType.MOST_BLOCKED_DOMAINS -> {
                    startActivity(
                        NetworkLogsActivity.Tabs.NETWORK_LOGS.screen,
                        appConnection.appOrDnsName
                    )
                }
                SummaryStatisticsType.MOST_CONTACTED_IPS -> {
                    startActivity(
                        NetworkLogsActivity.Tabs.NETWORK_LOGS.screen,
                        appConnection.ipAddress
                    )
                }
                SummaryStatisticsType.MOST_BLOCKED_IPS -> {
                    startActivity(
                        NetworkLogsActivity.Tabs.NETWORK_LOGS.screen,
                        appConnection.ipAddress
                    )
                }
                SummaryStatisticsType.MOST_CONTACTED_COUNTRIES -> {
                    startActivity(NetworkLogsActivity.Tabs.NETWORK_LOGS.screen, appConnection.flag)
                }
                else -> {
                    // should never happen, but just in case we'll show all logs
                    startActivity(NetworkLogsActivity.Tabs.NETWORK_LOGS.screen, appConnection.appOrDnsName)
                }
            }
        }
    private fun handleVpnState(): Boolean {
            if (persistentState.vpnEnabledLiveData.value == false) {
                Utilities.showToastUiCentered(
                    context,
                    context.getString(R.string.ssv_toast_start_rethink),
                    Toast.LENGTH_SHORT
                )
                return false
            }
            return true
        }
    private fun startActivity(screenToLoad: Int, searchParam: String?) {
            val intent = Intent(context, NetworkLogsActivity::class.java)
            intent.putExtra(Constants.VIEW_PAGER_SCREEN_TO_LOAD, screenToLoad)
            intent.putExtra(Constants.SEARCH_QUERY, searchParam.orEmpty())
            context.startActivity(intent)
        }
    private fun io(f: suspend () -> Unit) {
        (context as LifecycleOwner).lifecycleScope.launch(Dispatchers.IO) { f() }
    }
    private suspend fun uiCtx(f: suspend () -> Unit) {
        val owner = context as? LifecycleOwner ?: return

        withContext(Dispatchers.Main.immediate) {
            if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                return@withContext
            }

            f()
        }
    }
}
