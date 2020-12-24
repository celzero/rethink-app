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
import android.view.View
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.TextView
import com.celzero.bravedns.R
import com.mikepenz.fastadapter.FastAdapter
import com.mikepenz.fastadapter.items.AbstractItem


class ApplicationManagerApk (packageInfo: PackageInfo,  var category: String, context : Context) : AbstractItem<ApplicationManagerApk.ViewHolder>() {

    var appInfo: ApplicationInfo?= null
    var appName: String ?= null
    var packageName: String ?= null
    var appIcon : Drawable?= null
    var version: String? = ""
    var context : Context?= null
    var isChecked : Boolean = false

    init{

        this.appInfo = packageInfo.applicationInfo
        this.context = context
        this.appIcon = context.packageManager.getApplicationIcon(appInfo!!)
        this.appName = context.packageManager.getApplicationLabel(appInfo!!).toString()
        this.packageName = packageInfo.packageName
        this.version = packageInfo.versionName
        addedList.clear()

    }

    companion object{
        val addedList = ArrayList<ApplicationManagerApk>()
        fun getAddedList(context : Context ):ArrayList<ApplicationManagerApk>{
            return addedList
        }

        fun cleatList(){
            addedList.clear()
        }
    }

    /** defines the type defining this item. must be unique. preferably an id */
    override val type: Int
        get() = R.id.am_apk_parent

    /** defines the layout which will be used for this item in the list  */
    override val layoutRes: Int
        get() = R.layout.am_list_item

    override fun getViewHolder(v: View): ViewHolder {
        return ViewHolder(v)
    }

    inner class ViewHolder (itemView: View): FastAdapter.ViewHolder<ApplicationManagerApk>(itemView) {
        private val llFirwallBg : androidx.appcompat.widget.LinearLayoutCompat = itemView.findViewById(R.id.am_list_ll_bg)
        private val mIconImageView: ImageView = itemView.findViewById(R.id.am_apk_icon_iv)
        private val mLabelTextView: TextView = itemView.findViewById(R.id.am_apk_label_tv)
        private val mCheckBox : CheckBox = itemView.findViewById(R.id.am_action_item_checkbox)
        val mPackageTextView: TextView = itemView.findViewById(R.id.am_apk_package_tv)
        val mIconIndicator : TextView = itemView.findViewById(R.id.am_status_indicator)
        //val mUninstallTV : TextView = itemView.findViewById(R.id.am_uninstall_tv)
        //val mForceStopTV : TextView = itemView.findViewById(R.id.am_force_stop_tv)

        override fun bindView(permissionManagerApk:  ApplicationManagerApk, payloads: MutableList<Any>) {
            mIconImageView.setImageDrawable(permissionManagerApk.appIcon)
            mLabelTextView.setText(permissionManagerApk.appName)
            mPackageTextView.text = permissionManagerApk.category
            mCheckBox.setOnCheckedChangeListener(null)
            mCheckBox.isChecked = permissionManagerApk.isSelected

           /* if(this.layoutPosition%2 == 0){
                llFirwallBg.setBackgroundColor(context!!.getColor(R.color.colorPrimaryDark))
            }*/

            mCheckBox.setOnCheckedChangeListener { compoundButton: CompoundButton, b: Boolean ->
               if(b){
                   addedList.add(permissionManagerApk)
                   permissionManagerApk.isChecked = true
                   permissionManagerApk.isSelected = true
               }else{
                   addedList.remove(permissionManagerApk)
                   permissionManagerApk.isChecked = false
                   permissionManagerApk.isSelected = false
               }
                /*if(permissionManagerApk.isChecked && b) {
                    addedList.remove(permissionManagerApk)
                    permissionManagerApk.isChecked = false
                    mCheckBox.isChecked = false
                }else if(!permissionManagerApk.isChecked && !b){
                    addedList.add(permissionManagerApk)
                    permissionManagerApk.isChecked = true
                    mCheckBox.isChecked = b
                }*/
            }
        }

        override fun unbindView(item: ApplicationManagerApk) {
            super.detachFromWindow(item)
        }
    }
}