package com.celzero.bravedns.viewmodel

import android.content.Context
import androidx.arch.core.util.Function
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations
import androidx.lifecycle.ViewModel
import androidx.paging.PagedList
import androidx.paging.toLiveData
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.database.ConnectionTracker


class ConnectionTrackerViewModel : ViewModel() {

    companion object{
        lateinit var contextVal : Context
        fun setContext(context: Context){
            this.contextVal = context
        }
    }

    private val mDb = AppDatabase.invoke(contextVal.applicationContext)
    private val connectionTrackerDAO = mDb.connectionTrackerDAO()

    private var filteredList : MutableLiveData<String> = MutableLiveData()

    init {
        filteredList.value = ""
    }

    var connectionTrackerList = Transformations.switchMap<String, PagedList<ConnectionTracker>>(
                filteredList, (Function<String, LiveData<PagedList<ConnectionTracker>>> { input ->
                    if (input.isBlank()) {
                        connectionTrackerDAO.getConnectionTrackerLiveData().toLiveData(pageSize = 50)
                    } else if(input!!.equals("isFilter")){
                        connectionTrackerDAO.getConnectionBlockedConnections().toLiveData(pageSize = 50)
                    }else {
                        connectionTrackerDAO.getConnectionTrackerByName("%$input%").toLiveData(50)
                    }

                } as Function<String, LiveData<PagedList<ConnectionTracker>>>)!!

            )

    fun setFilter(filter: String?) {
        filteredList.value = filter
    }

    fun setFilterBlocked(filter: String){
        filteredList.value = filter
    }

}