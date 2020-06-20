package com.celzero.bravedns.adapter

import android.os.Build
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ToggleButton
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.net.dns.DnsPacket
import com.celzero.bravedns.net.doh.CountryMap
import com.celzero.bravedns.net.doh.Transaction
import com.celzero.bravedns.ui.QueryDetailActivity
import com.google.common.net.InternetDomainName
import java.io.IOException
import java.net.InetAddress
import java.net.ProtocolException
import java.util.*


class QueryAdapter(val activity : QueryDetailActivity) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    val condensedColor : Int = activity.getResources().getColor(R.color.light)
    val expandedColor : Int = activity.getResources().getColor(R.color.light)



    private var countryMap: CountryMap? = null

    private val transactions: MutableList<QueryAdapter.TransactionView> =  mutableListOf()

    companion object {
        const val TYPE_CONTROLS :Int =0
        const val TYPE_TRANSACTION : Int = 1
    }

    init {

    }

    override fun getItemCount(): Int {
        return (transactions.size)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        //TODO Commented this piece of code for the control view for the type Controls. Check that and modify based on it.
        /*if (holder is ControlViewHolder) {
            if (position != 0) {
                throw java.lang.AssertionError(
                    String.format(
                        Locale.ROOT,
                        "Cannot show control view holder at index %d",
                        position
                    )
                )
            }
        } else*/
        if (holder is TransactionViewHolder) {
            val transaction: TransactionView? = getItem(position)
            holder.update(transaction!!)
        } else {
            throw java.lang.AssertionError(
                String.format(
                    Locale.ROOT,
                    "Unknown holder %s",
                    holder.javaClass.toString()
                )
            )
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
                R.layout.transaction_row,
                parent, false
            )

            Log.i("BraveDNS", "Query List size : "+transactions.size)

            // Workaround for lack of vector drawable background support in pre-Lollipop Android.
            val expand = v.findViewById<View>(R.id.expand)
            // getDrawable automatically rasterizes vector drawables as needed on pre-Lollipop Android.
            // See https://stackoverflow.com/questions/29041027/android-getresources-getdrawable-deprecated-api-22
            val expander = ContextCompat.getDrawable(activity, R.drawable.expander)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                // Only available in API 16+.
                expand.background = expander
            } else {
                // Deprecated starting in API 16.
                expand.setBackgroundDrawable(expander)
            }
            TransactionViewHolder(v)
        } else {
            throw AssertionError(
                String.format(
                    Locale.ROOT,
                    "Unknown viewType %d",
                    viewType
                )
            )
        }
    }

    private fun getItem(position: Int): QueryAdapter.TransactionView? {
        Log.i("BraveDNS", "Query List size : "+transactions.size  + " -- Position : "+position)
        return transactions[(transactions.size-1) - position]
    }

    inner class TransactionViewHolder (itemView: View): RecyclerView.ViewHolder(itemView), View.OnClickListener {

        private var transaction: QueryAdapter.TransactionView? = null
        // Overall view
        private var rowView: View? = null

        // Contents of the condensed view
        private var hostnameView: TextView? = null
        private var timeView: TextView? = null
        private var flagView: TextView? = null
        private var expandButton: ToggleButton? = null

        // Contents of the expanded details view
        private var detailsView: View? = null
        private var fqdnView: TextView? = null
        private var typeView: TextView? = null
        private var latencyView: TextView? = null
        private var resolverView: TextView? = null
        private var responseView: TextView? = null

        init{
            rowView = itemView

            hostnameView = itemView.findViewById(R.id.hostname)
            timeView = itemView.findViewById(R.id.response_time)
            flagView = itemView.findViewById(R.id.flag)
            expandButton = itemView.findViewById(R.id.expand)

            expandButton!!.setOnClickListener(this)

            detailsView = itemView.findViewById(R.id.details)
            fqdnView = itemView.findViewById(R.id.fqdn)
            typeView = itemView.findViewById(R.id.qtype)
            latencyView = itemView.findViewById(R.id.latency_small)
            resolverView = itemView.findViewById(R.id.resolver)
            responseView = itemView.findViewById(R.id.response)
        }

        override fun onClick(view: View?) {
            val position = this.adapterPosition
            val transaction: TransactionView? = getItem(position)
            transaction!!.expanded = ! transaction.expanded
            notifyItemChanged(position)
        }

        fun update(transaction : TransactionView) {
            // This function can be run up to a dozen times while blocking rendering, so it needs to be
            // as brief as possible.
            this.transaction = transaction
            hostnameView!!.setText(transaction.hostname)
            timeView!!.setText(transaction.time)
            flagView!!.setText(transaction.flag)
            setExpanded(transaction.expanded)
        }


        private fun setExpanded(expanded: Boolean) {
            detailsView!!.setVisibility(if (expanded) View.VISIBLE else View.GONE)
            rowView!!.setBackgroundColor(if (expanded) expandedColor else condensedColor)
            expandButton!!.setChecked(expanded)
            if (expanded) {
                // Make sure the details are up to date.
                fqdnView!!.setText(transaction!!.fqdn)
                typeView!!.setText(transaction!!.typename)
                latencyView!!.setText(transaction!!.latency)
                if (transaction!!.resolver != null) {
                    resolverView!!.setText(transaction!!.resolver)
                } else {
                    resolverView!!.setText(R.string.unknown_server)
                }
                responseView!!.setText(transaction!!.response)
            }
        }

    }

    /**
     * Replace the current list of transactions with these.
     * A null argument is treated as an empty list.
     */
    fun reset(transactions: Queue<Transaction?>?) {
        this.transactions.clear()
        if (transactions != null) {
            for (t in transactions) {
                this.transactions.add(TransactionView(t!!))
            }
        } else {
            countryMap = null
        }
        notifyDataSetChanged()
    }

    /**
     * Add a new transaction to the top of the displayed list
     */
    fun add(transaction: Transaction?) {
        transactions.add(TransactionView(transaction!!))
        notifyItemInserted(1)
    }

    inner class TransactionView(transaction : Transaction) {

        var expanded = false
        var fqdn : String? = null
        var hostname : String? = null
        var time : String? = null
        var flag : String? = null
        val template : String ?= "(30) ms"
        var resolver : String? = null
        var response : String? = null
        var latency : String? = null
        var typename : String? = null
            init{
            // If true, the panel is expanded to show details.


            // Human-readable representation of this transaction.
            fqdn = transaction.name
            hostname = getETldPlus1(transaction.name)

            val hour :Int ?= transaction.responseCalendar[Calendar.HOUR_OF_DAY]
            val minute :Int ?= transaction.responseCalendar[Calendar.MINUTE]
            val second :Int ?= transaction.responseCalendar[Calendar.SECOND]
            time  =  String.format(Locale.ROOT, "%02d:%02d:%02d", hour, minute, second)


            latency  = java.lang.String.format(template!!, transaction.responseTime - transaction.queryTime)
            typename = getTypeName(transaction.type.toInt())

            var serverAddress : InetAddress?= null

                if (transaction.serverIp != null) {
                    serverAddress = InetAddress.getByName(transaction.serverIp)
                } else {
                    serverAddress = null
                }



            if (serverAddress != null) {
                val countryCode: String = getCountryCode(serverAddress) //TODO: Country code things
                resolver = makeAddressPair(countryCode, serverAddress.getHostAddress())
            } else {
                resolver = transaction.serverIp
            }



            if (transaction.status === Transaction.Status.COMPLETE) {
                var packet: DnsPacket? = null
                var err: String? = null
                try {
                    packet = DnsPacket(transaction.response)
                } catch (e: ProtocolException) {
                    err = e.message
                }
                if (packet != null) {
                    val addresses: List<InetAddress> = packet.getResponseAddresses()
                    if (addresses.size > 0) {
                        val destination = addresses[0]
                        val countryCode: String = getCountryCode(destination) //TODO : Check on the country code stuff
                        response = makeAddressPair(countryCode, destination.hostAddress)
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


    // Convert an FQDN like "www.example.co.uk." to an eTLD + 1 like "example.co.uk".
    private fun getETldPlus1(fqdn: String): String? {
        return try {
            val name: InternetDomainName = InternetDomainName.from(fqdn)
            try {
                name.topPrivateDomain().toString()
            } catch (e: IllegalStateException) {
                // The name doesn't end in a recognized TLD.  This can happen for randomly generated
                // names, or when new TLDs are introduced.
                val parts: List<String> = name.parts()
                val size = parts.size
                if (size >= 2) {
                    parts[size - 2] + "." + parts[size - 1]
                } else if (size == 1) {
                    parts[0]
                } else {
                    // Empty input?
                    fqdn
                }
            }
        } catch (e: IllegalArgumentException) {
            // If fqdn is not a valid domain name, InternetDomainName.from() will throw an
            // exception.  Since this function is only for aesthetic purposes, we can
            // return the input unmodified in this case.
            fqdn
        }
    }

    // Return a two-letter ISO country code, or null if that fails.
    private fun getCountryCode(address: InetAddress): String {
        activateCountryMap()
        return (if (countryMap == null) {
            null
        } else {
            Log.i("BraveDNS","Country Map : getCountryCode")
            countryMap!!.getCountryCode(address)
        })!!
    }

    private fun activateCountryMap() {
        if (countryMap != null) {
            return
        }
        try {
            Log.i("BraveDNS","Country Map 1")
            countryMap = CountryMap(activity.getAssets())
            if(countryMap == null){
                Log.i("BraveDNS","Country Map is null??")
            }else{
                Log.i("BraveDNS","Country Map is not null??")
            }
        } catch (e: IOException) {
            Log.e("BraveDNS Exception",e.message)
        }
    }

    private fun makeAddressPair(
        countryCode: String?,
        ipAddress: String
    ): String? {
        return if (countryCode == null) {
            ipAddress
        } else String.format("%s (%s)", countryCode, ipAddress)
    }



}