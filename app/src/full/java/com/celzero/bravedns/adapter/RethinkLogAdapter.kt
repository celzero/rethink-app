/*
Copyright 2023 RethinkDNS and its authors

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

import Logger
import Logger.LOG_TAG_UI
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
import com.bumptech.glide.Glide
import com.celzero.bravedns.R
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.database.RethinkLog
import com.celzero.bravedns.databinding.ListItemConnTrackBinding
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.bottomsheet.ConnTrackerBottomSheet
import com.celzero.bravedns.ui.bottomsheet.RethinkLogBottomSheet
import com.celzero.bravedns.util.Constants.Companion.TIME_FORMAT_1
import com.celzero.bravedns.util.KnownPorts
import com.celzero.bravedns.util.Protocol
import com.celzero.bravedns.util.UIUtils.getDurationInHumanReadableFormat
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.getIcon
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class RethinkLogAdapter(private val context: Context) :
    PagingDataAdapter<RethinkLog, RethinkLogAdapter.RethinkLogViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<RethinkLog>() {

                override fun areItemsTheSame(oldConnection: RethinkLog, newConnection: RethinkLog) =
                    oldConnection.id == newConnection.id

                override fun areContentsTheSame(
                    oldConnection: RethinkLog,
                    newConnection: RethinkLog
                ) = oldConnection == newConnection
            }

        private const val MAX_BYTES = 500000 // 500 KB
        private const val MAX_TIME_TCP = 135 // seconds
        private const val MAX_TIME_UDP = 135 // seconds

        const val DNS_IP_TEMPLATE_V4 = "10.111.222.3"
        const val DNS_IP_TEMPLATE_V6 = "fd66:f83a:c650::3"
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RethinkLogViewHolder {
        val itemBinding =
            ListItemConnTrackBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        return RethinkLogViewHolder(itemBinding)
    }

    override fun onBindViewHolder(holder: RethinkLogViewHolder, position: Int) {
        val log: RethinkLog = getItem(position) ?: return

        holder.update(log)
        holder.setTag(log)
    }

    inner class RethinkLogViewHolder(private val b: ListItemConnTrackBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun update(log: RethinkLog) {
            displayTransactionDetails(log)
            displayProtocolDetails(log.port, log.protocol)
            displayAppDetails(log)
            displaySummaryDetails(log)
            displayFirewallRulesetHint(log.isBlocked)

            b.connectionParentLayout.setOnClickListener { openBottomSheet(log) }
        }

        fun setTag(log: RethinkLog) {
            b.connectionResponseTime.tag = log.timeStamp
            b.root.tag = log.timeStamp
        }

        private fun openBottomSheet(log: RethinkLog) {
            if (context !is FragmentActivity) {
                Logger.w(LOG_TAG_UI, "err opening the connection tracker bottomsheet")
                return
            }

            val bottomSheetFragment = ConnTrackerBottomSheet()
            // see AppIpRulesAdapter.kt#openBottomSheet()
            val bundle = Bundle()
            bundle.putString(ConnTrackerBottomSheet.INSTANCE_STATE_IPDETAILS, Gson().toJson(log))
            bottomSheetFragment.arguments = bundle
            bottomSheetFragment.show(context.supportFragmentManager, bottomSheetFragment.tag)
        }

        private fun displayTransactionDetails(log: RethinkLog) {
            val time = Utilities.convertLongToTime(log.timeStamp, TIME_FORMAT_1)
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
                // marquee is not working for the textview, hence the workaround.
                b.connectionDomain.isSelected = true
            }
        }

        private fun displayAppDetails(log: RethinkLog) {
            b.connectionAppName.text = log.appName

            io {
                val apps = FirewallManager.getPackageNamesByUid(log.uid)
                uiCtx {
                    if (apps.isEmpty()) {
                        loadAppIcon(Utilities.getDefaultIcon(context))
                        return@uiCtx
                    }

                    val count = apps.count()
                    val appName =
                        if (count > 1) {
                            context.getString(
                                R.string.ctbs_app_other_apps,
                                log.appName,
                                (count).minus(1).toString()
                            )
                        } else {
                            log.appName
                        }

                    b.connectionAppName.text = appName
                    loadAppIcon(getIcon(context, apps[0], /*No app name */ ""))
                }
            }
        }

        private fun displayProtocolDetails(port: Int, proto: Int) {
            // Instead of showing the port name and protocol, now the ports are resolved with
            // known ports(reserved port and protocol identifiers).
            // https://github.com/celzero/rethink-app/issues/42 - #3 - transport + protocol.
            val resolvedPort = KnownPorts.resolvePort(port)
            // case: for UDP/443 label it as HTTP3 instead of HTTPS
            b.connLatencyTxt.text =
                if (port == KnownPorts.HTTPS_PORT && proto == Protocol.UDP.protocolType) {
                    context.getString(R.string.connection_http3)
                } else if (resolvedPort != KnownPorts.PORT_VAL_UNKNOWN) {
                    resolvedPort.uppercase(Locale.ROOT)
                } else {
                    Protocol.getProtocolName(proto).name
                }
        }

        private fun displayFirewallRulesetHint(isBlocked: Boolean) {
            when {
                // hint red when blocked
                isBlocked -> {
                    b.connectionStatusIndicator.visibility = View.VISIBLE
                    b.connectionStatusIndicator.setBackgroundColor(
                        ContextCompat.getColor(context, R.color.colorRed_A400)
                    )
                }
                // no hints, otherwise
                else -> {
                    b.connectionStatusIndicator.visibility = View.INVISIBLE
                }
            }
        }

        private fun displaySummaryDetails(log: RethinkLog) {
            val connType = ConnectionTracker.ConnType.get(log.connType)
            if (
                log.duration == 0 &&
                    log.downloadBytes == 0L &&
                    log.uploadBytes == 0L &&
                    log.message.isEmpty()
            ) {
                var hasMinSummary = false
                if (VpnController.hasCid(log.connId, log.uid)) {
                    b.connectionSummaryLl.visibility = View.VISIBLE
                    b.connectionDataUsage.text = context.getString(R.string.lbl_active)
                    b.connectionDuration.text = context.getString(R.string.symbol_green_circle)
                    b.connectionDelay.text = ""
                    hasMinSummary = true
                }
                if (connType.isMetered()) {
                    b.connectionDelay.text = context.getString(R.string.symbol_currency)
                }

                if (isConnectionProxied(log.proxyDetails)) {
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
                return
            }

            b.connectionSummaryLl.visibility = View.VISIBLE
            val duration = getDurationInHumanReadableFormat(context, log.duration)
            b.connectionDuration.text = context.getString(R.string.single_argument, duration)
            // add unicode for download and upload
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
            if (isConnectionProxied(log.proxyDetails)) {
                b.connectionDelay.text =
                    context.getString(
                        R.string.ci_desc,
                        b.connectionDelay.text,
                        context.getString(R.string.symbol_key)
                    )
            }
            if (b.connectionDelay.text.isEmpty() && b.connectionDataUsage.text.isEmpty()) {
                b.connectionSummaryLl.visibility = View.GONE
            }
        }

        private fun isConnectionProxied(proxyDetails: String): Boolean {
            return ProxyManager.isNotLocalAndRpnProxy(proxyDetails)
        }

        private fun isConnectionHeavier(ct: RethinkLog): Boolean {
            return ct.downloadBytes + ct.uploadBytes > MAX_BYTES
        }

        private fun isConnectionSlower(ct: RethinkLog): Boolean {
            return (ct.protocol == Protocol.UDP.protocolType && ct.duration > MAX_TIME_UDP) ||
                (ct.protocol == Protocol.TCP.protocolType && ct.duration > MAX_TIME_TCP)
        }

        private fun loadAppIcon(drawable: Drawable?) {
            Glide.with(context)
                .load(drawable)
                .error(Utilities.getDefaultIcon(context))
                .into(b.connectionAppIcon)
        }
    }

    private fun io(f: suspend () -> Unit) {
        (context as LifecycleOwner).lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }
}
