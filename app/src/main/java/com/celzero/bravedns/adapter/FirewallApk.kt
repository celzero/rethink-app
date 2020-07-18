package com.celzero.bravedns.adapter

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import com.celzero.bravedns.R
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.service.PersistantState
import com.celzero.bravedns.ui.HomeScreenActivity
import com.mikepenz.fastadapter.FastAdapter
import com.mikepenz.fastadapter.items.AbstractItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

open class FirewallApk(packageInfo: PackageInfo , var isWifiEnabled : Boolean, var isDataEnabled : Boolean,  var isSystemApp : Boolean,
     var isScreenOff : Boolean , var isInternet : Boolean, var isBackgroundEnabled : Boolean, context : Context) : AbstractItem<FirewallApk.ViewHolder>(){


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
        val mIconImageView: ImageView = itemView.findViewById(R.id.firewall_apk_icon_iv)
        val mLabelTextView: TextView = itemView.findViewById(R.id.firewall_apk_label_tv)
        val mPackageTextView: TextView = itemView.findViewById(R.id.firewall_apk_package_tv)
        val mIconIndicator : TextView = itemView.findViewById(R.id.firewall_status_indicator)
        val fwMobileDataImg : AppCompatImageView = itemView.findViewById(R.id.firewall_toggle_mobile_data)
        val fwWifiImg : AppCompatImageView = itemView.findViewById(R.id.firewall_toggle_wifi)
        val fwBackgroundTxt : AppCompatTextView  = itemView.findViewById(R.id.firewall_background_txt)
        val fwScreenOffTxt : AppCompatTextView = itemView.findViewById(R.id.firewall_screenOff_txt)
        val fwInternetTxt : AppCompatTextView = itemView.findViewById(R.id.firewall_always_txt)

        override fun bindView(firewallApk:  FirewallApk, payloads: MutableList<Any>) {
            mIconImageView.setImageDrawable(firewallApk.appIcon)
            mLabelTextView.text = firewallApk.appName

            setFirewallApps()

            //For WiFi
            if(firewallApk.isWifiEnabled)
                fwWifiImg.setImageResource(R.drawable.wifi_on)
            else
                fwWifiImg.setImageResource(R.drawable.wifi_off)

            //For Mobile Data
            if(firewallApk.isDataEnabled)
                fwMobileDataImg.setImageResource(R.drawable.data_on)
            else
                fwMobileDataImg.setImageResource(R.drawable.data_off)


            //For Screen On/Off
            if(!firewallApk.isScreenOff)
                fwScreenOffTxt.setTextColor(context!!.getColor(R.color.colorGreen_900))
            else
                fwScreenOffTxt.setTextColor(context!!.getColor(R.color.colorRed_900))

            //For Background Restriction
            if(!firewallApk.isBackgroundEnabled)
                fwBackgroundTxt.setTextColor(context!!.getColor(R.color.colorGreen_900))
            else
                fwBackgroundTxt.setTextColor(context!!.getColor(R.color.colorRed_900))

            //For Internet allowed or not
            if(!firewallApk.isInternet)
                fwInternetTxt.setTextColor(context!!.getColor(R.color.colorGreen_900))
            else
                fwInternetTxt.setTextColor(context!!.getColor(R.color.colorRed_900))


            //TODO : Redundant onClick Listeners
            // Move those to the single click listener with the view check
            fwWifiImg.setOnClickListener{
                if(firewallApk.isWifiEnabled) {
                    fwWifiImg.setImageResource(R.drawable.wifi_off)
                    firewallApk.isWifiEnabled = false
                }
                else {
                    fwWifiImg.setImageResource(R.drawable.wifi_on)
                    firewallApk.isWifiEnabled = true
                }
                GlobalScope.launch(Dispatchers.IO) {
                    val mDb = AppDatabase.invoke(context!!.applicationContext)
                    //val appInfoDAO  = mDb.appInfoDAO()
                    val appInfoRepository = mDb.appInfoRepository()//AppInfoRepository(appInfoDAO)
                    val appInfo : AppInfo = assignValuesForAppInfo(firewallApk)
                    appInfoRepository.updateAsync(appInfo,this)
                }
            }

            fwMobileDataImg.setOnClickListener{
                if(firewallApk.isDataEnabled) {
                    fwMobileDataImg.setImageResource(R.drawable.data_off)
                    firewallApk.isDataEnabled = false
                }
                else {
                    fwMobileDataImg.setImageResource(R.drawable.data_on)
                    firewallApk.isDataEnabled = true
                }
                GlobalScope.launch(Dispatchers.IO) {
                    val mDb = AppDatabase.invoke(context!!.applicationContext)
                    //val appInfoDAO  = mDb.appInfoDAO()
                    val appInfoRepository = mDb.appInfoRepository()//AppInfoRepository(appInfoDAO)
                    val appInfo : AppInfo = assignValuesForAppInfo(firewallApk)
                    appInfoRepository.updateAsync(appInfo,this)
                }
            }

        }

        override fun unbindView(item: FirewallApk) {
            mIconImageView.setImageDrawable(null)
            mLabelTextView.setText(null)
        }

    }

    private fun assignValuesForAppInfo(firewallApk: FirewallApk): AppInfo {

        var appInfo = AppInfo()
        appInfo.isBackgroundEnabled = firewallApk.isBackgroundEnabled
        appInfo.isInternet = firewallApk.isInternet
        appInfo.isScreenOff = firewallApk.isScreenOff
        appInfo.isDataEnabled = firewallApk.isDataEnabled
        appInfo.isWifiEnabled = firewallApk.isWifiEnabled
        appInfo.packageInfo = firewallApk.packageName!!

        return appInfo
    }

    private fun setFirewallApps(){
        var sets: MutableSet<String> = HashSet()
        PersistantState.setExcludedPackages(sets,context)
    }

}