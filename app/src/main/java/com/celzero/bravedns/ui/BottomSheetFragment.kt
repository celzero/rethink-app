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

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.Apk
import com.celzero.bravedns.util.DatabaseHandler
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.koin.android.ext.android.inject

/**
 * Experimental BottomSheetFragment.
 * Implemented for the Permission Manager,
 * whether to auto remove, auto revoke and do nothing
 * for the permissions.
 */
class BottomSheetFragment(context : Context, apkItem : Apk) : BottomSheetDialogFragment() {

    private var fragmentView: View? = null

    private var apkVal : Apk = apkItem

    private lateinit var txtAutoRemove : TextView
    private lateinit var txtAutoRevoke : TextView
    private lateinit var txtDoNothing : TextView
    private val dbHandler by inject<DatabaseHandler>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun getTheme(): Int = R.style.BottomSheetDialogTheme

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        fragmentView = inflater.inflate(R.layout.bottom_sheet_permission_manager, container, false)
        return fragmentView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView(view)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState)
    }

    private fun initView(view : View) {
        print("initView")
        val rule = 0//HomeScreenActivity.dbHandler.getSpecificPackageRule(apkVal.packageName)
        //Toast.makeText(contextV,"Rule:"+rule,Toast.LENGTH_SHORT).show()
        txtAutoRemove = view.findViewById(R.id.textView)
        txtAutoRevoke = view.findViewById(R.id.textView2)
        txtDoNothing = view.findViewById(R.id.textView3)

        if(rule == 0){
            txtDoNothing.setCompoundDrawablesWithIntrinsicBounds(0,0,R.drawable.ok,0)
            txtAutoRemove.setCompoundDrawablesWithIntrinsicBounds(0,0,0,0)
            txtAutoRevoke.setCompoundDrawablesWithIntrinsicBounds(0,0,0,0)
        }else if(rule == 1){
            txtAutoRevoke.setCompoundDrawablesWithIntrinsicBounds(0,0,R.drawable.ok,0)
            txtAutoRemove.setCompoundDrawablesWithIntrinsicBounds(0,0,0,0)
            txtDoNothing.setCompoundDrawablesWithIntrinsicBounds(0,0,0,0)
        }else if(rule == 2){
            txtAutoRemove.setCompoundDrawablesWithIntrinsicBounds(0,0,R.drawable.ok,0)
            txtAutoRevoke.setCompoundDrawablesWithIntrinsicBounds(0,0,0,0)
            txtDoNothing.setCompoundDrawablesWithIntrinsicBounds(0,0,0,0)
        }

        txtAutoRemove.setOnClickListener{
            dbHandler.updatePackage(apkVal.packageName , 2)
            print(apkVal.packageName)
            this.dismiss()
        }
        txtAutoRevoke.setOnClickListener{
            dbHandler.updatePackage(apkVal.packageName , 1)
            print(apkVal.packageName)
            this.dismiss()
        }
        txtDoNothing.setOnClickListener{
            dbHandler.updatePackage(apkVal.packageName , 0)
            print(apkVal.packageName)
            this.dismiss()
        }

    }
}