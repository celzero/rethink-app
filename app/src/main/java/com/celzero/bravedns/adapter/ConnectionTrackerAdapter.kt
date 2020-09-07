package com.celzero.bravedns.adapter

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.paging.PagedListAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.ui.ConnectionTrackerActivity
import com.celzero.bravedns.util.Protocol
import com.celzero.bravedns.util.Utilities

class ConnectionTrackerAdapter(val activity : ConnectionTrackerActivity) : PagedListAdapter<ConnectionTracker, ConnectionTrackerAdapter.ConnectionTrackerViewHolder>(DIFF_CALLBACK) {


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
                var time = Utilities.convertLongToTime(connTracker.timeStamp)
                timeView!!.text = time
                flagView!!.text = connTracker.flag
                ipView!!.text = connTracker.ipAddress
                latencyTxt!!.text = connTracker.port.toString()
                fqdnView!!.text = connTracker.appName
                connectionType!!.text = Protocol.getProtocolName(connTracker.protocol).name
                if (connTracker.isBlocked)
                    connectionIndicator!!.visibility = View.VISIBLE
                else
                    connectionIndicator!!.visibility = View.INVISIBLE
                if (connTracker.appName != "Unknown") {
                    try {
                        var appArray = activity.packageManager.getPackagesForUid(connTracker!!.uid)
                        appIcon!!.setImageDrawable(activity.packageManager.getApplicationIcon(appArray?.get(0)!!))
                    } catch (e: Exception) {
                        appIcon!!.setImageDrawable(activity.getDrawable(R.drawable.default_app_icon))
                        Log.e("BraveDNS", "Package Not Found - " + e.message, e)
                    }
                }

                /*parentView!!.setOnClickListener {
                    val bottomSheetFragment = ConnTrackerBottomSheetFragment(activity, connTracker)
                    val frag = activity as FragmentActivity
                    bottomSheetFragment.show(frag.supportFragmentManager, bottomSheetFragment.tag)
                }*/
            }

        }

    }

}


