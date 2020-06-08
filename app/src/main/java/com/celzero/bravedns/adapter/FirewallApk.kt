package com.celzero.bravedns.adapter

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.celzero.bravedns.R
import com.mikepenz.fastadapter.FastAdapter
import com.mikepenz.fastadapter.items.AbstractItem

open class FirewallApk(packageInfo: PackageInfo, context : Context) : AbstractItem<FirewallApk.ViewHolder>(){

    var appInfo: ApplicationInfo ?= null
    var appName: String ?= null
    var packageName: String ?= null
    var appIcon : Drawable ?= null
    var version: String? = ""
    var context : Context ?= null


   init{

       this.appInfo = packageInfo.applicationInfo
       this.context = context
       this.appIcon = context.packageManager.getApplicationIcon(appInfo)
       this.appName = context.packageManager.getApplicationLabel(appInfo).toString()
       this.packageName = packageInfo.packageName
       this.version = packageInfo.versionName

   }

    /** defines the type defining this item. must be unique. preferably an id */
    override val type: Int
        get() = R.id.apk_parent

    /** defines the layout which will be used for this item in the list  */
    override val layoutRes: Int
        get() = R.layout.apk_list_item




    override fun getViewHolder(v: View): ViewHolder {
        return ViewHolder(v)
    }

    inner class ViewHolder (itemView: View): FastAdapter.ViewHolder<FirewallApk>(itemView) {
        val mIconImageView: ImageView = itemView.findViewById(R.id.apk_icon_iv)
        val mLabelTextView: TextView = itemView.findViewById(R.id.apk_label_tv)
        val mPackageTextView: TextView = itemView.findViewById(R.id.apk_package_tv)
        val mIconIndicator : TextView = itemView.findViewById(R.id.status_indicator)

        override fun bindView(firewallApk:  FirewallApk, payloads: MutableList<Any>) {
            mIconImageView.setImageDrawable(firewallApk.appIcon)
            mLabelTextView.setText(firewallApk.appName)
        }

        override fun unbindView(item: FirewallApk) {
            mIconImageView.setImageDrawable(null)
            mLabelTextView.setText(null)
        }

    }

}