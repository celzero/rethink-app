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
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.Apk
import com.celzero.bravedns.adapter.ApkListAdapter
import com.celzero.bravedns.database.AppInfoRepository
import com.celzero.bravedns.util.LoggerConstants
import kotlinx.coroutines.*
import org.koin.android.ext.android.inject

class PermissionManagerFragment : Fragment(), SearchView.OnQueryTextListener {
    private val apkList = ArrayList<Apk>()
    lateinit var mAdapter: ApkListAdapter
    private lateinit var progressBar: ProgressBar
    private lateinit var expandableImage: ImageView
    lateinit var mRecyclerView: RecyclerView
    private lateinit var mLinearLayoutManager: LinearLayoutManager
    lateinit var contextVal: Context
    private var editsearch: SearchView? = null

    private lateinit var filterIcon: ImageView

    private val appInfoRepository by inject<AppInfoRepository>()


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        val view: View = inflater.inflate(R.layout.fragment_permission_manager, container, false)

        val includeView = view.findViewById<View>(R.id.app_scrolling_incl)
        expandableImage = view.findViewById(R.id.expandedImage)
        progressBar = includeView.findViewById(R.id.progress)
        mRecyclerView = includeView.findViewById(R.id.apk_list_rv)
        mLinearLayoutManager = LinearLayoutManager(activity)
        mAdapter = ApkListAdapter(apkList, contextVal)

        filterIcon = includeView.findViewById(R.id.filter_icon)

        mRecyclerView.layoutManager = mLinearLayoutManager
        mRecyclerView.adapter = mAdapter

        editsearch = includeView.findViewById(R.id.search) as SearchView
        editsearch?.setOnQueryTextListener(this)

        updateAppList()

        expandableImage.setOnClickListener {
            mAdapter.notifyDataSetChanged()
        }

        filterIcon.setOnClickListener {
            if (activity == null) {
                Log.w(LoggerConstants.LOG_TAG_UI,
                      "Cannot open orbot-bottom-sheet, activity does not exist")
                return@setOnClickListener
            }
            val bottomFilterSheetFragment = FilterAndSortBottomFragment()
            bottomFilterSheetFragment.show(requireActivity().supportFragmentManager,
                                           bottomFilterSheetFragment.tag)
        }

        return view
    }

    private fun updateAppList() = lifecycleScope.launch {
        withContext(Dispatchers.Default) {
            val appList = appInfoRepository.getAppInfo()
            appList.forEach {
                val userApk = Apk(it.appName, it.appName, it.packageInfo, it.uid.toString())
                apkList.add(userApk)
            }
            withContext(Dispatchers.Main.immediate) {
                progressBar.visibility = View.GONE
                mAdapter.notifyDataSetChanged()
            }
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
        if (mAdapter.apkListFiltered.size == 0) mAdapter.apkListFiltered.addAll(apkList)

        mAdapter.filter(newText)
        return false
    }

}
