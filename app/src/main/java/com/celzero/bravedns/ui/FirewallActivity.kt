package com.celzero.bravedns.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.Observer
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.FirewallAppListAdapter
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.CategoryInfo
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable
import com.celzero.bravedns.util.BackgroundAccessibilityService
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.InternalCoroutinesApi


//TODO: Search feature is removed for firewall header testing
class FirewallActivity : AppCompatActivity() , SearchView.OnQueryTextListener {

    private var firewallExpandableList: ExpandableListView ?= null
    private var adapterList: ExpandableListAdapter ?= null
    private var titleList: List<CategoryInfo>? = ArrayList()
    private var listData: HashMap<CategoryInfo, ArrayList<AppInfo>> = HashMap()

    private lateinit var loadingProgressBar: ProgressBar

    private lateinit var firewallAllAppsToggle : SwitchCompat
    private lateinit var firewallAllAppsTxt : TextView

    private lateinit var universalFirewallTxt : TextView
    private lateinit var screenLockLL : LinearLayout
    private lateinit var allAppBgLL : LinearLayout

    private lateinit var categoryShowTxt : TextView
    private lateinit var firewallEnableTxt : TextView
    private lateinit var  firewallNotEnabledLL : LinearLayout

    private lateinit var backgoundModeToggleTxt : TextView
    private lateinit var backgoundModeToggle : SwitchCompat
    
    private var editSearch: SearchView? = null
    private lateinit var context : Context

    private var categoryState : Boolean = false
    private var universalState : Boolean = false

    private lateinit var scrollView : NestedScrollView

    @InternalCoroutinesApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_firewall)
        initView()
    }

    override fun onResume() {
        super.onResume()
        observersForUI()
    }


    @InternalCoroutinesApi
    private fun initView() {
        context = this

        val includeView = findViewById<View>(R.id.app_scrolling_incl_firewall)

        scrollView = includeView as NestedScrollView

        firewallNotEnabledLL = includeView.findViewById(R.id.firewall_scroll_connect_check)

        firewallEnableTxt = includeView.findViewById(R.id.firewall_enable_vpn_txt)
        loadingProgressBar = includeView.findViewById(R.id.firewall_update_progress)

        categoryShowTxt = includeView.findViewById(R.id.firewall_category_show_txt)

        universalFirewallTxt = includeView.findViewById(R.id.firewall_universal_top_text)
        screenLockLL = includeView.findViewById(R.id.firewall_screen_ll)
        allAppBgLL = includeView.findViewById(R.id.firewall_background_ll)

        val isServiceRunning = Utilities.isServiceRunning(this, BraveVPNService::class.java)
        if(!isServiceRunning){
            firewallNotEnabledLL.visibility = View.GONE
            firewallEnableTxt.visibility = View.VISIBLE
            return
        }else{
            firewallNotEnabledLL.visibility = View.VISIBLE
            firewallEnableTxt.visibility = View.GONE
        }

        categoryState = true
        universalState = true

        firewallExpandableList = includeView.findViewById(R.id.firewall_expandable_list)

        loadingProgressBar.visibility = View.VISIBLE
        firewallExpandableList!!.visibility = View.VISIBLE

        if (firewallExpandableList != null) {
            adapterList = FirewallAppListAdapter(this, titleList as ArrayList<CategoryInfo>, listData!!)
            firewallExpandableList!!.setAdapter(adapterList)

            firewallExpandableList!!.setOnGroupClickListener { expandableListView, view, i, l ->
                false
            }

            firewallExpandableList!!.setOnGroupExpandListener { it ->
                listData[titleList!![it]]!!.sortBy  { it.isInternetAllowed }
            }

        }

        if (HomeScreenActivity.isLoadingComplete) {
            loadingProgressBar.visibility = View.GONE
            firewallExpandableList!!.visibility = View.VISIBLE
        }

        editSearch = includeView.findViewById(R.id.search)
        //TODO Search
        editSearch!!.setOnQueryTextListener(this)

        //Firewall Toggle
        firewallAllAppsToggle = includeView.findViewById(R.id.firwall_all_apps_check)
        firewallAllAppsTxt = includeView.findViewById(R.id.firewall_all_apps_txt)
        backgoundModeToggle = includeView.findViewById(R.id.firewall_background_mode_check)
        backgoundModeToggleTxt = includeView.findViewById(R.id.firewall_background_mode_txt)

        firewallAllAppsToggle.isChecked = PersistentState.getFirewallModeForScreenState(context)
        backgoundModeToggle.isChecked = PersistentState.getBackgroundEnabled(context)

        firewallAllAppsToggle.setOnCheckedChangeListener { compoundButton, b ->
            PersistentState.setFirewallModeForScreenState(context, b)
        }

        firewallAllAppsTxt.setOnClickListener {
            if(PersistentState.getFirewallModeForScreenState(context)){
                firewallAllAppsToggle.isChecked = false
                PersistentState.setFirewallModeForScreenState(context, false)
            }else{
                firewallAllAppsToggle.isChecked = true
                PersistentState.setFirewallModeForScreenState(context, true)
            }
        }

        editSearch!!.setOnClickListener{
            editSearch!!.requestFocus()
            editSearch!!.onActionViewExpanded()
        }

        //Background mode toggle
        backgoundModeToggleTxt.setOnClickListener {
            var checkedVal = backgoundModeToggle.isChecked
            if (Utilities.isAccessibilityServiceEnabled(context, BackgroundAccessibilityService::class.java)) {
                GlobalVariable.isBackgroundEnabled = !checkedVal
                PersistentState.setBackgroundEnabled(context, !checkedVal)
                backgoundModeToggle.isChecked = !checkedVal
            } else {
                if (!showAlertForPermission()) {
                    backgoundModeToggle.isChecked = false
                }
            }
        }

        backgoundModeToggle.setOnCheckedChangeListener { compoundButton, b ->
            if(Utilities.isAccessibilityServiceEnabled(context, BackgroundAccessibilityService::class.java)){
                GlobalVariable.isBackgroundEnabled = b
                PersistentState.setBackgroundEnabled(context, b)
            }else{
                if(!showAlertForPermission()){
                    backgoundModeToggle.isChecked = false
                }
            }
        }

        categoryShowTxt.setOnClickListener{
            if(!categoryState) {
                categoryState = true
                firewallExpandableList!!.visibility = View.VISIBLE
                categoryShowTxt.setCompoundDrawablesWithIntrinsicBounds(null, null, context.getDrawable(R.drawable.ic_keyboard_arrow_up_gray_24dp), null)
            }else {
                firewallExpandableList!!.visibility = View.GONE
                categoryShowTxt.setCompoundDrawablesWithIntrinsicBounds(null, null, context.getDrawable(R.drawable.ic_keyboard_arrow_down_gray_24dp), null)
                categoryState = false
            }
        }

        universalFirewallTxt.setOnClickListener{
            if(universalState){
                universalState = false
                allAppBgLL.visibility = View.VISIBLE
                screenLockLL.visibility = View.VISIBLE
                universalFirewallTxt.setCompoundDrawablesWithIntrinsicBounds(null, null, context.getDrawable(R.drawable.ic_keyboard_arrow_up_gray_24dp), null)
            }else{
                universalState = true
                allAppBgLL.visibility = View.GONE
                screenLockLL.visibility = View.GONE
                universalFirewallTxt.setCompoundDrawablesWithIntrinsicBounds(null, null, context.getDrawable(R.drawable.ic_keyboard_arrow_down_gray_24dp), null)
            }
        }

    }


    private fun observersForUI() {
            val mDb = AppDatabase.invoke(this.applicationContext)
            val appInfoRepository = mDb.appInfoRepository()
            appInfoRepository.getAllAppDetailsForLiveData().observe(this, Observer {
                val list = it!!
                titleList!!.forEach {
                    var categoryList = list.filter { a -> a.appCategory == it.categoryName }
                    var count = categoryList.filter { a -> !a.isInternetAllowed }
                    it.numOfAppsBlocked = count.size
                    if (categoryList.size == count.size)
                        it.isInternetBlocked = true
                    listData.put(it, categoryList as java.util.ArrayList<AppInfo>)
                }
                if (adapterList != null) {
                    (adapterList as FirewallAppListAdapter).updateData(titleList!!, listData, list as ArrayList<AppInfo>)
                    if (HomeScreenActivity.isLoadingComplete) {
                        setListViewHeight(firewallExpandableList!!, 1)
                        loadingProgressBar.visibility = View.GONE
                        firewallExpandableList!!.visibility = View.VISIBLE
                    }
                } else {
                    loadingProgressBar.visibility = View.GONE
                }
            })

            val categoryInfoRepository = mDb.categoryInfoRepository()
            categoryInfoRepository.getAppCategoryForLiveData().observe(this, Observer {
                titleList = it
            })
    }

    private fun setListViewHeight(listView: ExpandableListView, group: Int) {
            val listAdapter = listView.expandableListAdapter as ExpandableListAdapter
            var totalHeight = 0
            val desiredWidth = View.MeasureSpec.makeMeasureSpec(
                listView.width,
                View.MeasureSpec.EXACTLY
            )
            for (i in 0 until listAdapter.groupCount) {
                val groupItem = listAdapter.getGroupView(i, false, null, listView)
                groupItem.measure(desiredWidth, View.MeasureSpec.UNSPECIFIED)
                totalHeight += groupItem.measuredHeight
                if (listView.isGroupExpanded(i) && i != group
                    || !listView.isGroupExpanded(i) && i == group) {
                    for (j in 0 until 1) {
                        val listItem = listAdapter.getChildView(
                            i, j, false, null, listView
                        )
                        listItem.measure(desiredWidth, View.MeasureSpec.UNSPECIFIED)
                        totalHeight += listItem.measuredHeight
                    }
                   /*for (j in 0 until listAdapter.getChildrenCount(i)) {
                        val listItem = listAdapter.getChildView(
                            i, j, false, null, listView)
                        listItem.measure(desiredWidth, View.MeasureSpec.UNSPECIFIED)
                        totalHeight += listItem.measuredHeight
                    }*/
                }
            }
            val params: ViewGroup.LayoutParams = listView.layoutParams
            var height = (totalHeight
                    + listView.dividerHeight * (listAdapter.groupCount - 1))
            if (height < 10) height = 200
            params.height = height
            listView.layoutParams = params
            listView.requestLayout()
        }


    private fun showAlertForPermission() : Boolean {
        var isAllowed  = false
        val builder = AlertDialog.Builder(this)
        //set title for alert dialog
        builder.setTitle(R.string.alert_permission_accessibility)
        //set message for alert dialog
        builder.setMessage(R.string.alert_firewall_accessibility_explanation)
        builder.setIcon(android.R.drawable.ic_dialog_alert)

        //performing positive action
        builder.setPositiveButton("Allow"){ dialogInterface, which ->
            isAllowed = true
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivityForResult(intent, 0)
        }

        //performing negative action
        builder.setNegativeButton("Deny"){ dialogInterface, which ->
        }
        // Create the AlertDialog
        val alertDialog: AlertDialog = builder.create()
        // Set other dialog properties
        alertDialog.setCancelable(false)
        alertDialog.show()
        return isAllowed
    }


    override fun onQueryTextSubmit(query: String?): Boolean {
        (adapterList as FirewallAppListAdapter).filterData(query!!)
        return false
    }

    override fun onQueryTextChange(newText: String?): Boolean {
        (adapterList as FirewallAppListAdapter).filterData(newText!!)
        return true
    }

}
