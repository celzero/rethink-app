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

import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.database.MergedConnectionLog
import com.celzero.bravedns.database.toConnectionTracker
import com.celzero.bravedns.database.toRethinkLog
import com.celzero.bravedns.databinding.ListItemConnTrackBinding
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.FirewallRuleset
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.bottomsheet.ConnTrackerBottomSheet
import com.celzero.bravedns.util.Constants.Companion.EMPTY_PACKAGE_NAME
import com.celzero.bravedns.util.Constants.Companion.TIME_FORMAT_1
import com.celzero.bravedns.util.Constants.Companion.TIME_FORMAT_5
import com.celzero.bravedns.util.KnownPorts
import com.celzero.bravedns.util.Protocol
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.getDurationInHumanReadableFormat
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.getDefaultIcon
import com.celzero.bravedns.util.Utilities.getIcon
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.Locale

class ConnectionLogAdapter(private val context: Context) :
    PagingDataAdapter<MergedConnectionLog, ConnectionLogAdapter.ConnectionLogViewHolder>(
        DIFF_CALLBACK
    ) {

    // Per-uid cache of package names, invalidated on each new page submission.
    private val packageNameCache = Collections.synchronizedMap(HashMap<Int, List<String>>())

    companion object {
        private val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<MergedConnectionLog>() {

                override fun areItemsTheSame(
                    old: MergedConnectionLog,
                    new: MergedConnectionLog
                ): Boolean {
                    return old.id == new.id && old.source == new.source
                }

                override fun areContentsTheSame(
                    old: MergedConnectionLog,
                    new: MergedConnectionLog
                ): Boolean {
                    return old == new
                }
            }

        private const val MAX_BYTES = 500000 // 500 KB
        private const val MAX_TIME_TCP = 135 // seconds
        private const val MAX_TIME_UDP = 135 // seconds
        private const val NO_USER_ID = 0
        private const val RTT_SHORT_THRESHOLD_MS = 20 // milliseconds
        private const val TAG = "ConnLogAdapter"

        const val DNS_IP_TEMPLATE_V4 = "10.111.222.3"
        const val DNS_IP_TEMPLATE_V6 = "fd66:f83a:c650::3"
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConnectionLogViewHolder {
        val itemBinding =
            ListItemConnTrackBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        return ConnectionLogViewHolder(itemBinding)
    }

    override fun onBindViewHolder(holder: ConnectionLogViewHolder, position: Int) {
        val log: MergedConnectionLog? = getItem(position)

        if (log == null) {
            holder.clear()
            return
        }
        holder.update(log)
        holder.setTag(log)
    }

    inner class ConnectionLogViewHolder(private val b: ListItemConnTrackBinding) :
        RecyclerView.ViewHolder(b.root) {

        private var bindingScope: CoroutineScope? = null

        private fun cancelBinding() {
            bindingScope?.cancel()
            bindingScope = null
        }

        /** Launches [block] on IO in [bindingScope]. Cancelling [bindingScope]
         *  via [cancelBinding] will cancel all active children. */
        private fun launchBinding(block: suspend CoroutineScope.() -> Unit): Job? {
            val owner = context as? LifecycleOwner ?: return null
            if (bindingScope == null) {
                val parentJob = owner.lifecycleScope.coroutineContext[Job]
                bindingScope = CoroutineScope(Dispatchers.IO + SupervisorJob(parentJob))
            }
            return bindingScope?.launch(block = block)
        }

        fun clear() {
            cancelBinding()
            b.connectionResponseTime.text = ""
            b.connectionFlag.text = ""
            b.connectionIpAddress.text = ""
            b.connectionDomain.text = ""
            b.connectionAppName.text = ""
            b.connectionAppIcon.setImageDrawable(null)
            b.connectionDataUsage.text = ""
            b.connectionDelay.text = ""
            b.connectionStatusIndicator.visibility = View.INVISIBLE
            b.connectionSummaryLl.visibility = View.GONE
        }

        fun update(log: MergedConnectionLog) {
            cancelBinding()

            displayTransactionDetails(log)
            displayProtocolDetails(log.port, log.protocol)
            displayAppDetails(log)
            displaySummaryDetails(log)
            val blocked = if (log.blockedByRule == FirewallRuleset.RULE12.id) {
                log.proxyDetails.isEmpty()
            } else {
                log.isBlocked
            }
            val rule = if (log.blockedByRule == FirewallRuleset.RULE12.id && log.proxyDetails.isEmpty()) {
                FirewallRuleset.RULE18.id
            } else {
                log.blockedByRule
            }
            displayFirewallRulesetHint(blocked, rule)

            b.connectionParentLayout.setOnClickListener { openBottomSheet(log) }
        }

        fun setTag(log: MergedConnectionLog) {
            b.connectionResponseTime.tag = log.timeStamp
            b.root.tag = log.timeStamp
        }

        private fun openBottomSheet(log: MergedConnectionLog) {
            if (context !is FragmentActivity) {
                Logger.w(LOG_TAG_UI, "$TAG err opening the connection log bottomsheet")
                return
            }

            Logger.vv(LOG_TAG_UI, "$TAG show bottom sheet for ${log.appName}")
            val bottomSheetFragment = ConnTrackerBottomSheet()
            val bundle = Bundle()
            val json = if (log.isConnectionTracker()) {
                Gson().toJson(log.toConnectionTracker())
            } else {
                Gson().toJson(log.toRethinkLog())
            }
            bundle.putString(ConnTrackerBottomSheet.INSTANCE_STATE_IPDETAILS, json)
            bottomSheetFragment.arguments = bundle
            bottomSheetFragment.show(context.supportFragmentManager, bottomSheetFragment.tag)
        }

        private fun displayTransactionDetails(log: MergedConnectionLog) {
            val time = if (DEBUG) {
                Utilities.convertLongToTime(log.timeStamp, TIME_FORMAT_5)
            } else {
                Utilities.convertLongToTime(log.timeStamp, TIME_FORMAT_1)
            }
            b.connectionResponseTime.text = time
            b.connectionFlag.text = log.flag

            if (log.ipAddress == DNS_IP_TEMPLATE_V4 || log.ipAddress == DNS_IP_TEMPLATE_V6) {
                b.connectionIpAddress.text = context.getString(R.string.dns_mode_info_title)
            } else {
                b.connectionIpAddress.text = log.ipAddress
            }

            if (log.dnsQuery.isNullOrEmpty()) {
                b.connectionDomain.visibility = View.GONE
            } else {
                b.connectionDomain.text = log.dnsQuery
                b.connectionDomain.visibility = View.VISIBLE
                b.connectionDomain.isSelected = true
            }
        }

        private fun displayAppDetails(log: MergedConnectionLog) {
            launchBinding {
                val apps = packageNameCache.getOrPut(log.uid) {
                    FirewallManager.getPackageNamesByUid(log.uid)
                }
                val count = apps.count()
                val pkgName = log.packageName ?: ""

                val appName = when {
                    log.usrId != NO_USER_ID -> context.getString(
                        R.string.about_version_install_source,
                        log.appName,
                        log.usrId.toString()
                    )

                    count > 1 -> context.getString(
                        R.string.ctbs_app_other_apps,
                        log.appName,
                        "${count - 1}"
                    )

                    else -> log.appName
                }

                withContext(Dispatchers.Main.immediate) {
                    if (!isActive) return@withContext
                    b.connectionAppName.text = appName
                    val icon = if (apps.isEmpty() || pkgName.isEmpty() || pkgName == EMPTY_PACKAGE_NAME) {
                        getDefaultIcon(context)
                    } else {
                        getIcon(context, apps[0])
                    }
                    b.connectionAppIcon.setImageDrawable(icon)
                }
            }
        }

        private fun displayProtocolDetails(port: Int, proto: Int) {
            if (Protocol.UDP.protocolType != proto && Protocol.TCP.protocolType != proto) {
                b.connLatencyTxt.text = Protocol.getProtocolName(proto).name
                return
            }

            val resolvedPort = KnownPorts.resolvePort(port)
            b.connLatencyTxt.text =
                if (port == KnownPorts.HTTPS_PORT && proto == Protocol.UDP.protocolType) {
                    context.getString(R.string.connection_http3)
                } else if (resolvedPort != KnownPorts.PORT_VAL_UNKNOWN) {
                    resolvedPort.uppercase(Locale.ROOT)
                } else {
                    Protocol.getProtocolName(proto).name
                }
        }

        private fun displayFirewallRulesetHint(isBlocked: Boolean, ruleName: String?) {
            when {
                isBlocked -> {
                    b.connectionStatusIndicator.visibility = View.VISIBLE
                    val isError = FirewallRuleset.isProxyError(ruleName)
                    if (isError) {
                        b.connectionStatusIndicator.setBackgroundColor(
                            UIUtils.fetchColor(context, R.attr.chipTextNeutral)
                        )
                    } else {
                        b.connectionStatusIndicator.setBackgroundColor(
                            ContextCompat.getColor(context, R.color.colorRed_A400)
                        )
                    }
                }

                (FirewallRuleset.shouldShowHint(ruleName)) -> {
                    b.connectionStatusIndicator.visibility = View.VISIBLE
                    b.connectionStatusIndicator.setBackgroundColor(
                        ContextCompat.getColor(context, R.color.primaryLightColorText)
                    )
                }

                else -> {
                    b.connectionStatusIndicator.visibility = View.INVISIBLE
                }
            }
        }

        private fun displaySummaryDetails(log: MergedConnectionLog) {
            launchBinding {
                val hasCid = VpnController.hasCid(log.connId, log.uid)
                val connType = ConnectionTracker.ConnType.get(log.connType)

                withContext(Dispatchers.Main.immediate) {
                    if (!isActive) return@withContext
                    b.connectionDataUsage.text = ""
                    b.connectionDelay.text = ""
                    if (
                        log.duration == 0 &&
                        log.downloadBytes == 0L &&
                        log.uploadBytes == 0L &&
                        log.message.isEmpty()
                    ) {
                        var hasMinSummary = false
                        if (hasCid) {
                            b.connectionSummaryLl.visibility = View.VISIBLE
                            b.connectionDataUsage.text = context.getString(R.string.lbl_active)
                            b.connectionDuration.text = context.getString(R.string.symbol_green_circle)
                            b.connectionDelay.text = ""
                            hasMinSummary = true
                        } else {
                            b.connectionDataUsage.text = ""
                            b.connectionDuration.text = ""
                        }
                        if (connType.isMetered()) {
                            b.connectionDelay.text = context.getString(R.string.symbol_currency)
                            hasMinSummary = true
                        } else {
                            b.connectionDelay.text = ""
                        }

                        if (isRpnProxy(log.rpid)) {
                            b.connectionSummaryLl.visibility = View.VISIBLE
                            b.connectionDelay.text =
                                context.getString(
                                    R.string.ci_desc,
                                    b.connectionDelay.text,
                                    context.getString(R.string.symbol_sparkle)
                                )
                        } else if (isConnectionProxied(log.blockedByRule, log.proxyDetails)) {
                            b.connectionSummaryLl.visibility = View.VISIBLE
                            b.connectionDelay.text =
                                context.getString(
                                    R.string.ci_desc,
                                    b.connectionDelay.text,
                                    context.getString(R.string.symbol_key)
                                )
                            hasMinSummary = true
                        }
                        if (!hasMinSummary) {
                            b.connectionSummaryLl.visibility = View.GONE
                        }
                        return@withContext
                    }

                    b.connectionSummaryLl.visibility = View.VISIBLE
                    val duration = getDurationInHumanReadableFormat(context, log.duration)
                    b.connectionDuration.text = context.getString(R.string.single_argument, duration)
                    val download =
                        context.getString(
                            R.string.symbol_download,
                            Utilities.humanReadableByteCount(log.downloadBytes, true)
                        )
                    val upload =
                        context.getString(
                            R.string.symbol_upload,
                            Utilities.humanReadableByteCount(log.uploadBytes, true)
                        )
                    b.connectionDataUsage.text = context.getString(R.string.two_argument, upload, download)
                    b.connectionDelay.text = ""
                    if (connType.isMetered()) {
                        b.connectionDelay.text =
                            context.getString(
                                R.string.ci_desc,
                                b.connectionDelay.text,
                                context.getString(R.string.symbol_currency)
                            )
                    }
                    if (isConnectionHeavier(log)) {
                        b.connectionDelay.text =
                            context.getString(
                                R.string.ci_desc,
                                b.connectionDelay.text,
                                context.getString(R.string.symbol_heavy)
                            )
                    }
                    if (isConnectionSlower(log)) {
                        b.connectionDelay.text =
                            context.getString(
                                R.string.ci_desc,
                                b.connectionDelay.text,
                                context.getString(R.string.symbol_turtle)
                            )
                    }
                    if (isRpnProxy(log.rpid)) {
                        b.connectionSummaryLl.visibility = View.VISIBLE
                        b.connectionDelay.text =
                            context.getString(
                                R.string.ci_desc,
                                b.connectionDelay.text,
                                context.getString(R.string.symbol_sparkle)
                            )
                    } else if (containsRelayProxy(log.rpid)) {
                        b.connectionDelay.text =
                            context.getString(
                                R.string.ci_desc,
                                b.connectionDelay.text,
                                context.getString(R.string.symbol_bunny)
                            )
                    } else if (isConnectionProxied(log.blockedByRule, log.proxyDetails)) {
                        b.connectionDelay.text =
                            context.getString(
                                R.string.ci_desc,
                                b.connectionDelay.text,
                                context.getString(R.string.symbol_key)
                            )
                    }

                    if (isRoundTripShorter(log.synack, log.isBlocked)) {
                        b.connectionDelay.text =
                            context.getString(
                                R.string.ci_desc,
                                b.connectionDelay.text,
                                context.getString(R.string.symbol_rocket)
                            )
                    }

                    if (b.connectionDelay.text.isEmpty() && b.connectionDataUsage.text.isEmpty()) {
                        b.connectionSummaryLl.visibility = View.GONE
                    }

                }
            }

        }

        private fun isRoundTripShorter(rtt: Long, blocked: Boolean): Boolean {
            return rtt in 1..RTT_SHORT_THRESHOLD_MS && !blocked
        }

        private fun containsRelayProxy(rpid: String): Boolean {
            return rpid.isNotEmpty()
        }

        private fun isConnectionProxied(ruleName: String?, proxyDetails: String): Boolean {
            if (ruleName == null) return false
            val rule = FirewallRuleset.getFirewallRule(ruleName) ?: return false
            val proxy = ProxyManager.isNotLocalAndRpnProxy(proxyDetails)
            val isProxyError = FirewallRuleset.isProxyError(ruleName)
            return (FirewallRuleset.isProxied(rule) && proxyDetails.isNotEmpty() && proxy) || isProxyError
        }

        private fun isRpnProxy(pid: String): Boolean {
            return pid.isNotEmpty() && ProxyManager.isRpnProxy(pid)
        }

        private fun isConnectionHeavier(log: MergedConnectionLog): Boolean {
            return log.downloadBytes + log.uploadBytes > MAX_BYTES
        }

        private fun isConnectionSlower(log: MergedConnectionLog): Boolean {
            return (log.protocol == Protocol.UDP.protocolType && log.duration > MAX_TIME_UDP) ||
                (log.protocol == Protocol.TCP.protocolType && log.duration > MAX_TIME_TCP)
        }

        private fun loadAppIcon(drawable: Drawable?) {
            b.connectionAppIcon.setImageDrawable(drawable)
        }
    }
}
