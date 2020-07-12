package com.celzero.bravedns.adapter

import android.app.Activity
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.*
import androidx.core.content.ContextCompat.startActivity
import com.celzero.bravedns.R
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.database.AppInfo
import com.mikepenz.fastadapter.FastAdapter
import com.mikepenz.fastadapter.items.AbstractItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch


class ApplicationManagerApk (packageInfo: PackageInfo, context : Context) : AbstractItem<ApplicationManagerApk.ViewHolder>() {

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
        this.appIcon = context.packageManager.getApplicationIcon(appInfo)
        this.appName = context.packageManager.getApplicationLabel(appInfo).toString()
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
            mCheckBox.isChecked = permissionManagerApk.isChecked
            mCheckBox.setOnCheckedChangeListener(null)

            mCheckBox.setOnCheckedChangeListener { compoundButton: CompoundButton, b: Boolean ->
                if(permissionManagerApk.isChecked && b) {
                    addedList.remove(permissionManagerApk)
                    permissionManagerApk.isChecked = false
                    mCheckBox.isChecked = false
                }else if(!permissionManagerApk.isChecked && !b){
                    addedList.add(permissionManagerApk)
                    permissionManagerApk.isChecked = true
                    mCheckBox.isChecked = b
                }
            }
        }

        override fun unbindView(item: ApplicationManagerApk) {
            super.detachFromWindow(item)
        }
    }
}