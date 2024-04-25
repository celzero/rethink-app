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
import com.celzero.bravedns.databinding.ConnectionTransactionRowBinding
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.FirewallRuleset
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.bottomsheet.ConnTrackerBottomSheet
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

class ConnectionTrackerAdapter(private val context: Context) :
    PagingDataAdapter<ConnectionTracker, ConnectionTrackerAdapter.ConnectionTrackerViewHolder>(
        DIFF_CALLBACK
    ) {

    companion object {
        private val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<ConnectionTracker>() {

                override fun areItemsTheSame(
                    oldConnection: ConnectionTracker,
                    newConnection: ConnectionTracker
                ) = oldConnection.id == newConnection.id

                override fun areContentsTheSame(
                    oldConnection: ConnectionTracker,
                    newConnection: ConnectionTracker
                ) = oldConnection.id == newConnection.id
            }

        private const val MAX_BYTES = 500000 // 500 KB
        private const val MAX_TIME_TCP = 135 // seconds
        private const val MAX_TIME_UDP = 135 // seconds
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConnectionTrackerViewHolder {
        val itemBinding =
            ConnectionTransactionRowBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        return ConnectionTrackerViewHolder(itemBinding)
    }

    override fun onBindViewHolder(holder: ConnectionTrackerViewHolder, position: Int) {
        val connTracker: ConnectionTracker = getItem(position) ?: return

        holder.update(connTracker)
        holder.setTag(connTracker)
    }

    inner class ConnectionTrackerViewHolder(private val b: ConnectionTransactionRowBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun update(connTracker: ConnectionTracker) {
            displayTransactionDetails(connTracker)
            displayProtocolDetails(connTracker.port, connTracker.protocol)
            displayAppDetails(connTracker)
            displaySummaryDetails(connTracker)
            displayFirewallRulesetHint(connTracker.isBlocked, connTracker.blockedByRule)

            b.connectionParentLayout.setOnClickListener { openBottomSheet(connTracker) }
        }

        fun setTag(connTracker: ConnectionTracker) {
            b.connectionResponseTime.tag = connTracker.timeStamp
            b.root.tag = connTracker.timeStamp
        }

        private fun openBottomSheet(ct: ConnectionTracker) {
            if (context !is FragmentActivity) {
                Logger.w(LOG_TAG_UI, "err opening the connection tracker bottomsheet")
                return
            }

            val bottomSheetFragment = ConnTrackerBottomSheet()
            // see AppIpRulesAdapter.kt#openBottomSheet()
            val bundle = Bundle()
            bundle.putString(ConnTrackerBottomSheet.INSTANCE_STATE_IPDETAILS, Gson().toJson(ct))
            bottomSheetFragment.arguments = bundle
            bottomSheetFragment.show(context.supportFragmentManager, bottomSheetFragment.tag)
        }

        private fun displayTransactionDetails(connTracker: ConnectionTracker) {
            val time = Utilities.convertLongToTime(connTracker.timeStamp, TIME_FORMAT_1)
            b.connectionResponseTime.text = time
            b.connectionFlag.text = connTracker.flag

            if (connTracker.dnsQuery.isNullOrEmpty()) {
                b.connectionIpAddress.text = connTracker.ipAddress
                b.connectionDomain.visibility = View.GONE
            } else {
                b.connectionIpAddress.text = connTracker.ipAddress
                b.connectionDomain.text = connTracker.dnsQuery
                b.connectionDomain.visibility = View.VISIBLE
                // marquee is not working for the textview, hence the workaround.
                b.connectionDomain.isSelected = true
            }
        }

        private fun displayAppDetails(ct: ConnectionTracker) {
            io {
                uiCtx {
                    // append the usrId with app name if the usrId is not 0
                    // fixme: move the 0 to a constant
                    if (ct.usrId != 0) {
                        b.connectionAppName.text =
                            context.getString(
                                R.string.about_version_install_source,
                                ct.appName,
                                ct.usrId.toString()
                            )
                    } else {
                        b.connectionAppName.text = ct.appName
                    }

                    val apps = FirewallManager.getPackageNamesByUid(ct.uid)

                    if (apps.isEmpty()) {
                        loadAppIcon(Utilities.getDefaultIcon(context))
                        return@uiCtx
                    }

                    val count = apps.count()
                    val appName =
                        if (count > 1) {
                            context.getString(
                                R.string.ctbs_app_other_apps,
                                ct.appName,
                                (count).minus(1).toString()
                            )
                        } else {
                            ct.appName
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

        private fun displayFirewallRulesetHint(isBlocked: Boolean, ruleName: String?) {
            when {
                // hint red when blocked
                isBlocked -> {
                    b.connectionStatusIndicator.visibility = View.VISIBLE
                    b.connectionStatusIndicator.setBackgroundColor(
                        ContextCompat.getColor(context, R.color.colorRed_A400)
                    )
                }
                // hint white when whitelisted
                (FirewallRuleset.shouldShowHint(ruleName)) -> {
                    b.connectionStatusIndicator.visibility = View.VISIBLE
                    b.connectionStatusIndicator.setBackgroundColor(
                        ContextCompat.getColor(context, R.color.primaryLightColorText)
                    )
                }
                // no hints, otherwise
                else -> {
                    b.connectionStatusIndicator.visibility = View.INVISIBLE
                }
            }
        }

        private fun displaySummaryDetails(ct: ConnectionTracker) {
            val connType = ConnectionTracker.ConnType.get(ct.connType)
            b.connectionDataUsage.text = ""
            b.connectionDelay.text = ""
            if (
                ct.duration == 0 &&
                    ct.downloadBytes == 0L &&
                    ct.uploadBytes == 0L &&
                    ct.message.isEmpty()
            ) {
                var hasMinSummary = false
                if (VpnController.hasCid(ct.connId, ct.uid)) {
                    b.connectionSummaryLl.visibility = View.VISIBLE
                    b.connectionDataUsage.text = context.getString(R.string.lbl_active)
                    b.connectionDuration.text = context.getString(R.string.symbol_green_circle)
                    b.connectionDelay.text = ""
                    hasMinSummary = true
                } else {
                    b.connectionDataUsage.text = ""
                    b.connectionDuration.text =""
                }
                if (connType.isMetered()) {
                    b.connectionDelay.text = context.getString(R.string.symbol_currency)
                    hasMinSummary = true
                } else {
                    b.connectionDelay.text = ""
                }

                if (isConnectionProxied(ct.blockedByRule, ct.proxyDetails)) {
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
            val duration = getDurationInHumanReadableFormat(context, ct.duration)
            b.connectionDuration.text = context.getString(R.string.single_argument, duration)
            // add unicode for download and upload
            val download =
                context.getString(
                    R.string.symbol_download,
                    Utilities.humanReadableByteCount(ct.downloadBytes, true)
                )
            val upload =
                context.getString(
                    R.string.symbol_upload,
                    Utilities.humanReadableByteCount(ct.uploadBytes, true)
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
            if (isConnectionHeavier(ct)) {
                b.connectionDelay.text =
                    context.getString(
                        R.string.ci_desc,
                        b.connectionDelay.text,
                        context.getString(R.string.symbol_heavy)
                    )
            }
            if (isConnectionSlower(ct)) {
                b.connectionDelay.text =
                    context.getString(
                        R.string.ci_desc,
                        b.connectionDelay.text,
                        context.getString(R.string.symbol_turtle)
                    )
            }
            if (isConnectionProxied(ct.blockedByRule, ct.proxyDetails)) {
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

        private fun isConnectionProxied(ruleName: String?, proxyDetails: String): Boolean {
            if (ruleName == null) return false
            val rule = FirewallRuleset.getFirewallRule(ruleName) ?: return false
            val proxy = ProxyManager.isIpnProxy(proxyDetails)
            return FirewallRuleset.isProxied(rule) || proxy
        }

        private fun isConnectionHeavier(ct: ConnectionTracker): Boolean {
            return ct.downloadBytes + ct.uploadBytes > MAX_BYTES
        }

        private fun isConnectionSlower(ct: ConnectionTracker): Boolean {
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
