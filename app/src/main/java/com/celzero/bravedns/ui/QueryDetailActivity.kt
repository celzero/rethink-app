package com.celzero.bravedns.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatSpinner
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.QueryAdapter
import com.celzero.bravedns.net.doh.Transaction
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
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

    private lateinit var topLayoutRL : RelativeLayout
    private lateinit var urlSpinner : AppCompatSpinner


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_query_detail)

        initView()

    }

    private fun initView() {

        context = this
        val includeView = findViewById<View>(R.id.query_list_scroll_list)
        // Set up the recycler
        recyclerView = includeView.findViewById<View>(R.id.recycler_query) as RecyclerView
        loadingIllustration = includeView.findViewById(R.id.query_progressBarHolder)
        topLayoutRL = includeView.findViewById(R.id.settings_rl)

        latencyTxt = includeView.findViewById(R.id.latency_txt)
        //ipDetailTxt = includeView.findViewById(R.id.resolver_ip)
        queryCountTxt = includeView.findViewById(R.id.total_queries_txt)
        urlSpinner = includeView.findViewById<AppCompatSpinner>(R.id.setting_url_spinner)

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

        val urlName = resources.getStringArray(R.array.cloudflare_name)
        val urlValues = resources.getStringArray(R.array.cloudflare_url)
        if (urlSpinner != null) {
            val adapter = ArrayAdapter(context,
                android.R.layout.simple_spinner_dropdown_item, urlName)

            urlSpinner.adapter = adapter
            val url = PersistentState.getServerUrl(context)
            if (url != null) {
                urlSpinner.setSelection(getIndex(url))
            }else
                urlSpinner.setSelection(3)

            urlSpinner.onItemSelectedListener = object :
                AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>,
                                            view: View, position: Int, id: Long) {
                    if(position == 3) {
                        val snackbar = Snackbar.make(view, "Ads and Tracker Filters (coming soon)", Snackbar.LENGTH_LONG).setAction("SUBSCRIBE") {
                            val intent = Intent(context, FaqWebViewActivity::class.java)
                            intent.putExtra("url","https://www.bravedns.com/#subsec")
                            startActivity(intent)
                        }
                        snackbar.show()
                        //Toast.makeText(context, "Ads and Tracker Filters (coming soon)", Toast.LENGTH_LONG).show()
                        urlSpinner.setSelection(getIndex(PersistentState.getServerUrl(context)!!))
                    }else
                        PersistentState.setServerUrl(context, urlValues[position])
                }

                override fun onNothingSelected(parent: AdapterView<*>) {
                    // write code to perform some action
                    PersistentState.setServerUrl(context, url)
                }
            }


        }
        /*if(controller!!.getTracker(this)!!.recentTransactions.size > 0)
            ipDetailTxt.setText("Resolver IP :"+controller!!.getTracker(this)!!.recentTransactions.poll().serverIp)
        else
            ipDetailTxt.setText("Resolver IP : NA")*/
        //dohTxt.setText("DoH : "+PersistentState.getServerUrl(this))
        latencyTxt.setText("Latency: "+PersistentState.getMedianLatency(this)+"ms")
        queryCountTxt.setText("Lifetime Queries: "+PersistentState.getNumOfReq(this))
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
        return 0
    }


    override fun onDestroy() {

        super.onDestroy()
    }

}
