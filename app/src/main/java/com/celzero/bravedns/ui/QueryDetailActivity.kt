package com.celzero.bravedns.ui

import android.R.attr.delay
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.*
import android.widget.*
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatSpinner
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.QueryAdapter
import com.celzero.bravedns.net.doh.Transaction
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.VpnState
import com.celzero.bravedns.util.Utilities
import com.google.android.material.snackbar.Snackbar
import java.util.*


class QueryDetailActivity  : AppCompatActivity() {


    private var recyclerView: RecyclerView? = null
    private lateinit var context : Context
    private var layoutManager: RecyclerView.LayoutManager? = null

    private lateinit var loadingIllustration : FrameLayout
    private lateinit var latencyTxt : TextView
    private lateinit var queryCountTxt : TextView
    private lateinit var currentDNSUrl : TextView

    private lateinit var topLayoutRL : RelativeLayout
    private lateinit var urlSpinner : AppCompatSpinner

    private lateinit var dnsSelectorInfoIcon: ImageView

    lateinit var urlName : Array<String>// = resources.getStringArray(R.array.cloudflare_name)
    lateinit var urlValues :Array<String> // = resources.getStringArray(R.array.cloudflare_url)
    var prevSpinnerSelection : Int = 0
    var check = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_query_detail)
        urlName = resources.getStringArray(R.array.cloudflare_name)
        urlValues = resources.getStringArray(R.array.cloudflare_url)

        initView()

    }

    private fun initView() {

        context = this
        val includeView = findViewById<View>(R.id.query_list_scroll_list)
        // Set up the recycler
        recyclerView = includeView.findViewById<View>(R.id.recycler_query) as RecyclerView
        loadingIllustration = includeView.findViewById(R.id.query_progressBarHolder)
        topLayoutRL = includeView.findViewById(R.id.query_list_rl)

        latencyTxt = includeView.findViewById(R.id.latency_txt)
        //ipDetailTxt = includeView.findViewById(R.id.resolver_ip)
        queryCountTxt = includeView.findViewById(R.id.total_queries_txt)
        urlSpinner = includeView.findViewById(R.id.setting_url_spinner)

        currentDNSUrl = includeView.findViewById(R.id.query_current_url_txt)

        dnsSelectorInfoIcon = includeView.findViewById(R.id.query_dns_info_icon)

        recyclerView!!.setHasFixedSize(true)
        layoutManager = LinearLayoutManager(this)
        recyclerView!!.setLayoutManager(layoutManager)
        adapter = QueryAdapter(this)
        adapter!!.reset(getHistory())
        recyclerView!!.setAdapter(adapter)




        val controller = VpnController.getInstance()

        val isServiceRunning = Utilities.isServiceRunning(this, BraveVPNService::class.java)
        if(!isServiceRunning){
            loadingIllustration.visibility=View.VISIBLE
            topLayoutRL.visibility = View.GONE
        }else{
            loadingIllustration.visibility=View.GONE
            topLayoutRL.visibility = View.VISIBLE
        }



        currentDNSUrl.text = PersistentState.getServerUrl(this)

        if (urlSpinner != null) {
            val adapter = ArrayAdapter(context,
                android.R.layout.simple_spinner_dropdown_item, urlName)

            urlSpinner.adapter = adapter
            val url = PersistentState.getServerUrl(context)
            if(PersistentState.getCustomURLBool(this)){
                check = 1
                urlSpinner.setSelection(3)
                currentDNSUrl.text = PersistentState.getCustomURLVal(context)
            } else if (url != null) {
                var urlIndex = getIndex(url)
                //Log.d("BraveDNS","URL : $urlIndex")
               /* if(urlIndex == -1) {
                    urlSpinner.setSelection(3)
                    check = 1
                }
                else*/
                urlSpinner.setSelection(urlIndex)
            }else {
                urlSpinner.setSelection(2)
                PersistentState.setServerUrl(context, urlValues[2])
                currentDNSUrl.text = urlValues[2]
            }

            urlSpinner.onItemSelectedListener = object :
                AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>, view: View, position: Int, id: Long) {
                    if(position == 4) {
                        val snackbar = Snackbar.make(view, "BraveDNS Resolver launching soon. Subscribe for early access.", Snackbar.LENGTH_INDEFINITE).setAction("SUBSCRIBE") {
                            val intent = Intent(context, FaqWebViewActivity::class.java)
                            intent.putExtra("url","https://www.bravedns.com/#subsec")
                            startActivity(intent)
                        }
                        snackbar.show()
                        if(PersistentState.getCustomURLBool(context)){
                            check = 1
                            prevSpinnerSelection =  3
                            urlSpinner.setSelection(3)
                            currentDNSUrl.text  = PersistentState.getCustomURLVal(context)
                        }else {
                            val index = getIndex(PersistentState.getServerUrl(context)!!)
                            urlSpinner.setSelection(index)
                            PersistentState.setCustomURLBool(context, false)
                            prevSpinnerSelection = index
                        }
                    }else if(position == 3){
                        if(check > 1) {

                            showDialogForCustomURL()
                        }
                        check += 1
                    }
                    else {
                        prevSpinnerSelection = position
                        PersistentState.setServerUrl(context, urlValues[position])
                        PersistentState.setCustomURLBool(context,false)
                        currentDNSUrl.text = urlValues[position]
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>) {
                    // write code to perform some action
                    PersistentState.setServerUrl(context, url)
                }
            }


        }

        dnsSelectorInfoIcon.setOnClickListener {
            showDialogForDNSInfo()
        }

        /*if(controller!!.getTracker(this)!!.recentTransactions.size > 0)
            ipDetailTxt.setText("Resolver IP :"+controller!!.getTracker(this)!!.recentTransactions.poll().serverIp)
        else
            ipDetailTxt.setText("Resolver IP : NA")*/
        //dohTxt.setText("DoH : "+PersistentState.getServerUrl(this))


        var timer = Handler()
        var updates: Runnable? = Runnable {
            kotlin.run {
                latencyTxt.setText("Latency: "+PersistentState.getMedianLatency(this)+"ms")
                queryCountTxt.setText("Lifetime Queries: "+PersistentState.getNumOfReq(this))
            }
        }
        timer.postDelayed(updates, 1000)



    }

    /*
            Show the Info dialog for various modes in Brave
         */
        private fun showDialogForDNSInfo() {

            val dialog = Dialog(context!!)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setTitle("Brave DNS Modes")
            dialog.setCanceledOnTouchOutside(true)
            //dialog.setCancelable(false)
            dialog.setContentView(R.layout.dialog_dns_info_custom_layout)
            val okBtn = dialog.findViewById(R.id.query_info_dialog_cancel_img) as ImageView
            okBtn.setOnClickListener {
                dialog.dismiss()
            }
            dialog.show()

        }

    private fun showDialogForCustomURL() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setTitle("Custom Server URL")
        dialog.setContentView(R.layout.dialog_set_custom_url)

        val lp = WindowManager.LayoutParams()
        lp.copyFrom(dialog.getWindow()!!.getAttributes())
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        //dialog = yourBuilder.create()
        dialog.show()
        dialog.setCanceledOnTouchOutside(false)
        dialog.getWindow()!!.setAttributes(lp)

        val applyURLBtn = dialog.findViewById(R.id.dialog_custom_url_ok_btn) as AppCompatButton
        val cancelURLBtn = dialog.findViewById(R.id.dialog_custom_url_cancel_btn) as AppCompatButton
        val customURL : EditText = dialog.findViewById(R.id.dialog_custom_url_edit_text) as EditText
        customURL.setText(PersistentState.getCustomURLVal(this), TextView.BufferType.EDITABLE)
        /*if(PersistentState.getCustomURLBool(this)){

        }
*/
        applyURLBtn.setOnClickListener{
            if(customURL.text.toString() != "" || customURL.text.toString() != "https://") {
                dialog.dismiss()
                PersistentState.setServerUrl(context, customURL.text.toString())
                setProgressDialog()
            }else{
                Toast.makeText(context, "Invalid URL", Toast.LENGTH_SHORT).show()
            }
        }
        urlSpinner.setSelection(prevSpinnerSelection)
        cancelURLBtn.setOnClickListener {
            urlSpinner.setSelection(getIndex(PersistentState.getServerUrl(context)!!))
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun checkConnection() : Boolean {
        var connectionStatus = false
        var statusId: Int
        val status: VpnState? = VpnController.getInstance()!!.getState(this)
        if (status!!.activationRequested) {
            if (status.connectionState == null) {
                if (HomeScreenActivity.GlobalVariable.firewallMode == 2) {
                    connectionStatus = true
                    statusId = R.string.status_protected
                } else {
                    //prepareAndStartDnsVpn()
                    statusId = R.string.status_waiting
                }
            } else if (status.connectionState === BraveVPNService.State.NEW) {
                statusId = R.string.status_starting
            } else if (status.connectionState === BraveVPNService.State.WORKING) {
                connectionStatus = true
                statusId = R.string.status_protected
            } else {
                // status.connectionState == ServerConnection.State.FAILING
                statusId = R.string.status_failing
            }
        } else{
            statusId = R.string.status_failing
        }
        //Log.d("BraveDNS","Status : "+resources.getString(statusId) + "--Connection Status : $connectionStatus")
        return connectionStatus
    }

    var timerHandler = Handler()
    var updater: Runnable? = null

    private fun setProgressDialog() {
        val llPadding = 10
        val ll = LinearLayout(this)
        ll.orientation = LinearLayout.HORIZONTAL
        ll.setPadding(llPadding, llPadding, llPadding, llPadding)
        ll.gravity = Gravity.CENTER
        var llParam = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        llParam.gravity = Gravity.CENTER
        ll.layoutParams = llParam
        val progressBar = ProgressBar(this)
        progressBar.isIndeterminate = true
        progressBar.setPadding(llPadding, llPadding, llPadding, llPadding)
        progressBar.layoutParams = llParam
        llParam.gravity = Gravity.CENTER
        val tvText = TextView(this)
        tvText.text = "Validating..."
        tvText.setPadding(llPadding, llPadding, llPadding, llPadding)
        tvText.textSize = 16f
        tvText.layoutParams = llParam
        ll.addView(progressBar)
        ll.addView(tvText)
        ll.setPadding(llPadding, llPadding, llPadding, llPadding)
        val builder: AlertDialog.Builder = AlertDialog.Builder(this)
        builder.setCancelable(false)
        builder.setView(ll)
        val dialog: AlertDialog = builder.create()
        dialog.show()

        var count = 0
        var connectionStatus: Boolean
        updater = Runnable {
            kotlin.run {
                connectionStatus = checkConnection()
                if (connectionStatus || count == 5) {
                    timerHandler.removeCallbacksAndMessages(updater)
                    timerHandler.removeCallbacksAndMessages(null)
                    dialog.dismiss()
                    showText(connectionStatus)
                }
                count++
                if (!connectionStatus && count <= 5) {
                    timerHandler.postDelayed(updater, 1000)
                }
             }
        }
        timerHandler.postDelayed(updater,2000)
    }

    private fun showText(connectionStatus : Boolean) {
        var isSuccess = false
        var showText = ""
        var url = PersistentState.getServerUrl(this)
        if (connectionStatus) {
        //Log.d("BraveDNS","Connection Status : $connectionStatus, url: $url, --- url2: $urlValues[2]")
            showText = "Connected to custom DNS endpoint."
            isSuccess = true
            check = 1
            urlSpinner.setSelection(3)
            PersistentState.setCustomURLBool(this, true)
            PersistentState.setCustomURLVal(this,url!!)
            if(url == urlValues[2]) {
                showText = "Could not resolve custom DNS endpoint. Reset to default."
                urlSpinner.setSelection(2)
                currentDNSUrl.text = urlValues[2]
                isSuccess = false
                PersistentState.setCustomURLBool(this, false)
            }
            currentDNSUrl.text = url
        }
        else {
            //Log.d("BraveDNS","Connection Status : $connectionStatus, url: $url, --- url2: $urlValues[2]")
            showText = "Could not resolve custom DNS endpoint. Reset to default."
            urlSpinner.setSelection(2)
            //Log.d("BraveDNS","URL : "+urlValues[2])
            PersistentState.setServerUrl(this, urlValues[2])
            currentDNSUrl.text = urlValues[2]
            isSuccess = false
            PersistentState.setCustomURLBool(this, false)
            //urlSpinner.setSelection()
        }
        if(isSuccess)
            Toast.makeText(this, showText, Toast.LENGTH_LONG).show()
        else {
            val snackbar = Snackbar.make(topLayoutRL, showText, Snackbar.LENGTH_LONG).setAction("RETRY") {
                showDialogForCustomURL()
            }
            snackbar.show()
        }
    }


    companion object {
        private var adapter: QueryAdapter? = null

        fun updateStatsDisplay(numRequests: Long, transaction: Transaction) {
            showTransaction(transaction)
        }

        fun showTransaction(transaction: Transaction) {
            if(adapter != null) {
                adapter!!.add(transaction)
                adapter!!.notifyDataSetChanged()
            }
        }
    }

    private fun getHistory(): Queue<Transaction?>? {
        val controller = VpnController.getInstance()
        return controller!!.getTracker(this)!!.recentTransactions
    }

    private fun getNumRequests(): Long {
        val controller: VpnController ?= VpnController.getInstance()
        return controller!!.getTracker(this)!!.getNumRequests()
    }
    private fun getIndex(myString: String): Int {
        val urlValues = resources.getStringArray(R.array.cloudflare_url)

        for(i in urlValues.indices){
            if(urlValues[i] == myString)
                return i
        }
        return -1
    }


    override fun onDestroy() {

        super.onDestroy()
    }

}
