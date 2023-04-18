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
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.databinding.ConnectionTransactionRowBinding
import com.celzero.bravedns.glide.GlideApp
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.FirewallRuleset
import com.celzero.bravedns.ui.ConnTrackerBottomSheetFragment
import com.celzero.bravedns.util.Constants.Companion.TIME_FORMAT_1
import com.celzero.bravedns.util.KnownPorts
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_UI
import com.celzero.bravedns.util.Protocol
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.getIcon
import com.google.gson.Gson
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
                ) = oldConnection == newConnection
            }
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
            displayFirewallRulesetHint(connTracker.isBlocked, connTracker.blockedByRule)

            b.connectionParentLayout.setOnClickListener { openBottomSheet(connTracker) }
        }

        fun setTag(connTracker: ConnectionTracker) {
            b.connectionResponseTime.tag = connTracker.timeStamp
            b.root.tag = connTracker.timeStamp
        }

        private fun openBottomSheet(ct: ConnectionTracker) {
            if (context !is FragmentActivity) {
                Log.wtf(LOG_TAG_UI, "Error opening the connection tracker bottomsheet")
                return
            }

            val bottomSheetFragment = ConnTrackerBottomSheetFragment()
            // see AppIpRulesAdapter.kt#openBottomSheet()
            val bundle = Bundle()
            bundle.putString(
                ConnTrackerBottomSheetFragment.INSTANCE_STATE_IPDETAILS,
                Gson().toJson(ct)
            )
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
            b.connectionAppName.text = ct.appName

            val apps = FirewallManager.getPackageNamesByUid(ct.uid)

            if (apps.isNullOrEmpty()) {
                loadAppIcon(Utilities.getDefaultIcon(context))
                return
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

        private fun loadAppIcon(drawable: Drawable?) {
            GlideApp.with(context)
                .load(drawable)
                .error(Utilities.getDefaultIcon(context))
                .into(b.connectionAppIcon)
        }
    }
}
