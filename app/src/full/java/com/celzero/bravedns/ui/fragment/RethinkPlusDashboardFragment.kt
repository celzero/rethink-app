/*
 * Copyright 2025 RethinkDNS and its authors
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
package com.celzero.bravedns.ui.fragment

import Logger
import Logger.LOG_TAG_UI
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import android.widget.CompoundButton
import android.widget.Toast
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.WgIncludeAppsAdapter
import com.celzero.bravedns.databinding.ActivityRethinkPlusDashboardBinding
import com.celzero.bravedns.databinding.DialogInfoRulesLayoutBinding
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.scheduler.BugReportZipper.FILE_PROVIDER_NAME
import com.celzero.bravedns.scheduler.BugReportZipper.getZipFileName
import com.celzero.bravedns.scheduler.EnhancedBugReport
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.subscription.SubscriptionStateMachineV2
import com.celzero.bravedns.ui.activity.FragmentHostActivity
import com.celzero.bravedns.ui.activity.PingTestActivity
import com.celzero.bravedns.ui.activity.RpnWinProxyDetailsActivity
import com.celzero.bravedns.ui.dialog.SubscriptionAnimDialog
import com.celzero.bravedns.ui.dialog.WgIncludeAppsDialog
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.fetchColor
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.celzero.bravedns.viewmodel.ProxyAppsMappingViewModel
import com.celzero.firestack.backend.Backend
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.jvm.java

class RethinkPlusDashboardFragment : Fragment(R.layout.activity_rethink_plus_dashboard) {
    private val b by viewBinding(ActivityRethinkPlusDashboardBinding::bind)

    private val persistentState by inject<PersistentState>()
    private val mappingViewModel: ProxyAppsMappingViewModel by viewModel()

    private lateinit var animation: Animation

    private lateinit var options: List<String>

    companion object {
        private const val ANIMATION_DURATION = 750L
        private const val ANIMATION_REPEAT_COUNT = -1
        private const val ANIMATION_PIVOT_VALUE = 0.5f
        private const val ANIMATION_START_DEGREE = 0.0f
        private const val ANIMATION_END_DEGREE = 360.0f

        private const val TAG = "RPNDashAct"

        private const val DELAY = 1500L
        private const val GRACE_DIALOG_REMIND_AFTER_DAYS = 1 // days to remind again
    }

    private var warpProps: RpnProxyManager.RpnProps? = null
    private var seProps: RpnProxyManager.RpnProps? = null
    private var exit64Props: RpnProxyManager.RpnProps? = null
    private var amzProps: RpnProxyManager.RpnProps? = null
    private var winProps: RpnProxyManager.RpnProps? = null

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (!isAdded) {
            findNavController().navigate(R.id.action_switch_to_homeScreenFragment)
            return
        }

        if (!RpnProxyManager.hasValidSubscription()) {
            Logger.w(LOG_TAG_UI, "$TAG no valid subscription found, navigating to rethinkplus")
            findNavController().navigate(R.id.rethinkPlus)
            showToastUiCentered(requireContext(), "No valid subscription found", Toast.LENGTH_SHORT)
        }

        // drop the last element as the last one is exit which is not used in the UI
        options = listOf("WIN-US", "WIN-UK", "WIN-IN", "WIN-DE", "WIN-CA")
        initView()
        setupClickListeners()
    }

    private fun initView() {
        showConfettiEffectIfNeeded()
        addAnimation()
        handleRpnMode()
        addProxiesToUi()
        observeSubscriptionState()
    }

    private fun showConfettiEffectIfNeeded() {
        if (!persistentState.showConfettiOnRPlus) return

        SubscriptionAnimDialog().show(childFragmentManager, "SubscriptionAnimDialog")
        persistentState.showConfettiOnRPlus = false
    }

    private fun handleRpnMode() {
        val mode = RpnProxyManager.rpnMode()
        when (mode) {
            RpnProxyManager.RpnMode.ANTI_CENSORSHIP -> {
                b.rsAntiCensorshipRadio.isChecked = true
                b.rsHideIpRadio.isChecked = false
                b.rsOffRadio.isChecked = false
            }

            RpnProxyManager.RpnMode.HIDE_IP -> {
                b.rsAntiCensorshipRadio.isChecked = false
                b.rsHideIpRadio.isChecked = true
                b.rsOffRadio.isChecked = false
            }

            RpnProxyManager.RpnMode.NONE -> {
                b.rsAntiCensorshipRadio.isChecked = false
                b.rsHideIpRadio.isChecked = false
                b.rsOffRadio.isChecked = true
            }
        }
    }

    private fun observeSubscriptionState() {

        /*InAppBillingHandler.purchasesLiveData.distinctUntilChanged().observe(viewLifecycleOwner) { purchaseDetails ->
            Logger.v(LOG_TAG_UI, "$TAG subscription state changed: $purchaseDetails")
            if (purchaseDetails == null) {
                Logger.w(LOG_TAG_UI, "$TAG subscription state is null")
                return@observe
            }
            if (purchaseDetails.isEmpty()) {
                // see if there is a grace period if so highlight the grace period
                val state = RpnProxyManager.getSubscriptionState()
                Logger.i(LOG_TAG_UI, "$TAG no purchases found, checking subscription state: ${state?.name}, hasValidSubscription: ${state?.hasValidSubscription}")
                if (state != null && state.hasValidSubscription) {
                    // showGracePeriodUi(state)
                    showToastUiCentered(requireContext(), "Subscription is in grace period", Toast.LENGTH_SHORT)
                } else {
                    // navigate to rethinkplus subscription screen
                    findNavController().navigate(R.id.rethinkPlus)
                    showToastUiCentered(requireContext(), "No active subscription found", Toast.LENGTH_SHORT)
                }
            }
        }*/

        io {
            RpnProxyManager.collectSubscriptionState().collect {
                uiCtx { handleStateChange(it) }
            }
        }
    }

    private fun handleStateChange(state: SubscriptionStateMachineV2.SubscriptionState) {
        when (state) {
            SubscriptionStateMachineV2.SubscriptionState.Active -> {
                Logger.i(LOG_TAG_UI, "$TAG subscription state changed to ACTIVE")
                //showToastUiCentered(requireContext(), "Subscription is active", Toast.LENGTH_SHORT)
                reinitiateProxiesUi()
                if (b.renewButton.isVisible) {
                    b.renewButton.visibility = View.GONE
                }
            }
            SubscriptionStateMachineV2.SubscriptionState.Initial -> {
                Logger.i(LOG_TAG_UI, "$TAG subscription state changed to INACTIVE")
                // navigate to rethinkplus subscription screen
                //findNavController().navigate(R.id.rethinkPlus)
                showToastUiCentered(
                    requireContext(),
                    "No active subscription found",
                    Toast.LENGTH_SHORT
                )
            }
            SubscriptionStateMachineV2.SubscriptionState.Cancelled -> {
                Logger.i(LOG_TAG_UI, "$TAG subscription state changed to CANCELLED")
                //showToastUiCentered(requireContext(), "Subscription is cancelled", Toast.LENGTH_SHORT)
                // show grace period dialog
                showGracePeriodDialog()
                b.renewButton.visibility = View.VISIBLE
            }
            SubscriptionStateMachineV2.SubscriptionState.Revoked -> {
                Logger.i(LOG_TAG_UI, "$TAG subscription state changed to REVOKED")
                //showToastUiCentered(requireContext(), "Subscription is revoked", Toast.LENGTH_SHORT)
                // show grace period dialog
                showGracePeriodDialog()
                b.renewButton.visibility = View.VISIBLE
            }
            else -> {
                Logger.i(LOG_TAG_UI, "$TAG subscription state changed to UNKNOWN: $state")
                showToastUiCentered(requireContext(), "Invalid subscription state: $state", Toast.LENGTH_SHORT)
            }
        }
    }

    private fun showGracePeriodDialog() {
        val now = System.currentTimeMillis()

        val lastShown = persistentState.lastGracePeriodReminderTime
        val daysSinceLastShown = TimeUnit.MILLISECONDS.toDays(now - lastShown)
        if (daysSinceLastShown < GRACE_DIALOG_REMIND_AFTER_DAYS) return
        Logger.d(
            LOG_TAG_UI,
            "$TAG Grace period dialog last shown $daysSinceLastShown days ago"
        )
        io {
            val currentSubs = RpnProxyManager.getSubscriptionData()

            if (currentSubs == null) {
                Logger.v(
                    LOG_TAG_UI,
                    "$TAG No active subscription found, skipping grace period dialog"
                )
                return@io
            }

            // grace period is calculated based on billingExpiry and accountExpiry
            val billingExpiry = currentSubs.subscriptionStatus.billingExpiry
            val accountExpiry = currentSubs.subscriptionStatus.accountExpiry
            val gracePeriod = accountExpiry - billingExpiry
            val gracePeriodDays = TimeUnit.MILLISECONDS.toDays(gracePeriod)
            if (gracePeriodDays <= 0L) {
                Logger.v(
                    LOG_TAG_UI,
                    "$TAG No grace period available($gracePeriod), skipping grace period dialog"
                )
                return@io
            }
            val timeLeft = accountExpiry.minus(now)
            val timeLeftDays = TimeUnit.MILLISECONDS.toDays(timeLeft)
            if (timeLeftDays <= 0L) {
                Logger.i(
                    LOG_TAG_UI,
                    "$$TAG Grace period has ended(@$timeLeft), skipping grace period dialog"
                )
                return@io
            }

            val daysRemaining = TimeUnit.MILLISECONDS.toDays(timeLeft).toInt().coerceAtLeast(1)
            if (daysRemaining <= 0) {
                Logger.v(
                    LOG_TAG_UI,
                    "$$TAG No days remaining in grace period, skipping dialog"
                )
                return@io
            }
            Logger.v(
                LOG_TAG_UI,
                "$$TAG Showing grace period dialog, $daysRemaining days remaining"
            )
            uiCtx {
                val dialogView = LayoutInflater.from(requireContext())
                    .inflate(R.layout.dialog_grace_period_layout, null)

                dialogView.findViewById<AppCompatTextView>(R.id.dialog_days_left).text =
                    "\u23F3 $daysRemaining days remaining"

                dialogView.findViewById<LinearProgressIndicator>(R.id.dialog_progress).apply {
                    max = 100

                    // should be decreased from 100 to 0
                    progress = 100 - (timeLeftDays * 100 / gracePeriodDays).toInt()
                    if (progress < 0) 0 else progress
                    Logger.v(LOG_TAG_UI, "$$TAG Grace period progress: $progress%")
                }

                val dialog = MaterialAlertDialogBuilder(requireContext())
                    .setView(dialogView)
                    .setCancelable(false)
                    .create()

                dialogView.findViewById<AppCompatButton>(R.id.button_renew).setOnClickListener {
                    dialog.dismiss()
                    findNavController().navigate(R.id.rethinkPlus)
                }

                dialogView.findViewById<AppCompatButton>(R.id.button_later).setOnClickListener {
                    dialog.dismiss()
                    persistentState.lastGracePeriodReminderTime = System.currentTimeMillis()
                }
                persistentState.lastGracePeriodReminderTime = System.currentTimeMillis()
                dialog.show()
            }
        }
    }

    private fun addProxiesToUi() {
        if (!isAdded || view == null) return

        val mode = RpnProxyManager.rpnMode()
        ui {
            for (option in options) {
                val rowView =
                    layoutInflater.inflate(
                        R.layout.item_rpn_proxy_dashboard_stats,
                        b.proxyContainer,
                        false
                    )
                val iv = rowView.findViewById<AppCompatImageView>(R.id.proxy_icon)
                val title = rowView.findViewById<AppCompatTextView>(R.id.proxy_title)
                // treat refreshTv as a button
                val refreshBtn = rowView.findViewById<AppCompatButton>(R.id.refresh_button)
                val lastRefreshChip = rowView.findViewById<Chip>(R.id.proxy_last_checked)
                val infoBtn = rowView.findViewById<AppCompatButton>(R.id.info_button)
                val statusTv = rowView.findViewById<AppCompatTextView>(R.id.proxy_status)
                val whoTv = rowView.findViewById<AppCompatTextView>(R.id.proxy_latency)
                val addAppsBtn = rowView.findViewById<AppCompatButton>(R.id.use_button)
                val errorTv = rowView.findViewById<AppCompatTextView>(R.id.error_message)

                b.proxyContainer.addView(rowView)
                val id = options.indexOf(option)
                updateProxiesUi(
                    id,
                    iv,
                    title,
                    infoBtn,
                    statusTv,
                    errorTv,
                    lastRefreshChip,
                    whoTv,
                    addAppsBtn
                )

                /*val type = getType(id)
                if (type == null) {
                    Logger.w(LOG_TAG_UI, "$TAG type is null")
                    return@ui
                }*/

                statusTv.setOnClickListener {
                    // TODO: the click listener should be inside updateProxiesUi, after fetching the
                    // props it will decide to launch or not
                    launchRpnWinProxyDetailsActivity("US")
                }
                val type = getType(id)
                infoBtn.setOnClickListener {
                    if (type == null) {
                        Logger.w(LOG_TAG_UI, "$TAG type is null")
                        return@setOnClickListener
                    }
                    io {
                        var res: Pair<RpnProxyManager.RpnProps?, String?>? = null
                        ioCtx {
                            res = VpnController.getRpnProps(type)
                        }

                        val props = res?.first ?: return@io

                        uiCtx {  showInfoDialog(type, props) }
                    }
                }

                addAppsBtn.setOnClickListener {
                    Logger.vv(LOG_TAG_UI, "$TAG addAppsBtn clicked for $type")
                    val themeId = Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme)
                    val proxyId = id.toString()
                    val proxyName = "Win-US"
                    val appsAdapter = WgIncludeAppsAdapter(requireContext(), this, proxyId, proxyName)
                    mappingViewModel.apps.observe(viewLifecycleOwner) {
                        appsAdapter.submitData(
                            lifecycle,
                            it
                        )
                    }
                    val includeAppsDialog =
                        WgIncludeAppsDialog(
                            this.requireActivity(),
                            appsAdapter,
                            mappingViewModel,
                            themeId,
                            proxyId,
                            proxyName
                        )
                    includeAppsDialog.setCanceledOnTouchOutside(false)
                    includeAppsDialog.show()
                }


            }
            // do not update the UI if RPN is off
            if (mode.isNone()) {
                return@ui
            }

            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                // your repeating logic
                keepUpdatingProxiesUi()
            }
        }
    }

    private fun canRefresh(type: RpnProxyManager.RpnType): Boolean {
        val proxyDetail = RpnProxyManager.getProxy(type)
        if (proxyDetail == null) {
            Logger.w(LOG_TAG_UI, "$TAG proxy detail is null")
            return true
        }
        val lastRefreshTime = proxyDetail.lastRefreshTime
        val currentTime = System.currentTimeMillis()
        val diff = currentTime - lastRefreshTime
        val diffInHours = diff / (1000 * 60 * 60)
        if (diffInHours < 24) {
            Logger.w(LOG_TAG_UI, "$TAG refresh can be done only once in 24 hours")
            return false
        }
        return true
    }

    private fun launchRpnWinProxyDetailsActivity(countryCode: String? = "US") {
        val intent = Intent(requireContext(), RpnWinProxyDetailsActivity::class.java)
        intent.putExtra(RpnWinProxyDetailsActivity.COUNTRY_CODE, countryCode)
        startActivity(intent)
    }

    private fun reinitiateProxiesUi() {
        ui {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                // your repeating logic
                keepUpdatingProxiesUi()
            }
        }
    }

    private suspend fun keepUpdatingProxiesUi() {
        // keep updating the UI every 1.5 seconds
        while (true) {
            for (i in 0 until options.size) {
                val iv = b.proxyContainer.getChildAt(i).findViewById<AppCompatImageView>(R.id.proxy_icon)
                val title = b.proxyContainer.getChildAt(i).findViewById<AppCompatTextView>(R.id.proxy_title)
                val infoBtn = b.proxyContainer.getChildAt(i).findViewById<AppCompatButton>(R.id.info_button)
                val statusTv = b.proxyContainer.getChildAt(i).findViewById<AppCompatTextView>(R.id.proxy_status)
                val whoTv = b.proxyContainer.getChildAt(i).findViewById<AppCompatTextView>(R.id.proxy_latency)
                val lastRefreshChip = b.proxyContainer.getChildAt(i).findViewById<Chip>(R.id.proxy_last_checked)
                val addAppsBtn = b.proxyContainer.getChildAt(i).findViewById<AppCompatButton>(R.id.use_button)
                val errorTv = b.proxyContainer.getChildAt(i).findViewById<AppCompatTextView>(R.id.error_message)

                updateProxiesUi(i, iv, title, infoBtn, statusTv, errorTv, lastRefreshChip, whoTv, addAppsBtn)
            }
            Logger.v(LOG_TAG_UI, "$TAG updating proxies UI every $DELAY ms")
            delay(DELAY)
            if (!isAdded) {
                Logger.v(LOG_TAG_UI, "$TAG activity is finishing, stopping update")
                break
            }
            if (!RpnProxyManager.isRpnEnabled()) {
                Logger.v(LOG_TAG_UI, "$TAG RPN is not active, stopping update")
                break
            }
        }
    }

    private suspend fun updateProxiesUi(
        id: Int,
        iv: AppCompatImageView,
        title: AppCompatTextView,
        infoBtn: AppCompatButton,
        statusTv: AppCompatTextView,
        errorTv: AppCompatTextView,
        lastRefreshChip: Chip,
        whoTv: AppCompatTextView,
        addAppsBtn: AppCompatButton
    ) {
        val type = getType(id) ?: return // should never happen

        title.text = options[id]

        iv.setImageDrawable(ContextCompat.getDrawable(requireContext(), getDrawable(type)))

        if (RpnProxyManager.rpnMode().isNone()) {
            statusTv.text = getString(R.string.lbl_disabled)
            statusTv.setTextColor(requireContext(), false)
            infoBtn.setTextColor(requireContext(), false)
            whoTv.text = "--"
            lastRefreshChip.text = getString(R.string.last_refresh_time, "NA")
            return
        }

        var res: Pair<RpnProxyManager.RpnProps?, String?>? = null
        ioCtx {
            res = VpnController.getRpnProps(type)
        }

        val props = res?.first
        // in case of both props and error message are null, show vpn not connected
        val errMsg = res?.second ?: getString(R.string.notif_channel_vpn_failure)

        Logger.vv(LOG_TAG_UI, "$TAG updateProxiesUi $type props: $props, error: $errMsg")
        if (props == null) {
            infoBtn.setTextColor(requireContext(), false)
            statusTv.text = "Not Connected"
            errorTv.text = errMsg ?: "Error"
            statusTv.setTextColor(requireContext(), false)
            whoTv.text = "--"
            lastRefreshChip.text = getString(R.string.last_refresh_time, "NA")
            return
        }
        Logger.vv(LOG_TAG_UI, "$TAG updateProxiesUi $type props: $props, error: $errMsg")
        setProps(type, props)
        setStatus(props.status, statusTv)
        updateLastRefreshTime(type, lastRefreshChip)
        if (props.who.isEmpty()) {
            whoTv.text = "--"
        } else {
            whoTv.text = props.who
        }
        infoBtn.setTextColor(requireContext(), true)
    }

    private fun getProxyId(type: RpnProxyManager.RpnType): String {
        return when (type) {
            RpnProxyManager.RpnType.WIN -> Backend.RpnWin
            RpnProxyManager.RpnType.EXIT -> Backend.Exit // not used in the UI
        }
    }

    private fun updateLastRefreshTime(type: RpnProxyManager.RpnType, lastRefreshTv: Chip) {
        when (type) {
            RpnProxyManager.RpnType.WIN -> {
                val win = RpnProxyManager.getProxy(type)
                lastRefreshTv.text =getString(R.string.last_refresh_time, if (win == null) "NA" else getTime(win.lastRefreshTime))
            }
            RpnProxyManager.RpnType.EXIT -> {} // not used in the UI
        }
    }

    private fun getTime(time: Long): String {
        return Utilities.convertLongToTime(time, Constants.TIME_FORMAT_4)
    }

    private fun setProps(type: RpnProxyManager.RpnType, props: RpnProxyManager.RpnProps) {
        when (type) {
            RpnProxyManager.RpnType.WIN -> winProps = props
            RpnProxyManager.RpnType.EXIT -> {} // not used in the UI
        }
    }

    private fun getType(id: Int): RpnProxyManager.RpnType? {
        return when (id) {
            0 -> RpnProxyManager.RpnType.WIN
            1 -> RpnProxyManager.RpnType.EXIT // not used in the UI
            else -> null
        }
    }

    private fun getDrawable(type: RpnProxyManager.RpnType?): Int {
        return when (type) {
            RpnProxyManager.RpnType.EXIT -> R.drawable.ic_wireguard_icon // not used in the UI
            RpnProxyManager.RpnType.WIN -> R.drawable.ic_wireguard_icon
            null -> R.drawable.ic_wireguard_icon
        }
    }

    private fun setupClickListeners() {

        b.rsOffRl.setOnClickListener {
            val checked = b.rsOffRadio.isChecked
            if (!checked) {
                b.rsOffRadio.isChecked = true
            }
            handleRPlusOff(checked)
        }

        b.rsOffRadio.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            handleRPlusOff(checked)
        }

        b.rsAntiCensorshipRl.setOnClickListener {
            val checked = b.rsAntiCensorshipRadio.isChecked
            if (!checked) {
                b.rsAntiCensorshipRadio.isChecked = true
            }
            handleAntiCensorshipMode(checked)
        }

        b.rsAntiCensorshipRadio.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            handleAntiCensorshipMode(checked)
        }

        b.rsHideIpRl.setOnClickListener {
            val checked = b.rsHideIpRadio.isChecked
            if (!checked) {
                b.rsHideIpRadio.isChecked = true
            }
            handleHideIpMode(checked)
        }

        b.rsHideIpRadio.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            handleHideIpMode(checked)
        }

        b.reportIssueRl.setOnClickListener {
            collectDataForTroubleshoot()
        }

        b.manageSubsRl.setOnClickListener {
            managePlayStoreSubs()
        }

        b.paymentHistoryRl.setOnClickListener {
            openBillingHistory()
        }

        b.pingTestRl.setOnClickListener {
            val intent = Intent(requireContext(), PingTestActivity::class.java)
            startActivity(intent)
        }

        b.addMoreProxies.setOnClickListener {
            handleAddMoreProxies()
        }
    }

    private fun handleAntiCensorshipMode(checked: Boolean) {
        Logger.v(LOG_TAG_UI, "$TAG Anti-censorship mode selected? $checked")
        if (!checked) return

        b.rsHideIpRadio.isChecked = false
        b.rsOffRadio.isChecked = false
        RpnProxyManager.setRpnMode(RpnProxyManager.RpnMode.ANTI_CENSORSHIP)
        reinitiateProxiesUi()
        Logger.i(LOG_TAG_UI, "$TAG Anti-censorship selected, mode: ${RpnProxyManager.rpnMode()}, state: ${persistentState.rpnState}")
    }

    private fun handleHideIpMode(checked: Boolean) {
        Logger.v(LOG_TAG_UI, "$TAG Hide IP mode selected? $checked")
        if (!checked) return

        b.rsAntiCensorshipRadio.isChecked = false
        b.rsOffRadio.isChecked = false
        RpnProxyManager.setRpnMode(RpnProxyManager.RpnMode.HIDE_IP)
        reinitiateProxiesUi()
        Logger.i(LOG_TAG_UI, "$TAG Hide IP selected, mode:  ${RpnProxyManager.rpnMode()}, state: ${persistentState.rpnState}")
    }

    private fun handleRPlusOff(checked: Boolean) {
        Logger.v(LOG_TAG_UI, "$TAG Off mode selected? $checked")
        if (!checked) return

        b.rsHideIpRadio.isChecked = false
        b.rsAntiCensorshipRadio.isChecked = false
        RpnProxyManager.setRpnMode(RpnProxyManager.RpnMode.NONE)
        reinitiateProxiesUi()
        Logger.i(LOG_TAG_UI, "$TAG off mode selected, mode:  ${RpnProxyManager.rpnMode()}, state: ${persistentState.rpnState}")
    }

    private fun handleAddMoreProxies() {
        if (!RpnProxyManager.isRpnEnabled()) {
            showToastUiCentered(requireContext(), "Rethink Plus is not enabled", Toast.LENGTH_SHORT)
            return
        }
        if (!RpnProxyManager.hasValidSubscription()) {
            showToastUiCentered(requireContext(), "No valid subscription found", Toast.LENGTH_SHORT)
            return
        }
        /*
            val winProxyServers = RpnProxyManager.getWinServers()
            if (winProxyServers.isEmpty()) {
            // show dialog to add more proxies
            uiCtx {
                // show toast that issue while retrieving win proxies
                showToastUiCentered(requireContext(),"Issue while retrieving Rpn proxies, please try again later",Toast.LENGTH_SHORT)
            }
            return@io
        }*/
    }

    private fun showInfoDialog(type: RpnProxyManager.RpnType,prop: RpnProxyManager.RpnProps) {
        val title = type.name.uppercase()
        val msg = prop.toString()
        showTroubleshootDialog(title, msg, isInfo = true)
    }

    private fun setStatus(status: Long?, txtView: AppCompatTextView) {
        if (status == null) {
            txtView.text = getString(R.string.lbl_disabled)
            txtView.setTextColor(requireContext(), false)
        } else {
            val statusTxt = UIUtils.getProxyStatusStringRes(status)
            txtView.text = getString(statusTxt).replaceFirstChar(Char::uppercase)
            val isPositive = status == Backend.TUP || status == Backend.TOK
            txtView.setTextColor(requireContext(), isPositive)
        }
    }

    private fun addAnimation() {
        animation =
            RotateAnimation(
                ANIMATION_START_DEGREE,
                ANIMATION_END_DEGREE,
                Animation.RELATIVE_TO_SELF,
                ANIMATION_PIVOT_VALUE,
                Animation.RELATIVE_TO_SELF,
                ANIMATION_PIVOT_VALUE
            )
        animation.repeatCount = ANIMATION_REPEAT_COUNT
        animation.duration = ANIMATION_DURATION
    }

    private fun showTroubleshootDialog(title: String, msg: String, isInfo: Boolean = false) {
        io {
            uiCtx {
                val dialogBinding = DialogInfoRulesLayoutBinding.inflate(layoutInflater)
                val builder =
                    MaterialAlertDialogBuilder(requireContext()).setView(dialogBinding.root)
                val lp = WindowManager.LayoutParams()
                val dialog = builder.create()
                dialog.show()
                lp.copyFrom(dialog.window?.attributes)
                lp.width = WindowManager.LayoutParams.MATCH_PARENT
                lp.height = WindowManager.LayoutParams.WRAP_CONTENT

                dialog.setCancelable(true)
                dialog.window?.attributes = lp

                val heading = dialogBinding.infoRulesDialogRulesTitle
                val cancelBtn = dialogBinding.infoRulesDialogCancelImg
                val okBtn = dialogBinding.infoRulesDialogOkBtn
                val descText = dialogBinding.infoRulesDialogRulesDesc
                dialogBinding.infoRulesDialogRulesIcon.visibility = View.GONE

                heading.text = title
                if (!isInfo) {
                    okBtn.visibility = View.VISIBLE
                    okBtn.text = getString(R.string.about_bug_report_dialog_positive_btn)
                }
                heading.setCompoundDrawablesWithIntrinsicBounds(
                    ContextCompat.getDrawable(requireContext(), R.drawable.ic_rethink_plus),
                    null,
                    null,
                    null
                )

                descText.movementMethod = LinkMovementMethod.getInstance()
                descText.text = msg

                cancelBtn.setOnClickListener { dialog.dismiss() }

                okBtn.setOnClickListener { emailBugReport(msg) }

                dialog.show()
            }
        }
    }

    private fun openBillingHistory() {
        //val link = InAppBillingHandler.HISTORY_LINK
        //openUrl(requireContext(), link)
    }

    private fun managePlayStoreSubs() {
        // Prepare arguments if needed
        val args = Bundle().apply { putString("ARG_KEY", "Launch_Manage_Subscriptions") }

        // Create intent using the helper
        val intent = FragmentHostActivity.createIntent(
            context = requireContext(),
            fragmentClass = ManageSubscriptionFragment::class.java,
            args = args // or null if none
        )

        // Start the activity
        startActivity(intent)
    }

    private fun getFileUri(file: File): Uri? {
        if (file.isFile && file.exists()) {
            return FileProvider.getUriForFile(
                requireContext().applicationContext,
                FILE_PROVIDER_NAME,
                file
            )
        }
        return null
    }

    private fun emailBugReport(msg: String) {
        try {
            // get the rethink.tombstone file
            val tombstoneFile: File? = EnhancedBugReport.getTombstoneZipFile(requireContext())

            // get the bug_report.zip file
            val file = File(getZipFileName(requireContext().filesDir))
            val uri = getFileUri(file) ?: throw Exception("file uri is null")

            // create an intent for sending email with multiple attachments
            val emailIntent = Intent(Intent.ACTION_SEND_MULTIPLE)
            emailIntent.type = "text/plain"
            emailIntent.putExtra(Intent.EXTRA_EMAIL, arrayOf(getString(R.string.about_mail_to)))
            emailIntent.putExtra(
                Intent.EXTRA_SUBJECT,
                getString(R.string.about_mail_plus_bugreport_subject)
            )
            val bugReportText = getString(R.string.about_mail_bugreport_text) + "\n\n" + msg

            val uriList = arrayListOf<Uri>()
            uriList.add(uri)

            // add the tombstone file if it exists
            if (tombstoneFile != null) {
                val tombstoneUri =
                    getFileUri(tombstoneFile) ?: throw Exception("tombstoneUri is null")
                uriList.add(tombstoneUri)
            }
            // add the uri list to the email intent
            emailIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uriList)
            emailIntent.putExtra(Intent.EXTRA_TEXT, bugReportText)

            Logger.i(LOG_TAG_UI, "email with attachment: $uri, ${tombstoneFile?.path}")
            emailIntent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (uriList.isNotEmpty()) {
                val clipData = ClipData.newUri(requireContext().contentResolver, "Logs", uriList[0])
                for (i in 1 until uriList.size) {
                    clipData.addItem(ClipData.Item(uriList[i]))
                }
                emailIntent.clipData = clipData
            }
            Logger.i(LOG_TAG_UI, "email with attachment: $uri, ${tombstoneFile?.path}")
            startActivity(
                Intent.createChooser(
                    emailIntent,
                    getString(R.string.about_mail_bugreport_share_title)
                )
            )
        } catch (e: Exception) {
            showToastUiCentered(
                requireContext(),
                getString(R.string.error_loading_log_file),
                Toast.LENGTH_SHORT
            )
            Logger.e(LOG_TAG_UI, "error sending email: ${e.message}", e)
        }
    }

    private fun collectDataForTroubleshoot() {
        io {
            val title = "Proxy Stats"
            val rpnStats =
                "WARP \n" + warpProps?.toString() + "\n\n" + "Amz \n" + amzProps?.toString() + "\n\n" + "SE \n" + seProps?.toString() + "\n\n" +  "Exit64 \n" + exit64Props?.toString() + "\n\n"
            val stats = rpnStats + VpnController.vpnStats()
            uiCtx {
                showTroubleshootDialog(title, stats)
            }
        }
    }

    private fun AppCompatTextView.setTextColor(context: Context, success: Boolean) {
        this.setTextColor(
            if (success) fetchColor(context, R.attr.chipTextPositive)
            else fetchColor(context, R.attr.chipTextNegative)
        )
    }

    private fun AppCompatButton.setTextColor(context: Context, success: Boolean) {
        this.setTextColor(
            if (success) fetchColor(context, R.attr.secondaryTextColor)
            else fetchColor(context, R.attr.primaryTextColor)
        )
    }

    private suspend fun showConfigCreationError(msg: String) {
        uiCtx { showToastUiCentered(requireContext(), msg, Toast.LENGTH_LONG) }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun ioCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.IO) { f() }
    }

    private fun ui(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.Main) { f() }
    }
}
