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

class ApplicationManagerApk (packageInfo: PackageInfo, context : Context) : AbstractItem<ApplicationManagerApk.ViewHolder>() {

    var appInfo: ApplicationInfo?= null
    var appName: String ?= null
    var packageName: String ?= null
    var appIcon : Drawable?= null
    var version: String? = ""
    var context : Context?= null


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
        get() = R.id.am_apk_parent

    /** defines the layout which will be used for this item in the list  */
    override val layoutRes: Int
        get() = R.layout.am_list_item




    override fun getViewHolder(v: View): ViewHolder {
        return ViewHolder(v)
    }

    inner class ViewHolder (itemView: View): FastAdapter.ViewHolder<ApplicationManagerApk>(itemView) {
        val mIconImageView: ImageView = itemView.findViewById(R.id.am_apk_icon_iv)
        val mLabelTextView: TextView = itemView.findViewById(R.id.am_apk_label_tv)
        val mPackageTextView: TextView = itemView.findViewById(R.id.am_apk_package_tv)
        val mIconIndicator : TextView = itemView.findViewById(R.id.am_status_indicator)

        override fun bindView(permissionManagerApk:  ApplicationManagerApk, payloads: MutableList<Any>) {
            mIconImageView.setImageDrawable(permissionManagerApk.appIcon)
            mLabelTextView.setText(permissionManagerApk.appName)
        }


        override fun unbindView(item: ApplicationManagerApk) {
            mIconImageView.setImageDrawable(null)
            mLabelTextView.setText(null)
        }

    }
}