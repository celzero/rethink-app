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
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.LinearLayoutManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.PermissionManagerApk
import com.celzero.bravedns.database.AppInfoRepository
import com.celzero.bravedns.databinding.ActivityPermissionManagerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class PermissionManagerActivity : AppCompatActivity(R.layout.activity_permission_manager),
                                  SearchView.OnQueryTextListener {
    private val b by viewBinding(ActivityPermissionManagerBinding::bind)

    //private lateinit var fastAdapter: FastAdapter<PermissionManagerApk>
    private val apkList = ArrayList<PermissionManagerApk>()

    //private lateinit var itemAdapter: ItemAdapter<PermissionManagerApk>
    private lateinit var context: Context

    private val appInfoRepository by inject<AppInfoRepository>()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initView()
        context = this
        updateAppList()
    }

    private fun initView() {

        b.permissionManagerRecyclerView.layoutManager = LinearLayoutManager(this)
        b.permissionManagerSearch.setOnQueryTextListener(this)
        /*
        itemAdapter = ItemAdapter()
        fastAdapter = FastAdapter.with(itemAdapter)

        b.permissionManagerRecyclerView.adapter = fastAdapter*/
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        /* itemAdapter.filter(query)
         itemAdapter.itemFilter.filterPredicate = { item: PermissionManagerApk, constraint: CharSequence? ->
             item.appName?.contains(constraint.toString(), ignoreCase = true)!!
         }*/
        return false
    }

    private fun updateAppList() = GlobalScope.launch(Dispatchers.Default) {
        val appList = appInfoRepository.getAppInfoAsync()
        appList.forEach {
            val userApk = PermissionManagerApk(packageManager.getPackageInfo(it.packageInfo, 0),
                                               context)
            apkList.add(userApk)
        }
        /*withContext(Dispatchers.Main.immediate) {
            itemAdapter.add(apkList)
            fastAdapter.notifyDataSetChanged()
        }*/
    }

    override fun onQueryTextChange(newText: String?): Boolean {
        /*itemAdapter.filter(newText)
        itemAdapter.itemFilter.filterPredicate = { item: PermissionManagerApk, constraint: CharSequence? ->
            item.appName?.contains(constraint.toString(), ignoreCase = true)!!
        }*/
        return true
    }
}
