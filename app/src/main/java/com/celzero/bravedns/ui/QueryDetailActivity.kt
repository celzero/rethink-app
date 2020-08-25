package com.celzero.bravedns.ui

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatSpinner
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.QueryAdapter
import com.celzero.bravedns.net.dns.DnsPacket
import com.celzero.bravedns.net.doh.Transaction
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.PersistentState.Companion.getMedianLatency
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.VpnState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.median50
import com.celzero.bravedns.util.Utilities
import com.google.android.material.snackbar.Snackbar
import java.net.InetAddress
import java.net.MalformedURLException
import java.net.ProtocolException
import java.net.URL
import java.util.*


class QueryDetailActivity  : AppCompatActivity() {

    private val SPINNER_VALUE_NO_FILTER = 0
    private val SPINNER_VALUE_FAMILY = 1
    private val SPINNER_VALUE_FREE_BRAVE_DNS = 2
    private val SPINNER_VALUE_CUSTOM_FILTER = 3
    private val SPINNER_VALUE_BRAVE_COMING_SOON = 4




    private var recyclerView: RecyclerView? = null
    private lateinit var context: Context
    private var layoutManager: RecyclerView.LayoutManager? = null

    private lateinit var loadingIllustration: FrameLayout
    private lateinit var latencyTxt: TextView
    private lateinit var queryCountTxt: TextView
    private lateinit var currentDNSUrl: TextView

    private lateinit var topLayoutRL: RelativeLayout
    private lateinit var urlSpinner: AppCompatSpinner

    private lateinit var dnsSelectorInfoIcon: ImageView

    lateinit var urlName: Array<String>
    lateinit var urlValues: Array<String>
    var prevSpinnerSelection: Int = 2
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


        val isServiceRunning = Utilities.isServiceRunning(this, BraveVPNService::class.java)
        if (!isServiceRunning) {
            loadingIllustration.visibility = View.VISIBLE
            topLayoutRL.visibility = View.GONE
        } else {
            loadingIllustration.visibility = View.GONE
            topLayoutRL.visibility = View.VISIBLE
        }

        currentDNSUrl.text = PersistentState.getServerUrl(this)

        if (urlSpinner != null) {
            val adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item, urlName
            )

            urlSpinner.adapter = adapter
            val url = PersistentState.getServerUrl(context)

            if (PersistentState.getCustomURLBool(this)) {
                check = 1
                urlSpinner.setSelection(SPINNER_VALUE_CUSTOM_FILTER)
                currentDNSUrl.text = PersistentState.getCustomURLVal(context)
            } else if (url != null) {
                var urlIndex = getIndex(url)
                urlSpinner.setSelection(urlIndex)
            } else {
                urlSpinner.setSelection(SPINNER_VALUE_FREE_BRAVE_DNS)
                PersistentState.setServerUrl(context, urlValues[SPINNER_VALUE_FREE_BRAVE_DNS])
                currentDNSUrl.text = urlValues[SPINNER_VALUE_FREE_BRAVE_DNS]
            }

            urlSpinner.onItemSelectedListener = object :
                AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>,
                    view: View,
                    position: Int,
                    id: Long
                ) {

                    if (position == SPINNER_VALUE_BRAVE_COMING_SOON) {
                        val snackbar = Snackbar.make(
                            view, resources.getString(R.string.brave_launching_soon),
                            Snackbar.LENGTH_INDEFINITE
                        ).setAction("SUBSCRIBE") {
                            val intent = Intent(context, FaqWebViewActivity::class.java)
                            intent.putExtra("url", "https://www.bravedns.com/#subsec")
                            startActivity(intent)
                        }
                        snackbar.show()
                        if (PersistentState.getCustomURLBool(context)) {
                            check = 1
                            prevSpinnerSelection = SPINNER_VALUE_CUSTOM_FILTER
                            urlSpinner.setSelection(SPINNER_VALUE_CUSTOM_FILTER)
                            currentDNSUrl.text = PersistentState.getCustomURLVal(context)
                        } else {
                            val index = getIndex(PersistentState.getServerUrl(context)!!)
                            urlSpinner.setSelection(index)
                            PersistentState.setCustomURLBool(context, false)
                            prevSpinnerSelection = index
                        }
                    } else if (position == SPINNER_VALUE_CUSTOM_FILTER) {
                        if (check > 1) {
                            showDialogForCustomURL()
                        }
                        check += 1
                    } else {
                        prevSpinnerSelection = position
                        PersistentState.setServerUrl(context, urlValues[position])
                        PersistentState.setCustomURLBool(context, false)
                        currentDNSUrl.text = urlValues[position]
                    }

                    object : CountDownTimer(1000, 500) {
                        override fun onTick(millisUntilFinished: Long) {
                            urlSpinner.isEnabled = false
                        }

                        override fun onFinish() {
                            urlSpinner.isEnabled = true
                        }
                    }.start()

                }

                override fun onNothingSelected(parent: AdapterView<*>) {
                    PersistentState.setServerUrl(context, url)
                }
            }
        }

        dnsSelectorInfoIcon.setOnClickListener {
            showDialogForDNSInfo()
        }

        median50.observe(this, androidx.lifecycle.Observer {
            latencyTxt.setText("Latency: "+median50.value.toString() + "ms")
        })

        //latencyTxt.setText("Latency: " + getMedianLatency(this) + "ms")
        queryCountTxt.setText("Lifetime Queries: " + PersistentState.getNumOfReq(this))

    }

    /**
     * Info dialog shows various modes in Brave
    */
    private fun showDialogForDNSInfo() {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setTitle("BraveDNS")
        dialog.setCanceledOnTouchOutside(true)
        dialog.setContentView(R.layout.dialog_dns_info_custom_layout)
        val okBtn = dialog.findViewById(R.id.query_info_dialog_cancel_img) as ImageView
        okBtn.setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    /**
     * Shows dialog for custom DNS endpoint configuration
     * If entered DNS end point is valid, then the DNS queries are forwarded to that end point
     * else, it will revert back to default end point
     */
    private fun showDialogForCustomURL() {
        var retryAttempts = 0
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setTitle("Custom Server URL")
        dialog.setContentView(R.layout.dialog_set_custom_url)

        val lp = WindowManager.LayoutParams()
        lp.copyFrom(dialog.getWindow()!!.getAttributes())
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        dialog.show()
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.getWindow()!!.setAttributes(lp)

        val applyURLBtn = dialog.findViewById(R.id.dialog_custom_url_ok_btn) as AppCompatButton
        val cancelURLBtn = dialog.findViewById(R.id.dialog_custom_url_cancel_btn) as AppCompatButton
        val customURL: EditText = dialog.findViewById(R.id.dialog_custom_url_edit_text) as EditText
        val progressBar: ProgressBar =
            dialog.findViewById(R.id.dialog_custom_url_loading) as ProgressBar
        val errorTxt: TextView =
            dialog.findViewById(R.id.dialog_custom_url_failure_text) as TextView

        customURL.setText(PersistentState.getCustomURLVal(this), TextView.BufferType.EDITABLE)

        applyURLBtn.setOnClickListener {
            var url = customURL.text.toString()

            var timerHandler = Handler()
            var updater: Runnable? = null
            if (checkUrl(url)) {
                errorTxt.visibility = View.GONE
                applyURLBtn.visibility = View.INVISIBLE
                cancelURLBtn.visibility = View.INVISIBLE
                progressBar.visibility = View.VISIBLE
                PersistentState.setServerUrl(context, customURL.text.toString())

                var count = 0
                var connectionStatus: Boolean
                updater = Runnable {
                    kotlin.run {
                        connectionStatus = checkConnection()
                        if (connectionStatus || count >= 3) {
                            timerHandler.removeCallbacksAndMessages(updater)
                            timerHandler.removeCallbacksAndMessages(null)
                            if (connectionStatus) {
                                runOnUiThread{
                                    currentDNSUrl.setText(url)
                                    urlSpinner.setSelection(SPINNER_VALUE_CUSTOM_FILTER)
                                    dialog.dismiss()
                                    Toast.makeText(context, resources.getString(R.string.custom_url_connection_successfull), Toast.LENGTH_SHORT).show()
                                }

                                if (retryAttempts > 0) {
                                    if (VpnController.getInstance() != null) {
                                        VpnController.getInstance()!!.stop(context)
                                        VpnController.getInstance()!!.start(context)
                                    }
                                }
                                PersistentState.setCustomURLBool(this, true)
                                PersistentState.setCustomURLVal(this, url)

                            } else {
                                retryAttempts += 1
                                errorTxt.setText(resources.getText(R.string.custom_url_error_host_failed))
                                errorTxt.visibility = View.VISIBLE
                                cancelURLBtn.visibility = View.VISIBLE
                                applyURLBtn.visibility = View.VISIBLE
                                progressBar.visibility = View.INVISIBLE
                            }
                        }
                        count++
                        if (!connectionStatus && count <= 3) {
                            timerHandler.postDelayed(updater, 1000)
                        }
                    }
                }
                timerHandler.postDelayed(updater, 2000)
            } else {
                errorTxt.setText(resources.getString(R.string.custom_url_error_invalid_url))
                errorTxt.visibility = View.VISIBLE
                cancelURLBtn.visibility = View.VISIBLE
                applyURLBtn.visibility = View.VISIBLE
                progressBar.visibility = View.INVISIBLE
            }
        }

        cancelURLBtn.setOnClickListener {
            urlSpinner.setSelection(SPINNER_VALUE_FREE_BRAVE_DNS)
            currentDNSUrl.setText(urlValues[SPINNER_VALUE_FREE_BRAVE_DNS])
            PersistentState.setServerUrl(context, urlValues[SPINNER_VALUE_FREE_BRAVE_DNS])
            if (VpnController.getInstance() != null && retryAttempts != 0) {
                VpnController.getInstance()!!.stop(context)
                VpnController.getInstance()!!.start(context)
            }
            PersistentState.setCustomURLBool(this, false)
            dialog.dismiss()
        }
        dialog.show()
    }

    // Check that the URL is a plausible DOH server: https with a domain, a path (at least "/"),
    // and no query parameters or fragment.
    private fun checkUrl(url: String): Boolean {
        return try {
            val parsed = URL(url)
            parsed.protocol == "https" && !parsed.host.isEmpty() &&
                    !parsed.path
                        .isEmpty() && parsed.query == null && parsed.ref == null
        } catch (e: MalformedURLException) {
            false
        }
    }

    private fun checkConnection(): Boolean {
        var connectionStatus = false
        val status: VpnState? = VpnController.getInstance()!!.getState(this)
        if (status!!.activationRequested) {
            if (status.connectionState == null) {
                if (HomeScreenActivity.GlobalVariable.firewallMode == 2) {
                    connectionStatus = true
                }
            } else if (status.connectionState === BraveVPNService.State.WORKING) {
                connectionStatus = true
            }
        }
        return connectionStatus
    }


    companion object {
        private var adapter: QueryAdapter? = null


        fun updateStatsDisplay(numRequests: Long, transaction: Transaction) {
            showTransaction(transaction)
        }

        private fun showTransaction(transaction: Transaction) {
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

    private fun getIndex(url: String): Int {
        val urlValues = resources.getStringArray(R.array.cloudflare_url)

        for(i in urlValues.indices){
            if(urlValues[i] == url)
                return i
        }
        return -1
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        finish()
        return super.onKeyDown(keyCode, event)
    }

    override fun onBackPressed() {
        finish()
    }

}
