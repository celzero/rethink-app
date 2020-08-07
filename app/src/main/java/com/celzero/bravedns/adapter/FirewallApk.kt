package com.celzero.bravedns.adapter

import android.R.id
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity
import com.mikepenz.fastadapter.FastAdapter
import com.mikepenz.fastadapter.items.AbstractItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.launch


open class FirewallApk(packageInfo: PackageInfo , var isWifiEnabled : Boolean, var isDataEnabled : Boolean,  var isSystemApp : Boolean,
     var isScreenOff : Boolean , var isInternetAllowed : Boolean, var isBackgroundEnabled : Boolean ,
    var category : String, context : Context) : AbstractItem<FirewallApk.ViewHolder>(){


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
        val mLLTopLayout : LinearLayoutCompat = itemView.findViewById(R.id.firewall_apk_list_top_layout)
        val mIconImageView: ImageView = itemView.findViewById(R.id.firewall_apk_icon_iv)
        val mLabelTextView: TextView = itemView.findViewById(R.id.firewall_apk_label_tv)
        val mPackageTextView: TextView = itemView.findViewById(R.id.firewall_apk_package_tv)
        val mIconIndicator : TextView = itemView.findViewById(R.id.firewall_status_indicator)
        val fwMobileDataImg : AppCompatImageView = itemView.findViewById(R.id.firewall_toggle_mobile_data)
        val fwWifiImg : SwitchCompat = itemView.findViewById(R.id.firewall_toggle_wifi)


        @InternalCoroutinesApi
        override fun bindView(firewallApk:  FirewallApk, payloads: MutableList<Any>) {
            mIconImageView.setImageDrawable(firewallApk.appIcon)
            mLabelTextView.text = firewallApk.appName

            if(this.layoutPosition%2 == 0)
               mLLTopLayout.setBackgroundColor(ContextCompat.getColor(context!!, R.color.colorPrimaryDark))


            if(category != null)
                mPackageTextView.text = firewallApk.category
            else
                mPackageTextView.text = "Category : Not Specified"
            //setFirewallApps()

            //For WiFi
            if(firewallApk.isInternetAllowed) {
                fwWifiImg.isChecked = false
                mIconIndicator.setBackgroundColor(context!!.getColor(R.color.colorGreen_900))
                //fwWifiImg.setImageResource(R.drawable.ic_dns_off)
                //mLLTopLayout.setBackgroundColor(ContextCompat.getColor(context!!, R.color.recycler_selected))
            }
            else {
                fwWifiImg.isChecked = true
                mIconIndicator.setBackgroundColor(context!!.getColor(R.color.colorAmber_900))
                //fwWifiImg.setImageResource(R.drawable.ic_dns_on)
                //mLLTopLayout.setBackgroundColor(ContextCompat.getColor(context!!, R.color.recycler_selected))
            }



            fwWifiImg.setOnCheckedChangeListener { compoundButton: CompoundButton, b: Boolean ->
                updateInternetPermission(firewallApk, !b)
            }


            //TODO : The value has been removed as of now. Needed in future. For Mobile Data
            /*if(firewallApk.isDataEnabled)
                fwMobileDataImg.setImageResource(R.drawable.data_on)
            else
                fwMobileDataImg.setImageResource(R.drawable.data_off)
            fwMobileDataImg.setOnClickListener{
                if(firewallApk.isDataEnabled) {
                    fwMobileDataImg.setImageResource(R.drawable.data_off)
                    firewallApk.isDataEnabled = false
                    firewallApk.isInternet = false
                    PersistentState.setExcludedPackagesData(firewallApk.packageName!!, false ,context)
                }
                else {
                    fwMobileDataImg.setImageResource(R.drawable.data_on)
                    firewallApk.isDataEnabled = true
                    firewallApk.isInternet = true
                    PersistentState.setExcludedPackagesData(firewallApk.packageName!!, true ,context)
                }
                GlobalScope.launch(Dispatchers.IO) {
                    val mDb = AppDatabase.invoke(context!!.applicationContext)
                    //val appInfoDAO  = mDb.appInfoDAO()
                    val appInfoRepository = mDb.appInfoRepository()//AppInfoRepository(appInfoDAO)
                    val appInfo : AppInfo = assignValuesForAppInfo(firewallApk)
                    appInfoRepository.updateAsync(appInfo,this)
                    //setFirewallApps(firewallApk.packageName!!)
                }
            }*/

        }


        private fun updateInternetPermission(firewallApk : FirewallApk, isInternetAllowed : Boolean){
            //Toast.makeText(context,"isChecked : "+isInternetAllowed, Toast.LENGTH_SHORT).show()
            fwWifiImg.isChecked = !isInternetAllowed
            firewallApk.isWifiEnabled = isInternetAllowed
            firewallApk.isInternetAllowed = isInternetAllowed
            HomeScreenActivity.GlobalVariable.appList.get(firewallApk.packageName!!)!!.isInternetAllowed = isInternetAllowed
            PersistentState.setExcludedPackagesWifi(firewallApk.packageName!!, isInternetAllowed ,context!!)
            FirewallManager.updateAppInternetPermission(firewallApk.packageName!!, isInternetAllowed)
            if(isInternetAllowed)
                mIconIndicator.setBackgroundColor(context!!.getColor(R.color.colorGreen_900))
            else
                mIconIndicator.setBackgroundColor(context!!.getColor(R.color.colorAmber_900))
            GlobalScope.launch(Dispatchers.IO) {
                val mDb = AppDatabase.invoke(context!!.applicationContext)
                val appInfoRepository = mDb.appInfoRepository()//AppInfoRepository(appInfoDAO)
                val appInfo : AppInfo = assignValuesForAppInfo(firewallApk)
                appInfoRepository.updateAsync(appInfo,this)
            }
        }

        override fun unbindView(item: FirewallApk) {
            super.detachFromWindow(item)
        }

    }

    private fun assignValuesForAppInfo(firewallApk: FirewallApk): AppInfo {

        var appInfo = AppInfo()
        appInfo.isBackgroundEnabled = firewallApk.isBackgroundEnabled
        appInfo.isInternetAllowed = firewallApk.isInternetAllowed
        appInfo.isScreenOff = firewallApk.isScreenOff
        appInfo.isDataEnabled = firewallApk.isDataEnabled
        appInfo.isWifiEnabled = firewallApk.isWifiEnabled
        appInfo.packageInfo = firewallApk.packageName!!

       /* if(HomeScreenActivity.GlobalVariable.appList.contains(firewallApk.packageName)){
            HomeScreenActivity.GlobalVariable.appList[firewallApk.packageName!!] = appInfo
        }*/

        return appInfo
    }


}