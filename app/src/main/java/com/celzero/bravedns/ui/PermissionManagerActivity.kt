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

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.PermissionManagerApk
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.database.AppInfoRepository
import com.mikepenz.fastadapter.FastAdapter
import com.mikepenz.fastadapter.adapters.ItemAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class PermissionManagerActivity: AppCompatActivity(), SearchView.OnQueryTextListener{

    private lateinit var fastAdapter: FastAdapter<PermissionManagerApk>
    private lateinit var recycle : RecyclerView
    val apkList = ArrayList<PermissionManagerApk>()
    lateinit var itemAdapter: ItemAdapter<PermissionManagerApk>
    private lateinit var context : Context

    private var editSearch: SearchView? = null

    private val appInfoRepository by inject<AppInfoRepository>()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permission_manager)
        Toast.makeText(this,"Permission Manager Activity", Toast.LENGTH_LONG).show()
        initView()

        context = this

        //val task = MyAsyncAppDetailsTaskPM(this)
        //task.execute(1)

        updateAppList()

    }

    private fun initView() {

        recycle = findViewById(R.id.permission_manager_recycler_view)
        recycle.layoutManager = LinearLayoutManager(this)

        itemAdapter =  ItemAdapter()
        fastAdapter = FastAdapter.with(itemAdapter)
        editSearch = findViewById(R.id.permission_manager_search)

        editSearch!!.setOnQueryTextListener(this)

        recycle.adapter = fastAdapter
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        itemAdapter.filter(query)
        itemAdapter.itemFilter.filterPredicate = { item: PermissionManagerApk, constraint: CharSequence? ->
            item.appName?.contains(constraint.toString(), ignoreCase = true)!!
        }
        return false
    }

    private fun updateAppList() = GlobalScope.launch ( Dispatchers.Default ){
        val appList = appInfoRepository.getAppInfoAsync()
        //Log.w("DB","App list from DB Size: "+appList.size)
        appList.forEach{

            val userApk = PermissionManagerApk(packageManager.getPackageInfo(it.packageInfo,0), context)
            apkList.add(userApk)
        }
        withContext(Dispatchers.Main.immediate) {
            itemAdapter.add(apkList)
            fastAdapter.notifyDataSetChanged()
        }
    }

    override fun onQueryTextChange(newText: String?): Boolean {
        itemAdapter.filter(newText)
        itemAdapter.itemFilter.filterPredicate = { item: PermissionManagerApk, constraint: CharSequence? ->
            item.appName?.contains(constraint.toString(), ignoreCase = true)!!
        }
        return true
    }
}