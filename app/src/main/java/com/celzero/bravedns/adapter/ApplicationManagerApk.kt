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
package com.celzero.bravedns.adapter

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.databinding.ListItemAppMgrBinding


class ApplicationManagerApk(packageInfo: PackageInfo, var category: String, context: Context) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var appInfo: ApplicationInfo? = null
    var appName: String? = null
    var packageName: String? = null
    var appIcon: Drawable? = null
    var version: String? = ""
    var isChecked: Boolean = false

    init {

        this.appInfo = packageInfo.applicationInfo
        this.appIcon = context.packageManager.getApplicationIcon(appInfo!!)
        this.appName = context.packageManager.getApplicationLabel(appInfo!!).toString()
        this.packageName = packageInfo.packageName
        this.version = packageInfo.versionName
        addedList.clear()

    }

    companion object {
        private val addedList = ArrayList<ApplicationManagerApk>()
        fun getAddedList(): ArrayList<ApplicationManagerApk> {
            return addedList
        }

        fun cleatList() {
            addedList.clear()
        }
    }


    inner class ViewHolder(b: ListItemAppMgrBinding) : RecyclerView.ViewHolder(b.root) {

    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val itemBinding = ListItemAppMgrBinding.inflate(LayoutInflater.from(parent.context), parent,
                                                        false)
        return ViewHolder(itemBinding)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {

    }

    override fun getItemCount(): Int {
        return 0
    }
}