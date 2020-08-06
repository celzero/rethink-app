package com.celzero.bravedns.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.AsyncTask
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room
import com.celzero.bravedns.adapter.Apk
import com.celzero.bravedns.adapter.ApkListAdapter
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.PermissionsManager
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.AppInfoRepository
import kotlinx.coroutines.*
import java.lang.ref.WeakReference

class PermissionManagerFragment : Fragment(), SearchView.OnQueryTextListener{
    private val apkList = ArrayList<Apk>()
    lateinit var mAdapter: ApkListAdapter
    private lateinit var progressBar: ProgressBar
    private lateinit var  expandableImage : ImageView
    lateinit var mRecyclerView: RecyclerView
    private lateinit var mLinearLayoutManager: LinearLayoutManager
    lateinit var contextVal : Context
    //private var arraySort = ArrayList<Apk>()
    private var editsearch: SearchView? = null

    private lateinit var filterIcon : ImageView


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view: View = inflater!!.inflate(R.layout.fragment_permission_manager,container,false)

        val includeView = view.findViewById<View>(R.id.app_scrolling_incl)
        expandableImage = view.findViewById(R.id.expandedImage)
        progressBar = includeView.findViewById(R.id.progress)
        mRecyclerView = includeView.findViewById(R.id.apk_list_rv)
        mLinearLayoutManager = LinearLayoutManager(activity)
        mAdapter = ApkListAdapter(apkList, contextVal)

        filterIcon = includeView.findViewById(R.id.filter_icon)

        mRecyclerView.layoutManager = mLinearLayoutManager
        mRecyclerView.adapter = mAdapter

        //arraySort = apkList

        editsearch = includeView.findViewById(R.id.search) as SearchView
        editsearch!!.setOnQueryTextListener(this)


        updateAppList()

        expandableImage.setOnClickListener(View.OnClickListener {
            Toast.makeText(this.context,"Load",Toast.LENGTH_SHORT).show()
            mAdapter.notifyDataSetChanged()
        })

        filterIcon.setOnClickListener(View.OnClickListener {
            val bottomFilterSheetFragment = FilterAndSortBottomFragment()
            val frag = context as FragmentActivity
            bottomFilterSheetFragment.show(frag.supportFragmentManager, bottomFilterSheetFragment.tag)
        })

        return view
    }

    private fun updateAppList() = GlobalScope.launch ( Dispatchers.Default ){
            val mDb = AppDatabase.invoke(contextVal.applicationContext)
            val appInfoRepository = mDb.appInfoRepository()
            val appList = appInfoRepository.getAppInfoAsync()
            //Log.w("DB","App list from DB Size: "+appList.size)
            appList.forEach{
                val userApk = Apk(it.appName,it.appName,it.packageInfo,it.uid.toString())
                apkList.add(userApk)
            }
            withContext(Dispatchers.Main.immediate) {
                progressBar.visibility = View.GONE
                mAdapter.notifyDataSetChanged()
            }
        }

    override fun onAttach(context: Context) {
        contextVal = context
        super.onAttach(context)
    }

    override fun onQueryTextSubmit(query: String): Boolean {

        return false
    }

    override fun onQueryTextChange(newText: String): Boolean {
        if(mAdapter.apkListFiltered.size == 0)
            mAdapter.apkListFiltered.addAll(apkList)

        mAdapter.filter(newText)
        return false
    }

}
