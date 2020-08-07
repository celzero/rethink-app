package com.celzero.bravedns.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.isVisible
import androidx.core.widget.ContentLoadingProgressBar
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.SmoothScroller
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.FirewallApk
import com.celzero.bravedns.adapter.FirewallHeader
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable
import com.celzero.bravedns.util.BackgroundAccessibilityService
import com.celzero.bravedns.util.Utilities
import com.mikepenz.fastadapter.FastAdapter
import com.mikepenz.fastadapter.adapters.ItemAdapter
import kotlinx.coroutines.*


//TODO: Search feature is removed for firewall header testing
class FirewallActivity : AppCompatActivity(), SearchView.OnQueryTextListener {

    private lateinit var fastAdapter: FastAdapter<FirewallApk>
    private  lateinit var fastAdapterHeader : FastAdapter<FirewallHeader>
    private lateinit var recycle : RecyclerView
    private lateinit var recyclerHeader : RecyclerView
    private val apkList = ArrayList<FirewallApk>()
    private val headerList = ArrayList<FirewallHeader>()
    lateinit var itemAdapter: ItemAdapter<FirewallApk>
    lateinit var headerAdapter : ItemAdapter<FirewallHeader>
    private lateinit var progressImageView: ImageView
    private lateinit var loadingProgressBar: ContentLoadingProgressBar

    private lateinit var firewallAllAppsToggle : SwitchCompat
    private lateinit var firewallAllAppsTxt : TextView

    private lateinit var universalFirewallTxt : TextView
    private lateinit var screenLockLL : LinearLayout
    private lateinit var allAppBgLL : LinearLayout

    private lateinit var  progressBarHolder : FrameLayout
    private lateinit var categoryShowTxt : TextView
    private lateinit var  appAppsShowTxt : TextView
    private lateinit var firewallEnableTxt : TextView
    private lateinit var  firewallNotEnabledLL : LinearLayout
    private lateinit var backgoundModeToggle : SwitchCompat
    
    private var editSearch: SearchView? = null
    private lateinit var context : Context

    private var categoryState : Boolean = false
    private var appAppsState : Boolean = false
    private var universalState : Boolean = false

    private lateinit var scrollView : NestedScrollView

    private lateinit var smoothScroller: SmoothScroller

    @InternalCoroutinesApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_firewall)

        initView()

        /*val task = MyAsyncAppDetailsTask(this)
        task.execute(1)*/
        /*var inAnimation = AlphaAnimation(0f, 1f)
        inAnimation.setDuration(200)
        progressBarHolder.animation = inAnimation*/
        progressBarHolder.visibility = View.VISIBLE


    }

    @InternalCoroutinesApi
    private fun initView() {
        context = this

        val includeView = findViewById<View>(R.id.app_scrolling_incl_firewall)

        scrollView = includeView as NestedScrollView

        firewallNotEnabledLL = includeView.findViewById(R.id.firewall_scroll_connect_check)
        progressBarHolder = includeView.findViewById(R.id.firewall_progressBarHolder)
        progressImageView = includeView.findViewById(R.id.firewall_progress)
        firewallEnableTxt = includeView.findViewById(R.id.firewall_enable_vpn_txt)
        loadingProgressBar = includeView.findViewById(R.id.firewall_update_progress)



        universalFirewallTxt = includeView.findViewById(R.id.firewall_universal_top_text)
        screenLockLL = includeView.findViewById(R.id.firewall_screen_ll)
        allAppBgLL = includeView.findViewById(R.id.firewall_background_ll)

        val isServiceRunning = Utilities.isServiceRunning(this, BraveVPNService::class.java)
        if(!isServiceRunning){
            firewallNotEnabledLL.visibility = View.GONE
            firewallEnableTxt.visibility = View.VISIBLE
            progressImageView.setImageDrawable(getDrawable(R.drawable.illustration_enable_vpn_message))
            return
        }else{
            firewallNotEnabledLL.visibility = View.VISIBLE
            firewallEnableTxt.visibility = View.GONE
            progressImageView.setImageDrawable(getDrawable(R.drawable.ic_illustrations_loading))
        }

        categoryState = false
        universalState = true

        //Recylers init
        recycle = includeView.findViewById(R.id.recycler)
        recycle.layoutManager = LinearLayoutManager(this)

        recyclerHeader = includeView.findViewById(R.id.recycler_header)
        recyclerHeader.layoutManager = LinearLayoutManager(this)
        progressBarHolder = includeView.findViewById(R.id.firewall_progressBarHolder)
        categoryShowTxt = includeView.findViewById(R.id.firewall_category_show_txt)
        appAppsShowTxt =includeView.findViewById(R.id.firewall_apps_show_txt)

        itemAdapter =  ItemAdapter()

        headerAdapter = ItemAdapter()

        fastAdapter = FastAdapter.with(itemAdapter)
        fastAdapterHeader = FastAdapter.with(headerAdapter)
        editSearch = includeView.findViewById(R.id.search)
        recycle.adapter = fastAdapter
        recyclerHeader.adapter = fastAdapterHeader
        editSearch!!.setOnQueryTextListener(this)

        //Firewall Toggle
        firewallAllAppsToggle = includeView.findViewById(R.id.firwall_all_apps_check)
        firewallAllAppsTxt = includeView.findViewById(R.id.firewall_all_apps_txt)
        backgoundModeToggle = includeView.findViewById(R.id.firewall_background_mode_check)

        firewallAllAppsToggle.isChecked = PersistentState.getFirewallModeForScreenState(context)
        backgoundModeToggle.isChecked = PersistentState.getBackgroundEnabled(context)

        firewallAllAppsToggle.setOnCheckedChangeListener { compoundButton, b ->
            PersistentState.setFirewallModeForScreenState(context, b)
            //FirewallManager.updateInternetPermissionForAllApp( b, context)
        }

        smoothScroller = object : LinearSmoothScroller(context) {
            override fun getVerticalSnapPreference(): Int {
                return SNAP_TO_ANY
            }
        }

        firewallAllAppsTxt.setOnClickListener {
            if(PersistentState.getFirewallModeForScreenState(context)){
                firewallAllAppsToggle.isChecked = false
                PersistentState.setFirewallModeForScreenState(context, false)
                //FirewallManager.updateInternetPermissionForAllApp(false,context)
            }else{
                firewallAllAppsToggle.isChecked = true
                PersistentState.setFirewallModeForScreenState(context, true)
                //FirewallManager.updateInternetPermissionForAllApp(true, context)
            }
        }



        editSearch!!.setOnClickListener{
            Log.d("BraveDNS","Click Came")
            editSearch!!.requestFocus()
            editSearch!!.onActionViewExpanded()
        }

        //Background mode toggle

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

        updateAppList(false)


        categoryShowTxt.setOnClickListener{
            //categoryShowTxt.state
            if(!categoryState) {
                categoryState = true
                recyclerHeader.visibility = View.VISIBLE
                categoryShowTxt.setCompoundDrawablesWithIntrinsicBounds(null,null,context.getDrawable(R.drawable.ic_keyboard_arrow_up_gray_24dp), null)
            }else {
                recyclerHeader.visibility = View.GONE
                categoryShowTxt.setCompoundDrawablesWithIntrinsicBounds(null,null,context.getDrawable(R.drawable.ic_keyboard_arrow_down_gray_24dp),null)
                categoryState = false
            }
        }

        appAppsShowTxt.setOnClickListener{
            if(!appAppsState) {
                updateUI()
                editSearch!!.visibility = View.VISIBLE
                recycle.visibility = View.VISIBLE
                    recycle.post {
                        var y = recycle.getY() + 100;
                        scrollView.smoothScrollTo(0,  y.toInt())
                    }
                editSearch!!.requestFocus()
                appAppsState = true
                appAppsShowTxt.setCompoundDrawablesWithIntrinsicBounds(null,null,context.getDrawable(R.drawable.ic_keyboard_arrow_up_gray_24dp), null)
            }else {
                recycle.visibility = View.GONE
                editSearch!!.visibility = View.GONE
                appAppsShowTxt.setCompoundDrawablesWithIntrinsicBounds(null,null,context.getDrawable(R.drawable.ic_keyboard_arrow_down_gray_24dp),null)
                appAppsState = false
            }
        }

        universalFirewallTxt.setOnClickListener{
            if(universalState){
                universalState = false
                allAppBgLL.visibility = View.VISIBLE
                screenLockLL.visibility = View.VISIBLE
                universalFirewallTxt.setCompoundDrawablesWithIntrinsicBounds(null,null,context.getDrawable(R.drawable.ic_keyboard_arrow_up_gray_24dp), null)
            }else{
                universalState = true
                allAppBgLL.visibility = View.GONE
                screenLockLL.visibility = View.GONE
                universalFirewallTxt.setCompoundDrawablesWithIntrinsicBounds(null,null,context.getDrawable(R.drawable.ic_keyboard_arrow_down_gray_24dp), null)
            }
        }

        FirewallHeader.setContextVal(this)
    }


    private fun showAlertForPermission() : Boolean {
        var isAllowed  = false
        val builder = AlertDialog.Builder(this)
        //set title for alert dialog
        builder.setTitle(R.string.alert_permission_accessibility)
        //set message for alert dialog
        builder.setMessage(R.string.alert_permission_accessibility_explanation)
        builder.setIcon(android.R.drawable.ic_dialog_alert)

        //performing positive action
        builder.setPositiveButton("Allow"){dialogInterface, which ->
            isAllowed = true
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivityForResult(intent, 0)
        }

        //performing negative action
        builder.setNegativeButton("Deny"){dialogInterface, which ->
            //Toast.makeText(applicationContext,"clicked No",Toast.LENGTH_LONG).show()
        }
        // Create the AlertDialog
        val alertDialog: AlertDialog = builder.create()
        // Set other dialog properties
        alertDialog.setCancelable(false)
        alertDialog.show()
        return isAllowed
    }


    fun updateUI(){
        progressBarHolder.visibility = View.VISIBLE
        itemAdapter =  ItemAdapter()
        headerAdapter = ItemAdapter()
        itemAdapter.clear()
        fastAdapter = FastAdapter.with(itemAdapter)
        recycle.adapter = fastAdapter
        //recyclerHeader.adapter = fastAdapterHeader
        //fastAdapterHeader = FastAdapter.with(headerAdapter)
        updateAppList(true)
    }

    /*fun showProgress(showProgress : Boolean){
        if(showProgress){
            loadingProgressBar.show()
        }else
            loadingProgressBar.hide()
    }*/

    private fun updateAppList(isUpdate : Boolean) = GlobalScope.launch ( Dispatchers.Default ){
        val mDb = AppDatabase.invoke(context.applicationContext)
        val appInfoRepository = mDb.appInfoRepository()
        //TODO Global scope variable is not updated properly. Work on that variable
        // Work on the below code.
       if(GlobalVariable.appList.isNotEmpty()){
           var firewallHeader  = FirewallHeader("")
           var prevVal = ""

          /* headerAdapter.adapterItems.clear()
           itemAdapter.adapterItems.clear()*/
           //headerList.clear()
           apkList.clear()
           //Log.d("BraveDNS","Local App List - Size : "+ GlobalVariable.appList.size)
           val sortedVal =
               GlobalVariable.appList.entries.sortedWith(compareBy { it.value.appCategory })
           //var firewallHeaderApps  = ArrayList<FirewallApk>()
           sortedVal.forEach{
                if(it.value.packageInfo != "com.celzero.bravedns" ) {

                    val firewallApk = FirewallApk(
                        packageManager.getPackageInfo(it.value.packageInfo, 0),
                        it.value.isWifiEnabled,
                        it.value.isDataEnabled,
                        it.value.isSystemApp,
                        it.value.isScreenOff,
                        it.value.isInternetAllowed,
                        it.value.isBackgroundEnabled,
                        it.value.appCategory,
                        context
                    )
                    //firewallHeaderApps.add(firewallApk)
                    apkList.add(firewallApk)

                    if(prevVal != it.value.appCategory && !isUpdate){
                        //Log.d("BraveDNS","appCategory : "+ it.value.appCategory + "List: "+ firewallHeaderApps.size)
                        firewallHeader = FirewallHeader(it.value.appCategory)
                        GlobalVariable.categoryList.add(it.value.appCategory)
                        headerList.add(firewallHeader)
                        //firewallHeaderApps.clear()
                    }
                    prevVal = it.value.appCategory
                }
            }
        }else{
            val appList = appInfoRepository.getAppInfoAsync()
            //Log.w("DB","App list from DB Size: "+appList.size)
            appList.forEach{
                if(it.packageInfo != "com.celzero.bravedns" ) {
                    val firewallApk = FirewallApk(
                        packageManager.getPackageInfo(it.packageInfo, 0),
                        it.isWifiEnabled,
                        it.isDataEnabled,
                        it.isSystemApp,
                        it.isScreenOff,
                        it.isInternetAllowed,
                        it.isBackgroundEnabled,
                        it.appCategory,
                        context
                    )
                    apkList.add(firewallApk)
                }
            }
        }
        /*val appList = appInfoRepository.getAppInfoAsync()
        Log.w("DB","App list from DB Size: "+appList.size)
        appList.forEach{
            val firewallApk = FirewallApk(packageManager.getPackageInfo(it.packageInfo,0), it.isWifiEnabled, it.isDataEnabled,
                it.isSystemApp, it.isScreenOff, it.isInternet, it.isBackgroundEnabled, context)
            apkList.add(firewallApk)
        }*/

        withContext(Dispatchers.Main.immediate) {
            if(!isUpdate){
                headerAdapter.add(headerList)
                fastAdapterHeader.notifyDataSetChanged()
            }
            itemAdapter.add(apkList)
            //Log.d("BraveDNS","firewallHeader size : "+headerList.size)
            //fastAdapter.notifyAdapterItemRangeChanged(0,apkList.size,null)
            fastAdapter.notifyAdapterDataSetChanged()
            fastAdapter.notifyDataSetChanged()

            /*var outAnimation = AlphaAnimation(1f, 0f)
            outAnimation.setDuration(200)
            progressBarHolder.animation = outAnimation*/
            progressBarHolder.visibility = View.GONE
            //progressBar.visibility = View.GONE
        }
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        itemAdapter.filter(query)
        itemAdapter.itemFilter.filterPredicate = { item: FirewallApk, constraint: CharSequence? ->
            item.appName?.contains(constraint.toString(), ignoreCase = true)!!
        }
        return false
    }

    override fun onQueryTextChange(newText: String?): Boolean {
        itemAdapter.filter(newText)
        itemAdapter.itemFilter.filterPredicate = { item: FirewallApk, constraint: CharSequence? ->
            item.appName?.contains(constraint.toString(), ignoreCase = true)!!
        }
        return true
    }

}
