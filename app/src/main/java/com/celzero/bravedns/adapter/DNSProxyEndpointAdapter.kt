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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.os.CountDownTimer
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.paging.PagedListAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.database.DNSProxyEndpoint
import com.celzero.bravedns.database.DNSProxyEndpointRepository
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.QueryTracker
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.appList
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import com.celzero.bravedns.util.UIUpdateInterface
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import settings.Settings

class DNSProxyEndpointAdapter(private val context: Context,
                              private val dnsProxyEndpointRepository: DNSProxyEndpointRepository,
                              private val persistentState:PersistentState,
                              private val queryTracker: QueryTracker,
                              val listener: UIUpdateInterface) : PagedListAdapter<DNSProxyEndpoint, DNSProxyEndpointAdapter.DNSProxyEndpointViewHolder>(DIFF_CALLBACK) {
    private var PROXY_TYPE_INTERNAL: String
    private var PROXY_TYPE_EXTERNAL: String

    init {
        PROXY_TYPE_INTERNAL = "Internal"
        PROXY_TYPE_EXTERNAL = "External"
    }

    companion object {
        private val DIFF_CALLBACK = object :
            DiffUtil.ItemCallback<DNSProxyEndpoint>() {
            // Concert details may have changed if reloaded from the database,
            // but ID is fixed.
            override fun areItemsTheSame(oldConnection: DNSProxyEndpoint, newConnection: DNSProxyEndpoint) = oldConnection.id == newConnection.id

            override fun areContentsTheSame(oldConnection: DNSProxyEndpoint, newConnection: DNSProxyEndpoint) = oldConnection == newConnection
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DNSProxyEndpointViewHolder {
        val v: View = LayoutInflater.from(parent.context).inflate(
            R.layout.dns_proxy_list_item,
            parent, false
        )
        //v.setBackgroundColor(context.getColor(R.color.colorPrimary))
        return DNSProxyEndpointViewHolder(v)
    }

    override fun onBindViewHolder(holder: DNSProxyEndpointViewHolder, position: Int) {
        val dnsProxyEndpoint: DNSProxyEndpoint? = getItem(position)
        holder.update(dnsProxyEndpoint)
    }


    inner class DNSProxyEndpointViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        // Overall view
        private var rowView: View? = null

        // Contents of the condensed view
        private var urlNameTxt: TextView
        private var urlExplanationTxt: TextView
        private var imageAction: ImageView
        private var checkBox: CheckBox


        init {
            rowView = itemView
            urlNameTxt = itemView.findViewById(R.id.dns_proxy_list_url_name)
            urlExplanationTxt = itemView.findViewById(R.id.dns_proxy_list_url_explanation)
            imageAction = itemView.findViewById(R.id.dns_proxy_list_action_image)
            checkBox = itemView.findViewById(R.id.dns_proxy_list_check_image)
        }

        fun update(dnsProxyEndpoint: DNSProxyEndpoint?) {
            if (dnsProxyEndpoint != null) {
                //if (DEBUG) Log.d(LOG_TAG, "Update - dohName ==> ${dnsProxyEndpoint.proxyType}")
                urlNameTxt.text = dnsProxyEndpoint.proxyName
                if (dnsProxyEndpoint.isSelected) {
                    if (dnsProxyEndpoint.proxyAppName == "Nobody") {
                        urlExplanationTxt.text = "Forwarding to ${dnsProxyEndpoint.proxyIP}:${dnsProxyEndpoint.proxyPort}, Nobody"
                    } else {
                        Log.d(LOG_TAG,"Proxy : ${dnsProxyEndpoint.proxyAppName}")
                        val appNameInfo = appList[dnsProxyEndpoint.proxyAppName]
                        urlExplanationTxt.text = "Forwarding to ${dnsProxyEndpoint.proxyIP}:${dnsProxyEndpoint.proxyPort}, ${appNameInfo?.appName}"
                    }
                } else {
                    if(dnsProxyEndpoint.proxyAppName == "Nobody") {
                        urlExplanationTxt.text = "${dnsProxyEndpoint.proxyIP}:${dnsProxyEndpoint.proxyPort}, Nobody"
                    }else{
                        Log.d(LOG_TAG,"Proxy : ${dnsProxyEndpoint.proxyAppName}")
                        val appNameInfo = appList[dnsProxyEndpoint.proxyAppName]
                        urlExplanationTxt.text = "${dnsProxyEndpoint.proxyIP}:${dnsProxyEndpoint.proxyPort}, ${appNameInfo?.appName}"
                    }
                }
                checkBox.isChecked = dnsProxyEndpoint.isSelected
                if (dnsProxyEndpoint.isCustom && !dnsProxyEndpoint.isSelected) {
                    imageAction.setImageDrawable(context.getDrawable(R.drawable.ic_fab_uninstall))
                } else {
                    imageAction.setImageDrawable(context.getDrawable(R.drawable.ic_fab_appinfo))
                }
                rowView?.setOnClickListener {
                    //showApplyDialog(dnsProxyEndpoint)
                    updateDNSProxyDetails(dnsProxyEndpoint)
                    checkBox.isChecked = true
                }
                imageAction.setOnClickListener {
                    showExplanationOnImageClick(dnsProxyEndpoint)
                }

                checkBox.setOnClickListener{
                    updateDNSProxyDetails(dnsProxyEndpoint)
                    checkBox.isChecked = true
                }

                //checkBox.setOn

            }

        }

        private fun showExplanationOnImageClick(dnsProxyEndpoint: DNSProxyEndpoint) {
            if (dnsProxyEndpoint.isCustom && !dnsProxyEndpoint.isSelected)
                showDialogForDelete(dnsProxyEndpoint)
            else {
                showDialogExplanation(dnsProxyEndpoint.proxyName, dnsProxyEndpoint.proxyAppName!!, dnsProxyEndpoint.proxyIP!!, dnsProxyEndpoint.proxyPort.toString())
                /*if (dnsProxyEndpoint.proxyIP.isNullOrEmpty()) {
                    showDialogExplanation(dnsProxyEndpoint.proxyName, dnsProxyEndpoint.proxyAppName!!, dnsProxyEndpoint.proxyPort.toString())
                } else {
                    showDialogExplanation(dnsProxyEndpoint.proxyName, dnsProxyEndpoint.proxyIP!!, dnsProxyEndpoint.proxyPort.toString())
                }*/
            }
        }

        private fun showDialogExplanation(title: String, appName: String, url: String, message: String) {
            val builder = AlertDialog.Builder(context)
            //set title for alert dialog
            builder.setTitle(title)
            val app = appList[appName]?.appName
            //set message for alert dialog
            if(app != null && !app.isNullOrEmpty()) {
                builder.setMessage("AppName: $app\n\nURL: $url\n\nPort: $message")
            }else{
                builder.setMessage("AppName: Nobody \n\nURL: $url\n\nPort: $message")
            }
            builder.setCancelable(true)
            //performing positive action
            builder.setPositiveButton("Ok") { dialogInterface, which ->
                dialogInterface.dismiss()
            }
            builder.setNeutralButton("Copy") { dialogInterface: DialogInterface, i: Int ->
                val clipboard: ClipboardManager? = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?
                val clip = ClipData.newPlainText("URL", url)
                clipboard?.setPrimaryClip(clip)
                Utilities.showToastInMidLayout(context, context.getString(R.string.info_dialog_copy_toast_msg), Toast.LENGTH_SHORT)
            }
            // Create the AlertDialog
            val alertDialog: AlertDialog = builder.create()
            // Set other dialog properties
            alertDialog.setCancelable(true)
            alertDialog.show()
        }

        private fun showDialogForDelete(dnsProxyEndpoint: DNSProxyEndpoint) {
            val builder = AlertDialog.Builder(context)
            //set title for alert dialog
            builder.setTitle(R.string.dns_proxy_remove_dialog_title)
            //set message for alert dialog
            builder.setMessage(R.string.dns_proxy_remove_dialog_message)
            builder.setIcon(android.R.drawable.ic_dialog_alert)
            builder.setCancelable(true)
            //performing positive action
            builder.setPositiveButton("Delete") { dialogInterface, which ->
                GlobalScope.launch(Dispatchers.IO) {
                    if (dnsProxyEndpoint != null) {
                        dnsProxyEndpointRepository.deleteDNSProxyEndpoint(dnsProxyEndpoint.id)
                    }
                }

                object : CountDownTimer(500, 500) {
                    override fun onTick(millisUntilFinished: Long) {
                    }

                    override fun onFinish() {
                        listener.updateUIFromAdapter(4)
                    }
                }.start()


                Toast.makeText(context, R.string.dns_proxy_remove_success, Toast.LENGTH_SHORT).show()
            }

            //performing negative action
            builder.setNegativeButton("Cancel") { dialogInterface, which ->
            }
            // Create the AlertDialog
            val alertDialog: AlertDialog = builder.create()
            // Set other dialog properties
            alertDialog.setCancelable(true)
            alertDialog.show()
        }

       /* private fun showApplyDialog(dnsProxyEndpoint: DNSProxyEndpoint) {
            val dialog = Dialog(context)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setContentView(R.layout.dialog_bottom_apply_changes)
            val window: Window = dialog.window!!
            val wlp: WindowManager.LayoutParams = window.attributes
            wlp.width = WindowManager.LayoutParams.WRAP_CONTENT
            wlp.gravity = Gravity.BOTTOM
            wlp.flags = wlp.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
            window.attributes = wlp
            dialog.setCancelable(false)

            val applyURLBtn = dialog.findViewById(R.id.dialog_bottom_apply_changes_ok_btn) as AppCompatButton
            val cancelURLBtn = dialog.findViewById(R.id.dialog_bottom_apply_changes_cancel_btn) as AppCompatButton

            applyURLBtn.setOnClickListener {
                updateDNSProxyDetails(dnsProxyEndpoint)
                dialog.dismiss()
            }

            cancelURLBtn.setOnClickListener {
                dialog.dismiss()
            }
            // Set other dialog properties
            dialog.show()

        }*/

        private fun updateDNSProxyDetails(dnsProxyEndpoint: DNSProxyEndpoint) {
            dnsProxyEndpoint.isSelected = true
            dnsProxyEndpointRepository.removeConnectionStatus()
            dnsProxyEndpointRepository.updateAsync(dnsProxyEndpoint)

            object : CountDownTimer(500, 500) {
                override fun onTick(millisUntilFinished: Long) {
                }

                override fun onFinish() {
                    notifyDataSetChanged()
                    queryTracker.reinitializeQuantileEstimator()
                }
            }.start()

            // Capture traffic for particular IP. Based on the setting. need to change the DNS
            // mode.
            if (dnsProxyEndpoint.proxyType == PROXY_TYPE_INTERNAL) {
                HomeScreenActivity.GlobalVariable.appMode?.setDNSMode(Settings.DNSModeProxyIP)
            } else {
                HomeScreenActivity.GlobalVariable.appMode?.setDNSMode(Settings.DNSModeProxyIP)
            }
            listener.updateUIFromAdapter(3)
            persistentState.dnsType = 3
            persistentState.connectionModeChange = dnsProxyEndpoint.proxyIP!!
            persistentState.dnsProxyIDChange = dnsProxyEndpoint.id
            //mDb.close()
        }
    }


}