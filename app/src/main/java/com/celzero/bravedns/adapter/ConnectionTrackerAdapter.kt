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
import android.content.res.TypedArray
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.paging.PagedListAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.celzero.bravedns.R
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.databinding.ConnectionTransactionRowBinding
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.ui.ConnTrackerBottomSheetFragment
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import com.celzero.bravedns.util.Protocol
import com.celzero.bravedns.util.Utilities

class ConnectionTrackerAdapter(val context: Context) : PagedListAdapter<ConnectionTracker, ConnectionTrackerAdapter.ConnectionTrackerViewHolder>(DIFF_CALLBACK) {


    companion object {
        private val DIFF_CALLBACK = object :
            DiffUtil.ItemCallback<ConnectionTracker>() {

            override fun areItemsTheSame(oldConnection: ConnectionTracker, newConnection: ConnectionTracker)
                = oldConnection.id == newConnection.id

            override fun areContentsTheSame(oldConnection: ConnectionTracker, newConnection: ConnectionTracker) = oldConnection == newConnection
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConnectionTrackerViewHolder {
        val itemBinding = ConnectionTransactionRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ConnectionTrackerViewHolder(itemBinding)
    }

    override fun onBindViewHolder(holder: ConnectionTrackerViewHolder, position: Int) {
        val connTracker: ConnectionTracker = getItem(position) ?: return
        holder.update(connTracker, position)
    }


    inner class ConnectionTrackerViewHolder(private val b: ConnectionTransactionRowBinding) : RecyclerView.ViewHolder(b.root) {

        fun update(connTracker: ConnectionTracker, position: Int) {
            val time = Utilities.convertLongToTime(connTracker.timeStamp)
            b.connectionResponseTime.text = time
            b.connectionFlag.text = connTracker.flag
            b.connectionIpAddress.text = connTracker.ipAddress
            b.connLatencyTxt.text = connTracker.port.toString()
            b.connectionAppName.text = connTracker.appName
            b.connectionType.text = Protocol.getProtocolName(connTracker.protocol).name
            when {
                connTracker.isBlocked -> {
                    b.connectionStatusIndicator.visibility = View.VISIBLE
                    b.connectionStatusIndicator.setBackgroundColor(ContextCompat.getColor(context, R.color.colorRed_A400))
                }
                connTracker.blockedByRule.equals(BraveVPNService.BlockedRuleNames.RULE7.ruleName) -> {
                    b.connectionStatusIndicator.visibility = View.VISIBLE
                    b.connectionStatusIndicator.setBackgroundColor(ContextCompat.getColor(context, R.color.dividerColor))
                }
                else -> {
                    b.connectionStatusIndicator.visibility = View.INVISIBLE
                }
            }
            if (connTracker.appName != "Unknown") {
                try {
                    val appArray = context.packageManager.getPackagesForUid(connTracker.uid)
                    val appCount = (appArray?.size)?.minus(1)
                    if (appArray?.size!! > 2) {
                        b.connectionAppName.text = "${connTracker.appName} + $appCount other apps"
                    } else if (appArray.size == 2) {
                        b.connectionAppName.text = "${connTracker.appName} + $appCount other app"
                    }
                    Glide.with(context).load(context.packageManager.getApplicationIcon(appArray[0]!!)).error(AppCompatResources.getDrawable(context, R.drawable.default_app_icon)).into(b.connectionAppIcon)
                } catch (e: Exception) {
                    Glide.with(context).load(AppCompatResources.getDrawable(context, R.drawable.default_app_icon)).error(AppCompatResources.getDrawable(context, R.drawable.default_app_icon)).into(b.connectionAppIcon)
                    Log.e(LOG_TAG, "Package Not Found - " + e.message, e)
                }
            } else {
                Glide.with(context).load(AppCompatResources.getDrawable(context, R.drawable.default_app_icon)).error(AppCompatResources.getDrawable(context, R.drawable.default_app_icon)).into(b.connectionAppIcon)
            }

            b.connectionParentLayout.setOnClickListener {
                b.connectionParentLayout.isEnabled = false
                val bottomSheetFragment = ConnTrackerBottomSheetFragment(context, connTracker)
                val frag = context as FragmentActivity
                bottomSheetFragment.show(frag.supportFragmentManager, bottomSheetFragment.tag)
                b.connectionParentLayout.isEnabled = true
            }

        }

        private fun fetchTextColor(attr: Int): Int {
            val attributeFetch = if (attr == R.color.dividerColor) {
                R.attr.dividerColor
            } else {
                R.attr.accentGood
            }
            val typedValue = TypedValue()
            val a: TypedArray = context.obtainStyledAttributes(typedValue.data, intArrayOf(attributeFetch))
            val color = a.getColor(0, 0)
            a.recycle()
            return color
        }


    }

}


