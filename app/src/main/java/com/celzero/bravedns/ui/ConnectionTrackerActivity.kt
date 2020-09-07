package com.celzero.bravedns.ui

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.ConnectionTrackerAdapter
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.viewmodel.ConnectionTrackerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class ConnectionTrackerActivity : AppCompatActivity(), SearchView.OnQueryTextListener {

    private var recyclerView: RecyclerView? = null
    private lateinit var context: Context
    private var layoutManager: RecyclerView.LayoutManager? = null
    private var editSearchView : SearchView ?= null
    private lateinit var filterIcon : ImageView
    private lateinit var deleteIcon : ImageView
    private var recyclerAdapter : ConnectionTrackerAdapter ?= null
    private val viewModel: ConnectionTrackerViewModel by viewModels()
    private lateinit var filterValue : String
    private var checkedItem = 1

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
        filterIcon = includeView.findViewById(R.id.connection_filter_icon)
        deleteIcon = includeView.findViewById(R.id.connection_delete_icon)

        recyclerView!!.setHasFixedSize(true)
        layoutManager = LinearLayoutManager(this)
        recyclerView!!.setLayoutManager(layoutManager)

        ConnectionTrackerViewModel.setContext(this)

        recyclerAdapter = ConnectionTrackerAdapter(this)

        viewModel.connectionTrackerList.observe(this, androidx.lifecycle.Observer(recyclerAdapter!!::submitList))

        recyclerView!!.adapter = recyclerAdapter

        editSearchView!!.setOnQueryTextListener(this)
        editSearchView!!.setOnClickListener{
            editSearchView!!.requestFocus()
            editSearchView!!.onActionViewExpanded()
        }

        filterIcon.setOnClickListener{
            showDialogForFilter()
        }

        deleteIcon.setOnClickListener{
            showDialogForDelete()
        }
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        viewModel.setFilter(query!!)
        return true
    }

    override fun onQueryTextChange(query: String?): Boolean {
        viewModel.setFilter(query!!)
        return true
    }

    private fun showDialogForFilter(){

        val singleItems = arrayOf("Blocked connections", "All connections")

        val builder = AlertDialog.Builder(context)
        builder.setTitle("Select filter")
        /*builder.setNeutralButton("cancel") { dialog, which ->
            // Respond to neutral button press
        }
        builder.setPositiveButton("ok") { dialog, which ->

        }*/
        // Single-choice items (initialized with checked item)
        builder.setSingleChoiceItems(singleItems, checkedItem) { dialog, which ->
            // Respond to item chosen
            filterValue = if (which == 0)
                "isFilter"
            else
                ""
            checkedItem = which
            if (HomeScreenActivity.GlobalVariable.DEBUG) Log.d("BraveDNS", "Filter Option selected: $filterValue")
            viewModel.setFilterBlocked(filterValue)
            dialog.dismiss()
        }
        builder.show()

    }


    private fun showDialogForDelete() {
        val builder = AlertDialog.Builder(context)
        //set title for alert dialog
        builder.setTitle(R.string.conn_track_clear_logs_title)
        //set message for alert dialog
        builder.setMessage(R.string.conn_track_clear_logs_message)
        builder.setIcon(android.R.drawable.ic_dialog_alert)
        builder.setCancelable(true)
        //performing positive action
        builder.setPositiveButton("Clear") { dialogInterface, which ->
            GlobalScope.launch(Dispatchers.IO) {
                val mDb = AppDatabase.invoke(ConnectionTrackerViewModel.contextVal.applicationContext)
                val connectionTrackerDAO = mDb.connectionTrackerDAO()
                connectionTrackerDAO.clearAllData()
            }
        }

        //performing negative action
        builder.setNegativeButton("Cancel") { dialogInterface, which ->
        }
        // Create the AlertDialog
        val alertDialog: AlertDialog = builder.create()
        // Set other dialog properties
        alertDialog.setCancelable(true)
        alertDialog.show()
        /*val positiveButton: Button = alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val negativeButton: Button = alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE)
        //val neutralButton: Button = alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL)

        // Change the alert dialog buttons text and background color
        positiveButton.setTextColor(Color.parseColor("#ff408100"))

        negativeButton.setTextColor(Color.parseColor("#deffffff"))*/

        /*neutralButton.setTextColor(Color.parseColor("#FF1B5AAC"))
        neutralButton.setBackgroundColor(Color.parseColor("#FFD9E9FF"))*/


    }

}
