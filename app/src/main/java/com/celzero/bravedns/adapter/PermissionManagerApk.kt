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
import com.celzero.bravedns.databinding.PmListItemBinding

class PermissionManagerApk(packageInfo: PackageInfo, context: Context) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var appInfo: ApplicationInfo = packageInfo.applicationInfo
    var appName: String? = null
    private var packageName: String? = null
    private var appIcon: Drawable? = null
    private var version: String? = ""
    private var context: Context? = null

    init {

        this.context = context
        this.appIcon = context.packageManager.getApplicationIcon(appInfo)
        this.appName = context.packageManager.getApplicationLabel(appInfo).toString()
        this.packageName = packageInfo.packageName
        this.version = packageInfo.versionName

    }


    inner class ViewHolder(b: PmListItemBinding) : RecyclerView.ViewHolder(b.root) {}

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val itemBinding = PmListItemBinding.inflate(LayoutInflater.from(parent.context), parent,
                                                    false)
        return ViewHolder(itemBinding)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
    }

    override fun getItemCount(): Int {
        return 0
    }
}
