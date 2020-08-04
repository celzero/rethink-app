package com.celzero.bravedns.ui


import android.app.Activity
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.ApplicationManagerApk
import com.celzero.bravedns.animation.ViewAnimation
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.database.AppInfo
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.mikepenz.fastadapter.FastAdapter
import com.mikepenz.fastadapter.adapters.ItemAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.UncheckedIOException


class ApplicationManagerActivity : AppCompatActivity(), SearchView.OnQueryTextListener{






    private lateinit var fabAddIcon : FloatingActionButton
    private lateinit var fabUninstallIcon : FloatingActionButton
    private lateinit var fabAppInfoIcon : FloatingActionButton

    private var editSearch: SearchView? = null

    private val UNINSTALL_REQUEST_CODE = 111

    var isRotate : Boolean = false


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
                list.get(list.size - 1).packageName?.let { it1 -> appInfoForPackage(it1) }
            }
        }


    }


    companion object{
        private lateinit var recycle : RecyclerView
        lateinit var itemAdapter: ItemAdapter<ApplicationManagerApk>
        private lateinit var fastAdapter: FastAdapter<ApplicationManagerApk>
        private lateinit var context : Context
        private val apkList = ArrayList<ApplicationManagerApk>()
        fun updateUI(packageName : String, isAdded : Boolean){
            Log.d("BraveDNS","Refresh list called : package Name :-" + packageName)
            //val packageName = packageName.removePrefix("package:").toString()
            fastAdapter = FastAdapter.with(itemAdapter)
            if(isAdded){
                val packageInfo = context.packageManager.getPackageInfo(packageName,0)
                ApplicationInfo.getCategoryTitle(context,packageInfo.applicationInfo.category)
                if(packageInfo.packageName != "com.celzero.bravedns" ) {
                    val userApk =  ApplicationManagerApk(packageInfo, "", context)
                    apkList.add(userApk)
                }
            }else{
                var apkDetail : ApplicationManagerApk? = null
                apkList.forEach {
                    if(it.packageName.equals(packageName)) {
                        apkDetail = it
                    }
                }
                if(apkDetail != null) {
                    Log.d("BraveDNS","apkDetail Removed  :-" + packageName)
                    apkList.remove(apkDetail!!)
                }else{
                    Log.d("BraveDNS","apkDetail is null  :-" + packageName)
                }
            }
            if(fastAdapter != null) {
                Log.d("BraveDNS","fastAdapter notified  :-" + packageName)
                itemAdapter.clear()
                recycle.adapter = fastAdapter
                itemAdapter.add(apkList)
                fastAdapter.notifyAdapterDataSetChanged()
                fastAdapter.notifyDataSetChanged()
            }else{
                Log.d("BraveDNS","fastAdapter is null  :-" + packageName)
            }
        }
    }

    private fun uninstallPackage(app : ApplicationManagerApk){
        val packageURI = Uri.parse("package:"+app.packageName)
        val intent : Intent = Intent(Intent.ACTION_DELETE,packageURI)
        intent.putExtra("packageName",app.packageName)
        startActivity(intent)
        //apkList.remove(app)

    }

    private fun appInfoForPackage(packageName : String){
        val activityManager : ActivityManager = context!!.getSystemService(Activity.ACTIVITY_SERVICE) as ActivityManager
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
        val mDb = AppDatabase.invoke(context.applicationContext)
        val appInfoRepository = mDb.appInfoRepository()
        val appList = appInfoRepository.getAppInfoAsync()
        Log.w("DB","App list from DB Size: "+appList.size)
        appList.forEach{
            val packageInfo = packageManager.getPackageInfo(it.packageInfo,0)
            if(packageInfo.packageName != "com.celzero.bravedns" ) {
                val userApk =  ApplicationManagerApk(packageManager.getPackageInfo(it.packageInfo, 0), it.appCategory, context)
                apkList.add(userApk)
            }
        }
        withContext(Dispatchers.Main.immediate) {
            itemAdapter.add(apkList)
            fastAdapter.notifyDataSetChanged()
        }
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


    /*override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == UNINSTALL_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                Toast.makeText(this, "Uninstall Successfull", Toast.LENGTH_SHORT)
                Log.d("TAG", "onActivityResult: user accepted the (un)install")
            } else if (resultCode == Activity.RESULT_CANCELED) {
                Toast.makeText(this, "Uninstall Cancelled", Toast.LENGTH_SHORT)
                Log.d("TAG", "onActivityResult: user canceled the (un)install")
            } else if (resultCode == Activity.RESULT_FIRST_USER) {
                Toast.makeText(this, "Failed to (Un)install", Toast.LENGTH_SHORT)
                Log.d("TAG", "onActivityResult: failed to (un)install")
            }
        }
    }*/

}

