package com.celzero.bravedns.ui

import android.annotation.TargetApi
import android.app.AppOpsManager
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.FirewallHeader
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.appList
import com.celzero.bravedns.util.Utilities.Companion.hasPermissionToReadPhoneStats
import com.celzero.bravedns.util.Utilities.Companion.requestPhoneStateStats
import com.celzero.bravedns.util.DatabaseHandler
import com.celzero.bravedns.util.NetworkStatsHelper
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.jsoup.Jsoup
import java.lang.Exception


class HomeScreenActivity : AppCompatActivity() {

    lateinit var internetManagerFragment: InternetManagerFragment
    lateinit var permissionManagerFragment : PermissionManagerFragment
    lateinit var settingsFragment : SettingsFragment
    lateinit var homeScreenFragment: HomeScreenFragment
    lateinit var context: Context
    lateinit var appSample : AppInfo

    /*TODO : This task need to be completed.
             Add all the appinfo in the global variable during appload
             Handle those things in the application instead of reaching to DB every time
             Call the coroutine scope to insert/update/delete the values*/

    object GlobalVariable{
        var braveMode : Int = -1
        //var appListSample =  HashMap<String, List<AppInfo>>()
        var appList : MutableMap<String, AppInfo> = HashMap()
        var categoryList : HashSet<String> = HashSet()
        var dnsMode = -1
        var firewallMode : Int = -1
        var lifeTimeQueries : Int = -1
        var installedAppCount : Int = -1
        var medianP90 : Long = -1
        var appStartTime : Long = System.currentTimeMillis()
        var isBackgroundEnabled : Boolean = false
        var blockedCategoryList : MutableSet<String> = HashSet<String>()
        var excludedPackageList : MutableSet<String> = HashSet<String>()
    }

    companion object {
        lateinit var dbHandler : DatabaseHandler
    }

    //TODO : Remove the unwanted data and the assignments happening
    //TODO : Create methods and segregate the data.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_home_screen)
        context = this
        dbHandler = DatabaseHandler(this)

        internetManagerFragment = InternetManagerFragment()
        homeScreenFragment = HomeScreenFragment()

        val navView: BottomNavigationView = findViewById(R.id.nav_view)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.fragment_container, homeScreenFragment, homeScreenFragment.javaClass.getSimpleName()).commit()
        }
        navView.setOnNavigationItemSelectedListener(onNavigationItemSelectedListener)

        GlobalVariable.dnsMode = PersistentState.getDnsMode(this)
        GlobalVariable.firewallMode = PersistentState.getFirewallMode(this)
        GlobalVariable.isBackgroundEnabled = PersistentState.getBackgroundEnabled(this)

        registerReceiversForScreenState()
        getAppInfo()

    }

    private fun registerReceiversForScreenState(){
        val filter = IntentFilter()
        filter.addAction(Intent.ACTION_PACKAGE_ADDED)
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED)
        registerReceiver(BraveVPNService.braveScreenStateReceiver, filter)
    }


    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(BraveVPNService.braveScreenStateReceiver)
        //unregisterReceiver(BraveVPNService.braveAutoStartReceiver)
    }


    private fun getAppInfo() {
        GlobalScope.launch(Dispatchers.IO) {
            val mDb = AppDatabase.invoke(context.applicationContext)
            val appInfoRepository = mDb.appInfoRepository()//AppInfoRepository(appInfoDAO)
            val allPackages: List<PackageInfo> = context.packageManager?.getInstalledPackages(PackageManager.GET_META_DATA )!!
            var count = 0
            if(appList.isEmpty()){
            allPackages.forEach {
                if ((it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0) {
                    if (it.applicationInfo.packageName != applicationContext.packageName) {
                        launch(Dispatchers.Default) {

                            val applicationInfo: ApplicationInfo = it.applicationInfo
                            val appInfo = AppInfo()
                            appInfo.appName = context.packageManager.getApplicationLabel(applicationInfo).toString()
                            val category = ApplicationInfo.getCategoryTitle(context, applicationInfo.category)
                            if (category != null)
                                appInfo.appCategory = category.toString()
                            else
                                appInfo.appCategory = "Unknown Category"

                            appInfo.isDataEnabled = true
                            appInfo.isWifiEnabled = true
                            appInfo.isSystemApp = false
                            count += 1
                            if ((applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0) {
                                //count += 1
                                appInfo.isSystemApp = false
                            }
                            appInfo.isScreenOff = false
                            appInfo.packageInfo = applicationInfo.packageName
                            appInfo.isInternetAllowed = PersistentState.isWifiAllowed(appInfo.packageInfo, context)
                            appInfo.isBackgroundEnabled = false
                            appInfo.mobileDataUsed = 0

                            appInfo.trackers = 0
                            appInfo.wifiDataUsed = 0
                            appInfo.uid = applicationInfo.uid

                            appSample = appInfo
                            //TODO Handle this Global scope variable properly. Only half done.
                            appList[applicationInfo.packageName] = appInfo
                            GlobalVariable.installedAppCount = count
                            appInfoRepository.insertAsync(appInfo, this)
                        }
                    }
                }
            }

            }else{
                appList.forEach{
                    it.value.isInternetAllowed = PersistentState.isWifiAllowed(it.value.packageInfo,context)
                    appList.put(it.key, it.value)
                }
            }


           // updateAppCategory()

            //appList.entries.sortedWith(compareBy { it.value.appCategory })

            //updateFragments()
                //delay(1 * 60 * 100)
        }
            //Log.w("DB","End of the loop for the DB")
    }

    private fun updateAppCategory() {
        val googlePlayStoreURL : String = "https://play.google.com/store/apps/details?id="

        appList.forEach {
            val finalURl = googlePlayStoreURL + it.key
            try {
                Log.d("BraveDNS","finalURl:$finalURl")
                val doc = Jsoup.connect(finalURl).get()
                Log.d("BraveDNS","Doc: "+ doc.toString())
                val link = doc.select("span[itemprop=genre]").first()
                Log.d("BraveDNS","link: "+link.toString())
                /*it.value.appCategory = link.text()
                Log.d("BraveDNS","App Category -- :${it.key} -- ${it.value.appCategory}")
                appList.put(it.key,it.value)*/
            } catch (exception: Exception) {
                Log.d("BraveDNS", "Exception in updateAppCategory : " + exception.message)
            }
        }


    }


    private fun hasPermissionToReadNetworkHistory(): Boolean {
        val appOps =
            getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(), packageName
        )
        if (mode == AppOpsManager.MODE_ALLOWED) {
            return true
        }
        requestReadNetworkHistoryAccess()
        return false
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private fun requestReadNetworkHistoryAccess() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        startActivity(intent)
    }

    private fun hasPermissions(): Boolean {
        return hasPermissionToReadNetworkHistory() && hasPermissionToReadPhoneStats(context)
    }

    private fun requestPermissions() {
        if (!hasPermissionToReadNetworkHistory()) {
            return
        }
        if (!hasPermissionToReadPhoneStats(this)) {
            requestPhoneStateStats(this)
        }
    }

    private val onNavigationItemSelectedListener = BottomNavigationView.OnNavigationItemSelectedListener { item ->
        when (item.itemId) {
            R.id.navigation_internet_manager -> {
                println("Internet Manager")
                /*supportFragmentManager.beginTransaction().replace(R.id.fragment_container, internetManagerFragment, internetManagerFragment.javaClass.getSimpleName())
                    .commit()*/
                supportFragmentManager.beginTransaction().replace(R.id.fragment_container, homeScreenFragment, homeScreenFragment.javaClass.getSimpleName())
                    .commit()
                return@OnNavigationItemSelectedListener true
            }
            /*R.id.navigation_permission_manager -> {
                println("Permission Manager")

                permissionManagerFragment = PermissionManagerFragment()
                supportFragmentManager.beginTransaction().replace(R.id.fragment_container, permissionManagerFragment, permissionManagerFragment.javaClass.getSimpleName())
                    .commit()
                return@OnNavigationItemSelectedListener true
            }*/
            R.id.navigation_settings -> {
                println("Settings")
                settingsFragment = SettingsFragment()
                supportFragmentManager.beginTransaction().replace(R.id.fragment_container, settingsFragment, settingsFragment.javaClass.getSimpleName())
                    .commit()
                return@OnNavigationItemSelectedListener true
            }
        }
        false
    }




}
