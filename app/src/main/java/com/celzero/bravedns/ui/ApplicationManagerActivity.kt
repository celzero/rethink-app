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


import android.app.Activity
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.BuildConfig
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.ApplicationManagerApk
import com.celzero.bravedns.animation.ViewAnimation
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.database.AppInfoRepository
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.mikepenz.fastadapter.FastAdapter
import com.mikepenz.fastadapter.adapters.ItemAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject


class ApplicationManagerActivity : AppCompatActivity(), SearchView.OnQueryTextListener{

    private lateinit var recycle : RecyclerView
    private lateinit var itemAdapter: ItemAdapter<ApplicationManagerApk>
    private lateinit var fastAdapter: FastAdapter<ApplicationManagerApk>
    private val apkList = ArrayList<ApplicationManagerApk>()
    private lateinit var fabAddIcon : FloatingActionButton
    private lateinit var fabUninstallIcon : FloatingActionButton
    private lateinit var fabAppInfoIcon : FloatingActionButton

    private var editSearch: SearchView? = null

    private var isRotate : Boolean = false

    private val appInfoRepository by inject<AppInfoRepository>()

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_application_manager)

        initView()
        updateAppList()

    }

    private fun initView() {
        recycle = findViewById(R.id.application_manager_recycler_view)
        recycle.layoutManager = LinearLayoutManager(this)

        itemAdapter =  ItemAdapter()
        fastAdapter = FastAdapter.with(itemAdapter)
        editSearch = findViewById(R.id.am_search)

        fabAddIcon = findViewById(R.id.am_fab_add_icon)
        fabUninstallIcon = findViewById(R.id.am_fab_uninstall_icon)
        fabAppInfoIcon = findViewById(R.id.am_fab_appinfo_icon)

        editSearch!!.setOnQueryTextListener(this)

        recycle.adapter = fastAdapter

        ViewAnimation.init(fabUninstallIcon)
        ViewAnimation.init(fabAppInfoIcon)

        ApplicationManagerApk.cleatList()

        fabAddIcon.setOnClickListener {
            isRotate = ViewAnimation.rotateFab(it, !isRotate)
            if(isRotate){
                ViewAnimation.showIn(fabUninstallIcon)
                ViewAnimation.showIn(fabAppInfoIcon)
            }else{
                ViewAnimation.showOut(fabUninstallIcon)
                ViewAnimation.showOut(fabAppInfoIcon)
            }
        }

        fabUninstallIcon.setOnClickListener{
            val list = ApplicationManagerApk.getAddedList(this)
            for(app in list){
                uninstallPackage(app)
            }
        }

        fabAppInfoIcon.setOnClickListener{
            val list = ApplicationManagerApk.getAddedList(this)
            if(list.size >= 1){
                list[list.size - 1].packageName?.let { it1 -> appInfoForPackage(it1) }
            }
        }
    }

    private fun uninstallPackage(app : ApplicationManagerApk){
        val packageURI = Uri.parse("package:"+app.packageName)
        val intent = Intent(Intent.ACTION_DELETE,packageURI)
        intent.putExtra("packageName",app.packageName)
        startActivity(intent)
    }

    private fun appInfoForPackage(packageName : String){
        val activityManager : ActivityManager = getSystemService(Activity.ACTIVITY_SERVICE) as ActivityManager
        activityManager.killBackgroundProcesses(packageName)

        try {
            //Open the specific App Info page:
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            //Open the generic Apps page:
            val intent = Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
            startActivity(intent)
        }
    }


    private fun updateAppList() = GlobalScope.launch ( Dispatchers.Default ){
        val appList = appInfoRepository.getAppInfoAsync()
        appList.forEach{
            val packageInfo = packageManager.getPackageInfo(it.packageInfo,0)
            if(packageInfo.packageName != BuildConfig.APPLICATION_ID ) {
                val userApk =  ApplicationManagerApk(packageManager.getPackageInfo(it.packageInfo, 0), it.appCategory, this@ApplicationManagerActivity)
                apkList.add(userApk)
            }
        }
        withContext(Dispatchers.Main.immediate) {
            itemAdapter.add(apkList)
            fastAdapter.notifyDataSetChanged()
        }
        //mDb.close()
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        itemAdapter.filter(query)
        itemAdapter.itemFilter.filterPredicate = { item: ApplicationManagerApk, constraint: CharSequence? ->
            item.appName?.contains(constraint.toString(), ignoreCase = true)!!
        }
        return false
    }

    override fun onQueryTextChange(newText: String?): Boolean {
        itemAdapter.filter(newText)
        itemAdapter.itemFilter.filterPredicate = { item: ApplicationManagerApk, constraint: CharSequence? ->
            item.appName?.contains(constraint.toString(), ignoreCase = true)!!
        }
        return true
    }
}

