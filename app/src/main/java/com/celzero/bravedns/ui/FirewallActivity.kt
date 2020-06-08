package com.celzero.bravedns.ui

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.AsyncTask
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.FirewallApk
import com.celzero.bravedns.automaton.PermissionsManager
import com.mikepenz.fastadapter.FastAdapter
import com.mikepenz.fastadapter.adapters.ItemAdapter


class FirewallActivity : AppCompatActivity(), SearchView.OnQueryTextListener {

    private lateinit var fastAdapter: FastAdapter<FirewallApk>
    private lateinit var recycle : RecyclerView
    internal val apkList = ArrayList<FirewallApk>()
    lateinit var itemAdapter: ItemAdapter<FirewallApk>
    
    private var editSearch: SearchView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_firewall)

        initView()

        val task = MyAsyncAppDetailsTask(this)
        task.execute(1)

    }

    private fun initView() {

        recycle = findViewById(R.id.recycler)
        recycle.layoutManager = LinearLayoutManager(this)

        itemAdapter =  ItemAdapter()
        fastAdapter = FastAdapter.with(itemAdapter)
        editSearch = findViewById(R.id.search)

        editSearch!!.setOnQueryTextListener(this)

        recycle.adapter = fastAdapter
    }


    companion object {
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
                    PermissionsManager.packageRules[it.packageName] = PermissionsManager.Rules.NONE
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


    }

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
