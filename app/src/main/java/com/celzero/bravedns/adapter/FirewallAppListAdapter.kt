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
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatToggleButton
import androidx.core.content.ContextCompat
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.AppInfoRepository
import com.celzero.bravedns.database.CategoryInfo
import com.celzero.bravedns.database.CategoryInfoRepository
import com.celzero.bravedns.databinding.ApkListItemBinding
import com.celzero.bravedns.databinding.ExpandableFirewallHeaderBinding
import com.celzero.bravedns.glide.GlideApp
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants.Companion.APP_CAT_SYSTEM_COMPONENTS
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_FIREWALL
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

    private lateinit var groupViewBinding: ExpandableFirewallHeaderBinding
    private lateinit var childViewBinding: ApkListItemBinding

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

        val appInfo = getChild(listPosition, expandedListPosition)
        childViewBinding = ApkListItemBinding.inflate(LayoutInflater.from(parent.context), parent,
                                                      false)

        displayChildDetails(appInfo)
        setupChildClickListeners(appInfo)

        return childViewBinding.root
    }

    private fun displayChildDetails(appInfo: AppInfo) {
        childViewBinding.firewallApkLabelTv.text = appInfo.appName
        childViewBinding.firewallApkProgressBar.visibility = View.GONE

        // To disable the app from selecting into firewall
        childViewBinding.firewallToggleWifi.isClickable = appInfo.canFirewall()
        childViewBinding.firewallToggleWifi.isEnabled = appInfo.canFirewall()
        childViewBinding.firewallToggleWifi.isChecked = !appInfo.isInternetAllowed

        displayIcon(getIcon(context, appInfo.packageInfo, appInfo.appName),
                    childViewBinding.firewallApkIconIv)
        showHint(childViewBinding.firewallStatusIndicator, appInfo)


    }

    private fun setupChildClickListeners(appInfo: AppInfo) {

        childViewBinding.firewallToggleWifi.setOnClickListener {
            childViewBinding.firewallToggleWifi.isEnabled = false
            Utilities.delay(1000) {
                childViewBinding.firewallToggleWifi.isEnabled = true
            }

            val isInternetAllowed = appInfo.isInternetAllowed
            val appUIDList = appInfoRepository.getAppListForUID(appInfo.uid)

            if (appUIDList.size > 1) {
                showDialog(appUIDList, appInfo, isInternetAllowed)
                return@setOnClickListener
            }
            updateBlockApp(appInfo, appUIDList, isInternetAllowed)
        }

        childViewBinding.firewallToggleWifi.setOnCheckedChangeListener(null)
    }

    private fun updateBlockApp(appInfo: AppInfo, appUIDList: List<AppInfo>,
                               isInternetAllowed: Boolean) {
        childViewBinding.firewallToggleWifi.isChecked = isInternetAllowed
        appInfo.isWifiEnabled = !isInternetAllowed
        appInfo.isInternetAllowed = !isInternetAllowed
        if (!isInternetAllowed) childViewBinding.firewallStatusIndicator.setBackgroundColor(
            context.getColor(R.color.colorGreen_900))
        else childViewBinding.firewallStatusIndicator.setBackgroundColor(
            context.getColor(R.color.colorAmber_900))
        persistFirewallRules(appUIDList, isInternetAllowed, appInfo.uid)
    }

    private fun persistFirewallRules(appUIDList: List<AppInfo>, isInternetAllowed: Boolean,
                                     uid: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            appUIDList.forEach {
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

    private fun showHint(mIconIndicator: TextView, appInfo: AppInfo) {
        if (appInfo.isInternetAllowed) {
            mIconIndicator.setBackgroundColor(context.getColor(R.color.colorGreen_900))
        } else {
            mIconIndicator.setBackgroundColor(context.getColor(R.color.colorAmber_900))
        }

        when {
            appInfo.whiteListUniv1 -> {
                showAppTextualHint(childViewBinding.firewallApkPackageTv,
                                   context.getString(R.string.firewall_app_added_in_whitelist))
            }
            appInfo.isExcluded -> {
                showAppTextualHint(childViewBinding.firewallApkPackageTv,
                                   context.getString(R.string.firewall_app_added_in_excluded_list))
            }
            else -> {
                hideAppTextualHint(childViewBinding.firewallApkPackageTv)
            }
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
        val categoryInfo = getGroup(listPosition)
        groupViewBinding = ExpandableFirewallHeaderBinding.inflate(
            LayoutInflater.from(parent.context), parent, false)

        //val isInternetAllowed = !categoryInfo.isInternetBlocked

        displayGroupDetails(categoryInfo, isExpanded)
        setUpGroupClickListeners(categoryInfo)
        return groupViewBinding.root
    }

    private fun displayGroupDetails(categoryInfo: CategoryInfo, isExpanded: Boolean) {
        groupViewBinding.expandTextViewCategoryName.text = "${categoryInfo.categoryName} (${categoryInfo.numberOFApps})"
        groupViewBinding.expandTextViewAppCount.text = context.getString(R.string.ct_app_details,
                                                                         categoryInfo.numOfAppsBlocked.toString(),
                                                                         categoryInfo.numOfAppWhitelisted.toString(),
                                                                         categoryInfo.numOfAppsExcluded.toString())
        groupViewBinding.expandCheckbox.isChecked = categoryInfo.isInternetBlocked

        showToggleButtonIcon(categoryInfo.isInternetBlocked,
                             groupViewBinding.expandHeaderCategoryIndicator,
                             groupViewBinding.expandCheckbox)
        displaySystemWarning(categoryInfo, groupViewBinding.expandSystemAppsWarning,
                             groupViewBinding.expandSystemPlaceHolder, isExpanded)
        showAppIcon(categoryInfo, groupViewBinding.imageLayout1, groupViewBinding.imageLayout2)
    }

    private fun setUpGroupClickListeners(categoryInfo: CategoryInfo) {

        groupViewBinding.expandCheckbox.setOnClickListener {
            // Click listener- Flip the internet blocked value of categoryInfo.
            val isInternetBlocked = !categoryInfo.isInternetBlocked
            if (categoryInfo.isAnySystemCategory()) {
                if (categoryInfo.numOfAppWhitelisted != categoryInfo.numberOFApps) {
                    showDialogForSystemAppBlock(categoryInfo)
                    return@setOnClickListener
                }
            }
            updateCategoryDetails(categoryInfo, isInternetBlocked)
        }

        groupViewBinding.expandCheckbox.setOnCheckedChangeListener(null)
    }

    private fun updateCategoryDetails(categoryInfo: CategoryInfo, isInternetBlocked: Boolean) {
        groupViewBinding.expandCheckbox.visibility = View.GONE
        groupViewBinding.expandHeaderCategoryIndicator.visibility = View.VISIBLE
        Utilities.delay(500) {
            groupViewBinding.expandHeaderCategoryIndicator.visibility = View.GONE
            groupViewBinding.expandCheckbox.visibility = View.VISIBLE
        }

        if (isInternetBlocked) {
            groupViewBinding.expandHeaderCategoryIndicator.visibility = View.VISIBLE
        } else {
            groupViewBinding.expandHeaderCategoryIndicator.visibility = View.INVISIBLE
        }
        persistAppDetails(categoryInfo, isInternetBlocked)
    }

    private fun persistAppDetails(categoryInfo: CategoryInfo, isInternetBlocked: Boolean) {
        FirewallManager.updateCategoryAppsInternetPermission(categoryInfo.categoryName,
                                                             isInternetBlocked)

        CoroutineScope(Dispatchers.IO).launch {
            // Flip the value of isInternetBlocked while updating appInfoRepository.
            // As column used in AppInfo is isInternet where in categoryInfo its internetBlocked.
            val count = appInfoRepository.setInternetAllowedForCategory(categoryInfo.categoryName,
                                                                        !isInternetBlocked)
            if (DEBUG) Log.d(LOG_TAG_FIREWALL, "Apps updated : $count, $isInternetBlocked")
            // Update the category's internet blocked based on the app's count which is returned
            // from the app info database.
            categoryInfoRepository.updateCategoryDetails(categoryInfo.categoryName, count,
                                                         isInternetBlocked)
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

    private fun showToggleButtonIcon(isInternetBlocked: Boolean, indicatorTV: TextView,
                                     internetChk: AppCompatToggleButton) {
        if (isInternetBlocked) {
            indicatorTV.visibility = View.VISIBLE
            internetChk.setCompoundDrawablesWithIntrinsicBounds(
                ContextCompat.getDrawable(context, R.drawable.dis_allowed), null, null, null)
        } else {
            indicatorTV.visibility = View.INVISIBLE
            internetChk.setCompoundDrawablesWithIntrinsicBounds(
                ContextCompat.getDrawable(context, R.drawable.allowed), null, null, null)
        }
    }

    private fun displaySystemWarning(categoryInfo: CategoryInfo, sysAppWarning: TextView,
                                     placeHolder: TextView, isExpanded: Boolean) {

        if (categoryInfo.isAnySystemCategory()) {
            showAppWarningText(sysAppWarning, categoryInfo.categoryName)
        } else {
            hideAppWarningText(sysAppWarning)
        }

        hidePlaceHolder(placeHolder)

        if (categoryInfo.categoryName == APP_CAT_SYSTEM_COMPONENTS && !isExpanded) {
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

    private fun showDialog(packageList: List<AppInfo>, appInfo: AppInfo, isInternet: Boolean) {
        val positiveTxt: String
        val packageNameList: List<String> = packageList.map { it.appName }

        val builderSingle: AlertDialog.Builder = AlertDialog.Builder(context)

        builderSingle.setIcon(R.drawable.spinner_firewall)
        positiveTxt = if (isInternet) {
            builderSingle.setTitle(
                context.getString(R.string.ctbs_block_other_apps, appInfo.appName,
                                  packageList.size.toString()))
            context.getString(R.string.ctbs_block_other_apps_positive_text,
                              packageList.size.toString())
        } else {
            builderSingle.setTitle(
                context.getString(R.string.ctbs_unblock_other_apps, appInfo.appName,
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
            updateBlockApp(appInfo, packageList, isInternet)
        }.setNeutralButton(
            context.getString(R.string.ctbs_dialog_negative_btn)) { _: DialogInterface, _: Int ->
            childViewBinding.firewallToggleWifi.isChecked = !isInternet
        }

        val alertDialog: AlertDialog = builderSingle.show()
        alertDialog.listView.setOnItemClickListener { _, _, _, _ -> }
        alertDialog.setCancelable(false)
    }

    private fun showDialogForSystemAppBlock(categoryInfo: CategoryInfo) {
        val builderSingle: AlertDialog.Builder = AlertDialog.Builder(context)

        builderSingle.setIcon(R.drawable.spinner_firewall)

        builderSingle.setTitle(
            context.resources.getString(R.string.system_apps_warning_dialog_title,
                                        categoryInfo.categoryName))
        builderSingle.setMessage(context.resources.getString(R.string.system_app_block_warning,
                                                             categoryInfo.categoryName.toLowerCase(
                                                                 Locale.ROOT)))

        builderSingle.setPositiveButton(context.resources.getString(
            R.string.system_apps_dialog_positive)) { _: DialogInterface, _: Int ->
            updateCategoryDetails(categoryInfo, true)
        }.setNegativeButton(context.resources.getString(
            R.string.system_apps_dialog_negative)) { _: DialogInterface, _: Int ->
            groupViewBinding.expandCheckbox.isChecked = false
            groupViewBinding.expandCheckbox.setCompoundDrawablesWithIntrinsicBounds(
                ContextCompat.getDrawable(context, R.drawable.allowed), null, null, null)
        }

        val alertDialog: AlertDialog = builderSingle.show()
        alertDialog.setCancelable(false)
    }
}
