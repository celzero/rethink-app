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
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.AppInfoActivity
import com.mikepenz.fastadapter.FastAdapter
import com.mikepenz.fastadapter.items.AbstractItem

class PermissionManagerApk (packageInfo: PackageInfo, context : Context) : AbstractItem<PermissionManagerApk.ViewHolder>() {

    private var appInfo: ApplicationInfo = packageInfo.applicationInfo
    var appName: String ?= null
    private var packageName: String ?= null
    private var appIcon : Drawable?= null
    private var version: String? = ""
    private var context : Context?= null


    init{

        this.context = context
        this.appIcon = context.packageManager.getApplicationIcon(appInfo)
        this.appName = context.packageManager.getApplicationLabel(appInfo).toString()
        this.packageName = packageInfo.packageName
        this.version = packageInfo.versionName

    }

    /** defines the type defining this item. must be unique. preferably an id */
    override val type: Int
        get() = R.id.pm_apk_parent

    /** defines the layout which will be used for this item in the list  */
    override val layoutRes: Int
        get() = R.layout.pm_list_item




    override fun getViewHolder(v: View): ViewHolder {
        return ViewHolder(v)
    }

    inner class ViewHolder (itemView: View): FastAdapter.ViewHolder<PermissionManagerApk>(itemView) {
        val mIconImageView: ImageView = itemView.findViewById(R.id.pm_apk_icon_iv)
        val mLabelTextView: TextView = itemView.findViewById(R.id.pm_apk_label_tv)
        val mPackageTextView: TextView = itemView.findViewById(R.id.pm_apk_package_tv)
        val mIconIndicator : TextView = itemView.findViewById(R.id.pm_status_indicator)

        override fun bindView(item:  PermissionManagerApk, payloads: MutableList<Any>) {
            mIconImageView.setImageDrawable(item.appIcon)
            mLabelTextView.setText(item.appName)

            mLabelTextView.setOnClickListener {
                val intent = Intent(context, AppInfoActivity::class.java)
                context?.startActivity(intent)
            }
        }

        override fun unbindView(item: PermissionManagerApk) {
            mIconImageView.setImageDrawable(null)
            mLabelTextView.setText(null)
        }

    }
}