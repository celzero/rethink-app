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

import android.os.Bundle
import android.view.View
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.Apk
import com.celzero.bravedns.databinding.BottomSheetPermissionManagerBinding
import com.celzero.bravedns.util.DatabaseHandler
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.koin.android.ext.android.inject

/**
 * Experimental BottomSheetFragment.
 * Implemented for the Permission Manager,
 * whether to auto remove, auto revoke and do nothing
 * for the permissions.
 */
class BottomSheetFragment(apkItem: Apk) : BottomSheetDialogFragment() {
    private val b by viewBinding(BottomSheetPermissionManagerBinding::bind)

    private var apkVal: Apk = apkItem
    private val dbHandler by inject<DatabaseHandler>()

    override fun getTheme(): Int = R.style.BottomSheetDialogTheme

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
    }

    private fun initView() {
        print("initView")
        val rule = 0 //HomeScreenActivity.dbHandler.getSpecificPackageRule(apkVal.packageName)
        //Toast.makeText(contextV,"Rule:"+rule,Toast.LENGTH_SHORT).show()

        when (rule) {
            0 -> {
                b.txtDoNothing.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ok, 0)
                b.txtAutoRemove.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
                b.txtAutoRevoke.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
            }
            1 -> {
                b.txtAutoRevoke.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ok, 0)
                b.txtAutoRemove.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
                b.txtDoNothing.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
            }
            2 -> {
                b.txtAutoRemove.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ok, 0)
                b.txtAutoRevoke.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
                b.txtDoNothing.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
            }
        }

        b.txtAutoRemove.setOnClickListener {
            dbHandler.updatePackage(apkVal.packageName, 2)
            print(apkVal.packageName)
            this.dismiss()
        }
        b.txtAutoRevoke.setOnClickListener {
            dbHandler.updatePackage(apkVal.packageName, 1)
            print(apkVal.packageName)
            this.dismiss()
        }
        b.txtDoNothing.setOnClickListener {
            dbHandler.updatePackage(apkVal.packageName, 0)
            print(apkVal.packageName)
            this.dismiss()
        }

    }
}