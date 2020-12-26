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
package com.celzero.bravedns.util

import android.content.Context
import android.content.pm.PackageManager


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

    fun getPackageUid(context: Context, packageName: String): Int {
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