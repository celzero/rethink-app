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
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.database.*
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.isSearchEnabled
import com.celzero.bravedns.util.Constants.Companion.APP_CAT_SYSTEM_APPS
import com.celzero.bravedns.util.Constants.Companion.APP_CAT_SYSTEM_COMPONENTS
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
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

/*    var completeList: List<AppInfo> = ArrayList<AppInfo>()
    var originalTitleList: List<CategoryInfo> = ArrayList()
    var originalDataList: HashMap<CategoryInfo, ArrayList<AppInfo>> = HashMap()*/

    override fun getChild(listPosition: Int, expandedListPosition: Int): AppInfo {
        return this.dataList[this.titleList[listPosition]]!![expandedListPosition]
    }

    override fun getChildId(listPosition: Int, expandedListPosition: Int): Long {
        return expandedListPosition.toLong()
    }

    fun updateData(title: List<CategoryInfo>, list: HashMap<CategoryInfo, ArrayList<AppInfo>>, completeList: ArrayList<AppInfo>) {
        //this.completeList = completeList
        titleList = title
        dataList = list
        this.notifyDataSetChanged()
        //originalTitleList = title
        //originalDataList = list
    }

    /**
     * Yet to complete the below function logic,
     * TODO : Filter the query string and update it in the list adapter for search
     */
    /*fun filterData(query: String) {
        titleList = originalTitleList
        dataList = originalDataList
        val searchResult = dataList
        if (query != "") {
            searchResult.clear()
            dataList.forEach {
                val normalList = it.value.filter { a -> a.appName.toLowerCase().contains(query.toLowerCase()) }
                printNormalList(normalList)
                if (normalList.isNotEmpty()) {
                    titleList = titleList.filter { titleList ->
                        titleList.categoryName.contains(
                            it.key.categoryName
                        )
                    }
                    searchResult[titleList[0]] = normalList as java.util.ArrayList<AppInfo>
                }
            }
            //if (searchResult.isNotEmpty())
            dataList = searchResult
        }
        this.notifyDataSetChanged()
    }

    private fun printNormalList(normalList: List<AppInfo>) {
        normalList.forEach {
            if(DEBUG) Log.d(LOG_TAG, "${it.appName} ... Category: ${it.appCategory}")
        }
    }*/

    override fun getChildView(listPosition: Int, expandedListPosition: Int, isLastChild: Boolean, convertView: View?, parent: ViewGroup): View {
        var convertView = convertView
        val appInfoDetail = getChild(listPosition, expandedListPosition)
        if (convertView == null) {
            val layoutInflater = this.context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
            convertView = layoutInflater.inflate(R.layout.apk_list_item, null)
        }
        //Not used
        val mLLTopLayout: LinearLayoutCompat = convertView!!.findViewById(R.id.firewall_apk_list_top_layout)
        val fwMobileDataImg: AppCompatImageView = convertView.findViewById(R.id.firewall_toggle_mobile_data)

        //Child View UI components
        val mIconImageView: ImageView = convertView.findViewById(R.id.firewall_apk_icon_iv)
        val mLabelTextView: TextView = convertView.findViewById(R.id.firewall_apk_label_tv)
        val mPackageTextView: TextView = convertView.findViewById(R.id.firewall_apk_package_tv)
        val mIconIndicator: TextView = convertView.findViewById(R.id.firewall_status_indicator)
        //val firewallSearchCard : CardView = convertView.findViewById(R.id.firewall_search_card)
        //val firewallSearch : androidx.appcompat.widget.SearchView = convertView.findViewById(R.id.firewall_search)

        val fwWifiImg: SwitchCompat = convertView.findViewById(R.id.firewall_toggle_wifi)
        val firewallApkProgressBar: ProgressBar = convertView.findViewById(R.id.firewall_apk_progress_bar)
        //var appIcon = context.resources.getDrawable(android.R.drawable.)



        try {
            //val appIcon = context.packageManager.getApplicationIcon(appInfoDetail.packageInfo)
            //mIconImageView.setImageDrawable(appIcon)
            Glide.with(context).load(context.packageManager.getApplicationIcon(appInfoDetail.packageInfo))
                .error(AppCompatResources.getDrawable(context, R.drawable.default_app_icon))
                .into(mIconImageView)
        } catch (e: Exception) {
            mIconImageView.setImageDrawable(context.getDrawable(R.drawable.default_app_icon))
            Glide.with(context).load(AppCompatResources.getDrawable(context, R.drawable.default_app_icon))
                .error(AppCompatResources.getDrawable(context, R.drawable.default_app_icon))
                .into(mIconImageView)
            Log.e(LOG_TAG, "Application Icon not available for package: ${appInfoDetail.packageInfo}" + e.message, e)
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


        //fwWifiImg.visibility = View.VISIBLE
        //For WiFi
        if (appInfoDetail.isInternetAllowed) {
            fwWifiImg.isChecked = false
            mIconIndicator.setBackgroundColor(context.getColor(R.color.colorGreen_900))
        } else {
            fwWifiImg.isChecked = true
            mIconIndicator.setBackgroundColor(context.getColor(R.color.colorAmber_900))
        }



        fwWifiImg.setOnClickListener {
            isSearchEnabled = false
            fwWifiImg.isEnabled = false
            val isInternetAllowed = appInfoDetail.isInternetAllowed
            val appUIDList = appInfoRepository.getAppListForUID(appInfoDetail.uid)
            var blockAllApps = false
            if (appUIDList.size > 1) {
                blockAllApps = showDialog(appUIDList, appInfoDetail.appName, isInternetAllowed)
            }

            val activityManager: ActivityManager = context.getSystemService(Activity.ACTIVITY_SERVICE) as ActivityManager
            if (appUIDList.size <= 1 || blockAllApps) {
                object : CountDownTimer(500, 250) {
                    override fun onTick(millisUntilFinished: Long) {
                        fwWifiImg.isEnabled = false
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
                                Log.e(LOG_TAG, "firewall - kill app - exception" + e.message, e)
                            }
                        }
                    }
                   /* val temp = appInfoDetail.appCategory
                    var list: CategoryInfo? = null
                    titleList.forEach {
                        if (it.categoryName == temp) {
                            list = it
                        }
                    }*/
                    /*if (list!!.isInternetBlocked) {
                        val categoryInfoRepository = mDb.categoryInfoRepository()
                        categoryInfoRepository.updateCategoryInternet(
                            list!!.categoryName,
                            false
                        )
                    }*/
                    appInfoRepository.updateInternetForuid(uid, !isInternetAllowed)

                }
            } else {
                fwWifiImg.isChecked = !isInternetAllowed
            }
            isSearchEnabled = true
            //mDb.close()
        }


      /*  mLabelTextView.setOnClickListener {
            val bottomSheetFragment = IPAppListBottomSheetFragment(context, appInfoDetail)
            val frag = context as FragmentActivity
            bottomSheetFragment.show(frag.supportFragmentManager, bottomSheetFragment.tag)
        }*/

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
        /*if (titleList.isEmpty()) {
            val mDb = AppDatabase.invoke(context.applicationContext)
            val appInfoRepository = mDb.appInfoRepository()
            this.titleList = appInfoRepository.getAllAppDetailsForLiveData()
            mDb.close()
        }*/
        return this.titleList.size
    }

    override fun getGroupId(listPosition: Int): Long {
        return listPosition.toLong()
    }

    override fun getGroupView(listPosition: Int, isExpanded: Boolean, convertView: View?, parent: ViewGroup): View {
        var convertView = convertView
        val listTitle = getGroup(listPosition)
        if (convertView == null) {
            val layoutInflater =
                this.context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
            convertView = layoutInflater.inflate(R.layout.expandable_firewall_header, null)
        }

        val categoryNameTV: TextView = convertView!!.findViewById(R.id.expand_textView_category_name)
        val appCountTV: TextView = convertView.findViewById(R.id.expand_textView_app_count)
        val internetChk: AppCompatToggleButton = convertView.findViewById((R.id.expand_checkbox))
        val imageHolderLL: LinearLayout = convertView.findViewById(R.id.imageLayout)
        val imageHolder1: AppCompatImageView = convertView.findViewById(R.id.imageLayout_1)
        val imageHolder2: AppCompatImageView = convertView.findViewById(R.id.imageLayout_2)
        val progressBar: ProgressBar = convertView.findViewById(R.id.expand_header_progress)
        val indicatorTV: TextView = convertView.findViewById(R.id.expand_header_category_indicator)
        val sysAppWarning: TextView = convertView.findViewById(R.id.expand_system_apps_warning)
        val placeHolder : TextView = convertView.findViewById(R.id.expand_system_place_holder)

        categoryNameTV.text = "${listTitle.categoryName} (${listTitle.numberOFApps})"
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
        } else{
            sysAppWarning.visibility = View.GONE
            placeHolder.visibility = View.GONE
        }

        val numberOfApps = listTitle.numberOFApps
        /*if (isInternetAllowed) {
            appCountTV.text = listTitle.numOfAppsBlocked.toString() + "/" + numberOfApps.toString() + " apps blocked"
        } else {
            appCountTV.text = numberOfApps.toString() + "/" + numberOfApps.toString() + " apps blocked"
        }*/
        appCountTV.text = "${listTitle.numOfAppsBlocked} blocked, ${listTitle.numOfAppWhitelisted} whitelisted, ${listTitle.numOfAppsExcluded} excluded."
        //appCountTV.text = "Blocked: ${listTitle.numOfAppsBlocked}, Whitelisted: ${listTitle.numOfAppWhitelisted},\nExcluded: ${listTitle.numOfAppsExcluded}, Total Apps: ${listTitle.numberOFApps} "

        val list = dataList[listTitle]
        try {
            if (list != null && list.isNotEmpty()) {
                if (numberOfApps != 0) {
                    if (numberOfApps >= 2) {
                        //imageHolder1.setImageDrawable(context.packageManager.getApplicationIcon(list[0].packageInfo))
                        //imageHolder2.setImageDrawable(context.packageManager.getApplicationIcon(list[1].packageInfo))
                        Glide.with(context).load(context.packageManager.getApplicationIcon(list[0].packageInfo))
                            .error(AppCompatResources.getDrawable(context, R.drawable.default_app_icon))
                            .into(imageHolder1)
                        Glide.with(context).load(context.packageManager.getApplicationIcon(list[1].packageInfo))
                            .error(AppCompatResources.getDrawable(context, R.drawable.default_app_icon))
                            .into(imageHolder2)
                    } else {
                        //imageHolder1.setImageDrawable(context.packageManager.getApplicationIcon(list[0].packageInfo))
                        Glide.with(context).load(context.packageManager.getApplicationIcon(list[0].packageInfo))
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
            Log.w(LOG_TAG, "One or more application icons are not available" + e.message, e)
        }
        internetChk.setOnClickListener {
            isSearchEnabled = false
            if(DEBUG) Log.d(LOG_TAG, "Category block clicked : $isSearchEnabled")
            var proceedBlock = false
            proceedBlock = if (listTitle.categoryName == APP_CAT_SYSTEM_APPS && isInternetAllowed) {
                showDialogForSystemAppBlock(false)
            }else{
                true
            }
            if(listTitle.categoryName == APP_CAT_SYSTEM_COMPONENTS && isInternetAllowed){
                val count = appInfoRepository.getWhitelistCount(listTitle.categoryName)
                if(DEBUG) Log.d(LOG_TAG, "Category block - System components, count: $count, ${listTitle.numberOFApps}")
                proceedBlock = if(count != listTitle.numberOFApps){
                    showDialogForSystemAppBlock(true)
                }else{
                    true
                }
            }
            if(proceedBlock) {
                Log.i(LOG_TAG,"Blocking proceeded - ")
                object : CountDownTimer(100, 500) {
                    override fun onTick(millisUntilFinished: Long) {
                        internetChk.visibility = View.GONE
                        progressBar.visibility = View.VISIBLE
                    }

                    override fun onFinish() {
                        progressBar.visibility = View.GONE
                        internetChk.visibility = View.VISIBLE
                        //notifyDataSetChanged()
                    }
                }.start()

                val isInternet = !listTitle.isInternetBlocked

                if (isInternet) {
                    indicatorTV.visibility = View.VISIBLE
                } else {
                    indicatorTV.visibility = View.INVISIBLE
                }
                FirewallManager.updateCategoryAppsInternetPermission(
                    listTitle.categoryName,
                    !isInternet,
                    context,
                    persistentState
                )

                GlobalScope.launch(Dispatchers.IO) {
                    val count = appInfoRepository.updateInternetForAppCategory(listTitle.categoryName, !isInternet)
                    if(DEBUG) Log.d(LOG_TAG,"Apps updated : $count, $isInternet")
                    //val count = appInfoRepository.getBlockedCountForCategory(listTitle.categoryName)
                    try {
                        if (count == listTitle.numberOFApps) {
                            categoryInfoRepository.updateCategoryInternet(listTitle.categoryName, isInternet)
                        } else {
                            categoryInfoRepository.updateBlockedCount(listTitle.categoryName, count)
                        }
                    }catch(e : Exception){
                        Log.e(LOG_TAG,"Exception when inserting the category internet info: ${e.message}",e)
                    }
                    isSearchEnabled = true
                    if(DEBUG) Log.d(LOG_TAG, "Category block completed : $isSearchEnabled")
                    //mDb.close()
                }
            }else{
                Log.d(LOG_TAG,"else - proceedBlock: $proceedBlock")
                internetChk.isChecked = proceedBlock
                internetChk.setCompoundDrawablesWithIntrinsicBounds(
                    context.getDrawable(R.drawable.dis_allowed),
                    null,
                    null,
                    null
                )
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
        //Change the handler logic into some other
        val handler: Handler = ThrowingHandler()
        var positiveTxt = ""
        val packageNameList: List<String> = packageList.map { it.appName }
        var proceedBlocking: Boolean = false

        val builderSingle: AlertDialog.Builder = AlertDialog.Builder(context)

        builderSingle.setIcon(R.drawable.spinner_firewall)
        if (isInternet) {
            builderSingle.setTitle("Blocking \"$appName\" will also block these ${packageList.size} apps")
            positiveTxt = "Block ${packageList.size} apps"
        } else {
            builderSingle.setTitle("Unblocking \"$appName\" will also unblock these ${packageList.size} apps")
            positiveTxt = "Unblock ${packageList.size} apps"
        }
        val arrayAdapter = ArrayAdapter<String>(
            context,
            android.R.layout.simple_list_item_activated_1
        )
        arrayAdapter.addAll(packageNameList)
        builderSingle.setCancelable(false)
        //builderSingle.setSingleChoiceItems(arrayAdapter,-1,({dialogInterface: DialogInterface, which : Int ->}))
        builderSingle.setItems(packageNameList.toTypedArray(), null)


        /* builderSingle.setAdapter(arrayAdapter) { dialogInterface, which ->
              Log.d(LOG_TAG,"OnClick")
             //dialogInterface.cancel()
             //builderSingle.setCancelable(false)
         }*/
        /*val alertDialog : AlertDialog = builderSingle.create()
        alertDialog.getListView().setOnItemClickListener({ adapterView, subview, i, l -> })*/
        builderSingle.setPositiveButton(
            positiveTxt
        ) { dialogInterface: DialogInterface, i: Int ->
            proceedBlocking = true
            handler.sendMessage(handler.obtainMessage())
        }.setNeutralButton(
            "Go Back"
        ) { dialogInterface: DialogInterface, i: Int ->
            handler.sendMessage(handler.obtainMessage())
            proceedBlocking = false
        }

        val alertDialog: AlertDialog = builderSingle.show()
        alertDialog.listView.setOnItemClickListener { adapterView, subview, i, l -> }
        alertDialog.setCancelable(false)
        try {
            Looper.loop()
        } catch (e2: java.lang.RuntimeException) {
        }

        return proceedBlocking
    }

    private fun showDialogForSystemAppBlock(isSysComponent : Boolean): Boolean {
        //Change the handler logic into some other
        val handlerDelete: Handler = ThrowingHandler()
        var proceedBlocking: Boolean = false

        val builderSingle: AlertDialog.Builder = AlertDialog.Builder(context)

        builderSingle.setIcon(R.drawable.spinner_firewall)

        if(isSysComponent){
            builderSingle.setTitle("Blocking System Components")
            builderSingle.setMessage(context.resources.getString(R.string.system_components_warning))
        }else{
            builderSingle.setTitle("Blocking System Apps")
            builderSingle.setMessage(context.resources.getString(R.string.system_apps_warning))
        }


        builderSingle.setPositiveButton(
            "Proceed"
        ) { dialogInterface: DialogInterface, i: Int ->
            proceedBlocking = true
            handlerDelete.sendMessage(handlerDelete.obtainMessage())
        }.setNegativeButton(
            "Go Back"
        ) { dialogInterface: DialogInterface, i: Int ->
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


    /*override fun onQueryTextSubmit(query: String?): Boolean {
        val filteredList = dataList[titleList.last()]!!.filter { a -> a.appName.toLowerCase().contains(query!!.toLowerCase()) }
        if(filteredList.isNotEmpty()) {
            dataList[titleList.last()]!!.clear()
            dataList[titleList.last()]!!.addAll(filteredList)
            notifyDataSetChanged()
        }
        return false
    }

    override fun onQueryTextChange(newText: String?): Boolean {
        val filteredList = dataList[titleList.last()]!!.filter { a -> a.appName.toLowerCase().contains(newText!!.toLowerCase()) }
        dataList[titleList.last()]!!.clear()
        dataList[titleList.last()]!!.addAll(filteredList)
        //notifyDataSetChanged()
        return true
    }*/

}
