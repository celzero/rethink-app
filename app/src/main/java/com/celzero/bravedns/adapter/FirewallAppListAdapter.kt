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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatToggleButton
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.CategoryInfo
import com.celzero.bravedns.database.CategoryInfoRepository
import com.celzero.bravedns.databinding.ApkListItemBinding
import com.celzero.bravedns.databinding.ExpandableFirewallHeaderBinding
import com.celzero.bravedns.glide.GlideApp
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.getDefaultIcon
import com.celzero.bravedns.util.Utilities.Companion.getIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*
import java.util.concurrent.TimeUnit

class FirewallAppListAdapter internal constructor(private val context: Context,
                                                  private val lifecycleOwner: LifecycleOwner,
                                                  private val persistentState: PersistentState,
                                                  private var titleList: List<CategoryInfo>,
                                                  private var dataList: HashMap<CategoryInfo, List<AppInfo>>) :
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

    fun updateData(title: List<CategoryInfo>, list: HashMap<CategoryInfo, List<AppInfo>>) {
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
        showAppHint(childViewBinding.firewallStatusIndicator, appInfo)

    }

    private fun setupChildClickListeners(appInfo: AppInfo) {

        childViewBinding.firewallToggleWifi.setOnCheckedChangeListener(null)
        childViewBinding.firewallToggleWifi.setOnClickListener {
            enableAfterDelay(TimeUnit.SECONDS.toMillis(1L), childViewBinding.firewallToggleWifi)

            val appUidList = FirewallManager.getAppNamesByUid(appInfo.uid)

            if (appUidList.size > 1) {
                childViewBinding.firewallToggleWifi.isChecked = !appInfo.isInternetAllowed
                // update the ui immediately after firewallToggleWifi's isChecked is modified
                notifyDataSetChanged()
                showDialog(appUidList, appInfo)
                return@setOnClickListener
            }
            updateBlockApp(appInfo)
        }
    }

    private fun updateBlockApp(appInfo: AppInfo) {
        val isInternetAllowed = appInfo.isInternetAllowed
        FirewallManager.updateFirewalledApps(appInfo.uid, !isInternetAllowed)
        if (!isInternetAllowed) childViewBinding.firewallStatusIndicator.setBackgroundColor(
            context.getColor(R.color.colorGreen_900))
        else childViewBinding.firewallStatusIndicator.setBackgroundColor(
            context.getColor(R.color.colorAmber_900))
        killApps(appInfo.uid)
    }

    private fun killApps(uid: Int) {
        if (!persistentState.killAppOnFirewall) return
        io {
            val apps = FirewallManager.getPackageNamesByUid(uid)
            apps.forEach {
                Utilities.killBg(activityManager, it)
            }
        }
    }


    private fun showAppHint(mIconIndicator: TextView, appInfo: AppInfo) {
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

        displayGroupDetails(categoryInfo, isExpanded)
        setupGroupClickListeners(categoryInfo)
        return groupViewBinding.root
    }

    private fun displayGroupDetails(categoryInfo: CategoryInfo, isExpanded: Boolean) {
        groupViewBinding.expandHeaderProgress.visibility = View.GONE
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

    private fun setupGroupClickListeners(categoryInfo: CategoryInfo) {

        groupViewBinding.expandCheckbox.setOnClickListener {
            // flip categoryInfo's isInternetBlocked
            val isInternetBlocked = !categoryInfo.isInternetBlocked
            if (categoryInfo.isAnySystemCategory(context)) {
                if (isInternetBlocked && categoryInfo.numOfAppWhitelisted != categoryInfo.numberOFApps) {
                    showSystemAppBlockDialog(categoryInfo)
                    return@setOnClickListener
                }
            }
            FirewallManager.updateFirewalledAppsByCategory(categoryInfo, isInternetBlocked)
        }

        groupViewBinding.expandCheckbox.setOnCheckedChangeListener(null)
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

        if (categoryInfo.isAnySystemCategory(context)) {
            showAppWarningText(sysAppWarning, categoryInfo.categoryName)
        } else {
            hideAppWarningText(sysAppWarning)
        }

        hidePlaceHolder(placeHolder)

        if (CategoryInfoRepository.CategoryConstants.isSystemComponent(context,
                                                                       categoryInfo.categoryName) && !isExpanded) {
            showPlaceHolder(placeHolder)
        }
    }

    private fun showAppWarningText(sysAppWarning: TextView, categoryName: String) {
        sysAppWarning.text = context.getString(R.string.system_app_block_warning,
                                               categoryName.lowercase(Locale.ROOT))
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

    private fun showDialog(packageList: List<String>, appInfo: AppInfo) {
        val positiveTxt: String

        val builderSingle: AlertDialog.Builder = AlertDialog.Builder(context)

        builderSingle.setIcon(R.drawable.spinner_firewall)
        positiveTxt = if (appInfo.isInternetAllowed) {
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
        arrayAdapter.addAll(packageList)
        builderSingle.setCancelable(false)

        builderSingle.setItems(packageList.toTypedArray(), null)

        builderSingle.setPositiveButton(positiveTxt) { _: DialogInterface, _: Int ->
            updateBlockApp(appInfo)
        }.setNeutralButton(
            context.getString(R.string.ctbs_dialog_negative_btn)) { _: DialogInterface, _: Int ->

        }

        val alertDialog: AlertDialog = builderSingle.create()
        alertDialog.listView.setOnItemClickListener { _, _, _, _ -> }
        alertDialog.show()
    }

    private fun showSystemAppBlockDialog(categoryInfo: CategoryInfo) {
        val builderSingle: AlertDialog.Builder = AlertDialog.Builder(context)

        builderSingle.setIcon(R.drawable.spinner_firewall)
        builderSingle.setCancelable(false)

        builderSingle.setTitle(
            context.resources.getString(R.string.system_apps_warning_dialog_title,
                                        categoryInfo.categoryName))
        builderSingle.setMessage(context.resources.getString(R.string.system_app_block_warning,
                                                             categoryInfo.categoryName.lowercase(
                                                                 Locale.ROOT)))

        builderSingle.setPositiveButton(context.resources.getString(
            R.string.system_apps_dialog_positive)) { _: DialogInterface, _: Int ->
            FirewallManager.updateFirewalledAppsByCategory(categoryInfo, isInternetBlocked = true)
        }.setNegativeButton(context.resources.getString(
            R.string.system_apps_dialog_negative)) { _: DialogInterface, _: Int ->
            groupViewBinding.expandCheckbox.isChecked = false
            groupViewBinding.expandCheckbox.setCompoundDrawablesWithIntrinsicBounds(
                ContextCompat.getDrawable(context, R.drawable.allowed), null, null, null)
            notifyDataSetChanged()
        }

        builderSingle.show()
    }

    private fun enableAfterDelay(delay: Long, vararg views: View) {
        for (v in views) v.isEnabled = false

        Utilities.delay(delay) {
            for (v in views) v.isEnabled = true
        }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                f()
            }
        }
    }
}
