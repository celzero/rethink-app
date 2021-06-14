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

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.DialogInterface
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatToggleButton
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.AppInfoRepository
import com.celzero.bravedns.database.CategoryInfo
import com.celzero.bravedns.database.CategoryInfoRepository
import com.celzero.bravedns.glide.GlideApp
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants.Companion.APP_CAT_SYSTEM_APPS
import com.celzero.bravedns.util.Constants.Companion.APP_CAT_SYSTEM_COMPONENTS
import com.celzero.bravedns.util.Constants.Companion.APP_NON_APP
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG_FIREWALL
import com.celzero.bravedns.util.ThrowingHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.*


class FirewallAppListAdapter internal constructor(
    private val context: Context,
    private val appInfoRepository:AppInfoRepository,
    private val categoryInfoRepository:CategoryInfoRepository,
    private val persistentState: PersistentState,
    private var titleList: List<CategoryInfo>,
    private var dataList: HashMap<CategoryInfo, ArrayList<AppInfo>>
) : BaseExpandableListAdapter() {


    override fun getChild(listPosition: Int, expandedListPosition: Int): AppInfo {
        return this.dataList[this.titleList[listPosition]]!![expandedListPosition]
    }

    override fun getChildId(listPosition: Int, expandedListPosition: Int): Long {
        return expandedListPosition.toLong()
    }

    fun updateData(title: List<CategoryInfo>, list: HashMap<CategoryInfo, ArrayList<AppInfo>>) {
        titleList = title
        dataList = list
        this.notifyDataSetChanged()
    }


    override fun getChildView(listPosition: Int, expandedListPosition: Int, isLastChild: Boolean, view: View?, parent: ViewGroup): View {
        var convertView = view
        val appInfoDetail = getChild(listPosition, expandedListPosition)
        if (convertView == null) {
            val layoutInflater = this.context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
            convertView = layoutInflater.inflate(R.layout.apk_list_item, null)
        }

        //Child View UI components
        val mIconImageView: ImageView = convertView!!.findViewById(R.id.firewall_apk_icon_iv)
        val mLabelTextView: TextView = convertView.findViewById(R.id.firewall_apk_label_tv)
        val mPackageTextView: TextView = convertView.findViewById(R.id.firewall_apk_package_tv)
        val mIconIndicator: TextView = convertView.findViewById(R.id.firewall_status_indicator)

        val fwWifiImg: SwitchCompat = convertView.findViewById(R.id.firewall_toggle_wifi)
        val firewallApkProgressBar: ProgressBar = convertView.findViewById(R.id.firewall_apk_progress_bar)

        try {
            GlideApp.with(context).load(context.packageManager.getApplicationIcon(appInfoDetail.packageInfo))
                .error(AppCompatResources.getDrawable(context, R.drawable.default_app_icon))
                .into(mIconImageView)
        } catch (e: Exception) {
            GlideApp.with(context).load(AppCompatResources.getDrawable(context, R.drawable.default_app_icon))
                .error(AppCompatResources.getDrawable(context, R.drawable.default_app_icon))
                .into(mIconImageView)
            Log.i(LOG_TAG_FIREWALL, "Application Icon not available for package: ${appInfoDetail.packageInfo}" + e.message)
        }
        mLabelTextView.text = appInfoDetail.appName

        firewallApkProgressBar.visibility = View.GONE

        //To disable the app from selecting into firewall
        if (appInfoDetail.whiteListUniv1) {
            fwWifiImg.isClickable = !appInfoDetail.whiteListUniv1
            fwWifiImg.isEnabled = !appInfoDetail.whiteListUniv1
            mPackageTextView.visibility = View.VISIBLE
            mPackageTextView.text = context.getString(R.string.firewall_app_added_in_whitelist)
        } else if (appInfoDetail.isExcluded) {
            fwWifiImg.isClickable = !appInfoDetail.isExcluded
            fwWifiImg.isEnabled = !appInfoDetail.isExcluded
            mPackageTextView.visibility = View.VISIBLE
            mPackageTextView.text = context.getString(R.string.firewall_app_added_in_excluded_list)
        } else {
            fwWifiImg.isClickable = !appInfoDetail.whiteListUniv1
            fwWifiImg.isEnabled = !appInfoDetail.whiteListUniv1
            mPackageTextView.visibility = View.GONE
        }

        //For WiFi
        if (appInfoDetail.isInternetAllowed) {
            fwWifiImg.isChecked = false
            mIconIndicator.setBackgroundColor(context.getColor(R.color.colorGreen_900))
        } else {
            fwWifiImg.isChecked = true
            mIconIndicator.setBackgroundColor(context.getColor(R.color.colorAmber_900))
        }

        fwWifiImg.setOnClickListener {
            fwWifiImg.isEnabled = false
            val isInternetAllowed = appInfoDetail.isInternetAllowed
            val appUIDList = appInfoRepository.getAppListForUID(appInfoDetail.uid)
            var blockAllApps = false
            if (appUIDList.size > 1) {
                blockAllApps = showDialog(appUIDList, appInfoDetail.appName, isInternetAllowed)
            }

            val activityManager: ActivityManager = context.getSystemService(Activity.ACTIVITY_SERVICE) as ActivityManager
            if (appUIDList.size <= 1 || blockAllApps) {
                object : CountDownTimer(500, 500) {
                    override fun onTick(millisUntilFinished: Long) {
                    }
                    override fun onFinish() {
                        fwWifiImg.isEnabled = true
                    }
                }.start()

                fwWifiImg.isEnabled = false
                fwWifiImg.isChecked = isInternetAllowed
                appInfoDetail.isWifiEnabled = !isInternetAllowed
                appInfoDetail.isInternetAllowed = !isInternetAllowed
                if (!isInternetAllowed)
                    mIconIndicator.setBackgroundColor(context.getColor(R.color.colorGreen_900))
                else
                    mIconIndicator.setBackgroundColor(context.getColor(R.color.colorAmber_900))
                val uid = appInfoDetail.uid
                fwWifiImg.isEnabled = true
                CoroutineScope(Dispatchers.IO).launch {
                    appUIDList.forEach {
                        HomeScreenActivity.GlobalVariable.appList[it.packageInfo]!!.isInternetAllowed = isInternetAllowed
                        persistentState.modifyAllowedWifi(it.packageInfo, !isInternetAllowed)
                        FirewallManager.updateAppInternetPermission(it.packageInfo, !isInternetAllowed)
                        FirewallManager.updateAppInternetPermissionByUID(it.uid, !isInternetAllowed)
                        categoryInfoRepository.updateNumberOfBlocked(it.appCategory,isInternetAllowed)

                        if(persistentState.killAppOnFirewall) {
                            try {
                                activityManager.killBackgroundProcesses(it.packageInfo)
                            } catch (e: Exception) {
                                Log.w(LOG_TAG_FIREWALL, "firewall - kill app - exception" + e.message, e)
                            }
                        }
                    }
                    appInfoRepository.updateInternetForUID(uid, !isInternetAllowed)

                }
            } else {
                fwWifiImg.isChecked = !isInternetAllowed
            }
        }

        fwWifiImg.setOnCheckedChangeListener(null)
        return convertView
    }


    override fun getChildrenCount(listPosition: Int): Int {
        return this.dataList[this.titleList[listPosition]]!!.size
    }

    override fun getGroup(listPosition: Int): CategoryInfo {
        return this.titleList[listPosition]
    }

    override fun getGroupCount(): Int {
        return this.titleList.size
    }

    override fun getGroupId(listPosition: Int): Long {
        return listPosition.toLong()
    }

    override fun getGroupView(listPosition: Int, isExpanded: Boolean, view: View?, parent: ViewGroup): View {
        var convertView = view
        val listTitle = getGroup(listPosition)
        if (convertView == null) {
            val layoutInflater =
                this.context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
            convertView = layoutInflater.inflate(R.layout.expandable_firewall_header, null)
        }

        val categoryNameTV: TextView = convertView!!.findViewById(R.id.expand_textView_category_name)
        val appCountTV: TextView = convertView.findViewById(R.id.expand_textView_app_count)
        val internetChk: AppCompatToggleButton = convertView.findViewById((R.id.expand_checkbox))
        val imageHolder1: AppCompatImageView = convertView.findViewById(R.id.imageLayout_1)
        val imageHolder2: AppCompatImageView = convertView.findViewById(R.id.imageLayout_2)
        val progressBar: ProgressBar = convertView.findViewById(R.id.expand_header_progress)
        val indicatorTV: TextView = convertView.findViewById(R.id.expand_header_category_indicator)
        val sysAppWarning: TextView = convertView.findViewById(R.id.expand_system_apps_warning)
        val placeHolder : TextView = convertView.findViewById(R.id.expand_system_place_holder)

        val numberOfApps = listTitle.numberOFApps
        categoryNameTV.text = "${listTitle.categoryName} ($numberOfApps)"
        val isInternetAllowed = !listTitle.isInternetBlocked

        internetChk.isChecked = !isInternetAllowed
        if (!isInternetAllowed) {
            internetChk.setCompoundDrawablesWithIntrinsicBounds(
                ContextCompat.getDrawable(context, R.drawable.dis_allowed),
                null,
                null,
                null
            )
        } else {
            internetChk.setCompoundDrawablesWithIntrinsicBounds(
                ContextCompat.getDrawable(context, R.drawable.allowed),
                null,
                null,
                null
            )
        }
        if (isInternetAllowed) {
            indicatorTV.visibility = View.INVISIBLE
        } else {
            indicatorTV.visibility = View.VISIBLE
        }

        if (listTitle.categoryName == APP_CAT_SYSTEM_APPS ) {
            sysAppWarning.text = context.getString(R.string.system_apps_warning)
            sysAppWarning.visibility = View.VISIBLE
            placeHolder.visibility = View.GONE
        } else if(listTitle.categoryName == APP_CAT_SYSTEM_COMPONENTS){
            sysAppWarning.text = context.getString(R.string.system_components_warning)
            sysAppWarning.visibility = View.VISIBLE
            if(isExpanded){
                placeHolder.visibility = View.GONE
            }else {
                placeHolder.visibility = View.VISIBLE
            }
        } else if(listTitle.categoryName == APP_NON_APP){
            sysAppWarning.text = context.getString(R.string.system_non_apps_warning)
            sysAppWarning.visibility = View.VISIBLE
            placeHolder.visibility = View.GONE
        }else{
            sysAppWarning.visibility = View.GONE
            placeHolder.visibility = View.GONE
        }
        appCountTV.text = context.getString(R.string.ct_app_details, listTitle.numOfAppsBlocked.toString(),
            listTitle.numOfAppWhitelisted.toString(), listTitle.numOfAppsExcluded.toString())

        val list = dataList[listTitle]
        try {
            if (list != null && list.isNotEmpty()) {
                if (numberOfApps != 0) {
                    if (numberOfApps >= 2) {
                        GlideApp.with(context).load(context.packageManager.getApplicationIcon(list[0].packageInfo))
                            .error(AppCompatResources.getDrawable(context, R.drawable.default_app_icon))
                            .into(imageHolder1)
                        GlideApp.with(context).load(context.packageManager.getApplicationIcon(list[1].packageInfo))
                            .error(AppCompatResources.getDrawable(context, R.drawable.default_app_icon))
                            .into(imageHolder2)
                    } else {
                        GlideApp.with(context).load(context.packageManager.getApplicationIcon(list[0].packageInfo))
                            .error(AppCompatResources.getDrawable(context, R.drawable.default_app_icon))
                            .into(imageHolder1)
                        imageHolder2.visibility = View.GONE
                    }
                } else {
                    imageHolder1.visibility = View.GONE
                    imageHolder2.visibility = View.GONE
                }
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG_FIREWALL, "One or more application icons are not available" + e.message)
            GlideApp.with(context).load(AppCompatResources.getDrawable(context, R.drawable.default_app_icon))
                .error(AppCompatResources.getDrawable(context, R.drawable.default_app_icon))
                .into(imageHolder1)
            if (numberOfApps == 1) {
                imageHolder2.visibility = View.GONE
            }else{
                GlideApp.with(context).load(AppCompatResources.getDrawable(context, R.drawable.default_app_icon))
                    .error(AppCompatResources.getDrawable(context, R.drawable.default_app_icon))
                    .into(imageHolder2)
            }
        }
        internetChk.setOnClickListener {
            var proceedBlock = if (listTitle.categoryName == APP_CAT_SYSTEM_APPS && isInternetAllowed) {
                if(listTitle.numOfAppWhitelisted != listTitle.numberOFApps) {
                    showDialogForSystemAppBlock(APP_CAT_SYSTEM_APPS)
                }else{
                    false
                }
            }else{
                true
            }
            if(listTitle.categoryName == APP_CAT_SYSTEM_COMPONENTS && isInternetAllowed){
                if(DEBUG) Log.d(LOG_TAG_FIREWALL, "Category block - System components, count: ${listTitle.numOfAppWhitelisted}, ${listTitle.numberOFApps}")
                proceedBlock = if(listTitle.numOfAppWhitelisted != listTitle.numberOFApps){
                    showDialogForSystemAppBlock(APP_CAT_SYSTEM_COMPONENTS)
                }else{
                    false
                }
            }
            if(listTitle.categoryName == APP_NON_APP && isInternetAllowed) {
                proceedBlock = if(listTitle.numOfAppWhitelisted != listTitle.numberOFApps){
                    showDialogForSystemAppBlock(APP_NON_APP)
                }else{
                    false
                }
            }
            if(proceedBlock) {
                Log.i(LOG_TAG_FIREWALL,"Blocking proceeded - ")
                internetChk.visibility = View.GONE
                progressBar.visibility = View.VISIBLE
                object : CountDownTimer(500, 500) {
                    override fun onTick(millisUntilFinished: Long) {
                    }

                    override fun onFinish() {
                        progressBar.visibility = View.GONE
                        internetChk.visibility = View.VISIBLE
                    }
                }.start()

                val isInternet = !listTitle.isInternetBlocked
                if (isInternet) {
                    indicatorTV.visibility = View.VISIBLE
                } else {
                    indicatorTV.visibility = View.INVISIBLE
                }
                FirewallManager.updateCategoryAppsInternetPermission(listTitle.categoryName, !isInternet, persistentState)

                GlobalScope.launch(Dispatchers.IO) {
                    val count = appInfoRepository.updateInternetForAppCategory(listTitle.categoryName, !isInternet)
                    if(DEBUG) Log.d(LOG_TAG_FIREWALL,"Apps updated : $count, $isInternet")
                    try {
                        if (count == listTitle.numberOFApps) {
                            categoryInfoRepository.updateCategoryInternet(listTitle.categoryName, isInternet)
                        } else {
                            categoryInfoRepository.updateBlockedCount(listTitle.categoryName, count)
                        }
                    }catch(e : Exception){
                        Log.w(LOG_TAG_FIREWALL,"Exception when inserting the category internet info: ${e.message}",e)
                    }
                }
            }else{
                if(DEBUG) Log.d(LOG_TAG_FIREWALL,"else - proceedBlock: $proceedBlock")
                internetChk.isChecked = proceedBlock
                internetChk.setCompoundDrawablesWithIntrinsicBounds(
                    ContextCompat.getDrawable(context, R.drawable.allowed), null, null, null)
            }
        }
        internetChk.setOnCheckedChangeListener(null)
        return convertView
    }

    override fun hasStableIds(): Boolean {
        return false
    }

    override fun isChildSelectable(listPosition: Int, expandedListPosition: Int): Boolean {
        return true
    }


    private fun showDialog(packageList: List<AppInfo>, appName: String, isInternet: Boolean): Boolean {
        val handler: Handler = ThrowingHandler()
        val positiveTxt : String
        val packageNameList: List<String> = packageList.map { it.appName }
        var proceedBlocking = false

        val builderSingle: AlertDialog.Builder = AlertDialog.Builder(context)

        builderSingle.setIcon(R.drawable.spinner_firewall)
        if (isInternet) {
            builderSingle.setTitle(context.getString(R.string.ctbs_block_other_apps, appName, packageList.size.toString()))
            positiveTxt = context.getString(R.string.ctbs_block_other_apps_positive_text, packageList.size.toString())
        } else {
            builderSingle.setTitle(context.getString(R.string.ctbs_unblock_other_apps, appName, packageList.size.toString()))
            positiveTxt = context.getString(R.string.ctbs_unblock_other_apps_positive_text, packageList.size.toString())
        }
        val arrayAdapter = ArrayAdapter<String>(
            context,
            android.R.layout.simple_list_item_activated_1
        )
        arrayAdapter.addAll(packageNameList)
        builderSingle.setCancelable(false)

        builderSingle.setItems(packageNameList.toTypedArray(), null)

        builderSingle.setPositiveButton(positiveTxt) { _: DialogInterface, _: Int ->
            proceedBlocking = true
            handler.sendMessage(handler.obtainMessage())
        }.setNeutralButton(context.getString(R.string.ctbs_dialog_negative_btn)) { _: DialogInterface, _: Int ->
            handler.sendMessage(handler.obtainMessage())
            proceedBlocking = false
        }

        val alertDialog: AlertDialog = builderSingle.show()
        alertDialog.listView.setOnItemClickListener { _, _, _, _ -> }
        alertDialog.setCancelable(false)
        try {
            Looper.loop()
        } catch (e2: java.lang.RuntimeException) {
        }

        return proceedBlocking
    }

    private fun showDialogForSystemAppBlock(isSysComponent : String): Boolean {
        //Change the handler logic into some other
        val handlerDelete: Handler = ThrowingHandler()
        var proceedBlocking = false

        val builderSingle: AlertDialog.Builder = AlertDialog.Builder(context)

        builderSingle.setIcon(R.drawable.spinner_firewall)

        if(isSysComponent == APP_CAT_SYSTEM_COMPONENTS){
            builderSingle.setTitle(context.resources.getString(R.string.system_components_warning_title))
            builderSingle.setMessage(context.resources.getString(R.string.system_components_warning))
        }else if(isSysComponent == APP_NON_APP){
            builderSingle.setTitle(context.resources.getString(R.string.system_non_app_warning_title))
            builderSingle.setMessage(context.resources.getString(R.string.system_non_apps_warning))
        }else{
            builderSingle.setTitle(context.resources.getString(R.string.system_apps_warning_title))
            builderSingle.setMessage(context.resources.getString(R.string.system_apps_warning))
        }


        builderSingle.setPositiveButton(context.resources.getString(R.string.system_apps_dialog_positive)) { _: DialogInterface, _: Int ->
            proceedBlocking = true
            handlerDelete.sendMessage(handlerDelete.obtainMessage())
        }.setNegativeButton(context.resources.getString(R.string.system_apps_dialog_negative)) { _: DialogInterface, _: Int ->
            handlerDelete.sendMessage(handlerDelete.obtainMessage())
            proceedBlocking = false
        }

        val alertDialog: AlertDialog = builderSingle.show()
        alertDialog.setCancelable(false)

        try {
            Looper.loop()
        } catch (e2: java.lang.RuntimeException) {
        }

        return proceedBlocking
    }
}
