/*
 * Copyright 2020 RethinkDNS and its authors
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

import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Html
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.AccelerateInterpolator
import android.view.animation.Animation
import android.widget.CompoundButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.celzero.bravedns.R
import com.celzero.bravedns.animation.Rotate3dAnimation
import com.celzero.bravedns.databinding.BottomSheetOrbotBinding
import com.celzero.bravedns.databinding.DialogInfoRulesLayoutBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import com.celzero.bravedns.util.OrbotHelper
import com.celzero.bravedns.util.Utilities
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject


/**
 * OrbotBottomSheetFragment - One touch Orbot Integration.
 * Bottom sheet dialog fragment will be shown in SettingsFragment.kt.
 * The fragment will prompt for the One touch Integration with Orbot app.
 */
class OrbotBottomSheetFragment(private val contextVal: Context) : BottomSheetDialogFragment() {
    private var _binding: BottomSheetOrbotBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val b get() = _binding!!

    private val persistentState by inject<PersistentState>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetOrbotBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }


    override fun getTheme(): Int = if (persistentState.theme == 0) {
        if (isDarkThemeOn()) {
            R.style.BottomSheetDialogTheme
        } else {
            R.style.BottomSheetDialogTheme_white
        }
    } else if (persistentState.theme == 1) {
        R.style.BottomSheetDialogTheme_white
    } else {
        R.style.BottomSheetDialogTheme
    }

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }
    //R.style.BottomSheetDialogTheme

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        initClickListeners()
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }


    private fun initView() {
        updateUI()

        //HTTP proxy support in the VPN builder is above Q.
        //The HTTP option and SOCKS5+HTTP option will not be visible for the
        //devices less than Q
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            b.bsOrbotHttpRl.visibility = View.GONE
            b.bsOrbotBothRl.visibility = View.GONE
        }
    }

    private fun initClickListeners() {

        b.bsOrbotApp.setOnClickListener {
            openOrbotApp()
        }

        /*b.bsOrbotRadioNone.setOnCheckedChangeListener{ compoundButton: CompoundButton, isSelected: Boolean ->
            if(isSelected){
                persistentState.orbotMode = Constants.ORBAT_MODE_NONE
                persistentState.orbotConnectionStatus.postValue(false)
                disableOrbot()
                showOrbotStopDialog()
            }
        }*/
        b.bsOrbotRadioNone.setOnCheckedChangeListener(null)
        b.bsOrbotRadioNone.setOnClickListener{
            if (b.bsOrbotRadioNone.isChecked) {
                HomeScreenActivity.GlobalVariable.appMode?.setProxyMode(settings.Settings.ProxyModeNone)
                persistentState.setOrbotModePersistence(Constants.ORBAT_MODE_NONE)
                persistentState.orbotEnabledMode = Constants.ORBAT_MODE_NONE
                b.bsOrbotRadioNone.isChecked = true
                persistentState.orbotEnabled = false
                persistentState.orbotConnectionStatus.postValue(false)
                disableOrbot()
                showOrbotStopDialog()
            }
        }

        b.bsOrbotNoneRl.setOnClickListener{
            if (!b.bsOrbotRadioNone.isChecked) {
                HomeScreenActivity.GlobalVariable.appMode?.setProxyMode(settings.Settings.ProxyModeNone)
                persistentState.setOrbotModePersistence(Constants.ORBAT_MODE_NONE)
                persistentState.orbotEnabledMode = Constants.ORBAT_MODE_NONE
                persistentState.orbotEnabled = false
                b.bsOrbotRadioNone.isChecked = true
                persistentState.orbotConnectionStatus.postValue(false)
                disableOrbot()
                showOrbotStopDialog()
            }
        }

        b.bsOrbotRadioSocks5.setOnCheckedChangeListener { _: CompoundButton, isSelected: Boolean ->
            if (isSelected) {
                persistentState.setOrbotModePersistence(Constants.ORBAT_MODE_SOCKS5)
                persistentState.orbotConnectionStatus.postValue(true)
                enableLoading()
                enableSOCKS5Orbot()
            }
        }

        b.bsSocks5OrbotRl.setOnClickListener {
            if (!b.bsOrbotRadioSocks5.isChecked) {
                persistentState.setOrbotModePersistence(Constants.ORBAT_MODE_SOCKS5)
                b.bsOrbotRadioSocks5.isChecked = true
                persistentState.orbotConnectionStatus.postValue(true)
                enableLoading()
                enableSOCKS5Orbot()
            }
        }

        b.bsOrbotRadioHttp.setOnCheckedChangeListener { _: CompoundButton, isSelected: Boolean ->
            if (isSelected) {
                persistentState.setOrbotModePersistence(Constants.ORBAT_MODE_HTTP)
                persistentState.orbotConnectionStatus.postValue(true)
                enableLoading()
                enableHTTPOrbot()
            }
        }

        b.bsOrbotHttpRl.setOnClickListener{
            if (!b.bsOrbotRadioHttp.isChecked) {
                persistentState.setOrbotModePersistence(Constants.ORBAT_MODE_HTTP)
                persistentState.orbotConnectionStatus.postValue(true)
                b.bsOrbotRadioHttp.isChecked = true
                enableLoading()
                enableHTTPOrbot()
            }
        }

        b.bsOrbotRadioBoth.setOnCheckedChangeListener { _: CompoundButton, isSelected: Boolean ->
            if (isSelected) {
                persistentState.setOrbotModePersistence(Constants.ORBAT_MODE_BOTH)
                persistentState.orbotConnectionStatus.postValue(true)
                enableLoading()
                enableSOCKS5HTTPOrbot()
            }
        }

        b.bsOrbotBothRl.setOnClickListener{
            if (!b.bsOrbotRadioBoth.isChecked) {
                persistentState.setOrbotModePersistence(Constants.ORBAT_MODE_BOTH)
                persistentState.orbotConnectionStatus.postValue(true)
                enableLoading()
                b.bsOrbotRadioBoth.isChecked = true
                enableSOCKS5HTTPOrbot()
            }
        }

        b.orbotInfoIcon.setOnClickListener{
            showDialogForInfo()
        }

        //Livedata value which will have the data whether the Orbot connection is initiated
        //If initiated, show loading animation.
        //else - allow user to select Orbot options
        persistentState.orbotConnectionStatus.observe(viewLifecycleOwner, {
            if (it) {
                b.orbotIcon.setImageResource(R.drawable.orbot_disabled)
                enableLoading()
            } else {
                disableLoading()
                updateUI()
            }
        })
    }

    private fun updateUI(){
        when (persistentState.getOrbotModePersistence()) {
            Constants.ORBAT_MODE_SOCKS5 -> {
                b.bsOrbotRadioSocks5.isChecked = true
                b.orbotIcon.setImageResource(R.drawable.orbot_enabled)
                b.orbotStatus.text = getString(R.string.orbot_bs_status_1)
            }
            Constants.ORBAT_MODE_HTTP -> {
                b.bsOrbotRadioHttp.isChecked = true
                b.orbotIcon.setImageResource(R.drawable.orbot_enabled)
                b.orbotStatus.text = getString(R.string.orbot_bs_status_2)
            }
            Constants.ORBAT_MODE_BOTH -> {
                b.bsOrbotRadioBoth.isChecked = true
                b.orbotIcon.setImageResource(R.drawable.orbot_enabled)
                b.orbotStatus.text = getString(R.string.orbot_bs_status_3)
            }
            Constants.ORBAT_MODE_NONE -> {
                b.bsOrbotRadioNone.isChecked = true
                b.bsOrbotRadioSocks5.isChecked = false
                b.bsOrbotRadioHttp.isChecked = false
                b.bsOrbotRadioBoth.isChecked = false
                b.orbotIcon.setImageResource(R.drawable.orbot_disabled)
                b.orbotStatus.text = getString(R.string.orbot_bs_status_4)
            }
            else -> {
                b.bsOrbotRadioNone.isChecked = true
                b.bsOrbotRadioSocks5.isChecked = false
                b.bsOrbotRadioHttp.isChecked = false
                b.bsOrbotRadioBoth.isChecked = false
                b.orbotIcon.setImageResource(R.drawable.orbot_disabled)
                b.orbotStatus.text = getString(R.string.orbot_bs_status_4)
            }
        }
    }

    /**
     * Disables the UI from selecting any other mode until the Orbot connection status is
     * obtained/time out for the Orbot.
     */
    private fun enableLoading(){
        b.bsSocks5OrbotRl.isClickable = false
        b.bsOrbotBothRl.isClickable = false
        b.bsOrbotHttpRl.isClickable = false
        b.bsOrbotNoneRl.isClickable = false
        b.bsOrbotRadioGroup.isClickable = false
        b.bsOrbotRadioSocks5.isClickable = false
        b.bsOrbotRadioHttp.isClickable = false
        b.bsOrbotRadioBoth.isClickable = false
        b.bsOrbotRadioNone.isClickable = false
        b.orbotStatus.text = getString(R.string.orbot_bs_status_trying_connect)
        animateOrbotIcon()
    }

    /**
     * Circular animation for the Orbot Icon.
     * The value is 82 is hardcoded for the Icon width.
     */
    private fun animateOrbotIcon() {
        val width = b.orbotIcon.measuredWidth
        val rotation = Rotate3dAnimation(0.0f, 360.0f * 4f, width / 2f, width / 2f, 20f, true)
        rotation.fillAfter = true
        rotation.interpolator = AccelerateInterpolator()
        rotation.duration = 2.toLong() * 1000
        rotation.repeatCount = Animation.INFINITE
        b.orbotIcon.startAnimation(rotation)
    }

    private fun disableLoading(){
        b.bsSocks5OrbotRl.isClickable = true
        b.bsOrbotBothRl.isClickable = true
        b.bsOrbotHttpRl.isClickable = true
        b.bsOrbotNoneRl.isClickable = true
        b.bsOrbotRadioGroup.isClickable = true
        b.bsOrbotRadioSocks5.isClickable = true
        b.bsOrbotRadioHttp.isClickable = true
        b.bsOrbotRadioBoth.isClickable = true
        b.bsOrbotRadioNone.isClickable = true
        b.orbotIcon.clearAnimation()
    }

    /**
     * Stop the Orbot - Calls the Orbot helper to initiate the stop orbot call.
     */
    private fun disableOrbot() {
        b.bsOrbotRadioSocks5.isChecked = false
        b.bsOrbotRadioHttp.isChecked = false
        b.bsOrbotRadioBoth.isChecked = false
        b.orbotIcon.setImageResource(R.drawable.orbot_disabled)
        val vpnService = VpnController.getInstance().getBraveVpnService()
        if (vpnService != null) {
            get<OrbotHelper>().stopOrbot(vpnService, isUserInitiated = true)
        }
    }

    /**
     * Enable - Orbot with SOCKS5 mode
     */
    private fun enableSOCKS5Orbot() {
        b.bsOrbotRadioNone.isChecked = false
        b.bsOrbotRadioHttp.isChecked = false
        b.bsOrbotRadioBoth.isChecked = false
        startOrbot()
    }

    /**
     * Enable - Orbot with HTTP mode
     */
    private fun enableHTTPOrbot() {
        b.bsOrbotRadioNone.isChecked = false
        b.bsOrbotRadioSocks5.isChecked = false
        b.bsOrbotRadioBoth.isChecked = false
        startOrbot()
    }

    /**
     * Enable - Orbot with SOCKS5 + HTTP mode
     */
    private fun enableSOCKS5HTTPOrbot(){
        b.bsOrbotRadioNone.isChecked = false
        b.bsOrbotRadioSocks5.isChecked = false
        b.bsOrbotRadioHttp.isChecked = false
        startOrbot()
    }

    /**
     * Throw intent to start the Orbot application.
     */
    private fun openOrbotApp() {
        val packageName =  OrbotHelper.ORBOT_PACKAGE_NAME
        try {
            val launchIntent: Intent? = contextVal.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {//null pointer check in case package name was not found
                startActivity(launchIntent)
            }else{
                Utilities.showToastInMidLayout(requireContext(), getString(R.string.orbot_app_issue), Toast.LENGTH_SHORT)
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.fromParts("package", packageName, null)
                startActivity(intent)
            }
        } catch (e: ActivityNotFoundException) {
            Log.w(LOG_TAG, "Exception while opening app info: ${e.message}", e)
        }
    }

    /**
     * Start the Orbot(OrbotHelper) - Intent action.
     */
    private fun startOrbot() {
        if ( get<OrbotHelper>().isOrbotInstalled()) {
            if (VpnController.getInstance() != null) {
                val vpnService = VpnController.getInstance().getBraveVpnService()
                if (vpnService != null) {
                    get<OrbotHelper>().startOrbot(vpnService)
                } else {
                    Utilities.showToastInMidLayout(requireContext(), getString(R.string.settings_socks5_vpn_disabled_error), Toast.LENGTH_LONG)
                }
            }
        }
    }

    private fun showOrbotStopDialog() {
        val builder = AlertDialog.Builder(requireContext())
        //set title for alert dialog
        builder.setTitle(getString(R.string.orbot_stop_dialog_title))
        //set message for alert dialog
        builder.setMessage(getString(R.string.orbot_stop_dialog_message))
        builder.setCancelable(true)
        //performing positive action
        builder.setPositiveButton(getString(R.string.orbot_stop_dialog_positive)) { dialogInterface, _ ->
            dialogInterface.dismiss()
        }
        builder.setNegativeButton(getString(R.string.orbot_stop_dialog_negative)) { dialogInterface: DialogInterface, _: Int ->
            dialogInterface.dismiss()
            val launchIntent: Intent? = contextVal.packageManager.getLaunchIntentForPackage(OrbotHelper.ORBOT_PACKAGE_NAME)
            if (launchIntent != null) {//null pointer check in case package name was not found
                Log.d(LOG_TAG, "launchIntent: ${OrbotHelper.ORBOT_PACKAGE_NAME}")
                startActivity(launchIntent)
            }
        }
        // Create the AlertDialog
        val alertDialog: AlertDialog = builder.create()
        // Set other dialog properties
        alertDialog.setCancelable(true)
        alertDialog.show()
    }

    private fun showDialogForInfo() {
        val dialogBinding = DialogInfoRulesLayoutBinding.inflate(layoutInflater)
        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setContentView(dialogBinding.root)
        val okBtn = dialogBinding.infoRulesDialogCancelImg
        val descText = dialogBinding.infoRulesDialogRulesDesc
        val titleText = dialogBinding.infoRulesDialogRulesTitle

        var text = getString(R.string.orbot_explanation)
        text = text.replace("\n", "<br /><br />")
        val styledText = Html.fromHtml(text)
        descText.text = styledText
        titleText.text = getString(R.string.orbot_title)
        titleText.setCompoundDrawablesRelative(null, null, null, null)

        okBtn.setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()

    }


}
