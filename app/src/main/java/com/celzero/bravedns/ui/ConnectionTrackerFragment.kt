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
package com.celzero.bravedns.ui

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.ConnectionTrackerAdapter
import com.celzero.bravedns.database.ConnectionTrackerDAO
import com.celzero.bravedns.databinding.ActivityConnectionTrackerBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import com.celzero.bravedns.viewmodel.ConnectionTrackerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * Connection Tracker - Network Monitor.
 * All the network activities are captured and stored in the database.
 * Live data is used to fetch the network details from the database.
 * Database table name - ConnectionTracker.
 */
class ConnectionTrackerFragment : Fragment(R.layout.activity_connection_tracker), SearchView.OnQueryTextListener {
    private val b by viewBinding(ActivityConnectionTrackerBinding::bind)

    private var layoutManager: RecyclerView.LayoutManager? = null
    private var recyclerAdapter: ConnectionTrackerAdapter? = null
    private val viewModel: ConnectionTrackerViewModel by viewModel()
    private var filterValue: String = ""
    private var checkedItem = 1

    private val connectionTrackerDAO by inject<ConnectionTrackerDAO>()
    private val persistentState by inject<PersistentState>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
    }

    companion object {
        fun newInstance() = ConnectionTrackerFragment()
    }

    private fun initView() {
        val includeView = b.connectionListScrollList

        if (persistentState.logsEnabled) {
            includeView.connectionListLogsDisabledTv.visibility = View.GONE
            includeView.connectionCardViewTop.visibility = View.VISIBLE

            includeView.recyclerConnection.setHasFixedSize(true)
            layoutManager = LinearLayoutManager(requireContext())
            includeView.recyclerConnection.layoutManager = layoutManager

            recyclerAdapter = ConnectionTrackerAdapter(requireContext())
            viewModel.connectionTrackerList.observe(viewLifecycleOwner, androidx.lifecycle.Observer(recyclerAdapter!!::submitList))
            includeView.recyclerConnection.adapter = recyclerAdapter
            //recyclerView!!.setItemViewCacheSize(100)
        } else {
            includeView.connectionListLogsDisabledTv.visibility = View.VISIBLE
            includeView.connectionCardViewTop.visibility = View.GONE
        }


        includeView.connectionSearch.setOnQueryTextListener(this)
        includeView.connectionSearch.setOnClickListener {
            includeView.connectionSearch.requestFocus()
            includeView.connectionSearch.onActionViewExpanded()
        }

        includeView.connectionFilterIcon.setOnClickListener {
            showDialogForFilter()
        }

        includeView.connectionDeleteIcon.setOnClickListener {
            showDialogForDelete()
        }


    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        viewModel.setFilter(query!!, filterValue)
        return true
    }

    override fun onQueryTextChange(query: String?): Boolean {
        viewModel.setFilter(query!!, filterValue)
        return true
    }

    private fun showDialogForFilter() {

        val singleItems = arrayOf(getString(R.string.filter_network_blocked_connections), getString(R.string.filter_network_all_connections))

        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle("Select filter")

        // Single-choice items (initialized with checked item)
        builder.setSingleChoiceItems(singleItems, checkedItem) { dialog, which ->
            // Respond to item chosen
            filterValue = if (which == 0) ":isFilter"
            else ""
            checkedItem = which
            if (HomeScreenActivity.GlobalVariable.DEBUG) Log.d(LOG_TAG, "Filter Option selected: $filterValue")
            viewModel.setFilterBlocked(filterValue)
            dialog.dismiss()
        }
        builder.show()

    }


    private fun showDialogForDelete() {
        val builder = AlertDialog.Builder(requireContext())
        //set title for alert dialog
        builder.setTitle(R.string.conn_track_clear_logs_title)
        //set message for alert dialog
        builder.setMessage(R.string.conn_track_clear_logs_message)
        builder.setIcon(android.R.drawable.ic_dialog_alert)
        builder.setCancelable(true)
        //performing positive action
        builder.setPositiveButton("Delete logs") { _, _ ->
            GlobalScope.launch(Dispatchers.IO) {
                connectionTrackerDAO.clearAllData()
            }
        }

        //performing negative action
        builder.setNegativeButton("Cancel") { _, _ ->
        }
        // Create the AlertDialog
        val alertDialog: AlertDialog = builder.create()
        // Set other dialog properties
        alertDialog.setCancelable(true)
        alertDialog.show()
    }
}
