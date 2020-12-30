/*
 *Copyright 2020 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.ui

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.util.Log
import android.util.Patterns
import android.view.*
import android.widget.*
import androidx.appcompat.widget.AppCompatButton
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.*
import com.celzero.bravedns.database.*
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.VpnState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.appMode
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.dnsType
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import com.celzero.bravedns.util.UIUpdateInterface
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.DNSCryptEndpointViewModel
import com.celzero.bravedns.viewmodel.DNSCryptRelayEndpointViewModel
import com.celzero.bravedns.viewmodel.DNSProxyEndpointViewModel
import com.celzero.bravedns.viewmodel.DoHEndpointViewModel
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import settings.Settings
import java.net.MalformedURLException
import java.net.URL


class ConfigureDNSFragment : Fragment(), UIUpdateInterface {
    private lateinit var dohRelativeLayout: RelativeLayout
    private lateinit var dnsCryptRelativeLayout: RelativeLayout
    private lateinit var dnsProxyRelativeLayout: RelativeLayout

    private lateinit var dnsModeSpinner: Spinner

    private lateinit var progressBar: ProgressBar

    private lateinit var connectedTitle: TextView
    private lateinit var connectedURL : TextView
    private lateinit var latencyTxt: TextView
    private lateinit var lifeTimeQueriesTxt: TextView

    private lateinit var dohCustomAddFabBtn: ExtendedFloatingActionButton

    //DOH UI elements
    private var dohRecyclerView: RecyclerView? = null
    private var layoutManager: RecyclerView.LayoutManager? = null
    private var dohRecyclerAdapter: DoHEndpointAdapter? = null
    private val viewModel: DoHEndpointViewModel by viewModel()

    //DNSCrypt UI elements
    private lateinit var dnsCryptRecyclerView: RecyclerView
    private lateinit var dnsCryptRecyclerAdapter: DNSCryptEndpointAdapter
    private var dnsCryptLayoutManager: RecyclerView.LayoutManager? = null
    private val dnsCryptViewModel: DNSCryptEndpointViewModel by viewModel()

    //DNSCryptRelay UI elements
    private lateinit var dnsCryptRelayRecyclerView: RecyclerView
    private lateinit var dnsCryptRelayRecyclerAdapter: DNSCryptRelayEndpointAdapter
    private var dnsCryptRelayLayoutManager: RecyclerView.LayoutManager? = null
    private val dnsCryptRelayViewModel: DNSCryptRelayEndpointViewModel by viewModel()

    //DNS Proxy UI Elements
    private lateinit var dnsProxyRecyclerView: RecyclerView
    private lateinit var dnsProxyRecyclerAdapter: DNSProxyEndpointAdapter
    private var dnsProxyLayoutManager: RecyclerView.LayoutManager? = null
    private val dnsProxyViewModel: DNSProxyEndpointViewModel by viewModel()
    private lateinit var noProxyText: TextView

    private lateinit var spinnerAdapter : CustomSpinnerAdapter

    private val appInfoRepository by inject<AppInfoRepository>()
    private val dohEndpointRepository by inject<DoHEndpointRepository>()
    private val dnsProxyEndpointRepository by inject<DNSProxyEndpointRepository>()
    private val dnsCryptEndpointRepository by inject<DNSCryptEndpointRepository>()
    private val dnsCryptRelayEndpointRepository by inject<DNSCryptRelayEndpointRepository>()
    private val doHEndpointRepository by inject<DoHEndpointRepository>()
    private val persistentState by inject<PersistentState>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        super.onCreate(savedInstanceState)
        return inflater.inflate(R.layout.fragment_configure_dns, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView(view)
        initClickListeners()
    }


    companion object {
        fun newInstance() = ConfigureDNSFragment()
    }

    private fun initView(view: View) {


        val arraySpinner = requireContext().resources.getStringArray(R.array.dns_endpoint_modes).toList()
        dnsModeSpinner = view.findViewById(R.id.configure_screen_spinner)

        spinnerAdapter = CustomSpinnerAdapter(requireContext(), arraySpinner)
        dnsModeSpinner.adapter = spinnerAdapter

        progressBar = view.findViewById(R.id.configure_dns_progress_bar)

        connectedTitle = view.findViewById(R.id.configure_connected_status_title)
        connectedURL  = view.findViewById(R.id.configure_connected_status_url)
        latencyTxt = view.findViewById(R.id.configure_latency_txt)
        lifeTimeQueriesTxt = view.findViewById(R.id.configure_total_queries_txt)

        dohCustomAddFabBtn = view.findViewById(R.id.doh_fab_add_server_icon)

        //Containers
        dohRelativeLayout = view.findViewById(R.id.recycler_doh_connections_header)
        dnsCryptRelativeLayout = view.findViewById(R.id.recycler_dns_crypt_connections_header)
        dnsProxyRelativeLayout = view.findViewById(R.id.recycler_dns_proxy_connections_header)

        progressBar.visibility = View.VISIBLE

        HomeScreenActivity.GlobalVariable.median50.observe(viewLifecycleOwner, {
            latencyTxt.setText("Latency: " + HomeScreenActivity.GlobalVariable.median50.value.toString() + "ms")
        })

        //latencyTxt.setText("Latency: " + getMedianLatency(this) + "ms")
        lifeTimeQueriesTxt.setText("Lifetime Queries: " + persistentState.getNumOfReq())

        //DOH init views
        dohRecyclerView = view.findViewById<View>(R.id.recycler_doh_connections) as RecyclerView
        layoutManager = LinearLayoutManager(requireContext())
        dohRecyclerView!!.layoutManager = layoutManager

        //DNS Crypt init views
        dnsCryptRecyclerView = view.findViewById(R.id.recycler_dns_crypt_connections)
        dnsCryptLayoutManager = LinearLayoutManager(requireContext())
        dnsCryptRecyclerView.layoutManager = dnsCryptLayoutManager

        //DNS Crypt Relay init views
        dnsCryptRelayRecyclerView = view.findViewById(R.id.recycler_dns_crypt_relays)
        dnsCryptRelayLayoutManager = LinearLayoutManager(requireContext())
        dnsCryptRelayRecyclerView.layoutManager = dnsCryptRelayLayoutManager

        //Proxy init views
        dnsProxyRecyclerView = view.findViewById(R.id.recycler_dns_proxy_connections)
        dnsProxyLayoutManager = LinearLayoutManager(requireContext())
        dnsProxyRecyclerView.layoutManager = dnsProxyLayoutManager
        noProxyText = view.findViewById(R.id.recycler_dns_proxy_title)

        dnsCryptRecyclerAdapter = DNSCryptEndpointAdapter(requireContext(), dnsCryptEndpointRepository, persistentState, get(), this)
        dnsCryptViewModel.dnsCryptEndpointList.observe(viewLifecycleOwner, androidx.lifecycle.Observer(dnsCryptRecyclerAdapter::submitList))
        dnsCryptRecyclerView.adapter = dnsCryptRecyclerAdapter

        dnsCryptRelayRecyclerAdapter = DNSCryptRelayEndpointAdapter(requireContext(), dnsCryptRelayEndpointRepository, persistentState, dnsCryptEndpointRepository)
        dnsCryptRelayViewModel.dnsCryptRelayEndpointList.observe(viewLifecycleOwner, androidx.lifecycle.Observer(dnsCryptRelayRecyclerAdapter::submitList))
        dnsCryptRelayRecyclerView.adapter = dnsCryptRelayRecyclerAdapter

        dohRecyclerAdapter = DoHEndpointAdapter(requireContext(), dohEndpointRepository,persistentState, get(), this)
        viewModel.dohEndpointList.observe(viewLifecycleOwner, androidx.lifecycle.Observer(dohRecyclerAdapter!!::submitList))
        dohRecyclerView!!.adapter = dohRecyclerAdapter

        dnsProxyRecyclerAdapter = DNSProxyEndpointAdapter(requireContext(), dnsProxyEndpointRepository, persistentState,  get(),this)
        dnsProxyViewModel.dnsProxyEndpointList.observe(viewLifecycleOwner, androidx.lifecycle.Observer(dnsProxyRecyclerAdapter::submitList))
        dnsProxyRecyclerView.adapter = dnsProxyRecyclerAdapter

        if (DEBUG) Log.d(LOG_TAG, "Notify the adapter called ???")
        progressBar.visibility = View.GONE
       val dnsValue= appMode?.getDNSType()
        if (dnsValue == 1) {
            dnsModeSpinner.setSelection(0)

            dohRelativeLayout.visibility = View.VISIBLE
            dnsCryptRelativeLayout.visibility = View.GONE
            dnsProxyRelativeLayout.visibility = View.GONE
        } else if (dnsValue == 2) {
            dnsModeSpinner.setSelection(1)
            dohRelativeLayout.visibility = View.GONE
            dnsCryptRelativeLayout.visibility = View.VISIBLE
            dnsProxyRelativeLayout.visibility = View.GONE
           /* val cryptDetails = appMode?.getDNSCryptServerCount()
            connectedTitle.text = resources.getString(R.string.configure_dns_connection_name) + "DNS crypt servers: $cryptDetails"
            connectedURL.text = resources.getString(R.string.configure_dns_connected_dns_crypt_status)*/
        } else {
            dnsModeSpinner.setSelection(2)
            dohRelativeLayout.visibility = View.GONE
            dnsCryptRelativeLayout.visibility = View.GONE
            dnsProxyRelativeLayout.visibility = View.VISIBLE
           /* val proxyDetails = appMode?.getDNSProxyServerDetails()
            connectedURL.text = resources.getString(R.string.configure_dns_connected_dns_proxy_status)
            connectedTitle.text = resources.getString(R.string.configure_dns_connection_name) + proxyDetails?.proxyName*/
        }

        val proxySize = checkProxySize()
        if (proxySize == 0) {
            noProxyText.visibility = View.VISIBLE
            dnsProxyRecyclerView.visibility = View.GONE
        }else{
            noProxyText.visibility = View.GONE
            dnsProxyRecyclerView.visibility = View.VISIBLE
        }

        dnsType.observe(viewLifecycleOwner, {
            updateUIFromAdapter(it!!)
        })

    }

    private fun getAppName(): MutableList<String> {
        return appInfoRepository.getAppNameList()
    }

    private fun initClickListeners() {

        dohCustomAddFabBtn.setOnClickListener {
            when {
                dnsModeSpinner.selectedItemPosition == 0 -> {
                    showDialogForDOHCustomURL()
                }
                dnsModeSpinner.selectedItemPosition == 1 -> {
                    showDialogForDNSCrypt()
                }
                dnsModeSpinner.selectedItemPosition == 2 -> {
                    showDialogForDNSProxy()
                }
            }
        }

        dnsModeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {

            }

            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == 0) {
                    dohRelativeLayout.visibility = View.VISIBLE
                    dnsCryptRelativeLayout.visibility = View.GONE
                    dnsProxyRelativeLayout.visibility = View.GONE
                } else if (position == 1) {
                    Log.d(LOG_TAG, "DNS Crypt on Click event")
                    dohRelativeLayout.visibility = View.GONE
                    dnsCryptRelativeLayout.visibility = View.VISIBLE
                    dnsProxyRelativeLayout.visibility = View.GONE
                } else if (position == 2) {
                    dohRelativeLayout.visibility = View.GONE
                    dnsCryptRelativeLayout.visibility = View.GONE
                    dnsProxyRelativeLayout.visibility = View.VISIBLE
                }
            }

        }

    }

    override fun onResume() {
        super.onResume()

        object : CountDownTimer(100, 500) {
            override fun onTick(millisUntilFinished: Long) {
            }

            override fun onFinish() {
                dohRecyclerAdapter?.notifyDataSetChanged()
                dnsCryptRecyclerAdapter.notifyDataSetChanged()
                dnsCryptRelayRecyclerAdapter.notifyDataSetChanged()
            }
        }.start()


        /*val dnsType = appMode?.getDNSType()
        if (dnsType == 1) {
            val dohDetail = appMode?.getDOHDetails()
            connectedURL.text = resources.getString(R.string.configure_dns_connected_doh_status)
            connectedTitle.text = resources.getString(R.string.configure_dns_connection_name) + dohDetail?.dohName
        } else if (dnsType == 2) {
            val cryptDetails = appMode?.getDNSCryptServerCount()
            connectedTitle.text = resources.getString(R.string.configure_dns_connection_name) + "DNS crypt servers: $cryptDetails"
            connectedURL.text = resources.getString(R.string.configure_dns_connected_dns_crypt_status)
        } else {
            val proxyDetails = appMode?.getDNSProxyServerDetails()
            connectedURL.text = resources.getString(R.string.configure_dns_connected_dns_proxy_status)
            connectedTitle.text = resources.getString(R.string.configure_dns_connection_name) +proxyDetails?.proxyName
        }*/
        if(DEBUG) Log.d(LOG_TAG, "onResume in fragment")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if(DEBUG) Log.d(LOG_TAG, "onActivityResult in fragment")
        super.onActivityResult(requestCode, resultCode, data)
        if(resultCode == Activity.RESULT_OK){
            val stamp = data?.getStringArrayExtra("stamp")
            if(DEBUG) Log.d(LOG_TAG, "onActivityResult - Stamp : $stamp")
        }
    }


    /**
     * Shows dialog for custom DNS endpoint configuration
     * If entered DNS end point is valid, then the DNS queries are forwarded to that end point
     * else, it will revert back to default end point
     */
    private fun showDialogForDOHCustomURL() {
        var retryAttempts = 0
        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setTitle("Custom Server URL")
        dialog.setContentView(R.layout.dialog_set_custom_url)

        val lp = WindowManager.LayoutParams()
        lp.copyFrom(dialog.window!!.attributes)
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        dialog.show()
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window!!.attributes = lp

        val applyURLBtn = dialog.findViewById(R.id.dialog_custom_url_ok_btn) as AppCompatButton
        val cancelURLBtn = dialog.findViewById(R.id.dialog_custom_url_cancel_btn) as AppCompatButton
        val customName = dialog.findViewById(R.id.dialog_custom_name_edit_text) as EditText
        val customURL: EditText = dialog.findViewById(R.id.dialog_custom_url_edit_text) as EditText
        val progressBar: ProgressBar =
            dialog.findViewById(R.id.dialog_custom_url_loading) as ProgressBar
        val errorTxt: TextView =
            dialog.findViewById(R.id.dialog_custom_url_failure_text) as TextView

        var count = dohEndpointRepository.getCount()
        count += 1

        customName.setText("DoH $count",TextView.BufferType.EDITABLE)
        applyURLBtn.setOnClickListener {
            val url = customURL.text.toString()
            val name = customName.text.toString()

            val timerHandler = Handler()
            var updater: Runnable? = null
            if (checkUrl(url)) {
                errorTxt.visibility = View.GONE
                applyURLBtn.visibility = View.INVISIBLE
                cancelURLBtn.visibility = View.INVISIBLE
                progressBar.visibility = View.VISIBLE

                var count = 0
                var connectionStatus: Boolean
                updater = Runnable {
                    kotlin.run {
                        connectionStatus = checkConnection()
                        if (connectionStatus || count >= 3) {
                            timerHandler.removeCallbacksAndMessages(updater)
                            timerHandler.removeCallbacksAndMessages(null)
                            if (connectionStatus) {
                                activity?.runOnUiThread {
                                    dialog.dismiss()
                                    Toast.makeText(context, resources.getString(R.string.custom_url_added_successfully), Toast.LENGTH_SHORT).show()
                                }
                                if (retryAttempts > 0) {
                                    if (VpnController.getInstance() != null) {
                                        VpnController.getInstance()!!.stop(requireContext())
                                        VpnController.getInstance()!!.start(requireContext())
                                    }
                                }
                                insertDoHEndpoint(name, url)

                            } else {
                                retryAttempts += 1
                                errorTxt.text = resources.getText(R.string.custom_url_error_host_failed)
                                errorTxt.visibility = View.VISIBLE
                                cancelURLBtn.visibility = View.VISIBLE
                                applyURLBtn.visibility = View.VISIBLE
                                progressBar.visibility = View.INVISIBLE
                            }
                        }
                        count++
                        if (!connectionStatus && count <= 3) {
                            timerHandler.postDelayed(updater!!, 1000)
                        }
                    }
                }
                timerHandler.postDelayed(updater, 2000)
            } else {
                errorTxt.text = resources.getString(R.string.custom_url_error_invalid_url)
                errorTxt.visibility = View.VISIBLE
                cancelURLBtn.visibility = View.VISIBLE
                applyURLBtn.visibility = View.VISIBLE
                progressBar.visibility = View.INVISIBLE
            }
        }

        cancelURLBtn.setOnClickListener {
            if (VpnController.getInstance() != null && retryAttempts != 0) {
                VpnController.getInstance()!!.stop(requireContext())
                VpnController.getInstance()!!.start(requireContext())
            }
            dialog.dismiss()
        }
        dialog.show()
    }


    private fun showDialogForDNSProxy() {
        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setTitle("Custom DNS Proxy")
        dialog.setContentView(R.layout.dialog_set_dns_proxy)

        val lp = WindowManager.LayoutParams()
        lp.copyFrom(dialog.window!!.attributes)
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        dialog.show()
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window!!.attributes = lp

       /* val radioInternal: RadioButton = dialog.findViewById(R.id.dialog_dns_proxy_radio_internal)
        val radioExternal: RadioButton = dialog.findViewById(R.id.dialog_dns_proxy_radio_external)*/
        val applyURLBtn = dialog.findViewById(R.id.dialog_dns_proxy_apply_btn) as AppCompatButton
        val cancelURLBtn = dialog.findViewById(R.id.dialog_dns_proxy_cancel_btn) as AppCompatButton
        val proxyNameEditText = dialog.findViewById(R.id.dialog_dns_proxy_edit_name) as EditText
        val ipAddressEditText: EditText = dialog.findViewById(R.id.dialog_dns_proxy_edit_ip)
        val portEditText: EditText = dialog.findViewById(R.id.dialog_dns_proxy_edit_port)
        val appNameSpinner: Spinner = dialog.findViewById(R.id.dialog_dns_proxy_spinner_appname)
        val errorTxt: TextView = dialog.findViewById(R.id.dialog_dns_proxy_error_text) as TextView
        val llSpinnerHeader: LinearLayout = dialog.findViewById(R.id.dialog_dns_proxy_spinner_header)
        val llIPHeader: LinearLayout = dialog.findViewById(R.id.dialog_dns_proxy_ip_header)

        var count  = dnsProxyEndpointRepository.getCount()
        count += 1
        proxyNameEditText.setText("Proxy $count", TextView.BufferType.EDITABLE)
        ipAddressEditText.setText("127.0.0.1",TextView.BufferType.EDITABLE)
        val appNames: MutableList<String> = ArrayList()
        appNames.add("Nobody")
        appNames.addAll(getAppName())
        val proxySpinnerAdapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, appNames
        )
        appNameSpinner.adapter = proxySpinnerAdapter
        //radioInternal.isChecked = true
        llSpinnerHeader.visibility = View.VISIBLE
        llIPHeader.visibility = View.VISIBLE

        /*radioInternal.setOnCheckedChangeListener { compoundButton: CompoundButton, b: Boolean ->
            if (b) {
                llSpinnerHeader.visibility = View.VISIBLE
                llIPHeader.visibility = View.GONE
            }
        }

        radioExternal.setOnCheckedChangeListener { compoundButton: CompoundButton, b: Boolean ->
            if (b) {
                llSpinnerHeader.visibility = View.GONE
                llIPHeader.visibility = View.VISIBLE
            }
        }*/

        applyURLBtn.setOnClickListener {
            var ip: String = ""
            var appName: String = ""
            var port: Int = 0
            var mode: String = ""
            var isValid = true
            var isIPValid = false
            val name = proxyNameEditText.text.toString()
            mode = "External"
            ip = ipAddressEditText.text.toString()

            appName = appNames[appNameSpinner.selectedItemPosition]
            if (appName.isEmpty() || appName == "Nobody") {
                appName = appNames[0]
            } else {
                appName = appInfoRepository.getPackageNameForAppName(appName)
            }


            if (Patterns.IP_ADDRESS.matcher(ip).matches()) {
                isIPValid = true
            } else {
                errorTxt.text = "Invalid IP address"
                isIPValid = false
            }

            try {
                port = portEditText.text.toString().toInt()
                if (Utilities.isIPLocal(ip)) {
                    if (port in 65535 downTo 1024) {
                        isValid = true
                    } else {
                        errorTxt.text = "Port range should be from 1024-65535"
                        isValid = false
                    }
                }else{
                    isValid = true
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Error: ${e.message}", e)
                errorTxt.setText("Invalid port")
                isValid = false
            }

            if (isValid && isIPValid) {
                //Do the DNS Proxy setting there
                if(DEBUG) Log.d(LOG_TAG, "Insert into DNSProxy")
                insertDNSProxyEndpointDB(mode, name, appName, ip, port)
                noProxyText.visibility = View.GONE
                dnsProxyRecyclerView.visibility = View.VISIBLE
                dialog.dismiss()
            } else {
                Log.i(LOG_TAG, "Insert into DNSProxy fail")
            }

        }

        cancelURLBtn.setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun insertDNSProxyEndpointDB(mode: String, name: String, appName: String, ip: String, port: Int) {
        var proxyName = name
        if (proxyName.isEmpty() || proxyName.isBlank()) {
            if (mode == "Internal") {
                proxyName = appName
            } else
                proxyName = ip
        }
        //id: Int, proxyName: String,  proxyType: String, proxyAppName: String, proxyIP: String,proxyPort : Int, isSelected: Boolean, isCustom: Boolean, modifiedDataTime: Long, latency: Int
        val dnsProxyEndpoint = DNSProxyEndpoint(-1, proxyName, mode, appName, ip, port, false, true, 0L, 0)
        dnsProxyEndpointRepository.insertAsync(dnsProxyEndpoint)
        if(DEBUG) Log.d(LOG_TAG, "Insert into DNSProxy - $appName, $port")
        object : CountDownTimer(500, 500) {
            override fun onTick(millisUntilFinished: Long) {
            }

            override fun onFinish() {
                dnsProxyRecyclerAdapter.notifyDataSetChanged()
            }
        }.start()
    }


    private fun showDialogForDNSCrypt() {
        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setTitle("Add DNSCrypt Resolver or Relay")
        dialog.setContentView(R.layout.dialog_set_dns_crypt)

        val lp = WindowManager.LayoutParams()
        lp.copyFrom(dialog.window!!.attributes)
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        dialog.show()
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window!!.attributes = lp

        val radioServer: RadioButton = dialog.findViewById(R.id.dialog_dns_crypt_radio_server)
        val radioRelay: RadioButton = dialog.findViewById(R.id.dialog_dns_crypt_radio_relay)
        val applyURLBtn = dialog.findViewById(R.id.dialog_dns_crypt_ok_btn) as AppCompatButton
        val cancelURLBtn = dialog.findViewById(R.id.dialog_dns_crypt_cancel_btn) as AppCompatButton
        val cryptNameEditText = dialog.findViewById(R.id.dialog_dns_crypt_name) as EditText
        val cryptURLEditText: EditText = dialog.findViewById(R.id.dialog_dns_crypt_url)
        val cryptDescEditText: EditText = dialog.findViewById(R.id.dialog_dns_crypt_desc)
        val errorTxt: TextView = dialog.findViewById(R.id.dialog_dns_crypt_error_txt) as TextView

        radioServer.isChecked = true
        var count = dnsCryptEndpointRepository.getCount()
        count += 1
        cryptNameEditText.setText("DNSCrypt $count", TextView.BufferType.EDITABLE)

        radioServer.setOnClickListener{
            var count = dnsCryptEndpointRepository.getCount()
            count +=1
            cryptNameEditText.setText("DNSCrypt $count",  TextView.BufferType.EDITABLE)
        }

        radioRelay.setOnClickListener{
            var count = dnsCryptRelayEndpointRepository.getCount()
            count += 1
            cryptNameEditText.setText("DNSRelay $count",  TextView.BufferType.EDITABLE)
        }

        applyURLBtn.setOnClickListener {
            var urlStamp = ""
            var desc = ""
            val isValid = true
            var mode: Int = -1
            val name: String = cryptNameEditText.text.toString()
            urlStamp = cryptURLEditText.text.toString()
            desc = cryptDescEditText.text.toString()

            if (radioServer.isChecked) {
                mode = 0
            } else if (radioRelay.isChecked) {
                mode = 1
            }

            if (isValid) {
                //Do the DNS Crypt setting there
                if (mode == 0) {
                    insertDNSCryptServer(name, urlStamp, desc)
                } else if (mode == 1) {
                    insertDNSCryptRelay(name, urlStamp, desc)
                }
                dialog.dismiss()
            }
        }

        cancelURLBtn.setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun insertDNSCryptRelay(name: String, urlStamp: String, desc: String) {
        var serverName = name
        if (serverName.isEmpty() || serverName.isBlank()) {
            serverName = urlStamp
        }
        //id: Int, dnsCryptRelayName: String, dnsCryptRelayURL: String, dnsCryptRelayExplanation: String, isSelected: Boolean, isCustom: Boolean, modifiedDataTime: Long, latency: Int
        val dnsCryptRelayEndpoint = DNSCryptRelayEndpoint(-1, serverName, urlStamp, desc, false, true, 0L, 0)
        dnsCryptRelayEndpointRepository.insertAsync(dnsCryptRelayEndpoint)
        object : CountDownTimer(500, 500) {
            override fun onTick(millisUntilFinished: Long) {
            }

            override fun onFinish() {
                dnsProxyRecyclerAdapter.notifyDataSetChanged()
            }
        }.start()
    }

    private fun insertDNSCryptServer(name: String, urlStamp: String, desc: String) {
        var serverName = name
        if (serverName.isEmpty() || serverName.isBlank()) {
            serverName = urlStamp
        }
        //id: Int, dnsCryptName: String, dnsCryptURL: String, dnsCryptExplanation: String, isSelected: Boolean, isCustom: Boolean, modifiedDataTime: Long, latency: Int
        val dnsCryptEndpoint = DNSCryptEndpoint(-1, serverName, urlStamp, desc, false, true, 0L, 0)
        dnsCryptEndpointRepository.insertAsync(dnsCryptEndpoint)
        object : CountDownTimer(500, 500) {
            override fun onTick(millisUntilFinished: Long) {
            }

            override fun onFinish() {
                dnsProxyRecyclerAdapter.notifyDataSetChanged()
            }
        }.start()
    }

    private fun checkProxySize(): Int {
        val count = dnsProxyEndpointRepository.getCount()
        return dnsProxyEndpointRepository.getCount()
    }


    private fun insertDoHEndpoint(name: String, url: String) {
        var dohName: String = name
        if (name.isEmpty() || name.isBlank()) {
            dohName = url
        }
        val doHEndpoint = DoHEndpoint(-1, dohName, url, "", false, true, 0, 0)
        doHEndpointRepository.insertAsync(doHEndpoint)
        object : CountDownTimer(500, 500) {
            override fun onTick(millisUntilFinished: Long) {
            }

            override fun onFinish() {
                dohRecyclerAdapter?.notifyDataSetChanged()
            }
        }.start()
    }

    // Check that the URL is a plausible DOH server: https with a domain, a path (at least "/"),
    // and no query parameters or fragment.
    private fun checkUrl(url: String): Boolean {
        return try {
            val parsed = URL(url)
            parsed.protocol == "https" && parsed.host.isNotEmpty() &&
                    parsed.path.isNotEmpty() && parsed.query == null && parsed.ref == null
        } catch (e: MalformedURLException) {
            false
        }
    }

    private fun checkConnection(): Boolean {
        var connectionStatus = false
        val status: VpnState? = VpnController.getInstance()!!.getState(requireContext())
        if (status!!.activationRequested) {
            if (status.connectionState == null) {
                if (appMode?.getFirewallMode() == Settings.BlockModeSink) {
                    connectionStatus = true
                }
            } else if (status.connectionState === BraveVPNService.State.WORKING) {
                connectionStatus = true
            }
        }
        return connectionStatus
    }

    override fun updateUIFromAdapter(dnsType: Int) {
        if(DEBUG) Log.d(LOG_TAG, "UI Update from adapter")
        if (dnsType == 1) {
            if(DEBUG) Log.d(LOG_TAG, "DOH has been changed, modify the connection status in the top layout")
            dnsCryptEndpointRepository.removeConnectionStatus()
            dnsCryptRelayEndpointRepository.removeConnectionStatus()
            dnsProxyEndpointRepository.removeConnectionStatus()
            dohRecyclerAdapter?.notifyDataSetChanged()
            dnsProxyRecyclerAdapter.notifyDataSetChanged()
            dnsCryptRecyclerAdapter.notifyDataSetChanged()
            dnsCryptRelayRecyclerAdapter.notifyDataSetChanged()
        } else if (dnsType == 2) {
            doHEndpointRepository.removeConnectionStatus()
            dnsProxyEndpointRepository.removeConnectionStatus()
            dnsProxyRecyclerAdapter.notifyDataSetChanged()
            dohRecyclerAdapter?.notifyDataSetChanged()
        } else if (dnsType == 3) {
            dnsProxyRecyclerView.visibility = View.VISIBLE
            doHEndpointRepository.removeConnectionStatus()
            dnsCryptEndpointRepository.removeConnectionStatus()
            dnsCryptRelayEndpointRepository.removeConnectionStatus()
            dohRecyclerAdapter?.notifyDataSetChanged()
            dnsCryptRecyclerAdapter.notifyDataSetChanged()
            dnsCryptRelayRecyclerAdapter.notifyDataSetChanged()
        } else if(dnsType == 4){
            val proxySize = checkProxySize()
            if (proxySize == 0) {
                noProxyText.visibility = View.VISIBLE
                dnsProxyRecyclerView.visibility = View.GONE
            } else {
                noProxyText.visibility = View.GONE
                dnsProxyRecyclerView.visibility = View.VISIBLE
            }
        }
        spinnerAdapter.notifyDataSetChanged()
    }

}
