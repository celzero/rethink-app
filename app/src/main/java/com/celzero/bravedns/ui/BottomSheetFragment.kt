package com.celzero.bravedns.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.celzero.bravedns.adapter.Apk
import com.celzero.bravedns.R
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class BottomSheetFragment(context : Context, apkItem : Apk) : BottomSheetDialogFragment() {

    private var fragmentView: View? = null
    var contextV : Context  ?= null
    val SHARED_PREF : String = "GZERO_PACKAGE_RULES_PREF"

    var apkVal : Apk = apkItem

    lateinit var txtAutoRemove : TextView
    lateinit var txtAutoRevoke : TextView
    lateinit var txtDoNothing : TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        this.contextV = context
        super.onCreate(savedInstanceState)
    }

    override fun getTheme(): Int = R.style.BottomSheetDialogTheme

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        fragmentView = inflater.inflate(R.layout.bottom_sheet_permission_manager, container, false)
        //initView(fragmentView)
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
        var rule = HomeScreenActivity.dbHandler.getSpecificPackageRule(apkVal.packageName)
        //Toast.makeText(contextV,"Rule:"+rule,Toast.LENGTH_SHORT).show()
        txtAutoRemove = view.findViewById(R.id.textView)
        txtAutoRevoke = view.findViewById(R.id.textView2)
        txtDoNothing = view.findViewById(R.id.textView3)

        if(rule == 0){
            txtDoNothing?.setCompoundDrawablesWithIntrinsicBounds(0,0,R.drawable.ok,0)
            txtAutoRemove?.setCompoundDrawablesWithIntrinsicBounds(0,0,0,0)
            txtAutoRevoke?.setCompoundDrawablesWithIntrinsicBounds(0,0,0,0)
        }else if(rule == 1){
            txtAutoRevoke?.setCompoundDrawablesWithIntrinsicBounds(0,0,R.drawable.ok,0)
            txtAutoRemove?.setCompoundDrawablesWithIntrinsicBounds(0,0,0,0)
            txtDoNothing?.setCompoundDrawablesWithIntrinsicBounds(0,0,0,0)
        }else if(rule == 2){
            txtAutoRemove?.setCompoundDrawablesWithIntrinsicBounds(0,0,R.drawable.ok,0)
            txtAutoRevoke?.setCompoundDrawablesWithIntrinsicBounds(0,0,0,0)
            txtDoNothing?.setCompoundDrawablesWithIntrinsicBounds(0,0,0,0)
        }

        txtAutoRemove!!.setOnClickListener{
            //Toast.makeText(contextV,"Package Name :"+apkVal.packageName,Toast.LENGTH_SHORT).show()
            HomeScreenActivity.dbHandler.updatePackage(apkVal.packageName , 2)
            print(apkVal.packageName)
            this.dismiss()
        }
        txtAutoRevoke!!.setOnClickListener{
            //Toast.makeText(contextV,"Package Name :"+apkVal.packageName,Toast.LENGTH_SHORT).show()
            HomeScreenActivity.dbHandler.updatePackage(apkVal.packageName , 1)
            print(apkVal.packageName)
            this.dismiss()
        }
        txtDoNothing!!.setOnClickListener{
            //Toast.makeText(contextV,"Package Name :"+apkVal.packageName,Toast.LENGTH_SHORT).show()
            HomeScreenActivity.dbHandler.updatePackage(apkVal.packageName , 0)
            print(apkVal.packageName)
            this.dismiss()
        }

    }
}