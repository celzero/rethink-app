package com.celzero.bravedns.ui

import android.app.Activity
import android.app.ActivityManager
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.Html
import android.text.Html.FROM_HTML_MODE_LEGACY
import android.text.Spanned
import android.text.format.DateUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.automaton.FirewallRules
import com.celzero.bravedns.data.ConnectionRules
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.AppInfoRepository
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Protocol
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ConnTrackerBottomSheetFragment(var contextVal: Context, var ipDetails: ConnectionTracker) : BottomSheetDialogFragment() {

    private var fragmentView: View? = null

    private lateinit var txtRule1 : TextView
    private lateinit var txtRule2 : TextView
    private lateinit var txtRule3 : TextView

    private lateinit var txtAppName: TextView
    private lateinit var txtAppBlockDesc : TextView
    private lateinit var txtAppBlock: TextView
    private lateinit var txtConnDetails : TextView
    private lateinit var txtConnectionIP : TextView
    private lateinit var txtFlag : TextView
    private lateinit var imgAppIcon: ImageView

    private lateinit var switchBlockApp : SwitchCompat
    private lateinit var switchBlockConnApp : SwitchCompat
    private lateinit var switchBlockConnAll : SwitchCompat

    private lateinit var chipKillApp : Chip
    private lateinit var chipClearRules : Chip

    private lateinit var firewallRules: FirewallRules

    private var isAppBlocked : Boolean = false
    private var isRuleBlocked : Boolean = false
    private var isRuleUniversal : Boolean = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        firewallRules = FirewallRules.getInstance()
    }

    companion object{
        const val UNIVERSAL_RULES_UID= -1000
    }

    override fun getTheme(): Int = R.style.BottomSheetDialogTheme
    lateinit var mDb : AppDatabase
    lateinit var appInfoRepository : AppInfoRepository

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        fragmentView = inflater.inflate(R.layout.bottom_sheet_conn_track, container, false)
        mDb = AppDatabase.invoke(contextVal.applicationContext)
        appInfoRepository = mDb.appInfoRepository()
        //initView(fragmentView)
        return fragmentView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView(view)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState)
    }

    private fun initView(view: View) {
        txtAppName = view.findViewById(R.id.bs_conn_track_app_name)
        imgAppIcon = view.findViewById(R.id.bs_conn_track_app_icon)

        txtRule1 = view.findViewById(R.id.bs_conn_block_app_txt)
        txtRule2 = view.findViewById(R.id.bs_conn_block_conn_app_txt)
        txtRule3 = view.findViewById(R.id.bs_conn_block_conn_all_txt)

        txtAppBlock = view.findViewById(R.id.bs_conn_blocked_desc)
        txtAppBlockDesc = view.findViewById(R.id.bs_conn_blocked_desc_2)
        txtConnDetails = view.findViewById(R.id.bs_conn_connection_details)

        txtConnectionIP = view.findViewById(R.id.bs_conn_connection_type_heading)
        txtFlag = view.findViewById(R.id.bs_conn_connection_flag)

        chipKillApp = view.findViewById(R.id.bs_conn_track_app_kill)
        chipClearRules = view.findViewById(R.id.bs_conn_track_app_clear_rules)

        switchBlockApp = view.findViewById(R.id.bs_conn_block_app_check)
        switchBlockConnAll = view.findViewById(R.id.bs_conn_block_conn_all_switch)
        switchBlockConnApp = view.findViewById(R.id.bs_conn_block_conn_app_switch)

        var protocol = Protocol.getProtocolName(ipDetails.protocol).name
        txtAppName.setText(ipDetails.appName)
        //var time = Utilities.convertLongToTime(ipDetails!!.timeStamp)

        txtConnectionIP.text = ipDetails.ipAddress!!
        txtFlag.text = ipDetails.flag.toString()

        var _text = getString(R.string.bsct_block)
        var _styledText: Spanned = Html.fromHtml(_text, FROM_HTML_MODE_LEGACY)
        txtRule1.text = _styledText

        _text = getString(R.string.bsct_block_app)
        _styledText = Html.fromHtml(_text, FROM_HTML_MODE_LEGACY)
        txtRule2.text = _styledText

        _text = getString(R.string.bsct_block_all)
        _styledText = Html.fromHtml(_text, FROM_HTML_MODE_LEGACY)
        txtRule3.text = _styledText


        val time = DateUtils.getRelativeTimeSpanString(ipDetails.timeStamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)

        _text = getString(R.string.bsct_conn_conn_desc, ipDetails.appName, ipDetails.ipAddress, ipDetails.port.toString(),protocol)
        _styledText = Html.fromHtml(_text, FROM_HTML_MODE_LEGACY)
        txtConnDetails.text = _styledText

        if (ipDetails!!.appName != "Unknown") {
            try {
                var appArray = contextVal.packageManager.getPackagesForUid(ipDetails!!.uid)
                imgAppIcon!!.setImageDrawable(contextVal.packageManager.getApplicationIcon(appArray?.get(0)!!))
            } catch (e: Exception) {
                Log.e("BraveDNS", "Package Not Found - " + e.message, e)
            }
        }

        isAppBlocked = FirewallManager.checkInternetPermission(ipDetails.uid)
        val connRules = ConnectionRules(ipDetails.ipAddress!!, ipDetails.port, protocol)
        isRuleBlocked = firewallRules.checkRules(ipDetails.uid, connRules)
        isRuleUniversal = firewallRules.checkRules(UNIVERSAL_RULES_UID, connRules)

        switchBlockApp.setOnCheckedChangeListener(null)
        switchBlockApp.setOnClickListener{
            firewallApp(isAppBlocked)
        }


        switchBlockApp.isChecked = isAppBlocked
        if(isAppBlocked){
            txtAppBlockDesc.visibility = View.VISIBLE
            val text = getString(R.string.bsct_conn_block_desc, time)
            val styledText: Spanned = Html.fromHtml(text, FROM_HTML_MODE_LEGACY)
            txtAppBlock.text =  styledText
            txtAppBlockDesc.text = "Rule #1"
        }else if(isRuleBlocked){
            txtAppBlockDesc.visibility = View.VISIBLE
            val text = getString(R.string.bsct_conn_block_desc, time)
            val styledText: Spanned = Html.fromHtml(text, FROM_HTML_MODE_LEGACY)
            txtAppBlock.text =  styledText
            switchBlockConnApp.isChecked = true
            txtAppBlockDesc.text = "Rule #2"
        }else if(isRuleUniversal){
            txtAppBlockDesc.visibility = View.VISIBLE
            val text = getString(R.string.bsct_conn_block_desc, time)
            val styledText: Spanned = Html.fromHtml(text, FROM_HTML_MODE_LEGACY)
            txtAppBlock.text = styledText
            switchBlockConnAll.isChecked = true
            txtAppBlockDesc.text = "Rule #3"
        }else{
            val text = getString(R.string.bsct_conn_unblock_desc, time)
            txtAppBlock.text =  text
            txtAppBlockDesc.visibility = View.GONE
            switchBlockConnApp.isChecked = false
        }


        switchBlockConnAll.setOnCheckedChangeListener(null)
        switchBlockConnAll.setOnClickListener {
            if (isRuleUniversal) {
                firewallRules.removeFirewallRules(UNIVERSAL_RULES_UID, connRules.ipAddress, contextVal)
                isRuleUniversal = false
                Toast.makeText(contextVal, "IP address rule cleared for all apps", Toast.LENGTH_SHORT).show()
            } else {
                firewallRules.addFirewallRules(UNIVERSAL_RULES_UID, connRules.ipAddress, contextVal)
                isRuleUniversal = true
                Toast.makeText(contextVal, "IP address rule applied for all apps", Toast.LENGTH_SHORT).show()
            }
        }

        switchBlockConnApp.setOnCheckedChangeListener(null)
        switchBlockConnApp.setOnClickListener {
            var allApps = false
            val appUIDList = appInfoRepository.getAppListForUID(ipDetails.uid)
            if (appUIDList.size > 1) {
                var title = "Adding rules for \"${ipDetails.appName}\" will also include these ${appUIDList.size} other apps"
                var positiveText = "Apply rule"
                if(isRuleBlocked){
                    title = "Removing rules for \"${ipDetails.appName}\" will also remove rules for these ${appUIDList.size} other apps"
                }
                allApps = showDialog(appUIDList, ipDetails.appName!!, title, positiveText)
                if (allApps) {
                    if (isRuleBlocked) {
                        firewallRules.removeFirewallRules(ipDetails.uid, connRules.ipAddress, contextVal)
                        isRuleBlocked = false
                    } else {
                        firewallRules.addFirewallRules(ipDetails.uid, connRules.ipAddress, contextVal)
                        isRuleBlocked = true
                    }
                }
                switchBlockConnApp.isChecked = isRuleBlocked
            } else {
                if (isRuleBlocked) {
                    firewallRules.removeFirewallRules(ipDetails.uid, connRules.ipAddress, contextVal)
                    isRuleBlocked = false
                } else {
                    firewallRules.addFirewallRules(ipDetails.uid, connRules.ipAddress, contextVal)
                    isRuleBlocked = true
                }
            }
        }

        chipKillApp.setOnClickListener{
            try {
                val appUIDList = appInfoRepository.getAppListForUID(ipDetails.uid)
                if (appUIDList.size == 1) {
                    val activityManager: ActivityManager = contextVal.getSystemService(Activity.ACTIVITY_SERVICE) as ActivityManager
                    val mDb = AppDatabase.invoke(contextVal.applicationContext)
                    val appInfoRepository = mDb.appInfoRepository()
                    if (ipDetails.appName != null || ipDetails.appName!!.equals("Unknown")) {
                        val packageName = appInfoRepository.getPackageNameForAppName(ipDetails.appName!!)
                        activityManager.killBackgroundProcesses(packageName)
                        Toast.makeText(contextVal, "${ipDetails.appName} - Kill success", Toast.LENGTH_SHORT).show()
                        if (DEBUG) Log.d("BraveDNS", "App kill - $packageName")
                    } else {
                        Toast.makeText(contextVal, "Can't able to kill the app", Toast.LENGTH_SHORT).show()
                    }
                }else{
                    Toast.makeText(contextVal, "System app - kill denied", Toast.LENGTH_SHORT).show()
                }
            }catch (e : java.lang.Exception){
                Toast.makeText(contextVal, "Can't able to kill the app", Toast.LENGTH_SHORT).show()
            }
        }

        chipClearRules.setOnClickListener{
            clearAppRules()
        }
    }

    private fun firewallApp(isBlocked : Boolean) {
        var blockAllApps = false
        val appUIDList = appInfoRepository.getAppListForUID(ipDetails.uid)
        if (appUIDList.size > 1) {
            var title = "Blocking \"${ipDetails.appName}\" will also block these ${appUIDList.size} other apps"
            var positiveText = "Block ${appUIDList.size} apps"
            if(isBlocked){
                title = "Unblocking \"${ipDetails.appName}\" will also unblock these ${appUIDList.size} other apps"
                positiveText = "Unblock ${appUIDList.size} apps"
            }
            blockAllApps = showDialog(appUIDList, ipDetails.appName!!, title,positiveText)
        }
        if (appUIDList.size <= 1 || blockAllApps) {
            val uid = ipDetails.uid
            CoroutineScope(Dispatchers.IO).launch {
                appUIDList.forEach {
                    PersistentState.setExcludedPackagesWifi(it.packageInfo, isBlocked, contextVal)
                    FirewallManager.updateAppInternetPermission(it.packageInfo, isBlocked)
                    FirewallManager.updateAppInternetPermissionByUID(it.uid, isBlocked)
                }
                appInfoRepository.updateInternetForuid(uid, isBlocked)
            }
        } else {
            switchBlockApp.isChecked = isBlocked
        }
    }

    private fun clearAppRules() {
        val blockAllApps: Boolean
        val appUIDList = appInfoRepository.getAppListForUID(ipDetails.uid)
        if (appUIDList.size > 1) {
            val title = "Clearing rules for \"${ipDetails.appName}\" will also clear rules for these ${appUIDList.size} other apps"
            val positiveText = "Clear rules"
            blockAllApps = showDialog(appUIDList, ipDetails.appName!!, title, positiveText)
            if (blockAllApps) {
                firewallRules.clearFirewallRules(ipDetails.uid, contextVal)
                Toast.makeText(contextVal, getString(R.string.bsct_rules_cleared_toast), Toast.LENGTH_SHORT).show()
            }
        } else {
            showAlertForClearRules()
        }
    }

    private fun showAlertForClearRules() {
        val builder = AlertDialog.Builder(contextVal)
        //set title for alert dialog
        builder.setTitle(R.string.bsct_alert_message_clear_rules_heading)
        //set message for alert dialog
        builder.setMessage(R.string.bsct_alert_message_clear_rules)
        builder.setIcon(android.R.drawable.ic_dialog_alert)
        builder.setCancelable(true)
        //performing positive action
        builder.setPositiveButton("Clear") { dialogInterface, which ->
            firewallRules.clearFirewallRules(ipDetails.uid, contextVal)
            switchBlockConnApp.isChecked = false
            Toast.makeText(contextVal, getString(R.string.bsct_rules_cleared_toast), Toast.LENGTH_SHORT).show()
        }

        //performing negative action
        builder.setNeutralButton("Cancel") { dialogInterface, which ->
        }
        // Create the AlertDialog
        val alertDialog: AlertDialog = builder.create()
        // Set other dialog properties
        alertDialog.setCancelable(false)
        alertDialog.show()
    }

    private  fun showDialog(packageList: List<AppInfo>, appName: String, title: String, positiveText : String) : Boolean{
            //Change the handler logic into some other
           val handler: Handler = object : Handler() {
               override fun handleMessage(mesg: Message?) {
                   throw RuntimeException()
               }
           }
           var positiveTxt  = ""
           val packageNameList:List<String> = packageList.map { it.appName }
           var proceedBlocking : Boolean = false

           val builderSingle: AlertDialog.Builder = AlertDialog.Builder(contextVal)

           builderSingle.setIcon(R.drawable.spinner_firewall)
            builderSingle.setTitle(title)
            positiveTxt = positiveText
          /* if(!isInternet && isFirewallDialog) {
                builderSingle.setTitle("Blocking \"$appName\" will also block these ${packageList.size} other apps")
                positiveTxt = "Block ${packageList.size} apps"
           }
           else if(isFirewallDialog) {
               builderSingle.setTitle("Unblocking \"$appName\" will also unblock these ${packageList.size} other apps")
               positiveTxt = "Unblock ${packageList.size} apps"
           }else if(!isFirewallDialog){
               builderSingle.setTitle("Clearing rules for \"$appName\" will also clear rules for these ${packageList.size} other apps")
               positiveTxt = "Clear rules"
           }*/
           val arrayAdapter = ArrayAdapter<String>(contextVal, android.R.layout.simple_list_item_activated_1)
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
           alertDialog.getListView().setOnItemClickListener({ adapterView, subview, i, l -> })
           alertDialog.setCancelable(false)
           try {
               Looper.loop()
           } catch (e2: java.lang.RuntimeException) {
           }

           return proceedBlocking
       }

}