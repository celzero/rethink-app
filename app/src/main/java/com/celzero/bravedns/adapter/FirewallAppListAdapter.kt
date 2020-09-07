package com.celzero.bravedns.adapter

import android.content.Context
import android.content.DialogInterface
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatToggleButton
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.appcompat.widget.SwitchCompat
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.CategoryInfo
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch


class FirewallAppListAdapter internal constructor(
    private val context: Context,
    private var titleList: List<CategoryInfo>,
    private var dataList: HashMap<CategoryInfo, ArrayList<AppInfo>>
) : BaseExpandableListAdapter() {

        var completeList : List<AppInfo> = ArrayList<AppInfo>()
        var originalTitleList : List<CategoryInfo> = ArrayList()
        var originalDataList : HashMap<CategoryInfo, ArrayList<AppInfo>> = HashMap()

        override fun getChild(listPosition: Int, expandedListPosition: Int): AppInfo {
            return this.dataList.get(this.titleList.get(listPosition))!![expandedListPosition]
        }

        override fun getChildId(listPosition: Int, expandedListPosition: Int): Long {
            return expandedListPosition.toLong()
        }

        fun updateData(title: List<CategoryInfo>, list: HashMap<CategoryInfo, ArrayList<AppInfo>>, completeList: ArrayList<AppInfo>){
            this.completeList = completeList
            titleList = title
            dataList = list
            this.notifyDataSetChanged()
            originalTitleList = title
            originalDataList = list
        }

    /**
     * Yet to complete the below function logic,
     * TODO : Filter the query string and update it in the list adapter for search
     */
        fun filterData(query: String){
            titleList = originalTitleList
            dataList = originalDataList
            var searchResult = dataList
            if(query != "") {
                searchResult.clear()
                dataList.forEach {
                    val normalList = it.value.filter { a -> a.appName.toLowerCase().contains(query.toLowerCase()) }
                    if (normalList.isNotEmpty()) {
                        titleList = titleList.filter { titleList -> titleList.categoryName.contains(
                            it.key.categoryName
                        ) }
                        searchResult.put(titleList.get(0), normalList as java.util.ArrayList<AppInfo>)
                    }
                }
                if(searchResult.isNotEmpty())
                    dataList = searchResult
            }
            this.notifyDataSetChanged()
        }


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

            val fwWifiImg: SwitchCompat = convertView.findViewById(R.id.firewall_toggle_wifi)
            val firewallApkProgressBar: ProgressBar = convertView.findViewById(R.id.firewall_apk_progress_bar)
            //var appIcon = context.resources.getDrawable(android.R.drawable.)
            try {
                val appIcon = context.packageManager.getApplicationIcon(appInfoDetail.packageInfo)
                mIconImageView.setImageDrawable(appIcon)
            }catch (e: Exception){
                mIconImageView.setImageDrawable(context.getDrawable(R.drawable.default_app_icon))
                Log.e("BraveDNS","Application Icon not available for package: ${appInfoDetail.packageInfo}"+e.message,e)
            }
            mLabelTextView.text = appInfoDetail.appName

            firewallApkProgressBar.visibility = View.GONE
            mPackageTextView.text = appInfoDetail.packageInfo
            //fwWifiImg.visibility = View.VISIBLE
            //For WiFi
            if (appInfoDetail.isInternetAllowed) {
                fwWifiImg.isChecked = false
                mIconIndicator.setBackgroundColor(context!!.getColor(R.color.colorGreen_900))
            } else {
                fwWifiImg.isChecked = true
                mIconIndicator.setBackgroundColor(context!!.getColor(R.color.colorAmber_900))
            }

            fwWifiImg.setOnClickListener{
                var isInternetAllowed = appInfoDetail.isInternetAllowed
                val mDb = AppDatabase.invoke(context.applicationContext)
                val appInfoRepository = mDb.appInfoRepository()
                val appUIDList = appInfoRepository.getAppListForUID(appInfoDetail.uid)
                var blockAllApps = false
                if(appUIDList.size > 1){
                    blockAllApps = showDialog(appUIDList, appInfoDetail.appName, isInternetAllowed)
                }

                if(appUIDList.size <= 1 || blockAllApps) {
                    object : CountDownTimer(500, 250) {
                        override fun onTick(millisUntilFinished: Long) {
                            fwWifiImg.visibility = View.GONE
                            firewallApkProgressBar.visibility = View.VISIBLE
                        }
                        override fun onFinish() {
                            firewallApkProgressBar.visibility = View.GONE
                            fwWifiImg.visibility = View.VISIBLE
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
                        appUIDList.forEach{
                            HomeScreenActivity.GlobalVariable.appList.get(it.packageInfo!!)!!.isInternetAllowed = isInternetAllowed
                            PersistentState.setExcludedPackagesWifi(it.packageInfo, !isInternetAllowed, context)
                            FirewallManager.updateAppInternetPermission(it.packageInfo, !isInternetAllowed)
                            FirewallManager.updateAppInternetPermissionByUID(it.uid, !isInternetAllowed)
                        }
                        val temp = appInfoDetail.appCategory
                        var list: CategoryInfo? = null
                        titleList.forEach {
                            if (it.categoryName.equals(temp)) {
                                list = it
                            }
                        }
                        if (list!!.isInternetBlocked) {
                            val categoryInfoRepository = mDb.categoryInfoRepository()
                            categoryInfoRepository.updateCategoryInternet(
                                list!!.categoryName,
                                false
                            )
                        }
                        appInfoRepository.updateInternetForuid(uid, !isInternetAllowed)
                    }
                }else{
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
            if(titleList.isEmpty()){
                val mDb = AppDatabase.invoke(context.applicationContext)
                val appInfoRepository = mDb.appInfoRepository()
                appInfoRepository.getAllAppDetailsForLiveData()
            }
            return this.titleList.size
        }

        override fun getGroupId(listPosition: Int): Long {
            return listPosition.toLong()
        }

        override fun getGroupView(
            listPosition: Int,
            isExpanded: Boolean,
            convertView: View?,
            parent: ViewGroup
        ): View {
            var convertView = convertView
            val listTitle = getGroup(listPosition)
            if (convertView == null) {
                val layoutInflater =
                    this.context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
                convertView = layoutInflater.inflate(R.layout.expandable_firewall_header, null)
            }

            val categoryNameTV : TextView = convertView!!.findViewById(R.id.expand_textView_category_name)
            val appCountTV : TextView = convertView.findViewById(R.id.expand_textView_app_count)
            val internetChk : AppCompatToggleButton = convertView.findViewById((R.id.expand_checkbox))
            val imageHolderLL : LinearLayout = convertView.findViewById(R.id.imageLayout)
            val imageHolder1 : AppCompatImageView = convertView.findViewById(R.id.imageLayout_1)
            val imageHolder2 : AppCompatImageView = convertView.findViewById(R.id.imageLayout_2)
            val imageHolder3 : AppCompatImageView = convertView.findViewById(R.id.imageLayout_3)
            val imageHolder4 : AppCompatImageView = convertView.findViewById(R.id.imageLayout_4)
            val progressBar : ProgressBar = convertView.findViewById(R.id.expand_header_progress)
            val indicatorTV : TextView = convertView.findViewById(R.id.expand_header_category_indicator)
            val sysAppWarning : TextView = convertView.findViewById(R.id.expand_system_apps_warning)

            categoryNameTV.text = listTitle.categoryName
            val isInternetAllowed = !listTitle.isInternetBlocked

            internetChk.isChecked = !isInternetAllowed
            if(!isInternetAllowed)
                internetChk.setCompoundDrawablesWithIntrinsicBounds(
                    context.resources.getDrawable(R.drawable.dis_allowed),
                    null,
                    null,
                    null
                )
            else
                internetChk.setCompoundDrawablesWithIntrinsicBounds(
                    context.resources.getDrawable(R.drawable.allowed),
                    null,
                    null,
                    null
                )
            if (isInternetAllowed) {
                indicatorTV.visibility = View.INVISIBLE
            } else {
                indicatorTV.visibility = View.VISIBLE
            }

            if(listTitle.categoryName.equals("System Apps")){
                sysAppWarning.visibility = View.VISIBLE
            }else{
                sysAppWarning.visibility = View.GONE
            }

            val numberOfApps = listTitle.numberOFApps
            if(isInternetAllowed){
                appCountTV.setText(listTitle.numOfAppsBlocked.toString() + "/" + numberOfApps.toString() + " apps blocked")
            }else{
                appCountTV.setText(numberOfApps.toString() + "/" + numberOfApps.toString() + " apps blocked")
            }

            //TODO - Dirty code - Change the logic for adding the imageview in the list instead of separate imageview
            var list = dataList[listTitle]
            try{
                if(list!= null && list!!.isNotEmpty()) {
                    if (numberOfApps != 0) {
                        if (numberOfApps >= 4) {
                            imageHolder1.setImageDrawable(context.packageManager.getApplicationIcon(list[0].packageInfo))
                            imageHolder2.setImageDrawable(context.packageManager.getApplicationIcon(list[1].packageInfo))
                            imageHolder3.setImageDrawable(context.packageManager.getApplicationIcon(list[2].packageInfo))
                            imageHolder4.setImageDrawable(context.packageManager.getApplicationIcon(list[3].packageInfo))
                        } else if (numberOfApps == 3) {
                            imageHolder1.setImageDrawable(context.packageManager.getApplicationIcon(list[0].packageInfo))
                            imageHolder2.setImageDrawable(context.packageManager.getApplicationIcon(list[1].packageInfo))
                            imageHolder3.setImageDrawable(context.packageManager.getApplicationIcon(list[2].packageInfo))
                            imageHolder4.visibility = View.GONE
                        } else if (numberOfApps == 2) {
                            imageHolder1.setImageDrawable(context.packageManager.getApplicationIcon(list[0].packageInfo))
                            imageHolder2.setImageDrawable(context.packageManager.getApplicationIcon(list[1].packageInfo))
                            imageHolder3.visibility = View.GONE
                            imageHolder4.visibility = View.GONE
                        } else {
                            imageHolder1.setImageDrawable(context.packageManager.getApplicationIcon(list[0].packageInfo))
                            imageHolder2.visibility = View.GONE
                            imageHolder3.visibility = View.GONE
                            imageHolder4.visibility = View.GONE
                        }
                    } else {
                        imageHolder1.visibility = View.GONE
                        imageHolder2.visibility = View.GONE
                        imageHolder3.visibility = View.GONE
                        imageHolder4.visibility = View.GONE
                    }
                }
            }catch (e: Exception){
                Log.e("BraveDNS","One or more application icons are not available"+e.message,e)
            }
            internetChk.setOnClickListener{
                object : CountDownTimer(1000, 500) {
                    override fun onTick(millisUntilFinished: Long) {
                        internetChk.visibility = View.GONE
                        progressBar.visibility = View.VISIBLE
                    }
                    override fun onFinish() {
                        progressBar.visibility = View.GONE
                        internetChk.visibility = View.VISIBLE
                    }
                }.start()
                var isInternet = !listTitle.isInternetBlocked
                if (isInternet) {
                    indicatorTV.visibility = View.VISIBLE
                } else {
                    indicatorTV.visibility = View.INVISIBLE
                }
                FirewallManager.updateCategoryAppsInternetPermission(
                    listTitle.categoryName,
                    !isInternet,
                    context
                )

                GlobalScope.launch(Dispatchers.IO) {
                    val mDb = AppDatabase.invoke(context.applicationContext)
                    val appInfoRepository = mDb.appInfoRepository()
                    val categoryInfoRepository = mDb.categoryInfoRepository()
                    categoryInfoRepository.updateCategoryInternet(listTitle.categoryName, isInternet)
                    appInfoRepository.updateInternetForAppCategory(listTitle.categoryName, !isInternet)
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


   private  fun showDialog(packageList: List<AppInfo>, appName: String, isInternet: Boolean) : Boolean{
        //Change the handler logic into some other
       val handler: Handler = object : Handler() {
           override fun handleMessage(mesg: Message?) {
               throw RuntimeException()
           }
       }
       var positiveTxt  = ""
       val packageNameList:List<String> = packageList.map { it.appName }
       var proceedBlocking : Boolean = false

       val builderSingle: AlertDialog.Builder = AlertDialog.Builder(context)

       builderSingle.setIcon(R.drawable.spinner_firewall)
       if(isInternet) {
            builderSingle.setTitle("Blocking \"$appName\" will also block these ${packageList.size} apps")
            positiveTxt = "Block ${packageList.size} apps"
       }
       else {
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
            Log.d("BraveDNS","OnClick")
           //dialogInterface.cancel()
           //builderSingle.setCancelable(false)
       }*/
       /*val alertDialog : AlertDialog = builderSingle.create()
       alertDialog.getListView().setOnItemClickListener({ adapterView, subview, i, l -> })*/
       builderSingle.setPositiveButton(
           positiveTxt,
           DialogInterface.OnClickListener { dialogInterface: DialogInterface, i: Int ->
               proceedBlocking = true
               handler.sendMessage(handler.obtainMessage())
           }).setNeutralButton(
           "Go Back",
           DialogInterface.OnClickListener { dialogInterface: DialogInterface, i: Int ->
               handler.sendMessage(handler.obtainMessage());
               proceedBlocking = false
           })

       val alertDialog : AlertDialog = builderSingle.show()
       alertDialog.listView.setOnItemClickListener { adapterView, subview, i, l -> }
       alertDialog.setCancelable(false)
       try {
           Looper.loop()
       } catch (e2: java.lang.RuntimeException) {
       }

       return proceedBlocking
   }

 }
