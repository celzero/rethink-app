package com.celzero.bravedns.util

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log


object PackageManagerHelper {

    fun isPackage(context: Context, s: CharSequence): Boolean {
        val packageManager: PackageManager = context.getPackageManager()
        try {
            packageManager.getPackageInfo(s.toString(), PackageManager.GET_META_DATA)
        } catch (e: PackageManager.NameNotFoundException) {
            return false
        }
        return true
    }

    fun getPackageUid(context: Context, packageName: String?): Int {
        val packageManager: PackageManager = context.getPackageManager()
        var uid = -1
        try {
            val packageInfo =
                packageManager.getPackageInfo(packageName, PackageManager.GET_META_DATA)
            //Log.d("PackageMgr", packageInfo.packageName)
            uid = packageInfo.applicationInfo.uid
        } catch (e: PackageManager.NameNotFoundException) {
        }
        return uid
    }
}