package com.celzero.bravedns.ui

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatSpinner
import androidx.fragment.app.Fragment
import com.celzero.bravedns.R
import com.celzero.bravedns.service.PersistantState


class SettingsFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {

        val view: View = inflater!!.inflate(R.layout.fragment_settings,container,false)
        initView(view)
        return view
    }

    private fun initView(view : View){
        val urlName = resources.getStringArray(R.array.adguard_name)
        val urlValues = resources.getStringArray(R.array.adguard_url)
        var urlSpinner = view.findViewById<AppCompatSpinner>(R.id.setting_url_spinner)
        var appModeRadioGroup = view.findViewById<RadioGroup>(R.id.setting_app_mode_radio_group)

        val btnApply = view.findViewById<AppCompatButton>(R.id.setting_apply)

        if (urlSpinner != null) {
            val adapter = ArrayAdapter(context!!,
                android.R.layout.simple_spinner_dropdown_item, urlName)
            urlSpinner.adapter = adapter
            Log.w("BraveDNS","Persistance URL: "+ PersistantState.getServerUrl(context!!))
            val url = PersistantState.getServerUrl(context!!)
            if (url != null) {
                //val spinnerPosition = adapter.getPosition(url)
                urlSpinner.setSelection(getIndex(urlSpinner, url))
            }

            urlSpinner.onItemSelectedListener = object :
                AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>,
                                            view: View, position: Int, id: Long) {
                    PersistantState.setServerUrl(context, urlValues[position])
                }

                override fun onNothingSelected(parent: AdapterView<*>) {
                    // write code to perform some action
                    PersistantState.setServerUrl(context, urlValues[0])
                }
            }
        }
        Log.w("BraveDNS","Persistance mode: "+ PersistantState.getDnsMode(context!!))
        if(PersistantState.getDnsMode(context!!) == 0)
            appModeRadioGroup.check(R.id.radio_button_1)
        else if(PersistantState.getDnsMode(context!!) == 1)
            appModeRadioGroup.check(R.id.radio_button_2)
        else
            appModeRadioGroup.check(R.id.radio_button_3)

        /*appModeRadioGroup.setOnCheckedChangeListener { group, checkedId ->
            val radio: RadioButton = view.findViewById(checkedId)
            Toast.makeText(context," On checked change : ${radio.text}",
                Toast.LENGTH_SHORT).show()
        }*/

        // Get radio group selected status and text using button click event
        btnApply.setOnClickListener{
            // Get the checked radio button id from radio group
            val id: Int = appModeRadioGroup.checkedRadioButtonId
            if (id!=-1){ // If any radio button checked from radio group
                // Get the instance of radio button using id
                val radio:RadioButton = view.findViewById(id)
                when (radio.text) {
                    getString(R.string.mode_dns) -> {
                        PersistantState.setDnsMode(context!!,1)
                        PersistantState.setFirewallMode(context!!, 0)
                    }
                    getString(R.string.mode_dns_firewall) -> {
                        PersistantState.setDnsMode(context!!,1)
                        PersistantState.setFirewallMode(context!!, 1)
                    }
                    else -> {
                        PersistantState.setDnsMode(context!!,1)
                        PersistantState.setFirewallMode(context!!, 2)
                    }
                }
                Toast.makeText(context,"Values Updated!",
                    Toast.LENGTH_SHORT).show()
            }else{
                // If no radio button checked in this radio group
                Toast.makeText(context,"Please select a mode",
                    Toast.LENGTH_SHORT).show()
            }
        }


    }

    private fun getIndex(spinner: Spinner, myString: String): Int {
        //val urlName = resources.getStringArray(R.array.adguard_name)
        val urlValues = resources.getStringArray(R.array.adguard_url)

        for(i in urlValues.indices){
            Log.w("BraveDNS","Persistance URL: "+ i + "---"+myString)
            if(urlValues[i] == myString)
                return i
        }/*
        for (i in 0 until spinner.count) {
            Log.w("BraveDNS","Persistance URL: "+ spinner.getItemAtPosition(i).toString())
            if (spinner.getItemAtPosition(i).toString() == myString) {
                Log.w("BraveDNS","Persistance URL: "+ spinner.getItemAtPosition(i).toString())
                return i
            }
        }*/
        // Check for this when you set the position.
        return -1
    }


}