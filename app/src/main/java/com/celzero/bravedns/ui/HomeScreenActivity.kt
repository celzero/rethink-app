package com.celzero.bravedns.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.MutableLiveData
import com.celzero.bravedns.R
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.CategoryInfo
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.appList
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.blockedUID
import com.celzero.bravedns.util.PlayStoreCategory
import com.celzero.bravedns.util.DatabaseHandler
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.jsoup.Jsoup
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap


class HomeScreenActivity : AppCompatActivity() {

    lateinit var internetManagerFragment: InternetManagerFragment
    lateinit var settingsFragment : SettingsFragment
    lateinit var homeScreenFragment: HomeScreenFragment
    lateinit var aboutFragment: AboutFragment
    lateinit var context: Context
    //lateinit var appSample : AppInfo

    /*TODO : This task need to be completed.
             Add all the appinfo in the global variable during appload
             Handle those things in the application instead of reaching to DB every time
             Call the coroutine scope to insert/update/delete the values*/

    object GlobalVariable{
        var braveMode : Int = -1
        var appList : MutableMap<String, AppInfo> = HashMap()
        var blockedUID : MutableMap<Int,Boolean> = HashMap()
        var dnsMode = -1
        var firewallMode : Int = -1
        var lifeTimeQueries : Int = -1
        var lifeTimeQ : MutableLiveData<Int> = MutableLiveData()
        var medianP90 : Long = -1
        var median50 : MutableLiveData<Long> = MutableLiveData()
        var blockedCount : MutableLiveData<Int> = MutableLiveData()
        var appsBlocked : MutableLiveData<Int> = MutableLiveData()
        var numUniversalBlock : MutableLiveData<Int> = MutableLiveData()
        var appStartTime : Long = System.currentTimeMillis()
        var isBackgroundEnabled : Boolean = false
    }

    companion object {
        lateinit var dbHandler : DatabaseHandler
        var isLoadingComplete : Boolean = false
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

        //getAppDetails()
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
    }

    private fun getAppInfo() {
        isLoadingComplete = false

        GlobalScope.launch(Dispatchers.IO) {
            val mDb = AppDatabase.invoke(context.applicationContext)
            val appInfoRepository = mDb.appInfoRepository()//AppInfoRepository(appInfoDAO)
            val allPackages: List<PackageInfo> = context.packageManager?.getInstalledPackages(PackageManager.GET_META_DATA)!!
            val appDetailsFromDB = appInfoRepository.getAppInfoAsync()
            var count = 0
            if (appDetailsFromDB.isEmpty() || appDetailsFromDB.size != allPackages.size-1) {
                allPackages.forEach {
                    if (it.applicationInfo.packageName != applicationContext.packageName) {
                        val applicationInfo: ApplicationInfo = it.applicationInfo
                        val appInfo = AppInfo()
                        appInfo.appName = context.packageManager.getApplicationLabel(applicationInfo).toString()

                        //ApplicationInfo.getCategoryTitle(context, applicationInfo.category)
                        appInfo.packageInfo = applicationInfo.packageName
                        val category = fetchCategory(appInfo.packageInfo)
                        if (category.toLowerCase(Locale.ROOT) != PlayStoreCategory.OTHER.name.toLowerCase(Locale.ROOT))
                            appInfo.appCategory = category
                        else if ((it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0) {
                            appInfo.appCategory = "System Apps"
                        } else {
                            val temp = ApplicationInfo.getCategoryTitle(context, applicationInfo.category)
                            if (temp != null)
                                appInfo.appCategory = temp.toString()
                            else
                                appInfo.appCategory = "Other"
                        }
                        if(appInfo.appCategory.contains("_"))
                            appInfo.appCategory = appInfo.appCategory.replace("_"," ").toLowerCase()
                        appInfo.isDataEnabled = true
                        appInfo.isWifiEnabled = true
                        appInfo.isSystemApp = true
                        count += 1
                        if ((applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0) {
                            appInfo.isSystemApp = false
                        }
                        appInfo.isScreenOff = false
                        appInfo.uid = packageManager.getPackageUid(appInfo.packageInfo, PackageManager.GET_META_DATA)
                        appInfo.isInternetAllowed = PersistentState.isWifiAllowed(appInfo.packageInfo, context)
                        appInfo.isBackgroundEnabled = false
                        appInfo.mobileDataUsed = 0
                        appInfo.trackers = 0
                        appInfo.wifiDataUsed = 0
                        appInfo.uid = applicationInfo.uid
                        //TODO Handle this Global scope variable properly. Only half done.
                        appList[applicationInfo.packageName] = appInfo
                        appInfoRepository.insertAsync(appInfo, this)
                    }
                }
                updateCategoryInDB()
            }else{
                appList.clear()
                appDetailsFromDB.forEach {
                    appList.put(it.packageInfo, it)
                    if(!it.isInternetAllowed){
                        blockedUID.put(it.uid , false)
                    }
                }
            }

            isLoadingComplete = true
        }
    }


    private val APP_URL = "https://play.google.com/store/apps/details?id="
    private val CAT_SIZE = 9
    private val CATEGORY_STRING = "category/"
    private val CATEGORY_GAME_STRING = "GAME_" // All games start with this prefix
    private val DEFAULT_VALUE = "OTHERS"

    private fun updateCategoryInDB() {
        val mDb = AppDatabase.invoke(context.applicationContext)
        val appInfoRepository = mDb.appInfoRepository()//AppInfoRepository(appInfoDAO)
        val categoryInfoRepository = mDb.categoryInfoRepository()
        val categoryDetailsFromDB = categoryInfoRepository.getAppCategoryList()
        var categoryListFromAppList = appInfoRepository.getAppCategoryList()
        if (categoryDetailsFromDB.isEmpty() || categoryDetailsFromDB.size != categoryListFromAppList.size) {
            categoryListFromAppList.forEach {
                val categoryInfo = CategoryInfo()
                categoryInfo.categoryName = it //.replace("_"," ").toLowerCase()
                categoryInfo.numberOFApps = appInfoRepository.getAppCountForCategory(it)
                categoryInfo.isInternetBlocked = false
                categoryInfoRepository.insertAsync(categoryInfo)
            }
        }
    }

    /**
     * Below code to fetch the google play service-application category
     * Not in use as of now.
     */
    private fun fetchCategory(packageName: String) : String {
        return PlayStoreCategory.OTHER.name
        try {
            val url = "$APP_URL$packageName&hl=en" //https://play.google.com/store/apps/details?id=com.example.app&hl=en
            //Log.d("BraveDNS","Insert Category: $packageName")
            val categoryRaw = parseAndExtractCategory(url)
            //Log.d("BraveDNS","Insert Category2: $packageName")
            val storeCategory = PlayStoreCategory.fromCategoryName(categoryRaw ?: PlayStoreCategory.OTHER.name)
            return storeCategory.name
        }catch (e: Exception){
            Log.e("BraveDNS","Exception:"+e.message,e)
            return PlayStoreCategory.OTHER.name
        }

    }

    private fun parseAndExtractCategory(url: String): String? {
        return try {
            val text = Jsoup.connect(url).get()?.select("a[itemprop=genre]") ?: return null
            val href = text.attr("abs:href")

            if (href != null && href.length > 4 && href.contains(CATEGORY_STRING)) {
                getCategoryTypeByHref(href)
            } else {
                PlayStoreCategory.OTHER.name
            }
        } catch (e: Exception) {
            Log.e("BraveDNS","Parse Category"+ e.message,e)
            //TODO handle error
            PlayStoreCategory.OTHER.name
        }
    }

    private fun getCategoryTypeByHref(href: String): String? {
        val appCategoryType = href.substring(href.indexOf(CATEGORY_STRING) + CAT_SIZE, href.length)
        return if (appCategoryType.contains(CATEGORY_GAME_STRING)) {
            PlayStoreCategory.GENERAL_GAMES_CATEGORY_NAME
        } else appCategoryType
    }

    private val onNavigationItemSelectedListener = BottomNavigationView.OnNavigationItemSelectedListener { item ->
        when (item.itemId) {
            R.id.navigation_internet_manager -> {
                supportFragmentManager.beginTransaction().replace(R.id.fragment_container, homeScreenFragment, homeScreenFragment.javaClass.getSimpleName())
                    .commit()
                return@OnNavigationItemSelectedListener true
            }

            R.id.navigation_settings -> {
                println("Settings")
                settingsFragment = SettingsFragment()
                supportFragmentManager.beginTransaction().replace(R.id.fragment_container, settingsFragment, settingsFragment.javaClass.getSimpleName())
                    .commit()
                return@OnNavigationItemSelectedListener true
            }

            R.id.navigation_about -> {
                aboutFragment = AboutFragment()
                supportFragmentManager.beginTransaction().replace(R.id.fragment_container, aboutFragment, aboutFragment.javaClass.getSimpleName())
                    .commit()
                return@OnNavigationItemSelectedListener true
            }
        }
        false
    }




}
