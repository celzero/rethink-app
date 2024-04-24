/*
 * Copyright 2023 RethinkDNS and its authors
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
package com.celzero.bravedns.ui.bottomsheet

import Logger
import Logger.LOG_TAG_FIREWALL
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.celzero.bravedns.R
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.database.RethinkLog
import com.celzero.bravedns.databinding.BottomSheetConnTrackBinding
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.activity.AppInfoActivity
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Protocol
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.fetchColor
import com.celzero.bravedns.util.UIUtils.updateHtmlEncodedText
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.getIcon
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.core.component.KoinComponent

class RethinkLogBottomSheet : BottomSheetDialogFragment(), KoinComponent {

    private var _binding: BottomSheetConnTrackBinding? = null

    // This property is only valid between onCreateView and onDestroyView.
    private val b
        get() = _binding!!

    private var info: RethinkLog? = null

    companion object {
        const val INSTANCE_STATE_IPDETAILS = "IPDETAILS"
        const val DNS_IP_TEMPLATE_V4 = "10.111.222.3"
        const val DNS_IP_TEMPLATE_V6 = "fd66:f83a:c650::3"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetConnTrackBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun getTheme(): Int =
        Themes.getBottomsheetCurrentTheme(isDarkThemeOn(), persistentState.theme)

    private val persistentState by inject<PersistentState>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val data = arguments?.getString(INSTANCE_STATE_IPDETAILS)
        info = Gson().fromJson(data, RethinkLog::class.java)
        initView()
    }

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun initView() {
        if (info == null) {
            Logger.w(LOG_TAG_FIREWALL, "ip-details missing: initView called before onViewCreated?")
            this.dismiss()
            return
        }

        if (info!!.ipAddress == DNS_IP_TEMPLATE_V4 || info!!.ipAddress == DNS_IP_TEMPLATE_V6) {
            val dnsTxt =
                getString(
                    R.string.about_version_install_source,
                    getString(R.string.dns_mode_info_title),
                    info!!.ipAddress
                )
            b.bsConnConnectionTypeHeading.text = dnsTxt
        } else {
            b.bsConnConnectionTypeHeading.text = info!!.ipAddress
        }
        b.bsConnConnectionFlag.text = info!!.flag

        b.bsConnBlockAppTxt.text = updateHtmlEncodedText(getString(R.string.bsct_block))
        b.bsConnBlockConnAllTxt.text = updateHtmlEncodedText(getString(R.string.bsct_block_ip))
        b.bsConnDomainTxt.text = updateHtmlEncodedText(getString(R.string.bsct_block_domain))

        // updates the application name and other details
        updateAppDetails()
        // updates the connection detail chip
        updateConnDetailsChip()
        // updates the blocked rules chip
        updateBlockedRulesChip()
        // assigns color for the blocked rules chip
        lightenUpChip()
        // updates the summary details
        displaySummaryDetails()
        // setup click and item selected listeners
        setupClickListeners()
        // updates the value from dns request cache if available
        updateDnsIfAvailable()
        // if the app is Rethink, then hide the firewall rules spinner
        handleRethinkApp()
    }

    override fun onResume() {
        super.onResume()
        if (info == null) {
            Logger.w(LOG_TAG_FIREWALL, "ip-details missing: initView called before onViewCreated?")
            this.dismiss()
            return
        }
    }

    private fun handleRethinkApp() {
        io {
            val pkgName = FirewallManager.getPackageNameByUid(info!!.uid)
            uiCtx {
                if (pkgName == requireContext().packageName) {
                    b.bsConnBlockedRule1HeaderLl.visibility = View.GONE
                    b.bsConnBlockedRule2HeaderLl.visibility = View.GONE
                    b.bsConnBlockedRule3HeaderLl.visibility = View.GONE
                    b.bsConnDomainRuleLl.visibility = View.GONE
                }
            }
        }
    }

    private fun updateDnsIfAvailable() {
        val domain = info?.dnsQuery
        val uid = info?.uid
        val flag = info?.flag

        if (domain.isNullOrEmpty() || uid == null) {
            b.bsConnDnsCacheText.visibility = View.VISIBLE
            b.bsConnDnsCacheText.text = UIUtils.getCountryNameFromFlag(flag)
            b.bsConnDomainRuleLl.visibility = View.GONE
            return
        }

        val status = DomainRulesManager.getDomainRule(domain, uid)
        b.bsConnDomainSpinner.setSelection(status.id)
        b.bsConnDnsCacheText.visibility = View.VISIBLE
        b.bsConnDnsCacheText.text =
            requireContext()
                .getString(R.string.two_argument, UIUtils.getCountryNameFromFlag(flag), domain)
    }

    private fun updateConnDetailsChip() {
        if (info == null) {
            Logger.w(LOG_TAG_FIREWALL, "ip-details missing: not updating the chip details")
            return
        }

        val protocol = Protocol.getProtocolName(info!!.protocol).name
        val time =
            DateUtils.getRelativeTimeSpanString(
                info!!.timeStamp,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            )
        val protocolDetails = "$protocol/${info!!.port}"
        if (info!!.isBlocked) {
            b.bsConnTrackPortDetailChip.text =
                getString(R.string.bsct_conn_desc_blocked, protocolDetails, time)
            return
        }

        b.bsConnTrackPortDetailChip.text =
            getString(R.string.bsct_conn_desc_allowed, protocolDetails, time)
    }

    private fun updateBlockedRulesChip() {
        if (!info!!.isBlocked) {
            b.bsConnTrackAppInfo.text = getString(R.string.firewall_rule_no_rule)
            return
        }
    }

    private fun updateAppDetails() {
        if (info == null) return
        io {
            val appNames = FirewallManager.getAppNamesByUid(info!!.uid)
            val pkgName = FirewallManager.getPackageNameByAppName(appNames[0])
            uiCtx {
                val appCount = appNames.count()
                if (appCount >= 1) {
                    b.bsConnBlockedRule2HeaderLl.visibility = View.GONE
                    b.bsConnTrackAppName.text =
                        if (appCount >= 2) {
                            getString(
                                R.string.ctbs_app_other_apps,
                                appNames[0],
                                appCount.minus(1).toString()
                            ) + "      ❯"
                        } else {
                            appNames[0] + "      ❯"
                        }
                    if (pkgName == null) return@uiCtx
                    b.bsConnTrackAppIcon.setImageDrawable(
                        getIcon(requireContext(), pkgName, info?.appName)
                    )
                } else {
                    // apps which are not available in cache are treated as non app.
                    // TODO: check packageManager#getApplicationInfo() for appInfo
                    handleNonApp()
                }
            }
        }
    }

    private fun displaySummaryDetails() {
        b.bsConnConnTypeSecondary.visibility = View.GONE
        b.connectionMessage.text = info?.message

        if (VpnController.hasCid(info!!.connId, info!!.uid)) {
            b.connectionMessageLl.visibility = View.VISIBLE
            b.bsConnConnDuration.text =
                getString(
                    R.string.two_argument_space,
                    getString(R.string.lbl_active),
                    getString(R.string.symbol_green_circle)
                )
        } else {
            b.bsConnConnDuration.text =
                getString(
                    R.string.two_argument_space,
                    getString(R.string.symbol_hyphen),
                    getString(R.string.symbol_clock)
                )
        }

        val connType = ConnectionTracker.ConnType.get(info?.connType)
        if (connType.isMetered()) {
            b.bsConnConnType.text =
                getString(
                    R.string.two_argument_space,
                    getString(R.string.ada_app_metered),
                    getString(R.string.symbol_currency)
                )
        } else {
            b.bsConnConnType.text =
                getString(
                    R.string.two_argument_space,
                    getString(R.string.ada_app_unmetered),
                    getString(R.string.symbol_global)
                )
        }

        if (
            info?.message?.isEmpty() == true &&
                info?.duration == 0 &&
                info?.downloadBytes == 0L &&
                info?.uploadBytes == 0L
        ) {
            b.connectionMessageLl.visibility = View.GONE
            b.bsConnSummaryDetailLl.visibility = View.GONE
            b.bsConnConnTypeSecondary.visibility = View.VISIBLE
            b.bsConnConnTypeSecondary.text = b.bsConnConnType.text
            return
        }

        b.connectionMessageLl.visibility = View.VISIBLE
        val downloadBytes =
            getString(
                R.string.symbol_download,
                Utilities.humanReadableByteCount(info?.downloadBytes ?: 0L, true)
            )
        val uploadBytes =
            getString(
                R.string.symbol_upload,
                Utilities.humanReadableByteCount(info?.uploadBytes ?: 0L, true)
            )

        b.bsConnConnUpload.text = uploadBytes
        b.bsConnConnDownload.text = downloadBytes
        val duration = UIUtils.getDurationInHumanReadableFormat(requireContext(), info!!.duration)
        b.bsConnConnDuration.text =
            getString(R.string.two_argument_space, duration, getString(R.string.symbol_clock))
    }

    private fun lightenUpChip() {
        // Load icons for the firewall rules if available
        b.bsConnTrackAppInfo.chipIcon =
            ContextCompat.getDrawable(requireContext(), R.drawable.ic_whats_new)
        if (info!!.isBlocked) {
            b.bsConnTrackAppInfo.setTextColor(fetchColor(requireContext(), R.attr.chipTextNegative))
            val colorFilter =
                PorterDuffColorFilter(
                    fetchColor(requireContext(), R.attr.chipTextNegative),
                    PorterDuff.Mode.SRC_IN
                )
            b.bsConnTrackAppInfo.chipBackgroundColor =
                ColorStateList.valueOf(fetchColor(requireContext(), R.attr.chipBgColorNegative))
            b.bsConnTrackAppInfo.chipIcon?.colorFilter = colorFilter
        } else {
            b.bsConnTrackAppInfo.setTextColor(fetchColor(requireContext(), R.attr.chipTextPositive))
            val colorFilter =
                PorterDuffColorFilter(
                    fetchColor(requireContext(), R.attr.chipTextPositive),
                    PorterDuff.Mode.SRC_IN
                )
            b.bsConnTrackAppInfo.chipBackgroundColor =
                ColorStateList.valueOf(fetchColor(requireContext(), R.attr.chipBgColorPositive))
            b.bsConnTrackAppInfo.chipIcon?.colorFilter = colorFilter
        }
    }

    private fun handleNonApp() {
        // show universal setting layout
        b.bsConnBlockedRule2HeaderLl.visibility = View.VISIBLE
        // hide the app firewall layout
        b.bsConnBlockedRule1HeaderLl.visibility = View.GONE
        b.bsConnUnknownAppCheck.isChecked = persistentState.getBlockUnknownConnections()
        b.bsConnTrackAppName.text = info!!.appName
    }

    private fun setupClickListeners() {

        b.bsConnTrackAppNameHeader.setOnClickListener {
            io {
                val ai = FirewallManager.getAppInfoByUid(info!!.uid)
                uiCtx {
                    // case: app is uninstalled but still available in RethinkDNS database
                    if (ai == null || info?.uid == Constants.INVALID_UID) {
                        showToastUiCentered(
                            requireContext(),
                            getString(R.string.ct_bs_app_info_error),
                            Toast.LENGTH_SHORT
                        )
                        return@uiCtx
                    }
                    openAppDetailActivity(info!!.uid)
                }
            }
        }
    }

    private fun openAppDetailActivity(uid: Int) {
        this.dismiss()
        val intent = Intent(requireContext(), AppInfoActivity::class.java)
        intent.putExtra(AppInfoActivity.UID_INTENT_NAME, uid)
        requireContext().startActivity(intent)
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }
}
