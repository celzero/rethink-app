package com.celzero.bravedns.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.PersistableBundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.QueryAdapter
import com.celzero.bravedns.net.doh.Transaction
import com.celzero.bravedns.service.InternalNames
import com.celzero.bravedns.service.VpnController
import java.util.*

class QueryDetailActivity  : AppCompatActivity() {


    private var recyclerView: RecyclerView? = null

    private var layoutManager: RecyclerView.LayoutManager? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main_recycler)
        // Set up the recycler
        recyclerView = findViewById<View>(R.id.recycler_query) as RecyclerView
        recyclerView!!.setHasFixedSize(true)
        layoutManager = LinearLayoutManager(this)
        recyclerView!!.setLayoutManager(layoutManager)
        adapter = QueryAdapter(this)
        adapter!!.reset(getHistory())
        recyclerView!!.setAdapter(adapter)
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

    override fun onDestroy() {

        super.onDestroy()
    }

}
