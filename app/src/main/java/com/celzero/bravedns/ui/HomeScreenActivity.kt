package com.celzero.bravedns.ui

import android.annotation.TargetApi
import android.app.*
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.celzero.bravedns.R
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.util.ApkUtilities.Companion.hasPermissionToReadPhoneStats
import com.celzero.bravedns.util.ApkUtilities.Companion.requestPhoneStateStats
import com.celzero.bravedns.util.DatabaseHandler
import com.celzero.bravedns.util.NetworkStatsHelper
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch



class HomeScreenActivity : AppCompatActivity() {

    lateinit var internetManagerFragment: InternetManagerFragment
    lateinit var permissionManagerFragment : PermissionManagerFragment
    lateinit var applicationManagerFragment : ApplicationManagerFragment
    lateinit var homeScreenFragment: HomeScreenFragment
    lateinit var context: Context
    lateinit var appSample : AppInfo
    companion object {
        lateinit var dbHandler : DatabaseHandler


        //TODO : Check for the functionality of the method and uses
        fun openAppIntent(context: Context, appExtras:Bundle? = null):Intent {
             return   Intent(context, HomeScreenActivity::class.java).apply {
                    if(appExtras != null) putExtras(appExtras)
                }
        }

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
            supportFragmentManager.beginTransaction().replace(R.id.fragment_container, homeScreenFragment, homeScreenFragment.javaClass.getSimpleName())
                .commit()
        }
        navView.setOnNavigationItemSelectedListener(onNavigationItemSelectedListener)

        getAppInfo()

    }





    private fun getAppInfo() {
        GlobalScope.launch(Dispatchers.IO) {
            //val mDb = Room.databaseBuilder(context.applicationContext, AppDatabase::class.java,"brave.db").build()
            val mDb = AppDatabase.invoke(context.applicationContext)
            //val appInfoDAO  = mDb.appInfoDAO()
            val appInfoRepository = mDb.appInfoRepository()//AppInfoRepository(appInfoDAO)
            val allPackages: List<PackageInfo> =
                context.packageManager?.getInstalledPackages(PackageManager.GET_META_DATA )!!
            var count : Int = 0
            allPackages.forEach {
                launch(Dispatchers.Default) {

                    val applicationInfo: ApplicationInfo = it.applicationInfo
                    val appInfo = AppInfo()
                    appInfo.appName = context.packageManager.getApplicationLabel(applicationInfo).toString()
                    appInfo.appCategory = applicationInfo.category
                    appInfo.isDataEnabled = true
                    appInfo.isWifiEnabled = true
                    appInfo.mobileDataUsed = 0
                    appInfo.packageInfo = applicationInfo.packageName
                    appInfo.trackers = 0
                    appInfo.wifiDataUsed = 0
                    appInfo.uid = applicationInfo.uid

                    appSample = appInfo
                    //println("doInBackground apkList: " + appInfo.appName)
                    appInfoRepository.insertAsync(appInfo,this)
                    count = count + 1
                    Log.w("DB Inserts","App Size : " + appInfo.packageInfo +": "+appInfo.uid)
                }

            }
                //delay(1 * 60 * 100)
        }
            Log.w("DB","End of the loop for the DB")
    }


    /*private fun actionOnService(action: Actions) {
        if (getServiceState(this.applicationContext) == ServiceState.STOPPED && action == Actions.STOP) return
        Log.w("Service","Starting the service")
        Intent(this.applicationContext, DnsService::class.java).also {
            Log.w("Service","Starting the service in >=26 Mode")
            it.action = action.name
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                this.applicationContext.startForegroundService(it)
                return
            }
            Log.w("Service","Starting the service in < 26 Mode")
            this.applicationContext.startService(it)
        }
    }*/

    //TODO : Check for the requirement of the method.
    @TargetApi(Build.VERSION_CODES.M)
    private fun fillNetworkStatsPackage(uid: Int ) {
        val networkStatsManager =
            applicationContext.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
        val networkStatsHelper = NetworkStatsHelper(networkStatsManager, uid)
        val mobileWifiRx = networkStatsHelper.getPackageRxBytesMobile(this) + networkStatsHelper.packageRxBytesWifi
        Log.w("NetworkStat","$mobileWifiRx B")
        //networkStatsPackageRx.setText("$mobileWifiRx B")
        val mobileWifiTx = networkStatsHelper.getPackageTxBytesMobile(this) + networkStatsHelper.packageTxBytesWifi
        //networkStatsPackageTx.setText("$mobileWifiTx B")
        Log.w("NetworkStat","$mobileWifiTx B")
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
            R.id.navigation_permission_manager -> {
                println("Permission Manager")

                permissionManagerFragment = PermissionManagerFragment()
                supportFragmentManager.beginTransaction().replace(R.id.fragment_container, permissionManagerFragment, permissionManagerFragment.javaClass.getSimpleName())
                    .commit()
                return@OnNavigationItemSelectedListener true
            }
            R.id.navigation_settings -> {
                println("Settings")
                applicationManagerFragment = ApplicationManagerFragment()
                supportFragmentManager.beginTransaction().replace(R.id.fragment_container, applicationManagerFragment, applicationManagerFragment.javaClass.getSimpleName())
                    .commit()
                return@OnNavigationItemSelectedListener true
            }
        }
        false
    }




}
