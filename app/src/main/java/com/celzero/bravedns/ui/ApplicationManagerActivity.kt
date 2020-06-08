package com.celzero.bravedns.ui


import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.TrafficStats
import android.os.AsyncTask
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.ApplicationManagerApk
import com.celzero.bravedns.adapter.PermissionManagerApk
import com.celzero.bravedns.automaton.PermissionsManager
import com.celzero.bravedns.database.AppDatabase
import com.mikepenz.fastadapter.FastAdapter
import com.mikepenz.fastadapter.adapters.ItemAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class ApplicationManagerActivity : AppCompatActivity(), SearchView.OnQueryTextListener{

    private lateinit var fastAdapter: FastAdapter<ApplicationManagerApk>
    private lateinit var recycle : RecyclerView
    private val apkList = ArrayList<ApplicationManagerApk>()
    lateinit var itemAdapter: ItemAdapter<ApplicationManagerApk>
    private lateinit var context : Context

    private var editSearch: SearchView? = null

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_application_manager)

        initView()

        //val task = MyAsyncAppDetailsTaskAM(this)
        //task.execute(1)
        updateAppList()

    }

    private fun initView() {
        context = this
        recycle = findViewById(R.id.permission_manager_recycler_view)
        recycle.layoutManager = LinearLayoutManager(this)

        itemAdapter =  ItemAdapter()
        fastAdapter = FastAdapter.with(itemAdapter)
        editSearch = findViewById(R.id.am_search)

        editSearch!!.setOnQueryTextListener(this)

        recycle.adapter = fastAdapter
    }


    private fun updateAppList() = GlobalScope.launch ( Dispatchers.Default ){
        val mDb = AppDatabase.invoke(context.applicationContext)
        val appInfoRepository = mDb.appInfoRepository()
        val appList = appInfoRepository.getAppInfoAsync()
        Log.w("DB","App list from DB Size: "+appList.size)
        appList.forEach{

            val userApk = ApplicationManagerApk(packageManager.getPackageInfo(it.packageInfo,0), context)
            apkList.add(userApk)
        }
        withContext(Dispatchers.Main.immediate) {
            itemAdapter.add(apkList)
            fastAdapter.notifyDataSetChanged()
        }
    }

    /*companion object {
        class MyAsyncAppDetailsTaskAM internal constructor(private var context: ApplicationManagerActivity) : AsyncTask<Int, String, String?>() {

            private var resp: String? = null

            override fun onPreExecute() {
                println("onPreExecute")
                if (context.isFinishing) return
            }

            override fun doInBackground(vararg params: Int?): String? {
                println("doInBackground")

                val allPackages: List<PackageInfo> =
                    context.packageManager?.getInstalledPackages(PackageManager.GET_META_DATA )!!

                allPackages.forEach {
                    val applicationManagerApk = ApplicationManagerApk(it,context )
                    //if((applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0){
                    PermissionsManager.packageRules[it.packageName] = PermissionsManager.Rules.NONE
                    //PermissionsManager.packageRules.put(it.packageName, PermissionsManager.Rules.NONE)
                    HomeScreenActivity.dbHandler.updatePackage(it.packageName,0)
                    context.apkList.add(applicationManagerApk)

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

