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
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.paging.PagedListAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.celzero.bravedns.R
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.ui.ConnTrackerBottomSheetFragment
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import com.celzero.bravedns.util.Protocol
import com.celzero.bravedns.util.Utilities

class ConnectionTrackerAdapter(val context : Context) : PagedListAdapter<ConnectionTracker, ConnectionTrackerAdapter.ConnectionTrackerViewHolder>(DIFF_CALLBACK) {


    companion object {
        private val DIFF_CALLBACK = object :
            DiffUtil.ItemCallback<ConnectionTracker>() {
            // Concert details may have changed if reloaded from the database,
            // but ID is fixed.
            override fun areItemsTheSame(oldConnection: ConnectionTracker, newConnection: ConnectionTracker)
                = oldConnection.id == newConnection.id

            override fun areContentsTheSame(oldConnection: ConnectionTracker, newConnection: ConnectionTracker)
                = oldConnection == newConnection
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConnectionTrackerViewHolder {
       val v: View = LayoutInflater.from(parent.context).inflate(
                       R.layout.connection_transaction_row,
                       parent, false
                   )
        return ConnectionTrackerViewHolder(v)
    }

    override fun onBindViewHolder(holder: ConnectionTrackerViewHolder, position: Int) {
        val connTracker: ConnectionTracker? = getItem(position)
        holder.update(connTracker,position)
    }



    inner class ConnectionTrackerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        // Overall view
        private var rowView: View? = null

        private var parentView: LinearLayout? = null

        // Contents of the condensed view
        private var timeView: TextView? = null
        private var flagView: TextView? = null

        private var fqdnView: TextView? = null
        private var ipView: TextView? = null
        private var latencyTxt: TextView? = null
        private var queryLayoutLL: LinearLayout? = null
        private var connectionType: TextView? = null
        private var appIcon: ImageView? = null
        private var connectionIndicator: TextView? = null

        init {
            rowView = itemView
            parentView = itemView.findViewById(R.id.connection_parent_layout)
            timeView = itemView.findViewById(R.id.connection_response_time)
            flagView = itemView.findViewById(R.id.connection_flag)
            fqdnView = itemView.findViewById(R.id.connection_app_name)
            ipView = itemView.findViewById(R.id.connection_ip_address)
            latencyTxt = itemView.findViewById(R.id.conn_latency_txt)
            connectionType = itemView.findViewById(R.id.connection_type)
            queryLayoutLL = itemView.findViewById(R.id.connection_screen_ll)
            appIcon = itemView.findViewById(R.id.connection_app_icon)
            connectionIndicator = itemView.findViewById(R.id.connection_status_indicator)
        }

        fun update(connTracker: ConnectionTracker?, position: Int) {
            if(connTracker != null){
                val time = Utilities.convertLongToTime(connTracker.timeStamp)
                timeView!!.text = time
                flagView!!.text = connTracker.flag
                ipView!!.text = connTracker.ipAddress
                latencyTxt!!.text = connTracker.port.toString()
                fqdnView!!.text = connTracker.appName
                connectionType!!.text = Protocol.getProtocolName(connTracker.protocol).name
                if (connTracker.isBlocked) {
                    connectionIndicator!!.visibility = View.VISIBLE
                    connectionIndicator!!.setBackgroundColor(ContextCompat.getColor(context, R.color.colorRed_A400))
                }else if(connTracker.blockedByRule.equals(BraveVPNService.BlockedRuleNames.RULE7.ruleName)){
                    connectionIndicator!!.visibility = View.VISIBLE
                    connectionIndicator!!.setBackgroundColor(ContextCompat.getColor(context, R.color.dividerColor))
                }
                else {
                    connectionIndicator!!.visibility = View.INVISIBLE
                }
                if (connTracker.appName != "Unknown") {
                    try {
                        val appArray = context.packageManager.getPackagesForUid(connTracker.uid)
                        val appCount = (appArray?.size)?.minus(1)
                        if (appArray?.size!! > 2) {
                            fqdnView!!.text = "${connTracker.appName} + $appCount other apps"
                        } else if (appArray.size == 2) {
                            fqdnView!!.text = "${connTracker.appName} + $appCount other app"
                        }
                        Glide.with(context)
                            .load(context.packageManager.getApplicationIcon(appArray[0]!!))
                            .error(AppCompatResources.getDrawable(context, R.drawable.default_app_icon))
                            .into(appIcon!!)
                    } catch (e: Exception) {
                        Glide.with(context)
                            .load(AppCompatResources.getDrawable(context, R.drawable.default_app_icon))
                            .error(AppCompatResources.getDrawable(context, R.drawable.default_app_icon))
                            .into(appIcon!!)
                        Log.e(LOG_TAG, "Package Not Found - " + e.message, e)
                    }
                }else{
                    Glide.with(context)
                        .load(AppCompatResources.getDrawable(context, R.drawable.default_app_icon))
                        .error(AppCompatResources.getDrawable(context, R.drawable.default_app_icon))
                        .into(appIcon!!)
                }

                parentView!!.setOnClickListener {
                    parentView!!.isEnabled = false
                    val bottomSheetFragment = ConnTrackerBottomSheetFragment(context, connTracker)
                    val frag = context as FragmentActivity
                    bottomSheetFragment.show(frag.supportFragmentManager, bottomSheetFragment.tag)
                    parentView!!.isEnabled = true
                }

            }

        }


    }

}


