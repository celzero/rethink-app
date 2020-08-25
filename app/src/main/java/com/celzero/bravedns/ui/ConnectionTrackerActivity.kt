package com.celzero.bravedns.ui

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.ConnectionTrackingAdapter
import com.celzero.bravedns.data.IPDetails
import com.celzero.bravedns.service.VpnController
import java.util.*

class ConnectionTrackerActivity : AppCompatActivity(), SearchView.OnQueryTextListener {

    private var recyclerView: RecyclerView? = null
    private lateinit var context: Context
    private var layoutManager: RecyclerView.LayoutManager? = null
    private var editSearchView : SearchView ?= null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connection_tracker)
        initView()
    }

    private fun initView() {
        context = this

        val includeView = findViewById<View>(R.id.connection_list_scroll_list)
        recyclerView = includeView.findViewById<View>(R.id.recycler_connection) as RecyclerView
        editSearchView = includeView.findViewById(R.id.connection_search)

        recyclerView!!.setHasFixedSize(true)
        layoutManager = LinearLayoutManager(this)
        recyclerView!!.setLayoutManager(layoutManager)
        adapter = ConnectionTrackingAdapter(this)
        adapter!!.reset(getHistory())
        recyclerView!!.setAdapter(adapter)

        editSearchView!!.setOnQueryTextListener(this)
        editSearchView!!.setOnClickListener{
            editSearchView!!.requestFocus()
            editSearchView!!.onActionViewExpanded()
        }
    }

    private fun getHistory(): Queue<IPDetails?>? {
        val controller = VpnController.getInstance()
        return controller!!.getIPTracker(this)!!.getRecentIPTransactions()
    }

    companion object{
        private var adapter: ConnectionTrackingAdapter? = null
        fun updateApplication(ipDetails: IPDetails){
            if(adapter != null) {
                adapter!!.add(ipDetails)
                adapter!!.notifyDataSetChanged()
            }
        }
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        adapter!!.searchData(query)
        return true
    }

    override fun onQueryTextChange(query: String?): Boolean {
        adapter!!.searchData(query)
        return true
    }

}
