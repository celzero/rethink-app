package com.celzero.bravedns.adapter

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.data.IPDetails
import com.celzero.bravedns.net.doh.CountryMap
import com.celzero.bravedns.net.doh.Transaction
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.ConnectionTrackerActivity
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.util.FileSystemUID
import com.celzero.bravedns.util.Protocol
import org.w3c.dom.Text
import java.io.IOException
import java.lang.Exception
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.*


class ConnectionTrackingAdapter(val activity : ConnectionTrackerActivity) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {



    private var ipDetailsList: MutableList<ConnectionTrackingAdapter.IPTrackerView> =  mutableListOf()
    private var originalList: MutableList<ConnectionTrackingAdapter.IPTrackerView> =  mutableListOf()

    companion object {
        const val TYPE_CONTROLS :Int =0
        const val TYPE_TRANSACTION : Int = 1
    }

    init {

    }

    override fun getItemCount(): Int {
        //Log.d("BraveDNS","Request getItemCount: ${ipDetailsList.size}")
        return (ipDetailsList.size)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        //TODO Commented this piece of code for the control view for the type Controls. Check that and modify based on it.
        if (holder is TransactionViewHolder) {
            val ipTrackerView: IPTrackerView? = getItem(position)
            holder.update(ipTrackerView!!,position)
        } else {
            throw java.lang.AssertionError(String.format(Locale.ROOT, "Unknown holder %s", holder.javaClass.toString()))
        }
    }

    override fun getItemViewType(position: Int): Int {
        return TYPE_TRANSACTION
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return /*if (viewType == TYPE_CONTROLS) {
               //TODO Commented this piece of code for the control view for the type Controls. Check that and modify based on it.
            val v: View = activity.getControlView(parent)
            QueryAdapter.ControlViewHolder(v)
        } else */if (viewType == TYPE_TRANSACTION) {
            val v: View = LayoutInflater.from(parent.context).inflate(
                R.layout.connection_transaction_row,
                parent, false
            )

            // Workaround for lack of vector drawable background support in pre-Lollipop Android.
            //val expand = v.findViewById<View>(R.id.expand)
            // getDrawable automatically rasterizes vector drawables as needed on pre-Lollipop Android.
            // See https://stackoverflow.com/questions/29041027/android-getresources-getdrawable-deprecated-api-22
            //val expander = ContextCompat.getDrawable(activity, R.drawable.expander)
            /*if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                // Only available in API 16+.
                expand.background = expander
            } else {
                // Deprecated starting in API 16.
                expand.setBackgroundDrawable(expander)
            }*/
            TransactionViewHolder(v)
        } else {
            throw AssertionError(String.format(Locale.ROOT, "Unknown viewType %d", viewType))
        }
    }

    private fun getItem(position: Int): ConnectionTrackingAdapter.IPTrackerView? {
        //Log.d("BraveDNS","getItem Request $position , ${ipDetailsList.size}")
        return ipDetailsList[(ipDetailsList.size-1) - position]
    }

    fun searchData(query : String?){
        //var originalList = ipDetailsList
        ipDetailsList = originalList
        ipDetailsList = ipDetailsList.filter { a -> a.appName!!.toLowerCase().contains(query!!.toLowerCase()) }.toMutableList()
        //Log.d("BraveDNS","Search : Query : $query, size: ${ipDetailsList.size}")
        this.notifyDataSetChanged()
    }

    fun filterData(query: String?) {
        //var originalList = ipDetailsList
        ipDetailsList = originalList
        ipDetailsList = ipDetailsList.filter { a -> a.sourceIP.equals(query) }.toMutableList()
        this.notifyDataSetChanged()
    }


    inner class TransactionViewHolder (itemView: View): RecyclerView.ViewHolder(itemView) {

        //private var transaction: ConnectionTrackingAdapter.TransactionView? = null
        private var ipTrackerView: ConnectionTrackingAdapter.IPTrackerView? = null
        // Overall view
        private var rowView: View? = null
        // Contents of the condensed view
        private var timeView: TextView? = null
        private var flagView: TextView? = null

        private var fqdnView: TextView? = null
        private var ipView: TextView?=null
        private var latencyView: TextView? = null
        private var queryLayoutLL : LinearLayout ?= null
        private var connectionType : TextView ?= null
        private var appIcon : ImageView ?= null
        private var connectionIndicator :  TextView ?= null

        init{
            rowView = itemView
            timeView = itemView.findViewById(R.id.connection_response_time)
            flagView = itemView.findViewById(R.id.connection_flag)
            fqdnView = itemView.findViewById(R.id.connection_app_name)
            ipView = itemView.findViewById(R.id.connection_ip_address)
            latencyView = itemView.findViewById(R.id.connection_latency_val)
            connectionType = itemView.findViewById(R.id.connection_type)
            queryLayoutLL = itemView.findViewById(R.id.connection_screen_ll)
            appIcon = itemView.findViewById(R.id.connection_app_icon)
            connectionIndicator = itemView.findViewById(R.id.connection_status_indicator)
        }

        fun update(ipTrackerView : IPTrackerView, position: Int) {
            this.ipTrackerView = ipTrackerView
            var time = convertLongToTime(this.ipTrackerView!!.timeStamp)
            timeView!!.setText(time)
            flagView!!.setText(this.ipTrackerView!!.flag)
            ipView!!.setText(this.ipTrackerView!!.destIP)
            latencyView!!.setText(this.ipTrackerView!!.destPort)
            fqdnView!!.setText(this.ipTrackerView!!.appName)
            appIcon!!.setImageDrawable(this.ipTrackerView!!.icon)
            connectionType!!.setText(this.ipTrackerView!!.protocolName)
            if(this.ipTrackerView!!.isBlocked!!)
                connectionIndicator!!.visibility = View.VISIBLE
            else
                connectionIndicator!!.visibility = View.INVISIBLE
            /*var packageName = activity.packageManager.getNameForUid(this.ipTrackerView!!.uid1111)
            Log.d("BraveDNS", "IPTracker(update) PackageName:  $packageName, ${this.ipTrackerView!!.uid}")
            if(packageName != null) {
                if(packageName.contains(":")){
                    packageName = packageName.split(":")[0]
                    Log.d("BraveDNS", "IPTracker(post split) PackageName:  $packageName, ${this.ipTrackerView!!.uid}")
                }
                try {
                    val appInfo = activity.packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
                    fqdnView!!.setText(activity.packageManager.getApplicationLabel(appInfo))
                    appIcon!!.setImageDrawable(activity.packageManager.getApplicationIcon(packageName))
                }catch (e : Exception){
                    fqdnView!!.setText("Unknown")
                    appIcon!!.setImageDrawable(activity.resources.getDrawable(android.R.drawable.sym_def_app_icon))
                }
            }else{
                var packageName = FileSystemUID.fromFileSystemUID(this.ipTrackerView!!.uid)
                if(packageName.uid == -1)
                    fqdnView!!.setText("Unknown")
                else
                    fqdnView!!.setText(packageName.name)
                appIcon!!.setImageDrawable(activity.resources.getDrawable(android.R.drawable.sym_def_app_icon))
            }*/
           /* if(this.ipTrackerView!!.uid == 0 || this.ipTrackerView!!.uid == -1)
                connectionType!!.setText("UDP")
            else
                connectionType!!.setText("TCP")*/

           /* if(this.ipTrackerView!!.destPort.equals("53"))
                connectionType!!.setText("DNS")*/


        }
    }

    /**
     * Replace the current list of transactions with these.
     * A null argument is treated as an empty list.
     */
    fun reset(ipDetails : Queue<IPDetails?>?) {

        this.ipDetailsList.clear()
        if (ipDetails != null) {
            for (t in ipDetails) {
                this.ipDetailsList.add(IPTrackerView(t!!))
                originalList = ipDetailsList
            }
        } else {
            countryMap = null
        }
        Log.d("BraveDNS","reset Request ${ipDetails!!.size}")
        notifyDataSetChanged()
    }

    /**
     * Add a new transaction to the top of the displayed list
     */
    fun add(ipDetails: IPDetails) {
        Log.d("BraveDNS","Adapter Request ${ipDetails.destIP}, ${ipDetails.destPort}")
        ipDetailsList.add(IPTrackerView(ipDetails))
        originalList = ipDetailsList
        notifyItemInserted(0)
    }

    inner class IPTrackerView(ipDetails: IPDetails) {

        var uid : Int = 0
        var sourceIP : String ?= null
        var sourcePort : String ?= null
        var destIP : String ?= null
        var destPort: String ?= null
        var timeStamp : Long = 0L
        var resolver : String? = null
        var flag : String? = null
        var appName : String ? = null
        var isBlocked : Boolean ?= false
        var icon : Drawable ?= null
        var protocolName : String = ""

        init{
            // If true, the panel is expanded to show details.
            // Human-readable representation of this transaction.
            uid = ipDetails.uid
            sourceIP = ipDetails.sourceIP
            sourcePort = ipDetails.sourcePort
            destIP = ipDetails.destIP
            destPort = ipDetails.destPort
            timeStamp = ipDetails.timeStamp
            isBlocked = ipDetails.isBlocked
            Log.d("BraveDNS","Protocol: ${ipDetails.protocol}")
            protocolName = Protocol.getProtocolName(ipDetails.protocol).name

            var serverAddress: InetAddress? = null

            if (destIP != null) {
                serverAddress = InetAddress.getByName(destIP)
            } else {
                serverAddress = null
            }

            if (destIP != null) {
                val countryCode: String = getCountryCode(serverAddress!! , activity) //TODO: Country code things
                resolver = makeAddressPair(countryCode, destIP!!)
            } else {
                resolver = ipDetails.sourceIP
            }
            val countryCode: String = getCountryCode(serverAddress!!,activity as Context)
            flag = getFlag(countryCode)

            //appname
            var packageName = activity.packageManager.getPackagesForUid(uid)

            if(packageName != null) {
                HomeScreenActivity.GlobalVariable.appList.forEach{
                    if(it.value.uid == this.uid){
                        appName = it.value.appName
                        icon = activity.packageManager.getApplicationIcon(it.value.packageInfo)
                    }
                }
                /*if(packageName.contains(":")){
                    packageName = packageName.split(":")[0]
                }
                try {
                    val appInfo = activity.packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
                    Log.d("BraveDNS","Connection: appInfo - ${appInfo.uid}")
                    appName = activity.packageManager.getApplicationLabel(appInfo).toString()
                    icon = activity.packageManager.getApplicationIcon(packageName)
                }catch (e : Exception){
                    appName = "Unknown"
                    icon = activity.resources.getDrawable(android.R.drawable.sym_def_app_icon)
                }*/
            }else{
                var packageName = FileSystemUID.fromFileSystemUID(uid)
                if(packageName.uid == -1)
                    appName = "Unknown"
                else
                    appName = packageName.name
                icon = activity.resources.getDrawable(android.R.drawable.sym_def_app_icon)
            }
            Log.d("BraveDNS","Protocol: ${ipDetails.protocol} - $protocolName, packageName: $appName")
        }
    }

    private var countryMap: CountryMap? = null

    // Return a two-letter ISO country code, or null if that fails.
    fun getCountryCode(address: InetAddress, context: Context): String {
        activateCountryMap(context)
        return (if (countryMap == null) {
            null
        } else {
            countryMap!!.getCountryCode(address)
        })!!
    }

    private fun activateCountryMap(context: Context) {
        if (countryMap != null) {
            return
        }
        try {
            countryMap = CountryMap(context.getAssets())
        } catch (e: IOException) {
            Log.e("BraveDNS Exception", e.message, e)
        }
    }


    private fun getTypeName(type: Int): String? {
        // From https://www.iana.org/assignments/dns-parameters/dns-parameters.xhtml#dns-parameters-4
        val names = arrayOf(
            "0",
            "A",
            "NS",
            "MD",
            "MF",
            "CNAME",
            "SOA",
            "MB",
            "MG",
            "MR",
            "NULL",
            "WKS",
            "PTR",
            "HINFO",
            "MINFO",
            "MX",
            "TXT",
            "RP",
            "AFSDB",
            "X25",
            "ISDN",
            "RT",
            "NSAP",
            "NSAP+PTR",
            "SIG",
            "KEY",
            "PX",
            "GPOS",
            "AAAA",
            "LOC",
            "NXT",
            "EID",
            "NIMLOC",
            "SRV",
            "ATMA",
            "NAPTR",
            "KX",
            "CERT",
            "A6",
            "DNAME",
            "SINK",
            "OPT",
            "APL",
            "DS",
            "SSHFP",
            "IPSECKEY",
            "RRSIG",
            "NSEC",
            "DNSKEY",
            "DHCID",
            "NSEC3",
            "NSEC3PARAM",
            "TLSA",
            "SMIMEA"
        )
        return if (type < names.size) {
            names[type]
        } else String.format(Locale.ROOT, "%d", type)
    }


    private fun getFlag(countryCode: String?): String? {
        if (countryCode == null) {
            return ""
        }
        // Flag emoji consist of two "regional indicator symbol letters", which are
        // Unicode characters that correspond to the English alphabet and are arranged in the same
        // order.  Therefore, to convert from a country code to a flag, we simply need to apply an
        // offset to each character, shifting it from the normal A-Z range into the region indicator
        // symbol letter range.
        val alphaBase = 'A'.toInt() // Start of alphabetic country code characters.
        val flagBase = 0x1F1E6 // Start of regional indicator symbol letters.
        val offset = flagBase - alphaBase
        val firstHalf = Character.codePointAt(countryCode, 0) + offset
        val secondHalf = Character.codePointAt(countryCode, 1) + offset
        return String(Character.toChars(firstHalf)) + String(
            Character.toChars(
                secondHalf
            )
        )
    }


    private fun makeAddressPair(
        countryCode: String?,
        ipAddress: String
    ): String? {
        return if (countryCode == null) {
            ipAddress
        } else String.format("%s (%s)", countryCode, ipAddress)
    }

    fun convertLongToTime(time: Long): String {
        val date = Date(time)
        val format = SimpleDateFormat("HH:mm:ss")
        return format.format(date)
    }



}