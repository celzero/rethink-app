package com.celzero.bravedns.ui

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.AsyncTask
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.ApplicationManagerApk
import com.celzero.bravedns.adapter.FirewallApk
import com.celzero.bravedns.automaton.PermissionsManager
import com.celzero.bravedns.database.AppDatabase
import com.mikepenz.fastadapter.FastAdapter
import com.mikepenz.fastadapter.adapters.ItemAdapter
import kotlinx.android.synthetic.main.app_scroll_list.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class FirewallActivity : AppCompatActivity(), SearchView.OnQueryTextListener {

    private lateinit var fastAdapter: FastAdapter<FirewallApk>
    private lateinit var recycle : RecyclerView
    internal val apkList = ArrayList<FirewallApk>()
    lateinit var itemAdapter: ItemAdapter<FirewallApk>
    private lateinit var progressBar : ProgressBar
    
    private var editSearch: SearchView? = null
    private lateinit var context : Context

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_firewall)

        initView()

        /*val task = MyAsyncAppDetailsTask(this)
        task.execute(1)*/
        updateAppList()

    }

    private fun initView() {
        context = this

        val includeView = findViewById<View>(R.id.app_scrolling_incl_firewall)

        recycle = includeView.findViewById(R.id.recycler)
        recycle.layoutManager = LinearLayoutManager(this)

        itemAdapter =  ItemAdapter()
        fastAdapter = FastAdapter.with(itemAdapter)
        editSearch = includeView.findViewById(R.id.search)

        progressBar = includeView.findViewById(R.id.firewall_progress)

        editSearch!!.setOnQueryTextListener(this)

        recycle.adapter = fastAdapter
    }


    private fun updateAppList() = GlobalScope.launch ( Dispatchers.Default ){
        val mDb = AppDatabase.invoke(context.applicationContext)
        val appInfoRepository = mDb.appInfoRepository()
        //TODO Global scope variable is not updated properly. Work on that variable
        // Work on the below code.
       /* if(HomeScreenActivity.GlobalVariable.appList.size > 0){
            HomeScreenActivity.GlobalVariable.appList.forEach{
                val firewallApk = FirewallApk(packageManager.getPackageInfo(it.packageInfo,0), it.isWifiEnabled, it.isDataEnabled,
                    it.isSystemApp, it.isScreenOff, it.isInternet, it.isBackgroundEnabled, context)
                apkList.add(firewallApk)
            }
        }else{
            val appList = appInfoRepository.getAppInfoAsync()
            Log.w("DB","App list from DB Size: "+appList.size)
            appList.forEach{
                val firewallApk = FirewallApk(packageManager.getPackageInfo(it.packageInfo,0), it.isWifiEnabled, it.isDataEnabled,
                    it.isSystemApp, it.isScreenOff, it.isInternet, it.isBackgroundEnabled, context)
                apkList.add(firewallApk)
            }
        }*/
        val appList = appInfoRepository.getAppInfoAsync()
        Log.w("DB","App list from DB Size: "+appList.size)
        appList.forEach{
            val firewallApk = FirewallApk(packageManager.getPackageInfo(it.packageInfo,0), it.isWifiEnabled, it.isDataEnabled,
                it.isSystemApp, it.isScreenOff, it.isInternet, it.isBackgroundEnabled, context)
            apkList.add(firewallApk)
        }

        withContext(Dispatchers.Main.immediate) {
            itemAdapter.add(apkList)
            fastAdapter.notifyDataSetChanged()
            progressBar.visibility = View.GONE
        }
    }


   /* companion object {
        class MyAsyncAppDetailsTask internal constructor(private var context: FirewallActivity) : AsyncTask<Int, String, String?>() {

            private var resp: String? = null

            override fun onPreExecute() {
                println("onPreExecute")
                val activity = context
                if (activity.isFinishing) return
            }

            override fun doInBackground(vararg params: Int?): String? {
                println("doInBackground")

                val allPackages: List<PackageInfo> =
                    context.packageManager?.getInstalledPackages(PackageManager.GET_META_DATA )!!

                allPackages.forEach {
                    val firewallApk = FirewallApk(it,context )
                    //if((applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0){
                    //PermissionsManager.packageRules[it.packageName] = PermissionsManager.Rules.NONE
                    //PermissionsManager.packageRules.put(it.packageName, PermissionsManager.Rules.NONE)
                    HomeScreenActivity.dbHandler.updatePackage(it.packageName,0)
                    context.apkList.add(firewallApk)

                }
               return resp
            }


            override fun onPostExecute(result: String?) {
                if (context.isFinishing) return

                context.itemAdapter.add(context.apkList)
                context.fastAdapter.notifyDataSetChanged()
            }

            override fun onProgressUpdate(vararg text: String?) {
                  if (context.isFinishing) return
            }
        }


    }*/

    override fun onQueryTextSubmit(query: String?): Boolean {
        itemAdapter.filter(query)
        itemAdapter.itemFilter.filterPredicate = { item: FirewallApk, constraint: CharSequence? ->
            item.appName?.contains(constraint.toString(), ignoreCase = true)!!
        }
        return false
    }

    override fun onQueryTextChange(newText: String?): Boolean {
        itemAdapter.filter(newText)
        itemAdapter.itemFilter.filterPredicate = { item: FirewallApk, constraint: CharSequence? ->
            item.appName?.contains(constraint.toString(), ignoreCase = true)!!
        }
        return true
    }

}
