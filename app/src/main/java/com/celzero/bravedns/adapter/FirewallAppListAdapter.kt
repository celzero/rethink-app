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

import android.app.ActivityManager
import android.content.Context
import android.content.DialogInterface
import android.graphics.drawable.Drawable
import android.net.VpnService
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
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
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_FIREWALL
import com.celzero.bravedns.util.ThrowingHandler
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.getDefaultIcon
import com.celzero.bravedns.util.Utilities.Companion.getIcon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*


class FirewallAppListAdapter internal constructor(private val context: Context,
                                                  private val appInfoRepository: AppInfoRepository,
                                                  private val categoryInfoRepository: CategoryInfoRepository,
                                                  private val persistentState: PersistentState,
                                                  private var titleList: List<CategoryInfo>,
                                                  private var dataList: HashMap<CategoryInfo, ArrayList<AppInfo>>) :
        BaseExpandableListAdapter() {

    private var activityManager: ActivityManager = context.getSystemService(
        VpnService.ACTIVITY_SERVICE) as ActivityManager

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


    override fun getChildView(listPosition: Int, expandedListPosition: Int, isLastChild: Boolean,
                              view: View?, parent: ViewGroup): View {
        var convertView = view
        val appInfoDetail = getChild(listPosition, expandedListPosition)
        if (convertView == null) {
            val layoutInflater = this.context.getSystemService(
                Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
            convertView = layoutInflater.inflate(R.layout.apk_list_item, null)
        }

        //Child View UI components
        val mIconImageView: ImageView = convertView!!.findViewById(R.id.firewall_apk_icon_iv)
        val mLabelTextView: TextView = convertView.findViewById(R.id.firewall_apk_label_tv)
        val mPackageTextView: TextView = convertView.findViewById(R.id.firewall_apk_package_tv)
        val mIconIndicator: TextView = convertView.findViewById(R.id.firewall_status_indicator)
        val fwWifiImg: SwitchCompat = convertView.findViewById(R.id.firewall_toggle_wifi)
        val firewallApkProgressBar: ProgressBar = convertView.findViewById(
            R.id.firewall_apk_progress_bar)

        mLabelTextView.text = appInfoDetail.appName
        firewallApkProgressBar.visibility = View.GONE

        // To disable the app from selecting into firewall
        fwWifiImg.isClickable = !appInfoDetail.whiteListUniv1 || !appInfoDetail.isExcluded
        fwWifiImg.isEnabled = !appInfoDetail.whiteListUniv1 || !appInfoDetail.isExcluded
        fwWifiImg.isChecked = !appInfoDetail.isInternetAllowed

        displayIcon(getIcon(context, appInfoDetail.packageInfo, appInfoDetail.appName),
                    mIconImageView)
        showVisualHint(appInfoDetail.isInternetAllowed, mIconIndicator)

        when {
            appInfoDetail.whiteListUniv1 -> {
                showAppTextualHint(mPackageTextView,
                                   context.getString(R.string.firewall_app_added_in_whitelist))
            }
            appInfoDetail.isExcluded -> {
                showAppTextualHint(mPackageTextView,
                                   context.getString(R.string.firewall_app_added_in_excluded_list))
            }
            else -> {
                hideAppTextualHint(mPackageTextView)
            }
        }

        // Click listener
        fwWifiImg.setOnClickListener {
            fwWifiImg.isEnabled = false
            object : CountDownTimer(1000, 1000) {
                override fun onTick(millisUntilFinished: Long) {
                }

                override fun onFinish() {
                    fwWifiImg.isEnabled = true
                }
            }.start()

            val isInternetAllowed = appInfoDetail.isInternetAllowed
            val appUIDList = appInfoRepository.getAppListForUID(appInfoDetail.uid)

            if (appUIDList.size > 1) {
                val blockAllApps = showDialog(appUIDList, appInfoDetail.appName, isInternetAllowed)

                if (!blockAllApps) {
                    fwWifiImg.isChecked = !isInternetAllowed
                    return@setOnClickListener
                }
            }

            fwWifiImg.isChecked = isInternetAllowed
            appInfoDetail.isWifiEnabled = !isInternetAllowed
            appInfoDetail.isInternetAllowed = !isInternetAllowed
            if (!isInternetAllowed) mIconIndicator.setBackgroundColor(
                context.getColor(R.color.colorGreen_900))
            else mIconIndicator.setBackgroundColor(context.getColor(R.color.colorAmber_900))
            persistFirewallRules(appUIDList, isInternetAllowed, appInfoDetail.uid)
        }

        fwWifiImg.setOnCheckedChangeListener(null)
        return convertView
    }

    private fun persistFirewallRules(appUIDList: List<AppInfo>, isInternetAllowed: Boolean,
                                     uid: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            appUIDList.forEach {
                HomeScreenActivity.GlobalVariable.appList[it.packageInfo]!!.isInternetAllowed = isInternetAllowed
                persistentState.modifyAllowedWifi(it.packageInfo, !isInternetAllowed)
                FirewallManager.updateAppInternetPermission(it.packageInfo, !isInternetAllowed)
                FirewallManager.updateAppInternetPermissionByUID(it.uid, !isInternetAllowed)
                categoryInfoRepository.updateNumberOfBlocked(it.appCategory, isInternetAllowed)

                if (persistentState.killAppOnFirewall) {
                    Utilities.killBg(activityManager, it.packageInfo)
                }
            }
            appInfoRepository.updateInternetForUID(uid, !isInternetAllowed)

        }
    }

    private fun showVisualHint(internetAllowed: Boolean, mIconIndicator: TextView) {
        if (internetAllowed) {
            mIconIndicator.setBackgroundColor(context.getColor(R.color.colorGreen_900))
        } else {
            mIconIndicator.setBackgroundColor(context.getColor(R.color.colorAmber_900))
        }
    }

    private fun displayIcon(drawable: Drawable?, mIconImageView: ImageView) {
        GlideApp.with(context).load(drawable).error(getDefaultIcon(context)).into(mIconImageView)
    }

    private fun hideAppTextualHint(mPackageTextView: TextView) {
        mPackageTextView.visibility = View.GONE
    }

    private fun showAppTextualHint(mPackageTextView: TextView, message: String) {
        mPackageTextView.visibility = View.VISIBLE
        mPackageTextView.text = message
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

    override fun getGroupView(listPosition: Int, isExpanded: Boolean, view: View?,
                              parent: ViewGroup): View {
        var convertView = view
        val categoryInfo = getGroup(listPosition)
        if (convertView == null) {
            val layoutInflater = this.context.getSystemService(
                Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
            convertView = layoutInflater.inflate(R.layout.expandable_firewall_header, null)
        }

        val categoryNameTV: TextView = convertView!!.findViewById(
            R.id.expand_textView_category_name)
        val appCountTV: TextView = convertView.findViewById(R.id.expand_textView_app_count)
        val internetChk: AppCompatToggleButton = convertView.findViewById((R.id.expand_checkbox))
        val app1Icon: AppCompatImageView = convertView.findViewById(R.id.imageLayout_1)
        val app2Icon: AppCompatImageView = convertView.findViewById(R.id.imageLayout_2)
        val progressBar: ProgressBar = convertView.findViewById(R.id.expand_header_progress)
        val indicatorTV: TextView = convertView.findViewById(R.id.expand_header_category_indicator)
        val sysAppWarning: TextView = convertView.findViewById(R.id.expand_system_apps_warning)
        val placeHolder: TextView = convertView.findViewById(R.id.expand_system_place_holder)

        val isInternetAllowed = !categoryInfo.isInternetBlocked

        categoryNameTV.text = "${categoryInfo.categoryName} (${categoryInfo.numberOFApps})"
        appCountTV.text = context.getString(R.string.ct_app_details,
                                            categoryInfo.numOfAppsBlocked.toString(),
                                            categoryInfo.numOfAppWhitelisted.toString(),
                                            categoryInfo.numOfAppsExcluded.toString())
        internetChk.isChecked = !isInternetAllowed

        showToggleButtonIcon(isInternetAllowed, indicatorTV, internetChk)
        displaySystemWarning(categoryInfo.categoryName, sysAppWarning, placeHolder, isExpanded)
        showAppIcon(categoryInfo, app1Icon, app2Icon)

        // Click listener
        internetChk.setOnClickListener {
            // TODO - Identify whether the below logic can be simplified.
            val shouldBlock = if (isInternetAllowed && isAnySystemCategory(
                    categoryInfo.categoryName)) {
                if (categoryInfo.numOfAppWhitelisted != categoryInfo.numberOFApps) {
                    showDialogForSystemAppBlock(categoryInfo.categoryName)
                } else {
                    false
                }
            } else {
                true
            }

            if (!shouldBlock) {
                internetChk.isChecked = false
                internetChk.setCompoundDrawablesWithIntrinsicBounds(
                    ContextCompat.getDrawable(context, R.drawable.allowed), null, null, null)
            } else {
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

                val isInternet = !categoryInfo.isInternetBlocked
                if (isInternet) {
                    indicatorTV.visibility = View.VISIBLE
                } else {
                    indicatorTV.visibility = View.INVISIBLE
                }
                persistAppDetails(categoryInfo, isInternet)
            }
        }

        internetChk.setOnCheckedChangeListener(null)
        return convertView
    }

    private fun persistAppDetails(categoryInfo: CategoryInfo, isInternet: Boolean) {
        FirewallManager.updateCategoryAppsInternetPermission(categoryInfo.categoryName, !isInternet)

        CoroutineScope(Dispatchers.IO).launch {
            val count = appInfoRepository.updateInternetForAppCategory(categoryInfo.categoryName,
                                                                       !isInternet)
            if (DEBUG) Log.d(LOG_TAG_FIREWALL, "Apps updated : $count, $isInternet")
            try {
                if (count == categoryInfo.numberOFApps) {
                    categoryInfoRepository.updateCategoryInternet(categoryInfo.categoryName,
                                                                  isInternet)
                } else {
                    categoryInfoRepository.updateBlockedCount(categoryInfo.categoryName, count)
                }
            } catch (e: Exception) {
                Log.w(LOG_TAG_FIREWALL,
                      "Failure inserting the category internet info: ${e.message}", e)
            }
        }
    }

    private fun showAppIcon(categoryInfo: CategoryInfo, app1Icon: AppCompatImageView,
                            app2Icon: AppCompatImageView) {
        val list = dataList[categoryInfo]

        if (list.isNullOrEmpty()) {
            hide(app1Icon)
            hide(app2Icon)
            return
        }

        when {
            list.size == 1 -> {
                show(app1Icon)
                hide(app2Icon)
                loadIcon(list[0].packageInfo, app1Icon)
            }
            list.size > 1 -> {
                show(app1Icon)
                show(app2Icon)
                loadIcon(list[0].packageInfo, app1Icon)
                loadIcon(list[1].packageInfo, app2Icon)
            }
        }
    }

    private fun showToggleButtonIcon(isInternetAllowed: Boolean, indicatorTV: TextView,
                                     internetChk: AppCompatToggleButton) {
        if (!isInternetAllowed) {
            indicatorTV.visibility = View.VISIBLE
            internetChk.setCompoundDrawablesWithIntrinsicBounds(
                ContextCompat.getDrawable(context, R.drawable.dis_allowed), null, null, null)
        } else {
            indicatorTV.visibility = View.INVISIBLE
            internetChk.setCompoundDrawablesWithIntrinsicBounds(
                ContextCompat.getDrawable(context, R.drawable.allowed), null, null, null)
        }
    }

    private fun displaySystemWarning(categoryName: String, sysAppWarning: TextView,
                                     placeHolder: TextView, isExpanded: Boolean) {

        if (isAnySystemCategory(categoryName)) {
            showAppWarningText(sysAppWarning, categoryName)
        } else {
            hideAppWarningText(sysAppWarning)
        }

        hidePlaceHolder(placeHolder)

        if (categoryName == APP_CAT_SYSTEM_COMPONENTS && !isExpanded) {
            showPlaceHolder(placeHolder)
        }
    }

    private fun showAppWarningText(sysAppWarning: TextView, categoryName: String) {
        sysAppWarning.text = context.getString(R.string.system_app_block_warning,
                                               categoryName.toLowerCase(Locale.ROOT))
        sysAppWarning.visibility = View.VISIBLE
    }

    private fun hideAppWarningText(sysAppWarning: TextView) {
        sysAppWarning.visibility = View.GONE
    }

    private fun showPlaceHolder(placeHolder: TextView) {
        placeHolder.visibility = View.VISIBLE
    }

    private fun hidePlaceHolder(placeHolder: TextView) {
        placeHolder.visibility = View.GONE
    }

    private fun isAnySystemCategory(categoryName: String): Boolean {
        return APP_CAT_SYSTEM_COMPONENTS == categoryName || APP_NON_APP == categoryName || APP_CAT_SYSTEM_APPS == categoryName
    }

    private fun show(view: View) {
        view.visibility = View.VISIBLE
    }

    private fun hide(view: View) {
        view.visibility = View.INVISIBLE
    }

    private fun loadIcon(packageInfo: String, imageView: AppCompatImageView) {
        return loadImage(getIcon(context, packageInfo, ""), getDefaultIcon(context), imageView)
    }

    private fun loadImage(drawable: Drawable?, defaultDrawable: Drawable?,
                          imageView: AppCompatImageView) {
        GlideApp.with(context).load(drawable).error(defaultDrawable).into(imageView)
    }

    override fun hasStableIds(): Boolean {
        return false
    }

    override fun isChildSelectable(listPosition: Int, expandedListPosition: Int): Boolean {
        return true
    }

    private fun showDialog(packageList: List<AppInfo>, appName: String,
                           isInternet: Boolean): Boolean {
        val handler: Handler = ThrowingHandler()
        val positiveTxt: String
        val packageNameList: List<String> = packageList.map { it.appName }
        var proceedBlocking = false

        val builderSingle: AlertDialog.Builder = AlertDialog.Builder(context)

        builderSingle.setIcon(R.drawable.spinner_firewall)
        positiveTxt = if (isInternet) {
            builderSingle.setTitle(context.getString(R.string.ctbs_block_other_apps, appName,
                                                     packageList.size.toString()))
            context.getString(R.string.ctbs_block_other_apps_positive_text,
                              packageList.size.toString())
        } else {
            builderSingle.setTitle(context.getString(R.string.ctbs_unblock_other_apps, appName,
                                                     packageList.size.toString()))
            context.getString(R.string.ctbs_unblock_other_apps_positive_text,
                              packageList.size.toString())
        }
        val arrayAdapter = ArrayAdapter<String>(context,
                                                android.R.layout.simple_list_item_activated_1)
        arrayAdapter.addAll(packageNameList)
        builderSingle.setCancelable(false)

        builderSingle.setItems(packageNameList.toTypedArray(), null)

        builderSingle.setPositiveButton(positiveTxt) { _: DialogInterface, _: Int ->
            proceedBlocking = true
            handler.sendMessage(handler.obtainMessage())
        }.setNeutralButton(
            context.getString(R.string.ctbs_dialog_negative_btn)) { _: DialogInterface, _: Int ->
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

    private fun showDialogForSystemAppBlock(categoryName: String): Boolean {
        //Change the handler logic into some other
        val handlerDelete: Handler = ThrowingHandler()
        var proceedBlocking = false

        val builderSingle: AlertDialog.Builder = AlertDialog.Builder(context)

        builderSingle.setIcon(R.drawable.spinner_firewall)

        builderSingle.setTitle(
            context.resources.getString(R.string.system_apps_warning_dialog_title, categoryName))
        builderSingle.setMessage(context.resources.getString(R.string.system_app_block_warning,
                                                             categoryName.toLowerCase(Locale.ROOT)))

        builderSingle.setPositiveButton(context.resources.getString(
            R.string.system_apps_dialog_positive)) { _: DialogInterface, _: Int ->
            proceedBlocking = true
            handlerDelete.sendMessage(handlerDelete.obtainMessage())
        }.setNegativeButton(context.resources.getString(
            R.string.system_apps_dialog_negative)) { _: DialogInterface, _: Int ->
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
