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

import android.content.Context
import android.content.DialogInterface
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.paging.PagedListAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.AppInfoRepository
import com.celzero.bravedns.database.CategoryInfoRepository
import com.celzero.bravedns.databinding.UnivWhitelistListItemBinding
import com.celzero.bravedns.service.BraveVPNService.Companion.appWhiteList
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import com.celzero.bravedns.util.ThrowingHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class UniversalAppListAdapter(private val context: Context, private val appInfoRepository: AppInfoRepository, private val categoryInfoRepository: CategoryInfoRepository, private val persistentState: PersistentState) : PagedListAdapter<AppInfo, UniversalAppListAdapter.UniversalAppInfoViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<AppInfo>() {
            // Concert details may have changed if reloaded from the database,
            // but ID is fixed.
            override fun areItemsTheSame(oldConnection: AppInfo, newConnection: AppInfo) = oldConnection.packageInfo == newConnection.packageInfo

            override fun areContentsTheSame(oldConnection: AppInfo, newConnection: AppInfo) = oldConnection == newConnection
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UniversalAppInfoViewHolder {
        val itemBinding = UnivWhitelistListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return UniversalAppInfoViewHolder(itemBinding)
    }

    override fun onBindViewHolder(holder: UniversalAppInfoViewHolder, position: Int) {
        val appInfo: AppInfo? = getItem(position)
        holder.update(appInfo)
    }


    inner class UniversalAppInfoViewHolder(private val b: UnivWhitelistListItemBinding) : RecyclerView.ViewHolder(b.root) {

        fun update(appInfo: AppInfo?) {
            if (appInfo != null) {
                if (appInfo.appCategory == Constants.APP_CAT_SYSTEM_COMPONENTS) {
                    b.univWhitelistApkLabelTv.text = appInfo.appName//+ Constants.RECOMMENDED
                } else {
                    b.univWhitelistApkLabelTv.text = appInfo.appName
                }

                b.univWhitelistCheckbox.isChecked = appInfo.whiteListUniv1
                try {
                    Glide.with(context).load(context.packageManager.getApplicationIcon(appInfo.packageInfo)).into(b.univWhitelistApkIconIv)
                    //val icon = context.packageManager.getApplicationIcon(appInfo.packageInfo)
                    //appIcon.setImageDrawable(icon)
                } catch (e: Exception) {
                    Glide.with(context).load(AppCompatResources.getDrawable(context, R.drawable.default_app_icon)).into(b.univWhitelistApkIconIv)
                    //appIcon.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.default_app_icon))
                    Log.e(LOG_TAG, "Application Icon not available for package: ${appInfo.packageInfo}" + e.message, e)
                }

                b.univWhitelistContainer.setOnClickListener {
                    if (DEBUG) Log.d(LOG_TAG, "parentView- whitelist - ${appInfo.appName},${appInfo.whiteListUniv1}")
                    appInfo.whiteListUniv1 = !appInfo.whiteListUniv1
                    modifyWhiteListApps(appInfo)
                    /*object : CountDownTimer(1000, 500) {
                        override fun onTick(millisUntilFinished: Long) {
                        }
                        override fun onFinish() {

                        }
                    }.start()*/

                }

                b.univWhitelistCheckbox.setOnCheckedChangeListener(null)
                b.univWhitelistCheckbox.setOnClickListener {
                    if (DEBUG) Log.d(LOG_TAG, "CheckBox- whitelist - ${appInfo.appName},${appInfo.whiteListUniv1}")
                    appInfo.whiteListUniv1 = !appInfo.whiteListUniv1
                    modifyWhiteListApps(appInfo)
                }
            }
        }

        private fun modifyWhiteListApps(appInfo: AppInfo) {
            val status = appInfo.whiteListUniv1
            appWhiteList[appInfo.uid] = status
            val appUIDList = appInfoRepository.getAppListForUID(appInfo.uid)
            val blockAllApps: Boolean
            blockAllApps = if (appUIDList.size > 1) {
                showDialog(appUIDList, appInfo.appName, status)
            } else {
                true
                /* if (status) {
                                 Toast.makeText(context, "${appInfo.appName} removed from whitelist", Toast.LENGTH_SHORT).show()
                             } else {
                                 Toast.makeText(context, "${appInfo.appName} added to whitelist", Toast.LENGTH_SHORT).show()
                             }*/
            }
            if (blockAllApps) {
                b.univWhitelistCheckbox.isChecked = status
                CoroutineScope(Dispatchers.IO).launch {
                    if (status) {
                        appUIDList.forEach {
                            HomeScreenActivity.GlobalVariable.appList[it.packageInfo]!!.isInternetAllowed = status
                            persistentState.modifyAllowedWifi(it.packageInfo, status)
                            FirewallManager.updateAppInternetPermission(it.packageInfo, status)
                            FirewallManager.updateAppInternetPermissionByUID(it.uid, status)
                        }
                        appInfoRepository.updateInternetForuid(appInfo.uid, status)
                    }
                    appInfoRepository.updateWhiteList(appInfo.uid, status)
                    val countBlocked = appInfoRepository.getBlockedCountForCategory(appInfo.appCategory)
                    val countWhitelisted = appInfoRepository.getWhitelistCount(appInfo.appCategory)
                    categoryInfoRepository.updateBlockedCount(appInfo.appCategory, countBlocked)
                    categoryInfoRepository.updateWhitelistCount(appInfo.appCategory, countWhitelisted)
                }
            } else {
                b.univWhitelistCheckbox.isChecked = !status
                appInfo.whiteListUniv1 = !status
            }
        }

        private fun showDialog(packageList: List<AppInfo>, appName: String, isInternet: Boolean): Boolean {
            //Change the handler logic into some other
            val handler: Handler = ThrowingHandler()
            var positiveTxt = ""
            val packageNameList: List<String> = packageList.map { it.appName }
            var proceedBlocking = false

            val builderSingle: AlertDialog.Builder = AlertDialog.Builder(context)

            builderSingle.setIcon(R.drawable.ic_whitelist)
            var appNameEllipsis = appName
            if (isInternet) {
                if (appNameEllipsis.length > 10) {
                    appNameEllipsis = appNameEllipsis.substring(0, 10)
                    appNameEllipsis = "$appNameEllipsis..."
                }
                builderSingle.setTitle("Adding \"$appNameEllipsis\" to the whitelist will also add these ${packageList.size} apps")
                positiveTxt = "Add ${packageList.size} apps"
            } else {
                builderSingle.setTitle("Removing  \"$appNameEllipsis\" from the whitelist will also remove these ${packageList.size} apps")
                positiveTxt = "Remove ${packageList.size} apps"
            }
            val arrayAdapter = ArrayAdapter<String>(context, android.R.layout.simple_list_item_activated_1)
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
            builderSingle.setPositiveButton(positiveTxt) { _: DialogInterface, _: Int ->
                proceedBlocking = true
                handler.sendMessage(handler.obtainMessage())
            }.setNeutralButton("Go Back") { _: DialogInterface, _: Int ->
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

    }

}