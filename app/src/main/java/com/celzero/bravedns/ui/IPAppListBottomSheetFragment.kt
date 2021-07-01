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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.ConnectionTracker
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.util.concurrent.ConcurrentHashMap


class IPAppListBottomSheetFragment(private var contextVal: Context, private var appInfo: AppInfo) :
        BottomSheetDialogFragment() {

    private lateinit var fragmentView: View
    lateinit var ipAppRecyclerView: RecyclerView

    lateinit var txtView: TextView

    private var titleList: List<ConnectionTracker>? = ArrayList()
    private var ipDetailsMap: ConcurrentHashMap<String, CustomList> = ConcurrentHashMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        fragmentView = inflater.inflate(R.layout.bottom_sheet_ip_app_detail, container, false)
        initView(fragmentView)
        return fragmentView
    }

    private fun initView(fragmentView: View) {
        ipAppRecyclerView = fragmentView.findViewById(R.id.ip_app_recyclerview)
        txtView = fragmentView.findViewById(R.id.parent_btm_sheet)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        //observersForUI()
        super.onViewCreated(view, savedInstanceState)
    }

    private fun prepareCustomList() {
        titleList!!.forEach {
            if (ipDetailsMap.containsKey(it.ipAddress)) {
                val cList = ipDetailsMap[it.ipAddress]
                cList!!.ipCount = +1
                if (it.isBlocked) {
                    cList.isAllowedCount = +1
                } else {
                    cList.isBlockedCount = +1
                }
            } else {
                ipDetailsMap[it.ipAddress!!] = CustomList(1, it.timestamp, 1, 1, it.isBlocked,
                                                          it.ipAddress!!)
            }
        }
    }

    class CustomList(var ipCount: Int, var timeStamp: Long, var isBlockedCount: Int,
                     var isAllowedCount: Int, var currentStatus: Boolean, var ipAddress: String)

}
