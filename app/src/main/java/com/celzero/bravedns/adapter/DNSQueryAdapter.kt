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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.paging.PagedListAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.database.DNSLogs
import com.celzero.bravedns.ui.DNSBlockListBottomSheetFragment
import java.text.SimpleDateFormat
import java.util.*


class DNSQueryAdapter(val context: Context) : PagedListAdapter<DNSLogs, DNSQueryAdapter.TransactionViewHolder>(DIFF_CALLBACK){


   // private val transactions: MutableList<DNSQueryAdapter.TransactionView> = mutableListOf()

    companion object {
        const val TYPE_CONTROLS: Int = 0
        const val TYPE_TRANSACTION: Int = 1
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<DNSLogs>() {
            // Concert details may have changed if reloaded from the database,
            // but ID is fixed.
            override fun areItemsTheSame(oldConnection: DNSLogs, newConnection: DNSLogs) = oldConnection.id == newConnection.id

            override fun areContentsTheSame(oldConnection: DNSLogs, newConnection: DNSLogs) = oldConnection == newConnection
        }
    }

   /* override fun getItemCount(): Int {
        return (transactions.size)
    }*/

    override fun onBindViewHolder(holder: TransactionViewHolder, position: Int) {
        val transaction : DNSLogs? = getItem(position)
        holder.update(transaction, position)


        /*//TODO Commented this piece of code for the control view for the type Controls. Check that and modify based on it.
        if (true) {
            val transaction: TransactionView? = getItem(position)
            holder.update(transaction!!, position)
        } else {
            throw java.lang.AssertionError(String.format(Locale.ROOT, "Unknown holder %s", holder.javaClass.toString()))
        }*/
    }

    override fun getItemViewType(position: Int): Int {
        return TYPE_TRANSACTION
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TransactionViewHolder {
        return /*if (viewType == TYPE_CONTROLS) {
               //TODO Commented this piece of code for the control view for the type Controls. Check that and modify based on it.
            val v: View = activity.getControlView(parent)
            QueryAdapter.ControlViewHolder(v)
        } else */if (viewType == TYPE_TRANSACTION) {
            val v: View = LayoutInflater.from(parent.context).inflate(
                R.layout.transaction_row,
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

    /*private fun getItem(position: Int): DNSQueryAdapter.TransactionView? {
        return transactions[(transactions.size - 1) - position]
    }*/

    inner class TransactionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        //private var transaction: DNSQueryAdapter.TransactionView? = null

        // Overall view
        private var rowView: View? = null

        // Contents of the condensed view
        private var timeView: TextView? = null
        private var flagView: TextView? = null

        // Contents of the expanded details view
        private var fqdnView: TextView? = null
        private var latencyView: TextView? = null
        private var queryLayoutLL: LinearLayout? = null
        private var queryIndicator: TextView? = null

        init {
            rowView = itemView

            timeView = itemView.findViewById(R.id.response_time)
            flagView = itemView.findViewById(R.id.flag)
            fqdnView = itemView.findViewById(R.id.fqdn)
            latencyView = itemView.findViewById(R.id.latency_val)
            queryLayoutLL = itemView.findViewById(R.id.query_screen_ll)
            queryIndicator = itemView.findViewById(R.id.query_log_indicator)
        }

        fun update(transaction: DNSLogs?, position: Int) {
            // This function can be run up to a dozen times while blocking rendering, so it needs to be
            // as brief as possible.
            //this.transaction = transaction
            if(transaction != null) {
                timeView!!.text = convertLongToTime(transaction.time)
                flagView!!.text = transaction.flag
                //fqdnView!!.text = Utilities.getETldPlus1(transaction.fqdn!!)
                fqdnView!!.text = transaction.queryStr
                latencyView!!.text = transaction.latency.toString() + "ms"

                if (transaction.isBlocked) {
                    queryIndicator!!.visibility = View.VISIBLE
                } else {
                    queryIndicator!!.visibility = View.INVISIBLE
                }
                rowView?.setOnClickListener {
                    //if (!transaction.blockList.isNullOrEmpty()) {
                    rowView?.isEnabled = false
                    openBottomSheet(transaction)
                    rowView?.isEnabled = true
                    //}
                }
            }

        }
    }

    fun openBottomSheet(transaction: DNSLogs){
        val bottomSheetFragment = DNSBlockListBottomSheetFragment(context, transaction)
        val frag = context as FragmentActivity
        bottomSheetFragment.show(frag.supportFragmentManager, bottomSheetFragment.tag)
    }

    fun convertLongToTime(time: Long): String {
        val date = Date(time)
        val format = SimpleDateFormat("HH:mm:ss")
        return format.format(date)
    }

    /**
     * Replace the current list of transactions with these.
     * A null argument is treated as an empty list.
     */
    /*fun reset(transactions: Queue<Transaction?>?) {
        this.transactions.clear()
        if (transactions != null) {
            for (t in transactions) {
                this.transactions.add(TransactionView(t!!))
            }
        } else {
            countryMap = null
        }
        notifyDataSetChanged()
    }*/

    /**
     * Add a new transaction to the top of the displayed list
     */
   /* fun add(transaction: Transaction?) {
        transactions.add(TransactionView(transaction!!))
        notifyItemInserted(1)
    }*/

    /*inner class TransactionView(transaction: Transaction) {

        var fqdn: String? = null

        //var hostname : String? = null
        var time: String? = null
        var flag: String? = null
        var resolver: String? = null
        var response: String? = null
        var latency: String? = null
        var typename: String? = null
        var isBlocked: Boolean = false
        var blockList: String? = null
        var serverIP : String ? =null
        var relayIP : String ? = null
        var responseTime : Long ?= null

        init {
            // If true, the panel is expanded to show details.
            // Human-readable representation of this transaction.
            fqdn = transaction.name
            //hostname = Utilities.getETldPlus1(transaction.name)

            val hour: Int? = transaction.responseCalendar[Calendar.HOUR_OF_DAY]
            val minute: Int? = transaction.responseCalendar[Calendar.MINUTE]
            val second: Int? = transaction.responseCalendar[Calendar.SECOND]
            time = String.format(Locale.ROOT, "%02d:%02d:%02d", hour, minute, second)

            latency = (transaction.responseTime - transaction.queryTime).toString() + "ms"
            typename = getTypeName(transaction.type.toInt())

            responseTime = transaction.responseCalendar.timeInMillis

            blockList = transaction.blockList
            if(DEBUG) Log.d(LOG_TAG, "Transaction block list: $blockList")
            serverIP = transaction.serverIp
            if(!transaction.relayIp.isNullOrEmpty()){
                relayIP = transaction.relayIp
            }

            var serverAddress: InetAddress? = null

            if (transaction.serverIp != null) {
                serverAddress = InetAddress.getByName(transaction.serverIp)
            } else {
                serverAddress = null
            }

            if (serverAddress != null) {
                val countryCode: String = getCountryCode(serverAddress, context) //TODO: Country code things
                resolver = makeAddressPair(countryCode, serverAddress.hostAddress)
            } else {
                resolver = transaction.serverIp
            }


            if(DEBUG) Log.d(LOG_TAG,"transaction.response - ${transaction.response}")

            if (transaction.status === Transaction.Status.COMPLETE) {
                var packet: DnsPacket? = null
                var err: String? = null
                try {
                    packet = DnsPacket(transaction.response)
                } catch (e: ProtocolException) {
                    err = e.message
                }
                if (packet != null) {
                    val addresses: List<InetAddress> = packet.responseAddresses
                    if (addresses.isNotEmpty()) {
                        val destination = addresses[0]
                        if(DEBUG) Log.d(LOG_TAG,"transaction.response - ${destination.address}")
                        val countryCode: String = getCountryCode(destination, context) //TODO : Check on the country code stuff
                        response = makeAddressPair(countryCode, destination.hostAddress)
                        if (destination.hostAddress.contains("0.0.0.0"))
                             isBlocked= true

                        if (destination.isAnyLocalAddress) {
                            if (DEBUG) Log.d(LOG_TAG, "Local address: $serverAddress")
                            isBlocked = true
                        } else if (destination.hostAddress == "::0" || destination.hostAddress == "::1") {
                            if (DEBUG) Log.d(LOG_TAG, "Local equals(::0): $serverAddress")
                            isBlocked = true
                        }
                        if(DEBUG) Log.d(LOG_TAG,"transaction.response - ${destination.hostAddress}")
                        flag = getFlag(countryCode)
                    } else {
                        response = "NXDOMAIN"
                        flag = "\u2754" // White question mark
                    }
                } else {
                    response = err
                    flag = "\u26a0" // Warning sign
                }
            } else {
                response = transaction.status.name
                flag = if (transaction.status === Transaction.Status.CANCELED) {
                    "\u274c" // "X" mark
                } else {
                    "\u26a0" // Warning sign
                }
            }
        }
    }*/

    /*private var countryMap: CountryMap? = null

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
            countryMap = CountryMap(context.assets)
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
    }*/


}